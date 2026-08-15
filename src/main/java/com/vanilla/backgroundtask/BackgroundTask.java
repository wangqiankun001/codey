package com.vanilla.backgroundtask;

public class BackgroundTask {

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
