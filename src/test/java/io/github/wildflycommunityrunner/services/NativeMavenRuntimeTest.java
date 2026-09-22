package io.github.wildflycommunityrunner.services;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.execution.ParametersListUtil;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.security.SecretRedactor;
import io.github.wildflycommunityrunner.util.IdeUi;
import org.jdom.Element;
import org.jdom.output.XMLOutputter;
import org.jetbrains.idea.maven.execution.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.wildflycommunityrunner.RuntimeTestSupport.*;

/** Executes IntelliJ's real Maven runner, including the public Java-parameters extension and native rerun. */
public class NativeMavenRuntimeTest extends BasePlatformTestCase {
    public void testNativeMavenSecretDeliveryCleanupAndRerun() throws Exception {
        var temporary = new TemporaryFolder(); temporary.create();
        boolean trusted = TrustedProjects.isProjectTrusted(getProject());
        var runner = MavenRunner.getInstance(getProject());
        String previousJre = runner.getSettings().getJreName();
        String previousOptions = runner.getSettings().getVmOptions();
        Registry.get("maven.use.scripts").setValue(false, getTestRootDisposable());
        var captured = new AtomicReference<ExecutionEnvironment>();
        var handlers = new CopyOnWriteArrayList<ProcessHandler>();
        var argumentFiles = new CopyOnWriteArrayList<Path>();
        var output = new CopyOnWriteArrayList<String>();
        var operations = new ArrayList<BuildOperation>();
        var connection = getProject().getMessageBus().connect(getTestRootDisposable());
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override public void processStarting(String executorId, ExecutionEnvironment environment, ProcessHandler handler) {
                if (!(environment.getRunProfile() instanceof MavenRunConfiguration)) return;
                captured.set(environment); handlers.add(handler);
                if (handler instanceof OSProcessHandler process) {
                    for (String argument : ParametersListUtil.parse(process.getCommandLine()))
                        if (argument.startsWith("@")) argumentFiles.add(Path.of(argument.substring(1)));
                }
                handler.addProcessListener(new ProcessListener() {
                    @Override public void onTextAvailable(ProcessEvent event, Key type) { output.add(event.getText()); }
                });
            }
        });
        try {
            TrustedProjects.setProjectTrusted(getProject(), true);
            runner.getSettings().setJreName(MavenRunnerSettings.USE_INTERNAL_JAVA);
            runner.getSettings().setVmOptions("");
            var service = new ServiceProfile(); service.name = "Native Maven smoke"; service.deployAfterBuild = false;
            Path pom = temporary.newFolder("Maven module with spaces").toPath().resolve("pom.xml");
            Files.writeString(pom, """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
                      <artifactId>${wildfly.fixture.password}</artifactId>
                      <version>1</version><packaging>pom</packaging>
                    </project>
                    """);
            service.buildFilePath = pom.toString(); service.buildTasks = "validate"; service.buildArguments = "-q -o";
            // This negative control proves the model needs the delivered JVM property.
            var control = BuildService.build(getProject(), service, output::add); operations.add(control);
            await(control.completion()::isDone, Duration.ofSeconds(90), "Native Maven control launch timed out");
            assertEquals(diagnostic(output), BuildOperation.Outcome.FAILED, control.completion().join().outcome());
            service.buildJvmOptions = "-Dwildfly.fixture.password=${env:WILDFLY_SMOKE_PASSWORD}";
            var first = BuildService.build(getProject(), service, output::add); operations.add(first);
            await(first.completion()::isDone, Duration.ofSeconds(90), "Native Maven launch timed out");
            assertEquals(diagnostic(output), BuildOperation.Outcome.SUCCESS, first.completion().join().outcome());
            var environment = captured.get(); assertNotNull(environment);
            var configuration = (MavenRunConfiguration) environment.getRunProfile();
            assertEquals(service.buildJvmOptions, configuration.getRunnerSettings().getVmOptions());
            var xml = new Element("configuration"); configuration.writeExternal(xml);
            String serialized = new XMLOutputter().outputString(xml);
            assertTrue(serialized.contains("${env:WILDFLY_SMOKE_PASSWORD}"));
            assertFalse(serialized.contains(System.getenv("WILDFLY_SMOKE_PASSWORD")));
            assertEquals("One private argument file per secret-bearing native launch", 1, argumentFiles.size());
            await(() -> argumentFiles.stream().noneMatch(Files::exists), Duration.ofSeconds(10), "Maven exit did not delete the private argument file");

            var rerun = new BuildOperation(action -> ApplicationManager.getApplication().executeOnPooledThread(action));
            operations.add(rerun);
            var next = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), environment.getRunnerAndConfigurationSettings()).build();
            new BuildExecutionListener(getProject(), next, rerun);
            IdeUi.later(getProject(), () -> {
                try { assertTrue(rerun.beginLaunch()); next.getRunner().execute(next); }
                catch (Exception failure) { rerun.failed(failure); }
            });
            await(rerun.completion()::isDone, Duration.ofSeconds(90), "Native Maven rerun timed out");
            assertEquals(diagnostic(output), BuildOperation.Outcome.SUCCESS, rerun.completion().join().outcome());
            assertEquals(2, argumentFiles.size());
            assertFalse(argumentFiles.get(0).equals(argumentFiles.get(1)));
            await(() -> argumentFiles.stream().noneMatch(Files::exists), Duration.ofSeconds(10), "Maven rerun did not release its private file");
        } finally {
            connection.disconnect(); operations.forEach(BuildOperation::cancel);
            for (ProcessHandler handler : handlers) {
                if (!handler.isProcessTerminated()) handler.destroyProcess();
                await(handler::isProcessTerminated, Duration.ofSeconds(30), "Maven fixture cleanup timed out");
            }
            var manager = ExecutionManager.getInstance(getProject()).getContentManager();
            for (var descriptor : List.copyOf(manager.getAllDescriptors())) {
                if (handlers.contains(descriptor.getProcessHandler())) manager.removeRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
            }
            runner.getSettings().setJreName(previousJre); runner.getSettings().setVmOptions(previousOptions);
            TrustedProjects.setProjectTrusted(getProject(), trusted); temporary.delete();
        }
    }
    private static String diagnostic(List<String> lines) {
        String text = new SecretRedactor(List.of(System.getenv("WILDFLY_SMOKE_PASSWORD"))).redact(String.join("", lines));
        return text.substring(Math.max(0, text.length() - 12000));
    }
}
