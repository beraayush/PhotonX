-keep class com.photonx.camera.** { *; }
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
-dontwarn com.google.mlkit.**
-keepclassmembers class * {
    @androidx.camera.core.impl.* <methods>;
}
