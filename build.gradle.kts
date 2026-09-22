plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.wildflycommunityrunner"
version = "0.5.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1.7")
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("com.intellij.java")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
