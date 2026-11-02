pluginManagement {
    repositories {
        // Maven Central via JetBrains cache (first, so plugin-portal redirects to Central are not needed)
        maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2")
        gradlePluginPortal()
    }
}
rootProject.name = "commit-line-splitter"
