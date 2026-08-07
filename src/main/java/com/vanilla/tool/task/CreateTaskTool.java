package com.vanilla.tool.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.Codey;
import com.vanilla.tool.Tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class CreateTaskTool implements Tool {

    public static final Path TASK_DIR = Codey.CONFIG_DIR.resolve("task");

    public static final ObjectMapper OM = new ObjectMapper();

    private static final Random RANDOM = new Random();

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("create_task")
                .description("Create a new task with optional blockedBy dependencies.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("subject")
                        .addStringProperty("description")
                        .addProperty("blockedBy", JsonArraySchema.builder()
                                .items(JsonIntegerSchema.builder().build())
                                .build())
                        .required("subject", "description", "blockedBy")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        record CreateTaskParam(String subject, String description, List<String> blockedBy) {
        }
        CreateTaskParam param;
        try {
            param = OM.readValue(request.arguments(), CreateTaskParam.class);
        } catch (JsonProcessingException e) {
            return "Create task failed";
        }
        String step = param.blockedBy().isEmpty() ? "empty"
                : "(" + param.blockedBy().stream().collect(Collectors.joining(",")) + ")";
        Task task = createAndSaveTask(param.subject(), param.description(), "agent", param.blockedBy());
        return String.format("Created taskId:%s, subject:%s, blockedBy:%s", task.id(), task.subject(), step);
    }

    public static Task createAndSaveTask(String subject, String description, String owner, List<String> blockedBy) {
        Task task = new Task(
                String.format("task_%d_%04d", System.currentTimeMillis(), RANDOM.nextInt(9999)),
                subject,
                description,
                TaskStatus.PENDING,
                owner,
                blockedBy);
        saveTask(task);
        return task;
    }

    public static void saveTask(Task task) {
        Path path = TASK_DIR.resolve(task.id() + ".json");
        try {
            Files.createDirectories(path.getParent());
            Files.createFile(path);
            Files.writeString(path, OM.writeValueAsString(task), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("task 写入失败：" + e.getMessage());
        }
    }

}
