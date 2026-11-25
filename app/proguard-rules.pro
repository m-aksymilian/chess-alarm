# Spotify App Remote uses Gson reflection on its protocol types.
-keep class com.spotify.protocol.** { *; }
-keep class com.spotify.android.appremote.** { *; }
-keep class com.spotify.sdk.android.auth.** { *; }
-dontwarn com.spotify.**
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.gson.** { *; }

# kotlinx.serialization
-keepclassmembers @kotlinx.serialization.Serializable class app.chessalarm.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class app.chessalarm.**$$serializer { *; }
