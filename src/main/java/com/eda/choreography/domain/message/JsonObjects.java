package com.eda.choreography.domain.message;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON objects in plain Java: a {@code Map<String, Object>} whose values are strings, numbers,
 * booleans, null, lists of values, or nested objects. This is the shape a JSON library reads
 * an object into, so the domain gets JSON without depending on one.
 */
final class JsonObjects {

    private JsonObjects() {
    }

    /**
     * A deep, unmodifiable copy, so a message's content cannot change after it is built.
     * Rejects anything JSON cannot represent, naming where it was found.
     */
    static Map<String, Object> copyOf(Map<String, ?> object) {
        if (object == null) {
            throw new MalformedMessageException("a JSON object is required, got null");
        }
        return copyObject(object, "");
    }

    private static Map<String, Object> copyObject(Map<?, ?> object, String path) {
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new MalformedMessageException(
                        "JSON object keys must be text, found " + entry.getKey() + " at " + describe(path));
            }
            var childPath = path.isEmpty() ? key : path + '.' + key;
            copy.put(key, copyValue(entry.getValue(), childPath));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean || isJsonNumber(value)) {
            return value;
        }
        if (value instanceof Map<?, ?> object) {
            return copyObject(object, path);
        }
        if (value instanceof Collection<?> array) {
            var copy = new ArrayList<>(array.size());
            int index = 0;
            for (var element : array) {
                copy.add(copyValue(element, path + '[' + index++ + ']'));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new MalformedMessageException(
                "not a JSON value at " + describe(path) + ": " + value.getClass().getSimpleName());
    }

    private static boolean isJsonNumber(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof Double || value instanceof Float
                || value instanceof BigDecimal || value instanceof BigInteger;
    }

    private static String describe(String path) {
        return path.isEmpty() ? "the top level" : path;
    }
}
