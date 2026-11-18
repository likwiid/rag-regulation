package com.likwiid.ragregulation.retrieval;

import java.util.Locale;
import java.util.Set;

/**
 * Tells English from German by counting very common function words. The corpus has exactly these two
 * languages, so a small word list is enough; English is the default when the signal is tied.
 */
public final class LanguageDetector {

    private static final Set<String> GERMAN = Set.of("der", "die", "das", "und", "ist", "nicht", "ein", "eine",
            "einer", "eines", "einem", "einen", "den", "dem", "des", "mit", "für", "von", "zu", "auf", "im", "wie",
            "was", "wer", "wann", "welche", "welcher", "welches", "welchen", "muss", "müssen", "darf", "dürfen",
            "kann", "können", "sind", "wird", "werden", "bei", "nach", "über", "unter", "oder", "sich", "es",
            "wenn", "gilt", "gelten", "innerhalb", "zur", "zum", "hat", "haben");
    private static final Set<String> ENGLISH = Set.of("the", "and", "is", "are", "not", "a", "an", "of", "to",
            "for", "with", "what", "who", "when", "which", "how", "must", "may", "can", "does", "do", "be", "by",
            "on", "at", "or", "under", "it", "if", "within", "has", "have", "shall", "should", "that", "this");

    private LanguageDetector() {
    }

    public static String detect(String text) {
        int german = 0;
        int english = 0;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (GERMAN.contains(token)) {
                german++;
            }
            if (ENGLISH.contains(token)) {
                english++;
            }
        }
        if (german == english && text.matches(".*[äöüßÄÖÜ].*")) {
            return "de";
        }
        return german > english ? "de" : "en";
    }
}
