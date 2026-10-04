# Client offload research — 26.3 (2026-10-04)

## Current evidence

V/W compare assistance against the same cooperative backend, not against a
different server scheduler. All three paired FULL and receipt measurements
improved about5–7%, while server CPU increased about5%. W doubled bounded
windows but did not establish a larger net improvement. X's completed pair
improved all three FULL/receipt/CPU repeats, median paired13.93%/14.04%/2.80%,
with p95 ticks3.66% higher. Its parity fixture exercised426 validated applications
through the new lane. Full cache coverage is~40-50%; the early cache.hit metric
alone omits queued-ready use. Separate W/X baselines differed; confirmation with
opposite condition order is next. See TEST_RESULTS_LATEST for artifact/run IDs.

These results do not yet meet the user's substantial acceleration goal. Keep
beta judgment pending and preserve original evidence for each candidate.
Y opposite-order confirmation has now completed: all three FULL/receipt faster,
median paired6.86%/7.08%, CPU6.31% higher in all three. CPU reduction did not
reproduce. Z removes futile candidate ordering when all admission slots are full,
targeting measured metadata overhead without reducing secret verification.

## Next decision after X completes

First inspect measured cache coverage, cancelled/unused requests, paired CPU,
FULL, receipt and p95. A scheduling change is useful only if more client work
replaces server computation with a net gain. Reuse the same exact JAR for a
configuration experiment; select affected tests/builds only for new source.

The other substantial target is surface/material work, not more validation
reduction. Existing V repeat1 JFR places material surface at29.25% of assisted
execution samples, terrain fill23.16%, manager4.27%, validation1.38% and ore
veins5.35%. These inclusive categories overlap and are not elapsed-time savings.

Verified generated26.3 sources: NoiseBasedChunkGenerator.buildTerrain runs
doFill, then buildSurface, then generateCarvers within one original noise chunk.
RandomState.surfaceSystem returns material.MaterialSystem, not the older
SurfaceSystem class. MaterialSystem.buildSurface depends on the current chunk's
filled height/columns, in-chunk height gradients, biome selection, material rules
and density samplers. It narrows bulk density sampling to the highest filled
section. Badlands and frozen-ocean extensions also modify columns. Treat these
dependencies as part of correctness, not just the rule's final return value.

OreVeinRule is a record with server-selected ore/raw/filler states and three
density functions. Its evaluator uses exact float fields plus the positional
"ore" random factory. A bounded rule-choice code could avoid this calculation
without accepting arbitrary block IDs, but it targets only a fraction of
material cost. Full float volumes would greatly enlarge current compressed
decision messages. Compare expected saved CPU and wire cost before choosing it.

A larger material offload needs an explicit work kind/version, bounded
server-defined decision domain, exact graph/biome/material context fingerprint,
private client calculation, independent secret verification and original server
application/fallback. The current fingerprint covers noise settings/density/noise
registries; it does not separately encode the biome source or material-rule
registry. Do not assume it authenticates a full surface calculation. Recomputing
the entire terrain/material chunk merely to verify a response would erase its
benefit. Prove a smaller independent verification method before implementing
this extension. No surface/ore offload has been implemented or tested here.
