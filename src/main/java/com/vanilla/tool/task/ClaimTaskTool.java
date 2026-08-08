package com.vanilla.tool.task;

import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.tool.Tool;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class ClaimTaskTool implements Tool {

    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("claim_task")
                .description("Claim a pending task. SEts owner, changes status to in_progress.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("taskId")
                        .required("taskId")
                        .build())
                .build();

    }

    @Override
    public String execute(ToolExecutionRequest request) {
        String taskId = JSONUtil.parseObj(request.arguments()).getStr("taskId");
        Task task = Task.loadTask(taskId);
        if (task == null) {
            return "Task not exist: " + taskId;
        }
        if (!task.canStart()) {
            String blockedByTaskId = task.getBlockedBy().stream().map(Task::loadTask)
                    .filter(t -> t.getStatus() != TaskStatus.COMPLETED).map(Task::getId)
                    .collect(Collectors.joining(","));
            return "Blocked by: " + blockedByTaskId;
        }
        task.setOwner("agent");
        task.setStatus(TaskStatus.IN_PROGRESS);
        Task.saveTask(task);
        return taskId;
    }

}
