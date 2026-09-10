# Security Model

## Public-fixture exception — 2026-09-05

The seeded-leaf design now has a separately enabled v3 fixture transport and
live adapter for the fixed already-public seed 8675309 only. The actual server
world seed is checked before ledger/worker admission. See
`SEEDED_LEAF_FIXTURE_PROTOCOL.md`. Earlier unregistered/inactive descriptions in
this ledger are the pre-fixture baseline. Private-world transport and public
hostile-server readiness remain blocked: deterministic leaf transcripts permit
candidate-seed checking, and quotas/HMAC are not confidentiality proofs.

## 1. Initial trust level

The first PoC assumes a trusted client on a controlled server.

This assumption is temporary and must not be confused with production security.

---

## 2. Authoritative server principle

The server owns:

- world persistence
- accepted chunk state
- gameplay state
- resource placement policy
- entities
- validation
- fallback

The client supplies computation results, not authority.

---

## 3. Threats

A modified client may:

- return arbitrary values
- return specially crafted oversized payloads
- lie about completion time
- respond to the wrong chunk
- replay an old result
- submit a late result after fallback
- intentionally timeout
- attempt to infer world seed / future world data
- attempt to create valuable blocks if the protocol permits block-state results
- send malformed compressed data

---

## 4. First-line defenses

Even before sophisticated result verification:

- bind jobs to player UUID
- bind result to `jobId`
- bind job to context/config hash
- enforce size limits
- enforce timeouts
- reject duplicates
- reject stale results
- reject unexpected coordinates
- never deserialize arbitrary Java objects
- use explicit structured codecs
- keep final feature/resource placement server-side

The Phase 3 protocol fixes a non-zero UUID job ID, protocol version `2`,
Minecraft-valid chunk coordinates, 256-byte UTF-8 identifier bounds, and an
immutable 32-byte SHA-256 context fingerprint. The server derives the
fingerprint from its own Minecraft version, dimension, seed, selected noise
settings, complete density/noise registries, height/config values, and relevant
debug flags; it is not accepted as a client-selected configuration.

The owning player UUID is stored in the server-side pending-job record keyed by
the server-issued job ID. It is intentionally not accepted as a client-asserted
identity field.

`PendingTerrainJobRegistry` enforces bounded total/per-owner admission and
atomically rejects wrong-owner, wrong-identity, duplicate, cancelled, expired,
and unknown results. Terminal tombstones are bounded; eviction changes a replay
classification from duplicate to unknown, never to accepted. Disconnect,
rejected re-handshake, datapack reload, timeout, and shutdown complete pending
attempts through vanilla fallback.

The result packet is capped at 787,456 bytes. Its decoder validates the density
count, RAW/DEFLATE tag, and encoded length before allocating the encoded bytes;
the encoded density section cannot exceed `count * 8` or the 786,432-byte
maximum. DEFLATE is accepted only if it terminates at exactly the declared raw
length without a preset dictionary, truncation, output expansion, or trailing
input. The reconstructed result rejects non-finite density or absolute density
above 1,000,000. Exact identity, request sample count, and cached `NoiseChunk`
geometry are checked before installation. These limits protect resource/state
boundaries, but do not prove that an otherwise valid density value is honest.

A packet atomically claims the pending job before decompression. Further copies
cannot consume decoder capacity. Decompression runs on one daemon executor with
a queue no larger than the configured in-flight limit; queue rejection and any
decode failure release the lease and trigger untouched local generation. A
timeout, disconnect, reload, or shutdown may still win while decode is running,
in which case the decoded result cannot be applied.

The server cache holds at most `0..256` raw density arrays (default `16`) and
clones them at both boundaries. Its key includes a lifecycle generation,
dimension/chunk, context fingerprint, noise settings, and complete density
geometry. Start, datapack reload, and shutdown clear it and advance the
generation. This bounds memory to roughly 12 MiB of density data by default and
192 MiB at the configured maximum for full-height entries, excluding ordinary
JVM/container overhead, and prevents a pre-reload response from repopulating a
new context.

Phase 4 prediction is disabled by default and cannot be enabled with a zero-size
cache. It observes only the sole accepted worker player's server-side movement,
uses that owner's connection exclusively, and shares the existing bounded
in-flight admission. Candidate coordinates must be Minecraft-valid and inside
the world border; the scheduler scans no more than eight chunks and never calls
into the chunk pipeline to create speculative state. A cached or in-flight
prediction is usable only after actual demand independently passes the complete
direct eligibility checks. Disconnect, reload, start, and shutdown clear or
invalidate prediction state. These constraints bound wasted work and prevent
one player from assigning speculative computation to another player's client;
they do not make returned density trustworthy.

Phase 5 adds separately opt-in random cell validation (`0..64`, default `0`).
After a response is fixed, the server uses `SecureRandom` to choose unique
interpolation cells and independently recreates vanilla `NoiseChunk` sampling
from authoritative state. Every density in a selected cell is compared by its
exact IEEE-754 bits before direct results or predictions may enter the cache or
be installed. A mismatch evicts the keyed cache value, preserves the untouched
local fallback, and quarantines that owner until disconnect; repeat hellos on
the same connection remain rejected.

---

## 5. Seed leakage

If the client must construct an equivalent vanilla worldgen state, it may need seed-derived information.

The implemented configuration now defaults to `SeedDisclosureMode.DENY`.
`WORLDGEN_ASSIST_REMOTE=true` by itself rejects worker registration and cannot
dispatch the seed-bearing request. The existing protocol runs only when the
operator separately sets
`WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE=trusted_raw`. This prevents accidental
leakage; it does not make that explicitly enabled mode confidential.

For a private PoC:

```text
seed disclosure is acceptable
```

For a public server:

```text
seed disclosure is a security/product concern
```

Format-1 context hashing includes the raw 64-bit seed in its preimage but logs
only the resulting SHA-256 digest. That digest is an equality identifier, not a
password hash or a confidentiality mechanism: a known configuration permits
offline guesses across candidate seeds. Do not claim that either this digest or
derived noise state automatically hides the seed safely. Seed inference must be
analyzed separately before use on a public server.

Generated Minecraft 26.2 source confirms that `RandomState` uses the seed to
derive the root positional factory and wire noise holders and blended terrain,
which ultimately produces reusable `ImprovedNoise` offsets and permutation
tables. Sending that initialized state instead of the numeric seed is therefore
not accepted as a confidentiality fix. Encryption whose key and plaintext are
held by the ordinary client process is rejected for the same reason.

`SEED_CONFIDENTIALITY.md` records the full source audit, threat definition,
rejected shortcuts, and the two retained research candidates: an attested
worker and a job-bounded server-generated leaf transcript. The transcript now
has a bounded server-local recorder/replayer, a dictionary binary codec, and
bit-exact tests under an unrelated dummy seed after codec round trip. An
unregistered draft job replaces the seed-committing fingerprint with a
server-random opaque context ID and structurally contains no raw-seed field.
The implemented server-only HMAC-SHA-256 primitive binds that ID and every
semantic job/transcript field under a random 256-bit key, with constant-time
verification. The synchronized seeded-leaf authority now owns server-lifetime
key/context state, rotates it on datapack reload, binds pending work to one
owner, consumes one exact fixed-size response claim, and cancels on disconnect,
reload, timeout, or stop. Issuance charges a conservative 100,000-entry per-
owner budget that is never refunded and survives disconnect/reload.
Live pending jobs are also limited to 100,000 transcript entries in aggregate.
Accept, cancel, expiry, and reload discard the full transcript immediately and
retain only fixed-size tombstone data for replay classification.

Every issuance is also charged before authorization against a 100,000-entry
world-local global ledger. Its fixed 72-byte record stores only maximum, used
count, sequence, and a SHA-256 integrity checksum. Same-directory atomic
replacement makes a successful charge durable before disclosure. Any pending-
file ambiguity, malformed record, configured-maximum mismatch, unavailable
atomic replacement, or write failure disables further issuance. This closes
the simple process-restart reset, but does not make multiple UUIDs one security
principal or prove the transcript limit cryptographically safe.
An adjacent fixed initialization marker also makes a missing ledger after
partial world-data loss fail closed. Deleting both files by a local administrator
remains outside the untrusted-client threat model.

The unregistered result draft contains a fixed claim rather than the raw seed or
the protocol-v2 fingerprint. It bounds count, bytes, timings, decompression,
and every decoded density. The server result gate authenticates and consumes a
claim before decompression, compares the declared count with the server-retained
job, and returns a transcript-free geometry/value projection only after
finite/range validation. Invalid shape or data after an authentic claim is
terminal, preventing repeated decompression attempts for one job. Failed owner,
context, or constant-time tag matching does not consume the rightful claim.
The resulting seed-free projection can enter the existing independent
`NoiseChunk` validator directly. That overload rejects disagreement with
server-owned `NoiseSettings` before sampling and uses server-owned `RandomState`
for bit-exact whole-cell comparison; it does not reconstruct or carry a client
seed identity.

Decode and sampling now pass through a bounded single-worker executor. Reload
advances an admission epoch before cancellation, and disconnect marks the UUID
inactive before owner cancellation. A result crossing either boundary cannot
become validated. Timeout/cancel completes the waiting caller promptly, but the
capacity permit remains held until actual computation exits. Only a
`VALIDATED` outcome can be converted to the generic density installation field.

This is still not a network path. The persistent budget does not aggregate
related accounts; 100,000 is a conservative engineering bound, not a
cryptographic leakage proof. Client isolation, registered payloads and live
scheduling/application policy, adaptive abuse controls, and cumulative seed-
recovery analysis remain unresolved, so public-server confidentiality stays
unchecked.

Owner budget bookkeeping is capped at 1,024 distinct UUIDs per server lifetime;
additional new owners are rejected rather than growing memory without bound.
This state bound does not make those UUIDs independent security principals or
solve multi-account aggregation.

The inactive orchestrator adds a server-local connection token so stale
disconnect callbacks cannot close a newer connection of the same UUID. It
serializes dispatch commitment with lifecycle changes, enforces a total deadline
from recording through result validation, and suppresses issuance after a
cancel/reload/disconnect. Invalid results quarantine the connection immediately;
three consecutive post-issuance timeouts also quarantine it. Reload preserves
that health state; reconnect does not refund disclosure budgets. A validated
offer is single-use and rechecks current connection/generation/deadline before
installation. The generation continuation calls vanilla once and clears the
field before advancing the downstream stage. These are local integration
controls, not a network adapter or a cryptographic confidentiality argument.
Its exact contracts and remaining gates are in `SEEDED_LEAF_ORCHESTRATION.md`.

---

## 6. Resource cheating

The implemented early design does not let the client decide ores/features.

If the returned format contains final block states, the server must treat it as untrusted.

A malicious result such as:

```text
stone -> diamond_ore
```

must not be accepted merely because the packet is well-formed.

The client returns only interpolated final density. Vanilla server code still
runs aquifer fluid selection, `OreVeinifier`, block writes, heightmap updates,
and fluid post-processing. A 7/7 fixed-seed digest comparison verifies that
remote-applied chunks matched independent local output, but that deterministic
development test is not adversarial validation. Phase 4 additionally matched
all three consumed speculative cache results against independent local output
(`3/3`), which verifies deterministic plumbing but carries the same limitation.

---

## 7. Validation approaches

### Full recomputation

Strong correctness, zero compute savings for that job.

Useful only for development tests.

### Random sample verification

Server recomputes selected sample points.

Advantages:

- low average server verification cost

Limitations:

- probabilistic
- difficult if one wrong point can influence a large downstream region
- needs unpredictable sample selection
- must match exact intermediate semantics

For a result with `N` cells, of which an attacker altered `m`, sampling `k`
unique cells has detection probability:

```text
1 - C(N - m, k) / C(N, k)
```

The standard Overworld geometry has `N = 768`; `k = 8` validates 1,024 density
values. Altering every cell is detected with certainty, while corruption
confined to one cell is detected with probability `8 / 768` (about 1.04%).
Operators must choose the cost/coverage tradeoff knowingly. Sampling does not
provide deterministic protection against sparse targeted corruption.

### Redundant client computation

Not preferred under the fairness model because it uses another player's resources.

Could be opt-in research mode.

### Cryptographic proof / verifiable computation

Out of scope initially.

---

## 8. Denial of service

A client should not be able to make the server:

- allocate unbounded memory
- keep unlimited pending futures
- repeatedly decompress huge payloads
- continuously fallback after accepting excessive jobs
- hold chunk generation indefinitely

Controls:

```text
max in-flight jobs/player
max bytes/job
timeout
backoff after repeated failure
temporary worker disable
```

The current PoC implements bounded compressed/decoded results, a bounded
single-thread decoder queue, a configurable `1..64` in-flight cap (default
`1`), a `0..256` entry cache (default `16`), and a `50..60,000` ms timeout
(default `2,000`). Timeout is enforced both at server tick end and by a daemon
watchdog, so a synchronous chunk wait cannot hold generation indefinitely.
The first exact-validation or malformed compressed-result failure disables that
worker for the remainder of its connection and cancels its other outstanding
jobs. Three consecutive timeouts do the same; any successfully decoded result
resets the timeout counter. Performance-based or gradually recovering backoff
remains future work.

---

## 9. Worker reputation / health

Later, maintain operational stats:

```text
success rate
timeout rate
invalid result rate
EWMA RTT
EWMA compute time
```

Use them to decide whether offload is beneficial.

Do not treat client-reported hardware capability as trusted.

---

## 10. Privacy / user expectation

Client compute should be explicit.

A future configuration UI should communicate:

```text
Enable client-assisted terrain generation
Maximum worker threads
Optional CPU utilization target
```

Default behavior should not silently consume large amounts of client CPU.

The fairness model should remain understandable:

> Your PC helps calculate terrain that your own player is likely to load.

---

## 11. Security milestone

Do not call the system suitable for an untrusted public server until:

- [x] bounded malformed result-payload fuzz smoke exists
- [x] size limits are enforced
- [x] timeout fallback and three-strike connection quarantine work
- [x] final resource-placement authority is server-side
- [x] replay/stale results are rejected
- [x] seed leakage is documented
- [x] raw-seed dispatch is fail-closed unless separately authorized
- [x] unregistered bounded transcript/job model contains no raw seed or seed-derived fingerprint field
- [x] unregistered job fields have a server-keyed HMAC-SHA-256 binding primitive
- [x] seeded-leaf pending claims are owner-bound, one-shot, expiring, and lifecycle-invalidated
- [x] the unregistered seeded-leaf result is seed-free, byte-bounded, and claimed before decompression
- [x] cancellation/reconnect/reload cannot refund disclosure and restart cannot reset the world-global ledger
- [x] validation strategy has measured detection properties
- [x] adversarial modified-client tests have been run

These checks do not override the milestone heading: the authorized protocol
still discloses the seed, while the
probabilistic sparse-corruption gap, and adaptive abuse controls still prevent
a public-server suitability claim.
