package com.likwiid.ragregulation.ingest;

import java.util.List;
import java.util.Map;

/** The acts in the corpus and how they are named in citations. */
public final class Acts {

    public record Act(String key, String celex, String labelEn, String labelDe) {
        public String label(String lang) {
            return "de".equals(lang) ? labelDe : labelEn;
        }
    }

    public static final List<Act> ALL = List.of(
            new Act("idd", "32016L0097", "IDD", "IDD"),
            new Act("solvency2", "32009L0138", "Solvency II", "Solvabilität II"),
            new Act("gdpr", "32016R0679", "GDPR", "DSGVO"),
            new Act("dora", "32022R2554", "DORA", "DORA"),
            new Act("ai-act", "32024R1689", "AI Act", "KI-Verordnung"));

    private static final Map<String, Act> BY_KEY = Map.of(
            "idd", ALL.get(0), "solvency2", ALL.get(1), "gdpr", ALL.get(2), "dora", ALL.get(3), "ai-act", ALL.get(4));

    private Acts() {
    }

    public static Act byKey(String key) {
        Act act = BY_KEY.get(key);
        if (act == null) {
            throw new IllegalArgumentException("Unknown act " + key);
        }
        return act;
    }

    /** Human readable citation, for example "GDPR, Article 15(1)" or "DSGVO, Artikel 15 Absatz 1". */
    public static String citation(String actKey, String lang, int article, int paragraph) {
        Act act = byKey(actKey);
        if ("de".equals(lang)) {
            return act.labelDe() + ", Artikel " + article + (paragraph > 0 ? " Absatz " + paragraph : "");
        }
        return act.labelEn() + ", Article " + article + (paragraph > 0 ? "(" + paragraph + ")" : "");
    }

    /** Deep link to the article on EUR-Lex. */
    public static String eurLexUrl(String celex, String lang, int article) {
        return "https://eur-lex.europa.eu/legal-content/" + lang.toUpperCase() + "/TXT/HTML/?uri=CELEX:" + celex
                + "#art_" + article;
    }
}
