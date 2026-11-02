# Known limitations

## Runtime validation

This candidate has not been loaded by Minecraft, a client, a server or GameTest in Cycle 2L. Static compilation and bytecode checks do not prove that the mixins apply or that radar scans, launchers, gunfire, collisions or interception work in a live world.

- Radar Spear launch, guided flight, callback delivery and structural impact remain pending in-game validation (Cycle 2E is still open).
- Radar Spear automatic acquisition and Warium launch remain pending in-game validation.
- CIWS targeting, mount movement, native firing and collision-driven neutralization remain pending in-game validation. CBC's void fireShot contract means a returned dispatch is not proof a projectile was created or an interception occurred.
- Predictive tracking can reject uncertain maneuvering targets; observed motion is not a guarantee of future trajectory.

## Explicitly blocked

- Radar and weapons ticking from inside Sable plots are blocked by the pinned Sable/Create/Warium/CBC contracts documented in IMPLEMENTATION_STATUS.md. No onboard weapon or radar behavior is claimed.
- Explosive damage to entities, fragmentation, ERA, blast forces and delayed destructive Warium procedures are not part of this candidate.
- The build verifies only the exact hashes in dependency-hashes.properties. Other versions or arbitrary modpacks are not accredited.

## Track UUID ambiguity

Create: Radars 0.4.9.4 stores tracks by entity UUID in a map, so its normal fixed implementation presents at most one track per UUID. CMI also deduplicates UUIDs and retains the first authorized observation. If a custom or future provider returns contradictory observations for the same UUID, CMI does not compare them for conflict; this limitation is not a guarantee for such providers.

## Eight-candidate solver budget

Each radar sweep deterministically retains at most eight eligible unique targets before calling `FiringSolver`: a still-eligible current target first, then time-to-closest-approach, distance, and UUID. Only plans that survive the solver, arc, and corridor checks reach reservation fallback. If those eight produce no usable plan, or every resulting plan is reserved by another mount, a ninth eligible radar contact is not solved or reservation-tested during that sweep. It can be considered on a later sweep if it enters the selected eight; persistent higher-ranked candidates can therefore starve it. The hard solver budget is bounded and deterministic, but it does not provide fairness across candidates beyond the selected window.

## Distribution status

The candidate package is for review only. It is not published, tagged, installed on client/server, or approved for production use. A GitHub pre-release requires separate review and authorization.
