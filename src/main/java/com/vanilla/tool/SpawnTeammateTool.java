package com.vanilla.tool;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.agent.TeammateAgent;
import com.vanilla.util.ConsoleRenderer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class SpawnTeammateTool implements Tool {

    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("spawn_teammate")
                .description("Spawn a teammate agent in a background thread.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("name")
                        .addStringProperty("role")
                        .addBooleanProperty("prompt")
                        .required("name", "role", "prompt")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        record SpawnTeammateParam(String name, String role, String prompt) {
        }
        SpawnTeammateParam param;
        try {
            param = mapper.readValue(request.arguments(), SpawnTeammateParam.class);
        } catch (JsonProcessingException e) {
            ConsoleRenderer.getShared().printError("Spawn teammate faild, error param:" + request.arguments());
            return "参数错误";
        }
        new TeammateAgent(param.name(), param.role(), param.prompt(), null).start();

        return String.format("Teammate '%s' spawned as %s", param.name(),param.role());
    }

}
