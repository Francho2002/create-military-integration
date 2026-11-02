# Compatibility profile

This candidate targets only the dependency profile below. Hashes are SHA-256 from dependency-hashes.properties and are checked by the build. They identify the local inputs used for static compilation; they are not a runtime compatibility guarantee.

| Component | Fixed version / artifact | SHA-256 |
|---|---|---|
| Minecraft | 1.21.1 | — |
| NeoForge | 21.1.265 | — |
| Create | 6.0.10 (`create-1.21.1-6.0.10.jar`) | `EF87FE5709F1BA1F5B8BB20A2925B5AFB4669E178FD6D8BF10C167759EEFE37A` |
| Sable | 2.0.6 (`sable-neoforge-1.21.1-2.0.6.jar`) | `9C199157843BF953A47DFF625FC5BB41C01A90538D35AD18E6996BBCB8A617C2` |
| Create Big Cannons | 5.11.7 (`createbigcannons-5.11.7+mc.1.21.1.jar`) | `EA017AB94637032D039899D88404838D173FA0E6286657CAE6A6C3AF459E87CA` |
| CBC Neo Warfare | 0.0.6v (`cbcmodernwarfare-0.0.6v+mc.1.21.1-neoforge.jar`) | `F8BBFCB4766F08A88C773A60AA154C4EB9704F974266E00F00569C765667A348` |
| CBC: Warium Projectiles | 1.2.0 (`cbcwariumce-1.2.0.jar`) | `A1A6D1F972F61E2CD5BD1A78AA19A858964F9A26E97A238962552073921DE85B` |
| Warium CE | 1.0.4 (`wariumce-1.0.4.jar`) | `AA0DD2FA8B5C8C2B4FFF40B4FE2189D94BC9D07240F43A5E6AF95D109B39E27E` |
| Create: Radars | 0.4.9.4 (`create-radars-0.4.9.4.jar`) | `4554359FEE389B1C255B4CEF23AC41E978B2A371CEC9E952185F4EC0A90EE930` |
| Better Targeting / Fire Control | 1.0.0.1 (`create-better-targeting-1.0.0.1.jar`) | `D31DCB676ADE66D47F8165001DE339DF195C9BB5B8E8A31877A246A91F70EF55` |
| FTB Teams | 2101.1.11 (`ftb-teams-neoforge-2101.1.11.jar`) | `E6BCD670493624E6906DF872A9C6AF4A33FE6405CAC57A32C59CD4CD5838C655` |
| FTB Chunks | 2101.1.22 (`ftb-chunks-neoforge-2101.1.22.jar`) | `4BA1B3352184FA7F6E2AA4BCA76EDD074867697D62344DFA50DCCCDB4DAB5217` |
| MineColonies | 1.1.1403-1.21.1 (`minecolonies-1.1.1403-1.21.1.jar`) | `C8D4373FFC8A76638C8EDF2BB6716F9209599032209C48D0D559B787F69A0561` |

The audited modpack profile also contains Aeronautics 1.3.2. CMI does not declare Aeronautics as a required mod dependency; systems mounted inside Sable remain blocked in this version.

## Installation shape

The mod metadata declares CMI dependencies on both sides. A future isolated test must use the same CMI artifact and the exact required dependency versions on client and server. This package is not a recommendation to replace an active or production installation; runtime validation remains pending. Do not install it into the public or active KxCraft environment based only on static checks.

The package intentionally excludes all third-party mod JARs. Obtain and install each dependency separately under its own terms.

For a future isolated test only, first stop the client/server cleanly and prepare a disposable test world. Place the same CMI artifact in the client and server `mods` directories; install the required mods at the exact versions listed above on both sides. Do not replace an active KxCraft or production artifact from this review package. The present candidate has not been tested at runtime.
