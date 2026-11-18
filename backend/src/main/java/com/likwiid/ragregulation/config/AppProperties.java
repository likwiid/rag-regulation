package com.likwiid.ragregulation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rag")
public record AppProperties(
        String corpusDir,
        String evalDir,
        String modelsDir,
        String anthropicApiKey,
        String answerModel,
        String judgeModel,
        int topK,
        int candidates,
        int rrfK,
        int maxChunkChars,
        Reranker reranker) {

    public record Reranker(String modelUrl, String tokenizerUrl, int maxLength) {
    }

    public boolean generationEnabled() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }
}
