# Keep Kotlin metadata and WebView-related classes
-keep class com.cinehd.tv.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
