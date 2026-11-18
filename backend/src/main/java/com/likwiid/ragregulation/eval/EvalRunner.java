package com.likwiid.ragregulation.eval;

import com.likwiid.ragregulation.answer.AnswerService;
import com.likwiid.ragregulation.answer.ClaudeClient;
import com.likwiid.ragregulation.config.AppProperties;
import com.likwiid.ragregulation.ingest.Acts;
import com.likwiid.ragregulation.retrieval.Chunk;
import com.likwiid.ragregulation.retrieval.RetrievalMode;
import com.likwiid.ragregulation.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Compares the three retrieval modes on the question set in {@code eval/questions.json}.
 * <ul>
 *   <li>Retrieval: hit rate at 5 and mean reciprocal rank at 10 against the gold articles.</li>
 *   <li>Answers (needs an API key): faithfulness scored by a model judge, how often the answer cites a
 *       gold article, and how often out-of-scope questions are declined.</li>
 * </ul>
 * Options: {@code --retrieval-only}, {@code --limit=N}.
 */
@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final int MRR_DEPTH = 10;

    public record Gold(String act, int article) {
    }

    /** @param type "answerable" or "out_of_scope" */
    public record Question(String id, String lang, String type, String question, List<Gold> gold) {
        boolean answerable() {
            return "answerable".equals(type);
        }
    }

    public record Retrieved(String citation, long chunkId, boolean gold) {
    }

    public record RetrievalResult(String id, String lang, String mode, int firstGoldRank, List<Retrieved> top) {
    }

    public record AnswerResult(String id, String lang, String type, String mode, boolean refused, String answer,
                               List<String> cited, boolean citesGold, Double faithfulness,
                               List<FaithfulnessJudge.ClaimVerdict> claims) {
    }

    private record Generated(AnswerService.Answer answer, FaithfulnessJudge.Verdict verdict) {
    }

    private final AppProperties properties;
    private final RetrievalService retrieval;
    private final AnswerService answers;
    private final FaithfulnessJudge judge;
    private final ClaudeClient claude;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public EvalRunner(AppProperties properties, RetrievalService retrieval, AnswerService answers,
                      FaithfulnessJudge judge, ClaudeClient claude) {
        this.properties = properties;
        this.retrieval = retrieval;
        this.answers = answers;
        this.judge = judge;
        this.claude = claude;
    }

    public void run(List<String> options) throws Exception {
        boolean retrievalOnly = options.contains("--retrieval-only");
        int limit = options.stream().filter(o -> o.startsWith("--limit=")).findFirst()
                .map(o -> Integer.parseInt(o.substring("--limit=".length()))).orElse(Integer.MAX_VALUE);
        if (!retrievalOnly && !answers.enabled()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set. Set it, or pass --retrieval-only to score retrieval alone.");
        }

        Path dir = Path.of(properties.evalDir());
        List<Question> questions = json.readValue(Files.readString(dir.resolve("questions.json")),
                new TypeReference<List<Question>>() {
                });
        questions = questions.subList(0, Math.min(limit, questions.size()));
        Path results = dir.resolve("results");
        Files.createDirectories(results);

        // Retrieval for every question and mode. The top 10 feed the rank metric, the top 5 feed the answer.
        Map<String, List<Chunk>> retrieved = new LinkedHashMap<>();
        List<RetrievalResult> retrievalResults = new ArrayList<>();
        for (Question q : questions) {
            for (RetrievalMode mode : RetrievalMode.values()) {
                List<Chunk> top = retrieval.retrieve(q.question(), q.lang(), mode, MRR_DEPTH);
                retrieved.put(key(q, mode), top);
                if (q.answerable()) {
                    int rank = 0;
                    List<Retrieved> rows = new ArrayList<>();
                    for (int i = 0; i < top.size(); i++) {
                        boolean gold = isGold(q, top.get(i));
                        if (gold && rank == 0) {
                            rank = i + 1;
                        }
                        rows.add(new Retrieved(citation(top.get(i)), top.get(i).id(), gold));
                    }
                    retrievalResults.add(new RetrievalResult(q.id(), q.lang(), mode.name(), rank, rows));
                }
            }
        }
        json.writeValue(results.resolve("retrieval.json").toFile(), retrievalResults);
        log.info("Retrieval scored for {} questions", questions.size());

        List<AnswerResult> answerResults = retrievalOnly ? List.of() : generateAndJudge(questions, retrieved);
        if (!retrievalOnly) {
            json.writeValue(results.resolve("answers.json").toFile(), answerResults);
        }

        String summary = summary(questions, retrievalResults, answerResults, retrievalOnly);
        Files.writeString(results.resolve(retrievalOnly ? "summary-retrieval.md" : "summary.md"), summary);
        System.out.println(summary);
    }

    private List<AnswerResult> generateAndJudge(List<Question> questions, Map<String, List<Chunk>> retrieved)
            throws Exception {
        // Two modes often return the same five passages in the same order; that answer is generated once.
        Map<String, Generated> cache = new ConcurrentHashMap<>();
        List<Future<AnswerResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (Question q : questions) {
                for (RetrievalMode mode : RetrievalMode.values()) {
                    List<Chunk> all = retrieved.get(key(q, mode));
                    List<Chunk> sources = all.subList(0, Math.min(properties.topK(), all.size()));
                    futures.add(pool.submit(() -> {
                        String cacheKey = q.id() + sources.stream().map(c -> String.valueOf(c.id()))
                                .collect(Collectors.joining(",", ":", ""));
                        Generated g = cache.computeIfAbsent(cacheKey, k -> {
                            AnswerService.Answer a = answers.answer(q.question(), sources);
                            FaithfulnessJudge.Verdict v = a.refused() ? null
                                    : judge.judge(q.question(), sources, a.text());
                            return new Generated(a, v);
                        });
                        List<Chunk> cited = g.answer().citedSources().stream().map(n -> sources.get(n - 1)).toList();
                        boolean citesGold = !g.answer().refused() && cited.stream().anyMatch(c -> isGold(q, c));
                        return new AnswerResult(q.id(), q.lang(), q.type(), mode.name(), g.answer().refused(),
                                g.answer().text(), cited.stream().map(EvalRunner::citation).toList(), citesGold,
                                g.verdict() == null ? null : g.verdict().score(),
                                g.verdict() == null ? null : g.verdict().claims());
                    }));
                }
            }
            List<AnswerResult> out = new ArrayList<>();
            for (Future<AnswerResult> f : futures) {
                out.add(f.get());
                if (out.size() % 30 == 0) {
                    log.info("Answered and judged {} of {}", out.size(), futures.size());
                }
            }
            log.info("Claude usage for this run: {} input tokens, {} output tokens", claude.totalInputTokens(),
                    claude.totalOutputTokens());
            return out;
        }
    }

    private String summary(List<Question> questions, List<RetrievalResult> retrievalResults,
                           List<AnswerResult> answerResults, boolean retrievalOnly) {
        long answerable = questions.stream().filter(Question::answerable).count();
        long outOfScope = questions.size() - answerable;
        StringBuilder sb = new StringBuilder();
        sb.append("Evaluation run of ").append(LocalDate.now()).append(": ").append(answerable)
                .append(" answerable questions");
        for (String lang : List.of("en", "de")) {
            sb.append(lang.equals("en") ? " (" : ", ").append(questions.stream()
                    .filter(q -> q.answerable() && q.lang().equals(lang)).count()).append(' ').append(lang);
        }
        sb.append(") and ").append(outOfScope).append(" out-of-scope questions.\n\n");

        sb.append("| Retrieval mode | Hit rate at 5 | MRR at 10 | Hit at 5, en | Hit at 5, de |\n");
        sb.append("|---|---|---|---|---|\n");
        for (RetrievalMode mode : RetrievalMode.values()) {
            List<RetrievalResult> rows = retrievalResults.stream().filter(r -> r.mode().equals(mode.name())).toList();
            sb.append("| ").append(label(mode))
                    .append(" | ").append(pct(rate(rows, r -> r.firstGoldRank() >= 1 && r.firstGoldRank() <= 5)))
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", rows.stream()
                            .mapToDouble(r -> r.firstGoldRank() == 0 ? 0 : 1.0 / r.firstGoldRank()).average().orElse(0)))
                    .append(" | ").append(pct(rate(rows.stream().filter(r -> r.lang().equals("en")).toList(),
                            r -> r.firstGoldRank() >= 1 && r.firstGoldRank() <= 5)))
                    .append(" | ").append(pct(rate(rows.stream().filter(r -> r.lang().equals("de")).toList(),
                            r -> r.firstGoldRank() >= 1 && r.firstGoldRank() <= 5)))
                    .append(" |\n");
        }
        if (retrievalOnly) {
            return sb.toString();
        }

        sb.append("\nAnswers generated by ").append(properties.answerModel()).append(" from the top ")
                .append(properties.topK()).append(" passages, judged by ").append(properties.judgeModel())
                .append(".\n\n");
        sb.append("| Retrieval mode | Faithfulness | Answered, of answerable | Answer cites a gold article "
                + "| Declined, of out of scope |\n");
        sb.append("|---|---|---|---|---|\n");
        for (RetrievalMode mode : RetrievalMode.values()) {
            List<AnswerResult> rows = answerResults.stream().filter(r -> r.mode().equals(mode.name())).toList();
            List<AnswerResult> inScope = rows.stream().filter(r -> r.type().equals("answerable")).toList();
            List<AnswerResult> out = rows.stream().filter(r -> !r.type().equals("answerable")).toList();
            List<AnswerResult> judged = inScope.stream().filter(r -> r.faithfulness() != null).toList();
            sb.append("| ").append(label(mode))
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f",
                            judged.stream().mapToDouble(AnswerResult::faithfulness).average().orElse(Double.NaN)))
                    .append(" | ").append(pct(rate(inScope, r -> !r.refused())))
                    .append(" | ").append(pct(rate(inScope, AnswerResult::citesGold)))
                    .append(" | ").append(pct(rate(out, AnswerResult::refused)))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static <T> double rate(List<T> rows, Predicate<T> predicate) {
        return rows.isEmpty() ? Double.NaN : (double) rows.stream().filter(predicate).count() / rows.size();
    }

    private static String pct(double value) {
        return Double.isNaN(value) ? "n/a" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String label(RetrievalMode mode) {
        return switch (mode) {
            case VECTOR -> "Vector only";
            case HYBRID -> "Hybrid (vector + full text, RRF)";
            case HYBRID_RERANK -> "Hybrid + cross-encoder rerank";
        };
    }

    private static boolean isGold(Question q, Chunk c) {
        return q.gold() != null && q.gold().stream().anyMatch(g -> g.act().equals(c.act()) && g.article() == c.article());
    }

    private static String citation(Chunk c) {
        return Acts.citation(c.act(), c.lang(), c.article(), c.paragraph());
    }

    private static String key(Question q, RetrievalMode mode) {
        return q.id() + "|" + mode;
    }
}
