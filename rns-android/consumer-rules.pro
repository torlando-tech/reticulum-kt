# Consumer ProGuard rules for rns-android library
# These rules are automatically included when apps consume this library

# Keep Reticulum public API
-keep class network.reticulum.** { *; }
-keep class network.reticulum.android.** { *; }

# Keep ReticulumConfig Parcelable
-keepclassmembers class network.reticulum.android.ReticulumConfig {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep MessagePack serialization (uses Class.forName for MessageBufferU)
-keep class org.msgpack.** { *; }
-dontwarn org.msgpack.**

# Keep BouncyCastle cryptographic classes
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
