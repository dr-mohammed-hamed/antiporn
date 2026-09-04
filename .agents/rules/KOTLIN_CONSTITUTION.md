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
7. **Empirical Gate**: Verify using `.\gradlew.bat compileDebugSources` and `.\gradlew.bat lintDebug` (0 errors) before presenting.
