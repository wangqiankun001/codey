package com.vanilla.cron;

import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;

/** Converts fired cron entries into one user message, preserving single-text semantics. */
public final class CronMessageInjector {
    private CronMessageInjector() {}

    public static List<CronEntry> drainEntries() {
        List<CronEntry> result = new ArrayList<>();
        CronScheduler scheduler = CronScheduler.getInstance();
        scheduler.queue().drainTo(result);
        return result;
    }

    public static UserMessage drainDueMessages() {
        List<CronEntry> entries = drainEntries();
        if (entries.isEmpty()) return null;
        String combined = entries.stream()
                .map(entry -> "[Scheduled] " + entry.getPrompt())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return UserMessage.from(combined);
    }
}
