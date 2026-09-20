# Keep libbox JNI bindings (added in milestone 2b)
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }

# OkHttp (safe defaults)
-dontwarn okhttp3.**
-dontwarn okio.**
