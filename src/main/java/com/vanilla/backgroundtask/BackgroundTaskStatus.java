package com.vanilla.backgroundtask;

import com.fasterxml.jackson.annotation.JsonValue;

public enum BackgroundTaskStatus {
    INIT("init"), IN_PROGRESS("in_progress"), COMPLETED("completed"), STOP("stop");

    @JsonValue
    private String value;

    private BackgroundTaskStatus(String value) {
        this.value = value;
    }

}
