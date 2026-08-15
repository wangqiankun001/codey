package com.vanilla;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import com.vanilla.backgroundtask.BackgroundTask;
import com.vanilla.backgroundtask.BackgroundTaskUtil;
import com.vanilla.cron.CronMessageScheduler;
import com.vanilla.cron.CronScheduler;
import com.vanilla.compactor.BudgetMessageCompactor;
import com.vanilla.compactor.LLMMessageCompactor;
import com.vanilla.compactor.MicoMessageCompactor;
import com.vanilla.compactor.ReactiveMessageCompactor;
import com.vanilla.compactor.SnipMessageCompactor;
import com.vanilla.hook.HookContext;
import com.vanilla.hook.HookDispatcher;
import com.vanilla.hook.HookEvent;
import com.vanilla.hook.HookResult;
import com.vanilla.inbox.AgentMessage;
import com.vanilla.inbox.MessageBus;
import com.vanilla.memory.MemoryManager;
import com.vanilla.prompt.SystemMessageBuilder;
import com.vanilla.tool.TodoWriteTool;
import com.vanilla.tool.Tool;
import com.vanilla.tool.ToolManager;
import com.vanilla.util.ChatMessageJsonConvertor;
import com.vanilla.util.ConsoleRenderer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;

public class Codey {

    public static final Path WORKSPACE = Paths.get(System.getProperty("user.dir"));

    public static final Path CONFIG_DIR = WORKSPACE.resolve(".codey");

    private final List<ChatMessage> history = new ArrayList<>();
    private final ConsoleRenderer console = new ConsoleRenderer(System.out);
    private final int MAX_REACTIVE_RETRIES = 3;
    private final Terminal terminal;
    private final LineReader lineReader;
    private final Thread inputReaderThread;
    private final Thread inboxPoller;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();


    private static record Event(String kind,String payload) {
    }

    public Codey() {
        // 让子 Agent 等没有直接持有 console 的组件也能拿到同一个渲染器。
        ConsoleRenderer.setShared(console);
        CronScheduler.getInstance().start();
        CronMessageScheduler.getInstance().start();
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            lineReader = LineReaderBuilder.builder().terminal(terminal).build();
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化终端输入", e);
        }
        inputReaderThread = new Thread(() -> readInput(),"user-input-reader");
        inputReaderThread.setDaemon(true);
        inputReaderThread.start();
        inboxPoller = new Thread(() -> pollInbox(),"inbox-message-poller");
        inboxPoller.setDaemon(true);
        inboxPoller.start();
    }

    private final OpenAiChatModel client = OpenAiChatModel.builder()
            .apiKey(requireEnv("OPENAI_API_KEY"))
            .strictJsonSchema(true)
            .baseUrl(requireEnv("OPENAI_BASE_URL"))
            .modelName(requireEnv("OPENAI_MODEL_NAME"))
            .customParameters(Map.of("reasoning_split", true))
            .build();

    /**
     * 启动时强制要求关键环境变量齐全，否则后续跑到 BOM 阶段才会报错，
     * 既难定位，又会白白把首条用户消息写进历史。
     */
    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必需的环境变量: " + key);
        }
        return value;
    }

    private void readInput() {
        while (true) {
            String input;
            try {
                input = lineReader.readLine(console.promptText());
            } catch (UserInterruptException e) {
                lineReader.getBuffer().clear();
                continue;
            } catch (EndOfFileException e) {
                console.printGoodbye();
                events.offer(new Event("quit", null));
                return;
            }
            if (StrUtil.isBlankIfStr(input)) {
                continue;
            }
            events.offer(new Event("user", input));
        }
    }

    private void pollInbox(){
        while (true) {
            if (MessageBus.getInstance().peek("lead") || BackgroundTaskUtil.hasCompleted()) {
                events.offer(new Event("wake", null));
            }
            try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
        }
    }

    private void run() {
        console.printWelcome();
        history.add(SystemMessage.from(SystemMessageBuilder.getSystemPrompt()));

        try {
            while (true) {
                Event event;
				try {
					event = events.take();
				} catch (InterruptedException e) {
                    return;
				}
                String kind = event.kind().strip();

                if ("exit".equals(kind)) {
                    console.printGoodbye();
                    return;
                } else if("user".equals(kind)) {
                    history.add(UserMessage.from(event.payload()));
                } else if ("wake".equals(kind)) {
                    List<String> messageAndTaskResult = new ArrayList<>();
                    List<AgentMessage> inbox = MessageBus.getInstance().readInbox("lead");
                    messageAndTaskResult.addAll(inbox.stream().map(message -> {
                        return String.format("""
                                [Inbox]
                                From %s: %s
                                """, message.fromAgent(), message.content());
                    }).toList());
                    messageAndTaskResult.addAll(BackgroundTaskUtil.collectBackgroundTaskResultStr());
                    history.add(UserMessage.from(messageAndTaskResult.stream().collect(Collectors.joining("\n"))));
                }
                HookResult hookResult = HookDispatcher.dispatch(HookEvent.UserPromptSubmit, HookContext.from(event.payload()));
                if (!hookResult.continueRun()) {
                    console.printWarning(hookResult.getMsg());
                }
                console.printThinking();
                try {
                    AiMessage answer = this.agentLoop(history, event.payload());
                    if (answer != null) console.printAiMessage(answer.text());
                } catch (RuntimeException e) {
                    console.printError(readableError(e,history));
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

    private synchronized AiMessage agentLoop(List<ChatMessage> history, String userInput) {
        // 在压缩/记忆注入之前先冻结本次会话的原始快照，压缩会改写 history，
        // 若不预先快照，MemoryManager.extractMemory 会把压缩后的版本误当原始对话。
        List<ChatMessage> preCompact = List.of(history.toArray(ChatMessage[]::new));
        MemoryManager.injectRelevantMemory(history,client);

        while (true) {
            history.set(0, SystemMessage.from(SystemMessageBuilder.getSystemPrompt()));
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
                MemoryManager.extractMemory(preCompact, client);
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

                if (BackgroundTaskUtil.shouldRunBackground(toolExeReq)) {
                    String backgroundTaskId = BackgroundTaskUtil.startBackgroundTask(toolExeReq);
                    history.add(ToolExecutionResultMessage.from(toolExeReq, backgroundTaskId).toBuilder()
                            .contents(TextContent.from(String.format("""
                                    [Background task %s started]
                                    Command: %s.
                                    Result will be available when complete.
                                    """, backgroundTaskId,
                                    JSONUtil.parseObj(toolExeReq.arguments()).getStr("command"))))
                            .build());
                } else {
                    long startMillis = System.currentTimeMillis();
                    String toolResult;
                    try {
                        toolResult = tool.execute(toolExeReq);
                    } catch (RuntimeException e) {
                        toolResult = "Error: tool execution failed: " + readableError(e, history);
                    }
                    long elapsedMillis = System.currentTimeMillis() - startMillis;
                    boolean success = toolResult != null && !toolResult.startsWith("Error:");
                    console.printToolResult(toolExeReq.name(), toolResult, elapsedMillis, success);
                    console.printMessageState(history);
                    history.add(ToolExecutionResultMessage.from(toolExeReq, toolResult));
                }
                if (!TodoWriteTool.getTodos().isEmpty()) {
                    console.printTodos(TodoWriteTool.getTodos());
                }
            });
            List<BackgroundTask> result = BackgroundTaskUtil.collectBackgroundTaskResult();
            if(result == null || result.isEmpty()){
                continue;
            }
            // 多个后台任务完成通知需要合并到一个 TextContent 里,否则后续
            // MemoryManager/ConsoleRenderer 等调用 singleText() 会抛
            // "Expecting single text content" 异常,且 langchain4j 的 OpenAI
            // 实现对多 Content 的 UserMessage 行为也不一致。
            String combined = result.stream()
                    .filter(BackgroundTask.class::isInstance)
                    .map(BackgroundTask.class::cast)
                    .map(r -> String.format(
                            """
                            <task_notification>
                              <task_id>%s</task_id>
                              <status>completed</status>
                              <command>%s</command>
                              <result>%s</result>
                            </task_notification>
                            """, r.getTaskId(), r.getCommand(), r.getResult()))
                    .collect(Collectors.joining("\n"));
            history.add(UserMessage.from(combined));
        }
    }

    private static String readableError(RuntimeException error,List<ChatMessage> history) {
        String message = error.getMessage();
        Path path = CONFIG_DIR.resolve("error").resolve(System.currentTimeMillis() + ".jsonl");
        try {
            Files.createDirectories(path.getParent());
            Files.createFile(path);
            String crimeScene = history.stream().map(ChatMessageJsonConvertor.INSTANCE::convert).collect(Collectors.joining("\n"));
            Files.writeString(path, message);
            Files.writeString(path, "\n\n");
            Files.writeString(path, crimeScene, StandardCharsets.UTF_8);
        } catch (IOException e) {

        }
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
