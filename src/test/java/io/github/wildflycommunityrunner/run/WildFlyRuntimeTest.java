package io.github.wildflycommunityrunner.run;

import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RemoteState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.sun.jdi.Bootstrap;
import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.*;
import io.github.wildflycommunityrunner.settings.WildFlyApplicationSettings;
import io.github.wildflycommunityrunner.util.WildFlyPaths;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.*;
import org.junit.rules.TemporaryFolder;
import static io.github.wildflycommunityrunner.RuntimeTestSupport.*;

/** Real WildFly distribution, native RunProfileState, JDWP, HTTP and deployment scanner. */
public class WildFlyRuntimeTest extends BasePlatformTestCase {
    private final List<ExecutionResult> sessions = new ArrayList<>();
    private final List<String> output = new CopyOnWriteArrayList<>();

    public void testNativeStartReuseDebugAttachArtifactRedeployAndStop() throws Exception {
        String home = System.getenv("WILDFLY_TEST_HOME");
        assertNotNull("CI must prepare the pinned real WildFly distribution", home);
        var temporary = new TemporaryFolder(); temporary.create();
        var settings = WildFlyApplicationSettings.getInstance();
        var previous = settings.getState();
        boolean trusted = TrustedProjects.isProjectTrusted(getProject());
        var server = new ServerProfile(); server.name = "Runtime smoke"; server.home = home;
        server.javaHome = System.getProperty("java.home"); server.host = "127.0.0.1";
        Path root = temporary.getRoot().toPath(), base = root.resolve("server-base");
        var watcher = ArtifactAutoDeployService.getInstance(getProject());
        var processes = WildFlyProcessService.getInstance();
        try {
            background(() -> {
                Path configuration = base.resolve("configuration"); Files.createDirectories(configuration);
                Files.createDirectories(base.resolve("deployments"));
                try (var entries = Files.list(Path.of(home, "standalone", "configuration"))) {
                    for (Path entry : entries.toList()) if (Files.isRegularFile(entry))
                        Files.copy(entry, configuration.resolve(entry.getFileName().toString()));
                }
                int offset = availableOffset(); server.httpPort = 8080 + offset; server.debugPort = freePort();
                server.jvmOptions = "-Xms128m -Xmx512m \"-Djboss.server.base.dir=" + base + "\""
                        + " -Djboss.socket.binding.port-offset=" + offset
                        + " -Djboss.bind.address=127.0.0.1 -Djboss.bind.address.management=127.0.0.1"
                        + " -Dwildfly.fixture.password=${env:WILDFLY_SMOKE_PASSWORD}"
                        + " \"-Doracle.net.tns_admin=" + base.resolve("tns with spaces") + "\"";
                return null;
            });
            settings.loadState(new WildFlyApplicationSettings.StateData());
            settings.update(state -> state.servers.add(server));
            TrustedProjects.setProjectTrusted(getProject(), true);

            ProcessHandler owner = launch(server, false);
            waitHttp(server, "/", null);
            assertFalse(processes.isDebugRunning(server));
            assertEquals(WildFlyProcessService.ServerState.MANAGED, background(() -> processes.state(server)));
            String detectionFailure = background(() -> WildFlyDetectionDiagnostics.awaitMatch(server));
            assertNull(detectionFailure, detectionFailure);
            ProcessHandler reused = launch(server, false);
            // Wait until the reused session has bound, then Stop must leave the original owner alive.
            background(() -> { Thread.sleep(500); return null; });
            reused.destroyProcess();
            await(reused::isProcessTerminated, Duration.ofSeconds(15), "Reused native session did not detach");
            assertTrue(processes.isRunning(server)); waitHttp(server, "/", null);
            owner.destroyProcess();
            await(owner::isProcessTerminated, Duration.ofSeconds(30), "Native Stop did not finish");
            background(() -> { waitStopped(server); return null; });

            ProcessHandler debug = launch(server, true);
            waitHttp(server, "/", null);
            assertTrue(processes.isDebugRunning(server));
            var attach = configuration(server, true);
            var executor = DefaultDebugExecutor.getDebugExecutorInstance();
            var environment = ExecutionEnvironmentBuilder.create(executor, attach).build();
            var connection = ((RemoteState) attach.getState(executor, environment)).getRemoteConnection();
            assertEquals(Integer.toString(server.debugPort), connection.getDebuggerAddress());
            background(() -> {
                var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                        .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
                var arguments = connector.defaultArguments();
                arguments.get("hostname").setValue(connection.getDebuggerHostName());
                arguments.get("port").setValue(connection.getDebuggerAddress());
                arguments.get("timeout").setValue("15000");
                var vm = connector.attach(arguments);
                try { assertFalse(vm.allThreads().isEmpty()); } finally { vm.dispose(); }
                return null;
            });
            assertTrue("Debugger disconnect must keep WildFly alive", processes.isRunning(server));

            Path module = root.resolve("sample Maven module");
            Path artifact = module.resolve("target/smoke.war");
            var service = new ServiceProfile(); service.name = "Runtime WAR"; service.deploymentName = "smoke.war";
            service.buildFilePath = module.resolve("pom.xml").toString(); service.artifactPath = artifact.toString();
            background(() -> {
                Files.createDirectories(module); Files.writeString(module.resolve("pom.xml"), "<project/>");
                war(artifact, "first"); return null;
            });
            var deployed = new CompletableFuture<Boolean>();
            DeploymentScannerService.deploy(getProject(), server, artifact, service.deploymentName, output::add, deployed::complete);
            await(deployed::isDone, Duration.ofSeconds(90), diagnostic("Initial deployment timed out"));
            assertTrue(diagnostic("Initial deployment failed"), deployed.get());
            waitHttp(server, "/smoke/", "first:credential-ok");
            assertEquals("DEPLOYED", background(() -> DeploymentScannerService.status(server, "smoke.war")));
            var timestamp = background(() -> DeploymentScannerService.lastDeployedAt(server, "smoke.war"));
            assertNotNull(timestamp);
            background(() -> { watcher.configure(List.of(service), server, output::add); return null; });
            background(() -> {
                Files.createDirectories(module.resolve("src")); Files.writeString(module.resolve("src/OnlySource.txt"), "no rebuild");
                Thread.sleep(1800); return null;
            });
            assertEquals(timestamp, background(() -> DeploymentScannerService.lastDeployedAt(server, "smoke.war")));
            background(() -> { war(artifact, "second"); return null; });
            waitHttp(server, "/smoke/", "second:credential-ok");
            background(() -> { watcher.configure(List.of(), server, output::add); return null; });
            var undeployed = new CompletableFuture<Boolean>();
            DeploymentScannerService.undeploy(getProject(), server, "smoke.war", output::add, undeployed::complete);
            await(undeployed::isDone, Duration.ofSeconds(45), diagnostic("Undeployment timed out"));
            assertTrue(diagnostic("Undeployment failed"), undeployed.get());
            assertTrue(Files.isRegularFile(artifact));
            assertFalse(Files.exists(WildFlyPaths.deploymentsDir(server).resolve("smoke.war")));
            assertEquals("NOT DEPLOYED", background(() -> DeploymentScannerService.status(server, "smoke.war")));
            var tail = background(() -> new ServerLogTailer().poll(WildFlyPaths.logFile(server), text -> processes.redact(server, text)));
            assertTrue("Real server.log should contain startup records", tail.text().contains("WFLY"));
            assertFalse(String.join("", output).contains(System.getenv("WILDFLY_SMOKE_PASSWORD")));
            debug.destroyProcess();
            await(debug::isProcessTerminated, Duration.ofSeconds(30), "Debug owner Stop did not finish");
            background(() -> { waitStopped(server); return null; });
        } finally {
            background(() -> { watcher.configure(List.of(), server, output::add); processes.terminate(server, output::add); return null; });
            for (ExecutionResult session : sessions) {
                ProcessHandler handler = session.getProcessHandler();
                if (!handler.isProcessTerminated()) handler.destroyProcess();
                await(handler::isProcessTerminated, Duration.ofSeconds(30), diagnostic("Fixture process cleanup timed out"));
                if (session.getExecutionConsole() != null) Disposer.dispose(session.getExecutionConsole());
            }
            settings.loadState(previous); TrustedProjects.setProjectTrusted(getProject(), trusted);
            temporary.delete();
        }
    }

    private WildFlyRunConfiguration configuration(ServerProfile server, boolean attach) {
        var type = ConfigurationTypeUtil.findConfigurationType(WildFlyConfigurationType.class);
        var factory = Arrays.stream(type.getConfigurationFactories())
                .filter(value -> value.getId().equals(attach ? "AttachDebugger" : "LocalServer")).findFirst().orElseThrow();
        var configuration = (WildFlyRunConfiguration) factory.createTemplateConfiguration(getProject());
        configuration.setServerId(server.id); return configuration;
    }
    private ProcessHandler launch(ServerProfile server, boolean debug) throws Exception {
        var configuration = configuration(server, false);
        var executor = debug ? DefaultDebugExecutor.getDebugExecutorInstance() : DefaultRunExecutor.getRunExecutorInstance();
        var environment = ExecutionEnvironmentBuilder.create(executor, configuration).build();
        ExecutionResult result = configuration.getState(executor, environment).execute(executor, environment.getRunner());
        sessions.add(result);
        result.getProcessHandler().addProcessListener(new ProcessListener() {
            @Override public void onTextAvailable(ProcessEvent event, Key type) { output.add(event.getText()); }
        });
        return result.getProcessHandler();
    }
    private void waitHttp(ServerProfile server, String path, String expected) throws Exception {
        boolean success = background(() -> {
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < deadline) {
                if (!sessions.isEmpty() && sessions.stream().allMatch(session -> session.getProcessHandler().isProcessTerminated())) return false;
                try {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.httpPort + path)).timeout(Duration.ofSeconds(3)).build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && (expected == null || response.body().trim().equals(expected))) return true;
                } catch (java.io.IOException ignored) {}
                Thread.sleep(200);
            }
            return false;
            }
        });
        assertTrue(diagnostic("WildFly HTTP response did not become ready: " + path), success);
    }
    private static void waitStopped(ServerProfile server) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            if (WildFlyServerDetector.matchingProcesses(server).isEmpty() && !WildFlyServerDetector.isPortOpen(server)) return;
            Thread.sleep(200);
        }
        throw new AssertionError("WildFly child JVM or HTTP listener survived Stop");
    }
    private String diagnostic(String title) {
        String log = String.join("", output); return title + "\n" + log.substring(Math.max(0, log.length() - 12000));
    }
    private static int freePort() throws Exception { try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static int availableOffset() throws Exception {
        for (int offset = 1000; offset < 10000; offset += 113) {
            var sockets = new ArrayList<ServerSocket>();
            try {
                for (int port : new int[]{8080, 8443, 9990, 4712, 4713}) sockets.add(new ServerSocket(port + offset, 0, InetAddress.getByName("127.0.0.1")));
                return offset;
            } catch (java.io.IOException occupied) {
                // Try another complete socket-binding group.
            } finally { for (var socket : sockets) socket.close(); }
        }
        throw new AssertionError("No free WildFly socket-binding group");
    }
    private static void war(Path path, String version) throws Exception {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "war-", ".building");
        try (var output = new ZipOutputStream(Files.newOutputStream(temporary))) {
            output.putNextEntry(new ZipEntry("index.jsp"));
            String jsp = "<%@ page contentType=\"text/plain\" %>" + version
                    + ":<%= java.util.Objects.equals(System.getProperty(\"wildfly.fixture.password\"), System.getenv(\"WILDFLY_SMOKE_PASSWORD\"))"
                    + " && java.nio.file.Path.of(System.getProperty(\"jboss.server.base.dir\"), \"tns with spaces\").toString().equals(System.getProperty(\"oracle.net.tns_admin\"))"
                    + " ? \"credential-ok\" : \"credential-missing\" %>";
            output.write(jsp.getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
}
