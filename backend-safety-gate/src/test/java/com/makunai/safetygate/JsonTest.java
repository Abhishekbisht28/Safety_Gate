package com.makunai.safetygate;

import com.makunai.safetygate.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void parsesSimpleObject() {
        Map<String, Object> result = Json.parseObject("{\"a\": 1, \"b\": \"hello\", \"c\": true, \"d\": null}");
        assertEquals(1.0, result.get("a"));
        assertEquals("hello", result.get("b"));
        assertEquals(Boolean.TRUE, result.get("c"));
        assertNull(result.get("d"));
    }

    @Test
    void parsesNestedObjectsAndArrays() {
        Map<String, Object> result = Json.parseObject(
                "{\"apis\": [{\"method\": \"GET\", \"path\": \"/x\"}]}");
        @SuppressWarnings("unchecked")
        List<Object> apis = (List<Object>) result.get("apis");
        assertEquals(1, apis.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) apis.get(0);
        assertEquals("GET", first.get("method"));
    }

    @Test
    void handlesEscapedStrings() {
        Map<String, Object> result = Json.parseObject("{\"msg\": \"line1\\nline2 \\\"quoted\\\"\"}");
        assertEquals("line1\nline2 \"quoted\"", result.get("msg"));
    }

    @Test
    void roundTripsWriteAndParse() {
        Map<String, Object> original = Map.of("key", "value", "num", 42.0);
        String written = Json.write(original);
        Map<String, Object> reparsed = Json.parseObject(written);
        assertEquals("value", reparsed.get("key"));
        assertEquals(42.0, reparsed.get("num"));
    }

    @Test
    void writesIntegersWithoutDecimalPoint() {
        String written = Json.write(Map.of("count", 800.0));
        assertTrue(written.contains("800") && !written.contains("800.0"));
    }
}
