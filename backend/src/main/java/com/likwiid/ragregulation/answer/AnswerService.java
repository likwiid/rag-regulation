package com.likwiid.ragregulation.answer;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.likwiid.ragregulation.config.AppProperties;
import com.likwiid.ragregulation.ingest.Acts;
import com.likwiid.ragregulation.retrieval.Chunk;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks Claude to answer a question from the retrieved passages only, with a marker after each claim. */
@Service
public class AnswerService {

    static final String SYSTEM_PROMPT = """
            You answer questions about EU regulation for compliance and legal professionals. With each question \
            you receive numbered source passages retrieved from the official texts of five acts: the Insurance \
            Distribution Directive, Solvency II, the GDPR, DORA and the AI Act. Readers check every statement \
            against the passage it cites, so an answer that goes beyond the sources is worse than no answer.

            Ground every statement in the passages. Do not add facts from memory, even when you are sure of \
            them, because the reader cannot verify them here. After each statement, put the marker of the \
            passage or passages that support it, in the form [S1] or [S1][S3]. The application turns each \
            marker into the act, article and paragraph of that passage, so the marker is the citation: do not \
            write citations any other way, and only mention an article number when it appears in a passage.

            Retrieval is imperfect, so some passages will be irrelevant: ignore them. If the passages do not \
            contain what is needed to answer, set answerable to false and say in one or two sentences what the \
            sources do not cover; do not fill the gap from general knowledge. If they cover only part of the \
            question, answer that part with markers and say plainly which part is not covered.

            Answer in the language of the question. Lead with the direct answer, then the conditions and \
            exceptions the passages state. Keep it short: a few sentences are usually enough. The passages are \
            the texts as first published in the Official Journal, so describe what they say and do not \
            speculate about later amendments.""";

    private static final Pattern MARKER = Pattern.compile("\\[S(\\d+)]");

    /** The schema of the model reply. */
    public record ModelAnswer(
            @JsonPropertyDescription("True when the passages contain enough to answer at least part of the question. "
                    + "False when they do not cover it.")
            boolean answerable,
            @JsonPropertyDescription("The answer with [S#] markers after each statement, or, when answerable is "
                    + "false, a short note on what the sources do not cover.")
            String answer) {
    }

    public record Answer(boolean refused, String text, List<Integer> citedSources, String model, long inputTokens,
                         long outputTokens) {
    }

    private final AppProperties properties;
    private final ClaudeClient claude;

    public AnswerService(AppProperties properties, ClaudeClient claude) {
        this.properties = properties;
        this.claude = claude;
    }

    public boolean enabled() {
        return claude.enabled();
    }

    public Answer answer(String question, List<Chunk> sources) {
        ClaudeClient.Result<ModelAnswer> result = claude.ask(properties.answerModel(), SYSTEM_PROMPT,
                userMessage(question, sources), ModelAnswer.class);
        ModelAnswer reply = result.value();
        List<Integer> cited = citedSources(reply.answer(), sources.size());
        return new Answer(!reply.answerable(), reply.answer(), cited, result.model(), result.inputTokens(),
                result.outputTokens());
    }

    /** The source numbers (1 based) that the answer text refers to, ignoring markers that do not exist. */
    static List<Integer> citedSources(String text, int sourceCount) {
        TreeSet<Integer> cited = new TreeSet<>();
        Matcher m = MARKER.matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= sourceCount) {
                cited.add(n);
            }
        }
        return List.copyOf(cited);
    }

    public static String sourcesBlock(List<Chunk> sources) {
        StringBuilder sb = new StringBuilder("<sources>\n");
        for (int i = 0; i < sources.size(); i++) {
            Chunk c = sources.get(i);
            sb.append("<source id=\"S").append(i + 1).append("\" citation=\"")
                    .append(Acts.citation(c.act(), c.lang(), c.article(), c.paragraph()))
                    .append("\" article_title=\"").append(c.articleTitle().replace("\"", "'")).append("\">\n")
                    .append(c.text()).append("\n</source>\n");
        }
        return sb.append("</sources>").toString();
    }

    static String userMessage(String question, List<Chunk> sources) {
        return sourcesBlock(sources) + "\n\n<question>\n" + question.strip() + "\n</question>";
    }
}
