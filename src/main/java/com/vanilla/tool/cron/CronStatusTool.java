package com.vanilla.tool.cron;

import cn.hutool.json.JSONUtil;
import com.vanilla.Codey;
import com.vanilla.cron.CronScheduler;
import com.vanilla.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Report scheduler health: started flag, loaded job count, queue depth and state-dir health. */
public class CronStatusTool implements Tool {
    static final String NAME = "cron_status";

    private static final String DESCRIPTION =
            "Report the health of the cron scheduler: whether it has been started, how many "
                    + "jobs are loaded, how many are queued for the next minute, and whether the "
                    + "on-disk state directory exists.";

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(NAME)
                .description(DESCRIPTION)
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        if (request.arguments() != null && !request.arguments().isBlank()) {
            try {
                JSONUtil.parseObj(request.arguments());
            } catch (RuntimeException e) {
                return "Error: arguments is not valid JSON: " + e.getMessage();
            }
        }

        CronScheduler scheduler = CronScheduler.getInstance();
        Path stateDir = ScheduleCronTool.cronDir();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("started", scheduler.isStarted());
        payload.put("loaded_count", scheduler.loadedCount());
        payload.put("queued_count", scheduler.queuedCount());
        payload.put("state_dir", stateDir.toString());
        payload.put("state_dir_exists", Files.isDirectory(stateDir));

        // Best-effort: only report writability, don't fail the whole call if the probe fails.
        payload.put("state_dir_writable", checkWritable(stateDir));

        return JSONUtil.toJsonPrettyStr(payload);
    }

    /** Probe whether the scheduler's state directory is writable; swallow any IOException. */
    private static boolean checkWritable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".codey-write-probe-", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
