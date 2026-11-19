# rag-regulation

Question answering over EU financial and data regulation, in English and German, with answers that cite the exact act, article and paragraph they rely on and that decline when the retrieved text does not cover the question.

The corpus is five acts from EUR-Lex. The backend is Java with Spring Boot, Spring AI and PostgreSQL with pgvector. Embeddings and reranking run locally with open models. Answers are generated with the Claude API. A small React app lets you ask a question, read the answer and click a citation to see the source passage.

## What is in the corpus

| Act | CELEX | Articles | Source |
| --- | --- | --- | --- |
| Insurance Distribution Directive (IDD), Directive (EU) 2016/97 | 32016L0097 | 46 | https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016L0097 |
| Solvency II, Directive 2009/138/EC | 32009L0138 | 312 | https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32009L0138 |
| General Data Protection Regulation (GDPR), Regulation (EU) 2016/679 | 32016R0679 | 99 | https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679 |
| Digital Operational Resilience Act (DORA), Regulation (EU) 2022/2554 | 32022R2554 | 64 | https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32022R2554 |
| AI Act, Regulation (EU) 2024/1689 | 32024R1689 | 113 | https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32024R1689 |

Each act is stored in English and German under `corpus/`, together with `corpus/manifest.json`, which records the CELEX number, EUR-Lex URL, download URL, retrieval date and SHA-256 of every file. `scripts/download-corpus.sh` reproduces the download from the Publications Office of the European Union.

These are the texts as originally published in the Official Journal. Later amendments and corrigenda are not applied, so this is not the consolidated law in force. Only the articles are indexed. Recitals and annexes are left out.

### Reuse terms

The texts come from EUR-Lex, https://eur-lex.europa.eu, © European Union, 1998 to 2026. Reuse of EUR-Lex data is authorised for commercial and non-commercial purposes, provided the source is acknowledged, under Commission Decision 2011/833/EU on the reuse of Commission documents. See the EUR-Lex legal notice: https://eur-lex.europa.eu/content/legal-notice/legal-notice.html. Only the versions published in the Official Journal of the European Union are authentic. Nothing in this project is legal advice.

## Architecture

```
corpus/*.html (EUR-Lex XHTML, EN and DE)
   |  EurLexParser: article and paragraph structure from the official markup
   |  Chunker: one chunk per paragraph, long paragraphs split at sentence boundaries
   v
PostgreSQL 17 + pgvector, table "chunk"
   act, celex, lang, article, article_title, paragraph, part, text,
   embedding vector(384)  (HNSW index per language)
   tsv tsvector           (GIN index, English or German configuration)

question
   |  language detection (en or de)
   +--> vector search: multilingual-e5-small embedding, cosine distance, top 30
   +--> keyword search: Postgres full-text index, BM25 ranking, top 30
   |  reciprocal rank fusion (k = 60)
   |  cross-encoder reranking of the fused candidates (mMiniLMv2 multilingual)
   v
top 5 passages, each with act, article and paragraph
   |  Claude: answer only from the passages, mark every claim with a source,
   |  or say that the passages do not cover the question
   v
answer with clickable citations, plus the passages themselves
```

Details that matter:

- **Chunking follows the law's own structure.** The parser reads the article and paragraph ids that EUR-Lex puts in the markup, so every chunk knows its act, article number, article title, paragraph number and language. Lettered points stay with their paragraph. Chunks are capped at 1200 characters.
- **Embeddings are local.** `intfloat/multilingual-e5-small` (MIT licence, 384 dimensions) runs in the JVM through Spring AI's ONNX transformers support. No text leaves the machine for embedding.
- **Hybrid retrieval.** Vector search and keyword search each return 30 candidates in the question's language, and the two lists are merged by reciprocal rank fusion. The keyword side uses the Postgres full-text index (stemming and stopwords for English or German) to find matching passages and ranks them with BM25 computed in SQL, from term statistics stored at ingestion.
- **Reranking is local too.** `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` (Apache 2.0) scores each question and passage pair with ONNX Runtime.
- **Generation.** By default the official Anthropic Java SDK calls Claude with a structured output schema: a flag saying whether the passages answer the question, and the answer text with `[S1]` style source markers. The API turns each marker into a citation such as "GDPR, Article 15(1)" or "DSGVO, Artikel 15 Absatz 1". The model is instructed to decline when the passages do not cover the question. Server-side refusal fallbacks are enabled for the model families that support them.
- **Three retrieval modes** are selectable per request (`vector`, `hybrid`, `hybrid_rerank`) so they can be compared in the evaluation and in the UI.

Code layout:

```
backend/   Spring Boot app (ingest, retrieval, answer, eval, web packages)
frontend/  React + Vite + TypeScript single page app
corpus/    EUR-Lex source files and manifest
eval/      questions.json and measured results
scripts/   corpus download script
```

## Run it

Requirements: Docker with Compose. For answers and the faithfulness evaluation you also need an Anthropic API key. Retrieval works without one.

```bash
cp .env.example .env        # then put your ANTHROPIC_API_KEY in .env
docker compose up -d --build
docker compose run --rm app ingest
```

Then open http://localhost:8080.

The first `ingest` downloads the embedding model (about 470 MB) into a Docker volume, parses the ten files and embeds every chunk on the CPU, which takes around ten minutes on a laptop. The first question in `hybrid_rerank` mode downloads the reranker model (about 470 MB). The first run also fetches about 300 MB of native libraries for the tokenizer library into the same volume. Everything is cached afterwards, and an interrupted model download is resumed. On the development machine the first ingestion in Docker took 29 minutes on a slow connection, most of it downloading; embedding alone took about 11 minutes when run outside Docker.

`.env` is ignored by git. The key is only read from the environment.

### Without Docker for the app

```bash
docker compose up -d db
cd backend
mvn package
java -jar target/rag-regulation.jar ingest
java -jar target/rag-regulation.jar            # API on http://localhost:8080
cd ../frontend && npm install && npm run dev   # UI on http://localhost:5173
```

### Answers without an API key (development)

If the machine has Claude Code installed and logged in, the app can hand generation to the `claude` command line in print mode instead of calling the API. Prompts, sources and the reply schema are the same on both paths. This only works when the app runs from the jar, not inside the Docker image, because the image does not contain Claude Code.

```bash
cd backend
GENERATION_BACKEND=claude-cli java -jar target/rag-regulation.jar
GENERATION_BACKEND=claude-cli java -jar target/rag-regulation.jar eval
```

### API

```bash
curl -s localhost:8080/api/ask -H 'Content-Type: application/json' \
  -d '{"question":"Within what time must a personal data breach be notified to the supervisory authority?","mode":"hybrid_rerank"}'
```

The response contains the answer, whether the model declined, and the retrieved passages with act, article, paragraph, text and EUR-Lex link.

## Evaluation

`eval/questions.json` holds 68 questions written for this project: 60 answerable ones (6 English and 6 German per act) with the gold article or articles that answer them, and 8 questions the corpus does not cover (4 per language), used to check that the system declines.

One command runs everything:

```bash
docker compose run --rm app eval
```

Retrieval metrics only, which needs no API key:

```bash
docker compose run --rm app eval --retrieval-only
```

Results are written to `eval/results/`.

Metrics:

- **Hit rate at 5**: share of answerable questions with at least one chunk from a gold article in the top 5.
- **MRR**: mean reciprocal rank of the first chunk from a gold article, looking at the top 10.
- **Faithfulness**: a model judge splits each answer into claims and checks every claim against the passages the answer was given. The score is the share of supported claims, averaged over answered questions.
- **Answered**: share of answerable questions where the system gave an answer instead of declining.
- **Decline rate on out-of-scope questions**: share of the 8 uncovered questions where the system said the sources do not cover it.

### Results

Measured on 2026-11-18 on the corpus above (4,771 passages), 60 answerable questions, 30 English and 30 German. Reproduce with `docker compose run --rm app eval --retrieval-only`.

| Retrieval mode | Hit rate at 5 | MRR at 10 | Hit at 5, English | Hit at 5, German |
| --- | --- | --- | --- | --- |
| Vector only | 95.0% | 0.866 | 93.3% | 96.7% |
| Hybrid (vector + BM25 full text, RRF) | 96.7% | 0.856 | 96.7% | 96.7% |
| Hybrid + cross-encoder rerank | 98.3% | 0.947 | 100.0% | 96.7% |

In absolute terms that is 57, 58 and 59 of 60 questions with a gold article in the top 5. The per-question ranks are in `eval/results/retrieval.json`.

Repeatability: the corpus was later ingested again inside the Docker container and the retrieval evaluation rerun there (`docker compose run --rm app eval --retrieval-only`). Hybrid (58 of 60, MRR 0.849) and hybrid with reranking (59 of 60, MRR 0.947) came out the same; vector only found 56 of 60 instead of 57 (MRR 0.849 instead of 0.866). The cause of that one-question shift was not isolated. The vector index is approximate and is rebuilt on every ingestion, and the embeddings were computed on a different platform, so either can move a borderline passage across rank 5. The tables in this section are from the first run, which is also the run the answers were generated from.

How to read this:

- With 60 questions, one question is 1.7 points. The step from vector only to hybrid is a single question, and hybrid has a slightly lower MRR, so on this set hybrid alone is not a clear win over vector search.
- Reranking is the change that matters: it moves the first gold passage up (MRR 0.856 to 0.947) and fixes all but one miss.
- The one question every mode misses asks from which date DORA applies (Article 64). The date sits in a final provision with almost no vocabulary in common with the question.

Answers and faithfulness, same run. Answers were generated by `claude-sonnet-5-5` from the top 5 passages and judged by `claude-opus-5-5`, both through the Claude Code backend described above. Reproduce with `GENERATION_BACKEND=claude-cli ANSWER_MODEL=claude-sonnet-5-5 JUDGE_MODEL=claude-opus-5-5 java -jar target/rag-regulation.jar eval`, or with an API key and `docker compose run --rm app eval`.

| Retrieval mode | Faithfulness | Answered, of 60 answerable | Answer cites a gold article | Declined, of 8 out of scope |
| --- | --- | --- | --- | --- |
| Vector only | 0.989 | 86.7% (52) | 86.7% (52) | 100% (8) |
| Hybrid (vector + BM25 full text, RRF) | 0.989 | 88.3% (53) | 88.3% (53) | 100% (8) |
| Hybrid + cross-encoder rerank | 0.996 | 98.3% (59) | 98.3% (59) | 100% (8) |

- **Faithfulness is high in every mode** because the model is told to answer only from the passages. The judge marked 4 of 401 claims as unsupported with vector only, 6 of 411 with hybrid and 2 of 530 with reranking. Every answer and every judged claim is in `eval/results/answers.json`.
- **The modes differ in how often the system can answer at all.** Without reranking the right article is usually in the top 5, but often not the right paragraph, and the model then says the sources do not cover the question instead of guessing: 8 and 7 of the 60 answerable questions were declined. With reranking only one was, the DORA date question that retrieval misses.
- **All 8 out-of-scope questions were declined in every mode.** Eight is a small sample.
- The judge is a model from the same family as the answering model, so treat faithfulness as a consistency check, not as legal review. The answer texts are raw model output and are stored unedited.

One change was made after looking at results on this same question set, and it should be read with that in mind: the first version of the keyword side ranked with Postgres `ts_rank_cd`, which has no inverse document frequency. In a first check that hybrid found a gold article in the top 5 for 52 of 60 questions, worse than vector only at 57. The keyword ranking was replaced by BM25 computed in SQL over the same full-text index (standard parameters k1 = 1.2 and b = 0.75, not tuned), which gives the 58 of 60 above. Nothing else was adjusted against the evaluation set.

What would improve the numbers: a larger embedding model (multilingual-e5-base or large), indexing recitals and definitions as context for short final provisions, query rewriting for date and scope questions, and a bigger question set written by someone other than the author so that small differences become measurable.

Speed on a laptop CPU (Apple M1, no GPU): ingestion of all ten files took 636 seconds, almost all of it embedding. Median retrieval time over 20 questions, once the models are loaded: 24 ms vector only, 36 ms hybrid, 2.4 s hybrid plus reranking of 30 candidates. The reranker is where the time goes.

## Limitations

- Original Official Journal versions only, no consolidated texts, no recitals, no annexes.
- The evaluation set is small (60 answerable questions) and was written by the author of the system, so the numbers are indicative, not a benchmark. Gold labels are at article level, not paragraph level.
- BM25 is computed in plain SQL, which is fine for a few thousand passages. A much larger corpus would want a dedicated BM25 index.
- Language detection is a stopword heuristic for English and German only, and retrieval stays within the detected language.
- Cross references between articles ("as referred to in Article 6") are not followed.
- Faithfulness is judged by a model, not by a lawyer.

## Tests

```bash
cd backend && mvn test
```

The tests parse the real corpus files and check article counts, paragraph structure, English and German alignment, chunk size limits, rank fusion and citation formatting.

## Licences

Code: MIT, see LICENSE. Corpus: EUR-Lex, © European Union, reuse as described above. Models: multilingual-e5-small (MIT), mmarco-mMiniLMv2-L12-H384-v1 (Apache 2.0); both are downloaded at run time and not redistributed here.
