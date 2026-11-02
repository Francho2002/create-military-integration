# CMI 0.1.0-dev — experimental review candidate

This is a locally prepared experimental candidate for review, not a published release. Cycle 2L fixes the CIWS candidate-budget saturation path: eligible local radar contacts are ranked before the bounded solver phase, and one busy scan no longer discards all plans merely because it contains a ninth eligible candidate.

The existing physical-radar, ownership/IFF, motion-confidence, corridor, reservation, lock and native-fire checks remain in the path. A radar snapshot over the separate 128-track inspection budget still fails closed. The solver remains limited to eight ranked candidates per scan.

The eight-candidate cap is applied before solver work. If all selected candidates fail solution/safety checks or their reservations are occupied, a ninth eligible contact is not attempted in that sweep. It may be considered in a later sweep if it enters the selected window; persistent higher-ranked candidates can starve it. See KNOWN_LIMITATIONS.md.

Static validation does not establish that mixins apply or that a weapon fires or intercepts in Minecraft. See KNOWN_LIMITATIONS.md and VALIDATION.md. No Minecraft client, server or GameTest was run for this candidate.
