package com.vanilla.tool.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.vanilla.tool.Tool;
import com.vanilla.util.ConsoleRenderer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class ListTasksTool implements Tool {

    private static ConsoleRenderer consoleRenderer = ConsoleRenderer.getShared();

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("list_tasks")
                .description("List all tasks with status,owner,and dependencies.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        List<String> tasks = new ArrayList<>();
        try {
            Files.list(Task.TASK_DIR).forEach(f -> {
                try {
                    tasks.add(Files.readString(f, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    consoleRenderer.printDebug("读取任务列表失败:" + f.getFileName().toString());
                }
            });
        } catch (IOException e) {
            consoleRenderer.printDebug("读取任务列表失败");
            return "<empty>";
        }
        return tasks.stream().collect(Collectors.joining("\n"));
    }

}
