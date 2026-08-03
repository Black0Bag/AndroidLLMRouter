# Keep NanoHTTPD classes
-keep class org.nanohttpd.** { *; }
-keep class fi.iki.elonen.** { *; }
# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
# Keep Room generated code
-keep class * extends androidx.room.RoomDatabase { *; }
