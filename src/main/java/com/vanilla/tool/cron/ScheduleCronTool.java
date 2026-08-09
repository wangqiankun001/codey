package com.vanilla.tool.cron;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.tool.Tool;
import com.vanilla.util.ConsoleRenderer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class ScheduleCronTool implements Tool {

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name("schedule_cron")
                .description("Schedule a cron job. cron is 5-field: min hour dom month dow.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("cron", "5-field cron expression")
                        .addStringProperty("prompt", "Message to inject when fired")
                        .addBooleanProperty("recurring", "true=recurring, false=one-shot")
                        .addBooleanProperty("durable", "true=persist to disk")
                        .required("cron", "prompt")
                        .build())
                .build();
    }

    private final ObjectMapper om = new ObjectMapper();

    @Override
    public String execute(ToolExecutionRequest request) {
        record ScheduleParam(String cron,String prompt,Boolean recurring,Boolean durable){}
        try {
			var param = om.readValue(request.arguments(), ScheduleParam.class);
		} catch (JsonMappingException e) {
            ConsoleRenderer.getShared().printDebug("处理创建定时任务工具调用失败");
			return "参数格式错误";
		} catch (JsonProcessingException e) {
            ConsoleRenderer.getShared().printDebug("处理创建定时任务工具调用失败");
			return "参数处理失败";
		}

        return "";
    }

}
