import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名凭据保存在仓库之外（keystore.properties 不入库）。
// ⚠️ 缺凭据时打包 release 必须**直接失败**：早先的写法是"缺省就退回未签名"，
// assembleRelease 照样 exit 0，只是产出一个没签名的 APK——
// 传到 GitHub Release 就是发布事故（用户装不上，GitHub 也不会拦）。
// CI 若要构建无签名包，用 -PskipSigning=true 显式声明。
val skipSigning = providers.gradleProperty("skipSigning").orNull == "true"
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val requiredKeystoreKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingKeystoreKeys = requiredKeystoreKeys.filter { keystoreProps.getProperty(it).isNullOrBlank() }

if (missingKeystoreKeys.isNotEmpty() && !skipSigning) {
    logger.warn(
        "NBALive: 未找到完整的签名凭据（keystore.properties 缺少 ${missingKeystoreKeys.joinToString(", ")}）。" +
            "debug 构建不受影响；打包 release 时会直接失败。"
    )
}

android {
    namespace = "com.nbalive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nbalive.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 9
        versionName = "1.2.5"
        vectorDrawables { useSupportLibrary = true }
    }

    if (missingKeystoreKeys.isEmpty() && !skipSigning) {
        signingConfigs {
            create("release") {
                // 统一按仓库根解析：写相对路径时不会因为"相对 app/ 模块"而找不到文件
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 开启代码压缩 + 资源压缩：release 包不上传任何源码，
            // 混淆能让"抓包就能拿到源站解析逻辑"的门槛高一点，也能显著减小体积。
            // 依赖自带 consumer rules（Media3 / OkHttp / Coil / Kotlinx）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (missingKeystoreKeys.isEmpty() && !skipSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // ⚠️ 未签名包必须换个名字：
    // 否则它和正式包**同名同路径**（app-release.apk），
    // 手工往 GitHub Release 上传时根本分不出哪个是能装的，
    // 发上去用户装不上（"应用未安装"）——发布事故。
    // 文件名里带 UNSIGNED，看一眼就知道不能发。
    if (missingKeystoreKeys.isNotEmpty() || skipSigning) {
        androidComponents {
            onVariants(selector().withBuildType("release")) { variant ->
                variant.outputs.forEach { output ->
                    (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName =
                        "app-UNSIGNED-${variant.name}.apk"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 上没有 java.time（API 26 才加入），必须开启脱糖，否则 Android 7.x 启动即崩
        isCoreLibraryDesugaringEnabled = true
    }
    // Kotlin 2.x 推荐用 compilerOptions（kotlinOptions 已进入废弃流程）
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        // 需要 BuildConfig.VERSION_NAME（getPackageInfo 写法在 API 33+ 已废弃）
        // 与 BuildConfig.DEBUG（调试日志开关）
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions {
        // 单元测试跑在 JVM 上，android.util.Log 等框架桩没有实现。
        // 返回默认值而不是抛异常，数据层里的日志调用才不会让测试无法运行。
        unitTests.isReturnDefaultValues = true
    }
}

// ⚠️ 打包 release 时才检查签名凭据：
// 在配置阶段直接抛错会把 debug 构建和单元测试一起弄挂，
// 而真正需要拦住的是"产出一个没签名的 APK 拿去发布"。
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!skipSigning && missingKeystoreKeys.isNotEmpty()) {
            throw GradleException(
                "release 打包缺少签名凭据：keystore.properties 缺少或为空 -> " +
                    "${missingKeystoreKeys.joinToString(", ")}。" +
                    "请在仓库根放好 keystore.properties（内容不入库），" +
                    "或用 -PskipSigning=true 明确构建无签名包（仅供本地调试，别拿去发布）。"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.2")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.2")
    implementation("androidx.media3:media3-ui:1.9.2")
    implementation("androidx.media3:media3-common:1.9.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
