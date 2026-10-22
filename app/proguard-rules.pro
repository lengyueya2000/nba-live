# release 构建的 R8 规则。
#
# 依赖（Media3 / OkHttp / Coil / Jsoup / Kotlinx）自带 consumer rules，
# 这里只补 App 自身的保留项与诊断规则。

# ---- 保留诊断信息：崩溃栈里能看出行号与源文件名 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 数据模型：@Immutable 的 data class 参与 Compose 重组稳定性判断，
#      字段名变化不影响功能，但保留可读性（便于线上崩溃栈排查）----
-keep class com.nbalive.app.data.model.** { *; }

# ---- Jsoup：解析器会用到一些反射式的能力检测 ----
-dontwarn org.jsoup.**
-keep class org.jsoup.nodes.** { *; }

# ---- OkHttp / Okio：部分平台实现带可选依赖，缺失时只警告不报错 ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**

# ---- 保留 Kotlin 元数据（Compose 编译器与反射式序列化依赖）----
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, RuntimeVisible*Annotations, AnnotationDefault

# 去掉混淆带来的告警噪音（不影响功能）
-dontnote **
