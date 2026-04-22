# GitHub Copilot Instructions — BetterReplay

These instructions apply to all AI-assisted work on this repository.
Follow them every time code, documentation, or configuration is added or changed.

---

## Project identity

- **BetterReplay** is a server-side Minecraft replay plugin targeting **Paper** (with Folia-compatible scheduling via FoliaLib).
- Language: **Java 21**.
- Build tool: **Maven**.
- Packet interception: **PacketEvents**.
- Storage backends: `file` (JSON) and `MySQL`.
- Optional soft-dependency: **Floodgate** (Bedrock player support).

---

## Documentation — always keep in sync

### README.md
- Update `README.md` whenever any of the following change:
  - Feature list
  - Available commands or permissions
  - Configuration keys or valid values
  - Storage backends or their options
  - Build instructions or requirements
  - Public API surface (methods, events, classes)
  - Architecture overview or file references
- Do **not** leave the README describing removed, renamed, or outdated behaviour.

### CHANGELOG.md
- Every pull request or meaningful commit **must** include a corresponding entry in `CHANGELOG.md`.
- Follow **[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/)** format exactly:
  - Sections: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`.
  - New work goes under `## [Unreleased]` until a version is tagged.
  - On release: move `[Unreleased]` entries into a dated version block and update the comparison links at the bottom.
- A CHANGELOG entry is **required** for every user-facing or API-facing change, no matter how small.

---

## Testing — treat as first-class concern

### Coverage expectations
- Every new feature **must** ship with corresponding unit tests.
- Every bug fix **must** include a regression test that would have caught the bug.
- Tests live under `src/test/java/` and mirror the production package structure.
- Aim for meaningful behavior coverage, not just line coverage. Test edge cases and error paths, not just the happy path.

### Test quality rules
- Tests must be **deterministic** — no reliance on real server state, file system, or network.
- Mock or stub all Paper/Bukkit API calls; never require a running server.
- Avoid `static` singletons in production code that make tests hard to isolate (see the `Replay.getInstance()` pattern that was removed).
- Use `@BeforeEach` / `@AfterEach` to properly set up and tear down state; never share mutable state between test methods.
- Prefer clear, descriptive test method names that read as sentences (e.g., `startRecording_withDuplicateName_throwsIllegalState`).

### When making any change to the plugin
1. **Before coding**: identify which existing tests cover the area you are changing — make sure you understand the expected behaviour.
2. **During coding**: write tests alongside production code, not after.
3. **After coding**: run the full test suite (`mvn test`) and ensure no regressions.
4. If a test is hard to write, that is a design signal — favour testable designs (dependency injection, interfaces, avoid static state).

---

## Code quality

- Replace `printStackTrace()` with proper `Logger` calls (`java.util.logging.Logger` via the plugin instance).
- Remove unused imports before committing.
- Parameterize raw types (e.g., `EntityData<T>` not raw `EntityData`).
- Prefer sealed classes / records for closed type hierarchies (e.g., `TimelineEvent`).
- Thread safety: collections shared across threads **must** use concurrent variants (`ConcurrentHashMap`, etc.).
- Events that must run on the main thread must be fired synchronously — never `callEvent` from an async context without scheduling onto the main thread first.
- All cross-thread access to Bukkit API must be dispatched back to the server thread via `FoliaLib` scheduler.

---

## API contract

- `ReplayAPI` and `ReplayManager` form the **public API surface**. Treat them as a contract.
- Never break a public API method's signature or semantics without a CHANGELOG `Changed` or `Removed` entry and a migration note in `docs/API.md`.
- New API methods must be documented in `docs/API.md` with a usage example.
- All API-facing changes require matching test coverage.

---

## Version and release hygiene

- The canonical plugin version lives in `pom.xml` and is reflected in `plugin.yml`.
- Use [Semantic Versioning](https://semver.org/spec/v2.0.0.html):
  - **PATCH** — backward-compatible bug fixes.
  - **MINOR** — new backward-compatible features.
  - **MAJOR** — breaking changes to public API or config format.
- Strip `-SNAPSHOT` from comparison strings during update checks.
- Tag releases in Git (`v<major>.<minor>.<patch>`) and update the CHANGELOG comparison links.

---

## CI / Workflow

- The Maven CI workflow (`maven.yml`) runs on every push and pull request — keep it green.
- Do not merge branches with failing tests.
- Modrinth publishing is handled automatically by the CI workflow on tagged releases — ensure the version in `pom.xml` is correct before tagging.

---

## Commit messages

Use the Conventional Commits style:

```
<type>(<scope>): <short summary>

[optional body]
```

Common types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `ci`.

Examples:
- `feat(recording): add HeldItemChange event for slot tracking`
- `fix(playback): prevent controls activating on entity click`
- `docs(api): document deleteRecording method`
- `test(timeline): add regression for backward-compat deserialization`
