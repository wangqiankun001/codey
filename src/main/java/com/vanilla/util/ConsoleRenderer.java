package com.vanilla.util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import com.vanilla.tool.TodoWriteTool.Todo;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 负责 Codey 的终端展示，让业务流程不需要关心颜色、边框和文本换行。
 */
public final class ConsoleRenderer {

    private static final int DEFAULT_TERMINAL_WIDTH = 88;
    private static final int MIN_TERMINAL_WIDTH = 48;
    private static final int MAX_TERMINAL_WIDTH = 120;

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";

    /** 卡片最大宽度（终端字符列），过长会换行。 */
    private static final int CARD_CONTENT_WIDTH = 84;
    /** 单段 tool 输出最多显示的行数，超过会截断。 */
    private static final int MAX_RESULT_LINES = 16;
    /** 单段 tool 输出最多累计显示的字符数，超过会截断。 */
    private static final int MAX_RESULT_CHARS = 1600;
    /** 调用参数最大显示字符数，超过会截断。 */
    private static final int MAX_ARG_CHARS = 240;

    private final PrintStream out;
    private final int terminalWidth;
    private final boolean colorEnabled;

    /**
     * 进程内共享的渲染器，供没有直接持有 {@link ConsoleRenderer} 的组件（如子 Agent 工具）使用。
     * 由主 Agent 在构造时通过 {@link #setShared(ConsoleRenderer)} 注入，未注入时回退到 stdout 默认值。
     */
    private static volatile ConsoleRenderer shared;

    public ConsoleRenderer(PrintStream out) {
        this(out, detectTerminalWidth(), supportsColor());
    }

    ConsoleRenderer(PrintStream out, int terminalWidth, boolean colorEnabled) {
        this.out = out;
        this.terminalWidth = Math.max(MIN_TERMINAL_WIDTH, Math.min(MAX_TERMINAL_WIDTH, terminalWidth));
        this.colorEnabled = colorEnabled;
    }

    /**
     * 获取进程共享的渲染器。尚未注入时回退到基于 {@link System#out} 的默认实例。
     */
    public static ConsoleRenderer getShared() {
        ConsoleRenderer current = shared;
        if (current == null) {
            synchronized (ConsoleRenderer.class) {
                current = shared;
                if (current == null) {
                    current = new ConsoleRenderer(System.out);
                    shared = current;
                }
            }
        }
        return current;
    }

    public static void setShared(ConsoleRenderer renderer) {
        shared = renderer;
    }

    public void printWelcome() {
        out.println();
        printBox("Codey", List.of(
                style(BOLD + GREEN, "AI Coding Agent"),
                style(DIM, "输入问题开始对话；输入 q、quit 或 exit 退出")
        ), GREEN, null);
        out.println();
    }

    public void printPrompt() {
        out.print(promptText());
        out.flush();
    }

    /**
     * 返回给 JLine 使用的提示符。由输入编辑器负责光标移动和重绘，
     * 因此不能先单独打印提示符再调用 {@code readLine}。
     */
    public String promptText() {
        return style(BOLD + CYAN, "你 › ");
    }

    void printUserMessage(String message) {
        out.println();
        printBox("你", linesOf(message), CYAN, null);
        out.println();
    }

    public void printThinking() {
        out.println(style(DIM, "  ● Codey 正在思考…"));
        out.flush();
    }

    /**
     * 渲染子 Agent 会话开始的卡片，包含任务描述。
     */
    public void printSubagentStart(String task) {
        List<String> lines = new ArrayList<>();
        String preview = previewSingleLine(task, MAX_RESULT_CHARS);
        if (task != null && task.length() > MAX_RESULT_CHARS) {
            preview = preview + " …";
        }
        lines.add(style(DIM, "task   ") + style(BOLD, preview));
        out.println();
        printBox("▶ subagent · start", lines, MAGENTA, style(DIM, "派生子 Agent"));
        out.println();
    }

    /**
     * 渲染子 Agent 每一轮模型请求前的轻量进度行。
     */
    public void printSubagentRound(int round, int max) {
        out.println(style(MAGENTA, "  ● subagent · round " + round + "/" + max + "  正在请求模型…"));
        out.flush();
    }

    /**
     * 渲染子 Agent 结束卡片，包含轮数、耗时、最终回答的预览。
     */
    public void printSubagentDone(boolean success, int rounds, long elapsedMs, String answer) {
        String icon = success ? "✓" : "✗";
        String color = success ? GREEN : RED;
        List<String> lines = new ArrayList<>();
        lines.add(style(DIM, "rounds  ") + rounds);
        lines.add(style(DIM, "elapsed ") + elapsedMs + " ms");
        if (answer != null && !answer.isBlank()) {
            String preview = previewSingleLine(answer, MAX_RESULT_CHARS);
            if (answer.length() > MAX_RESULT_CHARS) {
                preview = preview + " …";
            }
            lines.add(style(DIM, "answer  ") + preview);
        } else if (success) {
            lines.add(style(DIM, "answer  ") + style(DIM, "(空)"));
        }
        out.println();
        printBox(icon + " subagent · done", lines, color,
                style(DIM, (success ? "成功 · " : "失败 · ") + elapsedMs + " ms"));
        out.println();
    }

    /**
     * 旧的轻量状态行，保留以兼容调用方。新流程已切换到 {@link #printToolCall} + {@link #printToolResult}。
     */
    public void printToolStatus(String toolName) {
        out.println(style(DIM, "  └─ 正在执行工具：" + toolName));
        out.flush();
    }

    /**
     * 渲染一次「工具开始调用」的卡片。
     *
     * @param toolName   工具名
     * @param arguments  工具参数的 JSON 字符串（过长会被截断）
     */
    public void printToolCall(String toolName, String arguments) {
        List<String> lines = new ArrayList<>();
        lines.add(style(DIM, "args   ") + style(BOLD, previewSingleLine(arguments, MAX_ARG_CHARS)));
        printBox("▶ tool · " + toolName, lines, BLUE, style(DIM, "调用中…"));
    }

    /**
     * 渲染一次「工具调用完成」的卡片，会根据 success 自动选择颜色与图标。
     *
     * @param toolName   工具名
     * @param result     工具返回的原始文本（过长会自动截断）
     * @param elapsedMs  执行耗时（毫秒）
     * @param success    是否成功（true=绿色 ✓ / false=红色 ✗）
     */
    public void printToolResult(String toolName, String result, long elapsedMs, boolean success) {
        String icon = success ? "✓" : "✗";
        String color = success ? GREEN : RED;
        List<String> lines = summarizeResult(result);
        String footer = (success ? "完成 · " : "失败 · ") + elapsedMs + " ms";
        printBox(icon + " tool · " + toolName, lines, color, style(DIM, footer));
    }

    /**
     * 渲染一次 Hook 触发后的结构化卡片，用于展示被拦截、安全告警等关键事件。
     *
     * @param hookName   Hook 实现类的简单名
     * @param event      Hook 事件名
     * @param status     状态文案，例如 {@code "ok"} / {@code "blocked"} / {@code "需要确认"}
     * @param detail     详细信息（多行）
     */
    public void printHookBlock(String hookName, String event, String status, List<String> detail) {
        boolean blocked = "blocked".equalsIgnoreCase(status);
        String icon = blocked ? "✋" : "⚙";
        String color = blocked ? RED : MAGENTA;
        List<String> lines = new ArrayList<>();
        lines.add(style(DIM, "event  ") + event);
        lines.add(style(DIM, "status ") + status);
        if (detail != null && !detail.isEmpty()) {
            lines.add(style(DIM, "detail"));
            for (String row : detail) {
                lines.add("  " + row);
            }
        }
        printBox(icon + " hook · " + hookName, lines, color, style(DIM, event + " · " + status));
    }

    /**
     * 渲染对话总结，常用于会话结束前的回顾。
     */
    public void printSummary(List<String> summaryLines) {
        printBox("⚙ hook · 对话总结", summaryLines, MAGENTA, style(DIM, "Session Summary"));
    }

    public void printAiMessage(String message) {
        out.println();
        String content = message == null || message.isBlank()
                ? "（AI 没有返回文本内容）"
                : message.strip();
        printBox("Codey · MiniMax-M3", linesOf(content), GREEN, null);
        out.println();
    }

    public void printWarning(String message) {
        out.println(style(YELLOW, "  提示：" + message));
        out.println();
    }

    public void printError(String message) {
        out.println();
        printBox("出错了", linesOf(message), RED, null);
        out.println();
    }

    public void printGoodbye() {
        out.println();
        out.println(style(DIM, "再见，期待下次和你一起写代码。"));
    }

    private void printBox(String title, List<String> sourceLines, String color) {
        printBox(title, sourceLines, color, null);
    }

    private void printBox(String title, List<String> sourceLines, String color, String footer) {
        int titleWidth = displayWidth(title);
        int topFillWidth = Math.max(1, terminalWidth - titleWidth - 5);
        out.println(style(color, "╭─ " + title + " " + "─".repeat(topFillWidth) + "╮"));

        int contentWidth = terminalWidth - 4;
        List<String> wrappedLines = new ArrayList<>();
        for (String line : sourceLines) {
            wrappedLines.addAll(wrapLine(line, contentWidth));
        }
        if (wrappedLines.isEmpty()) {
            wrappedLines.add("");
        }

        for (String line : wrappedLines) {
            int padding = Math.max(0, contentWidth - displayWidth(line));
            out.println(style(color, "│ ") + line + " ".repeat(padding) + style(color, " │"));
        }

        if (footer != null && !footer.isBlank()) {
            int footerWidth = displayWidth(footer);
            int leftPad = Math.max(1, (contentWidth - footerWidth) / 2);
            int rightPad = Math.max(1, contentWidth - footerWidth - leftPad);
            out.println(style(color, "│ ")
                    + " ".repeat(leftPad) + footer + " ".repeat(rightPad)
                    + style(color, " │"));
        }

        out.println(style(color, "╰" + "─".repeat(terminalWidth - 2) + "╯"));
    }

    /**
     * 把 tool 返回文本折成卡片内的多行展示，超长会自动截断并提示。
     */
    private List<String> summarizeResult(String result) {
        List<String> lines = new ArrayList<>();
        if (result == null || result.isEmpty()) {
            lines.add(style(DIM, "(空输出)"));
            return lines;
        }
        String normalized = result.replace("\r\n", "\n").replace('\r', '\n');
        String[] split = normalized.split("\n", -1);
        int totalLines = split.length;
        int shown = 0;
        int charCount = 0;
        for (int i = 0; i < totalLines && shown < MAX_RESULT_LINES; i++) {
            String line = split[i];
            int remaining = MAX_RESULT_CHARS - charCount;
            if (remaining <= 0) {
                break;
            }
            if (line.length() > remaining) {
                lines.add(line.substring(0, remaining) + " …");
                charCount += remaining;
                shown++;
                break;
            }
            lines.add(line);
            charCount += line.length();
            shown++;
        }
        if (totalLines > shown || charCount >= MAX_RESULT_CHARS) {
            lines.add(style(DIM, "… 共 " + totalLines + " 行 / " + result.length() + " 字符，已截断"));
        }
        if (lines.isEmpty()) {
            lines.add(style(DIM, "(空输出)"));
        }
        return lines;
    }

    private static String previewSingleLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + " …";
    }

    private List<String> wrapLine(String line, int maxWidth) {
        if (line == null || line.isEmpty()) {
            return List.of("");
        }

        List<String> result = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        int currentWidth = 0;

        for (int offset = 0; offset < line.length();) {
            int codePoint = line.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == '\t') {
                int spaces = Math.min(4 - currentWidth % 4, maxWidth - currentWidth);
                if (spaces == 0) {
                    result.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentWidth = 0;
                    spaces = 4;
                }
                currentLine.append(" ".repeat(spaces));
                currentWidth += spaces;
                continue;
            }

            int codePointWidth = displayWidth(codePoint);
            if (currentWidth > 0 && currentWidth + codePointWidth > maxWidth) {
                result.add(currentLine.toString());
                currentLine.setLength(0);
                currentWidth = 0;
            }

            currentLine.appendCodePoint(codePoint);
            currentWidth += codePointWidth;
        }

        result.add(currentLine.toString());
        return result;
    }

    private static List<String> linesOf(String message) {
        if (message == null) {
            return List.of("");
        }
        return List.of(message.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    private String style(String ansiStyle, String text) {
        return colorEnabled ? ansiStyle + text + RESET : text;
    }

    private static int displayWidth(String text) {
        return text.codePoints().map(ConsoleRenderer::displayWidth).sum();
    }

    private static int displayWidth(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT) {
            return 0;
        }

        // 常见 CJK、全角字符和 Emoji 在终端中通常占两个字符宽度。
        if (codePoint >= 0x1100 && (
                codePoint <= 0x115F
                        || codePoint == 0x2329
                        || codePoint == 0x232A
                        || codePoint >= 0x2E80 && codePoint <= 0xA4CF && codePoint != 0x303F
                        || codePoint >= 0xAC00 && codePoint <= 0xD7A3
                        || codePoint >= 0xF900 && codePoint <= 0xFAFF
                        || codePoint >= 0xFE10 && codePoint <= 0xFE19
                        || codePoint >= 0xFE30 && codePoint <= 0xFE6F
                        || codePoint >= 0xFF00 && codePoint <= 0xFF60
                        || codePoint >= 0xFFE0 && codePoint <= 0xFFE6
                        || codePoint >= 0x1F300 && codePoint <= 0x1FAFF
                        || codePoint >= 0x20000 && codePoint <= 0x3FFFD)) {
            return 2;
        }
        return 1;
    }

    private static int detectTerminalWidth() {
        String columns = System.getenv("COLUMNS");
        if (columns == null || columns.isBlank()) {
            return DEFAULT_TERMINAL_WIDTH;
        }
        try {
            return Integer.parseInt(columns);
        } catch (NumberFormatException ignored) {
            return DEFAULT_TERMINAL_WIDTH;
        }
    }

    private static boolean supportsColor() {
        return System.console() != null
                && System.getenv("NO_COLOR") == null
                && !Boolean.getBoolean("codey.noColor");
    }

    public void printMessageState(List<ChatMessage> history) {
        int total = history.stream().mapToInt(this::textLength).sum();
        int max = history.stream().mapToInt(this::textLength).max().getAsInt();
        printBox("当前对话存储情况", List.of("总消息数量：" + history.size(), "对话总大小：" + total, "单条最大大小：" + max), BLUE);
    }

    public int textLength(ChatMessage message) {
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

    public void printTodos(List<Todo> todos) {
        List<String> sourceLines = new ArrayList<>();
        for (int i = 0; i < todos.size(); i++) {
            Todo todo = todos.get(i);
            sourceLines.add(String.format("[%d] 任务：[%s] 状态:[%s]", i, todo.getContent(), todo.getStatus()));
        }
        printBox("任务进度",sourceLines, BLUE);
    }
}
