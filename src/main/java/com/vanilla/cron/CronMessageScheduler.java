package com.vanilla.cron;

import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.vanilla.Agent;
import com.vanilla.util.ConsoleRenderer;

/**
 * Converts fired cron entries into one user message, preserving single-text
 * semantics.
 */
public final class CronMessageScheduler {

    public static final CronMessageScheduler INSTANCE = new CronMessageScheduler();

    private ExecutorService executorService = Executors.newFixedThreadPool(10);

    private CronMessageScheduler() {
    }

    public static CronMessageScheduler getInstance() {
        return INSTANCE;
    }

    public static List<CronEntry> drainEntries() {
        List<CronEntry> result = new ArrayList<>();
        CronScheduler scheduler = CronScheduler.getInstance();
        scheduler.queue().drainTo(result);
        return result;
    }

    public static UserMessage drainDueMessages() {
        List<CronEntry> entries = drainEntries();
        if (entries.isEmpty())
            return null;
        String combined = entries.stream()
                .map(entry -> "[Scheduled] " + entry.getPrompt())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return UserMessage.from(combined);
    }

    public synchronized void start() {
        Thread taskHandler = new Thread(this::handleFire, "cron-task-handler");
        taskHandler.setDaemon(true);
        taskHandler.start();
    }

    public void handleFire() {
        while (true) {
            UserMessage cronPrompt = drainDueMessages();
            if (cronPrompt != null) {
                ConsoleRenderer.getShared().printDebug("处理定时任务：" + cronPrompt.singleText());
                executorService.execute(() -> {
                    if (cronPrompt.hasSingleText()) {
                        new Agent(cronPrompt.singleText()).run();
                    }
                });
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {

            }
        }
    }

}
