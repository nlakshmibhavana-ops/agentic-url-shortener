package io.agentflow.llm;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON Schema for a record type: enough for constrained decoding of agent outputs. */
public final class Schemas {

    private Schemas() {
    }

    public static Map<String, Object> of(Class<?> type) {
        return schema(type);
    }

    private static Map<String, Object> schema(Type type) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (type == String.class) {
            s.put("type", "string");
        } else if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            s.put("type", "integer");
        } else if (type == boolean.class || type == Boolean.class) {
            s.put("type", "boolean");
        } else if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
            s.put("type", "array");
            s.put("items", schema(p.getActualTypeArguments()[0]));
        } else if (type instanceof ParameterizedType p && p.getRawType() == Map.class) {
            s.put("type", "object");
            s.put("additionalProperties", schema(p.getActualTypeArguments()[1]));
        } else if (type instanceof Class<?> c && c.isRecord()) {
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent rc : c.getRecordComponents()) {
                props.put(rc.getName(), schema(rc.getGenericType()));
                required.add(rc.getName());
            }
            s.put("type", "object");
            s.put("properties", props);
            s.put("required", required);
            s.put("additionalProperties", false);
        } else {
            throw new IllegalArgumentException("unsupported schema type " + type);
        }
        return s;
    }
}
