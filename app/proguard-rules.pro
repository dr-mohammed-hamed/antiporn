# Preserve Device Admin and Receivers
-keep class com.drmohammed.antiporn.AdminReceiver { *; }
-keep class com.drmohammed.antiporn.PackageChangeReceiver { *; }
-keep class com.drmohammed.antiporn.curfew.CurfewAlarmReceiver { *; }
-keep class com.drmohammed.antiporn.DnsWatchdogService { *; }

# Keep AppPolicyRegistry and models
-keep class com.drmohammed.antiporn.core.policy.** { *; }
-keep class com.drmohammed.antiporn.core.model.** { *; }
-keep class com.drmohammed.antiporn.core.contract.** { *; }