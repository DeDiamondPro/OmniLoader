rootProject.name = "OmniLoader"

include("loader")
include("jar-minifier")

pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}