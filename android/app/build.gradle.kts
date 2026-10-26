import java.util.Base64

plugins {
    // AGP 9 起内置 Kotlin 支持，不能再应用 org.jetbrains.kotlin.android，
    // 否则报 "The 'org.jetbrains.kotlin.android' plugin is no longer required"。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * 签名：密钥来自 CI secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）。
 * 四个都齐才启用 release 签名；缺任一个就只出 debug，保证 CI 不会因为没配密钥变红。
 *
 * 变量名刻意带 env 前缀：`signingConfigs { create(...) { ... } }` 的接收者本身就是
 * SigningConfig，里面有同名的 keyAlias / keyPassword，外层同名字段会自己赋给自己。
 */
val envKeystoreBase64: String? = System.getenv("KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }
val envKeystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val envKeyAlias: String? = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val envKeyPassword: String? = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val hasSigningKey = listOf(envKeystoreBase64, envKeystorePassword, envKeyAlias, envKeyPassword)
    .all { it != null }

val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName: String = System.getenv("VERSION_NAME")
    ?.trim()
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: "1.0.0"

val releaseKeystore = layout.buildDirectory.file("signing/release.p12")
val prepareSigningKey by tasks.registering {
    onlyIf { hasSigningKey }
    val out = releaseKeystore
    val data = envKeystoreBase64
    outputs.file(out)
    doLast {
        val file = out.get().asFile
        file.parentFile.mkdirs()
        file.writeBytes(Base64.getDecoder().decode(data))
    }
}

android {
    namespace = "dev.yg.relay"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.yg.relay"
        // minSdk 33：与液态玻璃（RuntimeShader / RenderEffect）的硬要求一致，
        // 也让 miuix-blur 不需要 overrideLibrary 这类妥协。
        // Android 13 是 2022 年的系统，覆盖当前在用的绝大多数机器。
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        resourceConfigurations += listOf("zh", "en")
    }

    if (hasSigningKey) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore.get().asFile
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigningKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
            )
        }
    }
}

if (hasSigningKey) {
    tasks.matching { it.name.startsWith("package") }.configureEach {
        dependsOn(prepareSigningKey)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)

    testImplementation(libs.junit)
}
