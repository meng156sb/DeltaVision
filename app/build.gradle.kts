import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun readSigningConfig(name: String): String? {
    return (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
}

val configuredKeystoreFile = readSigningConfig("ANDROID_KEYSTORE_FILE")?.let(::file)
val configuredKeystoreBase64 = readSigningConfig("ANDROID_KEYSTORE_BASE64")
val configuredKeystorePassword = readSigningConfig("ANDROID_KEYSTORE_PASSWORD")
val configuredKeyAlias = readSigningConfig("ANDROID_KEY_ALIAS")
val configuredKeyPassword = readSigningConfig("ANDROID_KEY_PASSWORD")

val generatedKeystoreFile = if (configuredKeystoreFile == null && !configuredKeystoreBase64.isNullOrBlank()) {
    layout.buildDirectory.file("signing/release-keystore.jks").get().asFile.apply {
        parentFile.mkdirs()
        writeBytes(Base64.getDecoder().decode(configuredKeystoreBase64))
    }
} else {
    null
}

val releaseKeystoreFile = configuredKeystoreFile ?: generatedKeystoreFile
val hasReleaseSigning = releaseKeystoreFile?.exists() == true &&
    !configuredKeystorePassword.isNullOrBlank() &&
    !configuredKeyAlias.isNullOrBlank() &&
    !configuredKeyPassword.isNullOrBlank()

val verifyReleaseSigningConfig = tasks.register("verifyReleaseSigningConfig") {
    doLast {
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release signing is not configured. Provide ANDROID_KEYSTORE_BASE64 or ANDROID_KEYSTORE_FILE, " +
                    "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD.",
            )
        }
    }
}

tasks.configureEach {
    if (name.contains("Release", ignoreCase = true) && name != verifyReleaseSigningConfig.name) {
        dependsOn(verifyReleaseSigningConfig)
    }
}

android {
    namespace = "com.deltavision.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deltavision.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = configuredKeystorePassword
                keyAlias = configuredKeyAlias
                keyPassword = configuredKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
