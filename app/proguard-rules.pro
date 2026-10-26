# R8 rules for release builds. Firebase, Credential Manager, OkHttp, Ktor and kotlinx.serialization
# ship their own consumer rules; these only cover what is specific to this app.

# Keep line numbers for readable crash reports, but not the source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: keep the generated serializers of our API models (data/ApiModels.kt).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.jadbarakat.blooddonor.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.jadbarakat.blooddonor.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.jadbarakat.blooddonor.**
-keepclassmembers class com.jadbarakat.blooddonor.<1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# No logging in release: exceptions can carry server messages, and nothing personal (tokens,
# phone numbers, emails) may reach logcat. R8 removes these calls entirely.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Ktor references JVM-only classes that don't exist on Android and are never used there.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.**
