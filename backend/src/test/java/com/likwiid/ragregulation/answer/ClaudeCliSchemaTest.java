package com.likwiid.ragregulation.answer;

import com.likwiid.ragregulation.eval.FaithfulnessJudge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeCliSchemaTest {

    @Test
    @SuppressWarnings("unchecked")
    void describesTheAnswerRecord() {
        Map<String, Object> schema = ClaudeCli.schema(AnswerService.ModelAnswer.class);
        assertEquals("object", schema.get("type"));
        assertEquals(List.of("answerable", "answer"), schema.get("required"));
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schema.get("properties");
        assertEquals("boolean", properties.get("answerable").get("type"));
        assertEquals("string", properties.get("answer").get("type"));
        assertTrue(properties.get("answer").containsKey("description"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void describesNestedLists() {
        Map<String, Object> schema = ClaudeCli.schema(FaithfulnessJudge.Verdict.class);
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schema.get("properties");
        Map<String, Object> items = (Map<String, Object>) properties.get("claims").get("items");
        assertEquals(List.of("claim", "supported"), items.get("required"));
    }
}
