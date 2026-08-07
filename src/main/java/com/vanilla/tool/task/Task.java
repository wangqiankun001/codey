package com.vanilla.tool.task;

import java.util.List;

public record Task(
        String id,
        String subject,
        String description,
        TaskStatus status,
        String owner,
        List<String> blockedBy) {

}
