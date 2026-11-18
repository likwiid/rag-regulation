package com.likwiid.ragregulation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rag")
public record AppProperties(
        String corpusDir,
        String evalDir,
        String modelsDir,
        String anthropicApiKey,
        String generationBackend,
        String answerModel,
        String judgeModel,
        int topK,
        int candidates,
        int rrfK,
        int maxChunkChars,
        Reranker reranker) {

    public record Reranker(String modelUrl, String tokenizerUrl, int maxLength) {
    }

    /** True when answers come from a local Claude Code login instead of the API. */
    public boolean useClaudeCli() {
        return "claude-cli".equalsIgnoreCase(generationBackend);
    }

    public boolean generationEnabled() {
        return useClaudeCli() || (anthropicApiKey != null && !anthropicApiKey.isBlank());
    }
}
