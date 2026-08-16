package com.vanilla.tool;

import com.vanilla.inbox.MessageBus;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.internal.chat.Message;

public class CheckInboxTool implements Tool {

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("check_inbox")
                .description("Check Lead's inbox for teammate messages.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
       if (MessageBus.getInstance().peek("lead")) {
            MessageBus.getInstance().readInbox("lead").stream().map(message->{
            });
       }
    }

}
