package io.github.wildflycommunityrunner.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** JDK 21's Windows ProcessHandle does not expose argv. Query only local Java processes through CIM. */
final class WindowsProcessQuery {
    record Command(long pid, long startedMillis, String executable, List<String> arguments) {}
    private record Snapshot(long created, List<Command> commands) {}
    private static Snapshot cached = new Snapshot(0, List.of());
    private static final int MAX_OUTPUT = 1_048_576;
    private static final String QUERY = """
            $ErrorActionPreference = 'Stop'
            Get-CimInstance -ClassName Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -OperationTimeoutSec 2 | ForEach-Object {
              if ($_.ExecutablePath -and $_.CommandLine -and $_.CreationDate) {
                $created = ([DateTimeOffset]$_.CreationDate).ToUnixTimeMilliseconds()
                $exe = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_.ExecutablePath))
                $argsText = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_.CommandLine))
                [Console]::WriteLine([String]::Join([char]9, @([string]$_.ProcessId, [string]$created, $exe, $argsText)))
              }
            }
            """;

    private WindowsProcessQuery() {}

    static synchronized List<Command> read(boolean fresh) {
        ProgressManager.checkCanceled();
        if (!fresh && System.nanoTime() - cached.created() < TimeUnit.SECONDS.toNanos(1)) return cached.commands();
        List<Command> commands = query();
        cached = new Snapshot(System.nanoTime(), commands);
        return commands;
    }

    private static List<Command> query() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) return List.of();
        Process process = null;
        Future<byte[]> output = null;
        try {
            Path executable = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            // EncodedCommand preserves the constant script's quotes; no profile/user text is inserted into it.
            String encoded = Base64.getEncoder().encodeToString(QUERY.getBytes(StandardCharsets.UTF_16LE));
            process = new ProcessBuilder(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process query = process;
            output = ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try (var stream = query.getInputStream()) { return stream.readNBytes(MAX_OUTPUT + 1); }
            });
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) return List.of();
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (bytes.length > MAX_OUTPUT) return List.of();
            return parseRows(new String(bytes, StandardCharsets.US_ASCII));
        } catch (ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception unavailable) {
            // Restricted CIM, unavailable PowerShell, or a timeout leaves the process unverified.
            return List.of();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (output != null && !output.isDone()) output.cancel(true);
        }
    }

    static List<Command> parseRows(String text) {
        List<Command> commands = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 4) continue;
            try {
                long pid = Long.parseLong(fields[0]);
                long created = Long.parseLong(fields[1]);
                String executable = new String(Base64.getDecoder().decode(fields[2]), StandardCharsets.UTF_8);
                String commandLine = new String(Base64.getDecoder().decode(fields[3]), StandardCharsets.UTF_8);
                List<String> argv = parseCommandLine(commandLine);
                if (pid <= 0 || created <= 0 || argv.isEmpty() || !Path.of(argv.getFirst()).normalize().equals(Path.of(executable).normalize())) continue;
                commands.add(new Command(pid, created, executable, List.copyOf(argv.subList(1, argv.size()))));
            } catch (IllegalArgumentException ignored) { /* Never guess from a truncated or malformed row. */ }
        }
        return List.copyOf(commands);
    }

    /** Microsoft CRT quoting: 2n backslashes before quotes escape n slashes; 2n+1 also escapes the quote. */
    static List<String> parseCommandLine(String commandLine) {
        List<String> result = new ArrayList<>();
        int position = 0;
        while (position < commandLine.length()) {
            while (position < commandLine.length() && isSeparator(commandLine.charAt(position))) position++;
            if (position == commandLine.length()) break;
            StringBuilder argument = new StringBuilder();
            boolean quoted = false;
            while (position < commandLine.length()) {
                char character = commandLine.charAt(position);
                if (!quoted && isSeparator(character)) break;
                int slashes = 0;
                while (position < commandLine.length() && commandLine.charAt(position) == '\\') { slashes++; position++; }
                if (position < commandLine.length() && commandLine.charAt(position) == '"') {
                    argument.append("\\".repeat(slashes / 2));
                    position++;
                    if (slashes % 2 == 1) argument.append('"');
                    else if (quoted && position < commandLine.length() && commandLine.charAt(position) == '"') {
                        argument.append('"');
                        position++;
                    } else quoted = !quoted;
                } else {
                    argument.append("\\".repeat(slashes));
                    if (position < commandLine.length() && !quoted && isSeparator(commandLine.charAt(position))) break;
                    if (position < commandLine.length()) argument.append(commandLine.charAt(position++));
                }
            }
            if (quoted) return List.of();
            result.add(argument.toString());
        }
        return List.copyOf(result);
    }

    private static boolean isSeparator(char character) { return character == ' ' || character == '\t'; }
}
