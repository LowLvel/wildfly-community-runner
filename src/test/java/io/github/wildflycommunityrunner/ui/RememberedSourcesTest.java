package io.github.wildflycommunityrunner.ui;

import org.junit.Test;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RememberedSourcesTest {
    @Test public void missingAndInvalidPathsRemainRepairableWithoutAssumingTheyWereDeleted() throws Exception {
        var file = Files.createTempFile("remembered", ".xml");
        try {
            assertEquals("Available", RememberedSourcesDialog.availability(file.toString()));
            assertTrue(RememberedSourcesDialog.availability(file.resolveSibling("missing-pom.xml").toString()).startsWith("Unavailable"));
            assertTrue(RememberedSourcesDialog.availability("bad" + (char) 0 + "path").startsWith("Unavailable"));
            assertTrue(RememberedSourcesDialog.availability("pom.xml").startsWith("Relative path"));
            assertTrue(Files.exists(file));
        } finally { Files.deleteIfExists(file); }
    }
}
