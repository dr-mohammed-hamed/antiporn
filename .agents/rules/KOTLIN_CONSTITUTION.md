# Kotlin & Android Device Owner Rules

All AI agents working on this repository MUST strictly comply with the project constitution located at:
[.specify/memory/constitution.md](file:///d:/b/antiporn/.specify/memory/constitution.md)

### Top Immutable Principles:
1. **NEVER touch USB Debugging / Developer Options** (`DISALLOW_DEBUGGING_FEATURES` is strictly prohibited).
2. **Handle Platform Types Safely**: Always treat Android Java SDK returns as nullable (`Type?`).
3. **Prevent Context Leaks**: Never pass Activity context into singletons or background tasks; strictly use `context.applicationContext`.
4. **Check Before Write**: Prevent infinite observer loops by verifying system settings before writing.
5. **No Exception Swallowing**: Never leave empty `catch` blocks or return fake fallbacks.
6. **Strings in Resources**: All user-facing strings must reside in `res/values/strings.xml`.
7. **Mandatory Pluggable Shield Architecture**: Every protection feature must be encapsulated in an isolated `ProtectionShield` file (< 200 lines) with zero collateral damage to existing shields.
8. **File Size Ceiling & Modular Naming**: Files must NEVER reach 1000 lines (target 300–500 lines). Decompose using canonical suffixes (`...Shield`, `...Engine`, `...Watcher`, `...Bridge`, `...Handler`, `...ViewBinder`, `...Ext`).
9. **Strict No-Emoji UI Rule**: NEVER use Unicode emojis (🛡️, 🔒, 🚫, 👑, etc.) in user interfaces, buttons, notifications, or strings. Use clean Material Vector icons (`@drawable/...`) exclusively.
10. **Account Lockout Prevention**: NEVER enable `DISALLOW_MODIFY_ACCOUNTS` automatically upon Device Owner activation. Users must be able to re-add Google accounts.
11. **Monotonic Clock Enforcement**: For curfews and lock timers, ALWAYS use `SystemClock.elapsedRealtime()`. NEVER rely on `System.currentTimeMillis()` alone (prevents wall-clock manipulation bypass).
12. **Zero Busy-Waiting**: NEVER use `while(true) { Thread.sleep(...) }`. Rely strictly on OS callbacks (`UsageEvents`, `NetworkCallback`, `AlarmManager`).
13. **Lifecycle Safety Guard**: Always wrap asynchronous UI calls with `if (!isFinishing && !isDestroyed)` before touching views.
14. **Packaging & Metadata Pre-flight**: When adding dependencies, proactively add duplicate resource excludes in `packaging.resources.excludes` and `-Xskip-metadata-version-check`.
15. **Empirical Gate**: Verify using `.\gradlew.bat compileDebugSources` and `.\gradlew.bat lintDebug` (0 errors) before presenting.
16. **Mandatory Material 3 & Semantic Theming**: All UI layouts, cards, dialogs, and components MUST strictly adhere to Material 3 Day/Night architecture (Theme.Material3.DayNight.NoActionBar). NEVER hardcode surface colors (@color/black, @color/shield_hero_bg, #FFFFFF) onto card backgrounds or texts. Strictly use dynamic semantic attributes (?attr/colorSurface, ?attr/colorSurfaceContainer, ?attr/colorOnSurface, ?attr/colorOnSurfaceVariant, ?attr/colorOutlineVariant) to guarantee 100% WCAG AAA contrast accessibility across both Light and Dark themes.
