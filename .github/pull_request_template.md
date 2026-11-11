## 📝 وصف التعديل (Summary of Changes)
<!-- صف بإيجاز ما الذي يفعله هذا التعديل، والمشكلة التي يعالجها، وقيمته للمستخدم -->

---

## 🎯 نوع التعديل (Type of Change)
- [ ] 🐛 تصحيح خطأ برمجى (Bug fix)
- [ ] ✨ ميزة جديدة (New feature)
- [ ] ⚡ تحسين في الأداء أو استهلاك البطارية (Performance improvement)
- [ ] ♻️ إعادة هيكلة دون تغيير السلوك الخارجي (Refactoring)
- [ ] 📚 تحديث أو تحسين التوثيق (Documentation update)

---

## 🛡️ قائمة التحقق والامتثال للدستور (Constitution Compliance Checklist)
- [ ] التزمت بـ [دستور المشروع (PROJECT_CONSTITUTION.md)](PROJECT_CONSTITUTION.md) بنسبة 100%.
- [ ] لم أستخدم `!!` (Force-unwrap) نهائياً في أي سطر كود جديد أو معدل.
- [ ] لم أقم بإدراج أي أسرار، مفاتيح تشفير، أو ملفات بيئات شخصية (`*.jks`, `local.properties`).
- [ ] تأكدت أن كافة النصوص معزولة في `strings.xml` ومترجمة في `values-en/strings.xml`.
- [ ] قمت بتشغيل الاختبارات المؤتمتة محلياً ونجحت بنسبة 100%: `./gradlew testDebugUnitTest`.
- [ ] لم يتم كسر التوافق مع صلاحية مسؤول الجهاز (Device Owner) أو إغلاق منفذ تصحيح أخطاء المطور (ADB).
