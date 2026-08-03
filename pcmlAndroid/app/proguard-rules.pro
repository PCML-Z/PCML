# PMCL Android ProGuard/R8 规则

# === Kotlin ===
-keepattributes *Annotation*
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# === Compose ===
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# === AndroidX ===
-keep class androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }

# === PMCL Core ===
-keep class com.lash.pmcl.core.** { *; }
-keep class com.lash.pmcl.ui.** { *; }
-keep class com.lash.pmcl.MainActivity { *; }

# === Keep serialization ===
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keep class com.lash.pmcl.core.preferences.** { *; }

# === ZXing (QR code) ===
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# === NBT ===
-keep class com.lash.pmcl.core.nbt.** { *; }

# === OkHttp ===
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# === Gson (if used) ===
-keepattributes Signature
-keep class com.google.gson.** { *; }

# === MediaStream ===
-keep class android.media.** { *; }

# === Strip debug info ===
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# === Keep Composables ===
-keep class * extends androidx.compose.runtime.Composable { *; }
