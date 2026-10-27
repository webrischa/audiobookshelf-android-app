plugins {
  id("com.android.test")
  id("org.jetbrains.kotlin.android")
  id("androidx.baselineprofile") version "1.5.0"
}

android {
  namespace = "com.audiobookshelf.nativeapp.baselineprofile"
  compileSdk = 36

  // The app whose startup/journey is profiled. The generated file replaces the hand-written
  // baseline-prof.txt once `generateBaselineProfile` has been run on a physical device.
  targetProjectPath = ":app"

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  defaultConfig {
    // BaselineProfileRule requires API 28+ for the profiler it drives.
    minSdk = 28
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
  implementation("androidx.test.ext:junit:1.2.1")
  implementation("androidx.test:runner:1.7.0")
  implementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
