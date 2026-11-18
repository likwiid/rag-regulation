package com.likwiid.ragregulation.retrieval;

public enum RetrievalMode {
    /** pgvector cosine similarity only. */
    VECTOR,
    /** Vector and full-text results merged by reciprocal rank fusion. */
    HYBRID,
    /** Hybrid candidates rescored by a cross-encoder. */
    HYBRID_RERANK
}
