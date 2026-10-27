import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

// Release credentials live in local.properties (gitignored). The debug build keeps the stock
// Android debug key, release uses a separate key, and the two variants carry different
// applicationIds — so both APKs can be installed side by side without overwriting each other.
val releaseProps = Properties().apply {
  rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseKeyConfigured = listOf(
  "abs.release.storeFile",
  "abs.release.storePassword",
  "abs.release.keyAlias",
  "abs.release.keyPassword"
).all { releaseProps.getProperty(it)?.isNotBlank() == true }

android {
  namespace = "com.audiobookshelf.nativeapp"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.audiobookshelf.nativeapp"
    minSdk = 26
    targetSdk = 36
    versionCode = 3
    versionName = "0.3.0-native"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (releaseKeyConfigured) {
      create("releaseKey") {
        storeFile = rootProject.file(releaseProps.getProperty("abs.release.storeFile"))
        storePassword = releaseProps.getProperty("abs.release.storePassword")
        keyAlias = releaseProps.getProperty("abs.release.keyAlias")
        keyPassword = releaseProps.getProperty("abs.release.keyPassword")
      }
    }
  }

  buildTypes {
    debug {
      // Left unminified on purpose: readable stack traces and fast incremental builds matter more
      // than size while iterating on the device
      isMinifyEnabled = false
      // Distinct applicationId on purpose: debug and release stay installed side by side, each
      // with its own DataStore / WorkManager / notification-channel state.
      applicationIdSuffix = ".debug"
    }
    release {
      // R8 + resource shrinking: the icons bundle alone carries ~11 MB of unreferenced code, so
      // an unminified release weighs 16.9 MB against 3.1 MB with R8 on
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("releaseKey")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    // AppLog gates every log on BuildConfig.DEBUG, which AGP does not generate by default
    // from 8.0 onwards. Debug keeps the logs, release compiles them away.
    buildConfig = true
  }

  packaging {
    resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
  }

  testOptions {
    unitTests {
      // No Robolectric here on purpose: the tests below cover the pure logic (url parsing, time
      // formatting, chapter lookup). Anything that needs a real Android object is verified on a
      // device, not faked.
      isReturnDefaultValues = true
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.activity:activity-compose:1.9.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
  implementation("androidx.navigation:navigation-compose:2.7.7")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  // Durable, backoff-driven retry for progress writes the playback service could not deliver.
  implementation("androidx.work:work-runtime-ktx:2.9.1")

  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation("androidx.compose.ui:ui-tooling")

  // All media3 artifacts must share one version: mixing releases makes MediaSession and
  // ExoPlayer disagree about the session/notification contracts. Since 1.6.0 the session
  // module keeps the foreground state for 10 minutes after a pause/stop/error (the "user
  // engaged" timeout), and 1.11.x adds an idle-player notification policy — both replace the
  // hand-rolled foreground hold this app needed on 1.4.1.
  val media3Version = "1.11.1"
  implementation("androidx.media3:media3-exoplayer:$media3Version")
  implementation("androidx.media3:media3-session:$media3Version")
  implementation("androidx.media3:media3-ui:$media3Version")
  implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

  // Liquid glass (blur + lens) for the playback bar and the settings switches.
  // Pinned to 1.0.6 on purpose: it is the last release published against androidx.compose
  // directly (1.10.3). From 2.0.0 on, the library is published as Compose Multiplatform
  // (org.jetbrains.compose.*) and its AAR demands a newer compileSdk than this project targets.
  // Upgrading it therefore means bumping compileSdk + the whole compose-bom/material3 stack
  // together, not just this version number.
  implementation("io.github.kyant0:backdrop:1.0.6")

  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  implementation("io.coil-kt:coil-compose:2.6.0")
  implementation("io.coil-kt:coil:2.6.0")

  // Installs the baseline profile (this app's and the ones Compose/Media3 ship) at install time.
  // Without it ART only compiles the app on first launch, which is the slow first scroll.
  implementation("androidx.profileinstaller:profileinstaller:1.4.0")

  testImplementation("junit:junit:4.13.2")

  // Instrumented Compose UI tests. Kept on the same BOM as the app so the test rule and the
  // runtime agree on the Compose version.
  androidTestImplementation(platform(composeBom))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:runner:1.7.0")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Fail loudly instead of silently producing an unsigned release when a fresh clone has no
// local.properties. `validateSigningRelease` is NOT a usable hook for this: AGP only adds it
// to the task graph when a signing config exists, so with a missing key the guard never ran
// and assembleRelease quietly produced an unsigned APK. Fail during configuration for any
// release request, and keep a packaging-stage guard for release builds triggered indirectly.
val releaseSigningError = "Release signing missing: add abs.release.storeFile / " +
  "storePassword / keyAlias / keyPassword to local.properties"
val releaseRequested = gradle.startParameter.taskNames.any { name ->
  name.contains("Release", ignoreCase = true) ||
    name.substringAfterLast(':') in setOf("assemble", "build", "bundle")
}
if (releaseRequested) {
  check(releaseKeyConfigured) { releaseSigningError }
}
tasks.matching { it.name == "packageRelease" }.configureEach {
  doFirst {
    check(releaseKeyConfigured) { releaseSigningError }
  }
}
