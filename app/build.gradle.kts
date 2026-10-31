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
        versionCode = 28
        versionName = "1.2.24"
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
    //
    // ⚠️ 不要再用 AGP 内部实现类 `com.android.build.api.variant.impl.VariantOutputImpl`
    // 改 outputFileName（review/04-security.md P2-7）：
    // javap 实测 AGP 8.7.3 的**公开** `com.android.build.api.variant.VariantOutput`
    // 只有 versionCode / versionName / enabled / enable 四个 Property，
    // 根本没有 outputFileName —— 旧写法依赖 AGP 私有结构，升级时会静默失效或当场抛错。
    // 现在只依赖 Gradle 自己的任务与文件 API：打包任务跑完后把产物改名。
    //  - AGP 对未签名 release APK 的默认文件名**实测**是 `app-release-unsigned.apk`，
    //    改名后是 `app-UNSIGNED-release.apk`；
    //  - 正式签名包的 `app-release.apk` 不含 `-unsigned`，完全不受影响；
    //  - 万一 AGP 以后改了命名导致这里匹配不到，产物也仍是 `*-unsigned.apk`，
    //    **绝不会**占用 `app-release.apk` —— 退化方向是安全的。
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
    // 未签名产物改名（见上面 P2-7 的说明）：只动 `*-unsigned.apk`，
    // 正式签名包 `app-release.apk` 一个字都不碰。
    doLast {
        val apkRoot = layout.buildDirectory.dir("outputs/apk").get().asFile
        val unsigned = apkRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { variantDir ->
                variantDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith("-unsigned.apk") }
                    ?.map { variantDir to it }
                    .orEmpty()
            }
            .orEmpty()
        for ((variantDir, apk) in unsigned) {
            val target = File(variantDir, "app-UNSIGNED-${variantDir.name}.apk")
            if (!apk.renameTo(target)) {
                throw GradleException(
                    "无法重命名未签名包：${apk.absolutePath} -> ${target.absolutePath}。" +
                        "绝不能静默留下可能与正式包混淆的产物。",
                )
            }
            logger.lifecycle("NBALive: 未签名包已改名为 ${target.relativeTo(apkRoot)}（不能发布）")
        }
    }
}

// ⚠️ AAB 侧的真实缺口（review/04-security.md P2-8）：
// `bundleRelease` 的打包任务实测是 `com.android.build.gradle.internal.tasks.PackageBundleTask_Decorated`，
// **不继承** `com.android.build.gradle.tasks.PackageAndroidArtifact` —— 所以上面那道 `doFirst`
// 闸对 AAB 路径**根本不会 configure**，检查静默不执行（等于没有保护）。
// 实测：挪走 keystore.properties 后跑 `:app:bundleRelease` 仍 exit=0，
// 并产出 `outputs/bundle/release/app-release.aab`（默认发布名）。
//
// 修法：闸必须挂在**真正产出 AAB 的那个任务**上，且不依赖任何 AGP 内部类型
// （按任务名 `*ReleaseBundle` 匹配，换 AGP 版本也不会静默失效 —— 见下）：
//  - 无凭据且未显式声明调试 → 在产出前直接失败，磁盘上**不会**出现 `.aab`
//    （实测把闸挂在 `bundleRelease` 生命周期任务上太晚：`packageReleaseBundle`
//     已经把 `app-release.aab` 写出来了，构建失败但文件仍在 —— 不满足原条目的判据）；
//  - `-PskipSigning=true` 是显式调试路径，允许产出，但必须换成醒目的 UNSIGNED 名字，
//    绝不能留在 `app-release.aab` 这个发布路径上。
//    ⚠️ 改名**不能**放在 `packageReleaseBundle.doLast`：AGP 之后的
//    `produceReleaseBundleIdeListingFile` 仍按 `finalBundleFile` 原路径找文件，
//    改名会把构建打红（实测 exit=1 / "finalBundleFile specifies file … which doesn't exist"）。
//    所以改名放在生命周期最后一步 `bundleRelease.doLast`。
tasks.matching { it.name.endsWith("ReleaseBundle") }.configureEach {
    doFirst {
        if (!skipSigning && missingKeystoreKeys.isNotEmpty()) {
            throw GradleException(
                "bundleRelease 缺少签名凭据：keystore.properties 缺少或为空 -> " +
                    "${missingKeystoreKeys.joinToString(", ")}。" +
                    "无签名的 AAB 不能发布；若只是本地调试，用 -PskipSigning=true 显式声明。",
            )
        }
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    doLast {
        // 只有显式跳过签名才会走到这里；正式签名包的 `app-release.aab` 一个字都不碰。
        if (!skipSigning) return@doLast
        val bundleDir = layout.buildDirectory.dir("outputs/bundle/release").get().asFile
        val unsigned = bundleDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".aab") && !it.name.startsWith("app-UNSIGNED-") }
            .orEmpty()
        for (aab in unsigned) {
            val target = File(bundleDir, "app-UNSIGNED-release.aab")
            if (!aab.renameTo(target)) {
                throw GradleException(
                    "无法重命名未签名 AAB：${aab.absolutePath} -> ${target.absolutePath}。" +
                        "绝不能静默留下可能与正式包混淆的产物。",
                )
            }
            logger.lifecycle("NBALive: 未签名 AAB 已改名为 ${target.relativeTo(bundleDir)}（不能发布）")
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
