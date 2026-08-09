package com.vanilla.tool;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {

    public static final String NAME = "bash";

    private static final long DEFAULT_COMMAND_TIMEOUT_SECONDS = 120;

    /** Whether the current JVM is running on Windows. */
    private static final boolean IS_WINDOWS;
    static {
        String osName = System.getProperty("os.name", "");
        IS_WINDOWS = osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Shell candidates on Unix-like systems, tried in order. The first one that
     * successfully starts is used. {@code /bin/bash} is kept first to preserve
     * the previous behavior for existing users.
     */
    private static final List<List<String>> UNIX_SHELL_CANDIDATES = List.of(
            List.of("/bin/bash", "-c"),
            List.of("/usr/bin/bash", "-c"),
            List.of("bash", "-c"),
            List.of("/bin/sh", "-c"),
            List.of("sh", "-c")
    );

    /**
     * Shell candidates on Windows, tried in order. {@code cmd.exe} is always
     * available and is therefore placed last as the guaranteed fallback. Git
     * Bash / WSL bash (if installed) and PowerShell are tried first because
     * they expose a more familiar POSIX-like interface.
     */
    private static final List<List<String>> WINDOWS_SHELL_CANDIDATES = List.of(
            List.of("bash.exe", "-c"),
            List.of("C:\\Program Files\\Git\\bin\\bash.exe", "-c"),
            List.of("C:\\Program Files (x86)\\Git\\bin\\bash.exe", "-c"),
            List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command"),
            List.of("pwsh.exe", "-NoProfile", "-NonInteractive", "-Command"),
            List.of("cmd.exe", "/d", "/c")
    );

    @Override
    public ToolSpecification getSpecification() {
        String description = IS_WINDOWS
                ? "Run a shell command. On Windows, defaults to cmd.exe; PowerShell, Git Bash "
                        + "and WSL bash are tried automatically if available. You can force a "
                        + "specific shell via the optional 'shell' parameter ('cmd', 'powershell', "
                        + "'bash', etc.). On cmd.exe, prepend 'chcp 65001>nul && ' to your "
                        + "command to force UTF-8 output."
                : "Run a shell command. Uses /bin/bash when available, otherwise falls back to "
                        + "sh. Honors the optional 'shell', 'cwd' and 'timeout_seconds' parameters.";

        return ToolSpecification.builder()
                .name(NAME)
                .description(description)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("command", "The shell command to execute.")
                        .addStringProperty("shell",
                                "Optional. Override the shell. Accepts an alias such as "
                                        + "'cmd', 'powershell', 'pwsh', 'bash' or a full path. "
                                        + "The first available shell is used; fallbacks are tried "
                                        + "automatically if the requested one is missing.")
                        .addStringProperty("cwd",
                                "Optional. Working directory for the command. Defaults to the "
                                        + "current process working directory.")
                        .addNumberProperty("timeout_seconds",
                                "Optional. Maximum time (in seconds) to wait for the command. "
                                        + "Defaults to 120.")
                        .required("command")
                        .build())
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request) {

        if (request == null || request.arguments() == null) {
            return "Error: tool execution request or arguments cannot be null.";
        }

        final String command;
        final String shellOverride;
        final String cwd;
        final long timeoutSeconds;
        try {
            JSONObject args = JSONUtil.parseObj(request.arguments());
            command = args.getStr("command");
            shellOverride = args.getStr("shell");
            cwd = args.getStr("cwd");
            Object rawTimeout = args.get("timeout_seconds");
            timeoutSeconds = parseTimeoutSeconds(rawTimeout);
        } catch (RuntimeException e) {
            return "Error: invalid tool arguments: " + e.getMessage();
        }

        if (command == null || command.isBlank()) {
            return "Error: command cannot be empty.";
        }
        if (timeoutSeconds <= 0) {
            return "Error: timeout_seconds must be positive.";
        }

        File workingDirectory = null;
        if (cwd != null && !cwd.isBlank()) {
            workingDirectory = new File(cwd);
            if (!workingDirectory.isDirectory()) {
                return "Error: working directory does not exist or is not a directory: " + cwd;
            }
        }

        List<List<String>> candidates = resolveShellCandidates(shellOverride);

        // Try each shell candidate in order. If a candidate fails to start
        // (e.g. the executable is not installed), fall through to the next one.
        IOException lastError = null;
        for (List<String> shellCommand : candidates) {
            List<String> fullCommand = new ArrayList<>(shellCommand);
            fullCommand.add(command);

            ProcessBuilder processBuilder = new ProcessBuilder(fullCommand).redirectErrorStream(true);
            if (workingDirectory != null) {
                processBuilder.directory(workingDirectory);
            }

            Process process = null;
            ExecutorService outputReader = Executors.newSingleThreadExecutor();
            try {
                process = processBuilder.start();

                // Read output concurrently; otherwise a verbose command can fill the pipe
                // buffer and block before waitFor() gets a chance to return.
                Process runningProcess = process;
                Future<String> outputFuture = outputReader.submit(() -> readOutput(runningProcess));

                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    String output = getOutput(outputFuture);
                    return output + (output.isEmpty() ? "" : System.lineSeparator())
                            + "Error: command timed out after " + timeoutSeconds + " seconds.";
                }

                String output = getOutput(outputFuture);
                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    return output;
                }

                String result = output.isEmpty() ? "" : output + System.lineSeparator();
                return result + "Command exited with code " + exitCode + ".";
            } catch (IOException e) {
                // ProcessBuilder.start() failed for this candidate (e.g. program
                // not found). Remember the error and try the next shell.
                lastError = e;
            } catch (InterruptedException e) {
                if (process != null) {
                    process.destroyForcibly();
                }
                Thread.currentThread().interrupt();
                return "Error: command execution was interrupted.";
            } finally {
                outputReader.shutdownNow();
            }
        }

        if (lastError != null) {
            return "Error: failed to start shell (tried "
                    + candidateSummary(candidates) + "): " + lastError.getMessage()
                    + ". On Windows, ensure cmd.exe is reachable; otherwise pass "
                    + "'shell' explicitly (e.g. shell=\"C:\\\\Program Files\\\\Git\\\\bin\\\\bash.exe\").";
        }
        return "Error: no shell available to execute the command.";
    }

    /**
     * Resolve the timeout argument, accepting numbers and numeric strings and
     * falling back to the default when the argument is missing.
     *
     * @return parsed timeout in seconds, or the default when the input is null
     */
    private long parseTimeoutSeconds(Object raw) {
        if (raw == null) {
            return DEFAULT_COMMAND_TIMEOUT_SECONDS;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default below
            }
        }
        return DEFAULT_COMMAND_TIMEOUT_SECONDS;
    }

    /**
     * Build the ordered list of shell candidates to try. If a {@code shellOverride}
     * is provided, it is placed at the front of the platform-default list.
     */
    private List<List<String>> resolveShellCandidates(String shellOverride) {
        List<List<String>> defaults = IS_WINDOWS ? WINDOWS_SHELL_CANDIDATES : UNIX_SHELL_CANDIDATES;
        if (shellOverride == null || shellOverride.isBlank()) {
            return defaults;
        }

        String override = shellOverride.strip();
        List<List<String>> customized = new ArrayList<>();
        customized.add(buildOverrideCommand(override));
        customized.addAll(defaults);
        return customized;
    }

    /**
     * Translate a user-supplied shell alias into the full {@code program + args}
     * pair that ProcessBuilder needs.
     */
    private static List<String> buildOverrideCommand(String override) {
        String lower = override.toLowerCase(Locale.ROOT);
        if (lower.equals("cmd") || lower.equals("cmd.exe")) {
            return List.of(override, "/d", "/c");
        }
        if (lower.equals("powershell") || lower.equals("powershell.exe")
                || lower.equals("pwsh") || lower.equals("pwsh.exe")) {
            return List.of(override, "-NoProfile", "-NonInteractive", "-Command");
        }
        if (lower.equals("bash") || lower.equals("bash.exe") || lower.equals("sh")
                || lower.equals("sh.exe") || lower.equals("zsh") || lower.equals("zsh.exe")) {
            return List.of(override, "-c");
        }
        // Generic fallback: assume the executable follows POSIX conventions and
        // accepts "-c <command>". This works for most user-installed shells.
        return List.of(override, "-c");
    }

    private static String candidateSummary(List<List<String>> candidates) {
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                summary.append(", ");
            }
            summary.append(candidates.get(i).get(0));
        }
        return summary.toString();
    }

    private String getOutput(Future<String> outputFuture)
            throws InterruptedException, IOException {
        try {
            return outputFuture.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("failed to read command output", cause);
        }
    }

    private String readOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append(System.lineSeparator());
                }
                output.append(line);
            }
        }
        return output.toString();
    }
}
