package com.vanilla.tool.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanilla.Codey;

public class Task {

    private String id;
    private String subject;
    private String description;
    private TaskStatus status;
    private String owner;
    private List<String> blockedBy;

    public Task(String id, String subject, String description, TaskStatus status, String owner,
            List<String> blockedBy) {
        this.id = id;
        this.subject = subject;
        this.description = description;
        this.status = status;
        this.owner = owner;
        this.blockedBy = blockedBy;
    }

    public Task() {
	}

	public static final Path TASK_DIR = Codey.CONFIG_DIR.resolve("task");

    private static final ObjectMapper OM = new ObjectMapper();

    public boolean canStart() {
        for (String preTaskId : this.blockedBy) {
            Task preTask = loadTask(preTaskId);
            if (preTask == null) {
                return false;
            }
            if (preTask.status != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    public static Task loadTask(String taskId) {
        Path path = TASK_DIR.resolve(taskId + ".json");
        if (!path.toFile().exists()) {
            return null;
        }
        try {
            return OM.readValue(Files.readString(path, StandardCharsets.UTF_8), Task.class);
        } catch (IOException e) {
            throw new UncheckedIOException("task load failed: " + taskId, e);
        }
    }

    public static void saveTask(Task task) {
        Path path = TASK_DIR.resolve(task.getId() + ".json");
        try {
            if (!path.toFile().exists()) {
                Files.createDirectories(path.getParent());
                Files.createFile(path);
            }
            Files.writeString(path, OM.writeValueAsString(task), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("task 写入失败：" + e.getMessage());
        }
    }

    public static List<Task> listTask() {
        List<Path> list;
        List<Task> result = new ArrayList<>();
        try {
            list = Files.list(TASK_DIR).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Task列表加载失败", e);
        }
        for (Path file : list) {
            try {
                Task task = OM.readValue(Files.readString(file, StandardCharsets.UTF_8), Task.class);
                result.add(task);
            } catch (IOException e) {
                throw new UncheckedIOException("Task读取失败:" + file.getFileName().toString(), e);
            }
        }
        return result;
    }

    public String getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getOwner() {
        return owner;
    }

    public List<String> getBlockedBy() {
        return blockedBy;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public void setBlockedBy(List<String> blockedBy) {
        this.blockedBy = blockedBy;
    }
}
