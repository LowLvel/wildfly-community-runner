package io.github.wildflycommunityrunner;

import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

public final class TestFiles {
    private TestFiles() {}

    public static Path write(Path root, String relative, String contents) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, contents);
    }

    /** Fail on unexpected IDE access: these scenarios should only need the project directory. */
    public static Project projectAt(Path base) {
        return (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> base.toString();
                    case "isDisposed" -> false;
                    case "toString" -> "Filesystem test project";
                    default -> throw new AssertionError("Unexpected Project API: " + method.getName());
                });
    }
}
