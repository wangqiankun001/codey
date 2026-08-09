package com.vanilla.tool.task;

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
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

public class CreateTaskTool implements Tool {

    public static final ObjectMapper OM = new ObjectMapper();

    private static final Random RANDOM = new Random();

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("create_task")
                .description("Create a new task with optional blockedBy dependencies. "
                        + "blockedBy is a list of taskId strings; pass an empty array when no dependencies.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("subject")
                        .addStringProperty("description")
                        .addProperty("blockedBy", JsonArraySchema.builder()
                                .items(JsonStringSchema.builder().build())
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
            return "Error: Create task failed: " + e.getOriginalMessage();
        }
        if (param.subject() == null || param.subject().isBlank()) {
            return "Error: subject cannot be empty.";
        }
        if (param.description() == null || param.description().isBlank()) {
            return "Error: description cannot be empty.";
        }
        List<String> blockedBy = param.blockedBy() == null ? List.of() : param.blockedBy();
        String step = blockedBy.isEmpty() ? "empty"
                : "(" + blockedBy.stream().collect(Collectors.joining(",")) + ")";
        Task task = createAndSaveTask(param.subject(), param.description(), "agent", blockedBy);
        return String.format("Created taskId:%s, subject:%s, blockedBy:%s", task.getId(), task.getSubject(), step);
    }

    public static Task createAndSaveTask(String subject, String description, String owner, List<String> blockedBy) {
        Task task = new Task(
                String.format("task_%d_%04d", System.currentTimeMillis(), RANDOM.nextInt(9999)),
                subject,
                description,
                TaskStatus.PENDING,
                owner,
                blockedBy);
        Task.saveTask(task);
        return task;
    }
}
