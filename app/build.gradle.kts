import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // gradle.properties 里 android.builtInKotlin=false —— 用传统 KGP（版本在根 build.gradle.kts，
    // 2.4.20），否则内置 Kotlin 2.2.10 读不了 Miuix 0.9.4 的 metadata 2.4.0
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 本地与 CI 共用同一套配置；本地文件位于已忽略的 keystore/ 中，环境变量优先。
val localSigning = Properties().apply {
    val config = rootProject.file("keystore/signing.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}
val signingValue = { name: String ->
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localSigning.getProperty(name)?.takeIf { it.isNotBlank() }
}
val keystoreFile = file(signingValue("KEYSTORE_PATH") ?: "../keystore/talktact.p12")
val keystorePassword = signingValue("KEYSTORE_PASSWORD")
val keystoreAlias = signingValue("KEYSTORE_ALIAS") ?: "talktact"
val signingRequested = keystoreFile.exists() ||
    listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEYSTORE_ALIAS").any { signingValue(it) != null }
val hasStableSigning = keystoreFile.isFile && !keystorePassword.isNullOrBlank()
check(!signingRequested || hasStableSigning) {
    "固定签名配置不完整：请检查 keystore/signing.properties 或 KEYSTORE_* 环境变量"
}

android {
    namespace = "dev.goutou.wingman"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.shibry88_netizen.talktact"
        minSdk = 31  // Android 12+：液态玻璃的真实背景模糊走 RenderEffect
        targetSdk = 34
        versionCode = 40
        versionName = "0.8.12-fork.1"

        /**
         * 只打包 arm64-v8a（0.8.10 起）。
         *
         * 图片 OCR 用的 ML Kit 内置模型带一个 ~11MB 的原生库 `libmlkit_google_ocr_pipeline.so`，
         * 它给 4 个 ABI 各备了一份（合起来 40MB+）；这些 .so 在 APK 里是**不压缩**存放的，
         * 带上 2 份 arm 就要多出十几 MB。x86 / x86_64 是模拟器才有的东西，一直不带。
         *
         * ⚠️ 代价：**32 位老设备（armeabi-v7a）装不上这个包了**。想恢复 32 位支持，
         * 把 "armeabi-v7a" 加回下面那一行即可（微信跑在哪个 ABI、模块就跟着哪个）。
         */
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }
    // 独立二改签名与原版不同。无配置时 debug 可本地构建，release 必须有固定签名。
    val stableSigning = if (hasStableSigning) {
        signingConfigs.create("stable") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = keystoreAlias
            keyPassword = keystorePassword
            storeType = "PKCS12"
        }
    } else {
        null
    }
    buildTypes {
        debug { stableSigning?.let { signingConfig = it } }
        release {
            isMinifyEnabled = false
            stableSigning?.let { signingConfig = it }
        }
    }
    testOptions {
        unitTests {
            // Robolectric 要能读到 res/（字符串、颜色、drawable），否则 Compose 界面渲染不出来
            isIncludeAndroidResources = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 传统 KGP（android.builtInKotlin=false）不会自动跟随上面的 compileOptions，
    // 不显式对齐会报「Inconsistent JVM Target Compatibility Between Java and Kotlin Tasks」
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // AGP 内置 Kotlin 的 JVM target 跟随上面的 targetCompatibility（17）。
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // 让 resources/ 下的 META-INF/xposed/* 原样进 APK —— 框架就是靠这三个文件认模块的，
        // 少一个 LSPosed 就当它不是模块（列表里都不出现）
        resources.merges += "META-INF/xposed/*"
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(hasStableSigning) { "Release 构建需要本 fork 的固定签名，详见 signing/README.md" }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseSigning)
}

dependencies {
    // 注入到微信进程里的那部分（config/llm/wechat）刻意只用系统 API + kotlin stdlib，
    // 不引 OkHttp / 序列化库 —— 免得和微信自带的同名库撞车（parent-first 类加载会拿到它那份）。
    // ⚠️ 不能升到 2026.x（compose ui 1.12）：那一代重构掉了 androidx.compose.ui.graphics.layer
    // 包，rememberGraphicsLayer / GraphicsLayer.record / drawLayer 没了，而液态玻璃底栏的
    // 内容捕获（Haze 式）靠它们。miuix 0.9.4 自己声明的 ui 依赖就是 1.6.0，在 1.7.1 上二进制兼容。
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 折叠菜单的展开/收起动画（AnimatedVisibility）。material3/foundation 一般会把它带进来，
    // 但那是传递依赖，说不准哪天就没了 —— 这里显式写一条，版本仍由上面的 BOM 管。
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")

    // Miuix（HyperOS 风格）主题与组件：top.yukonga.miuix.kmp 的 Android 变体。
    // 只用于 App 进程的 Compose 界面，注入微信侧的代码绝不引用它。
    // 前置条件（2026-10 已满足）：Kotlin 2.4.20（读 metadata 2.4.0）、daemon JDK 21（字节码 class v65）。
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")

    // 「每天中午 12:00 提炼说话风格」用它的周期任务：App 没开、手机重启过都照跑，
    // 也不必申请精确闹钟权限（AlarmManager 那条路 Android 12+ 要额外权限）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 图片文字识别（OCR）：ML Kit 的**内置**中文模型 —— 完全离线、不依赖 Play 服务、不要联网下载模型。
    // 它只在 **App 进程**里用（proxy/ProxyServer.kt 的 /proxy/ocr 那条路由），
    // 注入到微信里的那段代码绝不会碰到它 —— 模型和原生库太重，也不该塞进微信的进程。
    // 代价：APK 里多一份模型 + 一个原生库（ABI 已在 defaultConfig 里收窄，见上面的说明）。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 现代 Xposed API（libxposed）。必须是 compileOnly：这些类由框架在运行时提供，
    // 打进 APK 反而会和框架自己那份撞车。
    compileOnly("io.github.libxposed:api:102.0.0")

    // service 是「模块 App ↔ 框架」的那一侧：写入 remote preferences 走它。
    // 必须是 implementation —— 它带一个 ContentProvider，运行时得真的存在。
    // 代价是它的 AAR 声明了 minCompileSdk=37，所以上面的 compileSdk 必须跟到 37。
    implementation("io.github.libxposed:service:102.0.0")

    testImplementation("junit:junit:4.13.2")

    // 截图回归：在 JVM 上用 Robolectric 把真实界面渲染出来
    // （跑得比真机快、还能进 CI；渲染结果会作为 artifacts 上传，方便肉眼核对）
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    // ⚠️ 必须是 debugImplementation：它往**被测 APK 的 manifest** 里塞一个 ComponentActivity，
    // 而 createComposeRule() 要启动的就是它 —— 放 testImplementation 的话运行时找不到这个 Activity
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
