# Preserve Device Admin and Receivers
-keep class com.drmohammed.antiporn.AdminReceiver { *; }
-keep class com.drmohammed.antiporn.PackageChangeReceiver { *; }
-keep class com.drmohammed.antiporn.curfew.CurfewAlarmReceiver { *; }
-keep class com.drmohammed.antiporn.DnsWatchdogService { *; }

# Keep AppPolicyRegistry and models
-keep class com.drmohammed.antiporn.core.policy.** { *; }
-keep class com.drmohammed.antiporn.core.model.** { *; }
-keep class com.drmohammed.antiporn.core.contract.** { *; }

# CodeRabbit MINOR-7: AI shield + TFLite — verify minified release loads both models.
-keep class com.drmohammed.antiporn.ai.core.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ONNX Runtime preservation for minified release
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**