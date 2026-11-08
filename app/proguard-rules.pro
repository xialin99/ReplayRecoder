# ============================================================
# R8 / ProGuard 规则 —— 防反编译 + 防运行时崩溃
# 原则: 让 R8 最大程度混淆业务代码, 仅保留必要的运行时边界
# ============================================================

# --- BuildConfig 类: 关于页直接读 VERSION_NAME/BUILD_TYPE/BUILD_TIME ---
# 字段被代码静态引用, R8 会自动保留; 显式 keep 类名防止反射场景异常
-keep class com.replay.recoder.BuildConfig { *; }

# --- Kotlin 元数据: Kotlin 编译器注入的 @Metadata 注解 ---
# 部分 Kotlin 反射场景(如 Kotlinx Coroutines 内部)会读 Metadata
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.Metadata { *; }

# --- JNI / native 方法: 保留 native 声明与 native 类全限定名 ---
# FFmpeg-kit 等库底层走 JNI, 类名/方法签名不能改
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- ViewBinding 生成的绑定类: layout 文件名决定, XML 引用需保留 ---
-keep class com.replay.recoder.databinding.** { *; }

# --- FileProvider: Manifest 声明 + authorities 字符串, 自动保留 ---
# (R8 不会修改字符串常量, 无需显式规则)

# --- 录制核心类: R8 不得移除/内联采集与编码方法 ---
# ScreenCapturer.start() 创建 MediaCodec + VirtualDisplay + drain 线程,
# 若被 R8 误判为无副作用而内联/移除, 会导致视频编码器不启动、无视频帧。
-keep class com.replay.recoder.record.ScreenCapturer { *; }
-keep class com.replay.recoder.record.AudioCapturer { *; }
-keep class com.replay.recoder.record.SegmentWriter { *; }
-keep class com.replay.recoder.record.RecordService { *; }
-keep class com.replay.recoder.record.SegmentManager { *; }
-keep class com.replay.recoder.record.RecordState { *; }
-keep class com.replay.recoder.record.RecordConfig { *; }
-keep class com.replay.recoder.record.WatermarkConfig { *; }
# MediaProjection.Callback / MediaCodec 相关匿名内部类与回调
-keep class * extends android.media.projection.MediaProjection$Callback { *; }

# --- AndroidX / 三方库自带 consumer-rules, 无需在此重复声明 ---

# --- JSch: SSH 客户端库,反射创建加密算法实例,必须保留 ---
-keep class com.jcraft.jsch.** { *; }
-keep class com.jcraft.jsch.jce.** { *; }
-dontwarn com.jcraft.jsch.**

# --- jackpal VT100 终端模拟器:含 native 方法与反射,全量保留 ---
-keep class jackpal.androidterm.** { *; }
-dontwarn jackpal.androidterm.**

# --- SSH 数据类:Gson 序列化/反序列化,字段名不能混淆 ---
-keep class com.replay.recoder.ssh.SshConnection { *; }
-keep class com.replay.recoder.ssh.SshConfigStore { *; }

# --- Gson:泛型类型反射 ---
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# --- AndroidX 注解 @Keep 标记的类/成员 ---
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# --- 四大组件:由 Manifest 自动保留,此处兜底防止 R8 误删 ---
-keep class com.replay.recoder.ui.**Activity { *; }
-keep class com.replay.recoder.ui.**Receiver { *; }
-keep class com.replay.recoder.record.**Service { *; }

# --- 优化: 移除调试日志(可选, 减小体积) ---
# -assumenosideeffects class android.util.Log {
#     public static *** v(...);
#     public static *** d(...);
#     public static *** i(...);
# }
