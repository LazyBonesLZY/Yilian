import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

/**
 * 发布签名的配置来自仓库根目录的 keystore.properties（已 gitignore），
 * 或同名的环境变量（CI 用）。缺失时 release 不做签名——不静默退回 debug 签名：
 * debug 签名的包换机安装会因签名不一致失败，而且谁都能用公开的 debug key 覆盖安装。
 *
 * keystore.properties 格式：
 *   storeFile=/绝对/路径/yilian-release.jks
 *   storePassword=...
 *   keyAlias=yilian
 *   keyPassword=...
 */
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    (signingProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "YILIAN_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "YILIAN_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "YILIAN_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "YILIAN_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    file(releaseStoreFile).exists()

android {
    namespace = "com.esurfing.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.esurfing.client"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.4.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk 26，v2/v3 足够；v1(JAR 签名) 在 minSdk ≥ 24 时会被 AGP 跳过，不必开
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    buildFeatures {
        compose = true
        // 设置页的「关于」要拿 VERSION_NAME / VERSION_CODE
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // AppLog 走 android.util.Log, 单元测试里那些方法是未实现的桩会直接抛异常。
            // 让它们返回默认值, 这样测试测的是解析逻辑本身, 而不是被日志绊倒。
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// 没配签名时提醒一句，免得拿着未签名的包白折腾半天。
// 放在配置阶段而不是 doFirst：doFirst 的闭包会捕获脚本对象，配置缓存不支持序列化它。
if (!hasReleaseSigning) {
    logger.lifecycle(
        "提示: 未找到发布签名配置 (keystore.properties 或 YILIAN_STORE_FILE 等环境变量), " +
            "release 产物将是未签名的。",
    )
}

dependencies {
    implementation(libs.androidx.activity.compose)
    // 设置页要在从系统设置返回时重查电池优化状态, 用它的 LocalLifecycleOwner
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // iOS/macOS 通道的动态 ZSM 模块是 LZMA1 压缩的, Android 平台没有内建解码器
    implementation(libs.xz)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)

    testImplementation(libs.junit)
}
