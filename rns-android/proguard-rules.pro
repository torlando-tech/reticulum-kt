# Reticulum Android ProGuard rules

# Keep Reticulum public API
-keep class network.reticulum.** { *; }
-keep class network.reticulum.android.** { *; }

# Keep cryptographic classes (BouncyCastle)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep MessagePack serialization
-keep class org.msgpack.** { *; }
-dontwarn org.msgpack.**

# Keep coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Parcelable classes
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
