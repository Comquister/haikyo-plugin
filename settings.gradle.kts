pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "haikyo-plugin"

include(
    "haikyo-api",
    "haikyo-core",
    "haikyo-paper",
    "haikyo-velocity",
    "haikyo-fabric",
)
