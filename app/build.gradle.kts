import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Release signing. Secrets are NEVER stored in the repository.
// They come from either:
//   1. environment variables (used by GitHub Actions, filled from repository secrets), or
//   2. a local, git-ignored file `keystore.properties` in the project root.
// If neither is present, the release build is simply left unsigned and the
// debug build (always installable) is used instead.
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(propName)

val releaseStoreFile = secret("CYCLING_KEYSTORE_PATH", "storeFile")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

// Version: bump versionCode for every release you install over an older one.
val appVersionCode = 1
val appVersionName = "1.0.0"

android {
    namespace = "com.batraamul.cycling"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.batraamul.cycling"
        minSdk = 29          // Android 10: activity-recognition permission model
        targetSdk = 36       // Android 16
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = secret("CYCLING_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = secret("CYCLING_KEY_ALIAS", "keyAlias")
                keyPassword = secret("CYCLING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"   // debug and release can be installed side by side
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true            // R8 shrinks and optimises the code
            isShrinkResources = true          // removes unused resources
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true   // needed by Robolectric
    }
    lint {
        // Library versions are pinned deliberately (newest releases need compileSdk 37 + AGP 9; see docs/ARCHITECTURE.md),
        // so "newer version available" notices are silenced. Every other lint error fails the build.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        abortOnError = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    // Room writes its schema here so database migrations can be reviewed in Git.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
