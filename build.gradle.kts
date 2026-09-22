import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        ides {
            providers.gradleProperty("verifierIde")
                .orElse(providers.gradleProperty("pluginVerifierIdeVersions"))
                .get().split(',').map(String::trim).forEach { notation ->
                    val parts = notation.split('-', limit = 2)
                    require(parts.size == 2 && parts.all(String::isNotBlank)) {
                        "Expected verifier IDE notation TYPE-VERSION, got '$notation'"
                    }
                    create(parts[0], parts[1])
                }
        }
    }
}

tasks {
    test {
        useJUnit()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    withType<Wrapper> {
        gradleVersion = "9.0.0"
        distributionType = Wrapper.DistributionType.BIN
        distributionSha256Sum = "8fad3d78296ca518113f3d29016617c7f9367dc005f932bd9d93bf45ba46072b"
    }
}
