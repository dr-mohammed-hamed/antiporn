# 📜 دستور هندسة كوتلين وأندرويد لتطبيقات الحماية الذاتية
# Kotlin & Android Device Owner Engineering Constitution

> **الإصدار:** 1.0.0  
> **النطاق:** مشروع AntiPorn وكافة أنظمة الحماية الذاتية بصلاحية Device Owner على أندرويد.  
> **الفئة المستهدفة:** مطورو البرمجيات ونماذج الذكاء الاصطناعي (AI Coding Agents) العاملة على هذا المستودع.  
> **الإلزامية:** هذا الدستور ملزم تعاقدياً بنسبة 100%، ولا يجوز لأي وكيل ذكاء اصطناعي تجاوزه تحت أي ظرف.

---

## 🏛️ الباب الأول: المبادئ الحاكمة غير القابلة للتفاوض (Immutable Core Laws)

### 🔴 المادة 1: قدسية بيئة المطور وتصحيح أخطاء USB (Developer ADB Preservation)
> [!CAUTION]
> **قاعدة أمنية قطعية:**
> * صاحب الهاتف يعمل في **مجال تطوير وبرمجة تطبيقات الأجهزة الذكية**.
> * **يُمنع منعاً باتاً ومطلقاً** تحت أي مبرر تطبيق القيود التالية:
>   - `UserManager.DISALLOW_DEBUGGING_FEATURES`
>   - `UserManager.DISALLOW_CONFIG_DEVELOPER_OPTIONS`
> * أي نموذج ذكاء اصطناعي يقترح أو يكتب كوداً يفعّل هذين القيدين يُعتبر مرتكباً لخطأ أمني فادح يؤدي لعزل المطور وحبسه خارج هاتفه.
> * سطر أوامر **ADB عبر الحاسوب** هو المنفذ الوحيد المعتمد لإلغاء القفل، تصحيح الأخطاء، أو حذف التطبيق.

### 🔴 المادة 2: الحماية على مستوى نواة الشبكة (System & Network Stack Level)
* لا يجوز الاعتماد على حلول واجهات المستخدم السطحية (مثل مراقبة العناوين في المتصفحات عبر Accessibility Service) كبديل لدرع الشبكة.
* الحماية الأساسية تكون دائماً مشفرة وعميقة عبر:
  1. فرض **NextDNS over TLS** على مستوى النظام (`DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost`).
  2. قفل واجهة الـ DNS في الإعدادات (`DISALLOW_CONFIG_PRIVATE_DNS`).
  3. حظر بروتوكولات الـ VPN بالكامل (`DISALLOW_CONFIG_VPN`).

### 🔴 المادة 3: منع الالتفاف السلوكي والنفسي الشامل (Zero-Bypass Architecture)
* لا تقتصر الحماية على الـ DNS؛ بل يجب إغلاق كافة المنافذ البديلة التي يلجأ إليها العقل في لحظات الضعف:
  * **الوضع الآمن (Safe Mode):** محظور إجبارياً عبر `DISALLOW_SAFE_BOOT`.
  * **المستخدمون المتعددون والضيوف:** محظور إجبارياً عبر `DISALLOW_ADD_USER` و `DISALLOW_USER_SWITCH`.
  * **مساحات استنساخ النظام (System Cloner / Work Profiles):** محظورة عبر `DISALLOW_ADD_MANAGED_PROFILE`.
  * **وضع التصفح المتخفي (Incognito Mode):** معطل إجبارياً على مستوى المتصفحات عبر `setApplicationRestrictions`.
  * **إلغاء التثبيت وفورمات الجهاز:** محظور عبر `setUninstallBlocked` و `DISALLOW_FACTORY_RESET`.

### 🔴 المادة 4: التجريب والإثبات الواقعي قبل إعلان النجاح (Empirical Proof Gate)
* يُحظر على أي نموذج ذكاء اصطناعي إعلان نجاح المهمة بناءً على الافتراض النظري.
* إعلان النجاح يتطلب حصراً:
  1. `.\gradlew.bat compileDebugSources` بدون أي خطأ.
  2. `.\gradlew.bat lintDebug` بصفر أخطاء (`0 errors`).
  3. اختبار مباشر على الجهاز المتصل والتحقق من سجلات `Logcat` الحية.

---

## ⚠️ الباب الثاني: كتالوج أخطاء نماذج الذكاء الاصطناعي في Kotlin & Android
**(AI Hallucination & Failure Catalog)**

هذا القسم يوثق أخطر الأخطاء الشائعة التي تقع فيها نماذج الذكاء الاصطناعي (مثل GPT و Claude و Gemini) عند كتابة كود Kotlin وأندرويد، مع بيان المعيار الإلزامي لتفاديها:

### 1. فخ الأنواع المنصية وانعدام التحقق من الفراغ (Platform Types & NPE Traps)
* **المشكلة:** دوال أندرويد SDK الأصلية مكتوبة بلغة Java. عندما تستدعيها بلغة Kotlin، يعامل المترجم النتيجة كـ `Platform Type` (تظهر كـ `Type!`). تفترض نماذج AI خطأً أن القيمة غير فارغة وتستدعي الدوال عليها مباشرة (مثل `restrictions.getInt(...)`)، وعندما ترجع الدالة `null` في بعض الأجهزة أو الإصدارات ينهار التطبيق فوراً بـ `NullPointerException`.
* **المعيار الإلزامي:**  
  يجب معاملة أي قيمة مرجعة من دوال نظام أندرويد (الـ Framework APIs) كقيمة اختيارية قابلة للفراغ (`Nullable - Type?`) واستخدام عوامل الأمان التلقائية في كوتلين (`?.` و `?:`) وتطويقها بـ `try-catch` مخصص.
  ```kotlin
  // ❌ خطأ شائع من نماذج الـ AI (يسبب NPE):
  val bundle = dpm.getApplicationRestrictions(admin, pkg)
  val mode = bundle.getInt("IncognitoModeAvailability")

  // ✅ المعيار الصارم المعتمد:
  val bundle: Bundle? = try { dpm.getApplicationRestrictions(admin, pkg) } catch (e: Exception) { null }
  val isLocked = bundle?.getInt("IncognitoModeAvailability", 0) == 1
  ```

---

### 2. تسريب الذاكرة وسياق التطبيق (Context Leaks)
* **المشكلة:** تقوم نماذج AI بتمرير سياق الشاشة (`Activity Context`) إلى كائنات وحيدة ممتدة الأجل (Singletons / `object`) مثل `DnsManager`، أو تخزين مراجع الـ Views في خيوط خلفية، مما يمنع Garbage Collector من تنظيف الشاشات عند تدوير الهاتف أو إغلاقه ويؤدي لنفاد ذاكرة الرام (`OutOfMemoryError`).
* **المعيار الإلزامي:**  
  أي كائن ممتد الأجل أو `object` أو دالة خلفية يجب أن تستخدم حصراً سياق التطبيق العام:
  ```kotlin
  // ❌ خطأ AI:
  fun applyPolicies(context: Context) { this.savedContext = context }

  // ✅ المعيار الصارم:
  fun applyPolicies(context: Context) {
      val appContext = context.applicationContext
      // استخدام appContext حصراً
  }
  ```

---

### 3. إسقاط الاستدعاءات المتزامنة وتجميد الأزرار (Callback Dropping & UI Freeze)
* **المشكلة:** لمنع التكرار، تضع نماذج الـ AI قفل فحص ساذج مثل `if (!isEnforcing.compareAndSet(false, true)) return`. وإذا ضغط المستخدم الزر أثناء تنفيذ خلفي سابق، يتم تجاهل طلبه وإسقاط دالة الرد (`onComplete`) دون استدعائها. ولأن الزر في الواجهة يتم تعطيله (`btn.isEnabled = false`) انتظاراً للرد؛ فإن إسقاط الـ Callback يجعل الزر معطلاً للأبد!
* **المعيار الإلزامي:**  
  يجب استخدام قائمة انتظار متزامنة آمنة للخيوط (`CopyOnWriteArrayList<(Result) -> Unit>`) تجمع كافة دوال الرد المعلقة، وتقوم بإخطارها جميعاً على خيط الواجهة الرئيسي (`Looper.getMainLooper()`) فور اكتمال التنفيذ لضمان إعادة تفعيل الأزرار دائماً.

---

### 4. حلقة الفحص والتنفيذ اللانهائية مع المراقبين (Observer Feedback Loops)
* **المشكلة:** تقوم نماذج AI بإنشاء مراقب للإعدادات (`ContentObserver`) لمراقبة `private_dns_mode`. وبمجرد حدوث أي تغيير، تقوم الدالة باستدعاء `dpm.setGlobalPrivateDnsModeSpecifiedHost`. ولكن كتابة الإعدادات تُطلق حدث تغيير جديد في الـ Observer، فيعيد المراقب طلب التطبيق، ليدخل التطبيق في حلقة تكرار لانهائية كل نصف ثانية تستنزف البطارية والمعالج!
* **المعيار الإلزامي (Check-Before-Write):**  
  يُحظر منعاً باتاً استدعاء دوال الكتابة المؤسسية إذا كانت القيمة الحالية مطابقة بالفعل للقيمة المستهدفة:
  ```kotlin
  // ✅ فحص الحالة الحالية قبل الكتابة لمنع الحلقة اللانهائية:
  val currentMode = dpm.getGlobalPrivateDnsMode(admin)
  val currentHost = dpm.getGlobalPrivateDnsHost(admin)
  if (currentMode != DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME || currentHost != NEXTDNS_HOST) {
      dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, NEXTDNS_HOST)
  } else {
      // تخطي الكتابة لعدم إثارة الـ Observer
  }
  ```

---

### 5. قيود خدمات الخلفية الصارمة (Android 12 & 14+ FGS Restrictions)
* **المشكلة:** تفشل نماذج AI في مواكبة تطور قيود أندرويد الصارمة على الخدمات الخلفية:
  1. تشغيل `Foreground Service` من الخلفية يسبب انهيار `ForegroundServiceStartNotAllowedException` في أندرويد 12+.
  2. في أندرويد 14+ (API 34)، تشغيل خدمة بدون تصريح نوع الخدمة الصريح داخل المانيفست يسبب انهياراً فورياً.
* **المعيار الإلزامي:**
  1. الاستفادة من استثناءات صفة مسؤول الجهاز (`Device Owner`) المسموح لها بتشغيل الخدمات.
  2. تحديد نوع الخدمة الصريح: `foregroundServiceType="specialUse"` مع إضافة خاصية `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
  3. استدعاء `startForeground()` خلال أقل من 5 ثوانٍ من إنشاء الخدمة.
  4. المنح التلقائي الصامت لإذن الإشعارات `POST_NOTIFICATIONS` بصلاحية Device Owner على أندرويد 13 فما فوق.

---

### 6. ثغرة الإقلاع بدون إنترنت (Boot-without-Network Loophole)
* **المشكلة:** عند استقبال بث إعادة تشغيل الهاتف (`ACTION_BOOT_COMPLETED`)، لا يكون الاتصال بالإنترنت (الواي فاي أو بيانات الهاتف) قد تم ربطه بعد. محاولة فحص خادم الـ DNS عبر TLS ترجع الخطأ `PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING` (1) ولا يتم حفظ الـ DNS. وإذا كان التطبيق يراقب فقط تغييرات الإعدادات، فإن عودة الإنترنت لاحقاً لا تُعيد تطبيق الـ DNS ويبقى الهاتف مكشوفاً!
* **المعيار الإلزامي:**  
  تسجيل مراقب لشبكة الاتصال (`ConnectivityManager.NetworkCallback`) داخل خدمة المراقبة للتحقق التلقائي وإعادة فرض الـ DNS فور توفر الإنترنت.

---

### 7. كتم الأخطاء بصمت (Silent Exception Swallowing)
* **المشكلة:** تلجأ نماذج AI الكسولة لكتابة:
  ```kotlin
  // ❌ كارثة برمجية:
  try { ... } catch (e: Exception) {}
  ```
* **المعيار الإلزامي:**  
  يُمنع منعاً باتاً ترك كتل `catch` فارغة أو إعادة قيم افتراضية زائفة. يجب دائماً تسجيل الخطأ بوضوح عبر `Log.e(TAG, "...", e)` أو إعادته في كائن النتيجة للمستخدم لعرضه في الواجهة.

---

### 8. النصوص المكتوبة يدوياً وتراكم تحذيرات الـ Lint
* **المشكلة:** كتابة النصوص العربية أو الإنجليزية مباشرة داخل ملفات Kotlin أو الـ XML (`HardcodedText`).
* **المعيار الإلزامي:**  
  كافة النصوص بلا استثناء يجب تعريفها داخل `res/values/strings.xml` واستدعاؤها عبر `getString(R.string...)` أو `@string/...` للحفاظ على نظافة المشروع وخلو تقرير الـ Lint من التحذيرات.

---

## 📋 الباب الثالث: مصفوفة المتصفحات وقفل التصفح المتخفي (Browser Hardening Matrix)

لحظر التصفح المتخفي وفرض البحث الآمن، يجب تطبيق حزمة الإعدادات المؤسسية التالية على كافة المتصفحات المستهدفة:

```kotlin
val bundle = Bundle().apply {
    putInt("IncognitoModeAvailability", 1)       // 1 = إلغاء وحظر التصفح المتخفي تماماً
    putBoolean("ForceGoogleSafeSearch", true)     // فرض البحث الآمن في جوجل
    putBoolean("ForceYouTubeSafetyMode", true)   // وضع الأمان الكلاسيكي في يوتيوب
    putInt("ForceYouTubeRestrict", 2)            // وضع الأمان الصارم في يوتيوب (Modern Chromium)
    putInt("SafeSitesFilterBehavior", 1)         // حظر المواقع الإباحية الصريحة في المتصفح
}
```

### قائمة الحزم الإلزامية المستهدفة (Target Packages):
* **Google Chrome:** `com.android.chrome`, `com.chrome.beta`, `com.chrome.dev`, `com.chrome.canary`.
* **Brave Browser:** `com.brave.browser`, `com.brave.browser_beta`, `com.brave.browser_nightly`.
* **Microsoft Edge:** `com.microsoft.emmx`, `com.microsoft.emmx.canary`.
* **Samsung Internet:** `com.sec.android.app.sbrowser`.
* **Opera & Opera Mini:** `com.opera.browser`, `com.opera.mini.native`.
* **Vivaldi:** `com.vivaldi.browser`.

---

## 🛠️ الباب الرابع: بروتوكول إلغاء القفل وحالات الطوارئ (Emergency Protocol)

بما أن التطبيق يمنع إلغاء التثبيت ويمنع الفورمات من داخل الهاتف لمنع أي محاولة للالتفاف، فإن الطريقة الوحيدة والمصرح بها حصرياً لصاحب الجهاز هي عبر سطر أوامر ADB من الحاسوب الشخصي:

```bash
# خطوة 1: إلغاء صلاحية مسؤول الجهاز المالك (Device Owner)
adb shell dpm remove-active-admin --user 0 com.drmohammed.antiporn/.AdminReceiver

# خطوة 2: حذف التطبيق بالكامل
adb uninstall com.drmohammed.antiporn
```

---

## ✅ الباب الخامس: قائمة التحقق الصارمة للـ AI قبل اعتماد أي كود (AI Pre-Commit Gate)

قبل أن يُعلن أي وكيل ذكاء اصطناعي اكتمال أي مهمة في هذا المشروع، يجب أن يجيب بـ "نعم" موثقة على الأسئلة التالية:

1. [ ] هل تأكدت من أن خيارات المطور وتصحيح أخطاء USB لم تُمس ولم تُعطل؟
2. [ ] هل استعلمت عن دوال الـ SDK بحذر مع التحقق من احتمالية رجوع `null`؟
3. [ ] هل تحققت من أن الـ Singletons لا تحتفظ بـ `Activity Context`؟
4. [ ] هل فحصت إعدادات النظام قبل الكتابة لتفادي حلقة الـ Observer اللانهائية؟
5. [ ] هل تم وضع كافة النصوص داخل `strings.xml`؟
6. [ ] هل تم تشغيل `.\gradlew.bat compileDebugSources` بنجاح؟
7. [ ] هل تم تشغيل `.\gradlew.bat lintDebug` وحقق صفر أخطاء؟
8. [ ] هل تم تثبيت التطبيق وتجريبه على الجهاز المتصل فعلياً؟
