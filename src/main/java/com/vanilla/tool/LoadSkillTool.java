package com.vanilla.tool;

import com.vanilla.skill.SkillManager;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class LoadSkillTool implements Tool {

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("load_skill")
                .description("Load detail of skill by given skill name.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("skillName", "Specific name of skill.")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        return SkillManager.getFullContent(JSONUtil.parseObj(request.arguments()).getStr("skillName"));
    }

}
