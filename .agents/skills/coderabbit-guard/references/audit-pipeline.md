# CodeRabbit Audit Pipeline Details

This reference file defines the 5-stage automated audit pipeline used by `coderabbit-guard`.

---

## Stage 1: AST-like Diff Retrieval & Layer Chunking

1. Retrieve active git changes:
   - For working directory: `git diff HEAD` or staged `git diff --cached`.
   - For specific commit: `git show <commit_hash>`.
2. Parse added/modified chunks:
   - Separate code diffs from configuration (`pubspec.yaml`, `build.yaml`).
   - Group modified Dart symbols (Classes, Mixins, Extension Methods, Top-level Functions, Notifiers, Repositories).
3. Classify file layer:
   - **Data/DB**: `lib/data/`, `database/`, `tables/`, `dao/`, `repositories/`.
   - **State**: `providers/`, `notifiers/`, `controllers/`, `riverpod/`.
   - **UI**: `widgets/`, `screens/`, `pages/`, `views/`, `cells/`.

---

## Stage 2: Mechanical Static Linter Pass

Execute `flutter analyze` via `run_command` in the project root:
- Filter output to only include errors/warnings affecting changed files.
- Treat compiler errors as immediate `[CRITICAL]` failures.
- Treat warnings (`unnecessary_null_checks`, `unused_field`, `avoid_print`, etc.) as input context for Subagents.

---

## Stage 3: LSP Symbol Tracing & Graphify Impact Mapping

1. **LSP Diagnostics & Caller Search (`dart-mcp-server`)**:
   - For every modified method or field, search references using LSP find-references.
   - Verify if any caller site was broken by parameter signature changes, required parameter additions, or return type modifications.
2. **Graphify Dependency Mapping (`graphify`)**:
   - Query `graphify` knowledge graph for modified file nodes.
   - Trace callers up 2 dependency levels to detect indirect side effects (e.g. modifying a cart calculation helper impacting checkout summary screens).

---

## Stage 4: Subagent Execution Protocol

Subagents are executed in parallel (or sequential dedicated prompt blocks) with strict non-overlapping responsibilities:
- Subagent A: Focuses exclusively on Security, Exceptions, and Null Safety.
- Subagent B: Focuses exclusively on Concurrency, Drift SQLite DB Locks, Riverpod Notifiers, and Flutter UI Lifecycle (`mounted`).
- Subagent C: Focuses exclusively on API Contract Integrity, Signature Parity, and Desktop vs Mobile UX Parity.

---

## Stage 5: Noise Reduction & Severity Deduplication

Before rendering the final report:
1. Deduplicate findings across subagents.
2. Filter out subjective style nitpicks unless explicitly requested.
3. Categorize severity:
   - `[CRITICAL]`: Unhandled exceptions leading to crashes, DB data corruption/deadlocks, broken call signatures, missing `if (!context.mounted)` before UI navigation.
   - `[MAJOR]`: Silent exception swallowing, stale state in Riverpod vs SQLite DB, missing desktop keyboard listeners, unbounded widget lists.
   - `[MINOR]`: Missing `const` constructors, redundant imports, minor code duplication.
4. Output concise GitHub-flavored markdown with code diffs.
