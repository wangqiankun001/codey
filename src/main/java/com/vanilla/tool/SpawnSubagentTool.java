package com.vanilla.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;

import com.vanilla.hook.HookContext;
import com.vanilla.hook.HookDispatcher;
import com.vanilla.hook.HookEvent;
import com.vanilla.hook.HookResult;
import com.vanilla.util.ConsoleRenderer;

/**
 * 启动一个独立的子 Agent 来处理复杂子任务。
 *
 * <p>子 Agent 拥有自己的对话历史和模型调用循环，最多重试 {@link Subagent#MAX_CALL} 轮；
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
            return new Subagent(task).run();
        } catch (RuntimeException e) {
            String message = "subagent failed: " + safeMessage(e);
            ConsoleRenderer.getShared().printError(message);
            return "Error: " + message;
        }
    }

    /**
     * 子 Agent 的一次性执行环境：持有任务描述、对话历史和模型客户端。
     */
    public static class Subagent {

        private static final int MAX_CALL = 30;

        private final String task;
        private final List<ChatMessage> history;
        private final ConsoleRenderer console;

        private final OpenAiChatModel client = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .strictJsonSchema(true)
            .baseUrl(System.getenv("OPENAI_BASE_URL"))
            .modelName(System.getenv("OPENAI_MODEL_NAME"))
            .customParameters(Map.of("reasoning_split", true))
            .build();

        public Subagent(String task) {
            this(task, ConsoleRenderer.getShared());
        }

        public Subagent(String task, ConsoleRenderer console) {
            this.task = task;
            this.console = console;
            this.history = new ArrayList<>();
            this.history.add(UserMessage.from(task));
        }

        public String run() {
            long startMillis = System.currentTimeMillis();
            console.printSubagentStart(task);

            AiMessage finalAnswer = null;
            int round = 0;
            for (round = 0; round < MAX_CALL; round++) {
                console.printSubagentRound(round + 1, MAX_CALL);
                final ChatResponse response;
                try {
                    response = client.chat(ChatRequest.builder()
                        .toolSpecifications(ToolManager.subagentToolSpecifications())
                        .messages(history)
                        .build()
                    );
                } catch (RuntimeException e) {
                    console.printError("subagent 调用模型失败：" + safeMessage(e));
                    throw e;
                }
                AiMessage aiMessage = response.aiMessage();
                if (!FinishReason.TOOL_EXECUTION.equals(response.finishReason())) {
                    finalAnswer = aiMessage;
                    break;
                }
                history.add(aiMessage);
                aiMessage.toolExecutionRequests().forEach(toolRequest -> handleToolRequest(toolRequest));
            }
            long elapsedMillis = System.currentTimeMillis() - startMillis;
            if (finalAnswer == null) {
                String error = "Error: subagent stopped after " + MAX_CALL + " turns without a final answer.";
                console.printSubagentDone(false, MAX_CALL, elapsedMillis, error);
                return error;
            }
            String answer = finalAnswer.text();
            console.printSubagentDone(true, round + 1, elapsedMillis, answer);
            return answer == null ? "" : answer;
        }

        private void handleToolRequest(ToolExecutionRequest toolRequest) {
            Tool tool = ToolManager.handler(toolRequest.name());
            if (tool == null) {
                String errorMessage = "Error: unknown tool '" + toolRequest.name() + "'.";
                console.printToolCall(toolRequest.name(), toolRequest.arguments());
                console.printToolResult(toolRequest.name(), errorMessage, 0L, false);
                history.add(ToolExecutionResultMessage.from(toolRequest, errorMessage));
                return;
            }
            HookResult result = HookDispatcher.dispatch(HookEvent.PreToolUse,
                    HookContext.builder()
                            .toolUseRequest(List.of(toolRequest))
                            .build());
            if (!result.continueRun()) {
                // Hook 已自行渲染拦截卡片，这里不再重复打印。
                history.add(ToolExecutionResultMessage.from(toolRequest, result.getMsg()));
                return;
            }
            console.printToolCall(toolRequest.name(), toolRequest.arguments());
            long toolStart = System.currentTimeMillis();
            String output;
            try {
                output = tool.execute(toolRequest);
            } catch (RuntimeException e) {
                output = "Error: tool execution failed: " + safeMessage(e);
            }
            long toolElapsed = System.currentTimeMillis() - toolStart;
            boolean success = output != null && !output.startsWith("Error:");
            console.printToolResult(toolRequest.name(), output, toolElapsed, success);
            history.add(ToolExecutionResultMessage.from(toolRequest, output));
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
