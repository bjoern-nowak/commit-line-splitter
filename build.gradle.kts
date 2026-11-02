import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin { jvmToolchain(21) }

repositories {
    maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2")
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "de.nowakhub.commitlinesplitter"
        name = "Commit Line Splitter"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "263.*"
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1.5")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.4")
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks {
    test {
        // tests pass non-ASCII names on git command lines
        environment("LC_ALL", "C.UTF-8")
        environment("LANG", "C.UTF-8")
    }
    prepareTestSandbox {
        // The unified IDEA distribution ships a JetBrains-Client Git plugin whose module id clashes with Git4Idea
        // in the test sandbox, which would prevent Git (and therefore this plugin) from loading in tests.
        disabledPlugins.add("com.intellij.jetbrains.client.git")
    }
}
