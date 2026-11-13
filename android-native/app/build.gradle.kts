import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
  val file = rootProject.file("local.properties")
  if (file.exists()) {
    load(file.inputStream())
  }
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY") ?: System.getenv("MAPS_API_KEY") ?: ""
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY")
  ?: System.getenv("GEMINI_API_KEY")
  ?: mapsApiKey
val firebaseDbUrl: String = localProperties.getProperty("FIREBASE_DB_URL")
  ?: System.getenv("FIREBASE_DB_URL")
  ?: "https://drivetest-telemetry-default-rtdb.asia-southeast1.firebasedatabase.app"
val firebaseAuthToken: String = localProperties.getProperty("FIREBASE_AUTH_TOKEN")
  ?: System.getenv("FIREBASE_AUTH_TOKEN")
  ?: ""
// Drive uploads run server-side (worker/drive-upload.js); the app only needs that endpoint, never a Google credential.
val cloudUploadUrl: String = localProperties.getProperty("CLOUD_UPLOAD_URL")
  ?: System.getenv("CLOUD_UPLOAD_URL")
  ?: ""
// Release signing material lives outside VCS (local.properties or CI env vars).
// Never commit the keystore path, store password, alias or key password.
val releaseStorePath: String = localProperties.getProperty("RELEASE_KEYSTORE_PATH")
  ?: System.getenv("RELEASE_KEYSTORE_PATH")
  ?: ""
val releaseStorePassword: String = localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD")
  ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
  ?: ""
val releaseKeyAlias: String = localProperties.getProperty("RELEASE_KEY_ALIAS")
  ?: System.getenv("RELEASE_KEY_ALIAS")
  ?: ""
val releaseKeyPassword: String = localProperties.getProperty("RELEASE_KEY_PASSWORD")
  ?: System.getenv("RELEASE_KEY_PASSWORD")
  ?: ""

android {
  namespace = "com.drivetest.lte5g"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.drivetest.lte5g"
    minSdk = 29
    targetSdk = 37
    versionCode = 47
    versionName = "1.13.3"
    manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    buildConfigField("String", "FIREBASE_DB_URL", "\"$firebaseDbUrl\"")
    buildConfigField("String", "FIREBASE_AUTH_TOKEN", "\"$firebaseAuthToken\"")
    buildConfigField("String", "CLOUD_UPLOAD_URL", "\"$cloudUploadUrl\"")
  }

  signingConfigs {
    create("release") {
      val keystoreFile = releaseStorePath.takeIf { it.isNotBlank() }?.let { file(it) }
      val hasReleaseSigning = keystoreFile != null &&
        keystoreFile.exists() &&
        releaseStorePassword.isNotBlank() &&
        releaseKeyAlias.isNotBlank() &&
        releaseKeyPassword.isNotBlank()
      if (hasReleaseSigning) {
        storeFile = keystoreFile
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
        enableV1Signing = true
        enableV2Signing = true
      } else {
        logger.lifecycle("Release keystore not configured; falling back to debug signing.")
        val debugConfig = signingConfigs.getByName("debug")
        storeFile = debugConfig.storeFile
        storePassword = debugConfig.storePassword
        keyAlias = debugConfig.keyAlias
        keyPassword = debugConfig.keyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
    aidl = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

// Security guard: fails the build if a Google OAuth token (ya29.) or a non-empty GDRIVE_ACCESS_TOKEN field reappears in the generated BuildConfig.
val guardNoDriveToken = tasks.register("guardNoDriveToken") {
  group = "verification"
  description = "Fails when a Google Drive OAuth token or the removed GDRIVE_ACCESS_TOKEN field returns to BuildConfig."
  dependsOn(tasks.matching { it.name.startsWith("generate") && it.name.endsWith("BuildConfig") })
  outputs.upToDateWhen { false }
  val generatedRoot = layout.buildDirectory.dir("generated/source/buildConfig")
  doLast {
    val token = Regex("""ya29\.[A-Za-z0-9_\-.]{10,}""")
    val injectedField = Regex("""GDRIVE_ACCESS_TOKEN\s*=\s*"(?!")""")
    val violations = mutableListOf<String>()
    generatedRoot.get().asFile.walkTopDown()
      .filter { it.isFile && it.name == "BuildConfig.java" }
      .forEach { f ->
        val text = f.readText()
        if (token.containsMatchIn(text)) violations += "${f.path}: ya29. token"
        if (injectedField.containsMatchIn(text)) violations += "${f.path}: GDRIVE_ACCESS_TOKEN is non-empty"
      }
    if (violations.isNotEmpty()) {
      throw GradleException("Drive token guard failed (secrets must stay server-side):\n" + violations.joinToString("\n"))
    }
  }
}
tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" || it.name == "bundleRelease" }
  .configureEach { dependsOn(guardNoDriveToken) }

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.json:json:20240303")

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Location & Google Maps
  implementation(libs.play.services.location)
  implementation(libs.play.services.maps)
  implementation(libs.maps.compose)
  implementation(libs.androidx.compose.material.icons.extended)

  // WorkManager (Background Cloud Uploads on Wi-Fi)
  implementation(libs.androidx.work.runtime.ktx)
  testImplementation("androidx.work:work-testing:2.10.0")

  // Google Play In-App Updates (requested by @updater, board #31)
  implementation("com.google.android.play:app-update:2.1.0")
  implementation("com.google.android.play:app-update-ktx:2.1.0")
}
