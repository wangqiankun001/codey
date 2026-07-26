package com.vanilla.compactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * L3 上下文压缩：llmCompact
 *
 * <p>当历史中纯文本累计长度超过 {@link #CONTEXT_LIMIT} 字符时，把整段对话落盘
 * （便于事后审计 / 排查），并由 LLM 自身把对话压缩成一段摘要，再以一条
 * {@link UserMessage} 注入历史头部；任何 {@link SystemMessage} 都会被保留，
 * 避免破坏系统提示。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>阈值与上下文的"字符"度量保持一致：用 {@link #textLength(ChatMessage)} 对
 *       每个消息单独求和，忽略 tool_execution 的非文本字段。</li>
 *   <li>压缩提示词本身走 {@link ChatRequest} 给大模型，而非单字符串
 *       {@code chat(String)}，以保留 system 提示与轮次语义。</li>
 *   <li>历史中含 {@code %} 时也不会再被 {@link String#format} 当占位符解析。</li>
 * </ul>
 */
public class LLMMessageCompactor {

    /** 触发 llm 压缩的字符阈值。 */
    public static final int CONTEXT_LIMIT = 40_000;

    /** 落到磁盘的 transcript 子目录（相对当前工作目录）。 */
    private static final Path TRANSCRIPT_DIR = Paths.get(".codey", "transcript");

    /** 用于在终端上区分本压缩器自身输出的前缀。 */
    private static final String LOG_PREFIX = "[llm] ";

    /** 写入紧凑日志，自动 flush 以便在交互式终端里实时可见。 */
    private static void log(String message) {
        System.out.println(LOG_PREFIX + message);
        System.out.flush();
    }

    /**
     * 给 LLM 的压缩指令。注意：用户消息正文不再用 {@link String#format} 插入，
     * 因为历史里可能出现 {@code %} 这样的非占位符字面量，引发
     * {@link java.util.UnknownFormatConversionException}。
     */
    private static final String COMPACT_INSTRUCTION = """
            Summarize this coding-agent conversation so work can continue.
            Preserve:
            1. current goal,
            2. key findings/decisions,
            3. files read/changed,
            4. remaining work,
            5. user constraints.
            Be compact but concrete.

            Conversation (JSON):
            """;

    /**
     * 压缩入口。直接在传入的 {@code history} 上就地修改并返回，便于链式调用。
     *
     * <p>流程：
     * <ol>
     *   <li>累计所有消息的文本长度，未超过阈值则直接返回；</li>
     *   <li>先落盘 transcript；</li>
     *   <li>组装 system + user 两条消息调用 LLM 生成摘要；</li>
     *   <li>清空 history（保留原 SystemMessage），把摘要作为 UserMessage 注入。</li>
     * </ol>
     */
    public static List<ChatMessage> llmCompact(List<ChatMessage> history, ChatModel client) {
        log("enter llmCompact: historySize=" + history.size() + ", contextLimit=" + CONTEXT_LIMIT);
        int total = history.stream().mapToInt(LLMMessageCompactor::textLength).sum();
        if (total < CONTEXT_LIMIT) {
            log("skip llm: total=" + total + " chars already within contextLimit=" + CONTEXT_LIMIT);
            return history;
        }

        writeTranscript(history);

        log("start llm compaction: total=" + total + " chars > contextLimit=" + CONTEXT_LIMIT);
        String conversation = JSONUtil.toJsonStr(history);

        // 拼成单条 user 消息正文，避免 String.format 解析 JSON 里的 % 占位符。
        String userPrompt = COMPACT_INSTRUCTION + conversation;

        ChatResponse response;
        try {
            response = client.chat(ChatRequest.builder()
                    .messages(SystemMessage.from("You are a conversation compactor."),
                            UserMessage.from(userPrompt))
                    .build());
        } catch (RuntimeException e) {
            // LLM 失败时要把 transcript 留给后续排查，不再清空 history 以免丢上下文。
            log("llm compaction FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " — history kept unchanged, transcript preserved for inspection");
            return history;
        }

        AiMessage aiMessage = response == null ? null : response.aiMessage();
        String summary = aiMessage == null || aiMessage.text() == null ? "" : aiMessage.text();
        log("summary generated: conversation=" + conversation.length() + " chars, summary="
                + summary.length() + " chars");

        if (summary.isEmpty()) {
            log("llm compaction produced empty summary: keeping original history");
            return history;
        }

        // 保留所有 SystemMessage，丢掉其余消息，把摘要作为新的 UserMessage 注入头部。
        List<ChatMessage> systemMessages = new ArrayList<>();
        for (ChatMessage m : history) {
            if (m instanceof SystemMessage) {
                systemMessages.add(m);
            }
        }
        int beforeCount = history.size();
        history.clear();
        history.addAll(systemMessages);
        history.add(UserMessage.from("[Compressed summary]\n" + summary));
        log("llm done: history=" + beforeCount + " -> " + history.size()
                + " msgs, chars=" + total + " -> " + textLength(history.get(history.size() - 1))
                + " (system preserved=" + systemMessages.size() + ")");
        return history;
    }

    /**
     * 把压缩前的整段聊天历史写成 JSONL，文件名带时间戳，避免相互覆盖。
     * 失败时仅 warn，不抛异常——压缩仍可继续，只是失去事后排查能力。
     */
    private static void writeTranscript(List<ChatMessage> history) {
        try {
            Files.createDirectories(TRANSCRIPT_DIR);
            Path transcript = Files.createFile(
                    TRANSCRIPT_DIR.resolve(System.currentTimeMillis() + ".jsonl"));
            String content = history.stream()
                    .map(LLMMessageCompactor::transcriptLine)
                    .collect(java.util.stream.Collectors.joining("\n"));
            Files.writeString(transcript, content, StandardCharsets.UTF_8);
            log("transcript written: path=" + transcript.toAbsolutePath()
                    + ", messages=" + history.size() + ", bytes=" + content.length());
        } catch (IOException e) {
            log("transcript写入失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 把一条 {@link ChatMessage} 转成 JSONL 一行，便于人/脚本解析。 */
    private static String transcriptLine(ChatMessage message) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", message.type().name());
        if (message instanceof UserMessage um) {
            map.put("name", um.name());
            map.put("contents", um.contents());
        } else if (message instanceof AiMessage am) {
            map.put("text", am.text());
            map.put("thinking", am.thinking());
            if (am.hasToolExecutionRequests()) {
                map.put("toolExecutionRequests", am.toolExecutionRequests());
            }
        } else if (message instanceof ToolExecutionResultMessage tm) {
            map.put("id", tm.id());
            map.put("toolName", tm.toolName());
            map.put("text", tm.text());
            map.put("isError", tm.isError());
        } else if (message instanceof SystemMessage sm) {
            map.put("text", sm.text());
        }
        return JSONUtil.toJsonStr(map);
    }

    /** 单条消息的纯文本长度，用来汇总逼近上下文窗口。 */
    private static int textLength(ChatMessage message) {
        if (message instanceof UserMessage um) {
            return um.singleText().length();
        } else if (message instanceof AiMessage am) {
            int length = 0;
            if (am.text() != null) {
                length += am.text().length();
            }
            if (am.thinking() != null) {
                length += am.thinking().length();
            }
            if (am.hasToolExecutionRequests()) {
                length += am.toolExecutionRequests().stream()
                        .mapToInt(t -> {
                            int len = t.name() == null ? 0 : t.name().length();
                            len += t.arguments() == null ? 0 : t.arguments().length();
                            return len;
                        })
                        .sum();
            }
            return length;
        } else if (message instanceof ToolExecutionResultMessage tu) {
            return tu.text() == null ? 0 : tu.text().length();
        }
        return 0;
    }
}
