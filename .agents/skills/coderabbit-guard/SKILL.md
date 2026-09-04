---
name: coderabbit-guard
description: Strict CodeRabbit-grade code reviewer & AI failure-mode detector for Flutter/Dart. Parses git diffs, runs static analysis, extracts LSP/Graphify dependencies, spawns parallel subagents for security, state race conditions, and API breaking changes. Use when user says "coderabbit", "review PR", "audit changes", "coderabbit-guard", "فحص الكود قبل الـ commit", or asks for deep strict AI code review.
---

# CodeRabbit Guard (Flutter Agentic Reviewer)

## Overview

`coderabbit-guard` performs strict, multi-agent AI code reviews tailored for Flutter & Dart applications. It replicates CodeRabbit's deep inspection pipeline without external paid APIs by combining git diff AST-like chunk parsing, LSP symbol references (`dart-mcp-server`), dependency impact mapping (`graphify`), static linter diagnostics, and 3 specialized subagent reviewers.

## Workflow

When triggered (e.g. before `git commit` or when reviewing changes):

1. **Diff Retrieval & Layer Chunking**:
   - Extract staged/unstaged changes or target commit diff.
   - Categorize changed files into: Data/DB Layer (Drift), State Layer (Riverpod), and UI Layer (Desktop/Mobile).
   - Details: See [references/audit-pipeline.md](references/audit-pipeline.md).

2. **Static Linter Pass**:
   - Run `flutter analyze` or `dart analyze` to capture all compiler & linter warnings.

3. **LSP & Impact Enrichment**:
   - Query `dart-mcp-server` (`lsp` / `analyze_files`) for method signatures & symbol callers.
   - Query `graphify` (if `graphify-out/` exists) for transitive component impact.

4. **Parallel Subagent Audit**:
   Spawn 3 parallel subagents (or focused inspection passes) using roles defined in [references/subagents-roles.md](references/subagents-roles.md):
   - **Subagent A (Security & Database Safety)**: SQL Injection (parameterized `?` vs string interpolation), ORM schema vs query column alignment, silenced errors, unhandled nulls/empty payloads, data leaks.
   - **Subagent B (Concurrency, State & Persistence Sync)**: Reactive cache/stream invalidation (`markTablesUpdated` / provider refresh), async context safety, state vs DB desync, and **Mandatory Red-Teaming Audit** (3 scenarios: race conditions/rapid inputs, unhandled async I/O failures, stale memory cache reads).
   - **Subagent C (API Contracts & Parity)**: Signature changes, call site breakage, Windows Desktop (keyboard/focus) vs Mobile touch parity.

5. **Framework-Specific Guardrails**:
   Apply rules from [references/kotlin-checks.md](references/kotlin-checks.md) (for Kotlin/Android) or [references/flutter-checks.md](references/flutter-checks.md) (for Flutter), and the project constitution [.specify/memory/constitution.md](../../.specify/memory/constitution.md).

6. **Synthesis & Severity Rating**:
   Consolidate findings into a clean report:
   - `[CRITICAL]`: Must fix before commit (SQL Injection, ORM schema column mismatch, app crash, DB corruption, broken contracts).
   - `[MAJOR]`: Maintainability/performance risk (swallowed errors, stale UI memory cache, unmounted context, missing state refresh).
   - `[MINOR]`: Code style & minor optimization.
   Provide exact code replacement diffs for all `[CRITICAL]` and `[MAJOR]` findings.

## Output Format

Report findings using:
- Priority breakdown (`[CRITICAL]`, `[MAJOR]`, `[MINOR]`).
- Exact file path & line numbers `[file_basename](file:///path/to/file#L10-L25)`.
- Root cause explanation (why CodeRabbit would flag this).
- Drop-in git diff replacement.
