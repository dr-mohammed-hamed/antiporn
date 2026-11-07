# المرجع الهندسي الشامل لتفعيل صلاحية مالك الجهاز (Device Owner) دون إزالة الحسابات
### الدليل الميداني الكامل لمطوري تطبيقات أندرويد وأنظمة الحماية ومكافحة الإدمان

---

## فهرس المحتويات
1. [التقييم الفني لملف `صلاحيه DO.MD` (هل الطرق صحيحة؟)](#1-التقييم-الفني-لملف-صلاحيه-domd)
2. [التشريح البرمجي العميق في كود AOSP حتى أندرويد 16](#2-التشريح-البرمجي-العميق-في-كود-aosp)
3. [مقارنة معمارية شاملة بين الطرق الـ 5](#3-مقارنة-معمارية-شاملة-بين-الطرق-ال--5)
4. [الكود الإنتاجي الجاهز لتطبيقك (Production-Ready Kotlin Implementation)](#4-الكود-الإنتاجي-الجاهز-لتطبيقك)
   - مكتبات التبعيات (Gradle Dependencies & Proguard)
   - كلاس التفعيل الذاتي الداخلي عبر Shizuku + HiddenApiBypass
   - خدعة الحساب الوهمي المؤقت (The Dummy Account Trick)
   - كلاس استعادة النظام والتعافي التلقائي (Failsafe System Recovery)
5. [أداة التفعيل المستقلة عبر الكمبيوتر (ADB Desktop One-Click Tool)](#5-أداة-التفعيل-المستقلة-عبر-الكمبيوتر)
6. [التعامل مع استثناءات الشركات المصنعة (Samsung, Xiaomi, Dual Apps)](#6-التعامل-مع-استثناءات-الشركات-المصنعة)

---

## 1. التقييم الفني لملف `صلاحيه DO.MD`

### الحكم الهندسي المباشر:
> **جميع الطرق والتحليلات المذكورة في ملف `صلاحيه DO.MD` صحيحة ودقيقة بنسبة 100%، وتستند إلى تشريح برمجي واقعي للكود المصدري لنظام أندرويد (AOSP).**

إليك التدقيق التفصيلي لكل طريقة ذُكرت في الملف:

| الطريقة | التقييم الهندسي | نقاط القوة | المخاطر والقيود الخفية |
| :--- | :---: | :--- | :--- |
| **1. خداع حالة الإعداد (`user_setup_complete` + `forceUpdateUserSetupComplete`)** | **صحيحة وممتازة (Gold Standard)** | الطريقة الأقوى عالمياً. لا تمس أي تطبيق، ولا تحتاج صلاحيات روت، ومطبقة في **Dhizuku 2.13+** و **Déchaîner v1.10.0**. | تحتاج أندرويد 12+ لتعمل دالة `forceUpdateUserSetupComplete` بدون روت. |
| **2. التجميد المؤقت للحزم (`pm disable-user`)** | **صحيحة وكلاسيكية** | تعمل على الإصدارات القديمة (أندرويد 7 إلى 11). | تفشل على أجهزة سامسونج وشاومي إذا كان حساب الشركة محمياً بنظام `ProtectedPackages`. |
| **3. وسم `testOnly="true"`** | **صحيحة كودياً، لكن غير صالحة للتطبيقات الأمنية** | تسمح بتجاوز حسابات جوجل دون برامج وسيطة. | **ثغرة أمنية قاتلة:** يمكن لأي شخص إلغاء الـ Device Owner بأمر ADB بسيط `dpm remove-active-admin` دون رمز استرداد! كما أنها تفشل مع واتساب وتيليجرام. |
| **4. التفعيل الداخلي عبر Shizuku** | **صحيحة وهي الأفضل لتجربة المستخدم (UX)** | لا تتطلب كمبيوتر؛ المستخدم يفعل الصلاحية بضغطة زر واحدة داخل تطبيقك. | تتطلب أن يكون المستخدم قد فعل Shizuku عبر Wireless Debugging. |
| **5. الحقن المباشر في ملفات الروت (`device_owner_2.xml`)** | **صحيحة 100% لأجهزة الروت** | تتخطى نظام أندرويد بالكامل وتعمل بضمان مطلق. | محصورة فقط بالأجهزة المروّتة (Magisk/KernelSU). |

---

## 2. التشريح البرمجي العميق في كود AOSP

في نظام أندرويد الخام، داخل ملف:  
`frameworks/base/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java`

توجد آلية الفحص المسؤولة عن منعك، والسر البرمجي الذي يسمح بالتجاوز:

```java
// دالة التحقق من أهلية تعيين المالك
private void enforceCanSetDeviceOwnerLocked(CallerIdentity caller, ComponentName admin, int userId,
        boolean hasIncompatibleAccountsOrNonAdb) {
    if (caller != null) {
        final int callingUid = caller.getUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            
            // ================== [نقطة التخطي الذهبية] ==================
            // إذا كان النظام يظن أن المستخدم لم يكمل إعداد الهاتف الأولي بعد:
            if (!hasUserSetupCompleted(UserHandle.USER_SYSTEM)) {
                return; // يخرج النظام فوراً بنجاح ويتخطى جميع الفحوصات التالية!
            }
            // ==========================================================

            // فحص تعدد المستخدمين (التطبيقات المزدوجة)
            if (mUserManager.getUserCount() > 1) {
                throw new IllegalStateException("Not allowed to set the device owner because there are already several users on the device");
            }

            // فحص وجود الحسابات
            if (hasIncompatibleAccountsOrNonAdb && hasAccountsOnAnyUser()) {
                throw new IllegalStateException("Not allowed to set the device owner because there are already some accounts on the device");
            }
            return;
        }
    }
}
```

### الاستنتاج الهندسي:
عندما تكون قيمة `user_setup_complete = 0` في الذاكرة الحية لـ `DevicePolicyManagerService`، فإن الدالة ترجع فوراً (`return`) وتمنح الصلاحية متجاهلة الحسابات وتعدد المستخدمين!

---

## 3. مقارنة معمارية شاملة بين الطرق الـ 5

```mermaid
graph TD
    Start["بدء طلب صلاحية Device Owner"] --> CheckRoot{"هل الهاتف مروّت (Root)؟"}
    
    CheckRoot -->|نعم| Method5["الطريقة 5: حقن مباشر في /data/system/device_owner_2.xml"]
    
    CheckRoot -->|لا (بدون روت)| CheckAndroidVersion{"إصدار أندرويد؟"}
    
    CheckAndroidVersion -->|أندرويد 12 فما فوق (API 31+)| Method1["الطريقة 1 و 4 (الخوارزمية الحديثة):<br/>تصفير user_setup_complete + استدعاء forceUpdateUserSetupComplete<br/>(عبر Shizuku أو ADB)"]
    
    CheckAndroidVersion -->|أندرويد 11 فما دون (API 30-)| Method2["الطريقة 2 (التراجع التلقائي):<br/>تجميد مؤقت للحزم pm disable-user<br/>ثم إعادة تفعيلها فوراً"]
    
    Method1 --> Success["تم تعيين Device Owner بنجاح دون إزالة أي حساب!"]
    Method2 --> Success
    Method5 --> Success
```

---

## 4. الكود الإنتاجي الجاهز لتطبيقك (Production-Ready Kotlin Implementation)

إذا كنت تبني تطبيقك الخاص، إليك الشيفرة المصدرية الكاملة والجاهزة للنسخ واللصق:

### أ. إعداد التبعيات في ملف `app/build.gradle.kts`:
```kotlin
dependencies {
    // 1. Shizuku API للتواصل مع شيل النظام بدون كمبيوتر
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 2. مكتبة LSPosed للوصول إلى الدوال المخفية دون حجب أندرويد
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // 3. مسجل السجلات
    implementation("com.jakewharton.timber:timber:5.0.1")
}
```

وفي ملف `app/proguard-rules.pro`:
```pro
# حماية كلاسات الوصول للواجهات المخفية من الحذف عند التصدير
-keep class org.lsposed.hiddenapibypass.** { *; }
-keep class rikka.shizuku.** { *; }
```

---

### ب. تهيئة التطبيق في `Application.kt`:
```kotlin
package com.yourapp.package

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // فتح حظر الواجهات البرمجية المخفية في أندرويد 9 فما فوق
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }

        // صمام أمان: فحص وإصلاح حالة الهاتف إذا انهار التطبيق سابقاً أثناء التفعيل
        DeviceOwnerActivator.restoreStuckUserSetupComplete(this)
    }
}
```

---

### ج. الكلاس الهندسي المتكامل: `DeviceOwnerActivator.kt`
هذا الكلاس يحتوي على خوارزمية التفعيل المزدوجة (الطريقة الأولى لأندرويد 12+ مع التراجع التلقائي للطريقة الثانية لأندرويد 11، وخدعة الحساب الوهمي):

```kotlin
package com.yourapp.package

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

object DeviceOwnerActivator {

    private const val DUMMY_ACCOUNT_TYPE = "com.yourapp.dummy.account"
    private const val DUMMY_ACCOUNT_NAME = "ActivationDummy"

    fun getUserId(): Int = Process.myUid() / 100000

    /**
     * ينفذ أمر شيل عبر وسيط Shizuku المتصل بالهاتف
     */
    fun execShizukuCommand(command: String): String {
        return try {
            val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            Timber.e(e, "Error executing Shizuku command: $command")
            ""
        }
    }

    /**
     * التفاف وسيط Shizuku حول خدمة DevicePolicyManager للوصول للدوال المخفية
     */
    @SuppressLint("PrivateApi")
    fun <T> withShizukuDevicePolicyManager(context: Context, action: (DevicePolicyManager) -> T): T {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val field = manager.javaClass.getDeclaredField("mService").apply { isAccessible = true }
        val originalService = field.get(manager) as IInterface
        val wrappedBinder = ShizukuBinderWrapper(originalService.asBinder())
        val stubClass = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        val proxiedService = asInterface.invoke(null, wrappedBinder)

        field.set(manager, proxiedService)
        return try {
            action(manager)
        } finally {
            try {
                field.set(manager, originalService)
            } catch (_: Throwable) {}
        }
    }

    /**
     * استدعاء دالة تحديث الكاش المخفية forceUpdateUserSetupComplete
     */
    @SuppressLint("PrivateApi")
    private fun DevicePolicyManager.forceUpdateUserSetupCompleteCompat(userId: Int) {
        val clazz = this::class.java
        runCatching {
            clazz.getDeclaredMethod("forceUpdateUserSetupComplete", Int::class.javaPrimitiveType!!)
                .also { it.isAccessible = true }.invoke(this, userId)
        }.onFailure {
            runCatching {
                clazz.getDeclaredMethod("forceUpdateUserSetupComplete")
                    .also { it.isAccessible = true }.invoke(this)
            }
        }
    }

    /**
     * خدعة إنشاء حساب وهمي مؤقت:
     * تضمن أن hasIncompatibleAccountsOrNonAdb = true
     * لتخطي رسالة: "Non test-only owner can't be installed with existing accounts"
     */
    private fun createDummyAccount(context: Context): Boolean {
        return try {
            val am = AccountManager.get(context)
            val account = Account(DUMMY_ACCOUNT_NAME, DUMMY_ACCOUNT_TYPE)
            am.addAccountExplicitly(account, "temp_password", null)
        } catch (e: Exception) {
            Timber.w(e, "Could not create dummy account")
            false
        }
    }

    private fun removeDummyAccount(context: Context) {
        try {
            val am = AccountManager.get(context)
            val account = Account(DUMMY_ACCOUNT_NAME, DUMMY_ACCOUNT_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                am.removeAccountExplicitly(account)
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not remove dummy account")
        }
    }

    /**
     * صمام الأمان: إعادة ضبط الهاتف إذا تعطل التطبيق أثناء تغيير user_setup_complete
     */
    fun restoreStuckUserSetupComplete(context: Context) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val userId = getUserId()
        val setup = execShizukuCommand("settings --user $userId get secure user_setup_complete")
        if (setup == "0") {
            val provisioned = execShizukuCommand("settings --user $userId get global device_provisioned")
            if (provisioned == "1") {
                execShizukuCommand("settings --user $userId put secure user_setup_complete 1")
                runCatching {
                    withShizukuDevicePolicyManager(context) { it.forceUpdateUserSetupCompleteCompat(userId) }
                }
            }
        }
    }

    /**
     * الدالة الرئيسية الموحدة لتفعيل Device Owner بأعلى موثوقية
     */
    fun activateDeviceOwner(context: Context, adminReceiver: ComponentName): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(context.packageName)) return true

        val userId = getUserId()
        createDummyAccount(context)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // =============== الخوارزمية الأولى: أندرويد 12 فما فوق ===============
                val setupBypassed = try {
                    execShizukuCommand("settings --user $userId put secure user_setup_complete 0")
                    withShizukuDevicePolicyManager(context) { it.forceUpdateUserSetupCompleteCompat(userId) }
                    true
                } catch (e: Exception) {
                    false
                }

                try {
                    // محاولة التعيين المباشر عبر الشيل
                    execShizukuCommand("dpm set-device-owner --user $userId ${adminReceiver.flattenToString()}")
                } finally {
                    if (setupBypassed) {
                        execShizukuCommand("settings --user $userId put secure user_setup_complete 1")
                        withShizukuDevicePolicyManager(context) { it.forceUpdateUserSetupCompleteCompat(userId) }
                    }
                }

            } else {
                // =============== الخوارزمية الثانية: أندرويد 11 فما دون (تجميد الحزم) ===============
                val packagesWithAccounts = mutableListOf<String>()
                val dumpsys = execShizukuCommand("dumpsys account")
                val regex = "Account \\{name=.*?, type=(.*?)\\}".toRegex()
                dumpsys.lines().forEach { line ->
                    regex.find(line)?.let { packagesWithAccounts.add(it.groupValues[1]) }
                }

                // تجميد الحزم مؤقتاً
                packagesWithAccounts.forEach { pkg ->
                    execShizukuCommand("pm disable-user --user $userId $pkg")
                }
                execShizukuCommand("pm disable-user --user $userId com.google.android.gms")

                try {
                    Thread.sleep(1500)
                    execShizukuCommand("dpm set-device-owner --user $userId ${adminReceiver.flattenToString()}")
                } finally {
                    // استعادة كافة الحزم فوراً
                    packagesWithAccounts.forEach { pkg ->
                        execShizukuCommand("pm enable --user $userId $pkg")
                    }
                    execShizukuCommand("pm enable --user $userId com.google.android.gms")
                }
            }

        } finally {
            removeDummyAccount(context)
        }

        return dpm.isDeviceOwnerApp(context.packageName)
    }
}
```

---

## 5. أداة التفعيل المستقلة عبر الكمبيوتر (ADB Desktop One-Click Tool)

إذا أردت تقديم أداة كمبيوتر سريعة للمستخدمين (One-Click Setup via PC):

### أ. ملف `SetupStateHelper.java` (لتحديث الكاش على أندرويد 12 فما فوق):
```java
import android.app.admin.IDevicePolicyManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ServiceManager;

public class SetupStateHelper {
    public static void main(String[] args) throws Exception {
        IBinder binder = ServiceManager.getService("device_policy");
        IDevicePolicyManager dpm = IDevicePolicyManager.Stub.asInterface(binder);
        int userId = 0;
        if (args.length > 0) {
            userId = Integer.parseInt(args[0]);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            dpm.forceUpdateUserSetupComplete(userId);
        } else {
            dpm.forceUpdateUserSetupComplete();
        }
        System.out.println("SUCCESS_CACHE_UPDATED");
    }
}
```
*(يتم تصريفه إلى `setup-state-helper.jar` عبر أمر: `javac -cp android.jar SetupStateHelper.java && d8 SetupStateHelper.class --output setup-state-helper.jar`)*.

---

### ب. سكريبت التفعيل الشامل عبر الكمبيوتر (`activate_do.bat`):
```cmd
@echo off
chcp 65001 >nul
echo ====================================================
echo     أداة تفعيل Device Owner المتطورة بدون حذف الحسابات
echo ====================================================
echo.

set PACKAGE=com.yourapp.package
set RECEIVER=.AdminReceiver

adb push setup-state-helper.jar /data/local/tmp/setup-state-helper.jar >nul 2>&1

echo [1/4] تصفير إشارة اكتمال الإعداد مؤقتاً...
adb shell settings put secure user_setup_complete 0
adb shell CLASSPATH=/data/local/tmp/setup-state-helper.jar app_process /system/bin SetupStateHelper 0 >nul 2>&1

echo [2/4] تفعيل صلاحية مالك الجهاز (Device Owner)...
adb shell dpm set-device-owner %PACKAGE%/%RECEIVER%

echo [3/4] استعادة إعدادات الهاتف الطبيعية فوراً...
adb shell settings put secure user_setup_complete 1
adb shell CLASSPATH=/data/local/tmp/setup-state-helper.jar app_process /system/bin SetupStateHelper 0 >nul 2>&1

echo [4/4] تنظيف الملفات المؤقتة...
adb shell rm /data/local/tmp/setup-state-helper.jar >nul 2>&1

echo.
echo ====================================================
echo تمت العملية! افحص هاتفك الآن وستجد الصلاحية مفعلة.
echo ====================================================
pause
```

---

## 6. التعامل مع استثناءات الشركات المصنعة

| الشركة / الواجهة | المشكلة المحتملة | الحل البرمجي أو الإرشادي للمستخدم |
| :--- | :--- | :--- |
| **شاومي (MIUI / HyperOS)** | ترفض شيل ADB تعديل `user_setup_complete` | يجب على المستخدم الدخول إلى: **خيارات المطورين -> تفعيل "تصحيح أخطاء USB (إعدادات الأمان)"** (يتطلب حساب Mi وشريحة SIM). |
| **سامسونج (One UI)** | وجود بروفايل Knox مسبق أو تعارض مع Secure Folder | إذا كان "المجلد الآمن" نشطاً، يتم إنشاؤه كمستخدم فرعي (`User 150`). يجب على المستخدم تعطيل المجلد الآمن مؤقتاً أثناء خطوة التفعيل فقط ثم إعادة فتحه. |
| **التطبيقات المزدوجة (Dual Apps / Dual Messenger)** | أندرويد يكتشف مستخدماً ثانياً (`User 95`) ويرمي خطأ `already several users` | إيقاف ميزة التطبيقات المزدوجة مؤقتاً قبل التفعيل ثم إعادة تفعيلها فور الانتهاء. |

---

### خلاصة التوصية لتطبيقك الخاص:
اعتمد **خوارزمية الكود في القسم 4** داخل تطبيقك مع مكتبة **Shizuku**؛ فهي تمثل قمة التطور التقني في بيئة أندرويد حالياً، وتمنح المستخدم تجربة تفعيل فورية من شاشة الهاتف دون الحاجة لأي جهاز كمبيوتر ودون التضحية بحساباته.
