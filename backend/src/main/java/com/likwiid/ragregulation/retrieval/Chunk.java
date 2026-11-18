package com.likwiid.ragregulation.retrieval;

/** A stored passage as returned by a search, with the score of the stage that ranked it. */
public record Chunk(long id, String act, String celex, String lang, int article, String articleTitle,
                    int paragraph, int part, String text, double score) {

    public Chunk withScore(double newScore) {
        return new Chunk(id, act, celex, lang, article, articleTitle, paragraph, part, text, newScore);
    }
}
