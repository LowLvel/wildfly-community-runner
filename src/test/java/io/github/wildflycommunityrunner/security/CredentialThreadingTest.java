package io.github.wildflycommunityrunner.security;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.openapi.application.ApplicationManager;
import java.util.concurrent.TimeUnit;

public class CredentialThreadingTest extends BasePlatformTestCase {
    public void testCredentialsRejectEdtAccessAndWorkOnTheBackgroundExecutor() throws Exception {
        var store = new JvmSecretsTest.MemoryStore();
        var secrets = new JvmSecrets(store);
        try {
            assertTrue(ApplicationManager.getApplication().isDispatchThread());
            org.junit.Assert.assertThrows(IllegalStateException.class, secrets::protection);
            org.junit.Assert.assertThrows(IllegalStateException.class, () -> secrets.prepare("-Dpassword=value"));
            assertTrue(store.values.isEmpty());
            var result = ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try (var protection = secrets.protection()) { return protection.protect("-Dpassword=value"); }
            }).get(10, TimeUnit.SECONDS);
            assertTrue(result.contains("${secret:"));
            assertTrue(store.values.isEmpty());
        } finally { secrets.dispose(); }
    }
}
