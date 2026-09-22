package io.github.wildflycommunityrunner.security;

import com.intellij.util.execution.ParametersListUtil;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import org.junit.Test;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class JvmSecretsTest {
    static final class MemoryStore implements JvmSecrets.Store {
        final Map<String, String> values = new HashMap<>();
        boolean fail;
        @Override public String get(String id) { return values.get(id); }
        @Override public void set(String id, String value) {
            if (value == null) values.remove(id);
            else if (fail) throw new IllegalStateException("Store unavailable");
            else values.put(id, value);
        }
    }

    @Test public void savesOnlyReferencesAndResolvesQuotedValuesIntoPrivateFiles() throws Exception {
        var store = new MemoryStore(); var secrets = new JvmSecrets(store);
        String value = "test value with \"quotes\" and \\ slash";
        String original = ParametersListUtil.join("-Ddb.password=" + value, "-Doracle.net.tns_admin=C:\\Oracle\\TNS path");
        try {
            String saved;
            try (var protection = secrets.protection()) {
                saved = protection.protect(original);
                assertFalse(saved.contains(value));
                assertTrue(saved.contains("${secret:"));
                protection.commit();
            }
            assertEquals(1, store.values.size());
            try (var protection = secrets.protection()) { assertEquals(saved, protection.protect(saved)); protection.commit(); }
            assertEquals(1, store.values.size());
            try (var runtime = secrets.prepare(saved)) {
                assertFalse(runtime.options().contains(value));
                assertFalse(runtime.options().contains("${secret:"));
                assertTrue(runtime.options().contains("oracle.net.tns_admin"));
                assertTrue(Files.readString(runtime.file()).contains("-Ddb.password="));
                assertEquals("[redacted]", runtime.redactor().redact(value));
            }
        } finally { secrets.dispose(); }
    }

    @Test public void ordinaryJvmAndOracleOptionsStayUnchangedAndDoNotTouchCredentials() throws Exception {
        var store = new MemoryStore(); var secrets = new JvmSecrets(store);
        String options = "-Xmx1g  -Doracle.net.tns_admin=\"C:\\Oracle TNS\" -Djavax.net.ssl.keyStore=keys.jks";
        try {
            try (var protection = secrets.protection()) { assertEquals(options, protection.protect(options)); }
            try (var runtime = secrets.prepare(options)) {
                assertEquals(options, runtime.options()); assertFalse(runtime.containsSecrets());
            }
            assertTrue(store.values.isEmpty());
        } finally { secrets.dispose(); }
    }

    @Test public void cancelledProtectionRemovesOnlyItsNewEntries() {
        var store = new MemoryStore(); var secrets = new JvmSecrets(store);
        store.values.put("existing", "unchanged");
        try {
            try (var protection = secrets.protection()) {
                protection.protect("-Dpassword=temporary");
                assertEquals(2, store.values.size());
            }
            assertEquals(Map.of("existing", "unchanged"), store.values);
        } finally { secrets.dispose(); }
    }

    @Test public void failedStoreDoesNotChangeExistingSettingsOrIncludeValuesInErrors() {
        var store = new MemoryStore(); store.fail = true;
        var secrets = new JvmSecrets(store);
        var settings = new WildFlyProjectSettings();
        var service = new ServiceProfile(); service.buildJvmOptions = "-Dpassword=previous-private-value";
        settings.update(state -> state.services.add(service));
        try {
            var error = assertThrows(IllegalStateException.class, () -> SensitiveSettingsMigration.migrateService(secrets,
                    settings.services().getFirst(), edit -> settings.update(state -> state.services.forEach(edit))));
            assertFalse(error.getMessage().contains("previous-private-value"));
            assertEquals(service.buildJvmOptions, settings.services().getFirst().buildJvmOptions);
            assertTrue(store.values.isEmpty());
        } finally { secrets.dispose(); }
    }

    @Test public void migrationCannotOverwriteAnEditMadeWhileCredentialStorageWasOpen() {
        var store = new MemoryStore(); var secrets = new JvmSecrets(store);
        var settings = new WildFlyProjectSettings();
        var service = new ServiceProfile(); service.buildJvmOptions = "-Dpassword=old-value";
        settings.update(state -> state.services.add(service));
        var snapshot = settings.services().getFirst();
        settings.update(state -> state.services.getFirst().buildJvmOptions = "-Xmx2g");
        try {
            SensitiveSettingsMigration.migrateService(secrets, snapshot, edit -> settings.update(state -> state.services.forEach(edit)));
            assertEquals("-Xmx2g", settings.services().getFirst().buildJvmOptions);
            assertTrue(store.values.isEmpty());
        } finally { secrets.dispose(); }
    }

    @Test public void legacySensitiveCliPropertiesAreProtectedButNeverReinterpretedAsJvmOptions() {
        var store = new MemoryStore(); var secrets = new JvmSecrets(store);
        var settings = new WildFlyProjectSettings();
        var service = new ServiceProfile(); service.buildArguments = "-Dpassword=old-value";
        settings.update(state -> state.services.add(service));
        try {
            SensitiveSettingsMigration.migrateService(secrets, settings.services().getFirst(),
                    edit -> settings.update(state -> state.services.forEach(edit)));
            var saved = settings.services().getFirst();
            assertFalse(saved.buildArguments.contains("old-value"));
            assertEquals("", saved.buildJvmOptions);
            assertThrows(IllegalArgumentException.class, () -> SensitiveProperties.requireJvmField(saved.buildArguments, "build arguments"));
        } finally { secrets.dispose(); }
    }

    @Test public void missingSecretsAndInvalidQuotingFailBeforeCreatingArgumentFiles() {
        var store = new MemoryStore(); var secrets = new JvmSecrets(store);
        try {
            assertThrows(IllegalStateException.class, () -> secrets.prepare("-Dpassword=${secret:00000000-0000-0000-0000-000000000000}"));
            assertThrows(IllegalArgumentException.class, () -> secrets.prepare("-Dpassword='two words'"));
            assertThrows(IllegalArgumentException.class, () -> secrets.prepare("-Dcustom=prefix${secret:00000000-0000-0000-0000-000000000000}"));
        } finally { secrets.dispose(); }
    }

    @Test public void cleanupIsIdempotentAndPermissionsExcludeOtherUsers() throws Exception {
        var secrets = new JvmSecrets(new MemoryStore());
        try {
            var options = secrets.prepare("-Dpassword=temporary-value");
            var file = options.file();
            var posix = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            if (posix != null) assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
            else {
                var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
                assertNotNull(acl);
                assertTrue(acl.getAcl().stream().filter(entry -> entry.type() == java.nio.file.attribute.AclEntryType.ALLOW)
                        .allMatch(entry -> entry.principal().equals(aclOwner(acl))));
            }
            options.close(); options.close();
            assertFalse(Files.exists(file)); assertFalse(Files.exists(file.getParent()));
        } finally { secrets.dispose(); }
    }

    private static java.nio.file.attribute.UserPrincipal aclOwner(java.nio.file.attribute.AclFileAttributeView acl) {
        try { return acl.getOwner(); } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
}
