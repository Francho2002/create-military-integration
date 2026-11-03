# Validación realizada

## Ciclo 2H — referencias Sable y capacidades a bordo BLOQUEADAS

### Inspección de bytecode (lectura estática)

- Los JAR leídos por `javap` coinciden con `dependency-hashes.properties`: `sable-neoforge-1.21.1-2.0.6.jar` (`9C199157843BF953A47DFF625FC5BB41C01A90538D35AD18E6996BBCB8A617C2`) y `wariumce-1.0.4.jar` (`AA0DD2FA8B5C8C2B4FFF40B4FE2189D94BC9D07240F43A5E6AF95D109B39E27E`) del perfil `C:\Users\panch\Documents\MinecraftProfiles\NeoForge-1.21.1-Aeronautics\mods`; `create-radars-0.4.9.4.jar` (`4554359FEE389B1C255B4CEF23AC41E978B2A371CEC9E952185F4EC0A90EE930`) de `C:\Users\panch\OneDrive\Desktop\KxCraft\_descompiled\mods`.
- Se leyeron firmas/cuerpos de `SubLevelContainer`, `ServerSubLevelContainer`, `SubLevel`, `LevelPlot`, `ServerLevelPlot`, `EmbeddedPlotLevelAccessor`, `Pose3dc`, `IRadar`, `RadarTrack`, `RadarScanningBlockBehavior`, `MissileHardpointFireProcedure` y `BlockWeaponRecoil`. La cadena Sable invoca `SubLevel.tick` y `LevelPlot.tick`; el método `ServerLevelPlot.tick` no invoca tick de block entities. El accessor de plot transforma posiciones de blocks con el centro del plot pero no extiende `Level`. La rama Radar Spear de Warium comprueba `instanceof ServerLevel`, y su inserción nativa acepta `Level` (recibiendo la instancia ServerLevel); el accessor de plot no satisface esa comprobación.
- CBC/Neo Warfare comparten las rutas de montura y disparo verificadas por el `Verify-CIWS.ps1` existente; 2H no modifica `WeaponAdapters`, mixins de fire, balística, geometría, IFF ni reservas. Al no haber tick nativo de block entity en el plot, este ciclo no autoriza ni calcula aim/muzzle/fire a bordo.

### Cambios estáticos

- `MountedBlockReference` distingue `WORLD (dimension, BlockPos)` de `SABLE (dimensión padre, ship UUID, BlockPos local CMI estable)`. La resolución deriva `storedPosition` con `LevelPlot.toGlobal`, usa la coordenada local para `plot.getChunk` y la almacenada para `chunk.getBlockEntity`. No solicita chunks: exige el subnivel exacto y no retirado, el mismo parent `ServerLevel`, límites del plot, chunk disponible, BE exacto/no retirado y coincidencia de level/posición. Las coordenadas WORLD dentro de una parcela Sable fallan como ambiguas.
- La coordenada mundial del centro se recalcula como **posición local CMI → posición almacenada del plot → `logicalPose()` → mundo**. La revisión 2H.1 encontró y corrigió la llamada que pasaba la coordenada local directamente a la pose. `worldCenterFromStoredPlotPosition()` transforma el centro del bloque almacenado. La regresión usa local `(17,80,34)` y almacenada `(2065,80,-798)` con traslación y rotación, y verifica también el round-trip mundo → plot al stored position. La autoridad de Sable viene de `CmiData.ship(UUID)`; `cmiOwner` ausente hereda esa autoridad, pero un dato local malformado u obsoleto produce UNKNOWN.
- Las ramas explícitas `sable` de inspect, binding, perfil y enable evitan interpretar coordenadas WORLD como coordenadas locales. Los enlaces se guardan como referencias no ejecutables. El perfil escribe `cmiManaged`, `cmiOwner` igual al owner CmiData y `cmiRadarProfile`, pero auto permanece false; `true` de auto/CIWS limpia cualquier flag anterior y devuelve BLOQUEADO. No se crea entidades ni llama a fábricas de proyectil.

### Validación ejecutada

- `clean build --offline` terminó con `BUILD SUCCESSFUL` después de los cambios de código. Pasaron las **12 regresiones JavaExec**: `guidanceModeRegression`, `managedGuidanceNbtRegression`, `geometryIntersectionRegression`, `radarSpearModeRegression`, `radarSpearImpactRegression`, `radarSpearStructuralRegression`, `radarSpearBlastGeometryRegression`, `shotContextRegression`, `shipOwnershipRegression`, `radarSpearLockRegression`, `ciwsPolicyRegression` y `mountedBlockReferenceRegression`.
- Pasaron los **10 verificadores existentes**: `Verify-Artifact.ps1`, `Verify-CIWS.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-MixinTargets.ps1`, `Verify-RadarAutomation.ps1`, `Verify-RadarSpearArea.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-RadarSpearBytecode.ps1` y `Verify-SableBytecode.ps1`. `Verify-SableMountedSystems.ps1` también pasó con los tres JAR fijados; comprobó lookup/tick Sable, la rama Warium condicionada a ServerLevel y la firma nativa `spawn(Level, ...)`, y los guardas de referencias, ownership y comandos.
- `git diff --check` terminó sin errores. SHA-256 del JAR generado `build/libs/create-military-integration-0.1.0-dev.jar`: `BD00CB551457B1B1B401EEE8DE2EEE83A505D7E16FA447EEB653C64EF865BB1E`.
- **Corrección 2H.1 (validación estática):** `resolveSable()` ahora llama a `worldCenterFromStoredPlotPosition(ship.logicalPose(), storedPosition)`. El verificador confirma el orden `storedPlotPosition(...) → storedPosition → logicalPose()` y rechaza que la pose reciba `position`/`requestedPosition`. Después del arreglo corrieron `clean build --offline`, las 12 regresiones, los 10 verificadores previos y `Verify-SableMountedSystems.ps1` (11 verificadores en total). SHA-256 del artefacto: `BD00CB551457B1B1`. No se ejecutó runtime.
- **PENDIENTE DE VALIDACIÓN EN JUEGO:** resolución real de BE en un plot cargado, posición mientras se mueve una nave, sugerencias/ejecución de los comandos en servidor y cualquier integración runtime. No se iniciará Minecraft, cliente, servidor ni GameTest en este ciclo.

### Capacidades BLOQUEADAS

- Radar físico montado en Sable: el scanner de Radars necesita tick de block entity, que la cadena fijada del plot no ejecuta; sus tracks no pueden considerarse renovados.
- Radar Spear automático montado en Sable: además de los tracks, la inserción nativa Warium requiere `ServerLevel`; no se debe pasar el accessor del plot ni enviar coordenadas locales como mundiales.
- CIWS montado en Sable: no se acreditó el tick, orientación, muzzle y fireShot nativos de un CBC/Neo Warfare guardado dentro del plot.
- Todas las limitaciones anteriores se documentan desde bytecode leído, no desde una ejecución. No se afirma compatibilidad runtime ni se hace spawn manual de proyectiles.

## Ciclo 2G — CIWS automático (solo estático)

### ACREDITADO ESTÁTICAMENTE

- HEAD de partida `d9cf93caa23c0d295de0ceac3a05644b26bbccff`; se preservaron los no seguidos `tools/cycle2d.init.gradle` y `tools/cycle2d/`. No se iniciaron Minecraft, cliente, servidor ni GameTest.
- `clean build --offline` con Java 21 y hashes fijados terminó `BUILD SUCCESSFUL`. Pasaron **11 suites Java**, incluida `ciwsPolicyRegression`: IDs PROJECTILE válidos/malformados/caducados y presupuestos, owner/IFF, aproximación/NaN, AABB y solución, selección/lock/dos barridos, permiso de un uso y dos propietarios compitiendo por `ReservationBook` con liberación y caducidad. Sus guardas de fuente verifican radar físico, corredor y ausencia de adquisición global o neutralización por predicción.
- Pasaron los nueve verificadores anteriores y `Verify-CIWS.ps1`: **10 verificadores**. `Verify-Artifact` inspeccionó 85 clases Java 21 y 27 mixins; `Verify-MixinTargets` examinó 33 clases objetivo. `Verify-CIWS` valida hashes de Radar, Better Targeting, Neo Warfare, CBC, FTB Chunks/Teams y MineColonies; inspecciona constructor y clave `UUID.toString()` de track, callback de creación/refresco, categoría, barrido cada cinco ticks, expiración inicial de 100 ticks y eliminación de tracks muertos/vencidos, filtros por rango horizontal/FOV/altura, descriptores de radar/montura/solver/fireShot, cargas de munición, tiempo de vida, cooldown, mixin y guardas de origen.
- `FtbCompat.Inner.claimFriendly` retorna `Optional.empty()` para chunk sin claim y `Optional.of(true/false)` para claim con o sin amistad; CIWS usa la presencia para proteger ambos. `ColonyManager.isCoordinateInAnyColony` consulta ownership del chunk. El código CIWS falla cerrado ante manager FTB indisponible. En el radar, la ausencia de entidad elimina su track en la limpieza; una pérdida de cobertura puede dejarlo hasta la expiración, pero el filtro CMI de antigüedad 0–1 tick lo excluye antes.
- El JAR compilado SHA-256 es `16C598E4B54273ACDBB07AF5D32F5A6541065C3C5250072F36C41688E9D5F85C`. La compilación/bytecode no aplican mixins ni ejecutan el mundo.

### PENDIENTE DE VALIDACIÓN EN JUEGO

- Tracks reales PROJECTILE con ownership válido, aplicación/prioridad de mixins, lock, giro, disparo, interacción con FTB/MineColonies y colisión cinética contra un proyectil real. No se simuló neutralización por una solución matemática.
- CIWS está acotado a radar y montura del mundo principal. El cannon medium no dispone de lifetime finito acreditado en vuelo y queda bloqueado. Solo rotary Neo Warfare y autocannon CBC exactos, con AP inerte, sin incendiario/espoleta y dispersión cero, pasan los adaptadores; el runtime todavía no demuestra que un disparo impacte o neutralice.
- Se examina el corredor de **toda** la vida finita del proyectil, con máximo 200 ticks. Todo claim FTB bloquea CIWS, incluso propio y enemigo; los claims o colonias en torno a la montura pueden impedir el disparo. `cmiCIWS=false` deshabilita el coordinador CMI; el control nativo del arma no se desactiva.
- El Ciclo 2E continúa pendiente de validación en juego. Ningún resultado de 2G cambia ese estado.

## Ciclo 2F — Radar automático Radar Spear (solo estático)

### ACREDITADO ESTÁTICAMENTE

- Inicio verificado sobre `main` en `e5080d7133671696ba0d91031f6c015b32a8269d`; `origin/main` coincidía al comenzar. El árbol incluía trabajo posterior a la referencia 2E.1 antigua y no fue rebajado. Se preservaron los archivos locales no seguidos `tools/cycle2d.init.gradle` y `tools/cycle2d/`.
- La API real inspeccionada es `IRadar.getTracks()` de `StationaryRadarBlockEntity` y `RadarBearingBlockEntity`, con `RadarTrack.trackCategory/id/scannedTime/position/velocity`. `RadarScanningBlockBehavior` realiza los scans de Sable cada cinco ticks y usa rango/FOV/rango vertical. El código examinado no comprueba línea de visión. El sensor de Better Targeting no sustituye este contrato físico; CMI no lo consulta para adquirir.
- Warium copia en `HardpointTriggerTickProcedure` los keys `TargetX/Y/Z` seleccionados del Ordinance Controller al hardpoint y luego evalúa `Trigger`. Su ruta de disparo es `MissileHardpointFireProcedure.execute`; el bytecode fijado conserva la creación del Radar Spear, su `BlockWeaponRecoil.spawn` nativo y la sustitución del hardpoint cargado por su versión vacía.
- Implementación CMI: los comandos nuevos son `/cmi radarspearradar <hardpoint> <radar>` y `/cmi radarspearauto <pos> true|false`. El vínculo acepta solo un radar físico cargado de Create: Radars, mismo dimension y owner CMI conocido/igual. Auto requiere owner del hardpoint, perfil gestionado `direct_sable` o `explosive_sable` y radar enlazado.
- Las inyecciones nuevas condicionan su intervención al byte `cmiRadarAuto=true`. El hook de trigger cancela el copiado/control Ordinance únicamente en ese modo; el hook de `MissileHardpointFireProcedure` bloquea redstone/llamadas externas al modo auto. CMI invoca el procedimiento de disparo nativo bajo un permiso de un solo uso por `(LevelAccessor, BlockPos)`. No crea el misil ni llama a `BlockWeaponRecoil.spawn` directamente. El redirect existente `RadarSpearLaunchMixin` sigue capturando la entidad y `RadarSpearBridge` revalida en el lanzamiento el track físico, UUID Sable, estado actual de IFF, perfil y designación.
- Candidate filter: solo track categoría `SABLE`, UUID válido, muestra de radar de 0–1 ticks, contacto re-resuelto, owner y platform conocidos, platform distinta de la propia, relación `ENEMY`, y `canEngage`. Lock mantiene un blanco aún elegible, si no elige el más cercano al hardpoint con empate por UUID; exige dos timestamps distintos. Se designa el centro actual de los bounds. El auto procesador corre en la fase `gameTime % 5 == 2`, un tick después de la cadencia del scanner `% 5 == 1`.
- `RadarSpearLockRegression` cubre candidato ausente, freshness/caducidad, target retenido, liberación por pérdida, selección próxima y desempate, dos muestras diferentes, idempotencia del disparo por sweep y reacquisición desde cero. `Verify-RadarAutomation.ps1` contrasta hashes fijados, firmas radar/Warium, puntos de inyección, uso de tracks físicos, guards IFF/plataforma y ausencia de `CombatServices.contacts`, `Sensor.scan`, spawn directo o `BlockWeaponRecoil.spawn` en el automatizador.
- `clean build` terminó en `BUILD SUCCESSFUL` con Java 21, NeoForge/dependencias fijadas y `verifyDependencies`. Pasaron las diez suites Java: las nueve anteriores y `radarSpearLockRegression`.
- Pasaron los ocho verificadores anteriores (`Verify-Artifact`, `Verify-MixinTargets`, `Verify-GuidanceBytecode`, `Verify-RadarSpearBytecode`, `Verify-RadarSpearArrowCollision`, `Verify-SableBytecode`, `Verify-Geometry`, `Verify-RadarSpearArea`) y el nuevo `Verify-RadarAutomation`. Resultados: 74 clases Java 21/25 mixins registrados; 29 clases objetivo examinadas; firmas y call sites comprobados contra los JAR fijados.
- SHA-256 del artefacto compilado `build/libs/create-military-integration-0.1.0-dev.jar`: `873FD5CD96CEF9F3CF28C9217A7FA4887A5AF3A2A5F849C62A51CBB3599DB0BA`.
- No se inició Minecraft, cliente, servidor ni GameTest. Estos verificadores no aplican mixins y no acreditan runtime.

### PENDIENTE

- Aplicación efectiva/prioridad de los mixins y cualquier resultado observable de tracks, lock o disparo. No se afirma que un Radar Spear haya sido disparado por esta implementación.
- La asociación de configuración usa block entities accesibles desde el `ServerLevel`; bloques localizados dentro de un plot Sable no están admitidos por estos comandos en este ciclo. El mecanismo falla cerrado si no puede obtener un `ServerLevel`/radar enlazado válido.
- El escáner no ofrece prueba de línea de visión, por lo que el comportamiento implementado refleja la detección por rango, FOV, rango vertical y filtros configurados por Create: Radars, no oclusión física.

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
# Ciclo 2I — integración estática de misiles multibloque Neo Warfare

## Evidencia de dependencias fijadas

- Neo Warfare `0.0.6v` registra `MUNITIONS_CONTRAPTION` con la clase `MunitionsPhysicsContraptionEntity`; su namespace de Registrate es `cbcmodernwarfare`, por lo que el ID comprobado por bytecode es `cbcmodernwarfare:munitions_contraption`.
- La clase hereda `OrientedContraptionEntity` de Create, y por tanto `AbstractContraptionEntity`; no es una subclase de Minecraft `Projectile`. El scanner de Radars 0.4.9.4 consulta esta familia solo en su barrido `scanContraptions`. El tag `radar_projectile` se consulta antes del fallback `AbstractContraptionEntity`, así el track resultante es PROJECTILE.
- El barrido de Radars ocurre cada 5 ticks y conserva sus límites nativos de rango/FOV. CMI no enumera entidades como sensor. `scanProjectiles` es obligatorio para todo candidato; luego de resolver el UUID al entity real, `scanContraptions` es obligatorio solo si esa entidad es `AbstractContraptionEntity`. No modifica flags. Así el tag no permite adquirir una contraption cuando su consulta física está apagada, y el flag de proyectiles apagado rechaza incluso un track que Radars ya haya etiquetado.
- `RadarTrack(Entity)` toma `Entity.getUUID().toString()`, posición, `getDeltaMovement()`, hora del nivel y categoría. CMI valida el UUID canónico y reciente, resuelve la entidad viva por UUID, vuelve a crear el contacto actual y requiere identidad de proyectil, owner conocido, ENEMY y `canEngage` antes de seleccionar.
- El lanzador crea/posiciona/dispara la contraption y llama a `ServerLevel.addFreshEntity` desde `MountedMunitionsLauncherContraption.fireShot`. `ShotOwnershipMixin` envuelve ese método y mantiene `ShotContext` hasta que el alta de entidad invoca `EntityJoinLevelEvent`. El owner no se infiere de la ubicación ni del radar. La asignación se omite si no hay actor/owner, y el CIWS rechaza UNKNOWN.
- `MunitionsPhysicsContraptionEntity` integra el movimiento en `setContraptionMotion`; `shoot`, `tick` y el override de `setDeltaMovement` usan esa ruta. El snapshot de velocidad es el movimiento real actual de la entidad, pero no predice empuje/maniobras futuras. Sus límites de contraption se derivan de los bloques de munición y del margen fijo de 0.25 de Warium; CMI usa el `getBoundingBox()` de la entidad resuelta para la tolerancia.
- CBC recorre entidades en el volumen de desplazamiento, consulta `canHitEntity` y luego resuelve `onHitEntity` ante intersección real. `CannonInterceptionMixin` solo amplía la colisión para un disparo CMI gestionado; vuelve a resolver contacto y `canEngage`. `CombatServices.neutralize` descarta la entidad real solo tras esa colisión y otra validación. Aliados, UNKNOWN y ausencia de owner no pasan; no hay neutralización predictiva.

## Cambios y comprobaciones

- Se corrigió la ruta del tag a `data/create_radar/tags/entity_type/radar_projectile.json`, singular según Minecraft 1.21.1, conservando `replace:false` y el único valor `cbcmodernwarfare:munitions_contraption`.
- `CiwsCoordinator.projectileTracks` conserva los flags leídos con accessor de solo lectura. Después de resolver cada UUID al entity real, `CiwsPolicy.sensorAllows` aplica `scanProjectiles && (!esContraption || scanContraptions)` tanto en adquisición como en validación de lock/reserva. Un proyectil CBC normal no depende de `scanContraptions`; una munición contraption sí. El radar mantiene sus opciones; no se cambian range, FOV, cadencia, lock, solver, reservas, arma ni disparo nativo.
- `CiwsPolicyRegression` comprueba el contenido exacto del tag, la correlación track/entity y la guarda de filtros/IFF. `ShotContextRegression` comprueba el scope del `fireShot` nativo y la propagación de owner/platform al alta de la entidad.
- `Verify-MultiblockMissileRadar.ps1` contrasta hashes fijados, registro/tipo/herencia/launcher/velocidad/AABB de Neo Warfare, tag/categoría/barrido/tracks de Radars, ruta de procedencia, IFF CIWS y la ruta CBC de colisión; también exige el recurso en el artefacto.

**ACREDITADO ESTÁTICAMENTE:** `clean build --offline` pasó y ejecutó correctamente las 12 suites: `guidanceModeRegression`, `managedGuidanceNbtRegression`, `geometryIntersectionRegression`, `radarSpearModeRegression`, `radarSpearImpactRegression`, `radarSpearStructuralRegression`, `radarSpearBlastGeometryRegression`, `shotContextRegression`, `shipOwnershipRegression`, `radarSpearLockRegression`, `ciwsPolicyRegression` y `mountedBlockReferenceRegression`. Pasaron los 12 verificadores: `Verify-Artifact.ps1`, `Verify-CIWS.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-MixinTargets.ps1`, `Verify-RadarAutomation.ps1`, `Verify-RadarSpearArea.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-RadarSpearBytecode.ps1`, `Verify-SableBytecode.ps1`, `Verify-SableMountedSystems.ps1` y `Verify-MultiblockMissileRadar.ps1`. `git diff --check` pasó. SHA-256 del artefacto: `8AD901B6C5986E13C1BE455795C45DC9346CDD22FB2E9987D40D8AF52A443134`.

**PENDIENTE DE VALIDACIÓN EN JUEGO:** que el radar produzca el track durante el movimiento real de esta munición, que el lock sobreviva su física/thrust, que una ronda CBC interseque el AABB y que el hook neutralice el misil efectivamente. No se ejecutó Minecraft, servidor, cliente ni GameTest.

## Ciclo 2I.1 — ruta del tag 1.21.1 y filtros CIWS por tipo

### Cambios

- La causa del tag no detectado era el nombre de directorio plural. Para Minecraft 1.21.1 la ruta queda en `data/create_radar/tags/entity_type/radar_projectile.json`; el JSON conserva exactamente `replace:false` y un solo valor, `cbcmodernwarfare:munitions_contraption`. Se eliminó la ruta `entity_types`.
- `CiwsPolicy.sensorAllows` implementa la matriz por clase de entidad. CIWS lee los dos flags mediante el accessor existente, de solo lectura. Resuelve el UUID del track a entidad y aplica: todo candidato necesita `scanProjectiles`; una entidad `AbstractContraptionEntity` además necesita `scanContraptions`. Proyectiles CBC normales no requieren el filtro de contraptions. La regla se aplica durante adquisición y revalidación del target retenido/reservado.
- No cambian tracks físicos, frescura, IFF, ownership, lock, reservas, solver, seguridad de trayectoria, disparo nativo ni configuración del radar. Un track PROJECTILE no evita los rechazos por identidad/owner/IFF.
- `CiwsPolicyRegression` verifica las ocho combinaciones, owner desconocido, relaciones no hostiles, resolución del UUID antes del filtro en adquisición y revalidación, ruta singular y JSON exacto en fuentes y JAR. `Verify-CIWS.ps1` y `Verify-MultiblockMissileRadar.ps1` verifican los mismos contratos; el último rechaza la ruta plural en fuentes y artefacto.

### Validación estática ejecutada

- `clean build --offline` terminó con `BUILD SUCCESSFUL`; ejecutó las 12 regresiones Java, incluida `ciwsPolicyRegression`.
- Pasaron los 12 verificadores: `Verify-Artifact.ps1`, `Verify-CIWS.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-MixinTargets.ps1`, `Verify-MultiblockMissileRadar.ps1`, `Verify-RadarAutomation.ps1`, `Verify-RadarSpearArea.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-RadarSpearBytecode.ps1`, `Verify-SableBytecode.ps1` y `Verify-SableMountedSystems.ps1`.
- La regresión y el verificador confirmaron que el JAR tiene `data/create_radar/tags/entity_type/radar_projectile.json` con el contenido exacto y no tiene `data/create_radar/tags/entity_types/radar_projectile.json`. SHA-256 del JAR: `7C54267AFDF6402B57FA2CC87A975DD2C5A559559CFD154AE611BC57B1460C5F`.
- `git diff --check` pasó.

| scanProjectiles | scanContraptions | Projectile normal | AbstractContraptionEntity clasificada PROJECTILE |
|---|---|---|---|
| true | true | Permitido por filtro | Permitido por filtro |
| true | false | Permitido por filtro | Rechazado |
| false | true | Rechazado | Rechazado |
| false | false | Rechazado | Rechazado |

“Permitido” aquí solo significa que pasa el filtro del sensor; track reciente, entidad viva, owner, IFF ENEMY, `canEngage`, lock, solver y trayectoria segura siguen siendo obligatorios.

### PENDIENTE DE VALIDACIÓN EN JUEGO

No se inició Minecraft, cliente, servidor ni GameTest. La aplicación efectiva del tag al cargar datapacks y la detección/intercepción en partida siguen pendientes. No se cambiaron los JAR instalados en cliente o servidor.

## Ciclo 2J — seguimiento temporal y predicción de maniobras CIWS

### Evidencia del contrato Better Targeting 1.0.0.1

`Verify-CIWSMotionPrediction.ps1` valida el SHA fijado y ejecuta `javap -p -c -s` sobre `TargetMotion`, `FiringSolver`, `Solution`, `Approach` y `V3`. El tercer componente del record se almacena en el campo `acceleration`; `TargetMotion.at(t)` lo multiplica por un factor temporal basado en `0.5 × min(t,30)^2 + 30 × max(0,t-30)`, componente por componente. Antes de 30 ticks, el resultado es `p + v·t + a·t²/2`; después el solver continúa con la velocidad equivalente a la aceleración acumulada al tick 30. El JAR no declara unidades en metadatos: el uso del tiempo del solver en ticks y la combinación vectorial con posición y velocidad implican bloques, bloques/tick y bloques/tick², respectivamente; las unidades se tratan como inferidas, no como una etiqueta explícita del bytecode.

El bytecode de `solve` toma muestras con `TargetMotion.at`, y `finish` llama a `approach`, construyendo `Solution.intercept` desde el mismo estado temporal. `approach` compara cada paso de `ShellModel.step` con la posición temporal del objetivo. `path(direction,lifetime)` solo integra `ShellModel.step` para producir el corredor del proyectil disparado y no consulta ni sustituye la trayectoria real de la bala por la del blanco.

### Implementación y regresiones

`CiwsMotionTracker` recibe muestras únicamente después del track físico PROJECTILE, UUID resuelto, entidad real viva y autorización IFF. Mantiene como máximo tres muestras por blanco, exige timestamps de radar distintos y con delta exacto de cinco ticks, y guarda identidad del radar, montura, dimensión, UUID, owner/plataforma del tirador y procedencia actual del blanco. No almacena entidades ni niveles. Cambios de origen invalidan la historia; tracks que desaparecen se eliminan; límites de entradas, expiración, descarga de dimensión y descarga tanto del chunk de la montura como del radar limpian datos temporales.

`CiwsMotionPolicy` estima aceleración desde velocidades consecutivas divididas por los ticks reales transcurridos. Dos barridos autorizan solo el solver lineal si la aceleración no modelada cabe en el margen restante; tres barridos permiten acceleration-vector prediction únicamente cuando el residuo de posición y la divergencia entre aceleraciones consecutivas caben en la tolerancia real de la AABB. Aplica `0.5 × errorDeAceleración × vuelo²` y descuenta el miss del solver. Un error que iguala/supera el margen, un salto de scan, datos inválidos o una maniobra incompatible inhiben el plan de disparo. La confianza observada no garantiza que la maniobra continúe igual durante el vuelo.

Los proyectiles CBC ordinarios mantienen su plan de velocidad instantánea y el lock existente de dos barridos. Los misiles multibloque Neo Warfare requieren tres muestras para el modelo acelerado; antes de eso solo pueden usar lineal si el límite de error pasa. Justo antes del disparo se consulta de nuevo el radar físico y se exige el mismo UUID/timestamp reciente y filtros del sensor, junto con la revalidación de owner, plataforma, identidad e IFF. La pérdida de radar, owner, plataforma, identidad o ENEMY invalida la aprobación; no hay neutralización desde una predicción. La solución y el `approach` del cañón usan el mismo solver/TargetMotion, y el corredor evalúa el shell path.

La regresión `CiwsMotionRegression` cubre: primera/misma/nueva muestra, delta omitido, timestamps futuros/retrocedidos, NaN/Inf, cambio de UUID/radar/owner/procedencia, aceleración en tres ejes, aceleración opuesta, historia acotada y limpieza al perder tracks, desactivar/montura o descargar el radar. También comprueba el límite de 30 ticks, confianza contra vuelo/miss/hitbox, lock CBC de dos barridos, IFF desconocido, ausencia de sensores globales, ausencia de spawn y neutralización predictivos y uso del mismo solver para aim y trayectoria.

### Resultado del ciclo 2J

Las capacidades de código que se cubren son seguimiento entre barridos, estimación vectorial, contraste de tendencia, incertidumbre geométrica y predicción acelerada acotada para `MunitionsPhysicsContraptionEntity`; la adquisición continúa gobernada por `IRadar.getTracks()`, y el firing nativo, IFF, reservas, filtros y `TrajectorySafety` no se relajan. No se modifican velocidades, gravedad, drag, daño, spread, vida útil, cooldown o colisión de los proyectiles.

La validación de 2J es exclusivamente estática. Cualquier seguimiento y predicción real en juego, continuidad del track durante maniobras, orientación física, fuego, colisión e intercepción permanecen **PENDIENTES DE VALIDACIÓN EN JUEGO**. No iniciar Minecraft ni un servidor fue parte de las restricciones de este ciclo; no se modificaron JARs instalados.

### Resultados ejecutados

- `clean build --offline` con Java 21 y hashes de dependencias fijados: **BUILD SUCCESSFUL**. `check` ejecutó 13 regresiones Java: `guidanceModeRegression`, `managedGuidanceNbtRegression`, `geometryIntersectionRegression`, `radarSpearModeRegression`, `radarSpearImpactRegression`, `radarSpearStructuralRegression`, `radarSpearBlastGeometryRegression`, `shotContextRegression`, `shipOwnershipRegression`, `radarSpearLockRegression`, `ciwsPolicyRegression`, `mountedBlockReferenceRegression` y la nueva `ciwsMotionRegression`; todas pasaron.
- Pasaron los 12 verificadores previos y el nuevo `Verify-CIWSMotionPrediction.ps1`: `Verify-Artifact.ps1`, `Verify-CIWS.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-MixinTargets.ps1`, `Verify-MultiblockMissileRadar.ps1`, `Verify-RadarAutomation.ps1`, `Verify-RadarSpearArea.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-RadarSpearBytecode.ps1`, `Verify-SableBytecode.ps1`, `Verify-SableMountedSystems.ps1` y `Verify-CIWSMotionPrediction.ps1`.
- `git diff --check`: sin errores de whitespace; Git mostró únicamente su aviso normal de conversión CRLF→LF en `VALIDATION.md`.
- SHA-256 del artefacto construido `build/libs/create-military-integration-0.1.0-dev.jar`: `3854175D7AD4AF7921466462BCA70E203237D30A863D5DCE8559F1A243D6FB4D`.
- No se ejecutó Minecraft, cliente, servidor o GameTest. No se copiaron archivos a los perfiles ni al servidor. El build de desarrollo no acredita tejido efectivo de mixins ni intercepción runtime.

## Ciclo 2J.1 — regresiones de mantenimiento

### Cambios comprobados por regresión

- La regresión `ciwsMotionRegression` activa 2.049 observadores y comprueba que no se produce `ConcurrentModificationException`, que el observador menos reciente y su historial salen, y que el límite de 2.048 entradas permanece. Una segunda secuencia consulta primero al observador más antiguo y comprueba que el LRU conserva el orden por acceso y expulsa al siguiente más antiguo.
- La misma suite verifica que dos monturas en la misma dimensión/tick solo causan una limpieza global; las dimensiones mantienen estados de reloj separados; la fase siguiente permite limpiar de nuevo; se eliminan historiales vencidos y permanecen los recientes; un rollback (incluido el retorno al timestamp del barrido anterior) invalida el historial; tiempo negativo falla cerrado; descarga de dimensión y parada limpian metadatos; y la memoria de cadencia queda limitada a 2.048 dimensiones.
- `CiwsCoordinator` consulta la cadencia por dimensión antes de las salidas de modo y frecuencia. La llamada por montura solo hace comprobaciones O(1); la iteración completa de historiales ocurre como máximo una vez en la fase `gameTime % 5 == 2` para esa dimensión y timestamp.

### Validación del ciclo 2J.1

`clean build --offline`: **BUILD SUCCESSFUL**. `check` ejecutó las 13 regresiones Java, incluida `ciwsMotionRegression`, que pasó con 2.049 observadores, reordenamiento por acceso, expulsión y limpieza de historiales.

Pasaron los 13 verificadores: `Verify-Artifact.ps1`, `Verify-CIWS.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-MixinTargets.ps1`, `Verify-MultiblockMissileRadar.ps1`, `Verify-RadarAutomation.ps1`, `Verify-RadarSpearArea.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-RadarSpearBytecode.ps1`, `Verify-SableBytecode.ps1`, `Verify-SableMountedSystems.ps1` y `Verify-CIWSMotionPrediction.ps1`. `git diff --check` pasó; Git solo informó su conversión habitual CRLF→LF en `VALIDATION.md`.

SHA-256 de `build/libs/create-military-integration-0.1.0-dev.jar`: `7913F56F10011D486787976F2C3EBCFAB50D9D28AFCBDB4C59E62F2EF65A3BA9`.

No se ejecutaron Minecraft, cliente, servidor ni GameTest; no se copiaron JARs instalados. Las integraciones de predicción y disparo siguen sin acreditación runtime.

## Ciclo 2K — reconciliación de implementaciones

### Comparación de árboles

- `origin/main`: `9018a3254af8039248e56b9ebe15924eaf3f3e91`; integración aislada: `cf2d646b2c1b61af3243a125c8c19106ce3d7614`; base común: `54a26134df3f6ab71a8fc8180b51d03118641a6a`.
- La implementación de coordinación ya existente en `main` incluía fallback entre planes locales, prioridad determinista, reservas exclusivas, lock separado por UUID, timeout/defer PREPARING, protección IN_FLIGHT, fail-closed ante reloj negativo y metadatos PREPARING explícitos validados. Esas rutas se conservaron; no se fusionaron dos coordinadores duplicados.
- La única optimización de producción exclusiva útil de `cf2d646` fue deduplicar UUID repetidos del snapshot antes de actualizar historial/resolver solver; se incorporó mediante `solvedTracks`. También se incorporó el rechazo fail-closed de un `retryUntil` negativo/malformado.
- La integración aislada reemplazaba metadatos PREPARING explícitos por inferencia desde `TARGET`, quitaba la salida fail-closed `now < 0`, dejaba el lock/reserva hasta timeout en algunas salidas de seguridad y no limpiaba PREPARING de la misma manera después de dispatch. Se conservó `main` en estos puntos.
- El guard aislado `startedAt > Long.MAX_VALUE - 40` era incorrecto: con `startedAt = Long.MAX_VALUE - 39` y `now = Long.MAX_VALUE` la edad es 39 ticks, por lo que debe seguir sin expirar. La implementación base valida `0 <= startedAt <= now` y luego compara `now - startedAt >= 40`; esa resta no puede desbordarse bajo esas precondiciones. La prueba de overflow ahora verifica la igualdad en 40 y el caso de 39.
- La regresión base usa `ReservationBook<TargetId>` y ejercita A–L sobre el contrato real; comprueba también propiedad de release y el borde inclusivo de expiración. Se mantuvo y se amplió con ambos lados del deadline cerca de `Long.MAX_VALUE`, reloj negativo/retrocedido, deadline de defer inválido y saturación de `retryUntil`. No se adoptó la versión reflexiva de `cf2d646`, que usa `ReservationBook<UUID>` y pierde parte de esa cobertura de tipo.
- Se mantuvo el verificador `main`, que inspecciona firmas `javap`, contrato nativo CBC, `ReservationBook`, fuente y bytecode empaquetado. Se añadieron comprobaciones de hash para los 11 JAR fijados y guards de deadline/track duplicado; no se sustituyó por la versión aislada, que omitía varias comprobaciones compiladas y de descriptor.

### Validación reconciliada

- `clean build --offline`: **BUILD SUCCESSFUL** con Java 21 y pins actuales.
- **14 suites Java** aprobaron: `guidanceModeRegression`, `managedGuidanceNbtRegression`, `geometryIntersectionRegression`, `radarSpearModeRegression`, `radarSpearImpactRegression`, `radarSpearStructuralRegression`, `radarSpearBlastGeometryRegression`, `shotContextRegression`, `shipOwnershipRegression`, `radarSpearLockRegression`, `ciwsPolicyRegression`, `mountedBlockReferenceRegression`, `ciwsMotionRegression` y `ciwsCoordinationRegression`. La suite 2K conserva A–L y añade límites exactos de overflow y deadline inválido.
- **14 verificadores** aprobaron: `Verify-Artifact.ps1`, `Verify-CIWS.ps1`, `Verify-CiwsCoordination.ps1`, `Verify-CIWSMotionPrediction.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-MixinTargets.ps1`, `Verify-MultiblockMissileRadar.ps1`, `Verify-RadarAutomation.ps1`, `Verify-RadarSpearArea.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-RadarSpearBytecode.ps1`, `Verify-SableBytecode.ps1` y `Verify-SableMountedSystems.ps1`. El verificador de coordinación confirmó localmente los hashes de los 11 JAR fijados y el bytecode del JAR de CMI.
- `git diff --check`: aprobado. SHA-256 de `build/libs/create-military-integration-0.1.0-dev.jar`: `18C3705D6A8F8F09E45C1887B625CA7DAFF1C1CFF3B3C0157640989BD7ABD938`.
- No se inició Minecraft, cliente, servidor ni GameTest. No se modificó KxCraft, no se copiaron JAR instalados y no se publicó a `main` ni release. La distribución física entre varias monturas continúa **PENDIENTE DE VALIDACIÓN EN JUEGO**.

## Ciclo 2L — auditoría final y paquete experimental

### Defecto de saturación corregido

El defecto estaba en `CiwsCoordinator.plans()`: cada candidato pasaba los filtros, actualizaba la historia si correspondía y el método llamaba `FiringSolver.solve()` inmediatamente. Cuando el contador llegaba a nueve, se ejecutaba `MOTION.clearMount(...)` y se retornaba `null`, descartando cualquier plan anterior. Por tanto no se cumplía realmente un límite de ocho llamadas al solver: se ejecutaba una por candidato hasta que el noveno abortaba el barrido.

La planificación ahora separa la admisión barata de candidatos del solver. Primero valida track físico y fresco, UUID/entidad, configuración del sensor, tipo, ownership/IFF/canEngage, aproximación, tolerancia y estado temporal; mantiene `MOTION.retain` para el conjunto autorizado. Luego ordena de forma determinista y limita a ocho antes de construir solver: menor tiempo de aproximación, menor distancia y UUID. El target anterior obtiene prioridad solo si sigue apareciendo en el conjunto elegible. `safePath`, incertidumbre al impacto, arco, reservas, lock y fuego nativo siguen siendo posteriores y obligatorios. El exceso real de 128 tracks inspeccionados continúa fallando cerrado.

La regresión `CiwsCoordinationRegression` cubre snapshots de 1, 8, 9, 16, 64 y 128 candidatos; verifica top ocho en orden, independencia del orden de llegada, descarte del noveno sin borrar el conjunto anterior, retención condicional del target previo, rechazo de FRIEND/UNKNOWN y el guard de fuente que exige preselección antes del solver.

### Auditoría independiente

La revisión independiente encontró que la adquisición sigue viniendo de `IRadar.getTracks()` y que el coordinator conserva los gates de UUID reciente, entidad proyectil real, `sensorAllows`, owner/plataforma, ENEMY/`canEngage`, modelo temporal, solver, `safePath`/TrajectorySafety, reservas, lock vinculado al UUID y revalidación antes de `WeaponAdapters.fire`. La autorización no neutraliza por predicción. El contrato CBC `fireShot(...): void` y el resultado booleano del adapter describen despacho, no confirmación de proyectil o intercepción.

### Resultado de validación Cycle 2L

- `clean build --offline`: **BUILD SUCCESSFUL**, Java 21. Pasaron las 14 suites de regresión Java configuradas, incluida `ciwsCoordinationRegression`; la prueba `test` existente también pasó desde caché.
- `Verify-CiwsCandidateBudget.ps1`: **26/26 assertions estáticas aprobadas**, incluyendo comprobación del bytecode del artefacto compilado. La suite cubre 1, 8, 9, 16, 64 y 128 candidatos, orden determinista independiente del orden del snapshot, retención condicional del objetivo previo, exclusión FRIEND/UNKNOWN y selección antes del solver.
- **15/15 verificadores estáticos aprobados**: `Verify-Artifact.ps1`, `Verify-CIWS.ps1`, `Verify-CiwsCandidateBudget.ps1`, `Verify-CiwsCoordination.ps1`, `Verify-CIWSMotionPrediction.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-MixinTargets.ps1`, `Verify-MultiblockMissileRadar.ps1`, `Verify-RadarAutomation.ps1`, `Verify-RadarSpearArea.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-RadarSpearBytecode.ps1`, `Verify-SableBytecode.ps1` y `Verify-SableMountedSystems.ps1`.
- `Verify-Artifact.ps1`: **108 clases Java 21**, 28 mixins registrados, sin clases o JARs de terceros embebidos. El verificador de mixin targets contrastó las clases contra los mods fijados y el artefacto NeoForge generado.
- SHA-256 del artefacto binario de esta compilación: `5C2BF8DF279921DD7DC58E368DFE8640C3744BAEB2CF7D45C210DB5FACD0ECC5`. SHA-256 del source JAR: `FE30EBB65D776A61936106DD3E6966E1FA3F9D1DDE3D028435D86B10DF7A1468`.
- `git diff --check`: aprobado. El script del paquete experimental pasó el análisis sintáctico de PowerShell.
- No se ejecutaron Minecraft, cliente, servidor ni GameTest. No se modificó KxCraft ni se copiaron/instalaron JARs. La validación de runtime sigue pendiente.
- Límite de cobertura: el top ocho se selecciona antes de solver y reservas. Si esos ocho no producen planes viables o sus reservas están ocupadas, el noveno no se intenta en ese barrido. Puede ser elegido en un barrido posterior si entra en el top ocho; si los mismos candidatos de mayor prioridad persisten, puede quedar postergado indefinidamente. Esto es una limitación de equidad/atención, no una relajación de seguridad.

### Límites que siguen abiertos

No se ejecutará Minecraft, cliente, servidor ni GameTest. El ciclo 2E sigue pendiente de evidencia de impacto; Radar Spear y CIWS reales siguen pendientes de runtime. Las capacidades montadas dentro de Sable siguen bloqueadas. La limitación de pistas contradictorias con UUID repetido se mantiene: el radar fijado usa una tabla por UUID y CMI conserva la primera observación autorizada, pero no detecta contradicciones de proveedores alternativos.

El apartado 2K anterior registra el estado existente al cierre de ese ciclo. La reconciliación se publicó después mediante fast-forward al SHA `08aecb9c1f073b3f544c01a49d0819b09612f954`.

## CMI-PE-01 — Project: Explosive 0.7.2-beta (validación estática)

HEAD inicial: `7c71455fe10cd9289b3eaf64440a7da5175f896a`. El checkout canónico estaba limpio en archivos seguidos; se conservaron sin tocar los archivos previos no seguidos `tools/cycle2d.init.gradle` y `tools/cycle2d/`. No se crearon worktrees, clones ni ramas. No hubo cambios en Project: Explosive, Create: Radars, dependencias fijadas ni instalaciones.

La clasificación de nueve IDs se cotejó con el registro de entidades de la decompilación local (`l1loi01l110i1o10ooiliol0.java:10–46`). CMI resuelve entidades por `BuiltInRegistries.ENTITY_TYPE`, pero no importa tipos PE. Los cinco IDs principales quedan reconocidos solo como candidatos para futuro análisis y bloqueados para adquisición/neutralización. Booster, trails y `multi_block` quedan reconocidos y excluidos. Todo ID actual o futuro en el namespace PE falla cerrado en el gate CIWS.

La auditoría CBC/Neo Warfare utilizó los JAR fijados CBC 5.11.7 (`EA017AB94637032D039899D88404838D173FA0E6286657CAE6A6C3AF459E87CA`) y Neo Warfare 0.0.6v (`F8BBFCB4766F08A88C773A60AA154C4EB9704F974266E00F00569C765667A348`), y las fuentes Minecraft/NeoForge 21.1.265 generadas localmente. Se demostró por bytecode el barrido `clipAndDamage → canHitEntity → comprobación geométrica → onHitEntity`. La Mixin CMI de CBC solo intercepta su filtro para disparos CMI gestionados y tipos que `CombatServices.projectile` ya reconoce. No se agregaron IDs PE a esa whitelist, ni al Mixin; por eso esta entrega no neutraliza PE.

La comprobación adicional de `create-radars-0.4.9.4.jar` (SHA fijado en `dependency-hashes.properties`) con `javap -p -c` confirmó que `RadarScanningBlockBehavior.scanForEntityTracks()` consulta `Level.getEntitiesOfClass(Projectile.class, AABB)` cuando `scanProjectiles` está activo; `tick()` invoca el escaneo en una cadencia de cinco ticks; `updateRadarTracks()` usa UUID textual y actualiza solo entidades vivas dentro de alcance/FOV. `TrackCategory.get(Entity)` prueba primero tags, y luego clasifica `instanceof Projectile` como PROJECTILE. La inspección no autoriza adquisición: CMI rechaza el namespace antes del solver.

La decompilación local de Project: Explosive indica que los `remove(RemovalReason)` revisados de Mk 82, Mk 84 y Fat Man limpian metadatos/tickets y delegan a `super.remove`, sin invocar allí su handler de impacto. No se demostró que trabajos de crater u otros efectos nucleares ya encolados puedan cancelarse al retirar la entidad. `isAttackable()` no forma parte del filtro CBC revisado; eso no sustituye a la comprobación de pickability. La fuente decompilada es aproximada y no sustituye al bytecode del PE ni a pruebas runtime para las consecuencias de detonación.

### Verificación ejecutada

- `gradlew.bat --gradle-user-home .gradle-home -PmodpackDir="C:\Users\panch\OneDrive\Desktop\KxCraft\server\mods" -PcandidatesDir="C:\Users\panch\OneDrive\Desktop\KxCraft\_desechos\_descompiled\mods" clean build --offline`: **BUILD SUCCESSFUL**, Java 21; `check` ejecutó **15 suites JavaExec** y todas pasaron, incluidas las 14 existentes y `projectExplosiveCompatibilityRegression`.
- `Verify-ProjectExplosiveCompatibility.ps1`: **41/41** comprobaciones aprobadas. Contrastó catálogo, política fail-closed, ausencia de dependencia Java/Gradle, guardas de adquisición/retención/revalidación, radar/IFF y bytecode/JAR empaquetado.
- Los **15 verificadores preexistentes** pasaron: `Verify-Artifact.ps1`, `Verify-CIWS.ps1`, `Verify-CiwsCandidateBudget.ps1`, `Verify-CiwsCoordination.ps1`, `Verify-CIWSMotionPrediction.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-MixinTargets.ps1`, `Verify-MultiblockMissileRadar.ps1`, `Verify-RadarAutomation.ps1`, `Verify-RadarSpearArea.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-RadarSpearBytecode.ps1`, `Verify-SableBytecode.ps1` y `Verify-SableMountedSystems.ps1`.
- El JAR final contiene `ProjectExplosiveCompatibility.class` y el `CiwsCoordinator.class` con tres guardas PE compiladas; no contiene JARs de dependencias ni clases PE. SHA-256 del binario: `A27FAB02CD0B75D023A91D77D23F290248C6F6D8CDF2F20A5CA84F9251BBFE0D`. SHA-256 del JAR de fuentes: `43452377E13FA4E9C312F2EE38955AB608D576E8F24A040EE52B7E4CE7FBA572`.
- `git diff --check`: pasó después de actualizar la documentación.
- No se inició Minecraft, cliente, servidor ni GameTest. Detección en radar, callback real y cualquier neutralización/destrucción permanecen **PENDIENTES DE VALIDACIÓN EN JUEGO**; la neutralización PE permanece además bloqueada por política hasta diseñar y demostrar una cancelación segura.

## CMI-PE-02A — procedencia y autorización de bombas convencionales

HEAD inicial: `7ae107fe45ad9314d54e52b5286b62c31545a90a`. El análisis de creación y neutralización, la tabla de certeza y las rutas inspeccionadas están documentados en `PROJECT_EXPLOSIVE_PE_02A.md`. No se tocaron fuentes/JAR de Project: Explosive ni Create: Radars, versiones fijadas, instalaciones o archivos no seguidos preexistentes.

### Resultado estático

- La ruta directa de los ítems Mk 82/Mk 84 proporciona el `Player` al constructor y `Projectile.setOwner(player)`. Airstrike, `/custombomb`, activación desde bloque y bombas hijas generadas por ICBM no muestran transferencia de owner en sus rutas examinadas. Vanilla `Projectile` persiste su UUID Owner y `getOwner()` lo resuelve desde el `ServerLevel`; si no puede resolverlo, CMI informa owner desconocido. Un owner resuelto no demuestra que CMI iniciara el lanzamiento ni permite identificar por sí solo la ruta PE. Ninguna ruta crea un `ShotContext` de CMI.
- El adaptador opcional consume exclusivamente `IRadar.getTracks()` del radar físico activo y cargado, exige filtro de proyectiles, track PROJECTILE reciente, UUID que resuelve a entidad viva y Registry ID exacto. No alimenta adquisición ni fuego; `futureEligible` es solo evaluación informativa y ambas autorizaciones permanecen falsas.
- La política central exige `Classification.neutralizable()` para Project: Explosive; todo el namespace PE (incluidos IDs desconocidos) queda bloqueado. Para CBC/Warium ajeno a PE se conserva la whitelist previa. `CombatServices.neutralize`, la colisión CBC gestionada y la explosión gestionada consultan el nuevo permiso; la explosión gestionada excluye PE también del daño vanilla. La ruta nativa no gestionada no cambia.
- Mk 82/Mk 84 tienen callbacks propios de detonación al impacto; los `remove(RemovalReason)` inspeccionados no llaman esos callbacks. Esto demuestra únicamente que `discard()` antes del impacto no invoca detonación desde esos métodos. No demuestra cancelación de efectos secundarios después de comenzar una detonación ni neutralización física CBC.

### Validación ejecutada

- `gradlew.bat --gradle-user-home .gradle-home -PmodpackDir="C:\Users\panch\OneDrive\Desktop\KxCraft\server\mods" -PcandidatesDir="C:\Users\panch\OneDrive\Desktop\KxCraft\_desechos\_descompiled\mods" clean build --offline`: **BUILD SUCCESSFUL**, Java 21; **15/15 suites JavaExec** pasaron.
- **16/16 verificadores** pasaron: `Verify-Artifact.ps1`, `Verify-CIWS.ps1`, `Verify-CiwsCandidateBudget.ps1`, `Verify-CiwsCoordination.ps1`, `Verify-CIWSMotionPrediction.ps1`, `Verify-Geometry.ps1`, `Verify-GuidanceBytecode.ps1`, `Verify-MixinTargets.ps1`, `Verify-MultiblockMissileRadar.ps1`, `Verify-ProjectExplosiveCompatibility.ps1` (52 comprobaciones), `Verify-RadarAutomation.ps1`, `Verify-RadarSpearArea.ps1`, `Verify-RadarSpearArrowCollision.ps1`, `Verify-RadarSpearBytecode.ps1`, `Verify-SableBytecode.ps1` y `Verify-SableMountedSystems.ps1`.
- `git diff --check`: aprobado. JAR binario `create-military-integration-0.1.0-dev.jar`: SHA-256 `93DC6BA5EB744A6C565A9C386BEADD12E88181A8C6F168917877BAE11C6B1CB9`. JAR de fuentes: SHA-256 `C10D54A71980551F4AAA4A7AE88E986A27D089BE74CECDF3816FB93E392C8A39`. El binario incluye `NeutralizationPolicy` y `ProjectExplosiveBombObservation`, y no contiene código ni JARs de Project: Explosive.
- No se inició Minecraft, cliente, servidor ni GameTest. El radar real, IFF en runtime, colisión CBC y respuesta PE a efectos secundarios siguen **PENDIENTES DE VALIDACIÓN EN JUEGO**; adquisición y neutralización PE siguen **BLOQUEADAS** por este ciclo.
