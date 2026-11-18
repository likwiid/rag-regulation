package com.likwiid.ragregulation.retrieval;

import com.likwiid.ragregulation.config.AppProperties;
import com.likwiid.ragregulation.ingest.Vectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RetrievalService {

    private static final String COLUMNS =
            "id, act, celex, lang, article, article_title, paragraph, part, text";

    private static final RowMapper<Chunk> MAPPER = (rs, i) -> new Chunk(rs.getLong("id"), rs.getString("act"),
            rs.getString("celex"), rs.getString("lang"), rs.getInt("article"), rs.getString("article_title"),
            rs.getInt("paragraph"), rs.getInt("part"), rs.getString("text"), rs.getDouble("score"));

    private final AppProperties properties;
    private final EmbeddingModel embeddingModel;
    private final CrossEncoderReranker reranker;
    private final JdbcTemplate jdbc;

    public RetrievalService(AppProperties properties, EmbeddingModel embeddingModel, CrossEncoderReranker reranker,
                            JdbcTemplate jdbc) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.reranker = reranker;
        this.jdbc = jdbc;
    }

    /** Returns the best {@code limit} passages in the given language for the question. */
    public List<Chunk> retrieve(String question, String lang, RetrievalMode mode, int limit) {
        String language = requireLanguage(lang);
        return switch (mode) {
            case VECTOR -> vectorSearch(question, language, limit);
            case HYBRID -> first(hybrid(question, language), limit);
            case HYBRID_RERANK -> first(reranker.rerank(question, hybrid(question, language)), limit);
        };
    }

    List<Chunk> hybrid(String question, String lang) {
        int n = properties.candidates();
        return first(fuse(List.of(vectorSearch(question, lang, n), keywordSearch(question, lang, n)),
                properties.rrfK()), n);
    }

    public List<Chunk> vectorSearch(String question, String lang, int limit) {
        String vector = Vectors.literal(embeddingModel.embed("query: " + question));
        // The language is inlined (after validation) so the planner can pick the partial HNSW index.
        String sql = "SELECT " + COLUMNS + ", 1 - (embedding <=> ?::vector) AS score FROM chunk WHERE lang = '"
                + requireLanguage(lang) + "' ORDER BY embedding <=> ?::vector LIMIT ?";
        return jdbc.query(sql, MAPPER, vector, vector, limit);
    }

    /**
     * Postgres full-text search. The question is turned into an OR query over its stemmed terms, because a
     * natural language question almost never matches a passage on every term.
     */
    public List<Chunk> keywordSearch(String question, String lang, int limit) {
        String config = "de".equals(requireLanguage(lang)) ? "german" : "english";
        String sql = "WITH q AS (SELECT replace(plainto_tsquery('" + config + "', ?)::text, '&', '|')::tsquery AS query) "
                + "SELECT " + COLUMNS + ", ts_rank_cd(tsv, q.query, 1) AS score FROM chunk, q "
                + "WHERE lang = '" + lang + "' AND q.query <> ''::tsquery AND tsv @@ q.query "
                + "ORDER BY score DESC, id LIMIT ?";
        return jdbc.query(sql, MAPPER, question, limit);
    }

    /** Reciprocal rank fusion: each list contributes 1 / (k + rank) for every passage it returns. */
    static List<Chunk> fuse(List<List<Chunk>> rankings, int k) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, Chunk> byId = new LinkedHashMap<>();
        for (List<Chunk> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                Chunk chunk = ranking.get(rank);
                scores.merge(chunk.id(), 1.0 / (k + rank + 1), Double::sum);
                byId.putIfAbsent(chunk.id(), chunk);
            }
        }
        List<Chunk> fused = new ArrayList<>();
        byId.forEach((id, chunk) -> fused.add(chunk.withScore(scores.get(id))));
        fused.sort(Comparator.comparingDouble(Chunk::score).reversed().thenComparingLong(Chunk::id));
        return fused;
    }

    private static List<Chunk> first(List<Chunk> chunks, int limit) {
        return chunks.subList(0, Math.min(limit, chunks.size()));
    }

    private static String requireLanguage(String lang) {
        if (!"en".equals(lang) && !"de".equals(lang)) {
            throw new IllegalArgumentException("Unsupported language " + lang);
        }
        return lang;
    }
}
