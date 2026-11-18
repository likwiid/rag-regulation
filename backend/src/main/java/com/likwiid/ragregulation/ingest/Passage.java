package com.likwiid.ragregulation.ingest;

/**
 * One retrievable unit: a paragraph of an article, or a part of it when the paragraph is long.
 * Paragraph 0 means the article has no numbered paragraphs.
 */
public record Passage(String act, String celex, String lang, int article, String articleTitle, int paragraph,
                      int part, String text) {
}
