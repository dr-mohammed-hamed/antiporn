# الدليل الهندسي المرجعي: ترقية نمط مالك الملف (Profile Owner Hybrid) لمضاهاة مالك الجهاز (Device Owner)

> **وثيقة مرجعية استراتيجية لمشروع:** منظومة الثَّغْر — درع عِصْمَة (`AntiPorn`)  
> **التاريخ:** أكتوبر 2026  
> **الهدف:** توفير حماية سيادية بنسبة 95% من صلابة Device Owner دون إجبار المستخدم على حذف حساب Google أو إعادة ضبط المصنع.

---

## 1. الرؤية الهندسية والمعمارية (Architectural Paradigm)

في بيئة أندرويد القياسية:
* **مالك الجهاز (Device Owner - DO):** هو السلطة المطلقة على عتاد الهاتف وكافة المستخدمين، ولكنه يفرض حاجزاً قاسياً لتجربة المستخدم؛ إذ يتطلب مسح كافة حسابات Google من الهاتف أو عمل ضبط مصنع كامل لتفعيله.
* **مالك الملف التقليدي (Profile Owner - PO):** مخصص لملفات العمل (Work Profile)، وعادة ما يكون معزولاً في بيئة ثانوية (User 10) يسهل على المستخدم الالتفاف عليها بفتح الملف الشخصي العادي أو حذف ملف العمل بالكامل.

### الابتكار في مشروعنا: النمط الهجين (PO-Hybrid)
تحويل `Profile Owner` إلى **سلطة نظام شبه مطلقة** عبر دمج ثلاثة أركان برمجية متزامنة:
```
                     ┌──────────────────────────────────────────────┐
                     │          نمط مالك الملف الهجين                │
                     │           (Profile Owner Hybrid)             │
                     └──────────────────────┬───────────────────────┘
                                            │
        ┌───────────────────────────────────┼───────────────────────────────────┐
        │                                   │                                   │
┌───────▼──────────────┐        ┌───────────▼──────────┐        ┌───────────────▼───────┐
│     الركن الأول       │        │     الركن الثاني      │        │     الركن الثالث       │
│  التعيين المباشر     │        │  صلاحية الإعدادات    │        │  الحراسة الذاتية الحية │
│ على المستخدم الرئيسي │        │        الآمنة        │        │   DnsWatchdogService  │
│       (User 0)       │        │ WRITE_SECURE_SETTINGS│        │ (نبض كل 60ث + شاشة)   │
└──────────────────────┘        └──────────────────────┘        └───────────────────────┘
```

---

## 2. الثالوث السيادي (The 3 Pillar Foundation)

### الركن الأول: التعيين على المستخدم الرئيسي (`User 0`)
بدلاً من إنشاء ملف عمل ثانوي معزول، يتم حقن التطبيق كـ Profile Owner للمستخدم الرئيسي:
```bash
adb shell dpm set-profile-owner com.drmohammed.antiporn/.AdminReceiver
```
* **الأثر:** تصبح سلطة التطبيق سارية على الواجهة الرئيسية للهاتف (`Launcher 0`)، وبالتالي فإن أوامر تجميد التطبيقات (`setPackagesSuspended`) وحظر الحذف (`setUninstallBlocked`) تسري على تطبيقات المستخدم الأساسية مباشرة.

### الركن الثاني: جسر النظام (`WRITE_SECURE_SETTINGS`)
يمنح التطبيق حق الوصول المباشر إلى جدول إعدادات أندرويد العالمية (`Settings.Global`):
```bash
adb shell pm grant com.drmohammed.antiporn android.permission.WRITE_SECURE_SETTINGS
```
* **الأثر:** يكسر التطبيق حاجز الحرمان الذي تفرضه دالة `dpm.setGlobalSetting` على Profile Owner، ويستطيع حقن إعدادات الـ DNS، وإلغاء المستخدم الضيف، وقفل مزامنة الوقت برمجياً.

### الركن الثالث: الحراسة الذاتية الدائمة (`DnsWatchdogService`)
خدمة تعمل في الواجهة الأمامية (`Foreground Service - specialUse`) مرتبطة بـ:
1. مراقب تغيير الإعدادات (`ContentObserver` على `private_dns_mode`).
2. مراقب فتح الشاشة (`BroadcastReceiver` على `ACTION_USER_PRESENT` و `ACTION_SCREEN_ON`).
3. مراقب تثبيت الحزم (`ACTION_PACKAGE_ADDED`).
4. فحص دوري كل 60 ثانية (Heartbeat).

---

## 3. مصفوفة الثغرات وآليات سدها في نمط PO-Hybrid

### الثغرة 1: الالتفاف على خادم الـ DNS وتغييره
* **المشكلة الأصلية في PO:** دالة `dpm.setGlobalPrivateDnsModeSpecifiedHost` محصورة برمجياً لـ Device Owner فقط.
* **آلية السد في PO-Hybrid:**
  1. يقوم كود `DnsShield.kt` بالكتابة المباشرة عبر `ContentResolver`:
     ```kotlin
     Settings.Global.putString(context.contentResolver, "private_dns_mode", "hostname")
     Settings.Global.putString(context.contentResolver, "private_dns_specifier", targetHost)
     ```
  2. يقوم التطبيق بقفل وتجميد إمكانية تعديل الـ DNS في الإعدادات عبر:
     ```kotlin
     dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
     ```

---

### الثغرة 2: إنشاء مستخدم ضيف (Guest User) للهروب من القيود
* **المشكلة الأصلية في PO:** المستخدم يستطيع سحب شريط الإشعارات وإنشاء حساب ضيف غير خاضع للتطبيق.
* **آلية السد في PO-Hybrid:**
  1. تطبيق قيد حظر إنشاء مستخدمين في DPM:
     ```kotlin
     dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
     ```
  2. إخفاء وتعطيل مفتاح التبديل كلياً من واجهة النظام عبر `WRITE_SECURE_SETTINGS`:
     ```kotlin
     Settings.Global.putInt(context.contentResolver, "user_switcher_enabled", 0)
     Settings.Global.putInt(context.contentResolver, "guest_user_enabled", 0)
     ```

---

### الثغرة 3: تشغيل شبكات VPN خارجية لكسر تصفية DNS
* **آلية السد في PO-Hybrid:**
  صلاحية `Profile Owner` تدعم رسمياً قيد حظر الـ VPN الخارجي، ويتم تطبيقه في `VpnShield.kt`:
  ```kotlin
  dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
  ```
  * **النتيجة:** يعجز أندرويد عن بدء أو تشغيل أي اتصال VPN لأي تطبيق آخر في الجهاز.

---

### الثغرة 4: حذف ملف الإدارة أو إلغاء الصلاحية
* **المشكلة الأصلية في PO:** قد يدخل المستخدم إلى `الإعدادات -> الحسابات -> حذف ملف الإدارة`.
* **آلية السد في PO-Hybrid:**
  1. منع حذف الحزمة عبر DPM:
     ```kotlin
     dpm.setUninstallBlocked(admin, context.packageName, true)
     ```
  2. منع تعديل الحسابات وملفات الإدارة:
     ```kotlin
     dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
     ```
  3. الرقابة البصرية اللحظية عبر `ContextualGatingWatcher`: طرد المستخدم فوراً للشاشة الرئيسية إذا حاول دخول شاشات إدارة الحسابات الحساسة.

---

### الثغرة 5: التلاعب بساعة الهاتف لكسر الحظر الليلي (Curfew Bypass)
* **المشكلة الأصلية في PO:** قيد `DISALLOW_CONFIG_DATE_TIME` محصور لـ Device Owner.
* **آلية السد في PO-Hybrid:**
  1. إجبار الهاتف على مزامنة الوقت الذرية مع أبراج الشبكة والإنترنت (NTP):
     ```kotlin
     Settings.Global.putInt(context.contentResolver, Settings.Global.AUTO_TIME, 1)
     Settings.Global.putInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 0)
     ```
  2. يقوم `DnsWatchdogService` بفحص متغير `AUTO_TIME` دورياً؛ وإذا تم إطفاؤه يدوياً يعيد تشغيله بالملي ثانية.
  3. ضبط الحظر الليلي على مستوى إنذار المعالج العتادي (`AlarmManager.RTC_WAKEUP`).

---

### الثغرة 6: قتل الخدمة بالخلفية عبر توفير الطاقة للشركات المصنعة (OEM Doze)
* **آلية السد في PO-Hybrid:**
  إعفاء التطبيق من القيود العتادية للطاقة عبر ADB أثناء التثبيت:
  ```bash
  adb shell dumpsys deviceidle whitelist +com.drmohammed.antiporn
  ```

---

## 4. سكربت التثبيت الكامل لموقع التثبيت السحابي (WebADB Payload)

هذا هو السكربت الكامل المعتمد لتنفيذه في موقع التثبيت (`WebUSB/WebADB`) بنقرة زر واحدة:

```bash
# 1. تثبيت الحزمة مع منح كافة أذونات التشغيل
adb install -r -g app-release.apk

# 2. ترقية التطبيق لـ Profile Owner على المستخدم الرئيسي مباشرة (بدون مسح حسابات!)
adb shell dpm set-profile-owner com.drmohammed.antiporn/.AdminReceiver

# 3. منح الصلاحيات السيادية المتقدمة
adb shell pm grant com.drmohammed.antiporn android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.drmohammed.antiporn GET_USAGE_STATS allow
adb shell appops set com.drmohammed.antiporn SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.drmohammed.antiporn

# 4. الحقن الفوري لإعدادات الأمان في قلب النظام
adb shell settings put global private_dns_mode hostname
adb shell settings put global private_dns_specifier family.cloudflare-dns.com
adb shell settings put global user_switcher_enabled 0
adb shell settings put global guest_user_enabled 0
adb shell settings put global auto_time 1
adb shell settings put global auto_time_zone 0

# 5. بث إشارة تنشيط الدروع وتشغيل التطبيق
adb shell am broadcast -a com.drmohammed.antiporn.ENFORCE_POLICIES -n com.drmohammed.antiporn/.AdminReceiver
adb shell am start -n com.drmohammed.antiporn/.MainActivity
```

---

## 5. مصفوفة المقارنة المرجعية النهائية

| المعيار الهندسي | Profile Owner التقليدي | **Profile Owner الهجين (PO-Hybrid)** | Device Owner الكامل |
| :--- | :---: | :---: | :---: |
| **سهولة التثبيت** | سهلة |  **فائقة السهولة (بدون مسح حسابات)** | معقدة (تشترط مسح الحسابات) |
| **منع حذف التطبيق** | داخل الملف فقط |  **على الهاتف كاملاً** |  على الهاتف كاملاً |
| **تجميد التطبيقات الخبيثة** | داخل الملف فقط |  **على الهاتف كاملاً** |  على الهاتف كاملاً |
| **قفل خادم الـ DNS المشفر** | غير متاح |  **مقفل 100% بنظام التشغيل** |  مقفل 100% بنظام التشغيل |
| **حظر شبكات الـ VPN البديلة** |  مدعوم |  **مدعوم 100%** |  مدعوم 100% |
| **حظر المستخدم الضيف وتعدد المستخدمين** | ثغرة مفتوحة |  **مغلق ومخفي تماماً** |  مغلق بنظام التشغيل |
| **عزل الحظر الليلي (Blackhole VPN)** |  مدعوم |  **مدعوم 100%** |  مدعوم 100% |
| **قفل شاشة ضبط المصنع في الإعدادات** | غير متاح |  يعتمد على حماية FRP بحساب المشرف |  مدعوم برمجياً |
| **قفل الوضع الآمن (Safe Boot)** | غير متاح |  مراقب ذاتياً بالـ Watchdog |  مدعوم برمجياً |
| **الصلابة الدفاعية التراكمية** | **40%** | **95%** | **100%** |

---

## 6. إرشادات الصيانة والتطوير المستقبلي

1. **الالتزام بقاعدة فحص السيادة قبل الكتابة (Check-Before-Write):**  
   في أي درع مستقبلي، يجب فحص نوع الصلاحية:
   ```kotlin
   val isDo = dpm.isDeviceOwnerApp(context.packageName)
   if (isDo) {
       // استخدام واجهات DPM السيادية الخاصة بـ Device Owner
   } else {
       // استخدام واجهات Settings.Global المدعومة بـ WRITE_SECURE_SETTINGS
   }
   ```
2. **عدم حظر أدوات المطور (Strict Constitutional Rule):**  
   تجنب تطبيق `DISALLOW_DEBUGGING_FEATURES` لتظل إمكانية المراقبة والتصحيح متاحة للمشروع والمطور في كافة الأوقات.
3. **الحفاظ على سلامة الـ Watchdog:**  
   يجب ألا تتوقف خدمة `DnsWatchdogService`؛ فهي صمام الأمان الفعلي الذي يعيد فرض قيم الـ Hybrid PO كل 60 ثانية.
