package io.github.wildflycommunityrunner.services;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import static org.junit.Assert.*;

public class WindowsProcessQueryTest {
    @Test public void parsesQuotedPathsAndEmptyArguments() {
        assertEquals(List.of("C:\\Program Files\\Java\\bin\\java.exe", "-Dhome=C:\\WildFly Home", "", "last"),
                WindowsProcessQuery.parseCommandLine("\"C:\\Program Files\\Java\\bin\\java.exe\" \"-Dhome=C:\\WildFly Home\" \"\"\tlast"));
    }

    @Test public void preservesBackslashesAndEscapedQuotes() {
        String slash = "\\";
        assertEquals(List.of("abc" + slash.repeat(2), "next"),
                WindowsProcessQuery.parseCommandLine("abc" + slash.repeat(2) + " next"));
        assertEquals(List.of("a" + slash + "\"b", "c"),
                WindowsProcessQuery.parseCommandLine("a" + slash.repeat(3) + "\"b c"));
        assertEquals(List.of("a" + slash.repeat(2) + "b c"),
                WindowsProcessQuery.parseCommandLine("a" + slash.repeat(4) + "\"b c\""));
        assertEquals(List.of("quoted\"quote"), WindowsProcessQuery.parseCommandLine("\"quoted\"\"quote\""));
    }

    @Test public void rejectsTruncatedQuotesAndDoesNotTreatNewlineAsArgumentSeparator() {
        assertTrue(WindowsProcessQuery.parseCommandLine("java \"unfinished").isEmpty());
        assertEquals(List.of("a\nb", "c"), WindowsProcessQuery.parseCommandLine("a\nb c"));
    }

    @Test public void requiresCompleteRowsAndMatchingExecutable() {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
        String valid = row("123", "456", executable, "\"" + executable + "\" -jar example.jar");
        var parsed = WindowsProcessQuery.parseRows(valid + "\r\ninvalid\r\n" + row("0", "456", executable, executable));
        assertEquals(1, parsed.size());
        assertEquals(123, parsed.getFirst().pid());
        assertEquals(456, parsed.getFirst().startedMillis());
        assertEquals(List.of("-jar", "example.jar"), parsed.getFirst().arguments());
        assertTrue(WindowsProcessQuery.parseRows(row("123", "456", executable, "different.exe -jar a.jar")).isEmpty());
        assertTrue(WindowsProcessQuery.parseRows("123\t456\t%invalid\tbase64").isEmpty());
    }

    @Test public void acceptsTheImplicitExeSuffixUsedByStandaloneBat() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
        assertEquals(1, WindowsProcessQuery.parseRows(row("123", "456", java + ".exe", "\"" + java + "\" -jar server.jar")).size());
        assertTrue(WindowsProcessQuery.parseRows(row("123", "456", java + ".exe", "java -jar server.jar")).isEmpty());
        assertTrue(WindowsProcessQuery.parseRows(row("123", "456", java + ".exe", "\"" + java + "-other\" -jar server.jar")).isEmpty());
    }

    private static String row(String pid, String started, String executable, String command) {
        var encoder = Base64.getEncoder();
        return pid + "\t" + started + "\t" + encoder.encodeToString(executable.getBytes(StandardCharsets.UTF_8))
                + "\t" + encoder.encodeToString(command.getBytes(StandardCharsets.UTF_8));
    }
}
