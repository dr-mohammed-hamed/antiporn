# Kotlin & Android Architectural Guardrails (CodeRabbit Guard)

This reference document outlines strict anti-patterns and failure modes unique to Kotlin, Android Enterprise, and Device Owner applications.

---

## 1. Kotlin Language & Nullability Anti-Patterns

### Anti-Pattern 1.1: Platform Type Blindness (NPE Risk)
- **Defect**: Accessing fields/methods directly on objects returned from Java/Android framework APIs without safe call operator `?.`.
- **Impact**: Instant `NullPointerException` at runtime when API returns `null`.
- **Rule**: Always treat Java SDK returns as nullable (`Type?`).

### Anti-Pattern 1.2: Context Leakage
- **Defect**: Storing `Activity` context inside singletons, companion objects, or long-running executors.
- **Impact**: Memory leak of entire Activity, views, and resources (`OutOfMemoryError`).
- **Rule**: Always use `context.applicationContext`.

### Anti-Pattern 1.3: Silent Exception Swallowing
- **Defect**: Using empty `catch (e: Exception) {}` or returning dummy defaults (`false`, `null`, `0`) without logging or surfacing the error.
- **Impact**: Silent failures that hide system-level breakage and corruption.
- **Rule**: Always log with `Log.e(TAG, "...", e)` and surface the error to the caller/UI.

---

## 2. Android Lifecycle & Concurrency Anti-Patterns

### Anti-Pattern 2.1: Watchdog / Observer Self-Triggering Loops
- **Defect**: `ContentObserver` triggers on settings changes and re-writes the same setting unconditionally, firing another change event.
- **Impact**: Infinite background CPU thrashing and battery drain every few milliseconds.
- **Rule**: Always verify if the current value matches the desired value before executing writes (`Check-Before-Write`).

### Anti-Pattern 2.2: Callback Dropping on CAS Lock
- **Defect**: Using `AtomicBoolean.compareAndSet(false, true)` and returning without queuing or invoking incoming callbacks when an operation is in progress.
- **Impact**: UI buttons permanently disabled (`isEnabled = false`).
- **Rule**: Queue all callbacks in a thread-safe list (`CopyOnWriteArrayList`) and dispatch all when finished.

### Anti-Pattern 2.3: Foreground Service Launch Violations
- **Defect**: Starting FGS from background on Android 12+ without exemptions, or omitting `foregroundServiceType="specialUse"` on Android 14+.
- **Impact**: `ForegroundServiceStartNotAllowedException` crash.
- **Rule**: Declare explicit FGS types, properties in manifest, and leverage Device Owner privileges.

---

## 3. Device Owner & Security Anti-Patterns

### Anti-Pattern 3.1: Developer ADB Lockout (CRITICAL)
- **Defect**: Setting `UserManager.DISALLOW_DEBUGGING_FEATURES` or `DISALLOW_CONFIG_DEVELOPER_OPTIONS`.
- **Impact**: Locks the developer out of ADB permanently, preventing debugging or uninstalling.
- **Rule**: NEVER set these flags. Strictly preserve developer ADB access.

### Anti-Pattern 3.2: Partial Bypass Hardening
- **Defect**: Enforcing DNS but forgetting Safe Boot, Multi-User, System Cloner, or Browser Incognito restrictions.
- **Impact**: User bypasses protection via Safe Mode or Incognito tabs.
- **Rule**: Enforce full bypass matrix: Safe Boot (`DISALLOW_SAFE_BOOT`), Multi-User (`DISALLOW_ADD_USER`), Factory Reset (`DISALLOW_FACTORY_RESET`), Uninstall (`setUninstallBlocked`), and Browser Restrictions (`setApplicationRestrictions`).
