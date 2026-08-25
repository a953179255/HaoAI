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
