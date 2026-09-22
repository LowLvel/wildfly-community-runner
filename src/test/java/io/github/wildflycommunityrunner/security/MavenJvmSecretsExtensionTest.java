package io.github.wildflycommunityrunner.security;

import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.execution.ParametersListUtil;
import org.jdom.Element;
import org.jdom.output.XMLOutputter;
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;
import org.jetbrains.idea.maven.execution.MavenRunnerSettings;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class MavenJvmSecretsExtensionTest extends BasePlatformTestCase {
    private static final class Process extends ProcessHandler {
        @Override protected void destroyProcessImpl() { notifyProcessTerminated(1); }
        @Override protected void detachProcessImpl() { notifyProcessDetached(); }
        @Override public boolean detachIsDefault() { return false; }
        @Override public OutputStream getProcessInput() { return null; }
        void finish() { notifyProcessTerminated(0); }
    }

    public void testRerunCreatesFreshArgumentsAndSavedConfigurationKeepsOnlyReferences() throws Exception {
        var secrets = new JvmSecrets(new JvmSecretsTest.MemoryStore());
        var sessions = new MavenSecretSessions(() -> secrets);
        try {
            String saved = background(() -> {
                try (var protection = secrets.protection()) {
                    String references = protection.protect("-Dpassword=synthetic-value");
                    protection.commit(); return references;
                }
            });
            var settings = new MavenRunnerSettings(); settings.setVmOptions(saved);
            var configuration = MavenRunConfigurationType.createRunnerAndConfigurationSettings(null, settings,
                    new MavenRunnerParameters(), getProject(), "WildFly test build", false).getConfiguration();
            assertTrue(new MavenJvmSecretsExtension().isApplicableFor((com.intellij.execution.configurations.RunConfigurationBase<?>) configuration));
            JavaParameters first = background(() -> parameters(saved, sessions));
            Path firstFile = argumentFile(first);
            Process firstProcess = attach(first, sessions); firstProcess.finish(); awaitDeleted(firstFile);
            JavaParameters rerun = background(() -> parameters(saved, sessions));
            Path secondFile = argumentFile(rerun);
            assertFalse(firstFile.equals(secondFile)); assertTrue(Files.exists(secondFile));
            Element xml = new Element("configuration"); configuration.writeExternal(xml);
            String serialized = new XMLOutputter().outputString(xml);
            assertTrue(serialized.contains("${secret:"));
            assertFalse(serialized.contains("synthetic-value"));
            assertFalse(serialized.contains("options.args"));
            Process secondProcess = attach(rerun, sessions); secondProcess.finish(); awaitDeleted(secondFile);
        } finally { sessions.dispose(); secrets.dispose(); }
    }

    public void testConcurrentExecutionsReleaseOnlyTheirOwnFiles() throws Exception {
        var secrets = new JvmSecrets(new JvmSecretsTest.MemoryStore());
        var sessions = new MavenSecretSessions(() -> secrets);
        try {
            JavaParameters first = background(() -> parameters("-Dpassword=first", sessions));
            JavaParameters second = background(() -> parameters("-Dpassword=second", sessions));
            Path firstFile = argumentFile(first), secondFile = argumentFile(second);
            Process firstProcess = attach(first, sessions), secondProcess = attach(second, sessions);
            firstProcess.finish(); awaitDeleted(firstFile); assertTrue(Files.exists(secondFile));
            secondProcess.finish(); awaitDeleted(secondFile);
        } finally { sessions.dispose(); secrets.dispose(); }
    }

    public void testProjectDisposalReleasesLaunchesWithoutAHandlerAndDetachesListeners() throws Exception {
        var secrets = new JvmSecrets(new JvmSecretsTest.MemoryStore());
        var sessions = new MavenSecretSessions(() -> secrets);
        try {
            JavaParameters pending = background(() -> parameters("-Dpassword=pending", sessions));
            JavaParameters running = background(() -> parameters("-Dpassword=running", sessions));
            Process process = attach(running, sessions);
            sessions.dispose();
            awaitDeleted(argumentFile(pending)); awaitDeleted(argumentFile(running));
            assertFalse(process.isProcessTerminated()); process.finish();
        } finally { sessions.dispose(); secrets.dispose(); }
    }

    public void testFastExitBeforeExtensionAttachmentStillReleasesArguments() throws Exception {
        var secrets = new JvmSecrets(new JvmSecretsTest.MemoryStore());
        var sessions = new MavenSecretSessions(() -> secrets);
        try {
            JavaParameters parameters = background(() -> parameters("-Dpassword=value", sessions));
            Process process = new Process(); process.startNotify(); process.finish();
            sessions.attach(process, "java " + ParametersListUtil.join(parameters.getVMParametersList().getList()));
            awaitDeleted(argumentFile(parameters));
        } finally { sessions.dispose(); secrets.dispose(); }
    }

    private static JavaParameters parameters(String options, MavenSecretSessions sessions) throws Exception {
        JavaParameters parameters = new JavaParameters(); parameters.getVMParametersList().addParametersString(options);
        MavenJvmSecretsExtension.updateParameters(parameters, sessions); return parameters;
    }
    private static Path argumentFile(JavaParameters parameters) {
        return Path.of(parameters.getVMParametersList().getList().stream().filter(value -> value.startsWith("@")).findFirst().orElseThrow().substring(1));
    }
    private static Process attach(JavaParameters parameters, MavenSecretSessions sessions) {
        Process process = new Process();
        sessions.attach(process, "java " + ParametersListUtil.join(parameters.getVMParametersList().getList()));
        process.startNotify(); return process;
    }
    private static <T> T background(java.util.concurrent.Callable<T> operation) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(operation).get(10, TimeUnit.SECONDS);
    }
    private static void awaitDeleted(Path path) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Files.exists(path) && System.nanoTime() < end) Thread.sleep(10);
        assertFalse("Private argument file was not released", Files.exists(path));
    }
}
