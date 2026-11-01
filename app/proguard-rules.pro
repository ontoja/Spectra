# Strip all android.util.Log calls in release builds (defense in depth: no secrets are logged anyway).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.spectra.**$$serializer { *; }
-keepclassmembers class app.spectra.** { *** Companion; }
# BouncyCastle lightweight API used directly
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.ec.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
