package com.vanilla.tool.cron;

import com.vanilla.tool.Tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class CancelCronTool implements Tool {

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("cancel_cron")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("jobId")
                        .required("jobId")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'execute'");
    }

}
