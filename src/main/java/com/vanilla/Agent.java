package com.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.vanilla.compactor.BudgetMessageCompactor;
import com.vanilla.compactor.LLMMessageCompactor;
import com.vanilla.compactor.MicoMessageCompactor;
import com.vanilla.compactor.SnipMessageCompactor;
import com.vanilla.hook.HookContext;
import com.vanilla.hook.HookDispatcher;
import com.vanilla.tool.Tool;
import com.vanilla.tool.ToolManager;
import com.vanilla.util.ConsoleRenderer;
import com.vanilla.hook.HookResult;
import com.vanilla.hook.HookEvent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;

/**
 * 子 Agent 的一次性执行环境：持有任务描述、对话历史和模型客户端。
 */
public class Agent {

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

    public Agent(String task) {
        this(task, ConsoleRenderer.getShared());
    }

    public Agent(String task, ConsoleRenderer console) {
        this.task = task;
        this.console = console;
        this.history = new ArrayList<>();
        this.history.add(UserMessage.from(task));
    }

    public synchronized String run() {
        long startMillis = System.currentTimeMillis();

        BudgetMessageCompactor.toolResultBudget(history);
        SnipMessageCompactor.snipCompact(history);
        MicoMessageCompactor.micoCompact(history);
        LLMMessageCompactor.llmCompact(history, client);

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
                        .build());
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

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
