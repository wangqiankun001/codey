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

    public static class BackgroundTask {

        private String taskId;
        private String command;
        private BackgroundTaskStatus status;
        private Object Result;

        public BackgroundTask(String taskId, String command, BackgroundTaskStatus status, Object result) {
            this.taskId = taskId;
            this.command = command;
            this.status = status;
            Result = result;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }
        public String getTaskId() {
            return taskId;
        }
        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }
        public BackgroundTaskStatus getStatus() {
            return status;
        }

        public BackgroundTask setStatus(BackgroundTaskStatus status) {
            this.status = status;
            return this;
        }

        public Object getResult() {
            return Result;
        }

        public BackgroundTask setResult(Object result) {
            Result = result;
            return this;
        }

    }

    private static enum BackgroundTaskStatus {
        INIT("init"), IN_PROGRESS("in_progress"), COMPLETED("completed"), STOP("stop");

        @JsonValue
        private String value;

        private BackgroundTaskStatus(String value) {
            this.value = value;
        }

    }

    public static synchronized List<Object> collectBackgroundTaskResult() {
        Map<String, BackgroundTask> completes = new HashMap<>();
        tasks.forEach((k, v) -> {
            if (v.getStatus() == BackgroundTaskStatus.COMPLETED) {
                completes.put(k, v);
            }
        });
        completes.keySet().forEach(k -> tasks.remove(k));
        return new ArrayList<>(completes.values());
    }
}
