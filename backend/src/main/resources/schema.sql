CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chunk (
    id            BIGSERIAL PRIMARY KEY,
    act           TEXT    NOT NULL,
    celex         TEXT    NOT NULL,
    lang          TEXT    NOT NULL CHECK (lang IN ('en', 'de')),
    article       INT     NOT NULL,
    article_title TEXT    NOT NULL,
    paragraph     INT     NOT NULL,
    part          INT     NOT NULL,
    text          TEXT    NOT NULL,
    embedding     vector(384) NOT NULL,
    tsv           tsvector    NOT NULL
);

-- Every query filters on one language, so each language gets its own approximate nearest neighbour index.
CREATE INDEX IF NOT EXISTS chunk_embedding_en ON chunk USING hnsw (embedding vector_cosine_ops) WHERE lang = 'en';
CREATE INDEX IF NOT EXISTS chunk_embedding_de ON chunk USING hnsw (embedding vector_cosine_ops) WHERE lang = 'de';
CREATE INDEX IF NOT EXISTS chunk_tsv ON chunk USING gin (tsv);
CREATE INDEX IF NOT EXISTS chunk_act_article ON chunk (act, lang, article, paragraph, part);
