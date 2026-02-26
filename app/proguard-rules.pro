# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk/tools/proguard/proguard-android.txt

# Keep Timber
-keep class timber.log.** { *; }

# Keep data classes
-keep class com.google.android.diskusage.** { *; }

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep ViewModel
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
}

# Keep coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep R8 from removing classes accessed via reflection
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep OpenGL
-keep class javax.microedition.khronos.** { *; }
-keep class android.opengl.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep filesystem entities
-keep class com.google.android.diskusage.filesystem.entity.** { *; }

# Keep datasource classes
-keep class com.google.android.diskusage.datasource.** { *; }
