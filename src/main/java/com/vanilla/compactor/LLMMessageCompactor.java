package com.vanilla.compactor;

import java.util.List;
import java.util.stream.Collectors;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

public class LLMMessageCompactor {

    private static final int CONTEXT_LIMIT = 60_000;

    private static final String LOG_PREFIX = "[llm] ";

    /** 写入紧凑日志，自动 flush 以便在交互式终端里实时可见。 */
    private static void log(String message) {
        System.out.println(LOG_PREFIX + message);
        System.out.flush();
    }

    private static final String COMPACT_PROMPT = """
            Summarize this coding-agent conversation so work can continue.
            Preserve:
            1. current goal,
            2. key findings/decisions,
            3. files read/changed.
            4. remaining work,
            5. user constraints.
            Be compact but concrete.
            %s
            """;


    public static List<ChatMessage> llmCompact(List<ChatMessage> history, ChatModel client) {
        log("enter llmCompact: historySize=" + history.size() + ", contextLimit=" + CONTEXT_LIMIT);
        int total = history.stream().mapToInt(LLMMessageCompactor::textLength).sum();
        if (total < CONTEXT_LIMIT) {
            log("skip llm: total=" + total + " chars already within contextLimit=" + CONTEXT_LIMIT);
            return history;
        }
        log("start llm compaction: total=" + total + " chars > contextLimit=" + CONTEXT_LIMIT);
        String conversation = history.stream().map(m->{
                    return JSONUtil.toJsonStr(m);
                }).collect(Collectors.joining(","));
        String summary = client.chat(String.format(COMPACT_PROMPT, conversation));
        log("summary generated: conversation=" + conversation.length() + " chars, summary="
                + summary.length() + " chars");
        int beforeCount = history.size();
        history.clear();
        history.add(UserMessage.builder()
                .addContent(TextContent.from(summary))
                .build()
        );
        log("llm done: history=" + beforeCount + " -> " + history.size()
                + " msgs, chars=" + total + " -> " + textLength(history.get(0)));
        return history;
    }

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
                length += am.toolExecutionRequests().stream().mapToInt((ToolExecutionRequest t) -> {
                    return t.arguments().length() + t.name().length();
                }).sum();
            }
            return length;
        } else if (message instanceof ToolExecutionResultMessage tu) {
            return tu.text().length();
        }
        return 0;
    }
}
