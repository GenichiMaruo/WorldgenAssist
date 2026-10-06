# Original biome selection depends on generation history

## Dev22 actual-coordinate witness (closed offline batch)

`saved-biome-diagnosis-20261006-223522-724` completes with no issues. Source is
the closed ordinary view32 dev22 parallel world from184431-462, not a new
runtime. The failure is the prewrite rejection of job
56864f6f-44e6-4008-aeab-cca3ec21050b at chunk-2013,3043.

One stopped E-drive region `r.-63.95.mca`,5,758,976bytes, is copied read-only.
Remote before/after and retained SHA256 are identical:
FAD6DC3CF97C16D18471607BD928C94377DA40666AA2990C0577A781DD6377C8.
No owned server process was live. Production dev22 JAR stays unchanged.

Expanded existing offline helper, using ORIGINAL doCreateBiomes via reflection,
compares4 histories of81 job centers/121 canonical BIOMES chunks with interleaved
original17x17 carver-biome queries. Original RegionFileStorage and biome codec
decode9 saved FULL chunks/all24 biome sections each. ALL13,824 quart biome names
are compared against each generated history:

| History | Generated quart(-8048,-16,12168) | Differences from saved values |
| --- | --- | --- |
| zx forward | snowy_plains | 1 |
| zx reverse | frozen_ocean | 0 |
| xz forward | snowy_plains | 1 |
| xz reverse | frozen_ocean | 0 |

Only this one voxel differs; all289 final carver biomes and ordered carver inputs
match. The saved voxel is frozen_ocean, in neighboring chunk-2012,3042 at
blockY-64. The original4 histories now produce two different full window digests.
This is an ACTUAL-coordinate standard-generation history witness, beyond the
old synthetic tie. It supports a biome-order explanation for the refusal.
The actual rejected client payload is unavailable: it does not prove which
digest/voxel that payload contained or its exact earlier worker history.
Saved palette compaction is also separate from unused LIVE palette framing.

## Source interpretation and next decision

### Exact target fitness confirmation (closed)

`biome-history-probe-20261006-224045-917`/session99626 exits0,zero issues.
No unchanged history/JUnit/MODbuild/game reruns; only the modified helper is
compiled. Same retained raw region hash remains unchanged. Original26.3
chunk-volume framing and original samplers produce target:
temperature-5197,humidity-2486,continentalness-1900,erosion-2267,depth8762,
weirdness4779. Original ParameterPoint.fitness over ALL preset entries gives
the same global minimum1,532,644 for exactly frozen_ocean and snowy_plains.
This confirms an equal-distance tie at the witnessed voxel. Together with
the4 original history outputs and source strict comparisons, it establishes
real history-sensitive selection here. The historical failed payload/thread
trace is still absent; that causal limit remains.

The earlier UNRUN paragraph below describes the now-completed fitness plan.
After both batches were terminal, the runner's dry plan alone was corrected
to report0 histories/1 fitness voxel for tie-fitness; its execution branch is
unchanged. Retained executed script hashes remain the authority for those runs.

Primary26.2 and exact26.3 Climate.RTree have a thread-local lastResult; search
uses strict `>` comparisons and can retain a previously visited equal-distance
leaf. Original26.3 MultiNoiseBiomeSource builds six original climate volumes
for4x96x4 quart sampling before selecting leaves. Do not replace hashing or
discard biome checks to conceal a real generated-value difference.

One additional narrowed offline `tie-fitness` mode is implemented,UNRUN:
same retained region, exact witnessed quart, original climate chunk-volume
sampling and original ParameterPoint.fitness over the preset. Zero unchanged
history reruns/JUnit/MODbuilds/games; helper compilation only. It will check
whether both observed biome names are genuinely equal global minima.

Production alternatives after the evidence:

- Bind client terrain computation to the server's exact immutable authoritative
  biome window when it becomes available. This preserves existing-world inputs
  and allows original surface/carver code on the client; no guessed normalization.
- A deterministic per-chunk tie policy on BOTH sides would be a deliberate
  generation-semantic change, requiring an explicit server policy/context version,
  new-world/boundary rules and tests. It must not silently claim vanilla parity.
- Independent BIOMES work admitted earlier than COMPLETE_TERRAIN remains the
  direct speed candidate: current early piggyback arrives too late. Any such
  path must resolve the real tie/authority issue before removing server work.

This is diagnosis,not a speedup or beta result. Original prewrite rejection,
peer/full audit/context/height/shaping/domain/empty-target/final-authority guards
remain. No production version or Mixin change in this offline work.
