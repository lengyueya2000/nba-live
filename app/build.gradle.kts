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
        versionCode = 19
        versionName = "1.2.15"
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
                    // ⚠️ 文件名必须改掉，否则未签名包和正式包**同名同路径**
                    // （app-release.apk），手工上传 GitHub Release 时分不出哪个能装，
                    // 用户装不上（"应用未安装"）—— 发布事故。
                    //
                    // ⚠️ AGP 目前**没有公开的 outputFileName setter**
                    // （`VariantOutput` 只暴露只读属性），所以只能落到内部实现类。
                    // 审查报告（04-P2-7）要求去掉对内部类的依赖，但那需要 AGP 升级 ——
                    // 这里至少把 `as?` 的**静默失败**改成显式报错：
                    // 转换失败说明 AGP 内部结构变了，必须让人当场知道，
                    // 而不是悄悄产出同名包。
                    val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl
                    if (impl == null) {
                        throw GradleException(
                            "无法重命名未签名包：AGP 的 VariantOutput 实现类变了" +
                                "（期望 VariantOutputImpl）。请检查 AGP 版本升级，" +
                                "并改用新的公开 API —— 绝不能静默产出与正式包同名的未签名包。",
                        )
                    }
                    impl.outputFileName = "app-UNSIGNED-${variant.name}.apk"
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
        // ⚠️ 把单测 JVM 的时区**钉死**（review/audit-guards.md「未验证的怀疑 #1」）。
        //
        // 生产把源站时间串按 `MatchStatusClock.SOURCE_ZONE`（Asia/Shanghai）解释，
        // 而测试夹具如果按**设备默认时区**造时间，就只在东八区机器上与生产一致 ——
        // 实测钉成 UTC 后立刻有 3 条变红（`JrkanParserTest` 1 条、
        // `LiveStatusCorrectionTest` 2 条），是**真缺陷**而非环境问题；
        // 夹具已改为使用 `SOURCE_ZONE`。
        //
        // 钉死之后这条隐患变成**常驻守卫**：以后谁再写 `systemDefault()` 造时间，
        // 在任何机器上都会红，而不是"换台机器才暴露"。
        unitTests.all { it.systemProperty("user.timezone", "UTC") }
    }
}

// ⚠️ 打包 release 时才检查签名凭据：
// 在配置阶段直接抛错会把 debug 构建和单元测试一起弄挂，
// 而真正需要拦住的是"产出一个没签名的 APK 拿去发布"。
//
// ⚠️ 任务名匹配修正（review/04-security.md P2-8）：
// 原来只匹配 "packageRelease" / "bundleRelease" 两个**字面任务名**。
// AGP 的 AAB 任务是 `bundleRelease` 没错，但 APK 侧的实际任务名是
// `packageRelease` 之外的变体，且不同 AGP 版本命名会变 ——
// 名字对不上时这段检查**静默不执行**，等于没有保护。
// 改为按任务类型匹配（Zip 类任务里名字含 Release 的打包任务），
// 并用 withType 覆盖所有打包变体。
tasks.withType<com.android.build.gradle.tasks.PackageAndroidArtifact>().configureEach {
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
    // ⚠️ 版本必须与上面的 kotlinx-coroutines-android 一致（review/04-security.md P2-6）：
    // 原来这里写 1.8.1、运行库是 1.9.0。实际编译/运行用的是 1.9.0
    // （lockfile 里 debugUnitTestRuntimeClasspath 锁的就是 1.9.0），
    // 但 testImplementationDependenciesMetadata 配置仍锁着 1.8.1 ——
    // 声明与锁定不一致，升级时容易漏改，也让人误以为测试跑在 1.8.1 上。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// ⚠️ 依赖锁定：
// 没有它，某个传递依赖（比如 okio）被上游悄悄换成新版本时，
// 构建**不报错**，但产物行为会变 —— 而且没人知道改了什么。
// 锁定后版本被钉死，升级必须显式跑 `gradle dependencies --write-locks`。
dependencyLocking {
    lockAllConfigurations()
}
