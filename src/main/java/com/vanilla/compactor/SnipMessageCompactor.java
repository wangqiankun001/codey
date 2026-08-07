package com.vanilla.compactor;

import java.util.ArrayList;
import java.util.List;

import com.vanilla.util.ConsoleRenderer;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;

/**
 * L3 上下文压缩：snipCompact
 *
 * <p>当历史消息条数达到 {@link #MAX_MESSAGE_SIZE} 时，保留头部前
 * {@code KEEP_HEAD_SIZE} 条与尾部 {@code KEEP_TAIL_SIZE} 条，中间消息被替换成一条
 * {@code [snipped N messages]} 标记。
 *
 * <p>为了让中间省略不会破坏 assistant 工具调用 ↔ tool 结果的成对关系，
 * 若尾部向前回退时会跳过 {@link ChatMessageType#TOOL_EXECUTION_RESULT}，避免
 * 保留的 tail 起点落在 tool_result 中间。
 */
public class SnipMessageCompactor {

    /** 历史长度超过该阈值时触发 snip 压缩。 */
    public static int MAX_MESSAGE_SIZE = 80;

    /** 头部恒定保留条数。 */
    private static final int KEEP_HEAD_SIZE = 2;

    /** 用于在终端上区分本压缩器自身输出的前缀（与 BudgetMessageCompactor 的 [compactor] 平行）。 */
    private static final String LOG_PREFIX = "[snip] ";

    /** 写入紧凑日志，自动 flush 以便在交互式终端里实时可见。 */
    private static void log(String message) {
        ConsoleRenderer.getShared().printDebug(LOG_PREFIX, message);
    }

    public static List<ChatMessage> snipCompact(List<ChatMessage> history) {
        int size = history.size();
        if (size < MAX_MESSAGE_SIZE) {
            log("skipped: history=" + size + " < MAX_MESSAGE_SIZE " + MAX_MESSAGE_SIZE);
            return history;
        }

        int keepTail = MAX_MESSAGE_SIZE - KEEP_HEAD_SIZE;
        int startIdx = KEEP_HEAD_SIZE;
        int endIdx = size - keepTail;

        // 若 cut 点正好落在 tool_result 中间，向前回退以保留整段 tool 调用链
        int toolPullback = 0;
        while (endIdx > startIdx && ChatMessageType.TOOL_EXECUTION_RESULT.equals(history.get(endIdx).type())) {
            endIdx--;
            toolPullback++;
        }

        int snippedCount = endIdx - startIdx;
        if (snippedCount <= 0) {
            log("skipped: head/tail already overlap (size=" + size + ", head=" + startIdx + ", tailKeep=" + keepTail + ")");
            return history;
        }

        List<ChatMessage> newHistory = new ArrayList<>();
        newHistory.addAll(history.subList(0, startIdx));
        newHistory.add(UserMessage.from(String.format("[snipped %d messages]", snippedCount)));
        newHistory.addAll(history.subList(endIdx, size));

        history.clear();
        history.addAll(newHistory);

        // 唯一一行关键信息：被压缩的条数 + 前后长度
        log("snipped " + snippedCount + " msgs: " + size + " -> " + history.size());
        return history;
    }
}
