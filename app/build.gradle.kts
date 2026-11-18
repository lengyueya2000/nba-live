import java.util.Properties
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // B10：单测覆盖率（review/optimization-review.md B10）
    jacoco
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
        versionCode = 31
        versionName = "1.2.27"
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
            // ⚠️ B5（review/optimization-review.md）：发布产物不该带**构建元数据**。
            //
            // AGP 会把 VCS 信息写成 `META-INF/version-control-info.textproto`（含本次构建的
            // git revision）。实测（2026-11-01，逐 entry 检查产物）：`packaging.resources.excludes`
            // **挡不住**它 —— 它走的是另一条资源通道；带/不带前导斜杠两种写法都试过。
            //
            // 正确开关是**公开 DSL**（javap 实证 AGP 8.7.3：
            // `com.android.build.api.dsl.BuildType#vcsInfo(Function1<VcsInfo, Unit>)`，
            // `VcsInfo#include` 是公开属性；旧的 `android.enableVcsInfo` Gradle 属性已被 AGP
            // 弃用，字节码里的提示原文是"This feature is now enabled in the DSL per build type
            // with \"vcsInfo.include = true\""）——
            // 所以这里**不碰任何 AGP 内部实现类**（04-P2-7 的教训），只在 release 构建类型上关掉。
            vcsInfo { include = false }
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // ⚠️ 公开产物不该带构建元数据（review/optimization-review.md B5）：
            // 实测 release APK 里还有 `META-INF/README.md`（3,218 B）与
            // `META-INF/version-control-info.textproto`（120 B，内含本次构建的 git revision）。
            // 体积收益很小（约 3 KB），但"发布产物不带构建元数据"是常见纪律红线，收掉零成本。
            //
            // ⚠️ 路径带前导斜杠（与上面那条已有的 exclude 同形）。
            //
            // ⚠️ 实测结论（2026-11-01，用产物逐 entry 验证，不是看配置）：
            //   - `META-INF/README.md`：**已被挡下** ✅；
            //   - `META-INF/version-control-info.textproto`：**挡不住**（带/不带前导斜杠都试过，
            //     产物里仍然在）。它是 AGP 的 VCS 信息，走的是 java 资源那条路，
            //     不被 `packaging.resources.excludes` 覆盖；
            //     要真正去掉得挂 AGP 内部任务类型，而本仓库**明确不碰 AGP 内部实现**
            //     （review/04-security.md P2-7 的教训），故如实保留。
            //   影响评估：120 B；内容只有本次构建的 git revision，而本仓库是公开仓库、
            //   revision 本来就是公开信息。这条的价值在"知道它在那儿"，不在于能删掉它。
            excludes += "/META-INF/README.md"
            excludes += "/META-INF/version-control-info.textproto"
        }
    }
    lint {
        // ⚠️ review/optimization-review.md B7：lint 此前**不是闸** ——
        // release 只顺带跑 lintVital（fatal-only），warning/information 没有任何自动化，
        // 唯一一份完整报告还是几天前手工跑的（里面 2 条，其中
        // `AutoboxingStateCreation` 指向 `PlayerScreen.kt` 的 `mutableStateOf(0)`）。
        //
        // 这里把松紧**显式写出来**：error 阻塞、warning 不阻塞 ——
        // 写出来是为了防止有人为了让某次构建变绿而悄悄改掉这条策略
        // （AGP 默认就是 abortOnError=true，所以这不是新负担）。
        abortOnError = true
        warningsAsErrors = false
        // release 变体也跑 lint（此前只有 lintVital，fatal 之外一律看不见）
        checkReleaseBuilds = true
        // ⚠️ 刻意**不**设 baseline：baseline 会把这批问题一次性合法化，
        // 而"把违规记进 baseline 再也不用管"正是本仓库反复批评的假安全感形态。
        textReport = true
        htmlReport = true
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
//
// ⚠️ v1.2.25 实测教训（发版当天的 ci.yml）：**上面那句"名字含 Release"从来没落实到代码上**。
// `withType<PackageAndroidArtifact>()` 单独用会匹配**所有**打包任务，含 `packageDebug`，
// 于是 runner 上没有 `keystore.properties` 时，debug 打包报「release 打包缺少签名凭据」当场失败
// （日志：`> Task :app:packageDebug FAILED`）。而 debug 用的是 AGP 自带的 debug keystore，
// 跟发布红线毫无关系 —— 这道闸把日常 CI 变成了"永远红"，反而让真该拦的 release 路径没人看。
// 加 `.matching { it.name.contains("Release") }`：既覆盖 packageRelease 与各 flavor 的
// `packageXxxRelease`（换 AGP 版本也不会静默失效），又放过 debug。
tasks.withType<com.android.build.gradle.tasks.PackageAndroidArtifact>()
    .matching { it.name.contains("Release") }
    .configureEach {
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
    // ⚠️ B3（review/optimization-review.md）：这里原来依赖的是 **material-icons-extended**，
    // 它的 `classes.jar` 是 **35.7 MB**（每个图标一个类）。本 App 实际只用三十来个图标，
    // 其中 11 个由 `material-icons-core` 就能提供（那个 AAR 只有 0.8 MB），
    // 其余 25 个已由 `review/fix/icon-extract.py` 从官方 AAR 的**字节码**里逐命令还原成
    // `ui/icons/AppIcons.kt`（等价性由 `AppIconsParityTest` 逐项比对钉死）。
    // 于是 release 侧少给 R8 喂 35.7 MB，debug 包也从 12 个 dex 降下来。
    implementation("androidx.compose.material:material-icons-core")
    // 官方 extended 只留给**测试**：`AppIconsParityTest` 要拿它做比对基准。
    // 它不会再进 APK（testImplementation 不参与打包）。
    testImplementation("androidx.compose.material:material-icons-extended")

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

// ---------------------------------------------------------------------------
// B10 单测覆盖率（review/optimization-review.md B10）
// ---------------------------------------------------------------------------
//
// ## 为什么加它
//
// 本轮审计点名"哪些生产代码没被测到"在本仓库**没有数据**——714（现 836）条用例里
// 45% 是"把 Kotlin 源码当字符串匹配"的结构守卫，覆盖率报告是唯一能回答
// "剩下那 55% 到底盖住了多少生产代码"的手段。
//
// ## 为什么用 Gradle 自带的 jacoco 插件而不是 AGP 的开关
//
// AGP 的 `enableUnitTestCoverage` 只覆盖 instrumented 测试；JVM 单测走 Gradle 的
// `jacoco` 插件即可（它随 Gradle 发行版提供，不引第三方插件）。
//
// ⚠️ 依赖锁定：本模块 `lockAllConfigurations()` 会把插件新增的 configuration 也纳入锁定，
// 所以**首次接入后必须跑一次 `gradle :app:jacocoDebugUnitTestReport --write-locks`**
// 把 jacoco 自身的条目写进 `app/gradle.lockfile`；否则离线/CI 上会因为"lock state 缺失"直接失败。
// （这正是审计 B10 把它记为 M 成本的原因 —— 不是加插件难，是锁文件要重生成。）
//
// 用法：`.\gradlew.bat :app:jacocoDebugUnitTestReport`
// 报告：`app/build/reports/jacoco/jacocoDebugUnitTestReport/{html/index.html,xml/report.xml}`
jacoco {
    // 与 Gradle 8.9 内置默认一致；写出来是为了让升级 Gradle 的人看见这处耦合。
    toolVersion = "0.8.12"
}

// ⚠️ 只给 **debug 单测**开 agent（不要对整个 `Test` 类型开，也不要 `tasks.named(...)`）：
//  - 针对整个类型开：`testReleaseUnitTest` 也会往 `build/jacoco/` 写 .exec，
//    而报告任务若用 `fileTree(buildDir)` 去读，Gradle 执行期校验会报
//    "Gradle detected a problem with the following location: …testReleaseUnitTest.exec"；
//  - `tasks.named("testDebugUnitTest")`：AGP 的单测任务是在 `android {}` 之后**才注册**的，
//    配置期直接 `named` 会抛 "Task with name 'testDebugUnitTest' not found"（本轮实测）。
//    所以用 `matching { }.configureEach { }` —— 它对"稍后才出现的任务"同样生效。
tasks.withType<Test>().matching { it.name == "testDebugUnitTest" }.configureEach {
    configure<JacocoTaskExtension> {
        // 单测默认不开 agent，这里显式打开：下面的报告任务依赖这份 .exec。
        isEnabled = true
    }
}

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    group = "verification"
    description = "debug 变体单测覆盖率（HTML + XML；配合 CI 报告用）"
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        files(
            // Kotlin 与 Java 两处产物都要收：本模块几乎全是 Kotlin。
            fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
                // 生成类不算覆盖率（R / BuildConfig 没有可测逻辑）
                exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*")
            },
            fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
                exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*")
            },
        ),
    )
    // 精确到"这个任务产出的那一个文件"，不要扫整个 build 目录。
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
}

/*
 * 覆盖率**闸**（v1.2.26，本轮审查第 6 条建议）。
 *
 * ## 要解决什么
 * 报告任务一直存在，但既没接进 CI、也没有最低阈值 —— "有覆盖率数据"
 * 和"覆盖率不许掉"是两回事。本仓库约 45% 的用例是"把源码当字符串匹配"的
 * 结构守卫，真实行为覆盖到底多少，只有这个数字能回答。
 *
 * ## 阈值怎么定
 * 实测（2026-11-16，jacocoDebugUnitTestReport）：**行 46.5% / 分支 26.6% / 方法 53.2%**。
 * 这里钉 **40% 行覆盖**，取值理由：
 *  - 明显低于现状 → 不是"恰好等于今天"的脆闸（改一行就红会让人直接删闸）；
 *  - 又足以挡住"整片功能没测还全绿"的滑坡（掉到 39% 即红）。
 * 之后只许往上调，不许往下调。
 *
 * ⚠️ 只统计 **debug 单测的 .exec**，与上面的报告任务同源；用
 * JacocoCoverageVerification（Gradle 公开任务类型），不碰任何 AGP 内部实现类
 * （04-P2-7 的纪律）。
 */
tasks.register<JacocoCoverageVerification>("jacocoCoverageGate") {
    group = "verification"
    description = "覆盖率闸：debug 单测行覆盖低于阈值即失败（CI 消费，见 ci.yml）"
    dependsOn("testDebugUnitTest")

    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        files(
            fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
                exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*")
            },
            fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
                exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*")
            },
        ),
    )
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // 与实测 46.5% 之间留 6.5 个百分点的余量（见上方说明）
                minimum = "0.40".toBigDecimal()
            }
        }
    }
}
