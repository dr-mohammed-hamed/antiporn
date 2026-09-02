# Flutter & Dart Architectural Guardrails

This reference document outlines specific anti-patterns and failure modes unique to Flutter, Riverpod, and Drift SQLite projects.

---

## 1. Riverpod & State Management Anti-Patterns

### Anti-Pattern 1.1: Direct State Mutation
- **Defect**: Mutating state objects directly (e.g. `state.items.add(newItem)`) instead of emitting a new immutable state instance (`state = state.copyWith(...)`).
- **Impact**: Riverpod listeners will not trigger UI rebuilds.

### Anti-Pattern 1.2: Reading Providers inside Async Handlers without `ref.read`
- **Defect**: Using `ref.watch` inside button callbacks or async event handlers instead of `ref.read`.
- **Impact**: Causes unwanted widget rebuilds when state changes asynchronously.

### Anti-Pattern 1.3: Stale Cache on Outbox / Turso Sync Trigger
- **Defect**: Executing outbox sync or REST calls without invalidating memory providers (`ref.invalidate(provider)`).
- **Impact**: UI displays old offline data until app restart.

---

## 2. Drift SQLite Database Anti-Patterns

### Anti-Pattern 2.1: Heavy Async Queries on Main Isolate
- **Defect**: Performing un-indexed queries or large dataset fetches directly inside build methods or main isolate async calls.
- **Impact**: UI jank and frame drops on Windows & Android.

### Anti-Pattern 2.2: Missing Transaction Wrapper for Multi-Table Writes
- **Defect**: Inserting into an Outbox table and updating a Local Table in two separate non-transactional statements.
- **Impact**: Partial failure leaves database in an inconsistent state if an exception occurs mid-way.

---

## 3. Flutter UI & Layout Anti-Patterns

### Anti-Pattern 3.1: Hardcoded Offsets & Static Sizes
- **Defect**: Hardcoding pixel heights (e.g. `height: 450`) or fixed widths that overflow on smaller phone screens or resizable Windows windows.
- **Impact**: `RenderFlex overflowed by XXX pixels`.

### Anti-Pattern 3.2: Missing `const` Constructors
- **Defect**: Omitting `const` on static widget subtrees.
- **Impact**: Unnecessary widget instantiation and elevated GC pressure during animations or scrolling.

### Anti-Pattern 3.3: Unbounded List inside Column
- **Defect**: Placing `ListView` or `GridView` directly inside a `Column` without wrapping in `Expanded` or setting `shrinkWrap: true`.
- **Impact**: `Vertical viewport was given unbounded height` crash.
