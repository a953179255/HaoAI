-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.haoai.agent.**$$serializer { *; }
-keepclassmembers class com.haoai.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.haoai.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Room / WorkManager ──────────────────────────────────────────
# WorkDatabase 等 Impl 由反射 <init>() 实例化，R8 full mode 会剥掉构造器：
# v0.18.6 release 实测启动即崩 InitializationProvider → WorkDatabase_Impl NoSuchMethod
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.** { *; }
