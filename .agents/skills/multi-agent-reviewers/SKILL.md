---
name: multi-agent-reviewers
description: Multi-pass adversarial code review, automated bug-hunting, surgical fixing, and constitution auditing loop powered by specialized subagents with full parent permissions and adaptive tree delegation. Use when user says "multi-agent-reviewers", "multi agent review", "راجع الكود عبر الوكلاء", "فحص متعدد الوكلاء", or after completing a feature to ensure 0 critical/major issues and constitution compliance before commit.
---

# Multi-Agent Reviewers (نظام المراجعة والتدقيق متعدد الوكلاء الهرمي)

## 📌 نظرة عامة والمعمارية

تقوم هذه المهارة بتحويل الوكيل الرئيسي إلى **قائد ومدير عام (Leader Agent)** يدير دورة مراجعة مغلقة وشجرة هرمية من الوكلاء الفرعيين المزودين بـ **كامل صلاحيات الوكيل الأم** (`enable_write_tools: true`, `enable_subagent_tools: true`, `enable_mcp_tools: true`).

تعتمد المهارة مبدأ **التفريع الشجري الذكي المشروط (Adaptive Tree Delegation)** لموازنة العمق والكفاءة في استهلاك التوكنز:
1. **Subagent 1: الفاحص التشكيكي العام (Lead Adversarial Auditor)** — يدير الفحص التشكيكي الصارم للفلاتر الستة ويفرع وكلاء فرعيين للمستوى الثاني في المهام الكبيرة.
2. **Subagent 2: المصلح الجراحي (Surgical Fixer)** — يمتلك صلاحيات الكتابة الحصرية لتطبيق الإصلاحات الدقيقة وفق الدستور دون المساس بباقي الكود.
3. **Subagent 3: المدقق الدستوري (Constitution Auditor)** — يتحقق إجبارياً من التوافق التام مع ميثاق المشروع (`constitution.md`).

---

## 🌳 مخطط سير العمل الشجري والدائري المغلق (Adaptive Gauntlet Loop)

```mermaid
flowchart TD
    A[طلب المراجعة / انتهاء التعديل] --> B{هل التعديل متعدد الطبقات أو كبير؟}
    
    B -- نعم: تفريع شجري هرمي -- --> D1[Lead Auditor: يفرع 3 وكلاء متخصصين بالتوازي Tier 2]
    D1 --> SubDB[Child 1A: فاحص SQL والـ Outbox وقواعد البيانات]
    D1 --> SubState[Child 1B: فاحص التزامن و Riverpod والنقر المزدوج]
    D1 --> SubUI[Child 1C: فاحص توافقية Windows Keyboard & Mobile]
    SubDB & SubState & SubUI --> D1Report[تجميع التقرير التشكيكي الشامل]
    
    B -- لا: تعديل بسيط أو محدد -- --> D2[Lead Auditor: فحص تشكيكي مباشر بعمق 1]
    
    D1Report & D2 --> E{هل توجد أخطاء منطقية؟}
    E -- نعم --> F{هل تجاوزنا 3 جولات؟}
    F -- نعم --> G[تدخل القائد المباشر لكسر الحلقة Circuit Breaker]
    F -- لا --> H[إطلاق Subagent 2: المصلح الجراحي Targeted Fix]
    H --> B
    
    E -- 0 أخطاء --> I[إطلاق Subagent 3: المدقق الدستوري الإجباري]
    I --> J{هل يوجد تعارض دستوري؟}
    J -- نعم --> F
    J -- 100% متطابق --> K[فحص القائد المستقل: flutter analyze & flutter test]
    K --> L[توليد التقرير التنفيذي Artifact وطلب إذن الـ Commit]
```

---

## ⚙️ إعداد الصلاحيات الإلزامي لجميع الوكلاء

عند استدعاء أو تعريف أي وكيل فرعي، يتم تمرير كامل حزمة الصلاحيات:
```json
{
  "enable_write_tools": true,
  "enable_subagent_tools": true,
  "enable_mcp_tools": true
}
```
*(أو استخدام استنساخ `self` للوكيل الأم).*

---

## 📋 مراحل التنفيذ التفصيلية

### المرحلة 0: فحص البيئة والبوابة المسبقة (Pre-flight Gate)
- التحقق من توافر أداة `invoke_subagent` و `define_subagent`.
- فحص حجم وتعقيد التعديلات (`git status` و `git diff --stat`) لتحديد وضع التفريع (شجري هرمي أم مباشر).

---

### المرحلة 1: إطلاق الفاحص التشكيكي (Lead Adversarial Auditor)
يطلق الوكيل الرئيسي الوكيل الأول بكامل الصلاحيات وبحقن الـ Prompt الموجود في:
📄 [references/adversarial-auditor-prompt.md](references/adversarial-auditor-prompt.md).

#### 🔀 التفريع الشجري المشروط للمستوى الثاني (Tier 2 Child Subagents):
إذا كانت التعديلات تشمل **أكثر من طبقتين** (مثلاً: جداول DB + مزامنة Outbox + واجهات)، يقوم `Lead Auditor` تلقائياً بتفريع 3 وكلاء فرعيين متخصصين بالتوازي:
1. **Child 1A (DB & Persistence Auditor)**:
   - فحص استعلامات SQL، المعاملات الذرية `db.transaction`، وحسابات القروش (Cents).
   - فحص سلامة تعديل الجداول (`ALTER TABLE ADD COLUMN`) وتجنب الـ `NOT NULL` بدون قيمة افتراضية.
2. **Child 1B (Concurrency & State Auditor)**:
   - حماية النقر المزدوج (`_isSaving` و `finally { if (mounted) setState... }`).
   - ذرية Outbox، ثبات الحالة `copyWith`، واستخدام `ref.read` داخل معالجات الأحداث.
   - التحقق من إبطال الكاش لكافة الـ Providers المتأثرة (`ref.invalidate`).
3. **Child 1C (Cross-Platform & Async Context Auditor)**:
   - فحص أمان السياق (`if (!mounted) return;`) قبل استخدام `BuildContext` بعد أي `await`.
   - دعم لوحة المفاتيح والـ Desktop (`FocusNode`, `onFieldSubmitted`, shortcuts) وتوافق الشاشات للموبايل دون `RenderFlex overflow`.

*في التعديلات البسيطة (ملف واحد أو تعديل موضعي)، ينفذ `Lead Auditor` الفحص مباشرة دون تفريع وكلاء إضافيين لتوفير التوكنز والوقت.*

---

### المرحلة 2: الإصلاح الجراحي وإعادة الفحص (Refinement Loop)
- **مبدأ الكاتب المنفرد (Single-Writer Safety)**: وكلاء التدقيق يقرؤون ويحللون فقط؛ بينما التعديل والكتابة محصورة في **Subagent 2 (Surgical Fixer)** لمنع أي تعارض في الملفات.
- يطلق **Subagent 2 (Surgical Fixer)** باستخدام:
  📄 [references/surgical-fixer-prompt.md](references/surgical-fixer-prompt.md).
- **إعادة الفحص الإلزامية**: أي تعديل يُجريه المصلح يُعاد فحصه فوراً عبر **Lead Auditor** لضمان عدم توليد أي عيب جانبي.
- **قاطع الدورة (Circuit Breaker)**: حد أقصى 3 جولات تدقيق وإصلاح. إذا تكرر نفس الخطأ يتدخل الوكيل الرئيسي مباشرة.

---

### المرحلة 3: التدقيق الدستوري والدورة المغلقة (Constitution Closed Loop)
- فور وصول الفاحص إلى **0 أخطاء**، يُطلق **Subagent 3 (Constitution Auditor)** باستخدام:
  📄 [references/constitution-auditor-prompt.md](references/constitution-auditor-prompt.md).
- إذا وجد أي مخالفة للدستور (`constitution.md`)، تعود الدورة للمصلح الجراحي ثم يُعاد الفحص التشكيكي والدستوري حتى تحقيق:
  **(0 أخطاء منطقية + 100% توافق دستوري)**.

---

### المرحلة 4: التحقق التجريبي للقائد وبوابة الـ Commit (Commit Gate)
1. يقوم الوكيل الرئيسي بنفسه بتشغيل:
   - `flutter analyze` (المستهدف: 0 أخطاء و 0 تحذيرات).
   - `flutter test` (المستهدف: 100% نجاح لكافة الاختبارات).
2. إنشاء ملف التقرير التنفيذي كـ **Artifact** باسم `audit_executive_report.md` يحتوي على:
   - الشجرة المنفذة للمراجعة والمشاكل المكتشفة.
   - الحل الجراحي المطبق بالدليل الإثباتي.
   - مصفوفة نتائج الاختبارات.
3. التوقف التام وعرض رسالة في الشات تطلب من المستخدم صراحة:
   > *"تم الانتهاء من التدقيق والإصلاح الدستوري بنجاح 100%. هل تأذن لي بعمل Commit للتغييرات؟"*
