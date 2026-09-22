package io.github.wildflycommunityrunner.services;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType;
import org.jetbrains.idea.maven.execution.MavenRunnerParameters;

public class BuildExecutionListenerTest extends BasePlatformTestCase {
    private ExecutionEnvironment environment() throws Exception {
        var settings = MavenRunConfigurationType.createRunnerAndConfigurationSettings(
                null, null, new MavenRunnerParameters(), getProject(), "WildFly test build", false);
        return ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build();
    }

    public void testOnlyOwnedEnvironmentBindsBeforeStartNotifyAndRecognizesFastExit() throws Exception {
        var env = environment();
        var unrelated = environment();
        var operation = new BuildOperation(Runnable::run);
        new BuildExecutionListener(getProject(), env, operation);
        operation.beginLaunch();
        var events = getProject().getMessageBus().syncPublisher(ExecutionManager.EXECUTION_TOPIC);
        events.processNotStarted("Run", unrelated, new IllegalStateException("Unrelated build"));
        assertFalse(operation.completion().isDone());
        var process = new BuildOperationTest.Process();
        events.processStarting("Run", env, process);
        process.startNotify();
        process.exit(0);
        events.processStarted("Run", env, process);
        assertEquals(BuildOperation.Outcome.SUCCESS, operation.completion().join().outcome());
    }

    public void testLaunchFailureAcknowledgesCancellationAndSkipsQueuedNativeRun() throws Exception {
        var env = environment();
        var operation = new BuildOperation(Runnable::run);
        new BuildExecutionListener(getProject(), env, operation);
        operation.beginLaunch();
        operation.cancel();
        assertEquals(Boolean.TRUE, env.getUserData(ExecutionManager.EXECUTION_SKIP_RUN));
        assertFalse(operation.completion().isDone());
        getProject().getMessageBus().syncPublisher(ExecutionManager.EXECUTION_TOPIC).processNotStarted("Run", env);
        assertEquals(BuildOperation.Outcome.CANCELLED, operation.completion().join().outcome());
    }
}
