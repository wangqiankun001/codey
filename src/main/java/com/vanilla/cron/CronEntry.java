package com.vanilla.cron;

import java.util.Objects;

/** A cron entry loaded from one file in .codey/cron. */
public final class CronEntry {
    private String id;
    private String cron;
    private String prompt;
    private boolean recurring = true;
    private boolean durable = false;
    private String lastRunMarker;

    public CronEntry() {
    }

    public CronEntry(String id, String cron, String prompt, boolean recurring, boolean durable) {
        this.id = Objects.requireNonNull(id, "id");
        this.cron = Objects.requireNonNull(cron, "cron");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.recurring = recurring;
        this.durable = durable;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public boolean isRecurring() { return recurring; }
    public void setRecurring(boolean recurring) { this.recurring = recurring; }
    public boolean isDurable() { return durable; }
    public void setDurable(boolean durable) { this.durable = durable; }
    public String getLastRunMarker() { return lastRunMarker; }
    public void setLastRunMarker(String lastRunMarker) { this.lastRunMarker = lastRunMarker; }
}
