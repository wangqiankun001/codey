package com.vanilla.tool.cron;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.Codey;
import com.vanilla.cron.CronEntry;
import com.vanilla.cron.CronScheduler;
import com.vanilla.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persist a new cron entry to {@code .codey/crons/} and trigger an immediate scan. */
public class ScheduleCronTool implements Tool {
    static final String NAME = "schedule_cron";

    private static final String DESCRIPTION = "Schedule a cron job. cron is 5-field: min hour dom month dow.";

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(NAME)
                .description(DESCRIPTION)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("cron", "5-field cron expression, e.g. \"0 9 * * *\"")
                        .addStringProperty("prompt", "Text to inject into the agent when the job fires")
                        .addBooleanProperty("recurring", "If true (default) the job keeps firing every match; if false it runs once then is deleted")
                        .addBooleanProperty("durable", "Reserved persistence flag (default false); accepted for forward compatibility")
                        .required("cron", "prompt")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        Map<String, Object> args;
        try {
            args = JSONUtil.parseObj(request.arguments());
        } catch (RuntimeException e) {
            return "Error: arguments is not valid JSON: " + e.getMessage();
        }

        String cron = strArg(args, "cron");
        String prompt = strArg(args, "prompt");
        if (cron == null || cron.isBlank()) {
            return "Error: 'cron' is required";
        }
        if (prompt == null || prompt.isBlank()) {
            return "Error: 'prompt' is required";
        }
        boolean recurring = boolArg(args, "recurring", true);
        boolean durable = boolArg(args, "durable", false);

        String id = newId();
        Path file = fileFor(id);

        CronEntry entry = new CronEntry();
        entry.setId(id);
        entry.setCron(cron);
        entry.setPrompt(prompt);
        entry.setRecurring(recurring);
        entry.setDurable(durable);

        try {
            writeEntry(file, entry);
        } catch (IOException e) {
            return "Error: failed to persist cron job: " + e.getMessage();
        }

        // CronScheduler.getInstance().runScan();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduled", true);
        payload.put("job_id", id);
        payload.put("cron", cron);
        payload.put("recurring", recurring);
        payload.put("durable", durable);
        payload.put("file", file.toString());
        return JSONUtil.toJsonPrettyStr(payload);
    }

    // ---- helpers ------------------------------------------------------------------

    /** Directory used by the scheduler for persisted cron state. */
    public static Path cronDir() {
        return Codey.CONFIG_DIR.resolve("crons");
    }

    /** Build the canonical file name for a given id. */
    public static String fileNameFor(String id) {
        return "cron_" + id + ".json";
    }

    private static Path fileFor(String id) {
        return cronDir().resolve(fileNameFor(id));
    }

    /** Unique enough id: millis + short random suffix to avoid same-millisecond collisions. */
    static String newId() {
        return System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void writeEntry(Path file, CronEntry entry) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ObjectMapper mapper = new ObjectMapper();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, mapper.writeValueAsString(entry), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            // Some Windows / file-system combos don't support ATOMIC_MOVE;
            // fall back to a non-atomic replace so the feature still works.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String strArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean def) {
        Object v = args.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
