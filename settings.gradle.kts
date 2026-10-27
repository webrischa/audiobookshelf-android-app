pluginManagement {
  repositories {
    // Same opt-in mirror as below: plugin markers also live on Google Maven.
    if (gradle.startParameter.projectProperties["useGoogleMavenMirror"] == "true") {
      maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // Default: the official Google Maven + Maven Central. Networks that cannot reach
    // dl.google.com can opt into a mirror of the same AndroidX artifacts with
    // -PuseGoogleMavenMirror=true (or that property in gradle.properties). Kept behind a flag so
    // the default build never depends on a third-party repository.
    if (providers.gradleProperty("useGoogleMavenMirror").orNull == "true") {
      maven { url = uri("https://maven.aliyun.com/repository/google") }
      maven { url = uri("https://maven.aliyun.com/repository/public") }
      mavenCentral()
    } else {
      google()
      mavenCentral()
    }
  }
}

rootProject.name = "AudiobookshelfNative"
include(":app")
// Device-run generator for method-granularity baseline profiles. Opt-in: its Gradle plugin marker
// lives on Google Maven, which is unreachable on some networks, and loading it unconditionally
// broke the default `assembleDebug` configuration. Enable with -PwithBaselineProfileModule=true
// (and -PuseGoogleMavenMirror=true where Google Maven itself is blocked).
if (gradle.startParameter.projectProperties["withBaselineProfileModule"] == "true") {
  include(":baselineprofile")
}
