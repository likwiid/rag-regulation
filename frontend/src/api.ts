export type RetrievalMode = "VECTOR" | "HYBRID" | "HYBRID_RERANK";

export interface Source {
  ref: string;
  id: number;
  act: string;
  actLabel: string;
  citation: string;
  celex: string;
  lang: string;
  article: number;
  articleTitle: string;
  paragraph: number;
  part: number;
  text: string;
  url: string;
  score: number;
  cited: boolean;
}

export interface AskResponse {
  question: string;
  lang: string;
  mode: RetrievalMode;
  generation: "ok" | "disabled" | "error";
  refused: boolean;
  answer: string | null;
  message: string | null;
  sources: Source[];
  retrievalMs: number;
  generationMs: number;
}

export interface CorpusRow {
  act: string;
  lang: string;
  articles: number;
  passages: number;
}

export interface Status {
  generationEnabled: boolean;
  answerModel: string;
  topK: number;
  corpus: CorpusRow[];
}

export async function ask(question: string, lang: string, mode: RetrievalMode): Promise<AskResponse> {
  const response = await fetch("/api/ask", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question, lang, mode }),
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.detail ?? body?.message ?? `Request failed with status ${response.status}`);
  }
  return response.json();
}

export async function status(): Promise<Status> {
  const response = await fetch("/api/status");
  if (!response.ok) {
    throw new Error(`Status request failed with status ${response.status}`);
  }
  return response.json();
}
