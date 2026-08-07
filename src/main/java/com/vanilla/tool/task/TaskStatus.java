package com.vanilla.tool.task;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    @JsonValue
    private final String value;

    private TaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

}
