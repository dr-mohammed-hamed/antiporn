# وثائق مشروع AntiPorn (AntiPorn Documentation)

مرحباً بك في مجلد التوثيق الهندسي والمعماري لمشروع **AntiPorn**.  
يحتوي هذا المجلد على كافة الأدلة المعمارية والمخططات التوجيهية للأنظمة والدروع الأمنية.

---

## الفهرس العام للوثائق

| الوثيقة | الوصف | الفئة المستهدفة |
| :--- | :--- | :--- |
| 📘 **[سجل سياسات التطبيقات (APP_POLICY_REGISTRY.md)](file:///d:/b/antiporn/doc/APP_POLICY_REGISTRY.md)** | الدليل المعماري الشامل لإدارة وحظر واستثناء التطبيقات (SSOT)، مع شجرة اتخاذ القرار ودليل إضافة التطبيقات بسطر واحد. | المطورون ومدراء المشاريع |

---

### تعليمات سريعة للمطورين:
* **هل تريد إضافة أو استثناء تطبيق جديد؟**  
  افتح [`AppPolicyRegistry.kt`](file:///d:/b/antiporn/app/src/main/java/com/drmohammed/antiporn/core/policy/AppPolicyRegistry.kt) وأضف سطراً واحداً فقط. راجع [دليل المطور](file:///d:/b/antiporn/doc/APP_POLICY_REGISTRY.md#7-دليل-المطور-لإضافة-وتعديل-التطبيقات-developer-how-to-guide) لمزيد من التفاصيل.
