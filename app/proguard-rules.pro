# Preserve Device Admin and Receivers
-keep class com.drmohammed.antiporn.AdminReceiver { *; }
-keep class com.drmohammed.antiporn.PackageChangeReceiver { *; }
-keep class com.drmohammed.antiporn.curfew.CurfewAlarmReceiver { *; }
-keep class com.drmohammed.antiporn.curfew.CurfewBootReceiver { *; }
-keep class com.drmohammed.antiporn.DnsWatchdogService { *; }

# Keep AppPolicyRegistry and models
-keep class com.drmohammed.antiporn.core.policy.** { *; }
-keep class com.drmohammed.antiporn.core.model.** { *; }
-keep class com.drmohammed.antiporn.core.contract.** { *; }

# AI shield + TFLite — verify minified release loads both models.
-keep class com.drmohammed.antiporn.ai.core.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ONNX Runtime preservation for minified release
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**


# ====================================================================
# Google ML Kit Barcode Scanning & Component Discovery (R8 Full Mode Fix)
# ====================================================================
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    void <init>();
}
-keep class * implements com.google.mlkit.common.sdkinternal.ComponentRegistrar {
    void <init>();
}
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode** { *; }
-keep class com.google.mlkit.common.** { *; }
-keep class * extends com.google.mlkit.common.internal.LazyInstanceMap { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_barcode**

# ====================================================================
# CameraX R8 Full Mode Preservation
# ====================================================================
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.view.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep,allowobfuscation,allowshrinking class ** implements androidx.camera.core.impl.Quirk { *; }
