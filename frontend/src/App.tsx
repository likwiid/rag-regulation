import { FormEvent, Fragment, useEffect, useRef, useState } from "react";
import { ask, AskResponse, RetrievalMode, Source, status, Status } from "./api";

const EXAMPLES = [
  "Within what time must a personal data breach be reported to the supervisory authority?",
  "Which AI practices are prohibited?",
  "Welche Informationen muss ein Versicherungsvermittler dem Kunden vor Vertragsabschluss geben?",
  "Wie hoch ist das Konfidenzniveau für die Berechnung der Solvenzkapitalanforderung?",
  "What must a financial entity include in its register of ICT third-party arrangements?",
];

const MODES: { value: RetrievalMode; label: string }[] = [
  { value: "HYBRID_RERANK", label: "Hybrid + rerank" },
  { value: "HYBRID", label: "Hybrid" },
  { value: "VECTOR", label: "Vector only" },
];

/** Splits the answer on [S1] style markers and renders each marker as a citation button. */
function AnswerText(props: { text: string; sources: Source[]; onCite: (ref: string) => void; active: string | null }) {
  const byRef = new Map(props.sources.map((s) => [s.ref, s]));
  const parts = props.text.split(/(\[S\d+\])/g);
  return (
    <p className="answer-text">
      {parts.map((part, i) => {
        const match = /^\[(S\d+)\]$/.exec(part);
        const source = match ? byRef.get(match[1]) : undefined;
        if (!source) {
          return <Fragment key={i}>{part}</Fragment>;
        }
        return (
          <button
            key={i}
            type="button"
            className={"cite" + (props.active === source.ref ? " cite-active" : "")}
            onClick={() => props.onCite(source.ref)}
            title="Show the source passage"
          >
            {source.citation}
          </button>
        );
      })}
    </p>
  );
}

export default function App() {
  const [question, setQuestion] = useState("");
  const [lang, setLang] = useState("auto");
  const [mode, setMode] = useState<RetrievalMode>("HYBRID_RERANK");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<AskResponse | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const [info, setInfo] = useState<Status | null>(null);
  const sourceRefs = useRef(new Map<string, HTMLElement>());

  useEffect(() => {
    status().then(setInfo).catch(() => setInfo(null));
  }, []);

  async function submit(text: string) {
    const trimmed = text.trim();
    if (!trimmed || loading) {
      return;
    }
    setLoading(true);
    setError(null);
    setActive(null);
    try {
      setResult(await ask(trimmed, lang, mode));
    } catch (e) {
      setResult(null);
      setError(e instanceof Error ? e.message : "The request failed");
    } finally {
      setLoading(false);
    }
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    void submit(question);
  }

  function showSource(ref: string) {
    setActive(ref);
    sourceRefs.current.get(ref)?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }

  const passages = info?.corpus.reduce((sum, row) => sum + Number(row.passages), 0) ?? 0;

  return (
    <div className="page">
      <header>
        <h1>EU Regulation Q&amp;A</h1>
        <p className="subtitle">
          Answers grounded in the official texts of the IDD, Solvency II, the GDPR, DORA and the AI Act, in English
          and German. Every statement links to the article and paragraph it comes from.
        </p>
      </header>

      <form onSubmit={onSubmit} className="ask">
        <textarea
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void submit(question);
            }
          }}
          placeholder="Ask a question in English or German"
          rows={3}
          maxLength={1000}
          aria-label="Question"
        />
        <div className="controls">
          <label>
            Sources in
            <select value={lang} onChange={(e) => setLang(e.target.value)}>
              <option value="auto">Language of the question</option>
              <option value="en">English</option>
              <option value="de">German</option>
            </select>
          </label>
          <label>
            Retrieval
            <select value={mode} onChange={(e) => setMode(e.target.value as RetrievalMode)}>
              {MODES.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </label>
          <button type="submit" className="primary" disabled={loading || !question.trim()}>
            {loading ? "Searching..." : "Ask"}
          </button>
        </div>
        <div className="examples">
          {EXAMPLES.map((example) => (
            <button
              key={example}
              type="button"
              className="example"
              onClick={() => {
                setQuestion(example);
                void submit(example);
              }}
            >
              {example}
            </button>
          ))}
        </div>
      </form>

      {info && passages === 0 && (
        <p className="notice">The corpus is empty. Run the ingest command from the README first.</p>
      )}
      {error && <p className="notice notice-error">{error}</p>}

      {result && (
        <main className="result">
          <section className="answer">
            <h2>Answer</h2>
            {result.message && (
              <p className={"notice" + (result.generation === "error" ? " notice-error" : "")}>{result.message}</p>
            )}
            {result.answer && result.refused && (
              <p className="refused-label">The sources do not cover this question</p>
            )}
            {result.answer && (
              <AnswerText text={result.answer} sources={result.sources} onCite={showSource} active={active} />
            )}
            <p className="meta">
              Retrieval {result.retrievalMs} ms
              {result.generation === "ok" && <>, generation {result.generationMs} ms</>}, sources in{" "}
              {result.lang === "de" ? "German" : "English"}
            </p>
          </section>

          <section className="sources">
            <h2>Retrieved passages</h2>
            {result.sources.map((source) => (
              <article
                key={source.ref}
                ref={(el) => {
                  if (el) {
                    sourceRefs.current.set(source.ref, el);
                  } else {
                    sourceRefs.current.delete(source.ref);
                  }
                }}
                className={"source" + (active === source.ref ? " source-active" : "")}
                onClick={() => setActive(source.ref)}
              >
                <div className="source-head">
                  <span className="source-citation">{source.citation}</span>
                  {source.cited && <span className="badge">cited</span>}
                </div>
                <div className="source-title">{source.articleTitle}</div>
                <p className="source-text">{source.text}</p>
                <a href={source.url} target="_blank" rel="noreferrer">
                  Open this article on EUR-Lex
                </a>
              </article>
            ))}
          </section>
        </main>
      )}

      <footer>
        {info && (
          <span>
            {passages} passages indexed. Generation {info.generationEnabled ? `by ${info.answerModel}` : "is off"}.{" "}
          </span>
        )}
        Source: EUR-Lex, © European Union. Texts as first published in the Official Journal, without later
        amendments. This is a demonstration, not legal advice.
      </footer>
    </div>
  );
}
