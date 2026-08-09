package com.vanilla.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import com.vanilla.Agent;
import com.vanilla.util.ConsoleRenderer;

/**
 * 启动一个独立的子 Agent 来处理复杂子任务。
 *
 * <p>子 Agent 拥有自己的对话历史和模型调用循环，最多重试 {@link Agent#MAX_CALL} 轮；
 * 当模型返回的 {@code finishReason} 不再是 {@code TOOL_EXECUTION} 时，把最终文本作为
 * 工具结果回传给主 Agent。本工具不会把子 Agent 的中间过程暴露给主 Agent，
 * 但会通过共享的 {@link ConsoleRenderer} 在终端上完整渲染：开始卡片、每一轮进度、
 * 工具调用与结果、以及结束摘要。</p>
 */
public class SpawnSubagentTool implements Tool {

    private static final String TOOL_NAME = "task";

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(TOOL_NAME)
                .description("Launch a subagent to handle a complex subtask. "
                        + "Returns only the final conclusion.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("description",
                                "A self-contained description of the subtask the subagent should perform.")
                        .required("description")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        if (request == null || request.arguments() == null) {
            return "Error: tool execution request or arguments cannot be null.";
        }

        final JSONObject arguments;
        try {
            arguments = JSONUtil.parseObj(request.arguments());
        } catch (RuntimeException e) {
            return "Error: invalid tool arguments: " + safeMessage(e);
        }

        String task = arguments.getStr("description");
        if (task == null || task.isBlank()) {
            return "Error: description cannot be empty.";
        }

        try {
            return new Agent(task).run();
        } catch (RuntimeException e) {
            String message = "subagent failed: " + safeMessage(e);
            ConsoleRenderer.getShared().printError(message);
            return "Error: " + message;
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
