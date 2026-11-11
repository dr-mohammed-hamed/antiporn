# 📡 دليل منظومة التحديثات الهوائية المستقلة (Independent OTA via Cloudflare R2)

يعتمد مشروع **«حِصْن» (Hisn)** على آلية تحديث هوائية مستقلة ومباشرة (Over-The-Air) خارج متجر Google Play. يهدف هذا التوثيق إلى شرح كيفية عمل المنظومة، وكيف يمكن لأي مطور أو جهة تقوم بعمل Fork للمشروع استخدامها أو استبدالها بسهولة.

---

## 🏗️ 1. كيف تعمل منظومة التحديث الحالية؟

1. **الخادم السحابي (Cloudflare R2):**
   - تُستخدم خدمة Cloudflare R2 (المتوافقة مع S3 وذات تكلفة خروج بيانات صفرية Egress Fees) لاستضافة ملفين أساسيين:
     - `antiporn-release.apk`: حزمة التطبيق الموقعة والمجهزة للتثبيت.
     - `version.json`: ملف البيانات الوصفية (Metadata) الذي يحتوي على رقم الإصدار، رابط التحميل، بصمة التوقيع، وتفاصيل التحديث.

2. **التثبيت الصامت بصلاحية Device Owner:**
   - يمتلك التطبيق صلاحية مسؤول الجهاز المالك (Device Owner)، مما يسمح له بتنزيل التحديث وتثبيته في الخلفية عبر واجهة `PackageInstaller` في أندرويد دون الحاجة لتفاعل المستخدم.

3. **شجرة دوران التوقيع (Lineage Key Rotation):**
   - يدعم التطبيق معيار APK Signature Scheme v2/v3 Lineage Rotation لضمان قبول التحديثات التراكمية بأمان بين المفاتيح المتعاقبة.

---

## ⚙️ 2. للمطورين: كيف تخصص مسار التحديثات لبيئتك الخاصة؟

تم تجريد كافة الروابط الحساسة وعزلها عن الكود العام المرفوع على GitHub. لتشغيل التحديثات لحسابك:

### الخطوة 1: ضبط ملف `local.properties`
أضف المتغيرات التالية إلى ملف `local.properties` في جذر المشروع (الملف محمي ومستبعد تلقائياً من Git):

```properties
# عنوان التوزيع العام لحاوية Cloudflare R2 الخاصة بك
ota.base.url=https://your-custom-bucket.r2.dev

# اسم الحاوية في حساب Cloudflare
r2.bucket.name=your-releases-bucket

# كلمة سر مفتاح التوقيع الإنتاجي (اختياري، لتشغيل سكربت النشر الآلي دون مطالبة)
release.keystore.password=YourSecurePassword
```

### الخطوة 2: النشر التلقائي عبر السكربتات
عند تشغيل السكربت:
```powershell
.\scripts\publish_new_release.ps1 -NewVersionCode 19 -NewVersionName "1.80" -ReleaseNotes "تحسينات عامة"
```
سيقوم السكربت تلقائياً بـ:
1. بناء الـ APK وإعادة توقيعه عبر مفتاح التدوير.
2. تحديث `version.json` محلياً وحقن الرابط الفعلي من `local.properties` أثناء الرفع إلى R2.
3. رفع الملفات إلى حاوية R2 المحددة عبر أداة `wrangler`.

---

## 🔄 3. كيفية التحويل إلى مسارات بديلة (GitHub Releases أو خادم خاص)

إذا كنت ترغب في التخلي عن Cloudflare R2 واستخدام مسار آخر:

### أ. التحويل إلى GitHub Releases:
1. في ملف [`app/src/main/java/com/drmohammed/antiporn/ota/remote/OtaRemoteSource.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/ota/remote/OtaRemoteSource.kt):
   - يمكنك تعديل `fetchLatestVersion()` لتقرأ من واجهة GitHub API:
     `https://api.github.com/repos/YOUR_USER/YOUR_REPO/releases/latest`
2. في الـ Assets المرفوعة على Release في GitHub، قم بتضمين ملف الـ APK وملف `version.json`.

### ب. التحويل إلى خادم ويب خاص (Nginx / Apache / S3):
- يكفي فقط تغيير قيمة `ota.base.url` في `local.properties` (أو تمرير المتغير البيئي `OTA_BASE_URL` أثناء البناء) ليشير إلى خادمك:
  ```properties
  ota.base.url=https://updates.yourdomain.com
  ```
- سيربط محرك التطبيق تلقائياً بين هذا العنوان وملف `version.json`.
