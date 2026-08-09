package com.vanilla.tool.cron;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.vanilla.cron.CronEntry;
import com.vanilla.cron.CronScheduler;
import com.vanilla.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** List every cron job currently loaded by the scheduler. */
public class ListCronsTool implements Tool {
    static final String NAME = "list_crons";

    private static final String DESCRIPTION =
            "List all cron jobs currently loaded by the scheduler. Each item exposes "
                    + "id, cron, prompt, recurring and durable. One-shot jobs that have already "
                    + "fired and were deleted are not included.";

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(NAME)
                .description(DESCRIPTION)
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        // Argument shape doesn't matter for a list-only tool, but parse defensively
        // so an unexpected payload produces a clean error instead of a stack trace.
        if (request.arguments() != null && !request.arguments().isBlank()) {
            try {
                JSONUtil.parseObj(request.arguments());
            } catch (RuntimeException e) {
                return "Error: arguments is not valid JSON: " + e.getMessage();
            }
        }

        Map<String, CronEntry> snapshot = CronScheduler.getInstance().snapshot();

        List<JSONObject> rows = new ArrayList<>();
        // Snapshot iteration order is the HashMap order; sort by id for a stable output.
        snapshot.keySet().stream().sorted().forEach(fileName -> {
            CronEntry e = snapshot.get(fileName);
            if (e == null) return;
            String id = e.getId() != null ? e.getId() : stripCronPrefix(fileName);
            JSONObject obj = JSONUtil.createObj()
                    .set("id", id)
                    .set("cron", e.getCron())
                    .set("prompt", e.getPrompt())
                    .set("recurring", e.isRecurring())
                    .set("durable", e.isDurable());
            rows.add(obj);
        });

        JSONArray arr = JSONUtil.createArray();
        rows.forEach(arr::put);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("jobs", arr);
        return JSONUtil.toJsonPrettyStr(payload);
    }

    /** Best-effort recovery of the id when an entry file is missing the id field. */
    private static String stripCronPrefix(String fileName) {
        String s = fileName;
        if (s.endsWith(".json")) s = s.substring(0, s.length() - 5);
        if (s.startsWith("cron_")) s = s.substring(5);
        return s;
    }
}
