# ProGuard / R8 rules for lembretes-de-audio
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class com.example.data.db.** { *; }
-keep class com.example.domain.model.** { *; }
