package io.github.wildflycommunityrunner.util;

import io.github.wildflycommunityrunner.model.ServerProfile;
import io.github.wildflycommunityrunner.model.ServiceProfile;
import io.github.wildflycommunityrunner.services.ScannerConfiguration;
import io.github.wildflycommunityrunner.services.DeploymentScannerService;
import io.github.wildflycommunityrunner.services.WildFlyProcessService;
import io.github.wildflycommunityrunner.settings.ProjectDeploymentFile;
import io.github.wildflycommunityrunner.settings.WildFlyProjectSettings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import static io.github.wildflycommunityrunner.TestFiles.write;
import static org.junit.Assert.*;

public class DeploymentWorkflowTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private ServerProfile scanner(String xml) throws Exception {
        var server = new ServerProfile(); server.home = temp.getRoot().getAbsolutePath();
        write(Path.of(server.home), "standalone/configuration/standalone.xml", xml);
        return server;
    }
    @Test public void resolvesCustomNamedScannerAndRejectsDisabledOrUnknownConfiguration() throws Exception {
        var server = scanner("<server><paths><path name='apps' path='applications' relative-to='jboss.server.base.dir'/></paths>"
                + "<deployment-scanner name='custom' path='archives' relative-to='apps' scan-enabled='${scanner.enabled:false}'/></server>");
        server.scannerName = "custom";
        assertEquals(Path.of(server.home, "standalone/applications/archives"), WildFlyPaths.deploymentsDir(server));
        assertThrows(IllegalArgumentException.class, () -> ScannerConfiguration.requireEnabled(server));
        server.jvmOptions = "-Dscanner.enabled=true";
        ScannerConfiguration.requireEnabled(server);
        server.scannerName = "missing";
        assertThrows(IllegalArgumentException.class, () -> ScannerConfiguration.read(server));
    }
    @Test public void rejectsXmlEntitiesAndUnresolvedScannerExpressions() throws Exception {
        var server = scanner("<!DOCTYPE server [<!ENTITY x SYSTEM 'file:///no-read'>]><server><deployment-scanner path='&x;'/></server>");
        assertThrows(IllegalArgumentException.class, () -> ScannerConfiguration.read(server));
        scanner("<server><deployment-scanner path='${missing}'/></server>");
        assertThrows(IllegalArgumentException.class, () -> ScannerConfiguration.read(server));
    }
    @Test public void stoppedOrUnverifiedServerDoesNotDisplayDeployedMarkerAsLive() {
        assertEquals("SERVER STOPPED", DeploymentScannerService.visibleStatus("DEPLOYED", WildFlyProcessService.ServerState.STOPPED));
        assertEquals("UNKNOWN", DeploymentScannerService.visibleStatus("DEPLOYED", WildFlyProcessService.ServerState.PORT_BUSY));
        assertEquals("DEPLOYED", DeploymentScannerService.visibleStatus("DEPLOYED", WildFlyProcessService.ServerState.MANAGED));
    }
    @Test public void browserSupportsHttpsIpv6AndExplicitEarEndpoints() {
        var server = new ServerProfile(); server.host = "::1";
        assertEquals("http://[::1]:8080/app/", BrowserUrls.resolve(server, "app.war", "", ""));
        assertEquals("https://localhost:8443/orders", BrowserUrls.resolve(server, "app.ear", "", "https://localhost:8443/orders"));
        assertThrows(IllegalArgumentException.class, () -> BrowserUrls.resolve(server, "app.jar", "", ""));
        assertThrows(IllegalArgumentException.class, () -> BrowserUrls.validateOverride("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> BrowserUrls.validateOverride("https://user:password@localhost/"));
    }
    @Test public void duplicateNamesAreRejectedBeforeDeployment() {
        var a = ServiceProfile.create(); a.name = "orders"; a.deploymentName = "api.war";
        var b = ServiceProfile.create(); b.name = "billing"; b.deploymentName = "API.war";
        assertThrows(IllegalArgumentException.class, () -> DeploymentNames.requireUnique(List.of(a, b)));
    }
    @Test public void sharedProjectRoundTripExcludesLocalDataAndPreservesLocalChoices() throws Exception {
        Path root = temp.getRoot().toPath();
        var service = ServiceProfile.create(); service.name = "orders";
        service.buildFilePath = write(root, "orders/pom.xml", "<project/>").toString();
        service.artifactPath = root.resolve("orders/target/orders.war").toString();
        service.buildRootPath = root.resolve("pom.xml").toString();
        service.buildJavaHome = "LOCAL_JDK"; service.buildJvmOptions = "-Dpassword=LOCAL_SECRET"; service.deployAfterBuild = true;
        ProjectDeploymentFile.write(root, List.of(service));
        String xml = Files.readString(root.resolve(ProjectDeploymentFile.LOCATION));
        assertFalse(xml.contains("LOCAL_")); assertFalse(xml.contains(root.toString()));
        var read = ProjectDeploymentFile.read(root);
        assertEquals("orders/pom.xml", read.getFirst().buildFilePath);
        assertEquals("target/orders.war", read.getFirst().artifactPath);
        assertFalse(read.getFirst().deployAfterBuild);
        var state = new WildFlyProjectSettings.StateData(); state.services.add(service);
        ProjectDeploymentFile.merge(root, state, read);
        assertEquals(1, state.services.size());
        assertEquals(service.id, state.services.getFirst().id);
        assertEquals("-Dpassword=LOCAL_SECRET", state.services.getFirst().buildJvmOptions);
        assertTrue(state.services.getFirst().deployAfterBuild);
    }
    @Test public void sharedFileRejectsSensitiveArgumentsAndPathsOutsideProject() throws Exception {
        Path root = temp.getRoot().toPath();
        var service = ServiceProfile.create(); service.buildFilePath = "../outside/pom.xml";
        assertThrows(IllegalArgumentException.class, () -> ProjectDeploymentFile.write(root, List.of(service)));
        service.buildFilePath = "pom.xml"; service.buildArguments = "-Dpassword=secret";
        assertThrows(IllegalArgumentException.class, () -> ProjectDeploymentFile.write(root, List.of(service)));
    }
}
