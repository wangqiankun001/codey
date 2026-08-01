package com.vanilla.memory;

import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryType {
    USER("user"),
    FEEDBACK("feedback"),
    PROJECT("project"),
    REFERENCE("reference");

    @JsonValue
    private final String value;

    public String getValue() {
        return value;
    }

    MemoryType(String value) {
        this.value = value;
    }

    public static MemoryType from(String value) {
        return Stream.of(values()).filter(e -> e.getValue().equalsIgnoreCase(value)).findFirst().orElse(null);
    }
}
