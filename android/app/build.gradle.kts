plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号可由 CI 传入（打 tag 时用 tag 里的版本），本地默认见下。
// 用 -Poridesk.versionCode=… 而不是环境变量：gradle 属性会在构建日志里可见，
// 便于事后核对"这个 APK 到底是哪次构建产出的"。
//
// 默认值刻意与 Release workflow 的 semver 映射保持**同一套编码**
// （MAJOR*10000 + MINOR*100 + PATCH，见 android-release.yml 的「计算版本号」步骤）。
// 否则分支构建（默认值）与 tag 构建（映射值）的 versionCode 会差几个数量级，
// 用户先装 tag 版再装分支版会被系统当成降级而拒绝安装。
val orideskVersionCode = (findProperty("oridesk.versionCode") as String?)?.toIntOrNull() ?: 10101
val orideskVersionName = (findProperty("oridesk.versionName") as String?) ?: "1.0.1"

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
            val path = keystorePath
            if (hasReleaseKeystore && path != null) {
                storeFile = file(path)
                // 必须显式指定 storeType。CI 里的 keystore 是 PKCS12（.p12），
                // 而 AGP 默认按 JKS 解析 —— 不指定会报
                // "Keystore was tampered with, or password was incorrect"，
                // 这条错误信息会把人引向"口令错了"，而真实原因是格式不对。
                storeType = if (path.endsWith(".p12", true) || path.endsWith(".pfx", true)) {
                    "PKCS12"
                } else {
                    "JKS"
                }
                storePassword = System.getenv("ORIDESK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ORIDESK_KEY_ALIAS")
                keyPassword = System.getenv("ORIDESK_KEY_PASSWORD")

                // 签名方案显式声明，不依赖 AGP/apksigner 的默认值。
                //
                // 起因：CI 签名自检的验签输出显示 AGP 只启用了 v2，而我手工用 apksigner
                // 签的交付包是 v2+v3 —— 同一份代码两条路径产物不一致，将来必然有人
                // 对着"为什么这个包和那个包不一样"浪费时间。
                //
                // v1（JAR 签名）关掉：minSdk 26（Android 8.0），v2 已覆盖全部目标设备；
                // v1 只在 Android 6 及更早才需要，开它只会让 APK 变大、安装变慢。
                // v3 打开：v2 不支持**密钥轮换**，v3 支持。证书虽然有效期 30 年，
                // 但把轮换能力留着的成本是零。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
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

    lint {
        // `assembleRelease` 会顺带跑 lintVital，而它的告警能让构建失败。
        // 本机没有 Android 工具链，编译验证只能靠云端 CI 往返（3–8 分钟一轮），
        // 让"代码风格类告警"占用往返不划算。
        // 注意：这只是不**阻断构建**，不等于不做检查 —— 需要时单独跑 lint 任务。
        checkReleaseBuilds = false
        abortOnError = false
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
    //   并发  -> Thread + Handler（不直接依赖 kotlinx-coroutines；它会被
    //            androidx.lifecycle 间接带进来，但本工程不使用它的 API）
    //   加密  -> Android Keystore + AES/GCM（不用已废弃的 security-crypto，见 SecureStore.kt）
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
