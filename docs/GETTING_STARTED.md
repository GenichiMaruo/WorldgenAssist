# Getting Started — VS Code + Fabric + Minecraft 26.2

This guide prepares a Windows + VS Code environment for AI-assisted development of Client-Assisted World Generation.

The most important goal is not only to compile a Fabric mod, but to make the **actual Minecraft 26.2 source navigable by both the developer and the AI agent**.

---

## 1. Install prerequisites

Install:

- Git
- Visual Studio Code
- JDK 25

Fabric's current 26.2 documentation uses JDK 25 for the modern development environment.

Verify Java in PowerShell:

```powershell
java -version
javac -version
```

Both should resolve to JDK 25.

If multiple JDKs are installed, ensure VS Code / Gradle uses the intended JDK.

---

## 2. Install VS Code extensions

Install at minimum:

- **Extension Pack for Java** (Microsoft)
- **Gradle for Java** (Microsoft)

For AI-driven development, also use an AI coding agent that can:

- read the whole workspace
- edit files
- search symbols
- run terminal commands
- read build/test output
- read `run/logs/latest.log`

The AI agent should be instructed to read `AGENTS.md` before implementation.

---

## 3. Create the Fabric project

Preferred method:

Fabric Template Mod Generator:

https://fabricmc.net/develop/template/

Recommended initial choices:

```text
Minecraft version: 26.2
Language: Java
Environment: client + server
```

Use a unique package and mod ID.

Working example only:

```text
Mod name: Client Assisted Worldgen
Mod ID: client_assisted_worldgen
Package: com.example.clientassistedworldgen
```

Replace `com.example` with a package namespace you actually control before publishing.

### Project directory

Use a simple local path such as:

```text
C:\Projects\client-assisted-worldgen
```

Avoid:

- OneDrive / cloud-synced folders
- spaces if possible
- Japanese / other non-ASCII characters in the project path

This matches Fabric's project-creation guidance and avoids build/tooling path problems.

---

## 4. Open the project in VS Code

Open the extracted project folder:

```text
File
→ Open Folder
→ C:\Projects\client-assisted-worldgen
```

Wait for the Java and Gradle project import to finish.

Do not start editing internal Minecraft code assumptions before Gradle import completes.

---

## 5. Verify project versions

Before implementation, inspect:

```text
gradle.properties
build.gradle
settings.gradle
gradle/wrapper/gradle-wrapper.properties
src/main/resources/fabric.mod.json
```

For the initial Minecraft 26.2 setup, Fabric's 26.2 release notes recommend:

- Loom 1.17
- Gradle 9.5.1 at the time of that release

Use the versions generated/recommended by the current Fabric 26.2 template unless there is a documented reason to change them.

Do not manually copy versions from old 1.21.x tutorials.

---

## 6. Generate Minecraft source

This is one of the most important setup steps.

From the project root in PowerShell:

```powershell
.\gradlew.bat genSources
```

Fabric Loom will prepare locally navigable Minecraft sources for the development workspace.

### Why this matters

This project depends on internal world-generation behavior. The generated source is the primary reference for:

- `ChunkStatus`
- chunk generator implementation
- noise-generation classes
- `RandomState`
- `NoiseChunk`
- aquifer logic
- blending
- structure terrain adaptation
- asynchronous return types
- exact method signatures
- Mixin targets

Do not use old tutorials as the source of truth.

---

## 7. Navigate Minecraft source in VS Code

After `genSources` and Gradle import:

### Go to definition

- `F12`
- or `Ctrl + Click`

### Find references

- Right click a symbol
- `Find All References`

### Search classes / symbols

Use VS Code search and Java symbol navigation.

Start your worldgen investigation with names such as:

```text
ChunkStatus
ChunkGenerator
NoiseBasedChunkGenerator
NoiseChunk
RandomState
NoiseGeneratorSettings
Aquifer
Blender
StructureManager
ProtoChunk
ChunkAccess
```

Important:

These are investigation starting points. The exact current methods and signatures must be confirmed from the generated 26.2 source.

---

## 8. Generate VS Code launch targets

Run:

```powershell
.\gradlew.bat vscode
```

Fabric Loom can generate VS Code launch targets.

Then open:

```text
Run and Debug
```

You should be able to select development targets such as client/server and start debugging with `F5`.

If targets do not appear:

1. Reload VS Code.
2. Confirm Gradle import succeeded.
3. Run `.\gradlew.bat vscode` again.
4. Check the Gradle output.

---

## 9. First build

Run:

```powershell
.\gradlew.bat build
```

The mod JAR should be generated under:

```text
build\libs\
```

Do this before implementing the research feature.

The initial clean template must build successfully.

---

## 10. Run a development client

```powershell
.\gradlew.bat runClient
```

For debugger-driven development, prefer the VS Code Run and Debug launch target when possible.

The development game files are normally placed below a project runtime directory such as:

```text
run\
```

Keep this runtime as disposable test data.

---

## 11. Run a development dedicated server

```powershell
.\gradlew.bat runServer
```

The first server launch may require EULA handling depending on the generated setup.

Use the development server only for test worlds.

Never use a valuable personal world as the first test target for worldgen Mixins.

---

## 12. Initialize the documentation

Place these files in the repository:

```text
AGENTS.md
GETTING_STARTED.md

docs/
├─ ARCHITECTURE.md
├─ WORLDGEN_PIPELINE.md
├─ MIXIN_TARGETS.md
├─ REMOTE_PROTOCOL.md
├─ SECURITY_MODEL.md
├─ TESTING.md
├─ BENCHMARK.md
└─ REFERENCES.md
```

Tell the AI coding agent:

> Read AGENTS.md and all relevant docs before changing code. For Minecraft internals, inspect the generated 26.2 source and do not guess method signatures.

---

## 13. Recommended first source investigation

Before writing the first Mixin, answer these questions from generated source and record the results in `docs/MIXIN_TARGETS.md`:

1. Which `ChunkStatus` stage performs base terrain / noise filling?
2. Which concrete generator implementation handles Overworld noise generation?
3. What method starts that operation?
4. What is the exact return type?
5. Which arguments supply:
   - random/noise state
   - structure information
   - blending information
   - chunk access
6. Which thread executes the method?
7. Where does the next generation stage wait for completion?
8. Which chunk data is mutated during the stage?
9. Which heightmaps or post-processing data are updated?
10. What objects are safe or unsafe to use on an external worker thread?

Do not proceed to network offload until these are documented.

---

## 14. Recommended first implementation order

### Step A — logging only

Add a minimal hook that logs:

```text
chunk coordinate
generation stage
elapsed time
thread name
```

Do not alter output.

### Step B — deterministic baseline

For fixed test seeds and coordinates:

```text
generate
→ hash relevant output
→ save result
```

### Step C — local worker abstraction

Introduce a project-owned interface such as:

```java
interface TerrainComputeBackend {
    CompletableFuture<TerrainComputeResult> compute(TerrainComputeJob job);
}
```

First implementation:

```text
LocalTerrainComputeBackend
```

Only after this works:

```text
RemoteClientTerrainComputeBackend
```

This avoids mixing networking with worldgen reverse engineering.

Current implementation status: Step C's executor-level vertical slice is
complete. The exact-vanilla-executor `delegate` path passes the backend-seam
gate; the bounded dedicated `LocalWorldgenTaskBackend` preserves output but its
measured scheduling policy is not a performance-equivalent baseline. Phase 2's
first registered network protocol and trusted-client density worker are also
complete. It sends a bounded immutable `TerrainDensityJob`, receives a bounded
`TerrainDensityResult`, and substitutes only `fullNoiseDensity` before the
original server-side fill. The initial eligibility scope is one client, new
Overworld chunks, empty blending/beardifier, no retrogen, and vanilla-compatible
worldgen registries. Phase 3 advances the wire protocol to version 2: result
doubles are losslessly encoded as RAW or DEFLATE, decoded on a bounded server
executor, and may be retained in a bounded context-keyed LRU cache.
Phase 4 adds separately opt-in player-owned prediction. It schedules only a
safe density intermediate ahead of the sole worker player's motion and never
forces Minecraft to generate the predicted chunk.
Phase 5 adds separately opt-in server recomputation of unpredictable whole
interpolation cells before a result may be cached or installed.

To exercise it in a disposable world, start the server with:

```powershell
$env:WORLDGEN_ASSIST_REMOTE = "true"
$env:WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE = "trusted_raw"
$env:WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT = "1"
$env:WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS = "2000"
$env:WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES = "16"
.\gradlew.bat runServer --args nogui
```

Connect one development client from another shell:

```powershell
.\gradlew.bat runClient --args="--quickPlayMultiplayer localhost:25565"
```

The handshake and one-thread client worker are automatic. Look for
`worker.handshake status=ACCEPTED`, `job.client_complete`,
`job.result_decoded`, `job.result_received`, `cache.store`, and `job.complete`.
The in-flight/timeout/cache bounds are `1..64`, `50..60,000` ms, and `0..256`;
defaults are `1`, `2,000`, and `16`, and zero cache entries disables caching.
Equivalent JVM properties are `worldgen_assist.remote.max_in_flight`,
`worldgen_assist.remote.timeout_ms`, and
`worldgen_assist.remote.cache_entries`. The disclosure property's JVM form is
`worldgen_assist.remote.seed_disclosure`. Both remote execution and disclosure
permission are disabled by default. Setting remote execution alone produces
`remote.blocked`, rejects the worker, and sends no job. `trusted_raw` explicitly
discloses the raw seed, so this PoC must not be used on a public server. Set
`WORLDGEN_ASSIST_CONTEXT_FINGERPRINT=true` only when the separate
per-dimension startup diagnostic is needed.

To exercise Phase 4 in that disposable setup, also set:

```powershell
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION = "true"
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION_INTERVAL_TICKS = "20"
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION_LEAD_CHUNKS = "8"
```

Equivalent properties are `worldgen_assist.remote.prediction`,
`worldgen_assist.remote.prediction_interval_ticks`, and
`worldgen_assist.remote.prediction_lead_chunks`. Prediction is false by default,
requires at least one cache entry, and uses bounded interval `1..1200` and lead
`1..8` (defaults `20` and `8`). Watch for `prediction.sent`,
`prediction.complete`, `prediction.join`, and a subsequent `cache.hit`.

To exercise Phase 5 validation, set a bounded cell count (`0..64`, default
`0`) on the server:

```powershell
$env:WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS = "8"
```

The equivalent property is
`worldgen_assist.remote.validation_sample_cells`. Watch for
`job.validation_complete`. An exact mismatch logs `worker.quarantined` and
`job.apply_rejected`, skips cache/application, and completes through local
generation. The sampling guarantee is probabilistic, and the raw seed is still
disclosed; enabling it does not make the PoC suitable for a hostile public
server.

---

## 15. Suggested debug commands later

Once a server command system is implemented, useful commands include:

```text
/cawg status
/cawg workers
/cawg stats
/cawg cache
/cawg generate-local <x> <z>
/cawg generate-remote <x> <z>
/cawg benchmark <count>
/cawg clear-cache
```

Do not implement all commands immediately. Add them as instrumentation becomes useful.

---

## 16. Useful daily commands

```powershell
# Rebuild after changes
.\gradlew.bat build

# Unit tests
.\gradlew.bat test

# Generate source if needed
.\gradlew.bat genSources

# Generate/update VS Code launch targets
.\gradlew.bat vscode

# Client
.\gradlew.bat runClient

# Server
.\gradlew.bat runServer

# Inspect available tasks when unsure
.\gradlew.bat tasks
```

---

## 17. Troubleshooting checklist

### `java -version` is wrong

Fix `JAVA_HOME`, PATH, or VS Code Java runtime configuration.

### Gradle imports forever / fails strangely

Check:

- JDK version
- network access
- proxy settings
- project path
- Gradle output panel

### Minecraft source cannot be opened

Run:

```powershell
.\gradlew.bat genSources
```

Then reload the Java project / VS Code window.

### VS Code has no Minecraft launch target

Run:

```powershell
.\gradlew.bat vscode
```

Then reload VS Code.

### Mixin compiles but game crashes at startup

Compiler success does not prove a Mixin target is correct.

Inspect:

```text
run\logs\latest.log
```

and the crash report.

Re-open the exact generated target method and verify:

- class
- method
- descriptor
- injection point

---

## 18. Definition of “environment ready”

Do not start Phase 0 until all are true:

- [ ] `java -version` reports JDK 25
- [ ] VS Code recognizes the Gradle project
- [ ] `.\gradlew.bat genSources` succeeds
- [ ] Minecraft source navigation works
- [ ] `.\gradlew.bat vscode` succeeds
- [ ] `.\gradlew.bat build` succeeds
- [ ] dev client launches
- [ ] dev server launches
- [ ] `AGENTS.md` is present
- [ ] AI agent can read workspace files
- [ ] AI agent can execute Gradle commands
- [ ] AI agent can inspect `run/logs/latest.log`

At that point, begin with vanilla worldgen instrumentation, not remote networking.
