import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Local signing credentials are never committed. CI can provide the same values
// through ULTRADSU_STORE_FILE / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD.
val releaseSigning = Properties().apply {
    val source = rootProject.file("keystore.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
fun signingValue(property: String, environment: String): String? =
    providers.environmentVariable(environment).orNull ?: releaseSigning.getProperty(property)
val releaseStore = signingValue("storeFile", "ULTRADSU_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "ULTRADSU_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ULTRADSU_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ULTRADSU_KEY_PASSWORD")
val releaseSigningAvailable = listOf(releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.ultradsu"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.ultradsu"
        minSdk = 29
        targetSdk = 35
        versionCode = 22
        versionName = "0.2.8-alpha.11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (releaseSigningAvailable) create("release") {
            storeFile = rootProject.file(releaseStore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningAvailable) signingConfig = signingConfigs.getByName("release")
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val verifyReleaseSigning by tasks.registering {
    doLast {
        check(releaseSigningAvailable) { "Release signing is required. Configure keystore.properties or ULTRADSU signing environment variables; see docs/RELEASING.md." }
        check(rootProject.file(releaseStore!!).isFile) { "Release keystore file is missing." }
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("top.yukonga.miuix.kmp:miuix-android:0.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("me.zhanghai.android.libarchive:library:1.1.6")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
