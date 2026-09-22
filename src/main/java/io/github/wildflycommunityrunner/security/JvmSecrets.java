package io.github.wildflycommunityrunner.security;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.util.execution.ParametersListUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@Service(Service.Level.APP)
public final class JvmSecrets implements Disposable {
    interface Store { String get(String id); void set(String id, String value); }
    private final Store store;
    private final Set<PrivateJvmOptions> active = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService cleanup = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "WildFly private options cleanup"); thread.setDaemon(true); return thread;
    });
    private volatile boolean disposed;

    public JvmSecrets() {
        this(new Store() {
            private CredentialAttributes attributes(String id) {
                return new CredentialAttributes(CredentialAttributesKt.generateServiceName("WildFly Community Runner", id));
            }
            @Override public String get(String id) { return PasswordSafe.getInstance().getPassword(attributes(id)); }
            @Override public void set(String id, String value) { PasswordSafe.getInstance().setPassword(attributes(id), value); }
        });
    }
    JvmSecrets(Store store) { this.store = store; }
    public static JvmSecrets getInstance() { return ApplicationManager.getApplication().getService(JvmSecrets.class); }
    private void backgroundOnly() {
        var app = ApplicationManager.getApplication();
        if (app != null && app.isDispatchThread()) throw new IllegalStateException("Credentials must be accessed in a background task.");
        if (disposed) throw new IllegalStateException("WildFly credential handling is unavailable.");
    }

    public Protection protection() { backgroundOnly(); return new Protection(); }

    /** Roll back newly created entries unless their references were committed to settings. */
    public final class Protection implements AutoCloseable {
        private final List<String> created = new ArrayList<>();
        private boolean committed;
        private boolean closed;
        public synchronized String protect(String options) {
            backgroundOnly();
            if (closed) throw new IllegalStateException("The credential save was cancelled.");
            if (options == null || !SensitiveProperties.containsSensitive(options)) return options == null ? "" : options;
            SensitiveProperties.validateQuoting(options);
            List<String> arguments = new ArrayList<>(ParametersListUtil.parse(options));
            for (int i = 0; i < arguments.size(); i++) {
                String argument = arguments.get(i);
                if (!SensitiveProperties.protectedArgument(argument)) continue;
                int equals = argument.indexOf('=');
                String value = argument.substring(equals + 1);
                if (SensitiveProperties.REFERENCE.matcher(value).matches()
                        || SensitiveProperties.ENVIRONMENT.matcher(value).matches() || value.isEmpty()) continue;
                if (value.contains("${secret:") || value.contains("${env:"))
                    throw new IllegalArgumentException("A secret or environment reference must be the complete JVM property value.");
                String id = UUID.randomUUID().toString();
                created.add(id);
                try {
                    store.set(id, value);
                    if (!value.equals(store.get(id))) throw new IllegalStateException();
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("Could not save a JVM secret in the IDE Passwords store. Check Settings | Appearance & Behavior | System Settings | Passwords.");
                }
                arguments.set(i, argument.substring(0, equals + 1) + "${secret:" + id + "}");
            }
            return ParametersListUtil.join(arguments);
        }
        public synchronized void commit() {
            if (closed && !committed) throw new IllegalStateException("The credential save was cancelled.");
            committed = true;
        }
        public void rollbackAsync() { JvmSecrets.this.rollback(this); }
        @Override public synchronized void close() {
            closed = true;
            if (committed) return;
            for (String id : created) try { store.set(id, null); } catch (RuntimeException ignored) { }
            created.clear();
        }
    }

    public PrivateJvmOptions prepare(String options) throws IOException {
        backgroundOnly();
        String original = options == null ? "" : options;
        SensitiveProperties.validateQuoting(original);
        List<String> visible = new ArrayList<>(), protectedArgs = new ArrayList<>(), values = new ArrayList<>();
        for (String argument : ParametersListUtil.parse(original)) {
            if (!SensitiveProperties.protectedArgument(argument)) { visible.add(argument); continue; }
            int equals = argument.indexOf('=');
            String value = argument.substring(equals + 1);
            var reference = SensitiveProperties.REFERENCE.matcher(value);
            if (reference.matches()) {
                try { value = store.get(reference.group(1)); }
                catch (RuntimeException failure) { throw new IllegalStateException("The IDE Passwords store could not be opened. Re-enter the sensitive JVM property or unlock the store."); }
                if (value == null) throw new IllegalStateException("A saved JVM secret is unavailable. Re-enter its value in the JVM options field and save the profile.");
            } else if (SensitiveProperties.ENVIRONMENT.matcher(value).matches()) {
                var environment = SensitiveProperties.ENVIRONMENT.matcher(value);
                environment.matches();
                value = System.getenv(environment.group(1));
                if (value == null) throw new IllegalStateException("The environment variable " + environment.group(1) + " is unavailable. Set it before starting the IDE.");
            } else if (value.contains("${secret:") || value.contains("${env:")) {
                throw new IllegalArgumentException("A secret reference must be the complete JVM property value.");
            }
            values.add(value);
            protectedArgs.add(argument.substring(0, equals + 1) + value);
        }
        PrivateJvmOptions prepared = PrivateJvmOptions.create(original, visible, protectedArgs, values);
        return track(prepared);
    }

    public PrivateJvmOptions prepareGradleLauncher(PrivateJvmOptions daemon) throws IOException {
        backgroundOnly();
        return track(PrivateJvmOptions.gradleLauncher(daemon, java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))));
    }

    private PrivateJvmOptions track(PrivateJvmOptions prepared) {
        active.add(prepared);
        if (disposed) { prepared.close(); active.remove(prepared); throw new IllegalStateException("WildFly integration has been disposed."); }
        return prepared;
    }

    public void release(PrivateJvmOptions prepared) {
        if (prepared == null) return;
        Runnable close = () -> { prepared.close(); if (prepared.isClosed()) active.remove(prepared); };
        try { cleanup.execute(close); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { close.run(); }
    }

    public void rollback(Protection protection) {
        if (protection == null) return;
        try { cleanup.execute(protection::close); }
        catch (java.util.concurrent.RejectedExecutionException stopped) {
            java.util.concurrent.CompletableFuture.runAsync(protection::close);
        }
    }

    @Override public void dispose() {
        disposed = true;
        for (PrivateJvmOptions prepared : active) release(prepared);
        cleanup.shutdown();
    }
}
