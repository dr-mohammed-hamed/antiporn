---
name: deep-analysis
description: >
  Forces the agent to execute a world-class, long-running, deep investigation (similar to the /goal command).
  Enforces a strict 5-tier surgical tool hierarchy (Graphify -> Code-Index -> ast-grep -> Dart LSP -> Sequential Thinking),
  exhaustive end-to-end tracing across all architecture layers, runtime async/exception auditing, and adversarial red-teaming.
  Triggered when the user requests "deep analysis", says "حلل بعمق", "فكر بعمق", "deep-analysis", or when the user invokes /deep-analysis.
---

# Deep Analysis Workflow & Surgical Investigation Protocol

You are now in **Deep Analysis Mode**. You must treat the user's request as an exhaustive, high-thoroughness architectural investigation (operating at the level of a Principal Systems Architect). 

You are **STRICTLY PROHIBITED** from:
- Answering from memory, intuition, or assumptions.
- Reading whole 1000-line files carelessly (causing token bloat and attention drift).
- Concluding that "everything works perfectly" without demonstrating a step-by-step trace of async timing, exception abort paths, and database synchronization.

---

## 1. The 5-Tier Surgical Tool Hierarchy (Strict Execution Order)

Execute investigations through this precise tool hierarchy to maintain maximum token density and mathematical precision:

```
[Tier 1: Graphify]        ===> Map Whole-System Architecture & Blast Radius
[Tier 2: Code-Index]      ===> Surgical Symbol Extraction (get_symbol_body in ~150 tokens)
[Tier 3: ast-grep]        ===> Polyglot Structural AST Pattern Matching (Dart, SQL, YAML)
[Tier 4: Dart LSP]        ===> Semantic Type Hierarchy, Compiler Diagnostics & References
[Tier 5: Sequential CoT]  ===> Non-Linear Branching & Hypothesis Falsification
[Tier 6: grep_search]     ===> STRICT LAST RESORT ONLY for unindexed raw strings
```

### Tier 1: Architectural Topology & Blast Radius (`graphify`)
1. Query `graphify` / knowledge graph before inspecting code.
2. Determine:
   - What are the upstream callers and downstream dependents?
   - Is the target a God Node / High-Degree Hub?
   - What is the exact blast radius if this component is altered?

### Tier 2: Surgical Symbol & Body Extraction (`code-index-mcp`)
1. **Never read whole files blindly.**
2. Use `code-index-mcp` with `get_symbol_body(symbol_name, file_path)` to fetch the exact function, class, or method body in ~150 tokens.
3. Use `search_code_advanced` to locate relevant symbol signatures and summaries across the workspace without polluting context.

### Tier 3: Polyglot Structural AST Pattern Matching (`ast-grep-mcp`)
1. Use `find_code` with AST syntax patterns (e.g. `await $FUNC(); setState($$$ARGS);` or `class $NAME extends $BASE`) to find structural patterns across all files.
2. Use `find_code_by_rule` with YAML rules for complex relational AST searches (nested async loops, missing null guards, unhandled error blocks).
3. Use `dump_syntax_tree` to inspect the grammar structure of ambiguous code before formulating refactoring strategies.

### Tier 4: Semantic Type System & Compiler Verification (`dart-mcp-server`)
1. Use `dart-mcp-server` LSP (`find_references`, `go_to_definition`, `hover`) to trace exact semantic types, interface contracts, and polymorphic implementations.
2. Run `analyze_files` to verify real-time compiler diagnostics and catch subtle type mismatches.
3. Inspect `get_runtime_errors` when diagnosing crashes or performance regressions.

### Tier 5: Deliberate Non-Linear Reasoning (`sequential-thinking`)
1. For complex multi-step problems, invoke `sequentialthinking`.
2. **Mandatory Non-Linear Branching**: Actively use `branchFromThought`, `revisesThought`, and `branchId` to evaluate at least 2 competing architectural approaches.
3. Formulate a clear hypothesis, test it against the code traces, and record revisions when assumptions fail.

---

## 2. Exhaustive End-to-End Tracing (Anti-Blindspot Protocol)

You must trace the complete lifecycle of data and events across all 4 architectural layers:

```
[Layer 1: UI Widgets]       ===> Desktop & Mobile Responsive Parity + FocusNodes + Keyboard Handling
        ↓
[Layer 2: Riverpod State]   ===> StateNotifiers, AsyncNotifiers, StreamProviders, Cache Lifecycles
        ↓
[Layer 3: Repositories]     ===> Business Logic Invariants, Data Transformations, Error Wrapping
        ↓
[Layer 4: Local Database]   ===> Drift SQLite Tables, SQL Constraints, Turso Outbox Queue
```

1. **UI Layer**: Inspect both Desktop (physical keyboard, table cells, shortcuts) and Mobile (touch targets, modals, SnackBars) equivalents.
2. **State Layer**: Trace how state is held in memory, whether sessions or carts maintain stale data, and how listeners are notified.
3. **Repository Layer**: Verify contract boundaries, DTO transformations, and offline fallback mechanisms.
4. **Database & Sync Layer**: Verify SQLite transaction boundaries, foreign key cascades, and outbox sync reliability.

---

## 3. Dynamic Runtime & Async Auditing Protocol

Before finalizing your analysis, conduct a rigorous dynamic execution walkthrough:

### A. Async Gap Analysis
- Locate every `await` statement.
- **Audit**: What happens in the UI/State while waiting? Can the user click again (double-tap race condition)? Is `if (!context.mounted) return;` strictly present before any `BuildContext` access? Is there an event loop lag before providers notify listeners?

### B. Exception Propagation & Abort Path Audit
- Simulate exceptions at every I/O, DB, or network boundary.
- **Audit**: Is the exception caught gracefully? If it throws, what critical cleanup lines are bypassed (e.g. closing loading dialogs, resetting busy flags, releasing SQLite locks, returning focus)? Will the UI hang or freeze?

### C. State vs. SQLite Database Synchronization Audit
- When a database write occurs, audit the synchronization pipeline.
- **Audit**: Does the Riverpod provider update synchronously or with an async delay? If downstream code queries state immediately after DB write, will it read stale cache? Is explicit cache invalidation needed?

---

## 4. Adversarial Red-Teaming (The Mandatory 3-Failure-Mode Gate)

Before writing your final report:
1. Assume your primary thesis or implementation plan has critical flaws.
2. **You MUST uncover and document at least 3 concrete failure modes, edge cases, or race conditions** in the analyzed flow (e.g., network drop during outbox flush, concurrent rapid keystrokes, unhandled null in legacy DB rows).
3. Explicitly document each failure mode, its trigger conditions, its blast radius, and its preventative mitigation.

---

## 5. Output Deliverables & PM-Aligned Reporting

Format the final output cleanly:
- **Executive Summary**: 2-3 sentences explaining findings and value in plain English.
- **Architecture & Data Flow Diagram**: A clean Mermaid diagram showing the end-to-end flow.
- **Surgical Findings**: Clickable file links with exact line ranges: `[filename.dart:L45-L60](file:///d:/b/daftar_albahr/lib/path/to/file.dart#L45-L60)`.
- **The 3 Red-Teamed Failure Modes**: Detailed breakdown of identified risks and mitigations.
- **Clear Actionable Recommendations**: Prioritized steps for implementation or refactoring.
