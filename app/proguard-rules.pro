# Arrest Me ProGuard Rules
-keep class com.arrestme.benchmark.data.local.** { *; }
-keep class com.arrestme.benchmark.data.remote.dto.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
