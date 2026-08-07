package com.vanilla.compactor;

import java.util.ArrayList;
import java.util.List;

import com.vanilla.util.ConsoleRenderer;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

/**
 * L3 上下文压缩：micoCompact
 *
 * <p>在历史中扫描所有 {@link ChatMessageType#TOOL_EXECUTION_RESULT}，保留最近
 * {@link #KEEP_RECENT} 条原样，更早且文本长度超过
 * {@link #TOOL_USE_SIZE_THRESHOLD} 的结果会被替换为一行
 * {@code [Earlier tool result compacted. Re-run if needed.]} 占位文本，以让 LLM
 * 自行决定是否需要重新调用工具恢复。
 */
public class MicoMessageCompactor {

    /** 始终保留原样的最近 tool_result 条数。 */
    public static final int KEEP_RECENT = 5;

    /** 超过该字节阈值的早期 tool_result 文本会被压缩为占位行。 */
    public static final int TOOL_USE_SIZE_THRESHOLD = 120;

    /** 用于在终端上区分本压缩器自身输出的前缀（与 BudgetMessageCompactor 的 [compactor]、SnipMessageCompactor 的 [snip] 平行）。 */
    private static final String LOG_PREFIX = "[mico] ";

    /** 写入紧凑日志，自动 flush 以便在交互式终端里实时可见。 */
    private static void log(String message) {
        ConsoleRenderer.getShared().printDebug(LOG_PREFIX, message);
    }

    public static List<ChatMessage> micoCompact(List<ChatMessage> history) {
        List<Integer> tooUseResultIdxs = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (ChatMessageType.TOOL_EXECUTION_RESULT.equals(history.get(i).type())) {
                tooUseResultIdxs.add(i);
            }
        }
        int totalToolResults = tooUseResultIdxs.size();
        if (totalToolResults < KEEP_RECENT) {
            log("skipped: toolResults=" + totalToolResults + " < KEEP_RECENT " + KEEP_RECENT);
            return history;
        }

        List<Integer> targets = new ArrayList<>(tooUseResultIdxs.subList(0, totalToolResults - KEEP_RECENT));
        int compacted = 0;
        int skippedBelowThreshold = 0;
        for (Integer idx : targets) {
            ToolExecutionResultMessage toolExecutionResultMessage = (ToolExecutionResultMessage) history.get(idx);
            int toolResultLength = toolExecutionResultMessage.contents().stream().filter(TextContent.class::isInstance)
                    .mapToInt(tc -> {
                        return ((TextContent) tc).text().length();
                    }).sum();
            if (toolResultLength <= TOOL_USE_SIZE_THRESHOLD) {
                skippedBelowThreshold++;
                continue;
            }
            history.set(idx, toolExecutionResultMessage.toBuilder()
                    .contents(TextContent.from("[Earlier tool result compacted. Re-run if needed.]"))
                    .build());
            compacted++;
        }

        // 唯一一行关键信息：被压缩数 / 候选数，必要时附上低于阈值的跳过数
        if (compacted > 0) {
            log("compacted " + compacted + "/" + targets.size() + " early toolResults"
                    + (skippedBelowThreshold > 0 ? " (skipped " + skippedBelowThreshold + " below threshold)" : ""));
        } else {
            log("skipped: " + targets.size() + " early toolResults all below threshold "
                    + TOOL_USE_SIZE_THRESHOLD);
        }
        return history;
    }
}
