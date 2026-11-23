# Shizuku reflection and service binding rules
-keep class * extends dev.rikka.shizuku.Shizuku$* { *; }
-keep class dev.rikka.shizuku.** { *; }
-keep interface dev.rikka.shizuku.** { *; }

# AIDL and shell IPC services
-keep class com.optimizer.game.IShellService { *; }
-keep class com.optimizer.game.IShellService$* { *; }
-keep class com.optimizer.game.shizuku.ShellUserService { *; }

# Native ADB Daemon entrypoint for app_process execution
-keep class com.optimizer.game.adb.DaemonServer { *; }
-keepclassmembers class com.optimizer.game.adb.DaemonServer {
    public static void main(java.lang.String[]);
}

# dadb library rules
-keep class dadb.** { *; }
-keep interface dadb.** { *; }
-dontwarn dadb.**

# BouncyCastle cryptographic provider and ASN.1 / X.509 classes
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }
-keep class org.bouncycastle.crypto.digests.SHA256Digest { *; }
-keep class org.bouncycastle.crypto.generators.HKDFBytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.HKDFParameters { *; }
-dontwarn org.bouncycastle.**

# Coroutines and AndroidX
-keepclassmembers class * extends androidx.lifecycle.ViewModel { *; }
-dontwarn kotlinx.coroutines.**

# Conscrypt TLS & native ADB pairing library
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**
-dontwarn java.util.spi.ToolProvider
