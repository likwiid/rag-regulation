package com.likwiid.ragregulation;

import com.likwiid.ragregulation.retrieval.LanguageDetector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalLogicTest {

    @Test
    void detectsTheQuestionLanguage() {
        assertThat(LanguageDetector.detect("When must a data breach be reported?")).isEqualTo("en");
        assertThat(LanguageDetector.detect("Wann muss eine Datenschutzverletzung gemeldet werden?")).isEqualTo("de");
        assertThat(LanguageDetector.detect("Löschung personenbezogener Daten")).isEqualTo("de");
    }
}
