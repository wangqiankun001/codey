package com.vanilla.tool;

import java.io.IOException;
import java.nio.file.Files;

import com.vanilla.tool.task.Task;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class GetTaskTool implements Tool {

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("get_task")
                .description("Get full details of a specific task by ID.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("taskId")
                        .required("taskId")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        String taskId = JSONUtil.parseObj(request.arguments()).getStr("taskId");
        try {
            return Files.readString(Task.TASK_DIR.resolve(taskId + ".json"));
        } catch (IOException e) {
            return "task不存在";
        }
    }

}
