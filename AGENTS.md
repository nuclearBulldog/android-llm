# Project Guidelines — Android LiteRT-LM Chat App

This is a personal-use, on-device AI chat Android application powered by LiteRT-LM (`api.md`). All code must be **easy for a human developer to read, understand, debug, and modify months later**.

## Pragmatic Minimalism, Low SLOC & Opportunistic Hacks
Every subagent and contributor is rewarded for:
- **Simple (not ridiculously)**: Keep code easy to read and idiomatic. Never sacrifice clarity for cryptic one-line code-golf, and never add enterprise ceremony for trivial tasks.
- **Minimal (not ridiculously)**: Build only what is needed—no speculative abstractions, unused config knobs, or premature multi-module splits.
- **Low Codebase Line Count (SLOC)**: Minimize total non-whitespace, non-comment lines of code across the entire codebase. Prefer Kotlin stdlib, Compose, and LiteRT-LM built-ins over boilerplate or extra dependencies. (Comments explaining *why* are free and encouraged—never strip helpful comments to game line count.)
- **Rare, Relevant, Opportunistic MacGyver Hacks**: When facing a genuine mobile bottleneck, heavy-dependency bloat, or streaming/LiteRT-LM quirk, a compact, clever, self-contained hack is **highly rewarded**—provided it is **rare, relevant, isolated in a small helper, and documented with a brief "why" comment**.
  - *Example (Android AI Chat)*: Instead of importing a heavy Markdown AST library (which adds dependency bloat and flickers on unclosed ```` ``` ```` fences during token-by-token streaming), write a ~20-line isolated `splitStreamingBlocks()` helper that splits on triple-backtick fences (auto-closing an open trailing fence while streaming) and styles inline `` `code` `` / `**bold**` into a Compose `AnnotatedString`. Fewer lines, zero extra dependencies, and smoother streaming.

## Human Maintainability Principles
- **Clarity Over Cleverness**: Write straightforward, idiomatic Kotlin. Avoid over-abstracted generic frameworks, unnecessary reflection/magic, or premature multi-module complexity—keep a clean single-module (`:app`) package-by-feature structure unless a clear boundary demands otherwise.
- **Small, Cohesive Units**: Keep classes, `ViewModel`s, repositories, and `@Composable` functions focused on one responsibility with shallow nesting and early returns. Split large screens into small, named sub-composables in the same feature package.
- **Explicit State & Data Flow**: Model screen state with plain immutable `data class` / `sealed interface` types and unidirectional data flow. Avoid hidden mutable singletons, implicit side effects in `init` blocks, or `GlobalScope`.
- **Document the "Why", Not the "What"**: Add concise KDoc on public interfaces/repositories and brief inline comments explaining *why* for non-obvious behavior (e.g., LiteRT-LM `Engine.initialize()` threading, `close()` ordering, GPU manifest flags, or Compose `LaunchedEffect` keys).
- **Consistent Naming & Structure**: Group files by feature (`ui/chat/`, `ui/history/`, `ui/settings/`, `ui/models/`, `data/llm/`, `data/local/`) so a human can find everything related to a screen or capability in one place.
- **Testable Without Hardware**: Keep LiteRT-LM (`com.google.ai.edge.litertlm`) behind a small, well-documented interface so business logic and `ViewModel`s can be read and unit-tested on the JVM without native `.so` or `.litertlm` files.

## SOLID & DRY Principles
- **Single Responsibility (SRP)**: Each class, `ViewModel`, repository, mapper, and `@Composable` has one clear reason to change (e.g., separate model lifecycle management, chat persistence, and UI state orchestration).
- **Open/Closed & Interface Segregation (OCP / ISP)**: Keep interfaces small and focused (`LlmEngine`, `ChatRepository`, `ModelStorage`) rather than monolithic "god" interfaces; use `sealed interface` hierarchies and strategy/config objects for extensible backends (`CPU`, `GPU`, `NPU`) and message content types.
- **Liskov Substitution & Dependency Inversion (LSP / DIP)**: Depend on interfaces/abstractions in `domain` and `ui` (`ViewModel`s), never on concrete LiteRT-LM JNI classes, Room DAOs, or Android `Context` directly; inject dependencies via constructors.
- **Don't Repeat Yourself (DRY)**: Extract shared business logic, error-mapping, Room/domain mappers, and repeated Compose UI patterns (e.g., setting rows, message action bars, attachment chips) into single, well-named helpers or reusable composables—without creating speculative abstractions for one-off code.
