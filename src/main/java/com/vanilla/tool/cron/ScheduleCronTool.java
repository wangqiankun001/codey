package com.vanilla.tool.cron;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.Codey;
import com.vanilla.tool.Tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class ScheduleCronTool implements Tool {

    private static final Pattern CRON_5_FIELD = Pattern.compile(
            "^\\s*(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s*$");

    private static final ObjectMapper om = new ObjectMapper();

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("schedule_cron")
                .description("Schedule a cron job. cron is 5-field: min hour dom month dow. "
                        + "recurring defaults to true; durable defaults to false.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("cron", "5-field cron expression")
                        .addStringProperty("prompt", "Message to inject when fired")
                        .addBooleanProperty("recurring", "true=recurring, false=one-shot")
                        .addBooleanProperty("durable", "true=persist to disk")
                        .required("cron", "prompt")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        record ScheduleParam(String cron, String prompt, Boolean recurring, Boolean durable) {
        }
        ScheduleParam param;
        try {
            param = om.readValue(request.arguments(), ScheduleParam.class);
        } catch (JsonProcessingException e) {
            return "Error: invalid arguments: " + (e.getOriginalMessage() == null ? e.getMessage() : e.getOriginalMessage());
        }
        if (param == null || param.cron() == null || param.cron().isBlank()) {
            return "Error: cron cannot be empty.";
        }
        if (!CRON_5_FIELD.matcher(param.cron()).matches()) {
            return "Error: cron must be a 5-field expression (min hour dom month dow).";
        }
        if (param.prompt() == null || param.prompt().isBlank()) {
            return "Error: prompt cannot be empty.";
        }
        boolean recurring = param.recurring() == null ? true : param.recurring();
        boolean durable = param.durable() == null ? false : param.durable();
        Path dir = Codey.CONFIG_DIR.resolve("cron");
        try {
            Files.createDirectories(dir);
            String filename = "cron_" + System.currentTimeMillis() + ".json";
            Path path = dir.resolve(filename);
            String json = om.writeValueAsString(new ScheduleParam(
                    param.cron(), param.prompt(), recurring, durable));
            Files.writeString(path, json, StandardCharsets.UTF_8);
            return "Scheduled cron job: id=" + filename
                    + ", cron=" + param.cron()
                    + ", recurring=" + recurring
                    + ", durable=" + durable;
        } catch (IOException e) {
            return "Error: failed to persist cron job: " + e.getMessage();
        }
    }

    /** 暴露 cron 目录给平台级调度器（外部运行器）按需消费。 */
    public static Path cronDir() {
        return Paths.get(System.getProperty("user.dir"), ".codey", "cron");
    }
}
