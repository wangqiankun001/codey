package com.vanilla;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.vanilla.compactor.BudgetMessageCompactor;
import com.vanilla.compactor.LLMMessageCompactor;
import com.vanilla.compactor.MicoMessageCompactor;
import com.vanilla.compactor.ReactiveMessageCompactor;
import com.vanilla.compactor.SnipMessageCompactor;
import com.vanilla.content.Prompt;
import com.vanilla.hook.HookContext;
import com.vanilla.hook.HookDispatcher;
import com.vanilla.hook.HookEvent;
import com.vanilla.hook.HookResult;
import com.vanilla.memory.MemoryManager;
import com.vanilla.tool.TodoWriteTool;
import com.vanilla.tool.Tool;
import com.vanilla.tool.ToolManager;
import com.vanilla.util.ConsoleRenderer;

import cn.hutool.core.util.StrUtil;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;

public class Codey {

    private final List<ChatMessage> history = new ArrayList<>();
    private final ConsoleRenderer console = new ConsoleRenderer(System.out);
    private final int MAX_REACTIVE_RETRIES = 3;
    private final Terminal terminal;
    private final LineReader lineReader;

    public Codey() {
        // 让子 Agent 等没有直接持有 console 的组件也能拿到同一个渲染器。
        ConsoleRenderer.setShared(console);
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            lineReader = LineReaderBuilder.builder().terminal(terminal).build();
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化终端输入", e);
        }
    }

    private final OpenAiChatModel client = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .baseUrl(System.getenv("OPENAI_BASE_URL"))
            .modelName(System.getenv("OPENAI_MODEL_NAME"))
            .customParameters(Map.of("reasoning_split", true))
            .build();

    private void run() {
        console.printWelcome();
        history.add(SystemMessage.from(SystemMessageBuilder.buildSystemMessage()));

        try {
            while (true) {
                String input;
                try {
                    input = lineReader.readLine(console.promptText());
                } catch (UserInterruptException e) {
                    lineReader.getBuffer().clear();
                    continue;
                } catch (EndOfFileException e) {
                    console.printGoodbye();
                    return;
                }

                if ("exit".equals(input.strip())) {
                    console.printGoodbye();
                    return;
                } else if (StrUtil.isBlankIfStr(input)) {
                    continue;
                }
                HookResult hookResult = HookDispatcher.dispatch(HookEvent.UserPromptSubmit, HookContext.from(input));
                if (!hookResult.continueRun()) {
                    console.printWarning(hookResult.getMsg());
                }
                history.add(UserMessage.from(input));
                console.printThinking();

                try {
                    AiMessage answer = this.agentLoop(history, input);
                    if (answer != null) console.printAiMessage(answer.text());
                } catch (RuntimeException e) {
                    console.printError(readableError(e));
                }
            }
        } finally {
            try {
                terminal.close();
            } catch (IOException ignored) {
                // 进程退出时终端已经由 JLine 恢复；关闭失败不应覆盖原始结果。
            }
        }
    }

    private AiMessage agentLoop(List<ChatMessage> history, String userInput) {
        while (true) {
            BudgetMessageCompactor.toolResultBudget(history);
            SnipMessageCompactor.snipCompact(history);
            MicoMessageCompactor.micoCompact(history);
            LLMMessageCompactor.llmCompact(history, client);
            int retryTimes = 0;
            ChatResponse response;
            while (true) {
                retryTimes++;
                try {
                    response = client.chat(ChatRequest.builder()
                            .toolSpecifications(ToolManager.toolSpecifications())
                            .messages(history)
                            .build());
                    break;
                } catch (Exception e) {
                    if (retryTimes < MAX_REACTIVE_RETRIES) {
                        ReactiveMessageCompactor.reactiveCompact(history, client);
                    } else {
                        console.printError("达到最大尝试次数");
                        return null;
                    }
                }
            }

            AiMessage aiMessage = response.aiMessage();
            history.add(aiMessage);
            if (!FinishReason.TOOL_EXECUTION.equals(response.finishReason())) {
                MemoryManager.extractMemory(history, client);
                MemoryManager.consolidateMemories(client);
                HookDispatcher.dispatch(HookEvent.Stop, HookContext.builder().history(history).build());
                return aiMessage;
            }
            aiMessage.toolExecutionRequests().forEach(toolExeReq -> {
                Tool tool = ToolManager.handler(toolExeReq.name());
                if (tool == null) {
                    String message = "未找到对应工具: " + toolExeReq.name();
                    console.printToolCall(toolExeReq.name(), toolExeReq.arguments());
                    console.printToolResult(toolExeReq.name(), message, 0L, false);
                    history.add(ToolExecutionResultMessage.from(toolExeReq, message));
                    return;
                }

                HookResult result = HookDispatcher.dispatch(HookEvent.PreToolUse,
                        HookContext.builder().userPrompt(userInput).toolUseRequest(List.of(toolExeReq)).build());
                if (!result.continueRun()) {
                    // Hook 已自行渲染拦截卡片，这里不再重复打印。
                    history.add(ToolExecutionResultMessage.from(toolExeReq, result.getMsg()));
                    return;
                }

                console.printToolCall(toolExeReq.name(), toolExeReq.arguments());
                long startMillis = System.currentTimeMillis();
                String toolResult;
                try {
                    toolResult = tool.execute(toolExeReq);
                } catch (RuntimeException e) {
                    toolResult = "Error: tool execution failed: " + readableError(e);
                }
                long elapsedMillis = System.currentTimeMillis() - startMillis;
                boolean success = toolResult != null && !toolResult.startsWith("Error:");
                console.printToolResult(toolExeReq.name(), toolResult, elapsedMillis, success);
                console.printMessageState(history);
                history.add(ToolExecutionResultMessage.from(toolExeReq, toolResult));
                if (!TodoWriteTool.getTodos().isEmpty()) {
                    console.printTodos(TodoWriteTool.getTodos());
                }
            });
        }
    }

    private static String readableError(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "请求 AI 时发生未知错误，请稍后重试。";
        }
        return "请求 AI 失败：" + message;
    }

    public static void main(String[] args) {
        Codey codey = new Codey();
        codey.run();
    }
}
