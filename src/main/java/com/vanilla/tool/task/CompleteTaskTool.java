package com.vanilla.tool.task;

import java.util.stream.Collectors;

import com.vanilla.tool.Tool;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class CompleteTaskTool implements Tool {

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("complete_task")
                .description("Complete an in-progress task. Reports unblocked downstream tasks.")
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
            return "Task not exist:" + taskId;
        }
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            return String.format("Task %s is %s, cannot complete", task.getId(), task.getStatus().getValue());
        }
        task.setStatus(TaskStatus.COMPLETED);
        Task.saveTask(task);
        String unblockTaskIds = Task.listTask().stream().filter(t -> {
            return t.getStatus() == TaskStatus.PENDING && t.canStart();
        }).map(Task::getId).collect(Collectors.joining(","));
        if (unblockTaskIds != null && !unblockTaskIds.isBlank()) {
            return String.format("""
                    Completed %s (%s)
                    Unblock: %s
                    """, task.getId(), task.getSubject(), unblockTaskIds);
        }
        return String.format("Completed %s (%s)", task.getId(), task.getSubject());
    }

}
