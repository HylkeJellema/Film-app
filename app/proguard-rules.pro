# ML Kit models are looked up reflectively by the vision pipeline.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# kotlinx.serialization keeps generated serializers on the companion.
-keepclassmembers class com.kickercam.** {
    *** Companion;
}
-keepclasseswithmembers class com.kickercam.** {
    kotlinx.serialization.KSerializer serializer(...);
}
