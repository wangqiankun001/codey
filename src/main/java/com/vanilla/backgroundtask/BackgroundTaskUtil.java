package com.vanilla.backgroundtask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonValue;
import com.vanilla.tool.BashTool;
import com.vanilla.tool.Tool;
import com.vanilla.tool.ToolManager;
import com.vanilla.util.ConsoleRenderer;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

public class BackgroundTaskUtil {

    private static int backgroundTaskCnt = 0;

    private static ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(100);

    private static ExecutorService executorService = new ThreadPoolExecutor(5, 10, 60, TimeUnit.SECONDS, queue);

    private static Map<String, BackgroundTask> tasks = new ConcurrentHashMap<>();

    public static String startBackgroundTask(ToolExecutionRequest toolExeReq) {
        Tool handler = ToolManager.handler(toolExeReq.name());
        String command = JSONUtil.parseObj(toolExeReq.arguments()).getStr("command");
        backgroundTaskCnt++;
        var backgroundTaskId = String.format("bg_%04d", backgroundTaskCnt);

        executorService.submit(() -> {
            ConsoleRenderer.getShared().printDebug("后台任务开始，任务id: "+backgroundTaskId);
            tasks.put(backgroundTaskId, new BackgroundTask(backgroundTaskId,command,BackgroundTaskStatus.IN_PROGRESS, null));
            String result = handler.execute(toolExeReq);
            tasks.get(backgroundTaskId).setStatus(BackgroundTaskStatus.COMPLETED).setResult(result);
            ConsoleRenderer.getShared().printDebug("后台任务结束，任务id: "+backgroundTaskId);
        });
        return backgroundTaskId;
    }

    public static boolean shouldRunBackground(ToolExecutionRequest toolExeReq) {
        Boolean runInBackground = JSONUtil.parseObj(toolExeReq.arguments()).getBool("runInBackground");
        if (runInBackground != null && runInBackground) {
            return true;
        }
        return isSlowOperation(toolExeReq);

    }

    public static boolean isSlowOperation(ToolExecutionRequest toolExeReq) {
        if (!BashTool.NAME.equals(toolExeReq.name())) {
            return false;
        }
        String command = JSONUtil.parseObj(toolExeReq.arguments()).getStr("command").toLowerCase();
        List<String> slowKeywords = List.of("install", "build", "test", "deploy", "compile",
                "docker build", "pip install", "npm install",
                "cargo build", "pytest", "make");
        return slowKeywords.stream().anyMatch(command::contains);
    }


    public static synchronized List<String> collectBackgroundTaskResultStr() {
        return collectBackgroundTaskResult().stream()
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
                .collect(Collectors.toList());
    }


    public static synchronized List<BackgroundTask> collectBackgroundTaskResult() {
        Map<String, BackgroundTask> completes = new HashMap<>();
        tasks.forEach((k, v) -> {
            if (v.getStatus() == BackgroundTaskStatus.COMPLETED) {
                completes.put(k, v);
            }
        });
        completes.keySet().forEach(k -> tasks.remove(k));
        return new ArrayList<>(completes.values());
    }

    public static boolean hasCompleted() {
        return tasks.values().stream().anyMatch(task -> task.getStatus() == BackgroundTaskStatus.COMPLETED);
    }
}
