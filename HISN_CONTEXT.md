# 🛡️ التوثيق التقني والمعماري الشامل لمشروع «حِصْن» (Hisn / AntiPorn DO)
> **الإصدار الحالي:** 1.75 (Build Code: 13)  
> **الدور المعماري:** Senior Android Systems Architect  
> **الهدف من الوثيقة:** النقل الدقيق والكامل للسياق الهندسي والعملي لكافة ملفات وأكواد المشروع إلى نماذج الذكاء الاصطناعي والمطورين للتخطيط والتطوير المستقبلي.

---

# 1. نظرة عامة والتقنيات المستخدمة (Tech Stack & Environment)

### 1.1 لغة البرمجة ونمط المعمارية
- **لغة البرمجة:** Kotlin بنسبة 100%، بالاعتماد الكامل على النمط الاصطلاحي (Idiomatic Kotlin)، وممنوع منعاً باتاً استخدام الـ Force-Unwrap (`!!`).
- **التزامن والتوازي:** Kotlin Coroutines (`Dispatchers.IO`, `Dispatchers.Main`, `SupervisorJob()`) و StateFlow/SharedFlow لإدارة الحالة غير المتزامنة.
- **إطار العمل والواجهات:** واجهات أندرويد الأصلية Native Android UI مبنية باستخدام تقنية **ViewBinding** وملفات الـ XML (مع استخدام Material Components 3 والتلوين التكيفي Dynamic Colors).
- **النمط المعماري العام:** MVVM (Model-View-ViewModel) مع فصل طبقات الـ Domain والأمان (Shields Architecture) وطبقة الـ Repository مع مبدأ المصدر الموحد للحقيقة (Single Source of Truth - SSOT).

### 1.2 إعدادات الـ SDK والبناء (Build & SDK Configuration)
مستخرجة مباشرة من ملف [`app/build.gradle.kts`](file:///d:/b/antiporn/app/build.gradle.kts):
- **`compileSdk`:** 36 (Android 15+ / Vanilla Ice Cream)
- **`targetSdk`:** 36
- **`minSdk`:** 24 (يدعم أندرويد 7.0 فما فوق)
- **`versionCode`:** 13
- **`versionName`:** "1.75"
- **أداة البناء:** Gradle Kotlin DSL (`build.gradle.kts`) مع Android Gradle Plugin (AGP) بالإصدار `9.0.1`.
- **التوافقية (Java Compatibility):** Java 11 (`JavaVersion.VERSION_11`).
- **معمارية المعالجات المدعومة (NDK ABI Filters):** `arm64-v8a`, `armeabi-v7a`.
- **اللغات المدعومة (Resource Configurations):** العربية (`ar`) والإنجليزية (`en`).

### 1.3 أهم المكتبات والتبعيات المضافة (Core Dependencies)
مستخرجة من [`gradle/libs.versions.toml`](file:///d:/b/antiporn/gradle/libs.versions.toml) و [`app/build.gradle.kts`](file:///d:/b/antiporn/app/build.gradle.kts):
1. **الذكاء الاصطناعي والرؤية الحاسوبية على الجهاز (On-Device AI / ML):**
   - `org.tensorflow:tensorflow-lite:2.16.1`: تشغيل نموذج Halalify لكشف صور النساء وغض البصر.
   - `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`: تشغيل نموذج NudeNet v3 (YOLOv8 Nano) لاكتشاف وتحديد العورات بدقة عتادية عالية.
2. **الخدمات السحابية ومزامنة السياسات (Firebase Cloud Infrastructure):**
   - Firebase BoM (`33.10.0`):
     - `com.google.firebase:firebase-firestore`: المزامنة اللحظية ثنائية الاتجاه للسياسات وطلبات الموافقة والحالة.
     - `com.google.firebase:firebase-auth`: المصادقة المجهولة (Anonymous Auth) مع دعم الربط بحسابات Google.
     - `com.google.firebase:firebase-messaging`: استقبال رسائل الـ FCM الصامتة وفائقة الأولوية لتطبيق السياسات وفك الحزم فورياً.
   - `androidx.credentials:credentials:1.3.0` و `com.google.android.libraries.identity.googleid:googleid:1.1.1`: تسجيل الدخول عبر Google Credential Manager الحديث.
3. **حقن التبعيات (Dependency Injection):**
   - `io.insert-koin:koin-android:3.5.6`: إدارة حقن الاعتماديات لطبقات البيانات و الـ ViewModels.
4. **الترقية لصلاحية مسؤول الجهاز ذاتياً (Wireless ADB Provisioning):**
   - `com.github.rhythmcache:adb-kt:v1.0.13`: عميل ADB مكتوب بلغة Kotlin لتنفيذ بروتوكول المصادقة عبر SPAKE2 TLS 1.3 والاقتران الذاتي المحلي عبر `127.0.0.1`.
5. **الباركود والمسح والطباعة (QR Code & Camera):**
   - `com.google.zxing:core:3.5.3`: توليد أكواد QR لمزامنة السياسات دون اتصال بالإنترنت.
   - `com.google.mlkit:barcode-scanning:17.3.0` مع `androidx.camera:camera-camera2:1.4.1`: قراءة ومسح أكواد QR بالكاميرا.
   - `androidx.print:print:1.0.0`: طباعة وثيقة أو كود الاسترداد الاحتياطي.
6. **قاعدة البيانات والتسجيل:**
   - SQLite مدمجة داخلية عالية الأداء بنمط WAL (Write-Ahead Logging).
   - `com.jakewharton.timber:timber:5.0.1`: تسجيل الأحداث البرمجية في إصدارات التطوير.

---

# 2. هيكلة المشروع وشجرة الملفات (Project Structure)

يمتاز المشروع بتنظيم هندسي يعتمد على فصل المسؤوليات والحزم المتخصصة وفق المعمارية التالية:

```
com.drmohammed.antiporn
│
├── AdminReceiver.kt                       # مستقبل صلاحيات إدارة ومسؤول الجهاز (DeviceAdminReceiver)
├── AntiPornApplication.kt                 # نقطة انطلاق التطبيق، تهيئة Koin، CrashHandler، والوقت الموثوق
├── DnsWatchdogService.kt                  # خدمة الحراسة الأمامية لمراقبة إعدادات الـ DNS وشبكة الجهاز
├── MainActivity.kt                        # شاشة التحكم الرئيسية (ViewPager2 مع 4 تبويبات)
├── PackageChangeReceiver.kt               # استقبال أحداث تثبيت وإلغاء تثبيت التطبيقات فور وقوعها
│
├── ai/                                    # منظومة الذكاء الاصطناعي والتعتيم الموضعي اللحظي
│   ├── core/
│   │   ├── inference/                     # محركات الاستدلال لنماذج TFLite و ONNX
│   │   │   ├── HalalifyGenderDetector.kt  # فاحص تصنيف وكشف النساء عبر TFLite
│   │   │   ├── IModestyDetector.kt        # واجهة تجريد محرك الاستدلال
│   │   │   ├── MobileNetOutputDecoder.kt  # فك شفرة مصفوفات خرج MobileNet
│   │   │   ├── MobileNetV4ModestyDetector.kt # كاشف العورات المبني على MobileNetV4
│   │   │   ├── ModestyPostProcessor.kt    # معالجة مصفوفات الإحداثيات وتطبيق NMS
│   │   │   ├── NudeNetDetector.kt         # كاشف NudeNet v3 عبر ONNX Runtime
│   │   │   └── TfLiteModestyDetector.kt   # مشغل TFLite العام
│   │   ├── model/                         # نماذج البيانات الخاصة باكتشافات الذكاء الاصطناعي
│   │   │   ├── HalalifyDetection.kt
│   │   │   ├── ModestyClass.kt
│   │   │   ├── ModestyDetection.kt
│   │   │   ├── ModestyInferenceResult.kt
│   │   │   └── NudeNetDetection.kt
│   │   ├── pipeline/                      # خط معالجة الإطارات الموفر للطاقة والذاكرة
│   │   │   ├── ContextualGatingWatcher.kt # استثناء التطبيقات الآمنة وتوجيه الفحص للأهداف
│   │   │   ├── FrameDifferencer.kt        # مقارنة الفروق بين الإطارات لتخطي المعالجة المتطابقة
│   │   │   └── RowStrideExtractor.kt      # فك قيود الـ Stride لمصفوفات البكسل بكفاءة
│   │   ├── policy/
│   │   │   └── AiExemptionRegistry.kt     # السجل المعياري لاستثناءات فحص الذكاء الاصطناعي
│   │   └── tracking/                      # التتبع البصري فائق السرعة لصناديق التعتيم
│   │       ├── KltMathHelper.kt
│   │       ├── KltOpticalFlowTracker.kt   # خوارزمية Kanade-Lucas-Tomasi (KLT) للتتبع البصري
│   │       ├── KltPyramidHelper.kt
│   │       ├── KltSolver.kt
│   │       └── TrackedModestyBox.kt
│   ├── diagnostic/
│   │   └── TelegramAlertDispatcher.kt     # إرسال تنبيهات الاختراق وتشخيص الذكاء الاصطناعي
│   ├── overlay/
│   │   └── ScreenBlurOverlayManager.kt    # رسم نوافذ التعتيم الداكنة فوق العورات بنمط TYPE_APPLICATION_OVERLAY
│   └── service/
│       └── ScreenAiProjectionService.kt   # خدمة التقاط الشاشة وتحليلها (MediaProjection FGS)
│
├── cloud/                                 # التكامل مع الخدمات السحابية ومصادقة المستخدم
│   ├── auth/
│   │   └── GoogleAuthManager.kt           # ترقية الحساب المجهول إلى حساب Google دائم
│   └── fcm/
│       └── AntiPornFirebaseMessagingService.kt # استقبال أوامر الحماية وتحديث السياسات عبر FCM الصامت
│
├── core/                                  # النواة المركزية وقواعد الأمان المشتركة
│   ├── contract/
│   │   └── ProtectionShield.kt            # العقد المعياري لكافة دروع الحماية البرمجية
│   ├── engine/
│   │   └── ProtectionEngine.kt            # المنسق العام لتطبيق وفحص حالة الدروع الستة
│   ├── model/
│   │   ├── DevicePolicyBundle.kt          # حزمة إعدادات وسياسات الحماية الشاملة للجهاز
│   │   ├── IslamicTimeZoneRegistry.kt     # سجل المناطق الزمنية الإسلامية المعتمدة
│   │   ├── ShieldResult.kt                # نتيجة تطبيق الدرع
│   │   └── ShieldStatus.kt                # الحالة الحالية للدرع (نشط، يحتاج إنفاذ، غير مدعوم)
│   ├── network/
│   │   ├── DnsFilteringValidator.kt       # فحص واختبار حجب خوادم النطاقات
│   │   └── dns/DnsPacketParser.kt         # محلل حزم الـ DNS (Port 53 UDP) وقراءة أسماء النطاقات
│   ├── policy/
│   │   ├── AppClassification.kt           # تصنيفات الحزم البرمجية
│   │   ├── AppPolicyRegistry.kt           # السجل المركزي للقوائم السوداء والبيضاء والمتصفحات
│   │   └── AppRule.kt                     # قواعد التطبيقات
│   ├── time/
│   │   ├── SntpClient.kt                  # عميل SNTP للتواصل مع خوادم الوقت الذرية
│   │   └── TrustedTimeProvider.kt         # مزود الوقت الموثوق المضاد للتلاعب وتغيير الساعة
│   └── util/
│       └── BrowserUrlSanitizer.kt         # تنظيف ومطابقة عناوين الروابط وقوائم السماح والحجب
│
├── curfew/                                # منظومة حظر التجوال الليلي ووضع القيلولة
│   ├── CurfewAlarmReceiver.kt             # استقبال منبهات بدء وانتهاء حظر التجوال والقيلولة وتغير الوقت
│   ├── CurfewAppResolver.kt               # تمييز تطبيقات الترفيه المحظورة ليلاً عن التطبيقات الأساسية
│   ├── CurfewBlackholeVpnService.kt       # خدمة VPN ثنائية: فلترة DNS نهاراً وثقب أسود عازل للإنترنت ليلاً
│   ├── CurfewConfig.kt                    # ثوابت وإعدادات حظر التجوال والمناطق الزمنية
│   ├── CurfewEnforcer.kt                  # المنفذ الفعلي لتعليق التطبيقات وإخفائها وتفعيل العزل الشبكي
│   ├── CurfewForegroundWatcher.kt         # مراقبة التطبيقات في الواجهة (للحالات الاستثنائية)
│   ├── CurfewScheduler.kt                 # جدولة تنبيهات الحظر باستخدام Exact Alarm
│   ├── NapConfig.kt                       # إعدادات وضع القيلولة (60 دقيقة حظر تام)
│   ├── NapController.kt                   # إدارة حالة ومؤقت القيلولة المعتمد على ساعتين متزامنتين
│   ├── ZeroTrustForegroundWatcher.kt      # مراقبة فورية لإنفاذ حظر التطبيقات غير المعتمدة
│   └── notification/
│       ├── NapNotificationReceiver.kt     # استقبال طلب تمديد القيلولة (+60 دقيقة)
│       └── ProtectionNotificationCoordinator.kt # تنسيق إشعارات الحظر والقيلولة
│
├── data/                                  # طبقة البيانات والتخزين المحلي والسحابي
│   ├── local/db/
│   │   ├── AntiPornDatabase.kt            # قاعدة بيانات SQLite المدمجة (نمط WAL)
│   │   └── entity/                        # كيانات الجداول المحلية
│   │       ├── DevicePolicyEntity.kt      # جدول تخزين سياسات الحماية
│   │       ├── PartnerEntity.kt           # جدول رفقاء الدرب والمشرفين
│   │       └── PendingApprovalEntity.kt   # جدول التطبيقات المعلقة بانتظار الموافقة
│   ├── model/
│   │   └── InstalledAppDto.kt             # كائن نقل بيانات التطبيقات المثبتة
│   └── repository/
│       ├── AccountabilityIdentityMigrator.kt # ترحيل هويات المستخدمين بين الحساب المجهول وجوجل
│       ├── AccountabilityRepository.kt    # إدارة الشراكة والاقتران ورموز الربط (Pairing Codes)
│       ├── AppInventoryManager.kt         # إدارة جرد تطبيقات الجهاز ومزامنتها سحابياً
│       ├── ApprovalRepository.kt          # إدارة تجميد التطبيقات الجديدة في 0ms ونظام الاعتماد
│       └── PolicyRepository.kt            # المصدر الموحد للسياسات والمزامنة مع Firestore
│
├── di/
│   └── AppModule.kt                       # موديول Koin لحقن الاعتماديات و ViewModels
│
├── notification/                          # منظومة الإشعارات المركزية داخل وخارج التطبيق
│   ├── manager/AppNotificationManager.kt  # إدارة قنوات وإشعارات أندرويد
│   ├── model/InAppNotification.kt         # نموذج الإشعار الداخلي المحفوظ في قاعدة البيانات
│   ├── registry/NotificationRegistry.kt   # سجل قنوات ومعرفات الإشعارات
│   ├── repository/NotificationRepository.kt # حفظ واسترجاع الإشعارات من SQLite
│   └── router/NotificationActionRouter.kt # توجيه أحداث النقر على الإشعارات للشاشات المناسبة
│
├── ota/                                   # التحديث الهوائي الصامت المستقل (Over-The-Air Updates)
│   ├── installer/OtaPackageInstaller.kt   # تثبيت التحديثات صامتاً عبر جلسات PackageInstaller بدون تدخل المستخدم
│   ├── model/
│   │   ├── OtaUpdateState.kt
│   │   └── OtaVersionInfo.kt
│   ├── notification/OtaNotificationManager.kt
│   ├── receiver/OtaInstallReceiver.kt     # استقبال نتيجة تثبيت التحديث وتأكيد النجاح
│   ├── remote/OtaRemoteSource.kt          # جلب معلومات التحديث وملفات الـ APK من السحابة (Cloudflare R2)
│   ├── repository/OtaRepository.kt        # إدارة عملية التنزيل والتحقق والتثبيت
│   └── security/OtaIntegrityVerifier.kt   # التحقق من توقيع الـ APK وبصمة SHA-256 قبل التثبيت
│
├── provisioning/                          # تهيئة وترقية مسؤول الجهاز (Device Owner Setup)
│   ├── engine/
│   │   ├── AccountAuditor.kt              # فحص الحسابات المسجلة على الهاتف لتفادي فشل الترقية
│   │   ├── AdbMdnsDiscovery.kt            # اكتشاف منافذ تصحيح الأخطاء اللاسلكية عبر mDNS
│   │   └── DeviceOwnerPromoter.kt         # تنفيذ اقتران SPAKE2 TLS وأمر dpm set-device-owner محلياً
│   ├── receiver/PairingCodeReceiver.kt    # التقاط رمز الاقتران تلقائياً من إشعار النظام
│   ├── service/AdbPairingNotificationService.kt # خدمة الاستماع لرموز اقتران الـ ADB
│   └── ui/
│       ├── EnterpriseProvisioningActivity.kt # معالج إعداد Android Enterprise عبر مسح كود QR
│       └── ProvisioningWizardActivity.kt  # معالج الترقية اللاسلكية خطوة بخطوة داخل التطبيق
│
├── services/
│   └── NetworkWatchdog.kt                 # مراقب التغيرات في شبكة الاتصال والـ Wi-Fi
│
├── shields/                               # دروع الحماية المعزولة الستة (Pluggable Security Shields)
│   ├── AppSuspensionShield.kt             # تجميد وإخفاء تطبيقات القائمة السوداء (تيليجرام، تيك توك، إلخ)
│   ├── BrowserShield.kt                   # تعطيل التصفح الخفي وفرض البحث الآمن وحجب الروابط في كروم
│   ├── CurfewShield.kt                    # إنفاذ حظر التجوال الليلي وإغلاق المتاجر والشبكة
│   ├── DnsShield.kt                       # فرض DNS المشفر (DoT) وإغلاق إعدادات النطاقات الخاصة
│   ├── SystemTamperShield.kt              # منع ضبط المصنع، والوضع الآمن، وتعدد المستخدمين، وتغيير الوقت
│   └── VpnShield.kt                       # إغلاق ثغرة الـ VPN ومنع تجاوز الحماية
│
├── telemetry/                             # المراقبة الجنائية وتسجيل الأخطاء والصندوق الأسود
│   ├── AiTelemetryReporter.kt
│   ├── BlackBoxRecorder.kt                # تسجيل محاولات التلاعب والأحداث الأمنية
│   ├── DiagnosticEventDto.kt
│   ├── GlobalCrashHandler.kt              # لاقط الانهيارات الشامل ومنع التدمير الذاتي
│   └── SafeDpmGateway.kt                  # بوابة استدعاء DPM الآمنة وقياس زمن التنفيذ
│
└── ui/                                    # شاشات وواجهات المستخدم
    ├── ProtectionUiState.kt               # حالة واجهة المستخدم الموحدة
    ├── accountability/                    # شاشات إدارة المشرفين ورفقاء الدرب والتحكم عن بعد
    │   ├── AccountabilityFragment.kt
    │   ├── AccountabilityViewModel.kt
    │   ├── GuardianControlActivity.kt     # لوحة تحكم المشرف بجهاز المكفول عن بعد
    │   └── RemoteAppManagerActivity.kt    # إدارة تطبيقات المكفول وحظرها/السماح بها عن بعد
    ├── browser/
    │   └── BrowserUrlFilterActivity.kt    # إدارة قوائم المواقع المسموحة والمحظورة
    ├── control/                           # تبويب التحكم الذاتي وقفل الإعدادات برمز مرور/QR
    │   ├── SelfControlFragment.kt
    │   ├── SelfControlViewModel.kt
    │   └── qr/                            # توليد ومسح وطباعة باركود التحكم الذاتي
    ├── home/                              # شاشة الحصن الرئيسية وزر القيلولة ودرع الأمان
    │   ├── HomeFragment.kt
    │   └── HomeViewModel.kt
    ├── settings/                          # شاشة الإعدادات، الذكاء الاصطناعي، وسجل التشخيص
    │   ├── DiagnosticLogViewerActivity.kt
    │   ├── SettingsFragment.kt
    │   └── SettingsViewModel.kt
    └── shields/                           # القائمة السفلية لعرض تفاصيل الدروع النشطة
        ├── ActiveShieldsBottomSheet.kt
        └── ActiveShieldsEvaluator.kt
```

---

# 3. هندسة صلاحيات الـ Device Owner والحماية (DO & Security Architecture)

يعمل تطبيق «حِصْن» كمنظومة دفاعية غير قابلة للتحايل (Fail-Secure & Tamper-Proof) عبر استثمار أقصى صلاحيات نظام أندرويد المؤسسية (Device Owner - DO).

### 3.1 تفعيل وإدارة الـ Device Owner
يوفر التطبيق 3 مسارات هندسية للترقية:
1. **الترقية اللاسلكية الذاتية من داخل الهاتف (On-Device Wireless ADB):**
   - يديرها الكلاس [`DeviceOwnerPromoter.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/provisioning/engine/DeviceOwnerPromoter.kt) بالتعاون مع مكتبة `adb-kt`.
   - يقوم التطبيق بالاقتران المحلي مع خدمة تصحيح الأخطاء اللاسلكية على العنوان `127.0.0.1` عبر بروتوكول SPAKE2 TLS 1.3.
   - يكتشف المنافذ عبر `AdbMdnsDiscovery.kt`، ويستقبل رمز الاقتران عبر إشعار النظام المدار بواسطة `AdbPairingNotificationService.kt`.
   - بمجرد اكتمال المصافحة، ينفذ الأمر التالي محلياً:  
     `dpm set-device-owner com.drmohammed.antiporn/.AdminReceiver`
2. **تهيئة الجهاز المؤسسي عند ضبط المصنع (Android Enterprise QR Provisioning):**
   - يستقبل المعالج كود الـ QR الخاص بشركة أندرويد عبر [`EnterpriseProvisioningActivity.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/provisioning/ui/EnterpriseProvisioningActivity.kt).
   - يتعامل مع نوايا النظام: `android.app.action.GET_PROVISIONING_MODE` و `android.app.action.ADMIN_POLICY_COMPLIANCE`.
3. **أمر سطر الأوامر الخارجي (Computer ADB):**
   - `adb shell dpm set-device-owner com.drmohammed.antiporn/.AdminReceiver`

### 3.2 بوابة الـ DPM المركزية الآمنة (`SafeDpmGateway.kt`)
وفقاً لدستور المشروع الهندسي، يُحظر استدعاء `DevicePolicyManager` مباشرة من طبقات الـ UI والـ Repositories. كل التفاعلات تمر عبر [`SafeDpmGateway.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/telemetry/SafeDpmGateway.kt):
- التحقق المسبق من تفعيل الصلاحية (`isAdminActive` و `isDeviceOwnerApp`).
- الإلزام الصارم بالتنفيذ في مسار الخلفية (`Dispatchers.IO`).
- التقاط الاستثناءات الجنائية وقياس زمن التنفيذ بالمللي ثانية، وتسجيل النتيجة في جدول `diagnostic_events` داخل قاعدة بيانات SQLite فوراً.

### 3.3 السياسات والقيود المؤسسية المطبقة حالياً (Policies & Restrictions)
تدار السياسات وتنفذ عبر كلاسات الدروع المعزولة (`shields/`):

| السياسة / القيد | الكلاس المسؤول | الآلية والكود المطبق | الغرض الأمني |
|---|---|---|---|
| **منع إلغاء التثبيت** | `SystemTamperShield.kt` | `dpm.setUninstallBlocked(admin, packageName, true)` | منع المستخدم من حذف تطبيق الحصن من إعدادات التطبيقات. |
| **منع مسح البيانات** | `SystemTamperShield.kt` | `setClearApplicationUserDataBlocked(admin, pkg, true)` (عبر Reflection) + `UserManager.DISALLOW_APPS_CONTROL` | منع المستخدم من الضغط على "Clear Data" أو "Force Stop" لتعطيل الحماية. |
| **منع ضبط المصنع** | `SystemTamperShield.kt` | `dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)` | سد ثغرة إعادة ضبط الهاتف لمسح الحصن وصلاحيات الـ Device Owner. |
| **منع الوضع الآمن (Safe Mode)** | `SystemTamperShield.kt` | `dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)` | منع إعادة تشغيل الهاتف في الوضع الآمن لتجاوز تشغيل الحصن. |
| **منع تعدد المستخدمين وحسابات الضيف** | `SystemTamperShield.kt` | `UserManager.DISALLOW_ADD_USER` و `UserManager.DISALLOW_USER_SWITCH` مع ضبط `user_switcher_enabled=0` | منع فتح مساحة ثانية (Second Space) أو مستخدم ضيف لتصفح الإباحية. |
| **قفل التاريخ والوقت والمنطقة الزمنية** | `SystemTamperShield.kt` & `TrustedTimeProvider.kt` | `UserManager.DISALLOW_CONFIG_DATE_TIME` و `dpm.setTimeZone(admin, targetTz)` و `setAutoTimeEnabled(admin, true)` مع `setAutoTimeZoneEnabled(admin, false)` | منع التلاعب بالساعة لتخطي أوقات حظر التجوال الليلي أو وضع القيلولة. |
| **إغلاق ثغرة الـ VPN** | `VpnShield.kt` | `dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)` | منع إنشاء أو تفعيل أي تطبيق VPN خارجي لتجاوز خوادم الـ DNS النظيفة. |
| **قفل إعدادات الـ Private DNS** | `DnsShield.kt` | `dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)` و `UserManager.DISALLOW_NETWORK_RESET` | منع العبث بإعدادات خادم الـ DNS الخاص بالهاتف أو إعادة ضبط الشبكة. |
| **حظر المتصفحات الخبيثة وتطبيقات الشات** | `AppSuspensionShield.kt` | `dpm.setPackagesSuspended(admin, pkgs, true)` و `dpm.setApplicationHidden(admin, pkg, true)` | تجميد فوري وإخفاء تام لتطبيقات تيليجرام وفروعه، متصفحات Tor/Firefox/Bypass، وشات الروليت وتيك توك. |
| **حظر التصفح الخفي وفرض البحث الآمن** | `BrowserShield.kt` | `dpm.setApplicationRestrictions(admin, pkg, bundle)` | تعطيل التصفح الخفي (Incognito) وفرض Google SafeSearch و YouTube Restricted Mode وحجب الروابط في متصفحات كروم. |
| **العزل الشبكي التام أثناء حظر التجوال** | `CurfewShield.kt` | `dpm.setAlwaysOnVpnPackage(admin, packageName, true, bypassedPackages)` | قفل حركة المرور بالكامل (Always-on Lockdown) وتوجيه البيانات إلى ثقب أسود مع استثناء تطبيقات الطوارئ. |

> [!IMPORTANT]
> **قدسية تصحيح أخطاء USB (Developer ADB Sanctuary):**  
> وفقاً للمادة 2 من دستور المشروع، **يُمنع منعاً باتاً ومطلقاً** تطبيق `UserManager.DISALLOW_DEBUGGING_FEATURES` أو `UserManager.DISALLOW_CONFIG_DEVELOPER_OPTIONS`، لضمان بقاء شريان الحياة الوحيد للمطور مفتوحاً دائماً عبر الحاسوب للإصلاح والبرمجة.

### 3.4 منظومة حجب الإباحية متعددة الطبقات (Multi-Layer Protection Engine)
لا يعتمد «حِصْن» على وسيلة واحدة قابلة للكسر، بل يطبق 4 طبقات حماية متكاملة:
1. **الطبقة الأولى - DNS المشفر على مستوى النظام (DoT Enforced):**
   - يديرها [`DnsShield.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/shields/DnsShield.kt).
   - يفرض تشفير Private DNS (DNS-over-TLS) باستخدام AdGuard Family (`family.adguard-dns.com`) أو NextDNS (`family.cloudflare-dns.com`).
   - يدعم Fallback ذكي: في حال فشل استدعاء DPM في بعض واجهات الشركات (مثل واجهة Realme/ColorOS الخطأ 1)، يتم تفعيله فورياً عبر صلاحية كتابة إعدادات النظام `Settings.Global.putString(..., "private_dns_specifier", host)`.
2. **الطبقة الثانية - بالوعة الـ DNS المحلية الصامتة (Local UDP DNS Sinkhole):**
   - يديرها الكلاس [`CurfewBlackholeVpnService.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/curfew/CurfewBlackholeVpnService.kt) في وضع `DNS_FILTERING_ONLY`.
   - يتم إنشاء نفق VPN محلي يلتقط فقط مسار الـ DNS (عنوان `10.255.255.1/32` للمنفذ 53 UDP) دون إبطاء سرعة الإنترنت للجهاز.
   - يقوم محلل الحزم [`DnsPacketParser.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/core/network/dns/DnsPacketParser.kt) بقراءة استعلام النطاق:
     - إذا كان النطاق إباحياً أو محظوراً، يعيد رد `NXDOMAIN` أو عنوان خامل فوراً في 0ms.
     - إذا كان النطاق سليماً، يمرره عبر خادم المنبع النظيف (Upstream 1.1.1.3 أو خادم العائلة).
3. **الطبقة الثالثة - سياسات كروم المؤسسية وتصفية الروابط (Chromium Managed Policies):**
   - يديرها [`BrowserShield.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/shields/BrowserShield.kt) عبر `setApplicationRestrictions`.
   - ترسل حزم السياسات لكافة المتصفحات المبنية على Chromium (Chrome, Brave, Edge, Samsung Browser, Vivaldi):
     - `IncognitoModeAvailability = 1` (تعطيل الوضع الخفي نهائياً).
     - `ForceGoogleSafeSearch = true`.
     - `ForceYouTubeSafetyMode = true` و `ForceYouTubeRestrict = 2`.
     - `SafeSitesFilterBehavior = 1` (منع المواقع غير الآمنة على محرك المتصفح).
     - `URLBlocklist` و `URLAllowlist` لفرض القوائم المخصصة أو وضع "القائمة البيضاء فقط".
4. **الطبقة الرابعة - الرؤية الحاسوبية والذكاء الاصطناعي اللحظي (On-Device Computer Vision):**
   - تعمل عبر خدمة [`ScreenAiProjectionService.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/ai/service/ScreenAiProjectionService.kt) والتقاط الشاشة (`MediaProjection`).
   - يعمل نموذجان على المعالج العصبي دون خروج أي صورة خارج الهاتف:
     - **نموذج NudeNet v3:** مدرب على شبكة YOLOv8 عبر ONNX Runtime لكشف العورات بدقة تصنيف تصل إلى 18 فئة.
     - **نموذج Halalify v2:** مدرب على تصنيف وكشف النساء عبر TensorFlow Lite.
   - يعالج التقطيع وسرعة الحركة عبر خوارزمية التتبع البصري **KLT Optical Flow** (`KltOpticalFlowTracker.kt`).
   - بمجرد رصد عورة، يستدعي [`ScreenBlurOverlayManager.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/ai/overlay/ScreenBlurOverlayManager.kt) لرسم مستطيل تظليمي داكن فوري غير قابل للنقر (`FLAG_NOT_TOUCHABLE`) فوق المنطقة المكشوفة فقط دون إيقاف التطبيق أو حجب بقية الشاشة.

### 3.5 منظومة الحماية الذاتية ومنع التحايل (Anti-Tampering & Zero-Polling)
- **قانون انعدام الاستطلاع (Zero-Polling Law):**  
  وفقاً للمادة 8 من الدستور، لا توجد في التطبيق أي حلقة فحص سريعة تستهلك البطارية (مثل فحص `UsageStatsManager` كل 250ms). المنظومة تعتمد على أحداث النظام الأصلية:
  1. `dnsObserver`: مراقب محتوى (`ContentObserver`) يرصد أي تغيير في إعدادات النطاق الخاص لحظياً ويعيده لوضعه فوراً.
  2. `NetworkWatchdog`: رصد تبديل شبكات الاتصال والـ Wi-Fi.
  3. `packageReceiver`: رصد لحظي لتثبيت أي تطبيق جديد عبر `ACTION_PACKAGE_ADDED`.
  4. `screenReceiver`: رصد فتح الشاشة لعمل فحص سريع خفيف.
  5. نبضة قلب دورية هادئة كل **15 دقيقة** كصمام أمان دون استهلاك لطاقة المعالج.
- **الوقت الموثوق غير القابل للتقديم أو الإرجاع (`TrustedTimeProvider.kt`):**  
  تعتمد منظومة الحصن على ساعة أحادية الاتجاه (Monotonic Clock) تستند إلى `SystemClock.elapsedRealtime()` مقترنة بأختام زمنية ذرية عبر خوادم الـ SNTP. تقديم ساعة الهاتف من إعدادات أندرويد لا يؤثر إطلاقاً على موعد انتهاء حظر التجوال أو وضع القيلولة، مع تطبيق خوارزمية صارمة لمنع التراجع الزمني (Anti-Rollback).

---

# 4. المميزات الحالية المكتملة (Implemented Features)

تنقسم مميزات التطبيق المكتملة والجاهزة برمجياً إلى شقين أساسيين:

### 4.1 مميزات المنع والحجب والتحكم بالهاتف (Enforcement & Restriction Features)

| الميزة | الوظيفة البرمجية | الكلاسات المسؤولة |
|---|---|---|
| **الإنفاذ التلقائي للـ DNS المشفر** | فرض NextDNS أو AdGuard Family مشفر عبر DoT مع قفل الإعدادات. | `DnsShield.kt`, `DnsWatchdogService.kt` |
| **بالوعة حجب الـ DNS للمنفذ 53** | اعتراض وتصفية استعلامات الـ UDP للـ DNS محلياً في 0ms ورد `NXDOMAIN`. | `CurfewBlackholeVpnService.kt`, `DnsPacketParser.kt` |
| **قفل المتصفحات والبحث الآمن** | منع التصفح الخفي، حظر الدخول كضيف، تفعيل البحث الآمن وقوائم الروابط. | `BrowserShield.kt`, `BrowserUrlFilterActivity.kt` |
| **الاستئصال التلقائي للتطبيقات المحظورة** | تجميد وإخفاء تيليجرام وفروعه، تيك توك، المتصفحات غير المنضبطة، وشات التعارف. | `AppSuspensionShield.kt`, `AppPolicyRegistry.kt` |
| **معمارية الثقة الصفرية بالتطبيقات (Zero-Trust)** | تجميد فوري في 0ms لأي تطبيق جديد يثبته المستخدم حتى موافقة المشرف. | `ApprovalRepository.kt`, `PackageChangeReceiver.kt` |
| **حظر التجوال الليلي (Scheduled Night Curfew)** | حظر التطبيقات الترفيهية والمتاجر وعزل الإنترنت ليلاً (من 8:00م إلى 7:00ص افتراضياً). | `CurfewScheduler.kt`, `CurfewEnforcer.kt`, `CurfewBlackholeVpnService.kt` |
| **قفل إعدادات النظام ومكافحة التلاعب** | منع ضبط المصنع، مسح البيانات، الوضع الآمن، وتبديل المستخدمين، وقفل الساعة. | `SystemTamperShield.kt`, `TrustedTimeProvider.kt` |
| **التعتيم الذكي بالذكاء الاصطناعي (AI Screen Blur)** | فحص الشاشة وتظليل العورات فورياً بنماذج ONNX و TFLite وتتبع KLT البصري. | `ScreenAiProjectionService.kt`, `ScreenBlurOverlayManager.kt`, `NudeNetDetector.kt` |
| **التحديث الهوائي الصامت (Silent OTA Updates)** | تنزيل وتثبيت تحديثات التطبيق تلقائياً دون الحاجة لضغط زر أو تأكيد المستخدم. | `OtaPackageInstaller.kt`, `OtaRepository.kt`, `OtaInstallReceiver.kt` |
| **معالج الترقية اللاسلكية لمسؤول الجهاز** | ترقية الهاتف لصلاحية Device Owner من داخل الجهاز عبر Wireless ADB و SPAKE2. | `DeviceOwnerPromoter.kt`, `ProvisioningWizardActivity.kt` |

### 4.2 مميزات التعافي والدعم الإيماني والمسؤولية (Recovery & Accountability Features)

| الميزة | الوظيفة البرمجية | الكلاسات المسؤولة |
|---|---|---|
| **وضع القيلولة الطارئ (1-Hour Nap Mode)** | زر طوارئ على الشاشة الرئيسية يغلق المشتتات والإنترنت لمدة 60 دقيقة بلا تراجع للتعافي وللصلاة والراحة. | `NapController.kt`, `NapConfig.kt`, `HomeFragment.kt` |
| **نظام رفيق الدرب والإشراف (Accountability & Guardian)** | ربط الجهاز بمشرف/رفيق عبر كود اقتران من 6 أحرف أو رمز QR دون الحاجة لمعرفة تقنية. | `AccountabilityRepository.kt`, `AccountabilityFragment.kt` |
| **لوحة تحكم المشرف عن بعد (Guardian Remote Control)** | تمكين المشرف من تعديل أوقات الحظر، مراجعة التطبيقات، والموافقة على التثبيت من هاتفه. | `GuardianControlActivity.kt`, `GuardianApprovalsViewBinder.kt` |
| **إدارة التطبيقات المعلقة عن بعد (Remote App Approvals)** | إشعار فوري للمشرف لفك تجميد أو رفض أي تطبيق جديد يطلبه المكفول. | `ApprovalRepository.kt`, `AntiPornFirebaseMessagingService.kt` |
| **مركز الإشعارات والتنبيهات الداخلي (Notification Center)** | سجل داخلي دائم لكافة أحداث الحماية، ومحاولات التلاعب، وتنبيهات الأمان داخل التطبيق. | `AppNotificationManager.kt`, `NotificationRepository.kt`, `InAppNotification.kt` |
| **بوابة رمز المرور والباركود للتحكم الذاتي (Self-Control Gate)** | حماية قسم الإعدادات بقفل مشفر أو باركود ورقي مطبوع لمنع إلغاء الحماية لحظة الضعف. | `SelfControlAccessGate.kt`, `SelfControlQrManager.kt`, `QrExportDialog.kt` |
| **الصندوق الأسود وسجل التشخيص الجنائي (Black Box Telemetry)** | تسجيل دقيق لكافة محاولات التلاعب وفشل الأوامر وتغييرات السياسة في SQLite. | `BlackBoxRecorder.kt`, `DiagnosticLogViewerActivity.kt`, `SafeDpmGateway.kt` |

---

# 5. تدفق البيانات وتخزينها (Data Flow & Storage)

### 5.1 التخزين المحلي (Local Storage)
يعتمد التطبيق على قاعدة بيانات مدمجة عالية الكفاءة [`AntiPornDatabase.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/data/local/db/AntiPornDatabase.kt) مبنية بواسطة `SQLiteOpenHelper`:
- **وضع التشغيل:** تفعيل مسبق لـ Write-Ahead Logging (`WAL Mode`) مع تفعيل القيود التلقائية للمفاتيح الخارجية (`Foreign Keys`).
- **أمان الاستعلامات:** استعلامات برمجية مجزأة 100% (Parameterized Queries) لمنع ثغرات الـ SQL Injection تماماً.
- **الجداول الأساسية (Database Schema - Version 4):**
  1. `device_policies`: تخزين كائن السياسة الموحد بصيغة JSON مع رقم الإصدار وتاريخ التحديث.
  2. `partners`: تخزين بيانات رفقاء الدرب (المعرف، كود الاقتران، الاسم، الدور: `GUARDIAN` أو `WARD`، وتاريخ آخر مزامنة).
  3. `pending_approvals`: تسجيل التطبيقات المعلقة في نظام الثقة الصفرية، وقت الطلب، حالة الموافقة، وحالة المزامنة السحابية `is_synced`.
  4. `diagnostic_events`: سجل الصندوق الأسود (Tag, Action, Execution Time, Error Details, Metadata) بحجم حلقي محكوم (Ring Buffer يتسع حتى 5000 حدث مع مسح الأحداث الأقدم من 30 يوماً).
  5. `in_app_notifications`: مركز الإشعارات الداخلي مع مفتاح منع التكرار `dedup_key` ومستوى الأهمية.

بالإضافة إلى **SharedPreferences** مخصصة لبيانات الحالة السريعة:
- `antiporn_trusted_time_prefs`: حفظ نقاط ارتكاز الوقت الموثوق وأرقام تشغيل الهاتف (Boot Count).
- `nap_prefs`: تخزين أوقات بداية ونهاية وضع القيلولة وساعات الارتكاز المزدوجة.
- `antiporn_accountability_prefs`: حفظ معرف المستخدم الدائم `KEY_PERSISTED_UID` وكود الاقتران المحلي.

### 5.2 التكامل السحابي والمزامنة (Cloud Architecture & Offline-First)
- **العمل دون اتصال بالإنترنت (Offline-First Parity):**  
  التطبيق يعمل بنسبة 100% دون اتصال بالإنترنت. كافة القيود ودروع الأمان والحظر الليلي ووضع القيلولة والذكاء الاصطناعي تعمل محلياً بالكامل.
- **المزامنة السحابية (Firebase Cloud Sync):**  
  عند توفر الإنترنت، تتم المزامنة تلقائياً وبأقل استهلاك للموارد:
  - **المصادقة:** تسجيل دخول مجهول تلقائي عبر Firebase Anonymous Auth، مع إمكانية الترقية التلقائية لحساب Google دائم عبر `GoogleAuthManager`.
  - **مجموعات Firestore المستخدمة:**
    - `/pairings/{pairingCode}`: مستند نشر رمز الاقتران المكون من 6 خانات لربط الأجهزة.
    - `/policies/{uid}`: مستند السياسات الموحد المربوط بمعرف الجهاز، مع مراقبة حية عبر `addSnapshotListener`.
    - `/approvals/{uid}/requests/{packageName}`: مسار رفع طلبات موافقة التطبيقات للمشرف.
    - `/heartbeats/{uid}`: إرسال نبضة حياة سحابية دورية للمشرف (كل 12 ساعة).
    - `/inventory/{uid}`: مزامنة قائمة التطبيقات المثبتة لتمكين المشرف من التحكم بها عن بعد.
  - **الرسائل الصامتة عالية الأولوية (FCM High-Priority Messages):**  
    تستخدم لإيقاظ الجهاز وتحديث السياسات أو تطبيق الموافقة فورياً دون انتظار دورة المزامنة العادية.

---

# 6. تحليل ملف AndroidManifest.xml

تمت مراجعة ملف [`app/src/main/AndroidManifest.xml`](file:///d:/b/antiporn/app/src/main/AndroidManifest.xml) بدقة، وتفصيل كافة مكوناته كالتالي:

### 6.1 الصلاحيات (Permissions)
- `INTERNET` و `ACCESS_NETWORK_STATE` و `ACCESS_WIFI_STATE`: للاتصال بالإنترنت، ومزامنة السياسات، ومراقبة تغير الشبكات.
- `RECEIVE_BOOT_COMPLETED`: إعادة تشغيل خدمات الحراسة وجدولة حظر التجوال فور إقلاع الهاتف.
- `PACKAGE_USAGE_STATS` (محمية): مساعدة في مراقبة نشاط التطبيقات في الواجهة كخط دفاع إضافي.
- `SYSTEM_ALERT_WINDOW`: لرسم نوافذ التعتيم الموضعي للذكاء الاصطناعي فوق الشاشة (`ScreenBlurOverlayManager`).
- `WRITE_SECURE_SETTINGS` (صلاحية نظام تمنح تلقائياً للـ Device Owner): تعديل إعدادات الـ Private DNS وساعة النظام على مستوى الـ Global Settings.
- `FOREGROUND_SERVICE` و `FOREGROUND_SERVICE_SPECIAL_USE`: لتشغيل خدمات الحراسة الدائمة `DnsWatchdogService` وخدمة الـ VPN.
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: لتشغيل خدمة التقاط الشاشة وتحليلها بالذكاء الاصطناعي `ScreenAiProjectionService`.
- `POST_NOTIFICATIONS`: لإظهار إشعارات الخدمات الأمامية والتنبيهات الأمنية (أندرويد 13+).
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: لضمان استمرار الحراسة ومنع النظام من قتل التطبيق في الخلفية.
- `REQUEST_DELETE_PACKAGES` و `REQUEST_INSTALL_PACKAGES` و `UPDATE_PACKAGES_WITHOUT_USER_ACTION`: لتنفيذ التحديثات الهوائية الصامتة عبر PackageInstaller.
- `SCHEDULE_EXACT_ALARM` و `USE_EXACT_ALARM`: لضبط منبهات حظر التجوال الليلي بدقة الثانية دون تأخير من النظام.
- `QUERY_ALL_PACKAGES`: لفحص كافة التطبيقات المثبتة على الهاتف واكتشاف التطبيقات الإباحية وتطبيقات تيليجرام وتجميدها.
- `CAMERA`: لمسح أكواد QR الخاصة بربط المشرفين أو قفل التحكم الذاتي.
- `VIBRATE`: لإصدار اهتزاز تنبيهي لحظي عند رصد عورة على الشاشة.

### 6.2 المكونات المسجلة (Activities, Services, Receivers)
1. **الأنشطة (Activities):**
   - `MainActivity`: الشاشة الرئيسية (`launchMode="singleTask"`).
   - `EnterpriseProvisioningActivity`: استقبال أوامر إعداد المؤسسات عبر باركود أندرويد الرسمي.
   - `GuardianControlActivity`: لوحة تحكم المشرف عن بعد بالمكفولين.
   - `RemoteAppManagerActivity`: إدارة قائمة تطبيقات المكفول وحظرها عن بعد.
   - `BrowserUrlFilterActivity`: واجهة إدارة عناوين الروابط المسموحة والمحظورة.
   - `DiagnosticLogViewerActivity`: واجهة عرض وتصدير سجلات الصندوق الأسود الجنائية.
   - `ProvisioningWizardActivity`: معالج الترقية اللاسلكية الذاتية للـ Device Owner خطوة بخطوة.
2. **المستقبلات (BroadcastReceivers):**
   - `AdminReceiver`: مستقبل صلاحيات مسؤول الجهاز الأساسي (`android.permission.BIND_DEVICE_ADMIN`).
   - `PackageChangeReceiver`: مستقبل أحداث إضافة وإزالة التطبيقات لتنفيذ التجميد الفوري في 0ms.
   - `CurfewAlarmReceiver`: مستقبل منبهات حظر التجوال، والقيلولة، وتغير المنطقة الزمنية، وإعادة تشغيل الهاتف.
   - `NapNotificationReceiver`: مستقبل النقر على إشعار تمديد وضع القيلولة (+60 دقيقة).
   - `PairingCodeReceiver`: التقاط رمز اقتران تصحيح الأخطاء اللاسلكي من شريط إشعارات أندرويد.
   - `OtaInstallReceiver`: استقبال نتائج وتأكيد اكتمال التحديثات الهوائية.
3. **الخدمات (Services):**
   - `DnsWatchdogService`: خدمة حراسة أمامية (`specialUse`) لمراقبة خوادم الـ DNS ونشاط الشبكة وفتح الشاشة.
   - `CurfewBlackholeVpnService`: خدمة VPN أمامية (`specialUse`) لحجب الـ DNS نهاراً وحجب الإنترنت كلياً ليلاً.
   - `ScreenAiProjectionService`: خدمة ذكاء اصطناعي أمامية (`mediaProjection`) لالتقاط وتعتيم الشاشة.
   - `AntiPornFirebaseMessagingService`: خدمة استقبال الرسائل السحابية الصامتة من Firebase.
   - `AdbPairingNotificationService`: خدمة مساعدة في التقاط أكواد الـ ADB لتهيئة مسؤول الجهاز.
4. **المزودات (Content Providers):**
   - `androidx.core.content.FileProvider`: لمشاركة ملفات السجلات والتقارير وتحديثات APK داخلياً بأمان.

---

# 7. الديون التقنية والمشاكل المحتملة (Technical Debt & Known Limitations)

بصفتنا فاحصاً معمارياً دقيقاً للكود (Senior Android Architect)، نحدد النقاط التالية التي تتطلب انتباهاً وإعادة هيكلة (Refactoring) أثناء التطوير المستقبلي:

### 7.1 تجاوز حد أسطر الكود للملفات الكبيرة (File Length Violations)
ينص دستور المشروع (المادة 3) على ألا يتجاوز أي ملف كودي 400 سطر برمجي، إلا أن هناك عدة كلاسات مركزية ضخمة تحتاج إلى تجزئة:
1. [`AntiPornDatabase.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/data/local/db/AntiPornDatabase.kt) (1123 سطر): يجمع تعريف الجداول، والترقيات، وعمليات الـ CRUD لكافة الجداول في ملف واحد.  
   *الحل الموصى به:* تجزئة العمليات إلى كلاسات DAO منفصلة (`PolicyDao`, `PartnerDao`, `ApprovalDao`, `DiagnosticDao`) أو الترقية لمكتبة Room.
2. [`AccountabilityRepository.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/data/repository/AccountabilityRepository.kt) (1049 سطر): يجمع إدارة الحسابات المجهولة، وتوليد أكواد الاقتران، وإدارة رفقاء الدرب، والمزامنة السحابية.  
   *الحل الموصى به:* استخراج إدارة الاقتران إلى `PairingCodeManager` وإدارة التزامن إلى `AccountabilitySyncCoordinator`.
3. [`ScreenAiProjectionService.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/ai/service/ScreenAiProjectionService.kt) (953 سطر): يجمع دورة حياة MediaProjection، وإدارة الـ ImageReader، وتدفق التتبع البصري KLT، واستدعاءات النماذج، والتنبيهات.  
   *الحل الموصى به:* استخراج إدارة التقاط الصور إلى `ScreenCapturePipeline` ومحرك التشغيل إلى `AiInferenceCoordinator`.
4. [`ApprovalRepository.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/data/repository/ApprovalRepository.kt) (922 سطر) و [`PolicyRepository.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/data/repository/PolicyRepository.kt) (768 سطر) و [`ScreenBlurOverlayManager.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/ai/overlay/ScreenBlurOverlayManager.kt) (698 سطر).

### 7.2 النمط الهجين لحقن التبعيات (Transitional DI State)
- بالرغم من اعتماد Koin في [`AppModule.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/di/AppModule.kt)، لا تزال العديد من الكلاسات تطبق نمط الـ Singleton التقليدي عبر `getInstance(context)` ويقوم Koin بلفها (`single { PolicyRepository.getInstance(androidContext()) }`).
- *التوصية:* استكمال الترقية إما بحقن نظيف عبر Koin بالاعتماد الكامل على المنشئ constructor injection، أو الترقية المعيارية إلى **Google Hilt** كما تنص المادة 4 من الدستور، والتخلص نهائياً من دوال `getInstance()`.

### 7.3 استهلاك البطارية والحرارة في منظومة فحص الشاشة (AI Pipeline Thermal Budget)
- تشغيل التقاط الشاشة عبر `ImageReader` بمعدلات إطارات عالية مع تمرير مصفوفات بكسل ضخمة واستدعاء نموذجي NudeNet (ONNX) و Halalify (TFLite) يولد عبئاً حسابياً وحرارياً ملحوظاً، خصوصاً على الأجهزة المتوسطة (مثل معالجات Helio G90T / Snapdragon 600-series).
- *نقاط القوة الحالية:* تم التخفيف من ذلك بنجاح عبر كلاس [`FrameDifferencer.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/ai/core/pipeline/FrameDifferencer.kt) الذي يتخطى معالجة الإطارات الثابتة، واستثناء تطبيقات النظام النظيفة عبر [`AiExemptionRegistry.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/ai/core/policy/AiExemptionRegistry.kt).
- *التوصية:* إضافة خافض ديناميكي لمعدل الإطارات (Dynamic Throttle) يخفض معدل الفحص إلى إطارين في الثانية عند انخفاض شحن البطارية دون 20% أو عند ارتفاع حرارة الجهاز.

### 7.4 قيد الـ VPN الفردي في نظام أندرويد (Single VPN Slot Limitation)
- نظام أندرويد لا يسمح بتشغيل أكثر من خدمة VPN واحدة في نفس اللحظة.
- تطبيقنا يستخدم [`CurfewBlackholeVpnService.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/curfew/CurfewBlackholeVpnService.kt) كدرع بالوعة محلي للـ DNS أو كعازل شبكي ليلي. إذا رغب المستخدم في تشغيل VPN حقيقي لعمله أو لدراسته، سيحدث تعارض، ولذلك يمنع التطبيق حالياً إعداد أي VPN خارجي عبر `UserManager.DISALLOW_CONFIG_VPN`.
- *التوصية:* الاعتماد الأساسي على DoT (Private DNS) نهاراً لترك قناة الـ VPN شاغرة، وعدم استدعاء خدمة الـ VPN إلا أثناء حظر التجوال الليلي أو عند فشل DoT في الأجهزة التي تمنع كتابة النطاق الخاص.

### 7.5 قيود المتصفحات غير المبنية على محرك كروم (Gecko / Non-Chromium Browsers)
- درع المتصفحات [`BrowserShield.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/shields/BrowserShield.kt) يعتمد على `setApplicationRestrictions` وهي حزم مدعومة فقط في المتصفحات المبنية على Chromium (Chrome, Edge, Samsung Browser, Brave).
- المتصفحات الأخرى مثل Firefox (محرك Gecko) ومتصفح Tor لا تستجيب لسياسات `IncognitoModeAvailability` المؤسسية من أندرويد.
- *الحل المطبق حالياً في المشروع:* يقوم درع [`AppSuspensionShield.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/shields/AppSuspensionShield.kt) بإدراج عائلة فايرفوكس وتور وكافة المتصفحات البديلة ضمن القائمة السوداء الدائمة (`PROXY_BROWSERS`) وتجميدها فورياً عند التثبيت لحماية المستخدم من هذه الثغرة.

---

> 📝 **خلاصة جاهزية المشروع للمرحلة القادمة:**  
> يمتلك مشروع «حِصْن» أساساً هندسياً فائق القوة كـ Device Owner أصلي على أندرويد، محصناً ضد الإلغاء، والتلاعب، وتغيير الوقت، والوضع الآمن، مع تغطية شاملة للحجب عبر الـ DNS والـ VPN وسياسات كروم والذكاء الاصطناعي على الجهاز.  
> يعد هذا الملف `HISN_CONTEXT.md` وثيقة الأساس التي تلخص الحالة الحقيقية الدقيقة لكافة جوانب الكود لنقلها والاعتماد عليها في التخطيط المعماري القادم.
