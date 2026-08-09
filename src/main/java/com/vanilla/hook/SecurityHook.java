package com.vanilla.hook;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.vanilla.util.ConsoleRenderer;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

public class SecurityHook implements Hook {

    private static final List<String> DENY_LIST = List.of("rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=");

    private static final List<String> DESTRUCTIVE = List.of("rm ", "> /etc/", "chmod 777");

    private final ConsoleRenderer console = ConsoleRenderer.getShared();
    private final BufferedReader stdin = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));

    @Override
    public String id() {
        return "2ab434b3-836c-481b-89e6-066b7ce0538f";
    }

    @Override
    public HookEvent support() {
        return HookEvent.PreToolUse;
    }


    @Override
    public HookResult execute(HookContext context) {
        Iterator<ToolExecutionRequest> iterator = context.getToolUseRequest().iterator();
        while (iterator.hasNext()) {
            ToolExecutionRequest toolReq = iterator.next();
            String event = HookEvent.PreToolUse.name();

            // bash：deny list 直接拦截，destructive list 需要用户确认
            if ("bash".equals(toolReq.name())) {
                String command = JSONUtil.parseObj(toolReq.arguments()).getStr("command");
                if (command == null) {
                    continue;
                }
                for (String blocked : DENY_LIST) {
                    if (command.contains(blocked)) {
                        console.printHookBlock(getClass().getSimpleName(), event, "blocked", List.of(
                                "tool   " + toolReq.name(),
                                "reason 命中 deny list",
                                "match  " + blocked,
                                "cmd    " + command
                        ));
                        return HookResult.block("Permission denied by deny list");
                    }
                }
                for (String dangerous : DESTRUCTIVE) {
                    if (command.contains(dangerous)) {
                        if (!confirmWithUser(toolReq, event, "潜在危险操作", List.of(
                                "match  " + dangerous,
                                "cmd    " + command))) {
                            return HookResult.block("Permission denied by user");
                        }
                    }
                }
            }

            // write_file / edit_file：写入工作区之外需要用户确认
            if ("write_file".equals(toolReq.name()) || "edit_file".equals(toolReq.name())) {
                String pathArgument = JSONUtil.parseObj(toolReq.arguments()).getStr("path", "");
                if (pathArgument == null || pathArgument.isBlank()) {
                    continue;
                }
                try {
                    Path workdir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
                    Path target = Path.of(pathArgument.strip());
                    if (!target.isAbsolute()) {
                        target = workdir.resolve(target);
                    }
                    target = target.toAbsolutePath().normalize();
                    if (!target.startsWith(workdir)) {
                        if (!confirmWithUser(toolReq, event, "写入工作区之外", List.of(
                                "workdir " + workdir,
                                "target  " + target))) {
                            return HookResult.block("Permission denied by user");
                        }
                    }
                } catch (InvalidPathException e) {
                    if (!confirmWithUser(toolReq, event, "无效路径", List.of(
                            "path   " + pathArgument,
                            "error  " + e.getMessage()))) {
                        return HookResult.block("Permission denied by user");
                    }
                }
            }
        }
        return HookResult.allow();
    }

    /**
     * 渲染一个「需要确认」的 Hook 卡片，然后等待用户输入 y/N。
     *
     * <p>注意：必须保证读 stdin 与 JLine 不冲突。在 Codey 主循环中确认提示
     * 来自后台任务线程时，读取 System.in 仍可能与 LineReader 抢锁；当前
     * 实现由主 Agent 同步串行调用 Hook，预输入已通过 LineReader 缓冲，
     * 这里附加同步读取是安全的。</p>
     *
     * @return true 表示用户放行；false 表示拒绝
     */
    private boolean confirmWithUser(ToolExecutionRequest toolReq, String event,
                                    String reason, List<String> extraLines) {
        List<String> detail = new ArrayList<>();
        detail.add("tool   " + toolReq.name());
        detail.add("reason " + reason);
        detail.addAll(extraLines);

        console.printHookBlock(getClass().getSimpleName(), event, "需要确认", detail);
        console.printWarning("Allow? [y/N] ");
        String choice = null;
        try {
            choice = stdin.readLine();
        } catch (IOException ignored) {
            // EOF / 关闭 → 视为拒绝
        }
        boolean allowed = choice != null
                && (choice.trim().equalsIgnoreCase("y") || choice.trim().equalsIgnoreCase("yes"));
        if (!allowed) {
            console.printHookBlock(getClass().getSimpleName(), event, "blocked", List.of(
                    "tool   " + toolReq.name(),
                    "reason 用户拒绝放行"
            ));
        }
        return allowed;
    }
}
