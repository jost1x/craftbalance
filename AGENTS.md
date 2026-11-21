# Agent Guide — CraftBalance

## Purpose and Order of Work

This repository implements CraftBalance, a **server-side Fabric mod** for
Minecraft 1.21.1 (Java 21) that manages social playtime: it measures real
time, applies multiplayer consumption factors, tracks AFK, grants offline
recovery, enforces daily limits, and persists everything in SQLite. Clients
never install the mod.

The product behavior is defined by
[`CRAFTBALANCE_ESPECIFICACION (1).md`](<CRAFTBALANCE_ESPECIFICACION (1).md>).
The code defines what is already implemented; `README.md` documents the
shipped behavior and operations; `CHANGELOG.md` records per-release changes.
If code and spec disagree, identify the difference and do not silently change
a product rule; ask when the available context cannot resolve it.

When solving a task:

1. Review the Git state and the related files to distinguish your own work
   from pre-existing changes.
2. Locate the right module in the architecture below and the domain rules
   affected in the spec (sections 4–8 cover time, recovery, limits, AFK and
   the daily reset).
3. Implement the narrowest change that solves the request, preserving the
   boundaries between the server thread, the persistence worker, config, and
   pure calculation code.
4. Run the narrowest verification that applies and exists: `./gradlew build`
   (or `compileJava`) for code, pre-commit hooks for docs/config, manual
   staging scenarios for behavior. Do not invent scripts or claim a test
   suite exists — there is none (see below).
5. Review the final diff for unrelated changes, secrets, or needlessly
   modified derived files; summarize the validations performed and any
   pending limitation.

Do not reformat, revert, hide, or include unrelated changes. Check the tree
and the Gradle files before assuming a task, command, hook, or suite exists;
the tooling evolves and only the current repository state is authoritative.

## Working with Parallel Agents

- Delegate only bounded tasks that can progress independently, such as a
  focused review, documentation pass, or isolated implementation. Keep one
  writer per file; do not assign overlapping edits to parallel agents.
- Give each agent the goal, relevant specification context, allowed files,
  constraints, acceptance criteria, and requested checks. Ask it to report
  changed files, checks run, and unresolved questions.
- Agents share the working tree. Coordinate edits and avoid concurrent Git
  operations, staging-server changes, or database changes. Agents do not
  commit, push, or deploy; the coordinating agent reviews and integrates
  their work.
- The coordinating agent owns the final diff review, integration, relevant
  validation, and any requested commits. Do not treat an agent's completion
  report as a substitute for checking the resulting tree.

## Architecture and Responsibilities

| Location | Responsibility |
| --- | --- |
| `src/main/java/dev/craftbalance/CraftBalanceMod.java` | Fabric entrypoint (`ModInitializer`): loads config, builds `BalanceRuntime`, registers events and commands. |
| `.../config/` | `BalanceConfig` (immutable record of all options) and `ConfigManager` (YAML load, strict validation, defaults, reload support). |
| `.../time/` | `TimePolicy` (pure consumption/recovery calculations), `BalanceEngine` (applies one elapsed real-time interval to an immutable `PlayerBalance`), `DailyResetService` (once-per-date daily rollover in the configured timezone). |
| `.../storage/` | `PlayerBalance` and `AdminAdjustment` records; `SqlitePlayerRepository` (JDBC, worker-thread only); `PlayerPersistence` (single daemon worker that serializes every SQLite operation). |
| `.../server/` | `BalanceRuntime` (main-thread integration: tick loop, presence, AFK, events, limits, notifications), `BalanceCommands` (`/cb` command tree), `BalanceFormatting` (user-facing Spanish text). |
| `.../mixin/` | `ServerPlayNetworkHandlerMixin` — the only mixin in the project. |
| `src/main/resources/` | `fabric.mod.json` (version expanded from `${version}`), `craftbalance.mixins.json`, `craftbalance-default.yml` (shipped defaults), `THIRD-PARTY-NOTICES.md` and `META-INF/licenses/`. |
| `staging/` | Disposable test environment: `compose.yaml` (offline-mode Fabric server), reduced-time profiles `config.yml` / `config-a30.yml`, Mineflayer synthetic client (`client.mjs`). Never points at production data. |
| `gradle/` | Wrapper properties (pinned distribution SHA-256) and `verification-metadata.xml` (SHA-256 dependency verification). |

- The mod is server-only (`"environment": "server"` in `fabric.mod.json`).
  Do not add client-side code or require clients to install anything.
- Balances are immutable records; the engine produces new values instead of
  mutating state in place. Keep it that way.
- Time accounting is based on measured elapsed real time, never on an
  assumption of 20 ticks per second; fractional numerators preserve
  sub-millisecond precision across short intervals and restarts.
- `snakeyaml` and `sqlite-jdbc` are bundled into the jar with Gradle `include`.
  Adding or changing a bundled dependency requires updating
  `THIRD-PARTY-NOTICES.md` and `META-INF/licenses/` in the same change.
- Never hardcode the mod version in `fabric.mod.json`; `processResources`
  expands `${version}` from `mod_version` in `gradle.properties`.

## Domain Rules and Data

The spec defines the details; preserve these invariants when changing code:

- **Fail-closed persistence**: if SQLite cannot be opened (WAL unavailable)
  or a save fails, sessions are disconnected and new joins are blocked until
  restart. Do not weaken this behavior; balances that cannot be persisted
  must never keep being spent.
- SQLite runs in WAL mode with `synchronous=FULL`, verified at open. The
  config directory must live on a local disk; WAL is incompatible with
  network filesystems.
- **Daily reset** applies exactly once per player per date; the persisted
  `daily_date` never moves backwards, even if the system clock does. DST
  rules: a repeated local hour uses its first occurrence; a nonexistent local
  hour shifts forward by the gap.
- **Recovery**: requires the initial wait, accrues per active friend per
  eligible minute, and is capped per day — the cap counts recovery generated
  even after it is spent. With `only-after-normal-exhaustion: false`, the
  disconnection mark survives the daily reset. Server downtime never accrues
  recovery, and the wait restarts after an unclean shutdown.
- `time.enabled: false` suspends consumption and recovery generation, but
  presence/AFK keep updating and daily resets still apply; paused time is
  never credited retroactively.
- **Admin adjustments** are audited and transactional: balance change and
  audit entry commit together or roll back together; console adjustments are
  audited with the zero UUID; admin commands require permission level 2.
- **Config validation is strict**: unknown and duplicate keys are errors;
  keys missing from an old `config.yml` take the new default without
  rewriting the file; `/cb reload` validates the full YAML before swapping
  the active config and keeps the previous one on error. Changing
  `database.file` requires a restart.
- Schema migrations on an existing database are additive and one-shot; never
  require deleting or recreating `craftbalance.db`, and never rebuild
  already-credited balances retroactively.
- Consumption factors must be at least `1e-18` so fractional accumulation
  keeps precision.
- AFK detection counts movement, camera rotation over 1° between samples,
  own inventory/cursor and hotbar changes, chat, commands, item use, block
  and entity interactions, and attack attempts. External container changes
  are deliberately not a signal.

When adding or changing a config option, update in the same change:
`BalanceConfig` + `ConfigManager` validation, `craftbalance-default.yml`,
spec §10, and `README.md` if player- or admin-visible. Adjust `staging/`
profiles only when the scenario needs them.

## Server Safety Boundaries

- Never introduce blocking I/O (JDBC, file, network) on the Minecraft server
  thread; all SQLite work goes through `PlayerPersistence`'s single worker.
- Do not copy or move the database while a server is running; backups
  require an orderly shutdown or a WAL-aware copy (`-wal`/`-shm` included).
- Staging runs in offline mode (Minecraft does not authenticate usernames):
  keep it on the private compose network only, use a separate database, and
  restore season profiles before starting a normal server.
- Do not run `docker compose up`, deployments, or destructive commands on
  your own initiative; the staging stack is operated manually per
  `PRUEBA_ACEPTACION_SERVIDOR.md`.

## Build and Verification

- JDK 21 required. `./gradlew remapJar` produces the installable jar in
  `build/libs/`; `./gradlew serverBundle` produces the installation zip plus
  `.sha256` sidecars in `build/distributions/`.
- Gradle verifies every dependency against `gradle/verification-metadata.xml`
  and the wrapper distribution against `distributionSha256Sum`. After
  changing a dependency, regenerate with
  `./gradlew --write-verification-metadata sha256` and review the file before
  keeping it; treat both pins as deliberate updates, never side effects.
- **There is no automated test suite** (no `src/test`). Do not invent one,
  claim coverage, or fabricate test commands. Behavior changes are verified
  manually on the staging server (compose + Mineflayer client) against the
  scenarios in `PRUEBA_ACEPTACION_SERVIDOR.md`.
- The narrowest checks: `./gradlew compileJava` or `build` for Java; the
  pre-commit hooks for YAML/JSON/Markdown/staging JS.
- Pre-commit hooks (truth in `.pre-commit-config.yaml`; validate that file if
  it changes) run hygiene checks, `check-yaml`/`check-json`, yamllint,
  markdownlint (rules in `.markdownlint.yaml`), and prettier limited to
  `staging/`; `./gradlew build` runs at the **pre-push** stage because Loom
  builds are too slow per commit. If a hook modifies files or fails, inspect
  the result, stage only the task's changes, and retry the commit.
- Version coherence at release time: `mod_version` in `gradle.properties`, a
  new `CHANGELOG.md` entry, the version references in `README.md`, and the
  acceptance sheet header must all name the same version.

## Documentation and Git

- Documentation prose — `README.md`, `CHANGELOG.md`, the spec, and the
  acceptance sheet — is written in **Spanish**. In-game messages and command
  output use `assets/craftbalance/lang/`, with Spanish as the reference locale.
  Code, identifiers, javadoc, and commit messages are in **English**.
- If a product rule, permission, config option, or observable behavior
  changes, update README/spec/CHANGELOG in the same change. Recorded
  acceptance results are historical evidence: append new runs, never rewrite
  past results.
- Use Conventional Commits in English, short and specific: `type(scope):
  summary`. Useful scopes: `config`, `time`, `storage`, `server`, `mixin`,
  `staging`, `docs`, `build`. Examples: `fix(storage): keep audit entry when
  adjustment rolls back`, `docs(readme): document reload semantics`.
- When the user asks for a commit: review `git status` and `git diff`,
  identify unrelated changes, add only the task's files, and review the
  staged diff before confirming. `build/`, `.gradle/`, `run/`,
  `staging/data/`, and `*.db*` are gitignored — never force-add them.
- Do not create commits, push, switch or merge branches, tag releases, or
  deploy unless the user explicitly asks. Do not use global reset commands
  to clean shared staging.
- Do not introduce deploy tags, CI, containers, or infrastructure changes as
  a side effect; the staging compose file exists only for manual testing.
