package com.vanilla.tool.cron;

import cn.hutool.json.JSONUtil;
import com.vanilla.cron.CronScheduler;
import com.vanilla.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cancel a previously-scheduled cron job by id. */
public class CancelCronTool implements Tool {
    static final String NAME = "cancel_cron";

    private static final String DESCRIPTION =
            "Cancel a cron job previously created with schedule_cron. The id is the "
                    + "value returned by schedule_cron. Returns cancelled=false if no such job exists.";

    @Override
    public ToolSpecification getSpecification() {
        return ToolSpecification.builder()
                .name(NAME)
                .description(DESCRIPTION)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("job_id", "Job id returned by schedule_cron")
                        .required("job_id")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {
        Map<String, Object> args;
        try {
            args = JSONUtil.parseObj(request.arguments());
        } catch (RuntimeException e) {
            return "Error: arguments is not valid JSON: " + e.getMessage();
        }
        Object raw = args.get("job_id");
        if (raw == null) {
            return "Error: 'job_id' is required";
        }
        String jobId = String.valueOf(raw).trim();
        if (jobId.isEmpty()) {
            return "Error: 'job_id' is required";
        }

        // CronScheduler.cancel expects the on-disk filename (e.g. cron_<id>.json).
        String fileName = ScheduleCronTool.fileNameFor(jobId);
        boolean cancelled = CronScheduler.getInstance().cancel(fileName);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancelled", cancelled);
        payload.put("job_id", jobId);
        return JSONUtil.toJsonPrettyStr(payload);
    }
}
