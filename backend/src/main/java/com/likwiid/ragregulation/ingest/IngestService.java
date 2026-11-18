package com.likwiid.ragregulation.ingest;

import com.likwiid.ragregulation.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parses the corpus, embeds every passage with the local model and stores it in Postgres. */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final int BATCH = 32;

    public record ManifestEntry(String key, String celex, String title, String lang, String file, String eurlex_url,
                                String download_url, String retrieved, String sha256, long bytes) {
    }

    private final AppProperties properties;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public IngestService(AppProperties properties, EmbeddingModel embeddingModel, JdbcTemplate jdbc) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
    }

    public List<ManifestEntry> manifest() throws IOException {
        Path manifest = Path.of(properties.corpusDir(), "manifest.json");
        return json.readValue(Files.readString(manifest), new TypeReference<>() {
        });
    }

    public List<Passage> parse(ManifestEntry entry) throws IOException {
        String xhtml = Files.readString(Path.of(properties.corpusDir(), entry.file()), StandardCharsets.UTF_8);
        List<EurLexParser.Article> articles = new EurLexParser().parse(xhtml, entry.lang());
        return new Chunker(properties.maxChunkChars()).chunk(entry.key(), entry.celex(), entry.lang(), articles);
    }

    @Transactional
    public void ingestAll() throws IOException {
        long started = System.currentTimeMillis();
        int total = 0;
        for (ManifestEntry entry : manifest()) {
            List<Passage> passages = parse(entry);
            jdbc.update("DELETE FROM chunk WHERE act = ? AND lang = ?", entry.key(), entry.lang());
            for (int from = 0; from < passages.size(); from += BATCH) {
                List<Passage> batch = passages.subList(from, Math.min(from + BATCH, passages.size()));
                store(batch, embeddingModel.embed(batch.stream().map(IngestService::embeddingInput).toList()));
            }
            total += passages.size();
            log.info("Ingested {} ({}): {} passages", entry.key(), entry.lang(), passages.size());
        }
        log.info("Ingestion finished: {} passages in {} s", total, (System.currentTimeMillis() - started) / 1000);
    }

    /** The E5 models expect a "passage: " prefix on documents and "query: " on questions. */
    static String embeddingInput(Passage p) {
        return "passage: " + Acts.citation(p.act(), p.lang(), p.article(), 0) + ". " + p.articleTitle() + "\n"
                + p.text();
    }

    private void store(List<Passage> passages, List<float[]> embeddings) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            rows.add(new Object[]{p.act(), p.celex(), p.lang(), p.article(), p.articleTitle(), p.paragraph(),
                    p.part(), p.text(), Vectors.literal(embeddings.get(i)),
                    "de".equals(p.lang()) ? "german" : "english", p.articleTitle() + "\n" + p.text()});
        }
        jdbc.batchUpdate("""
                INSERT INTO chunk (act, celex, lang, article, article_title, paragraph, part, text, embedding, tsv)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, to_tsvector(?::regconfig, ?))
                """, rows);
    }
}
