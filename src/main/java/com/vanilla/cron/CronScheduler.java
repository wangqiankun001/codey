package com.vanilla.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.tool.cron.ScheduleCronTool;
import com.vanilla.util.ConsoleRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Scans the tool's cron directory and delivers due entries to a thread-safe queue. */
public final class CronScheduler {
    private static final CronScheduler INSTANCE = new CronScheduler();
    private static final DateTimeFormatter MARKER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CronEntry> entries = new HashMap<>();
    private final BlockingQueue<CronEntry> due = new LinkedBlockingQueue<>();
    private volatile boolean started;
    private Thread worker;

    private CronScheduler() {}
    public static CronScheduler getInstance() { return INSTANCE; }

    public synchronized void start() {
        if (started) return;
        started = true;
        worker = new Thread(this::loop, "cron-scheduler");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean isStarted() { return started; }
    public BlockingQueue<CronEntry> queue() { return due; }

    public synchronized int loadedCount() { return entries.size(); }
    public int queuedCount() { return due.size(); }

    public synchronized Map<String, CronEntry> snapshot() {
        return new HashMap<>(entries);
    }

    private void loop() {
        while (started) {
            try { scanAndFire(); }
            catch (RuntimeException e) { ConsoleRenderer.getShared().printDebug("[cron]", "scheduler error: " + e.getMessage()); }
            try { Thread.sleep(1_000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void scanAndFire() {
        Path dir = ScheduleCronTool.cronDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return;
        }
        Set<String> present = new HashSet<>();
        try (var files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(path -> {
                String id = path.getFileName().toString();
                present.add(id);
                try {
                    CronEntry entry = mapper.readValue(path.toFile(), CronEntry.class);
                    entry.setId(id);
                    synchronized (this) {
                        entries.put(id, entry);
                    }
                    String marker = LocalDateTime.now().format(MARKER);
                    if (CronMatcher.matches(entry.getCron(), LocalDateTime.now())
                            && !marker.equals(entry.getLastRunMarker())) {
                        entry.setLastRunMarker(marker);
                        due.offer(entry);
                        ConsoleRenderer.getShared().printDebug("[cron fire]", id + " -> " + entry.getPrompt());
                        if (!entry.isRecurring()) {
                            synchronized (this) {
                                entries.remove(id);
                            }
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        }
                    }
                } catch (Exception e) {
                    ConsoleRenderer.getShared().printDebug("[cron error]", id + ": " + e.getMessage());
                }
            });
        } catch (IOException ignored) {
        }
        synchronized (this) {
            entries.keySet().removeIf(id -> !present.contains(id));
        }
    }

    public synchronized boolean cancel(String id) {
        entries.remove(id);
        try { return Files.deleteIfExists(ScheduleCronTool.cronDir().resolve(id)); }
        catch (IOException e) { return false; }
    }

    /** Public hook used by tests and operators to trigger an immediate scan. */
    public void runScan() { scanAndFire(); }
}
