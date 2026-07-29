# Minification is off for v0.1. These rules exist so a release build works when
# it is switched on for v1.0.

# Retrofit interfaces are accessed reflectively.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# kotlinx.serialization keeps generated serializers on @Serializable classes.
-keepclassmembers class com.drivemp3.player.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.drivemp3.player.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
