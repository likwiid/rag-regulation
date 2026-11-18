package com.likwiid.ragregulation.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the Official Journal XHTML rendition of an act into articles and paragraphs.
 * <p>
 * The format marks every article as {@code <div class="eli-subdivision" id="art_N">} and most numbered
 * paragraphs as {@code <div id="NNN.PPP">}. Where an article numbers its paragraphs without those wrappers,
 * the paragraph number is read from the text ("1." in English, "(1)" in German). Recitals and annexes are
 * not part of the enacting terms and are skipped.
 */
public class EurLexParser {

    private static final Pattern ARTICLE_ID = Pattern.compile("art_(\\d+)");
    private static final Pattern PARAGRAPH_ID = Pattern.compile("\\d{3}\\.(\\d{3})");
    private static final Pattern NUMBER_EN = Pattern.compile("^(\\d{1,3})\\.\\s+\\S");
    private static final Pattern NUMBER_DE = Pattern.compile("^\\((\\d{1,3})\\)\\s+\\S");

    public record Article(int number, String title, Map<Integer, List<String>> paragraphs) {
    }

    public List<Article> parse(String xhtml, String lang) {
        Document doc = Jsoup.parse(xhtml, "", Parser.xmlParser());
        List<Article> articles = new ArrayList<>();
        for (Element div : doc.select("div.eli-subdivision[id^=art_]")) {
            Matcher id = ARTICLE_ID.matcher(div.id());
            if (!id.matches()) {
                continue;
            }
            Element titleEl = div.selectFirst("> div.eli-title");
            String title = titleEl == null ? "" : clean(titleEl.text());
            articles.add(new Article(Integer.parseInt(id.group(1)), title, paragraphs(div, lang)));
        }
        return articles;
    }

    private Map<Integer, List<String>> paragraphs(Element article, String lang) {
        Pattern numbered = "de".equals(lang) ? NUMBER_DE : NUMBER_EN;
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        int current = 0;
        for (Element child : article.children()) {
            if (child.hasClass("oj-ti-art") || child.hasClass("eli-title")) {
                continue;
            }
            Matcher wrapper = PARAGRAPH_ID.matcher(child.id());
            if (child.tagName().equals("div") && wrapper.matches()) {
                current = Integer.parseInt(wrapper.group(1));
                result.computeIfAbsent(current, k -> new ArrayList<>()).addAll(lines(child));
                continue;
            }
            List<String> lines = lines(child);
            if (lines.isEmpty()) {
                continue;
            }
            if (child.tagName().equals("p")) {
                Matcher m = numbered.matcher(lines.get(0));
                // Only accept the next number in sequence, so that numbered list items are not mistaken
                // for paragraphs.
                if (m.find() && Integer.parseInt(m.group(1)) == current + 1) {
                    current = Integer.parseInt(m.group(1));
                }
            }
            result.computeIfAbsent(current, k -> new ArrayList<>()).addAll(lines);
        }
        return result;
    }

    /** Flattens an element into text lines; a table row such as "(a) | text" becomes one line. */
    private List<String> lines(Element el) {
        List<String> out = new ArrayList<>();
        collect(el, out);
        return out;
    }

    private void collect(Element el, List<String> out) {
        switch (el.tagName()) {
            case "p" -> add(out, text(el));
            case "table" -> {
                for (Element row : el.select("> tbody > tr, > tr")) {
                    List<String> cells = new ArrayList<>();
                    for (Element cell : row.select("> td, > th")) {
                        List<String> cellLines = lines(cell);
                        if (!cellLines.isEmpty()) {
                            cells.add(String.join("\n", cellLines));
                        }
                    }
                    add(out, String.join(" ", cells));
                }
            }
            default -> {
                if (el.children().isEmpty()) {
                    add(out, text(el));
                } else {
                    for (Element child : el.children()) {
                        collect(child, out);
                    }
                }
            }
        }
    }

    private static String text(Element el) {
        Element copy = el.clone();
        copy.select(".oj-note-tag, a[href^=#ntr]").remove();
        return clean(copy.text());
    }

    private static void add(List<String> out, String line) {
        if (!line.isBlank()) {
            out.add(line);
        }
    }

    private static String clean(String s) {
        return s.replace(' ', ' ').replaceAll("[ \\t]+", " ").replaceAll(" ?\\n ?", "\n").trim();
    }
}
