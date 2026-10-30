# Validación realizada

## Ciclo 2E.2 — reanudación detenida antes del runtime

- HEAD verificado en local y `origin/main`: `d46a7cb0d4783a1160a284fbcda372b0a0471154`. Corresponde al mismo árbol de código del hash de referencia `0d736ea701724765a7590fc0deee7dd518c6d0f9`; la diferencia se debe al cambio previo del mensaje de commit a español.
- El PID actual 6016 se identificó por ejecutable y argumentos como servidor `CMI-Cycle-2E-Test`/NeoForge 21.1.265. El proceso sigue activo; no se confió solo en el PID antiguo. No hay consola normal accesible entre las ventanas visibles y `enable-rcon=false`. No se intentó forzar su cierre.
- JAR todavía presente en el servidor: `server/mods/create-military-integration-0.1.0-dev.jar`, SHA-256 `5FB3397CAB1760F5367A5F0F8701949D9BCD4F31B3617060FE2E2F0FEE431333`. Artefacto del árbol actual: `build/libs/create-military-integration-0.1.0-dev.jar`, SHA-256 `CF00AA0E5E9B9249B508C0CF1264B2ACC615F440A764D80B2C4A0C0180F84AA3`. No se copió sobre el servidor activo.

### ACREDITADO EN JUEGO

- De la ejecución previa de 2E: NeoForge arrancó, el servidor llegó a `Done`, el cliente entró, CMI apareció cargado y Sable pudo ensamblarse.
- En esta reanudación no se cargó el artefacto actualizado, no se ejecutaron comandos CMI y no se lanzó Radar Spear. No hay evidencia runtime nueva de propiedad, relación `ENEMY`, guiado, colisión o daño.

### ACREDITADO ESTÁTICAMENTE

- Artefacto SHA-256 actual coincide con el que se construyó tras las nueve regresiones. `clean build`, nueve regresiones Java y los ocho verificadores pasaron para este mismo árbol de código; el único cambio posterior fue el mensaje del commit.
- IFF offline y comandos de ownership están descritos en la sección de 2E.1.

### PENDIENTE

- Detener normalmente el servidor de prueba con `stop` desde su consola. En este equipo no hay ventana/consola visible ni RCON habilitado para hacerlo desde la sesión actual.
- Solo después de detenerlo: reemplazar el JAR de `CMI-Cycle-2E-Test` por el artefacto actual, confirmar el hash y reiniciar el servidor aislado.
- Luego ejecutar `/cmi ships`, `/cmi shipowner <shipUuid> <ownerUuid>`, `/cmi hostileuuid <ownerUuid> true` y `/cmi relation <shipUuid>`. No disparar hasta confirmar `owner`, `platform` y `ENEMY`. Después siguen pendientes Fase A, B, C1 y C2 con los criterios de más abajo.

## Ciclo 2E.1 — asignación CMI de owner Sable y hostilidad por UUID

### Inspección y comandos

- HEAD de partida confirmado: `83d97789d26c90dc3cfc0992ab99a630ef531b70` (`main`). `/cmi claim` conserva su semántica actual de buscar un block entity en el mundo principal; no debe usarse sobre coordenadas locales de un plot después de ensamblar.
- Se inspeccionó el código fijado de Better Targeting 1.0.0.1. `Iff.playerId(level, allegiance, uuid)` compara UUID y llama a `FtbCompat.teamFriend(owner, id)`; no consulta una instancia `Player` online. FTB usa `arePlayersInSameTeam(UUID, UUID)`, `getTeamForPlayerID(UUID)` y `isAlly(UUID)`. Así que un owner offline es válido en esta ruta. CMI conserva la prioridad: owner igual o aliado IFF/FTB produce FRIEND antes de consultar hostilidad explícita.
- Procedimiento runtime tras reiniciar el servidor de prueba:
  1. Ensamblar la nave; ejecutar `/cmi ships` y copiar el UUID que aparece para la Sable cargada.
  2. Asignar el owner CMI con `/cmi shipowner <shipUuid> <ownerUuid>`. El UUID debe identificar al jugador que representa al dueño de la nave; puede estar offline. El comando rechaza una nave que no esté actualmente cargada y conserva el IFF CMI existente.
  3. Desde el jugador tirador ejecutar `/cmi hostileuuid <ownerUuid> true`. Para retirar hostilidad usar `false`. Si FTB/IFF considera amigos al tirador y al owner, FRIEND sigue prevaleciendo.
  4. Ejecutar `/cmi relation <shipUuid>` desde el tirador. Continuar solo si muestra `owner` esperado, `platform` igual al UUID Sable y `relation to you: ENEMY`. `unowned`, `NEUTRAL`, `FRIEND` o `UNKNOWN` bloquean la prueba ofensiva.
  5. Configurar hardpoint y Ordinance Controller como antes, establecer `direct_sable` y seguir Fase A más abajo. No es necesario reclamar un cofre antes del ensamblado.
- `/cmi ships` es de solo lectura. `/cmi relation` es de solo lectura. `/cmi shipowner` modifica únicamente `CmiData` de CMI. `/cmi hostileuuid` guarda el par de UUID con la API persistente existente. El comando `/cmi hostile <player> <enabled>` existente se conserva.

### Estado de esta validación

- El PID 6016 se verificó como el servidor aislado `CMI-Cycle-2E-Test`, ejecutado por su JDK bundled desde el directorio de ese perfil. Sigue activo y no hay consola de servidor accesible desde esta sesión. Se dejó intacto; no se forzó `kill`, no se reemplazó el JAR mientras estaba ejecutándose y no se tocó producción.
- Por este motivo no se cargó el nuevo artefacto en runtime ni se reanudó Fase A. No se configuró una relación ENEMY y no hubo nuevas líneas `[CMI-RadarSpear]`. Se requiere detener normalmente y reiniciar manualmente el servidor aislado para que cargue el JAR recién construido.
- `shipOwnershipRegression` cubre bind solo a UUID incluido en la lista de Sables actualmente cargados, persistencia `CmiData` NBT, `Actor.platform`, update del owner, preservación de IFF, rechazo de Sable ausente/owner nulo, hostilidad true/false, owner ausente => UNKNOWN, mismo owner => FRIEND y amigo IFF/FTB por encima de hostilidad. También comprueba estáticamente que `/cmi claim` conserva su consulta al mundo principal y que los nuevos comandos están registrados.
- `clean build` con Java 21 y dependencias fijadas: **SUCCESS**. `check` ejecutó las nueve regresiones JavaExec y pasó, incluida `shipOwnershipRegression`.
- Pasaron los ocho verificadores: `Verify-Artifact` (65 clases Java 21; 23 mixins registrados; sin clases/JAR de terceros empaquetados), `Verify-MixinTargets` (27 clases objetivo), `Verify-RadarSpearBytecode`, `Verify-RadarSpearArrowCollision`, `Verify-SableBytecode`, `Verify-Geometry`, `Verify-GuidanceBytecode` y `Verify-RadarSpearArea`.
- SHA-256 del artefacto `build/libs/create-military-integration-0.1.0-dev.jar`: `CF00AA0E5E9B9249B508C0CF1264B2ACC615F440A764D80B2C4A0C0180F84AA3`.
- **ACREDITADO EN JUEGO:** no hay evidencia runtime nueva para los comandos ni para Radar Spear.
- **ACREDITADO ESTÁTICAMENTE:** API de IFF para UUID offline inspeccionada; build, nueve regresiones Java y ocho verificadores aprobados.
- **PENDIENTE:** reiniciar el servidor de prueba, validar comandos con una Sable cargada, confirmar `relation=ENEMY` y ejecutar Fase A. El Ciclo 2E continúa abierto.

## Ciclo 2E — escenario y primera ejecución runtime

### Estado

- HEAD inicial: `9536b794028204c4d9ea569d3efc1c0743e9fcc4` (`main`). Se usó solo el entorno aislado `CMI-Cycle-2E-Test`; no se modificaron carpetas de producción ni JAR de terceros.
- El primer arranque de diagnóstico reveló que el `@Shadow calculateTurningSpeed` no podía resolverse en la clase objetivo porque el método se declara en su clase padre. Se reemplazó por un invoker Mixin dirigido a `GuidanceBlockEntity`. Tras `clean build`, las ocho regresiones y los verificadores, el servidor arrancó en el siguiente intento. Esto corrige un error de carga, pero no prueba el guiado en runtime.
- Se añadieron logs de servidor de baja frecuencia en `RadarSpearDiagnostics`. Sus prefijos `[CMI-RadarSpear]` aparecen solo en la ruta CMI gestionada; los lanzamientos nativos no se instrumentan. Los estados de contacto/primer obstáculo se registran cuando cambian, no cada tick.
- El servidor local inició y llegó a `Done (14.993s)`; el cliente entró al mundo. CMI figura en el listado de mods y no aparece un error fatal de carga/mixin de CMI en el log de este arranque. No hay una línea de log que demuestre individualmente que cada mixin haya sido aplicado.
- La nave de prueba (`/fill 32 140 8 36 142 12 minecraft:stone`, cofre en `34 142 10`, `/sable assemble area 32 140 8 36 142 12`) se ensambló correctamente con 75 bloques.
- La Fase A no empezó: `/cmi claim 34 142 10`, después del ensamblado, respondió `No block entity at position`. El comando CMI busca el block entity con `ServerLevel.getBlockEntity(pos)` y esa posición de plot no se resuelve desde el mundo principal. Solo había un jugador conectado; `/cmi hostile <player> true` requiere un segundo jugador online. No se disparó ningún misil y no se observó ninguna línea `[CMI-RadarSpear]`.
- En el primer intento de entrar, Distant Horizons generó `IllegalStateException: Chunky is not loaded` durante el procesamiento de paquetes de chunks. Se deshabilitó `Chunky-NeoForge-1.4.23.jar` solo en `disabled-client-mods` de la copia cliente aislada; el ingreso siguiente funcionó. El servidor de prueba conserva Chunky. Esta modificación de diagnóstico no afecta el perfil normal.
- Se ejecutó `/save-all flush` y el log confirmó `Saved the game` y que todos los chunks se guardaron. El servidor de prueba sigue ejecutándose: la consola de su proceso no está accesible desde esta sesión y el comando de consola `stop` no está expuesto al cliente; no se forzó la terminación del proceso.
- Por lo tanto, no hay todavía evidencia runtime de captura, guiado, `sable_first_hit`, callback, daño, explosión duplicada, rotación ni rechazo IFF. El Ciclo 2E permanece abierto.
- El artefacto verificado se construyó en `build/libs/create-military-integration-0.1.0-dev.jar`; SHA-256: `5FB3397CAB1760F5367A5F0F8701949D9BCD4F31B3617060FE2E2F0FEE431333`.

### Preparación manual

Usar el mundo dedicado `CMI-Cycle-2E-Test` con NeoForge 21.1.265, Create 6.0.10, Warium CE 1.0.4, Sable 2.0.6, FTB Teams/Chunks y las dependencias fijadas. Mantener al tirador y al blanco cerca para cargar chunks, fuera de claims FTB y colonias MineColonies. No subir pasajeros ni tripulación a la nave blanco. Los comandos `/cmi ships`, `/cmi shipowner` y `/cmi hostileuuid` documentados en Ciclo 2E.1 resuelven la asignación de owner sin reclamar un block entity del plot.

1. Crear una aeronave Sable pequeña, estacionaria y sin tripulantes. En la cara que recibirá el impacto, poner un único bloque `minecraft:stone` expuesto como superficie de colisión.
2. Ejecutar `/cmi ships`, copiar el UUID de la nave y asignar owner con `/cmi shipowner <shipUuid> <ownerUuid>`. Si el owner no está online, su UUID sigue siendo válido para IFF; el propietario y el tirador deben ser identidades distintas para el caso positivo.
3. El tirador establece `/cmi hostileuuid <ownerUuid> true` y verifica `/cmi relation <shipUuid>`. Exigir owner, platform y `ENEMY`; no continuar si el resultado es friend, neutral, unknown o unowned.
4. Colocar/cargar un Radar Spear Missile Hardpoint y un Ordinance Controller de Warium. Configurar el controlador para designar un punto dentro de la caja de la aeronave blanco. `HardpointTriggerTickProcedure` copia `TargetX/Y/Z` del controlador seleccionado al hardpoint; confirmar que la designación permanece válida y que la nave está cargada.
5. El propietario del lanzador ejecuta `/cmi radarspearprofile <x> <y> <z> direct_sable` sobre el hardpoint. Cargar el misil según el montaje normal de Warium y disparar con el pulso que requiera ese hardpoint.
6. Seguir `logs/latest.log` con `Select-String -Path logs/latest.log -Pattern '\[CMI-RadarSpear\]'`. Conservar el extracto y apuntar el bloque inicial/final; detener la prueba en el primer evento de rechazo o ausencia de evento esperado.

### Fase A — `direct_sable` (obligatoria antes de continuar)

Orden esperado para un impacto válido: `launch_captured` → `impact_armed` → `guidance_active` → `guidance_target_acquired` → transición `sable_first_hit` a `TARGET` → `arrow_collision_world_endpoint status=resolved` → `on_hit_block native_warium_cancelled=true` → `direct_sable_begin` → `impact_confirmed` → `impact_consumed` → un `block_changed` (`minecraft:stone` a `minecraft:cobblestone`, count=1) → `missile_discarded`.

Comparar en el registro `arrow_collision_world_endpoint` (local y mundial), `sable_first_hit` (UUID abreviado, world, plot, bloque y cara), `on_hit_block` y `impact_confirmed`. Deben coincidir la nave, bloque plot y cara; el punto mundial/plot debe corresponder a la pose estacionaria. Comprobar que cambió únicamente el bloque frontal previsto, que el misil desapareció y que durante los siguientes 60 ticks no aparecen blast, cráter, proyectiles HV ni efectos diferidos Warium. Si falla cualquier paso, no configurar `explosive_sable`; guardar log/captura y detenerse.

### Fase B — `explosive_sable` (solo si A completa toda la cadena)

Ampliar la cara de prueba con al menos dos bloques elegibles expuestos dentro de 3 bloques mundiales, usando `stone`, `stone_bricks` y/o hormigón reforzado Warium. Dejar el entorno fuera de claims/colonias, sin pasajeros y sin otros Sables dentro de la envolvente. Restaurar el hardpoint y ejecutar `/cmi radarspearprofile <x> <y> <z> explosive_sable`; volver a designar el interior de la nave y disparar.

Exigir `impact_confirmed` con el Sable correcto, `area_candidates` con más de un elegible, `area_approved` con al menos dos aprobados, al menos dos `block_changed` distintos (una modificación por bloque) y `missile_discarded`. Observar el impacto y los 60 ticks siguientes: no debe haber explosión vanilla destructiva, fragmentos HV ni efecto Warium diferido. Si el registro se separa de la fase A en `area_candidates`, `area_approved` o revalidación, guardar el primer rechazo y detener la prueba.

### Fase C — nave rotada y caso negativo

- Repetir primero `direct_sable` con la nave blanco rotada y estacionaria. El callback mundial debe coincidir con el bloque/cara local del plot después de aplicar la pose; comprobar que el bloque equivalente del terreno principal no cambia. Solo después puede repetirse el perfil de área.
- Para el negativo, poner tirador y dueño blanco en el mismo equipo de FTB Teams e intentar otro lanzamiento al mismo blanco. Debe rechazarse como no atacable, no crearse un misil gestionado y no cambiarse ningún bloque. Conservar la línea `launch_rejected` y confirmar el estado previo/posterior del casco.

### Instrumentación añadida y límites

- Los eventos incluyen `launch_rejected`, `launch_captured`, `impact_armed`, `guidance_active`, cambios de contacto/primer obstáculo, `arrow_collision_world_endpoint`, `on_hit_block`, comienzos `direct_sable`/`explosive_sable`, `impact_confirmed`, `area_candidates`, `area_approved`, `impact_consumed`, `block_changed`, rechazos por fase y `missile_discarded`.
- La línea `native_warium_cancelled=true` acredita que CMI interceptó la entrada al callback; la ausencia de explosión/fragments debe observarse durante la ejecución y no se infiere solo del log.
- La ejecución actual confirmó solo arranque, entrada del cliente y ensamblado Sable; no se alcanzó la prueba de lanzamiento. El daño y las demás fases quedan **PENDIENTES DE VALIDACIÓN EN JUEGO**.

### Validación estática de Ciclo 2E

- `clean build` con Java 21 y dependencias fijadas terminó en `BUILD SUCCESSFUL`; las ocho regresiones Java pasaron. Se actualizó la comprobación fuente de `radarSpearModeRegression` para admitir el rechazo gestionado con diagnóstico.
- Pasaron los ocho verificadores: `Verify-Artifact.ps1` (64 clases Java 21, 23 mixins), `Verify-MixinTargets.ps1` (27 clases objetivo), `Verify-RadarSpearBytecode.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-SableBytecode.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1` y `Verify-RadarSpearArea.ps1`.
- Los verificadores comprueban estructura, referencias y bytecode fijado. La sesión real no produjo un error fatal CMI, pero no confirmó la aplicación de cada mixin ni los resultados observables de las fases A-C.

## Ciclo 2D.1 — cardinalidad X/Y/Z y límites del área

- Confirmado el HEAD de partida `1dca4fb914cd387a32897fc6db172b71f0cc525c`; se preservaron los archivos locales no seguidos `tools/cycle2d.init.gradle` y `tools/cycle2d/`.
- Se detectó que la fórmula anterior usaba `z1-y0+1` para la dimensión Z. El conteo de X=10..12, Y=60..64, Z=200..207 daba 2.220 en vez de 120. Se sustituyó por un helper con anchos inclusivos por eje, resta en `long`, rechazo de rangos invertidos y multiplicación comprobada.
- La suite existente `radarSpearBlastGeometryRegression` incorpora los límites 120, cero, negativos, una celda, debajo/en/encima de 4.096, rangos invertidos por eje y overflow. La misma suite comprueba que posiciones de bloque en la frontera X=15/16 se traduzcan a chunks distintos y mantiene casos de transformación, radio mundial y superset de enumeración. El total permanece en ocho suites Java.
- Verificación estática de geometría: `worldSphereBoundsInPlot` genera el superset de enumeración a partir de las esquinas del cubo mundial; `intersectsWorldSphere` decide la intersección según distancia en el mundo; la conversión mundo↔plot emplea la pose actual de Sable; y `PlotView` convierte `ChunkPos` con `toLocal` exclusivamente para `getChunk`. El radio mundial de 3 bloques y el modelo de daño no cambian.
- `clean build` terminó con `BUILD SUCCESSFUL` usando Gradle Wrapper 9.2.1, dependencias offline fijadas y Java 21. Pasaron las ocho suites: `geometryIntersectionRegression`, `guidanceModeRegression`, `managedGuidanceNbtRegression`, `radarSpearBlastGeometryRegression`, `radarSpearImpactRegression`, `radarSpearModeRegression`, `radarSpearStructuralRegression` y `shotContextRegression`.
- Pasaron los ocho verificadores: `Verify-Artifact.ps1` (62 clases Java 21 y 22 mixins), `Verify-MixinTargets.ps1` (26 clases objetivo), `Verify-RadarSpearBytecode.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-SableBytecode.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1` y `Verify-RadarSpearArea.ps1`, contra los JAR locales fijados.
- La nueva regresión produce exactamente 120 para X=10..12, Y=60..64, Z=200..207; cubre rangos negativos y centrados en cero, cardinalidades de 1 y 4.095/4.096/4.097, límites invertidos en cada eje y overflow de `long`. La prueba de chunk usa `BlockPos` en X=15/16 y también cubre coordenadas negativas.
- Estas comprobaciones prueban el cálculo aislado, las firmas y las rutas estáticas; no aplican mixins ni ejecutan una colisión, detonación, chunk de plot, actualización física, Minecraft, servidor o GameTest.

## Ciclo 2D — perfil de área estructural Radar Spear

- Confirmado el HEAD base `acb6e2f173598d0646e1be15f5e6d763fa4ea277`. Se preservaron sin tocar los archivos locales no seguidos `tools/cycle2d.init.gradle` y `tools/cycle2d/`; no se ejecutó ninguna tarea `run*`, servidor o GameTest.
- Se verificó Warium CE 1.0.4 del perfil cliente y servidor: ambos JAR tienen SHA-256 `AA0DD2FA8B5C8C2B4FFF40B4FE2189D94BC9D07240F43A5E6AF95D109B39E27E`. `Verify-RadarSpearArea.ps1` contrasta descriptores de `TankFireProjectileHitsBlockProcedure`, `SmallExplosionProcedure` y `HeavyCrackProcedureProcedure`, además de rutas nativas y ausencia de llamadas explosivas/diferidas en el handler CMI.
- `clean build` con Java 21, Gradle fijado y dependencias verificadas terminó correctamente. `check` ejecutó ocho suites JavaExec: las siete existentes más `radarSpearBlastGeometryRegression`. Se ampliaron las regresiones de autorización NBT y selección de perfil; la nueva prueba comprueba una pose rotada con escala, límites del radio en mundo, bloques a ambos lados de una frontera de chunk y rechazo de entradas inválidas.
- Pasaron `Verify-Artifact.ps1`, `Verify-MixinTargets.ps1`, `Verify-RadarSpearBytecode.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-SableBytecode.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1` y `Verify-RadarSpearArea.ps1` contra los JAR locales fijados. No se añadieron mixins nuevos; el perfil selecciona la ruta desde el callback ya existente.
- El bytecode demuestra que el impacto Warium llama `TankFireProjectileHitsBlockProcedure`, que difiere 25 proyectiles y `SmallExplosionProcedure`; este último llama `Level.explode` con fuerza 3/TNT y 7/NONE (con una llamada TNT adicional en agua) y crea 75 proyectiles HV. El código CMI del perfil explosivo no llama a esos procedimientos ni a `Level.explode`, y solo incluye `LevelPlot.toLocal/getChunk`, `Level.setBlock(...,3)` y `Level.destroyBlock(...,false)` tras comprobaciones y evento de rotura.
- La geometría aislada y el análisis de bytecode no ejecutan `AbstractArrow.tick`, `BlockGetterMixin.clip`, Sable, chunks, listeners, FTB, MineColonies, IFF vivo ni actualizaciones físicas. La aplicación/prioridad de mixins, el hecho de que la colisión alcance el callback en una aeronave móvil/rotada, la magnitud visual del radio, la degradación real de bloques en plot, crew/territorio y las reacciones físicas tras daño siguen **PENDIENTES DE VALIDACIÓN EN JUEGO**. No se declara equivalencia con la explosión Warium.

## Ciclo 2C.1

- `clean build` con Java 21 y dependencias fijadas, y las siete regresiones JavaExec: perfiles y contextos NBT, geometría y pose rotada, identidad/cara/bloque del callback, tolerancia y orden de intersecciones aisladas.
- `Verify-RadarSpearArrowCollision.ps1` contrasta el bytecode NeoForge de `AbstractArrow.tick`: `getLocation`, la comprobación `isRemoved`, `findHitEntity` y sustitución del resultado; también valida el descriptor del wrapper y las APIs de plot Sable. Los verificadores de artefacto, mixins, guiado, lanzamiento Radar Spear y geometría Sable se ejecutaron igualmente.
- Las pruebas no ejecutan un nivel Minecraft, el servidor ni GameTest; no simulan un `AbstractArrow.tick` con mixins aplicados, entidades reales, poses Sable concurrentes o listeners de protección. La aplicación real del wrapper y la llegada efectiva al callback quedan pendientes de validación integrada.

## Ciclo 2C

- `clean build` offline, Java 21 y dependencias fijadas: correcto. Las siete regresiones JavaExec incluyen identidad del callback, UUID y cara, rechazo de tipos de obstáculo no autorizados, orden de intersecciones y transformación inversa de una pose rotada, más separación de perfiles y consumo único de NBT.
- `Verify-SableBytecode.ps1` contrasta `SubLevelContainer.getChunk → LevelPlot.toLocal → getChunk`, los descriptores de pose y control de clip y la llamada de `LevelChunkMixin` a `SableCommonEvents.handleBlockChange`. `Verify-Geometry.ps1` comprueba la conversión y el presupuesto del recorrido. Los verificadores de artefacto, mixins, guiado y Radar Spear se ejecutaron igualmente.
- No se crearon un mundo ni un servidor. Las comprobaciones de geometría y NBT son aisladas: no simulan realmente chunks descargados, claims, cambios de IFF, jugadores, callbacks de mods, el tick de flecha ni colisiones Sable. Esos escenarios permanecen pendientes de pruebas de integración; la comprobación estática no certifica que los mixins se apliquen ni que el daño funcione en juego.

## Ciclo 2B

- `clean build` offline, Java 21 y JAR fijados: correcto. Se ejecutaron las seis regresiones JavaExec, incluidas pruebas NBT del perfil, identidad, expiración, cancelación, consumo único, interceptor, marcador malformado y designación incompleta.
- `Verify-RadarSpearBytecode.ps1` contrasta el descriptor del redirect, las cuatro llamadas a `BlockWeaponRecoil.spawn`, la fábrica Radar de la cuarta rama y el orden de inserción y reemplazo del hardpoint, además de los hooks de vuelo e impacto. Comprobó firmas y bytecode, sin aplicar mixins.
- `Verify-Artifact.ps1`, `Verify-MixinTargets.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-SableBytecode.ps1` y `Verify-Geometry.ps1` se ejecutan para las regresiones anteriores. La copia del contexto de las pruebas es en memoria; no se han programado ni probado tareas o fragmentos diferidos autorizados.
- No se ejecutó Minecraft, el servidor ni GameTest. No se ha acreditado el daño estructural Sable ni la seguridad integral del comportamiento de bloques, físicas, efectos retardados y otros mods al ejecutar una partida.

## Ciclo 2

- `clean build` offline con las dependencias fijadas y Java 21: correcto. `check` ejecutó las tres regresiones del ciclo anterior y las nuevas `radarSpearModeRegression` y `shotContextRegression`, sin iniciar Minecraft.
- Las regresiones cubren el indicador nativo/gestionado y malformado, copia en memoria del NBT, orden de validación y adopción en código, rechazo de ojivas desconocidas, propagación acotada del contexto y restauración tras excepciones. Las comprobaciones de llamadas son estáticas y no prueban una colisión real ni serialización de mundo.
- `tools/Verify-RadarSpearBytecode.ps1` contrasta los descriptores de Warium y los handlers CMI, los hooks HEAD y el orden nativo de `AbstractArrow.tick`, procedimiento de vuelo e impactos. No ejecuta ni aplica el mixin.
- También pasaron `Verify-Artifact.ps1`, `Verify-MixinTargets.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-SableBytecode.ps1` y `Verify-Geometry.ps1`.
- Se examinó el bytecode fijado y el código descompilado de Neo Warfare, CBC, Warium, CBC: Warium Projectiles y Sable. `ExplosionEvent.Detonate` no cubre automáticamente modificaciones privadas, fragmentación diferida sin fuente o todas las fuerzas Sable. La automatización explosiva de las familias prioritarias continúa bloqueada.
- No se ha demostrado el funcionamiento en una JVM Minecraft, que el hardpoint conserve siempre los datos CMI durante su sustitución, que cada ruta de explosión conserve identidad, ni que las colisiones y daños sobre Sable sean correctos. Los pulsos diferidos de Better Targeting tampoco tienen una propagación acreditada del contexto de disparo gestionado.

## Ciclo 1

- `clean build` con Java 21 y las versiones fijadas; `check` incluye regresiones de modo y NBT real de guiado y una comprobación de intersección geométrica en procesos Java que no inician Minecraft.
- `tools/Verify-GuidanceBytecode.ps1`: contrasta descriptores de los dos métodos Neo Warfare, sus handlers, campos y método heredado, los puntos HEAD y el recorrido de carga NBT/`canFire`.
- `tools/Verify-Geometry.ps1`: ejecuta el cálculo de límites de segmentos y verifica guardas de plot, presupuesto, radio y acceso al chunk. Es una comprobación estática y geométrica aislada; no ejecuta formas de colisión Sable.
- `tools/Verify-SableBytecode.ps1`: verifica firmas de plot/chunk/pose y el uso de `PlotChunkHolder.getChunk` en el JAR fijado.
- Se ejecutaron `tools/Verify-Artifact.ps1` y `tools/Verify-MixinTargets.ps1`. Comprobar nombres, descriptores y código compilado no aplica los mixins ni certifica sus prioridades o la interacción con otros mods.
- Sigue pendiente probar en juego la serialización completa de un misil, las transiciones de carga de plot, la geometría rotada real y el comportamiento multijugador. No se inició Minecraft ni el servidor.

- JDK 21; Gradle Wrapper 9.2.1 con checksum de distribución fijado; ModDevGradle 2.0.148; NeoForge 21.1.265.
- Se ejecutó `clean build` para comprobar que las APIs anidadas se extraen como tarea de compilación después de `clean`.
- `verifyDependencies` comprueba SHA-256 de las dependencias principales, incluidos Radars y Better Targeting.
- `tools/Verify-Artifact.ps1` comprueba metadatos, presencia de las clases mixin registradas, bytecode Java 21 y ausencia de clases/JAR de terceros empaquetados.
- `tools/Verify-MixinTargets.ps1` comprueba nombres de las clases y métodos de inyección contra los JAR locales con `javap`. No sustituye la validación de todos los descriptores, instrucciones objetivo o prioridades de mixin.
- No hay pruebas jugables ejecutadas. No se inició Minecraft, servidor, GameTest ni perfil de prueba. Los `test NO-SOURCE` de Gradle no son pruebas de funcionamiento.
- Las comprobaciones anteriores no demuestran que los mixins se apliquen correctamente en la JVM de Minecraft ni que la automatización sea segura en juego. No se declara finalizada la integración completa.
