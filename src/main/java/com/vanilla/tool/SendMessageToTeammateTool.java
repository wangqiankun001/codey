package com.vanilla.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.inbox.AgentMsgType;
import com.vanilla.inbox.MessageBus;
import com.vanilla.util.ConsoleRenderer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class SendMessageToTeammateTool implements Tool {

    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("send_message")
                .description("Send a message to a teammate via MessageBus")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("from")
                        .addStringProperty("to")
                        .addStringProperty("content")
                        .required("from", "to", "content")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        record SendMessageParam(String to, String from, String content) {
        }
        SendMessageParam param;
        try {
            param = mapper.readValue(request.arguments(), SendMessageParam.class);
        } catch (JsonProcessingException e) {
            ConsoleRenderer.getShared()
                    .printError("Send message to teammate failed, error param:" + request.arguments());
            return "参数格式不正确";
        }
        MessageBus.getInstance().send(param.from(), param.to(), param.content(), AgentMsgType.MESSAGE);

        return "Message send success.";
    }

}
