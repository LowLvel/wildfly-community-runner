package io.github.wildflycommunityrunner.security;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SecretRedactorTest {
    @Test public void recognizesCredentialNamesWithoutHidingPathHelpersOrEnabledFlags() {
        for (String name : List.of("db.password", "javax.net.ssl.trustStorePassword", "api_key", "accessToken", "aws.secretAccessKey"))
            assertTrue(name, SensitiveProperties.sensitiveName(name));
        for (String name : List.of("oracle.net.tns_admin", "javax.net.ssl.keyStore", "javax.net.ssl.trustStore", "token.enabled"))
            assertFalse(name, SensitiveProperties.sensitiveName(name));
    }
    @Test public void splitProcessChunksNeverExposePartialSecrets() {
        var text = new StringBuilder();
        var lines = new SecretRedactor(List.of("private-value")).new Lines(text::append);
        lines.accept("value=pri"); assertEquals("", text.toString());
        lines.accept("vate-va"); assertEquals("", text.toString());
        lines.accept("lue\nordinary\n");
        assertEquals("value=[redacted]\nordinary\n", text.toString());
        lines.accept("-Dpassword=also-hidden"); lines.finish();
        assertFalse(text.toString().contains("also-hidden"));
    }
    @Test public void oversizedLinesAreOmittedUntilTheNextNewline() {
        var text = new StringBuilder();
        var lines = new SecretRedactor(List.of("private-value")).new Lines(text::append);
        lines.accept("x".repeat(70_000) + "private-");
        lines.accept("value\nnext\n");
        assertEquals("[Oversized output line omitted]\nnext\n", text.toString());
    }
    @Test public void masksQuotedPropertiesAndKnownValuesWithoutRegexInterpretation() {
        var redactor = new SecretRedactor(List.of("a$\\b[1]"));
        assertEquals("[redacted] -Ddb.password=[redacted] -Dordinary=visible",
                redactor.redact("a$\\b[1] -Ddb.password=\"two words\" -Dordinary=visible"));
    }
    @Test public void wholeQuotedAndNestedJvmArgumentsAreRedactedWithoutLeakingTheirTails() {
        String result = SensitiveProperties.redactProperties("\"-Dpassword=two secret words\" -Dorg.gradle.jvmargs=\"-Dtoken=nested-private\"");
        assertFalse(result.contains("two secret words"));
        assertFalse(result.contains("nested-private"));
        assertTrue(result.contains("-Dorg.gradle.jvmargs"));
    }
}
