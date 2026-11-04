# 📜 دستور التطوير الهندسي لمشروع «حِصْن» (AntiPorn DO)
# Project Engineering Constitution & Immutable Laws

> **الإصدار:** 2.0.0 (Production-Grade OSS Standard)  
> **النطاق:** مشروع «حِصْن» وكافة الأنظمة التابعة له بصلاحية مسؤل الجهاز (Device Owner) على أندرويد.  
> **الفئة المستهدفة:** مهندسو البرمجيات والمراجعون ونماذج الذكاء الاصطناعي (AI Coding Agents) العاملة على هذا المستودع.  
> **الإلزامية:** هذا الدستور ملزم تعاقدياً بنسبة 100%. أي خرق لأحد بنوده يُعد سبباً كافياً لرفض الـ Pull Request أو إبطال مخرجات وكيل الذكاء الاصطناعي فوراً.

---

## 🏛️ الباب الأول: المحرمات القطعية وقواعد الأمان (Absolute Security Prohibitions)

### المادة 1: الحظر الصارم لرفع الأسرار والمفاتيح وإعدادات المحررات (Zero Leakage)
* **يُحظر منعاً باتاً ومطلقاً** تتبع أو ارتكاب (Commit) لأي من الملفات التالية داخل مستودع Git:
  1. ملفات مفاتيح التوقيع والشهادات الرقمية (`*.jks`, `*.keystore`, `*.p12`, `*.bin`, `key.properties`).
  2. ملفات إعدادات بيئة التطوير والمحررات (`.idea/`, `*.iml`, `.vscode/`).
  3. ملفات المسارات المحلية والبيئة الشخصية للمطور (`local.properties`).
  4. مخلفات أدوات التجميع والأكواد المؤقتة (`repomix-output*`, `provisioning_payload.json`, `provisioning_qr.png`).
* أي إضافة لهذه الملفات في الـ Git Index توجب التراجع الفوري وتحديث `.gitignore`.

### المادة 2: قدسية بيئة المطور وتصحيح أخطاء USB (Developer ADB Sanctuary)
* **يُمنع منعاً باتاً ومطلقاً** تحت أي مبرر برمجي أو رغبة في التضييق تطبيق القيود التالية:
  - `UserManager.DISALLOW_DEBUGGING_FEATURES`
  - `UserManager.DISALLOW_CONFIG_DEVELOPER_OPTIONS`
* سطر أوامر **ADB عبر الحاسوب** هو شريان الحياة الإلزامي للمطور للتصحيح، وتثبيت التحديثات، وإلغاء القفل. تفعيل هذه القيود يُعد خطأً أمنياً فادحاً يؤدي لعزل المطور نهائياً عن هاتفه.

---

## 🏛️ الباب الثاني: القواعد المعمارية وفصل المسؤوليات (Clean Architecture & DI)

### المادة 3: حظر الكلاسات العملاقة (No God Classes)
* الحد الأقصى لأي ملف كودي هو **400 سطر برمجي**.
* يُحظر تماماً جمع منطق الواجهة (UI)، مع استدعاءات قواعد البيانات، مع نداءات `DevicePolicyManager` في كلاس واحد.
* يجب تجزئة المسؤوليات وفق المعايير المعمارية:
  * واجهة المستخدم (`Fragment` / `Activity`): مسؤولة فقط عن عرض الحالة (`UiState`) وإرسال الأحداث (`UiIntent`).
  * طبقة العرض (`ViewModel`): إدارة تدفق البيانات والحالة عبر `StateFlow`.
  * طبقة الأعمال (`Domain UseCases`): تنفيذ قواعد الحظر والحماية بشكل معزول.
  * طبقة البيانات (`Repository`): إدارة التخزين المحلي والمزامنة السحابية فقط دون معرفة بتفاصيل الـ UI أو النظام.

### المادة 4: حقن التبعيات وحظر الـ Singletons المتصلبة
* بعد اكتمال تهيئة **Google Hilt**:
  * **يُحظر نهائياً** استخدام نمط `getInstance(context)` أو `AntiPornDatabase.getInstance()` أو `PolicyRepository.getInstance()`.
  * كافة التبعيات تُحقن بصورة صريحة عبر المُنشئ (`@Inject constructor(...)`).
* يُمنع تسريب سياق الأنشطة (`Activity Context`) في الـ Singletons أو المهام الخلفية؛ الاعتماد حصراً على `@ApplicationContext`.

### المادة 5: بوابة الـ DPM المركزية (Safe Gateway Isolation)
* **يُحظر منعاً باتاً** على أي كلاس في طبقة الواجهة (UI) أو مستودعات البيانات (Repositories) مناداة `DevicePolicyManager` مباشرة.
* جميع تفاعلات إدارة الأجهزة المؤسسية يجب أن تمر حصرياً عبر:
  1. كلاسات الحماية المعزولة المستقلة (`ProtectionShield`).
  2. بوابة الإدارة الآمنة [`SafeDpmGateway`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/telemetry/SafeDpmGateway.kt).

---

## 🏛️ الباب الثالث: لغة Kotlin والأسلوب الاصطلاحي (Idiomatic Kotlin)

### المادة 6: التحريم المطلق لاستخدام Force-Unwrap (`!!`)
* **يُحظر استخدام `!!` في أي موضع داخل المشروع دون أي استثناء.**
* التعامل مع القيم المحتمل فراغها (`Nullable`) يتم حصراً عبر:
  * التحقق الذكي: `val data = snapshot.data ?: return`
  * التحويل الآمن: `let { ... }` أو `takeIf { ... }`
  * توفير قيم افتراضية: `?: fallbackValue`
  * رمي استثناءات مخصصة ومفسرة عند الاستحالة: `?: throw IllegalStateException("...")`

### المادة 7: قدسية المسار الرئيسي والانضباط التزامني (Main Thread & Coroutines)
* **يُحظر تنفيذ أي عملية ثقيلة على مسار الواجهة (Main Thread)** بما في ذلك:
  * استعلامات أو كتابة في قاعدة بيانات SQLite.
  * قراءة أو كتابة على أقراص التخزين أو SharedPreferences.
  * إرسال طلبات مزامنة شبكية (SNTP أو Firestore).
  * عمليات التشفير وحساب الـ Hashes.
* **إدارة النطاقات (Coroutine Scopes):**
  * يُمنع إنشاء نطاقات عشوائية غير مدارة داخل الدوال: `CoroutineScope(Dispatchers.IO).launch`.
  * داخل الـ UI والـ ViewModels: استخدم `viewModelScope` أو `lifecycleScope`.
  * داخل المستودعات والخدمات: استخدم نطاق التطبيق الموحد المحقون `@ApplicationScope`.
  * داخل الـ BroadcastReceivers: استخدم `goAsync()` مع ضمان اكتمال `pendingResult.finish()` داخل كتلة `finally`.

---

## 🏛️ الباب الرابع: قواعد إدارة الجهاز والبطارية (Device Owner & Battery Hygiene)

### المادة 8: حظر حلقات الاستطلاع الدورية (Zero-Polling Law)
* نظراً لأن التطبيق يمتلك صلاحية **Device Owner** الكاملة:
  * **يُحظر تماماً** استخدام حلقات الفحص السريعة المجدولة (مثل فحص `UsageStatsManager` كل 250ms أو 300ms) لمراقبة المستخدم وطرده للشاشة الرئيسية.
  * البديل المعياري الإلزامي هو الاستفادة من إمكانيات نظام أندرويد الأصلية:
    1. تعليق التطبيقات غير المصرح بها فورياً عبر `dpm.setPackagesSuspended(admin, packages, true)`.
    2. إخفاء الحزم عبر `dpm.setApplicationHidden(admin, packageName, true)`.
    3. قفل تنزيل التطبيقات أثناء حظر التجوال عبر `dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)`.
* أي مهام دورية في الخلفية يجب ألا تنزل عن حد الـ 15 دقيقة وتدار حصراً عبر **WorkManager**.

---

## 🏛️ الباب الخامس: جودة الكود وبصمات الذكاء الاصطناعي (Code Smells & AI Artifacts)

### المادة 9: نظافة التوثيق والتعليقات
* يُحظر تماماً تضمين:
  1. أي إشارات لمحادثات الشات، الأوامر السابقة، أو أدوات المراجعة الآلية (مثل: `/grill-me`, `/goal`, `CodeRabbit`, `ChatGPT`, `Claude`).
  2. تعليقات بالعامية أو نصوص غير احترافية في الكود المصدري.
  3. تعليقات ميتة أو بديهية (مثل: `// this is a function to do X`).
  4. كود معطل داخل تعليقات (Commented-out Dead Code). الكود غير المستخدم يُحذف فوراً ويعتمد على تاريخ Git لاستعادته.

### المادة 10: حظر الطباعة العشوائية واعتماد Timber
* **يُمنع منعاً باتاً** استخدام:
  * `System.err.println()` أو `println()` في أي مكان في المشروع.
  * نداءات `Log.d` أو `Log.v` أو `Log.i` المباشرة ذات النصوص العشوائية أو الإيموجيات في كود الإنتاج.
* **البديل الإلزامي:**
  * استخدام مكتبة **Timber** حصراً: `Timber.d(...)`, `Timber.e(e, ...)`.
  * زراعة شجرة التسجيل (`Timber.plant(DebugTree())`) في بيئة التطوير (Debug Build) فقط، مع كتم كافة السجلات الحساسة في بيئة الإصدار النهائي (Release Build).

### المادة 11: عزل النصوص وتعدد اللغات
* **يُحظر وضع نصوص المستخدمين بشكل مباشر (Hardcoded Strings)** في الواجهات أو كود كوتلين.
* كافة النصوص والرسائل والتنبيهات يجب أن تكون معزولة في [`res/values/strings.xml`](file:///d:/b/antiporn/app/src/main/res/values/strings.xml) مع توفير الترجمة الإنجليزية المقابلة في [`res/values-en/strings.xml`](file:///d:/b/antiporn/app/src/main/res/values-en/strings.xml).

---

## 🏛️ الباب السادس: بوابة الاعتماد والإثبات الواقعي (Empirical Proof Gate)

### المادة 12: معايير قبول الكود (Definition of Done)
لا يُعتبر أي كود مكتملاً ومقبولاً في المستودع إلا بتحقيق الشروط التالية تباعاً:
1. اكتمال التجميع بنجاح: `.\gradlew.bat compileDebugSources` بدون أي أخطاء.
2. نجاح فحص الجودة المعياري: `.\gradlew.bat lintDebug` بصفر أخطاء (`0 errors`).
3. نجاح كافة الاختبارات الوحدوية: `.\gradlew.bat testDebugUnitTest` بنسبة 100%.
4. خلو التغييرات من أي تراجع وظيفي (Regression) في الميزات الحيوية (NextDNS, VPN, Curfew, Zero-Trust).
