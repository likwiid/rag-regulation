package com.likwiid.ragregulation.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns parsed articles into passages. A paragraph is kept whole when it fits; a long paragraph
 * (typically a definitions list) is split at line boundaries, and an oversized single line at
 * sentence boundaries.
 */
public class Chunker {

    private final int maxChars;

    public Chunker(int maxChars) {
        this.maxChars = maxChars;
    }

    public List<Passage> chunk(String act, String celex, String lang, List<EurLexParser.Article> articles) {
        List<Passage> passages = new ArrayList<>();
        for (EurLexParser.Article article : articles) {
            for (Map.Entry<Integer, List<String>> paragraph : article.paragraphs().entrySet()) {
                int part = 0;
                for (String text : split(paragraph.getValue())) {
                    passages.add(new Passage(act, celex, lang, article.number(), article.title(),
                            paragraph.getKey(), part++, text));
                }
            }
        }
        return passages;
    }

    List<String> split(List<String> lines) {
        List<String> units = new ArrayList<>();
        for (String line : lines) {
            if (line.length() <= maxChars) {
                units.add(line);
            } else {
                units.addAll(splitSentences(line));
            }
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() > 0 && current.length() + 1 + unit.length() > maxChars) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private List<String> splitSentences(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : line.split("(?<=[.;:])\\s+")) {
            if (current.length() > 0 && current.length() + 1 + sentence.length() > maxChars) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(sentence);
            // A single sentence longer than the limit is cut hard as a last resort.
            while (current.length() > maxChars) {
                int cut = current.lastIndexOf(" ", maxChars);
                if (cut <= 0) {
                    cut = maxChars;
                }
                out.add(current.substring(0, cut).trim());
                current.delete(0, cut);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().trim());
        }
        return out;
    }
}
