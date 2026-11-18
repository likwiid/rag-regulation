package com.likwiid.ragregulation.eval;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.likwiid.ragregulation.answer.AnswerService;
import com.likwiid.ragregulation.answer.ClaudeClient;
import com.likwiid.ragregulation.config.AppProperties;
import com.likwiid.ragregulation.retrieval.Chunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Model judge for faithfulness: splits an answer into claims and checks each one against the passages
 * the answer was generated from. The score is the share of supported claims.
 */
@Component
public class FaithfulnessJudge {

    static final String SYSTEM_PROMPT = """
            You are checking an answer produced by a question answering system over EU regulation. The system \
            was given numbered source passages and had to answer from them alone, citing passages with markers \
            such as [S2]. Your job is to measure faithfulness: whether each thing the answer asserts is stated \
            in the passages. You are not judging whether the answer is complete, well written or legally right.

            Work through the answer and list every distinct factual claim it makes about the law (obligations, \
            rights, conditions, deadlines, amounts, definitions, who something applies to). Skip sentences that \
            only say the sources do not cover something. For each claim decide whether it is supported: a claim \
            is supported when the passages it cites, or failing that any of the passages, state it or it \
            follows directly from them. A claim that adds a detail the passages do not contain, changes a \
            number or a condition, or relies on outside knowledge is not supported, even if it is true. Judge \
            strictly and use only the passages as evidence.""";

    public record ClaimVerdict(
            @JsonPropertyDescription("The claim, restated briefly") String claim,
            @JsonPropertyDescription("True when the passages state the claim") boolean supported) {
    }

    public record Verdict(List<ClaimVerdict> claims) {

        /** Share of supported claims, or 1 when the answer makes no claims. */
        public double score() {
            if (claims == null || claims.isEmpty()) {
                return 1.0;
            }
            return (double) claims.stream().filter(ClaimVerdict::supported).count() / claims.size();
        }
    }

    private final AppProperties properties;
    private final ClaudeClient claude;

    public FaithfulnessJudge(AppProperties properties, ClaudeClient claude) {
        this.properties = properties;
        this.claude = claude;
    }

    public Verdict judge(String question, List<Chunk> sources, String answer) {
        String user = AnswerService.sourcesBlock(sources) + "\n\n<question>\n" + question + "\n</question>\n\n"
                + "<answer>\n" + answer + "\n</answer>";
        return claude.ask(properties.judgeModel(), SYSTEM_PROMPT, user, Verdict.class).value();
    }
}
