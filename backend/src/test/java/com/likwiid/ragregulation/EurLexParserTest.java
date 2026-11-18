package com.likwiid.ragregulation;

import com.likwiid.ragregulation.ingest.Chunker;
import com.likwiid.ragregulation.ingest.EurLexParser;
import com.likwiid.ragregulation.ingest.Passage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Parses the real corpus files, so a change in the source format shows up here first. */
class EurLexParserTest {

    private static List<EurLexParser.Article> parse(String file, String lang) throws IOException {
        return new EurLexParser().parse(Files.readString(Path.of("..", "corpus", file)), lang);
    }

    @Test
    void findsEveryArticleOfEachAct() throws IOException {
        Map<String, Integer> expected = Map.of("idd", 46, "solvency2", 312, "gdpr", 99, "dora", 64, "ai-act", 113);
        for (Map.Entry<String, Integer> act : expected.entrySet()) {
            for (String lang : List.of("en", "de")) {
                List<EurLexParser.Article> articles = parse(act.getKey() + "." + lang + ".html", lang);
                assertThat(articles).as(act.getKey() + " " + lang).hasSize(act.getValue());
                assertThat(articles).allSatisfy(a -> assertThat(a.paragraphs()).isNotEmpty());
            }
        }
    }

    @Test
    void splitsNumberedParagraphsAndKeepsListItemsOnOneLine() throws IOException {
        EurLexParser.Article article15 = parse("gdpr.en.html", "en").get(14);
        assertThat(article15.number()).isEqualTo(15);
        assertThat(article15.title()).isEqualTo("Right of access by the data subject");
        assertThat(article15.paragraphs().keySet()).containsExactly(1, 2, 3, 4);
        assertThat(article15.paragraphs().get(1)).contains("(a) the purposes of the processing;");
    }

    @Test
    void germanParagraphNumbersMatchTheEnglishOnes() throws IOException {
        List<EurLexParser.Article> en = parse("dora.en.html", "en");
        List<EurLexParser.Article> de = parse("dora.de.html", "de");
        for (int i = 0; i < en.size(); i++) {
            assertThat(de.get(i).paragraphs().keySet()).as("Article " + en.get(i).number())
                    .isEqualTo(en.get(i).paragraphs().keySet());
        }
    }

    @Test
    void articleWithoutNumberedParagraphsBecomesParagraphZero() throws IOException {
        EurLexParser.Article definitions = parse("gdpr.en.html", "en").get(3);
        assertThat(definitions.paragraphs().keySet()).containsExactly(0);
    }

    @Test
    void longParagraphsAreSplitWithinTheLimit() throws IOException {
        List<Passage> passages = new Chunker(1200).chunk("gdpr", "32016R0679", "en", parse("gdpr.en.html", "en"));
        assertThat(passages).allSatisfy(p -> assertThat(p.text().length()).isLessThanOrEqualTo(1200));
        assertThat(passages.stream().filter(p -> p.article() == 4).count()).isGreaterThan(5);
    }
}
