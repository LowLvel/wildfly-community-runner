package io.github.wildflycommunityrunner.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Scoped to an execution; credentials are not retained in a global logging cache. */
public final class SecretRedactor {
    public static final com.intellij.openapi.util.Key<SecretRedactor> PROCESS = com.intellij.openapi.util.Key.create("wildfly.secret.redactor");
    private final List<String> values;
    public SecretRedactor(Collection<String> values) {
        List<String> fragments = new ArrayList<>();
        for (String value : values) {
            if (!value.isEmpty()) fragments.add(value);
            for (String line : value.split("[\\r\\n]")) if (!line.isEmpty()) fragments.add(line);
        }
        this.values = fragments.stream().distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }
    public String redact(String text) {
        if (text == null) return "";
        if (!values.isEmpty()) text = java.util.regex.Pattern.compile(values.stream().map(java.util.regex.Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"))).matcher(text).replaceAll("[redacted]");
        return SensitiveProperties.redactProperties(text);
    }

    /** Chunk boundaries must not expose half a password. Oversized lines are omitted completely. */
    public final class Lines {
        private static final int LIMIT = 65_536;
        private final StringBuilder pending = new StringBuilder();
        private final Consumer<String> output;
        private boolean dropping;
        public Lines(Consumer<String> output) { this.output = output; }
        public synchronized void accept(String chunk) {
            for (int i = 0; i < chunk.length(); i++) {
                char ch = chunk.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    if (dropping) { dropping = false; output.accept("[Oversized output line omitted]\n"); }
                    else { output.accept(redact(pending.toString()) + ch); pending.setLength(0); }
                } else if (!dropping) {
                    pending.append(ch);
                    if (pending.length() > LIMIT) { pending.setLength(0); dropping = true; }
                }
            }
        }
        public synchronized void finish() {
            if (dropping) output.accept("[Oversized output line omitted]\n");
            else if (!pending.isEmpty()) output.accept(redact(pending.toString()));
            pending.setLength(0); dropping = false;
        }
    }
}
