package com.eda.choreography.domain.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonObjectsTest {

    @Test
    void copiesEveryJsonValueKind() {
        var original = new HashMap<String, Object>();
        original.put("text", "HIGH");
        original.put("count", 3);
        original.put("big", 12_000_000_000L);
        original.put("price", new BigDecimal("9.99"));
        original.put("ratio", 0.5);
        original.put("flag", true);
        original.put("missing", null);
        original.put("items", List.of("a", "b"));
        original.put("nested", Map.of("risk", "LOW"));

        assertThat(JsonObjects.copyOf(original)).isEqualTo(original);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCopyIsDeeplyImmutableAndDetachedFromTheOriginal() {
        var items = new ArrayList<Object>(List.of("a"));
        var nested = new HashMap<String, Object>(Map.of("risk", "LOW"));
        var original = new HashMap<String, Object>(Map.of("items", items, "nested", nested));

        var copy = JsonObjects.copyOf(original);
        items.add("b");
        nested.put("risk", "HIGH");

        assertThat(copy).isEqualTo(Map.of("items", List.of("a"), "nested", Map.of("risk", "LOW")));
        assertThatThrownBy(() -> copy.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) copy.get("items")).add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<String, Object>) copy.get("nested")).put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAValueJsonCannotRepresentAndSaysWhere() {
        var original = Map.<String, Object>of("order", Map.of("placed", LocalDate.of(2026, 10, 4)));

        assertThatThrownBy(() -> JsonObjects.copyOf(original))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("order.placed")
                .hasMessageContaining("LocalDate");
    }

    @Test
    void rejectsANonTextKey() {
        Map<Object, Object> raw = new HashMap<>(Map.of(1, "one"));

        assertThatThrownBy(() -> JsonObjects.copyOf(Map.of("nested", raw)))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("nested");
    }

    @Test
    void rejectsAMissingObject() {
        assertThatThrownBy(() -> JsonObjects.copyOf(null)).isInstanceOf(MalformedMessageException.class);
    }
}
