package com.vanilla.compactor;

import java.util.ArrayList;
import java.util.List;

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
        System.out.println(LOG_PREFIX + message);
        System.out.flush();
    }

    public static List<ChatMessage> micoCompact(List<ChatMessage> history) {
        List<Integer> tooUseResultIdxs = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (ChatMessageType.TOOL_EXECUTION_RESULT.equals(history.get(i).type())) {
                tooUseResultIdxs.add(i);
            }
        }
        int totalToolResults = tooUseResultIdxs.size();
        log("enter micoCompact: historySize=" + history.size()
                + ", toolResults=" + totalToolResults
                + ", keepRecent=" + KEEP_RECENT
                + ", toolUseSizeThreshold=" + TOOL_USE_SIZE_THRESHOLD);

        if (totalToolResults < KEEP_RECENT) {
            log("skip mico: toolResults=" + totalToolResults + " < keepRecent=" + KEEP_RECENT);
            return history;
        }

        List<Integer> targets = new ArrayList<>(tooUseResultIdxs.subList(0, totalToolResults - KEEP_RECENT));
        int overThreshold = 0;
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
            overThreshold++;
            history.set(idx, toolExecutionResultMessage.toBuilder()
                    .contents(TextContent.from("[Earlier tool result compacted. Re-run if needed.]"))
                    .build());
            compacted++;
        }

        log("mico candidates: candidates=" + targets.size()
                + ", overThreshold=" + overThreshold
                + ", skippedBelowThreshold=" + skippedBelowThreshold
                + ", compacted=" + compacted
                + ", retainedRecent=" + KEEP_RECENT);
        if (compacted > 0) {
            log("mico done: historySize=" + history.size()
                    + ", toolResultsCompacted=" + compacted
                    + " of " + totalToolResults);
        } else {
            log("mico no-op: no tool_result over threshold, historySize=" + history.size());
        }
        return history;
    }
}
