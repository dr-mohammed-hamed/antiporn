# 🛠️ أدوات وأدلة الأتمتة المساعدة (Hisn Automation & Deployment Hub)

يحتوي هذا المجلد على مجموعة السكربتات المساعدة لإدارة دورة حياة المشروع، والتوقيع الرقمي للشهادات، والنشر السحابي الهوائي، والنسخ الاحتياطي.

---

> [!NOTE]  
> ### 🪟 تنبيه بيئة التشغيل (Windows PowerShell Environment Notice)
> **كافة سكربتات الأتمتة والنشر والتوقيع المرفقة في هذا المجلد مطورة ومختبرة لتعمل أصلياً في بيئة نظام ويندوز (Windows PowerShell).**  
> * إذا كنت تعمل على نظام **macOS** أو **Linux (Ubuntu)**، يمكنك تشغيل هذه السكربتات مباشرة عبر تثبيت [PowerShell Core (`pwsh`)](https://github.com/PowerShell/PowerShell)، أو تنفيذ المهام المكافئة لها باستخدام أدوات Gradle الرسمية وأدوات Android SDK Platform-Tools مباشرة.

---

## 🧭 الفهرس العام للسكربتات والوظائف

| السكربت | لغة التشغيل | الوصف والوظيفة |
| :--- | :---: | :--- |
| **`publish_new_release.ps1`** | PowerShell | السكربت الشامل الكل-في-واحد لبناء إصدار إنتاجي جديد: يقوم بتحديث رقم الإصدار، بناء الـ APK عبر Gradle، توقيعه بشجرة التدوير، ورفعه تلقائياً إلى Cloudflare R2. |
| **`deploy_r2_distribution.ps1`** | PowerShell | رفع حزمة التطبيق الموقعة وبيانات الإصدار الوصفية (`version.json`) إلى حاوية Cloudflare R2 المستقلة عبر أداة Wrangler. |
| **`sign_rotated_release.ps1`** | PowerShell | توقيع حزمة الـ APK باستخدام معيار التوقيع v2/v3 Lineage Rotation لربط المفتاح الجديد بالمفتاح القديم وضمان الترقية الآمنة. |
| **`create_backup.bat`** | Windows Batch | مشغل نقرة واحدة سريع للمطورين على ويندوز لتشغيل سكربت النسخ الاحتياطي الشامل بسهولة. |
| **`create_backup.ps1`** | PowerShell | إنشاء أرشيف مضغوط كامل ونظيف لكافة ملفات الكود والوثائق داخل مجلد `backups/` مع استبعاد مخلفات البناء المؤقتة. |
| **`generate_qr_payload.ps1`** | PowerShell | توليد حمولة بيانات رمز الاستجابة السريعة (QR Provisioning Payload) لتهيئة أجهزة أندرويد المؤسسية فورياً عبر مسح الكاميرا. |
| **`generate_hisn_assets.py`** | Python 3 | معالجة صورة الشعار الرسمية (`art/hisn_brand_logo.jpg`) وتوليد كافة أحجام الأيقونات التكيفية (`mipmap`) والخلفيات لنظام أندرويد. |

---

### 🚀 أمثلة سريعة للاستخدام:

#### 1. نشر إصدار جديد بنقرة واحدة:
```powershell
.\scripts\publish_new_release.ps1 -NewVersionCode 19 -NewVersionName "1.80" -ReleaseNotes "تحسينات في الأداء وثبات النظام"
```

#### 2. إنشاء نسخة احتياطية محلية سريعة:
```cmd
.\scripts\create_backup.bat
```
أو عبر PowerShell:
```powershell
.\scripts\create_backup.ps1
```
