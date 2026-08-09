package com.vanilla.tool.cron;

import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Collectors;

import com.vanilla.tool.Tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class ListCronsTool implements Tool {

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("list_crons")
                .description("List all registered cron jobs.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        try (java.util.stream.Stream<java.nio.file.Path> stream = Files.list(ScheduleCronTool.cronDir())) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Error: failed to list cron jobs: " + e.getMessage();
        }
    }
}
