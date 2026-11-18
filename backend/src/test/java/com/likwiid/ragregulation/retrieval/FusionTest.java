package com.likwiid.ragregulation.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FusionTest {

    private static Chunk chunk(long id) {
        return new Chunk(id, "gdpr", "32016R0679", "en", 1, "", 1, 0, "", 0);
    }

    @Test
    void passageFoundByBothRetrieversOutranksPassagesFoundByOne() {
        List<Chunk> vector = List.of(chunk(1), chunk(2), chunk(3));
        List<Chunk> keyword = List.of(chunk(4), chunk(2), chunk(5));

        List<Chunk> fused = RetrievalService.fuse(List.of(vector, keyword), 60);

        assertThat(fused).extracting(Chunk::id).containsExactly(2L, 1L, 4L, 3L, 5L);
        assertThat(fused.get(0).score()).isEqualTo(2.0 / 62);
    }
}
