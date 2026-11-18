package com.likwiid.ragregulation.answer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerServiceTest {

    @Test
    void readsCitationMarkersAndIgnoresOnesThatDoNotExist() {
        String text = "Notify within 72 hours [S2]. Give reasons for a delay [S2][S1]. See also [S9].";
        assertThat(AnswerService.citedSources(text, 5)).containsExactly(1, 2);
    }
}
