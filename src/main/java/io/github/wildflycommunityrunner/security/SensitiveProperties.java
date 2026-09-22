package io.github.wildflycommunityrunner.security;

import com.intellij.util.execution.ParametersListUtil;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Property names remain visible; only their values are treated as credentials. */
public final class SensitiveProperties {
    public static final Pattern REFERENCE = Pattern.compile("\\$\\{secret:([0-9a-fA-F-]{36})}");
    public static final Pattern ENVIRONMENT = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern ASSIGNMENT = Pattern.compile("(-D([A-Za-z0-9_.-]+)=)(\"(?:\\\\.|[^\"])*\"|'[^']*'|[^\\s]+)");
    private SensitiveProperties() {}

    public static boolean sensitiveName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return List.of("password", "passwd", "pwd", "secret", "token", "credential", "credentials",
                "apikey", "accesskey", "privatekey", "secretkey").stream().anyMatch(normalized::endsWith);
    }

    static boolean protectedArgument(String argument) {
        if (!argument.startsWith("-D")) return false;
        int equals = argument.indexOf('=');
        if (equals <= 2) return false;
        if (sensitiveName(argument.substring(2, equals)) || argument.contains("${secret:") || argument.contains("${env:")) return true;
        var nested = ASSIGNMENT.matcher(argument.substring(equals + 1));
        while (nested.find()) if (sensitiveName(nested.group(2))) return true;
        return false;
    }

    public static boolean containsSensitive(String options) {
        if (options == null) return false;
        if (options.contains("${secret:") || options.contains("${env:")) return true;
        var matcher = ASSIGNMENT.matcher(options);
        while (matcher.find()) if (sensitiveName(matcher.group(2))) return true;
        return ParametersListUtil.parse(options).stream().anyMatch(SensitiveProperties::protectedArgument);
    }

    public static void requireJvmField(String arguments, String field) {
        if (containsSensitive(arguments)) throw new IllegalArgumentException(
                "Sensitive properties in " + field + " must be moved to the JVM options field or the build tool's credential configuration.");
    }

    static void validateQuoting(String options) {
        var matcher = ASSIGNMENT.matcher(options);
        while (matcher.find()) if (sensitiveName(matcher.group(2)) && matcher.group(3).startsWith("'"))
            throw new IllegalArgumentException("Use double quotes around sensitive JVM values containing spaces.");
    }

    public static String redactProperties(String text) {
        if (text == null) return "";
        // Scan overlapping assignments too: org.gradle.jvmargs may contain another -D property.
        java.util.List<int[]> ranges = new java.util.ArrayList<>();
        int position = 0;
        while ((position = text.indexOf("-D", position)) >= 0) {
            var matcher = ASSIGNMENT.matcher(text).region(position, text.length());
            if (matcher.lookingAt() && sensitiveName(matcher.group(2))) {
                int start = matcher.start(3), end = matcher.end(3);
                if (position > 0 && (text.charAt(position - 1) == '"' || text.charAt(position - 1) == '\'')) {
                    char quote = text.charAt(position - 1);
                    end = quotedEnd(text, start, quote);
                } else if (text.charAt(start) == '"' || text.charAt(start) == '\'') {
                    int closing = quotedEnd(text, start + 1, text.charAt(start));
                    end = closing < text.length() ? closing + 1 : closing;
                }
                if (!ranges.isEmpty() && ranges.getLast()[1] >= start) ranges.getLast()[1] = Math.max(ranges.getLast()[1], end);
                else ranges.add(new int[]{start, end});
            }
            position += 2;
        }
        StringBuilder result = new StringBuilder();
        int copied = 0;
        for (int[] range : ranges) {
            result.append(text, copied, range[0]).append("[redacted]");
            copied = range[1];
        }
        result.append(text, copied, text.length());
        return result.toString();
    }

    private static int quotedEnd(String text, int start, char quote) {
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == quote && !escaped) return i;
            escaped = ch == '\\' && !escaped;
        }
        return text.length();
    }
}
