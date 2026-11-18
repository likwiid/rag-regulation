package com.likwiid.ragregulation.web;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.likwiid.ragregulation.answer.AnswerService;
import com.likwiid.ragregulation.answer.ClaudeClient;
import com.likwiid.ragregulation.config.AppProperties;
import com.likwiid.ragregulation.ingest.Acts;
import com.likwiid.ragregulation.retrieval.Chunk;
import com.likwiid.ragregulation.retrieval.LanguageDetector;
import com.likwiid.ragregulation.retrieval.RetrievalMode;
import com.likwiid.ragregulation.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    private static final int MAX_QUESTION_CHARS = 1000;

    public record AskRequest(String question, String lang, String mode) {
    }

    public record Source(String ref, long id, String act, String actLabel, String citation, String celex,
                         String lang, int article, String articleTitle, int paragraph, int part, String text,
                         String url, double score, boolean cited) {
    }

    /**
     * @param generation "ok", "disabled" (no API key configured) or "error"
     * @param refused    true when the model found the sources insufficient
     */
    public record AskResponse(String question, String lang, String mode, String generation, boolean refused,
                              String answer, String message, List<Source> sources, long retrievalMs,
                              long generationMs) {
    }

    private final AppProperties properties;
    private final RetrievalService retrieval;
    private final AnswerService answers;
    private final JdbcTemplate jdbc;

    public ApiController(AppProperties properties, RetrievalService retrieval, AnswerService answers,
                         JdbcTemplate jdbc) {
        this.properties = properties;
        this.retrieval = retrieval;
        this.answers = answers;
        this.jdbc = jdbc;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        String question = request.question() == null ? "" : request.question().strip();
        if (question.isEmpty() || question.length() > MAX_QUESTION_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The question must have between 1 and " + MAX_QUESTION_CHARS + " characters");
        }
        String lang = "en".equals(request.lang()) || "de".equals(request.lang())
                ? request.lang() : LanguageDetector.detect(question);
        RetrievalMode mode = parseMode(request.mode());

        long t0 = System.currentTimeMillis();
        List<Chunk> chunks = retrieval.retrieve(question, lang, mode, properties.topK());
        long retrievalMs = System.currentTimeMillis() - t0;

        String generation = "disabled";
        String message = "Answer generation is off because no ANTHROPIC_API_KEY is configured. "
                + "The retrieved passages are still shown.";
        String text = null;
        boolean refused = false;
        List<Integer> cited = List.of();
        long t1 = System.currentTimeMillis();
        if (chunks.isEmpty()) {
            message = "The corpus is empty. Run the ingest command first.";
        } else if (answers.enabled()) {
            try {
                AnswerService.Answer answer = answers.answer(question, chunks);
                generation = "ok";
                message = null;
                text = answer.text();
                refused = answer.refused();
                cited = answer.citedSources();
            } catch (ClaudeClient.ModelRefusedException e) {
                generation = "error";
                message = "The model declined to answer this question.";
            } catch (UnauthorizedException e) {
                generation = "error";
                message = "The Anthropic API rejected the configured API key.";
            } catch (RateLimitException e) {
                generation = "error";
                message = "The Anthropic API rate limit was reached. Try again in a moment.";
            } catch (AnthropicServiceException e) {
                log.warn("Anthropic API error {}", e.statusCode(), e);
                generation = "error";
                message = "The Anthropic API returned an error (HTTP " + e.statusCode() + ").";
            } catch (AnthropicIoException e) {
                log.warn("Could not reach the Anthropic API", e);
                generation = "error";
                message = "The Anthropic API could not be reached.";
            }
        }
        long generationMs = System.currentTimeMillis() - t1;

        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            sources.add(new Source("S" + (i + 1), c.id(), c.act(), Acts.byKey(c.act()).label(c.lang()),
                    Acts.citation(c.act(), c.lang(), c.article(), c.paragraph()), c.celex(), c.lang(), c.article(),
                    c.articleTitle(), c.paragraph(), c.part(), c.text(),
                    Acts.eurLexUrl(c.celex(), c.lang(), c.article()), c.score(), cited.contains(i + 1)));
        }
        return new AskResponse(question, lang, mode.name(), generation, refused, text, message, sources,
                retrievalMs, generationMs);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<Map<String, Object>> corpus = jdbc.queryForList(
                "SELECT act, lang, count(DISTINCT article) AS articles, count(*) AS passages "
                        + "FROM chunk GROUP BY act, lang ORDER BY act, lang");
        return Map.of("generationEnabled", answers.enabled(), "answerModel", properties.answerModel(),
                "topK", properties.topK(), "corpus", corpus);
    }

    private static RetrievalMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return RetrievalMode.HYBRID_RERANK;
        }
        try {
            return RetrievalMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown retrieval mode " + mode);
        }
    }
}
