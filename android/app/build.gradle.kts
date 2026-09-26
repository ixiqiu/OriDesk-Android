plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号可由 CI 传入（打 tag 时用 tag 里的版本），本地默认 1 / 1.0.0。
// 用 -Poridesk.versionCode=… 而不是环境变量：gradle 属性会在构建日志里可见，
// 便于事后核对"这个 APK 到底是哪次构建产出的"。
val orideskVersionCode = (findProperty("oridesk.versionCode") as String?)?.toIntOrNull() ?: 1
val orideskVersionName = (findProperty("oridesk.versionName") as String?) ?: "1.0.0"

// 固定 release keystore（决策 8）。
// debug 签名每次构建都不同 —— 每次升级必须卸载重装，本地数据与 ntfy 订阅全丢。
// CI 从 4 个 GitHub Secret 还原出 keystore 文件并设置这些环境变量。
val keystorePath: String? = System.getenv("ORIDESK_KEYSTORE_PATH")
val hasReleaseKeystore: Boolean = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.xinjiyuan.oridesk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xinjiyuan.oridesk"
        minSdk = 26
        targetSdk = 35
        versionCode = orideskVersionCode
        versionName = orideskVersionName

        // 服务器地址**不在编译期注入**（决策 10）：仓库公开，硬编码会暴露内部域名，
        // 换域名也不该需要重新发版。地址一律运行时由用户填写并记住。
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("ORIDESK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ORIDESK_KEY_ALIAS")
                keyPassword = System.getenv("ORIDESK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 没配 keystore 时退回 debug 签名，好让 CI 仍能产出**可安装**的 APK 供联调；
            // 正式分发必须配齐 4 个 secret，否则每次构建签名都不同、无法覆盖升级。
            // 工作流会在这种情况下打警告，见 .github/workflows/android.yml。
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // 依赖刻意压到最少（04-CICD与验证边界.md 风险 #1 的缓解手段）：
    // 本机没有 Android 工具链，编译验证只能靠云端，每多一个依赖就多一份
    // 解析失败/版本冲突的概率，而且会让每次 CI 迭代变慢。
    //
    // 因此以下都**不用第三方库**：
    //   HTTP  -> framework 的 HttpURLConnection（不用 OkHttp）
    //   JSON  -> framework 的 org.json（不用 Gson/Moshi）
    //   并发  -> Thread + Handler（不用 kotlinx-coroutines）
    //   加密  -> Android Keystore + AES/GCM（不用已废弃的 security-crypto，见 SecureStore.kt）
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
