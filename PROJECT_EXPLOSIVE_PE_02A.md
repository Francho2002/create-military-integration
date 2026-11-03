# CMI-PE-02A — Procedencia y autorización de Mk 82 / Mk 84

## Resultado

La implementación queda **estáticamente preparada y fail-closed**. Mk 82 (`projectexplosive:small_bomb`) y Mk 84 (`projectexplosive:big_bomb`) pueden observarse solamente desde tracks PROJECTILE vigentes de un radar físico Create: Radars. La observación puede informar el owner que Minecraft resuelva, su procedencia de `Projectile.getOwner()`, la relación IFF, protección y un estado de futura revisión. **No incorpora estas bombas a `CombatServices.projectile()`, no las adquiere el CIWS y no autoriza neutralización.**

La evidencia procede del árbol decompilado local de Project: Explosive 0.7.2-beta y del bytecode Minecraft/NeoForge 21.1.265 generado desde el artefacto fijado. La decompilación es aproximada. No se modificaron archivos o JARs de Project: Explosive ni Create: Radars.

## Rutas de creación y ownership

| Ruta | Código observado | Owner suministrado / resultado | Contexto CMI |
|---|---|---|---|
| Uso directo del ítem Mk 82/Mk 84 | `llio10o0i0i1lill1l1ll0il.java:95-107, 123-137` crea la entidad, posiciona, da movimiento y la añade al nivel. Los constructores de Mk 82/Mk 84 en `l0io0i1o00lll00olo0110ii.java:127-131` y `llooi0ilol1o11oiliioo1ll.java:119-123` llaman `setOwner(player)`. | `Projectile.getOwner()` puede resolver al jugador real mientras siga disponible en el `ServerLevel`. La clase base guarda el UUID del owner en NBT y vuelve a leerlo. | No se crea `ShotContext` CMI. Es evidencia de owner Minecraft, no un contexto de lanzamiento CMI. |
| Airstrike | `liol1i0011oll0oi11li0o10.java:49-68`: el helper calcula el punto, construye un Mk 82 con coordenadas y lo añade al nivel. No recibe ni asigna el jugador que activó el ítem. | Sin owner suministrado; `getOwner()` es null salvo que otro mecanismo posterior lo cambie. | Ausente. La identidad del usuario del airstrike no se puede inferir por proximidad o punto de caída. |
| Comando de bomba personalizada | `l0iil11llioi1oii0ooo111o.java:44-64`: selecciona Mk 82 o Mk 84 según escala, usa constructor por coordenadas y `addFreshEntity`. | Sin owner suministrado en esa ruta. | Ausente. La identidad del ejecutor no se transmite al proyectil. |
| Activación desde bloque/bomba colocada | `lo0io1l10io010llil00oioi.java:242-255`: las variantes SMALL/BIG construyen la entidad con coordenadas y la añaden al nivel. | Sin owner suministrado en esa ruta. | Ausente. El bloque activador no se convierte en propietario del proyectil. |
| Ojiva convencional generada por ICBM | `l11l1oi10liliioloilo0o10.java:787-803`: crea Mk 82/Mk 84 con posición y el flag de lanzamiento por bomba, configura estado de vuelo, añade la entidad y descarta el ICBM. No llama `setOwner` ni transfiere el owner del ICBM. | La bomba secundaria no hereda owner según el código observado. Aunque el misil padre tuviera owner, esa identidad no se propaga aquí. | Ausente en la bomba secundaria. El booleano del constructor no demuestra procedencia. |
| Guardado/restauración | Mk 82 llama `super.addAdditionalSaveData` en `l0io0i1o00lll00olo0110ii.java:159-160` y `super.readAdditionalSaveData` en `:205-206`; Mk 84 hace lo mismo en `llooi0ilol1o11oiliioo1ll.java:145-146, 189-190`. El bytecode base de `Projectile` escribe/lee `Owner` como UUID. | El UUID persiste. `Projectile.getOwner()` vuelve a consultar `ServerLevel.getEntity(ownerUUID)`; si no obtiene entidad, devuelve null. Con la API pública inspeccionada no se distingue owner originalmente ausente de owner no cargado/no resoluble. | CMI no añade un contexto de lanzamiento al restaurar PE. No se usa un UUID del NBT privado como sustituto del owner resuelto. |

**IFF:** para la observación, el owner reconocido es el UUID de la entidad devuelta por `Projectile.getOwner()`. CMI evalúa `CombatServices.relation` con esa identidad. FRIEND/NEUTRAL/UNKNOWN no generan futura elegibilidad; FRIEND conserva precedencia sobre hostilidad explícita. Owner null o no resoluble se informa `OWNER_UNKNOWN_OR_UNLOADED` y queda UNKNOWN. Las rutas sin `setOwner` no se reparan mediante una inferencia.

**Procedencia:** un owner Minecraft resuelto identifica el vínculo `Projectile` → owner que expone la clase base. No acredita que CMI iniciara ese lanzamiento ni permite distinguir desde el adaptador qué ruta PE creó la bomba. `cmiLaunchContextVerified` es false en el adaptador; la elegibilidad futura solo etiqueta una observación que podría revisarse, nunca concede adquisición o fuego. No se confía en `cmiOwner` arbitrario ni en destino, bloque cercano, jugador cercano o posición Sable.

## Adaptador de observación

`ProjectExplosiveBombObservation.inspect(...)` exige una `StationaryRadarBlockEntity` o `RadarBearingBlockEntity` cargada en el mismo `ServerLevel`, activa y con `scanProjectiles` habilitado. Lee únicamente `IRadar.getTracks()`, limita el snapshot a 128, exige categoría PROJECTILE y la validación de UUID/frescura ya utilizada por CIWS (`now` o `now - 1`), y resuelve cada UUID directamente con `ServerLevel.getEntity`.

Solo después de resolver la entidad comprueba su Registry ID exacto, posición/velocidad del track, owner actual, IFF y protección. No consulta `CombatServices.contacts()`, `getAllEntities()` ni `Sensor.scan()`. El adaptador no está conectado al selector CIWS ni al fuego. `futureEligible=true` significa que identidad, track, owner, ENEMY y protección permiten una revisión futura; el resultado continúa con `acquisitionAuthorized=false`, `neutralizationAuthorized=false` y rechazo `AWAITING_PE_02B_AUTHORIZATION`.

## Política destructiva separada

`NeutralizationPolicy` es ahora la autoridad central aparte de la clasificación:

- Para namespace `projectexplosive`, exige `Classification.neutralizable()`. Todos los tipos PE reconocidos siguen devolviendo false; los IDs PE desconocidos también fallan cerrados.
- Para los demás namespaces, conserva exactamente el booleano de la whitelist anterior `CombatServices.projectile`, manteniendo CBC/Warium existentes.
- `CombatServices.neutralize` mantiene sus gates anteriores de contacto reconocido, `canEngage`/IFF y tipo ENTITY, y además exige `CombatServices.neutralizable` sobre la entidad viva resuelta antes de `discard()`.
- El hook de colisión CBC de disparos gestionados usa la autorización central. Si el objetivo es de cualquier Registry ID PE, cancela el impacto gestionado; no permite que la mera clasificación habilite la ruta.
- El handler de explosión gestionada excluye entidades PE del daño vanilla y usa la misma autorización central para neutralización. No cambia explosiones PE nativas ni explosiones de disparos no gestionados.

No cambió `CombatServices.projectile()`, `CiwsCoordinator`, el lock, filtros radar, límites, balística ni firing. Ningún ID PE fue añadido a la whitelist de proyectiles.

## Impacto, eliminación y riesgos

Mk 82/Mk 84 derivan de `ThrowableProjectile`. Sus `onHit(HitResult)` están en `l0io0i1o00lll00olo0110ii.java:1226-1244` y `llooi0ilol1o11oiliioo1ll.java:1151-1169`; la lógica de detonación marca `HasExploded` y usa el procedimiento propio de PE con `ExplosionInteraction.NONE` (Mk 82 `:812-835`, Mk 84 `:809-832`). No es una explosión vanilla normal controlada por CMI. Los dos tipos pueden crear daños/efectos adicionales y actividad de entidad después de iniciar la detonación.

Sus `remove(RemovalReason)` están en Mk 82 `:1262-1272` y Mk 84 `:1206-1216`. El código limpia tickets y delega a `super.remove`; no llama a `onHit` ni al procedimiento de explosión. Por tanto, el código examinado no muestra que un `discard()` previo al impacto, por sí mismo, detone la bomba. Esto **no demuestra** que retirar una entidad después de que `HasExploded` sea true cancele todo efecto ya iniciado, tickets, entidades secundarias o trabajo persistido. Esa fase continúa sin un contrato de cancelación acreditado.

El bytecode de `Projectile` decide `canHitEntity` usando `Entity.canBeHitByProjectile`, que a su vez depende de `isPickable`; `Projectile.isPickable` consulta el tag `REDIRECTABLE_PROJECTILE`. `isAttackable()` de Mk 82 devuelve false (`:1258-1260`) y no es la comprobación del CBC auditada. La pertenencia PE a tags dinámicos finales del juego no se probó aquí. En disparos CMI gestionados, el nuevo mixin deniega de todos modos la colisión contra PE. El CBC no gestionado conserva su comportamiento nativo; este ciclo no declara interoperabilidad física.

El loop CBC fijado recopila candidatos de colisión y ejecuta callbacks `onHitEntity` posteriormente. La Mixin CMI gatea por `cmiManagedShot` y el permiso central; el nuevo gate PE cancela el callback gestionado antes de una neutralización. No hay prueba runtime de colisión, de la respuesta nativa de CBC a toda entidad PE o de cancelación de trabajos PE ya iniciados.

## Estado por tipo

| Entity ID | Reconocido | Observación física futura | Adquisición automática | Neutralización CMI |
|---|---:|---:|---:|---:|
| `projectexplosive:small_bomb` (Mk 82) | Sí | Condicionada a track reciente, owner resuelto ENEMY y no protegido | BLOQUEADA | BLOQUEADA |
| `projectexplosive:big_bomb` (Mk 84) | Sí | Condicionada a track reciente, owner resuelto ENEMY y no protegido | BLOQUEADA | BLOQUEADA |
| `projectexplosive:fire_bomb` | Sí, catálogo PE-01 | Fuera del adaptador convencional | BLOQUEADA | BLOQUEADA |
| `projectexplosive:nuclear_bomb` (Fat Man) | Sí, catálogo PE-01 | Fuera del adaptador convencional | BLOQUEADA | BLOQUEADA |
| `projectexplosive:icbm` | Sí, catálogo PE-01 | Fuera del adaptador convencional | BLOQUEADA | BLOQUEADA |
| Booster, estelas, `multi_block` | Sí, catálogo PE-01 | Excluidos/no son Mk 82/Mk 84 | BLOQUEADA | BLOQUEADA |

## Evidencia y nivel de certeza

| Afirmación | Evidencia | Certeza |
|---|---|---|
| IDs y clases exactas | Decomp PE `l1loi01l110i1o10ooiliol0.java:10-46` | DEMOSTRADO por registro decompilado; la fuente es reconstruida |
| Uso directo asigna owner player | Decomp PE `llio10o0i0i1lill1l1ll0il.java:123-137`, constructores citados arriba | DEMOSTRADO por call sites decompilados |
| Airstrike, custom command, bloque e ICBM-child no suministran owner | Archivos/rangos de creación en matriz | DEMOSTRADO para las rutas leídas; un mecanismo externo adicional no está descartado |
| Owner UUID se serializa y resolución requiere entidad disponible | `javap -p -c net.minecraft.world.entity.projectile.Projectile` del artefacto NeoForge 21.1.265 | DEMOSTRADO estáticamente por bytecode fijado |
| Track UUID, posición, velocidad, tiempo y tipo | Create: Radars 0.4.9.4, `RadarTrack(Entity)` y métodos de acceso, inspeccionados con `javap` | DEMOSTRADO estáticamente por bytecode fijado |
| No detona desde `remove()` previo al impacto | `remove(RemovalReason)` de ambos tipos no llama al procedimiento; `onHit` contiene la ruta de impacto | DEMOSTRADO para estos métodos; cancelación de efectos ya iniciados sigue NO VERIFICADA |
| CBC puede neutralizar Mk 82/Mk 84 | Filter y tags dinámicos finales además de comportamiento runtime | NO VERIFICADO; CMI no habilita esa ruta |

## Validación de este ciclo

`clean build --offline` terminó con BUILD SUCCESSFUL, Java 21. La regresión PE existente fue ampliada; las 15 suites JavaExec del proyecto pasaron. Los 16 verificadores estáticos pasaron, incluyendo `Verify-ProjectExplosiveCompatibility.ps1` con 52 comprobaciones. SHA-256 del artefacto binario: `93DC6BA5EB744A6C565A9C386BEADD12E88181A8C6F168917877BAE11C6B1CB9`; SHA-256 del JAR de fuentes: `C10D54A71980551F4AAA4A7AE88E986A27D089BE74CECDF3816FB93E392C8A39`. El contenido y los resultados detallados se registran en `VALIDATION.md`.

No se inició Minecraft, cliente, servidor ni GameTest. Detección efectiva, IFF runtime, impacto CBC, comportamiento PE tras `discard()` y cualquier neutralización permanecen **PENDIENTES DE VALIDACIÓN EN JUEGO**; la neutralización PE continúa **BLOQUEADA** por política hasta otra revisión aprobada.
