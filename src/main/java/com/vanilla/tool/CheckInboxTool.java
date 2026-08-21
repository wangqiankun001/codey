package com.vanilla.tool;

import com.vanilla.inbox.AgentMessage;
import java.util.List;
import java.util.stream.Collectors;

import com.vanilla.inbox.MessageBus;
import com.vanilla.util.ConsoleRenderer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

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
        ConsoleRenderer.getShared().printDebug("检查信箱");
        if (MessageBus.getInstance().peek("lead")) {
            List<AgentMessage> inbox = MessageBus.getInstance().readInbox("lead");
            if (inbox == null || inbox.size() == 0) {
                return "(inbox empty)";
            }
            return inbox.stream().map(m -> {
                return String.format("[%s] %s", m.fromAgent(), m.content());
            }).collect(Collectors.joining(System.lineSeparator()));
        }
        return "(inbox empty)";
    }

}
