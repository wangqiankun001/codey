package com.vanilla.tool.cron;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.vanilla.tool.Tool;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class CancelCronTool implements Tool {

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("cancel_cron")
                .description("Cancel a previously scheduled cron job by its jobId.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("jobId", "The cron job filename (e.g. cron_1717000000000.json)")
                        .required("jobId")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        String jobId = JSONUtil.parseObj(request.arguments()).getStr("jobId");
        if (jobId == null || jobId.isBlank()) {
            return "Error: jobId cannot be empty.";
        }
        // 防止路径逃逸：只允许字母数字、点、下划线、连字符。
        if (!jobId.matches("[A-Za-z0-9._-]+")) {
            return "Error: invalid jobId.";
        }
        Path path = ScheduleCronTool.cronDir().resolve(jobId);
        if (!Files.exists(path)) {
            return "Cron job not found: " + jobId;
        }
        try {
            Files.delete(path);
            return "Cancelled cron job: " + jobId;
        } catch (IOException e) {
            return "Error: failed to cancel cron job: " + e.getMessage();
        }
    }
}
