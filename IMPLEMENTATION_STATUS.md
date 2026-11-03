# Estado de desarrollo

Esta tabla describe el código, no una prueba dentro de Minecraft. Compilación no demuestra aplicación correcta de mixins.

## Ciclo 2L — auditoría final y paquete experimental

Base de trabajo: reconciliación 2K en `08aecb9c1f073b3f544c01a49d0819b09612f954`, publicada mediante fast-forward en `main`. 2L no añade sistemas militares: corrige el tratamiento del presupuesto de candidatos CIWS y prepara un paquete local de revisión.

En `CiwsCoordinator.plans()`, el límite de ocho se aplica después de filtrar tracks físicos, UUID/entidad viva, tipo, configuración del sensor, owner/plataforma, IFF, permiso, tendencia, confianza temporal y geometría finita. Los candidatos preseleccionados se ordenan antes del solver por menor tiempo de aproximación, menor distancia y UUID, conservando el target anterior solamente si sigue en el conjunto elegible. El solver procesa como máximo ocho candidatos; el noveno ya no descarta los planes anteriores ni limpia el historial por una saturación legítima. Más de 128 tracks inspeccionados o datos malformados mantienen rechazo fail-closed. Los planes todavía deben superar solver, incertidumbre, arco y `TrajectorySafety.clear` antes de entrar al lock/reserva/fuego.

| Capacidad | Estado 2L |
|---|---|
| Presupuesto CIWS: hasta 8 candidatos elegibles tras filtrar | IMPLEMENTADO ESTÁTICAMENTE |
| Límite de 128 tracks físicos y rechazo de datos inválidos | IMPLEMENTADO ESTÁTICAMENTE |
| Radar físico, correlación, IFF, confianza y corredor seguro | IMPLEMENTADO ESTÁTICAMENTE; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Coordinación multi-CIWS y reservas | IMPLEMENTADO ESTÁTICAMENTE; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Predicción temporal CIWS y disparo nativo | CONDICIONADO a loadout/solución; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Detección de misiles multibloque Neo Warfare | IMPLEMENTADO ESTÁTICAMENTE; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Neutralización de proyectil por colisión real | CONDICIONADO a colisión; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Radar Spear manual y automático, impacto existente | IMPLEMENTADO ESTÁTICAMENTE; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Radar/CIWS/Radar Spear montados en Sable | BLOQUEADO |
| Paquete experimental | PREPARADO LOCALMENTE PARA REVISIÓN; NO PUBLICADO |

Limitación de UUID: Create: Radars 0.4.9.4 indexa su colección por UUID, de modo que su implementación fijada normalmente no expone duplicados. CMI deduplica por UUID antes del trabajo costoso y conserva la primera observación autorizada; no detecta ni reconcilia datos contradictorios si un proveedor alternativo entregara varias pistas para el mismo UUID.

El paquete experimental se prepara solo en `build/distributions`; no se instala, etiqueta ni publica. Incluye el JAR y su source JAR, documentación, avisos, hashes y procedencia, sin dependencias externas. La validación de juego permanece pendiente.
## Ciclo 2K — coordinación local entre CIWS

- Cada montura mantiene su propia fuente de adquisición `IRadar.getTracks()`. `CiwsCoordinationPolicy` deduplica y ordena los planes locales: conserva primero el blanco anterior si sigue en los planes, después menor tiempo de aproximación, distancia y UUID. El límite continúa en ocho candidatos; los planes y soluciones se calculan una vez y luego solo se prueban las reservas.
- Ante una reserva ajena, la montura prueba el siguiente plan válido. Si cambia de blanco, libera solo su reserva anterior; `CiwsPolicy.observe` inicia un lock de una muestra para el nuevo UUID. Si no hay alternativa asignable, libera su lock y no dispara. El estado compartido sigue limitado a las reservas existentes por `TargetId` y propietario de montura; no se añadió un scheduler ni un sensor global.
- Una reserva **PREPARING** dura 10 ticks y puede renovarse durante 40 ticks (ocho barridos del radar) para acumular lock y orientar el cañón. El estado conserva UUID y hora de inicio explícitos; los metadatos parciales/inválidos se limpian. El timeout valida reloj no negativo y monotónico antes de evaluar `now - start`, cuya resta es segura después de esas guardas. Si vence, la montura libera su reserva y difiere ese UUID localmente durante 10 ticks (dos barridos), pudiendo atender otra amenaza mientras otra montura lo intenta. El defer no afecta a otras monturas ni permite atacar un contacto que no figure en su radar.
- Una reserva **IN_FLIGHT** conserva el plazo previsto de vuelo y la revalidación existente; no se reduce por la política de preparación. La ruta CBC fijada usa `fireShot(...): void`; `WeaponAdapters.fire` solo confirma que la llamada nativa retornó por su ruta de dispatch, y el cannon puede rechazarla internamente. Por eso el HOLD es una protección conservadora contra fuego duplicado, no una confirmación de proyectil creado o de intercepción. CMI no anuncia una intercepción por el mero intento.
- IFF, `canEngage`, filtros/track físico, solver, alineación, trayectoria, permiso de un solo uso y fuego nativo conservan sus guardas. El coordinador sigue siendo local y puede no ser globalmente óptimo cuando varias armas compiten, hay pérdida de tracks o la preparación expira.
- La reconciliación comparó `origin/main` (`9018a3254af8039248e56b9ebe15924eaf3f3e91`) con `cf2d646b2c1b61af3243a125c8c19106ce3d7614`. La coordinación central (fallback, reservas, locks, PREPARING/IN_FLIGHT, límites e IFF) ya estaba en `main`; se conservó esa implementación. Se añadieron solo: descarte de UUID de track duplicados antes de gasto de solver y rechazo fail-closed de deadlines de defer negativos. No se copió el guard aislado `start > Long.MAX_VALUE - 40`: expira incorrectamente cuando quedan 39 ticks; la resta de elapsed de `main` es segura tras validar `0 <= start <= now`.
- `CiwsCoordinationRegression` mantiene `ReservationBook<TargetId>` real y los escenarios A–L; ahora cubre ambos lados del deadline cerca de `Long.MAX_VALUE`, reloj negativo/retrocedido, deadline de defer inválido y saturación de `retryUntil`. `Verify-CiwsCoordination.ps1` conserva las verificaciones `javap`, bytecode del artefacto, contrato CBC y reservas que estaban en `main`, y suma hashes de los 11 JAR fijados, guardas de overflow y descarte de tracks duplicados.
- Reconciliación verificada estáticamente con `clean build --offline`, 14 suites Java, 14 verificadores y `git diff --check`. SHA-256 del JAR reconciliado: `18C3705D6A8F8F09E45C1887B625CA7DAFF1C1CFF3B3C0157640989BD7ABD938`. Sin Minecraft/servidor/GameTest ni cambios a KxCraft; la distribución real entre monturas y las respuestas físicas siguen pendientes de validación en juego.

## Ciclo 2J.1 — mantenimiento del historial CIWS

- **LRU:** la expulsión ahora itera `activeObservers.entrySet()` y toma el observador de `Entry.getValue()` antes de `Iterator.remove()`. Ya no consulta el mapa de acceso mientras su iterador está activo; se conserva `MAX_ENTRIES = 2048` y se eliminan los historiales del observador expulsado.
- **Limpieza:** `CiwsCoordinator.tick()` informa al tracker del game time antes de sus salidas por modo/cadencia. El tracker recuerda por dimensión el último tiempo observado y la última limpieza; una limpieza completa solo ocurre en la fase efectiva de cinco ticks y como máximo una vez por dimensión/tick, incluso con muchas monturas. El estado de mantenimiento también está limitado a 2.048 dimensiones.
- **Fail-closed y ciclo de vida:** el retroceso del tiempo o un valor negativo limpia el historial de esa dimensión; `clearDimension()` elimina sus metadatos de cadencia y `clear()` elimina todos los historiales y estados al detener el servidor. Cada dimensión mantiene su propio reloj. Se conservan expiración de 10 ticks y máximo de 3 muestras.
- No se modificaron el radar físico, predicción/aceleración, Better Targeting, filtros, IFF, reservas, geometría, solver, orientación ni disparo. Runtime permanece sin validar.
- **Verificación estática:** `clean build --offline` pasó con las 13 regresiones Java. Pasaron los 13 verificadores estáticos y `git diff --check`. SHA-256 del JAR: `7913F56F10011D486787976F2C3EBCFAB50D9D28AFCBDB4C59E62F2EF65A3BA9`. No se inició el juego y el artefacto no se instaló.

## Ciclo 2H — referencias de bloque montado en Sable (alcance estático)

- `MountedBlockReference` representa de forma explícita `WORLD` como dimensión + `BlockPos`, y `SABLE` como dimensión padre + UUID de nave + `BlockPos` local estable. En Sable, X/Z empiezan en el chunk mínimo del plot y se convierten con `LevelPlot.toGlobal` a `storedPosition`; Y conserva la coordenada del nivel padre. El chunk se resuelve con la coordenada local que exige `LevelPlot.getChunk`, y el block entity se recupera con `storedPosition`. Resolver exige el subnivel UUID actualmente cargado, no retirado y unido al `ServerLevel` esperado; no solicita cargas, y verifica un block entity vivo cuya posición/level coincidan exactamente. Referencias `WORLD` que caen dentro de un plot se rechazan como ambiguas.
- Cada resolución Sable transforma el centro de `storedPosition` (frame almacenado del plot) mediante la pose viva de la nave: **local CMI → `LevelPlot.toGlobal`/posición almacenada del plot → `logicalPose()` → mundo**. La revisión 2H.1 corrigió una confusión previa que transformaba directamente la posición local CMI. No se guarda una coordenada mundial derivada ni se reutiliza una pose anterior. `actor()` obtiene owner/IFF desde `CmiData.ship(shipUuid)`. Si el BE lleva `cmiOwner`, el UUID debe ser correcto y coincidir con el owner actual de la nave; un campo malformado, obsoleto o sin dueño deja el actor como UNKNOWN.
- Comandos con selector espacial explícito: `/cmi mounted inspect world <pos>`, `/cmi mounted inspect sable <shipUuid> <localPos>`, `/cmi ciwsradar sable <mountShip> <mountLocalPos> <radarShip> <radarLocalPos>` y `/cmi radarspearradar sable <hardpointShip> <hardpointLocalPos> <radarShip> <radarLocalPos>`. Los enlaces Sable válidos se guardan como referencias tipadas persistentes y con `...BindingExecutable=false`; no activan automatización.
- `/cmi ciws sable <shipUuid> <localPos> true` y `/cmi radarspearauto sable <shipUuid> <localPos> true` limpian cualquier indicador previo y contestan BLOQUEADO; `false` solo desactiva. `/cmi radarspearprofile sable <shipUuid> <localPos> <profile>` exige que el ejecutor sea el owner actual de `CmiData.ship`, limpia auto, guarda `cmiManaged`, un `cmiOwner` concordante y `cmiRadarProfile`; no habilita auto ni una ruta de lanzamiento. Los comandos WORLD existentes siguen en sus ramas originales.
- **BLOQUEADO — radar a bordo:** en Sable 2.0.6, el bytecode sigue `ServerSubLevelContainer.tick → SubLevelContainer.tick → SubLevel.tick → LevelPlot.tick`. `ServerLevelPlot.tick` solo actualiza luz y retira contraptions inválidas; no llama al tick de chunks o block entities. Create: Radars 0.4.9.4 renueva tracks mediante su behavior tickeable, por lo que un radar guardado en el plot no ofrece tracks renovados que CMI pueda autorizar. No se añadió un tick global que replique el ciclo nativo.
- **BLOQUEADO — Radar Spear automático y spawn:** Warium 1.0.4 `MissileHardpointFireProcedure.execute(LevelAccessor, DDD, BlockState)` crea el proyectil Radar Spear dentro de una rama que exige `levelAccessor instanceof ServerLevel`, luego invoca `BlockWeaponRecoil.spawn(Level, Projectile, BlockPos, Weapon)` con esa instancia. `EmbeddedPlotLevelAccessor` de Sable implementa `ServerLevelAccessor`, pero no extiende `ServerLevel`, así que no alcanza la rama. El procedimiento también lee y modifica el hardpoint mediante las coordenadas de ese mismo accessor; no se acreditó una ruta que traduzca con seguridad su posición plot-local al `ServerLevel` padre. CMI no crea proyectiles manualmente.
- **BLOQUEADO — CIWS a bordo:** el tick de block entity de la montura no se ejecuta desde el ciclo del plot, y no quedó acreditada una ruta nativa CBC/Neo Warfare de orientación, muzzle y `fireShot` que opere sobre un cañón guardado en ese plot. No se solicita aim, no se llama al fuego y no se alteran las reservas WORLD.
- Evidencia estática leída con `javap` en los JAR que coinciden con `dependency-hashes.properties`: Sable 2.0.6 (`9C199157843BF953A47DFF625FC5BB41C01A90538D35AD18E6996BBCB8A617C2`), Warium CE 1.0.4 (`AA0DD2FA8B5C8C2B4FFF40B4FE2189D94BC9D07240F43A5E6AF95D109B39E27E`) del perfil `NeoForge-1.21.1-Aeronautics`; Create: Radars 0.4.9.4 (`4554359FEE389B1C255B4CEF23AC41E978B2A371CEC9E952185F4EC0A90EE930`) de `_descompiled/mods`. Sable: `SubLevelContainer`, `ServerSubLevelContainer`, `SubLevel`, `LevelPlot`, `ServerLevelPlot`, `EmbeddedPlotLevelAccessor`, `Pose3dc`; Warium: `MissileHardpointFireProcedure`, `BlockWeaponRecoil`; revisión Radars: `IRadar`, `RadarTrack`, `RadarScanningBlockBehavior`. La inspección fue de bytecode/API; no se inició ni ejecutó Minecraft.
- `MountedBlockReferenceRegression` comprueba round-trip y rechazo de referencias malformadas, UUID distinto para barcos con la misma coordenada local, ownership actual/malformado/obsoleto, traducción X/Z de plot y rechazo por coordenada/Y inválidos. La regresión 2H.1 usa local `(17,80,34)` y almacenada `(2065,80,-798)` con traslación y rotación no identidad; verifica que el centro mundial y el round-trip inverso operan en el frame almacenado, y que mover la pose actualiza el centro sin cambiar la identidad local. `Verify-SableMountedSystems.ps1` comprueba además que `resolveSable()` transforma `storedPosition`, no la referencia local.

**Validación estática 2H.1:** `clean build --offline`, las 12 regresiones Java, los 10 verificadores previos y `Verify-SableMountedSystems.ps1` pasaron; `git diff --check` no reportó errores. No se ejecutó Minecraft, servidor ni GameTest.

**PENDIENTE DE VALIDACIÓN EN JUEGO:** resolución de block entities guardados en plots cargados, inspección del centro mientras una nave se mueve y registro de comandos. Este ciclo no declara compatibilidad runtime de Radar Spear, radares ni CIWS en Sable. Las capacidades de automatización y disparo mencionadas permanecen BLOQUEADAS hasta que exista una ruta nativa compatible y verificable.

## Ciclo 2G — CIWS por radar físico

- `/cmi ciwsradar <mount> <radar>` enlaza una montura compacta a un `StationaryRadarBlockEntity` o `RadarBearingBlockEntity` cargado, de la misma dimensión y con el mismo owner CMI. `/cmi ciws <mount> true|false` requiere propietario, radar, montura ensamblada y munición examinable. Ausencia de `cmiCIWS` conserva el coordinador general previo; `false` detiene la automatización CMI de esa montura y libera el lock/reserva, mientras el uso nativo/manual/redstone sigue como antes. Un marcador malformado falla cerrado.
- `CiwsCoordinator` consulta solo `IRadar.getTracks()`. Antes de filtrar limita toda la colección a 128 entradas; desde 2L ordena los candidatos elegibles antes del solver y procesa como máximo ocho; un noveno candidato legítimo no descarta el lote, mientras más de 128 tracks inspeccionados continúa fallando cerrado. `PROJECTILE` se correlaciona por UUID canónico a una entidad viva real, se exige `CombatServices.projectile`, owner conocido, plataforma distinta de la propia, relación `ENEMY` y `canEngage`. No adquiere desde `CombatServices.contacts`, `getAllEntities` ni `Sensor.scan`.
- Contrato Radars 0.4.9.4: `RadarTrack(Entity)` usa `entity.getUUID().toString()`, posición y velocidad de la entidad y `level.getGameTime()`; `updateRadarTracks` usa el mismo UUID como clave de `HashMap.compute` y su lambda crea o actualiza ese track. `trackExpiration` inicia en 100 ticks, es guardado/restaurado por el radar y puede configurarse; la limpieza elimina entidades muertas enseguida y tracks cuya antigüedad supera ese valor. Si una entidad sale de cobertura puede quedar en el mapa hasta caducar, pero CIWS solo acepta edad 0–1. La categoría `PROJECTILE` se asigna por el tag de proyectil antes del fallback `instanceof Projectile`; los tags anteriores de hostile/animal/mob prevalecen. La detección usa rango horizontal, límite vertical configurable y FOV angular configurable; el bytecode no aplica línea de visión. Los filtros/rangos son los del radar enlazado. El scanner actúa en `% 5 == 1`, y CIWS procesa `% 5 == 2`. El conjunto de entidades buscadas se actualiza en el barrido anterior.
- Se exige velocidad finita no despreciable y componente radial entrante (`(posición - montura) · velocidad < 0`). El lock retiene el blanco que siga detectado, autorizado, con solución y corredor válidos; si cambia, selecciona menor tiempo de máxima aproximación, distancia y UUID. Requiere dos scans consecutivos distintos; un timestamp repetido o un salto de barrido no constituye lock. Los datos se vuelven a resolver antes de disparar. Los locks se limpian al reconstruir el block entity, perder el track o detener el servidor.
- `FiringSolver` modela posición/velocidad actual y proyectil real con gravedad, drag, velocidad, distancia de salida y lifetime obtenidos del cañón/munición. Su `miss` es la distancia mínima de centros simulados; el umbral es el radio de la esfera inscrita en la AABB real del blanco (`nextDown(minDimension / 2)`), no un 0,25 fijo. Se verifican solución finita, tiempo, ángulos mecánicos y después se evalúa de nuevo el miss usando el cañón **actual**, no solo los ángulos escritos.
- `WeaponAdapters.ciwsShell` reconstruye el muzzle CBC desde el recorrido nativo: el `muzzleDistance` de `CannonProbe` fijado queda un bloque más adelante que el `fireShot` CBC inspeccionado. Se permiten únicamente clases exactas CBC autocannon y Neo rotary con AP inerte, vida finita de 1–200 ticks y dispersión nativa cero; Neo Warfare puede marcar un AP como incendiario mediante `AmmoItemMixinInerface`, por lo que CIWS rechaza incendiarios, ausencia de esa interfaz y espoletas anidadas. Se respeta `breech.canFire` y su cooldown nativo. No se alteró la inspección general fuera de CIWS.
- **Bloqueo medium:** `AbstractMediumcannonProjectile` no establece tiempo de vida de vuelo; solo su permanencia en tierra caduca. Un fallo de intercepción podría seguir más allá del tramo inspeccionado, por lo que `ciwsShell` devuelve lifetime 0 y la activación lo rechaza. No se inventó una caducidad. Los lanzadores y las demás armas no examinadas se rechazan.
- `safePath` ejecuta `TrajectorySafety.clear` sobre toda la vida finita, incluyendo lo que hay detrás del blanco si el disparo falla; ignora solo la entidad de la montura y la entidad hostil ya autorizada. Rechaza terreno, geometría Sable/estado desconocido, entidades amigas/neutrales/desconocidas, chunks ausentes y MineColonies. Además CIWS bloquea **cualquier** claim FTB que toque el corredor, incluso propio/enemigo, y falla cerrado si el manager FTB no está disponible; esto puede impedir usar CIWS desde bases reclamadas. Rechaza también fluidos porque alteran el drag real CBC. El filtro se realiza para el candidato y se repite sobre el ángulo actual antes de disparar.
- `CombatServices.reserve` usa `ReservationBook` con claves `TargetId`, identidad estable derivada de dimensión/posición de montura y TTL. La reserva se refresca durante el lock; después de disparar se conserva hasta la llegada prevista mientras el radar y el IFF sigan válidos, aun con cooldown. Al perder el objetivo se libera; si se descarga caduca. No contiene referencias a entidades y se vacía al detener el servidor.
- `CiwsFireGuardMixin` protege `fireShot` nativo medium/rotary/CBC autocannon cuando CIWS está activo: el tick propio del rotary/CBC o redstone no pueden eludir IFF. Solo la solicitud de `WeaponAdapters.fire` bajo permiso scoped de un uso entra al método nativo, que conserva su cooldown y consumo; `ShotContext.runManaged` y `ShotOwnershipMixin` mantienen la atribución existente. Ninguna entidad de proyectil se crea o neutraliza desde el solver. La neutralización real requiere la colisión y `CannonInterceptionMixin` existente.
- Alcance: solo radar/montura accesibles desde el `ServerLevel` principal; un plot Sable se rechaza. `MunitionsPhysicsContraptionEntity` de Neo Warfare es contraption y el JAR Radars no lo marca por defecto como `PROJECTILE`, así que sus misiles multibloque no son candidatos salvo un tag de datos externo y demás requisitos. Otros proyectiles CBC/Warium solo son candidatos si el radar realmente los clasifica `PROJECTILE` y CMI puede identificar un owner hostil.

| Capacidad | Estado |
|---|---|
| Radar físico PROJECTILE y correlación track → entidad | IMPLEMENTADO; pendiente de validación en juego |
| IFF y acercamiento | IMPLEMENTADO; owner/relación deben ser conocidos |
| Lock y priorización | IMPLEMENTADO; dos barridos consecutivos y presupuestos |
| Solución balística y orientación | CONDICIONADO a munición y montura examinadas, vida finita y dispersión cero |
| Seguridad de trayectoria | IMPLEMENTADO; claims FTB, colonias, fluidos y geometría desconocida bloquean |
| Reserva multi-CIWS | IMPLEMENTADO con TTL y liberación; sin referencias persistentes a entidades |
| Disparo nativo | CONDICIONADO a cooldown, permiso de un uso y autorización actual |
| Neutralización real | CONDICIONADO a colisión real y hook existente; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Medium Neo Warfare | BLOQUEADO por ausencia de vida finita acreditada en vuelo |

**PENDIENTE DE VALIDACIÓN EN JUEGO:** aplicación de mixins, detección, precisión, montaje, reserva y colisión/neutralización no se han ejecutado. 2E sigue pendiente. El cierre descrito es exclusivamente estático.

## Ciclo 2F — Radar automático Radar Spear (validación estática)

- Base real al comenzar: `main` en `e5080d7133671696ba0d91031f6c015b32a8269d`, que ya contiene los cambios posteriores al commit antiguo `0d736ea...`. Se conservaron sin tocar `tools/cycle2d.init.gradle` y `tools/cycle2d/`.
- API de detección fijada: `create-radars-0.4.9.4.jar`, SHA-256 contrastado con `dependency-hashes.properties`. `IRadar.getTracks()` es implementado por `StationaryRadarBlockEntity` y `RadarBearingBlockEntity`. Sus `RadarTrack` tienen ID, posición, velocidad, instante de scan y `TrackCategory`; Sable se etiqueta `SABLE` y su ID es el UUID del subnivel. `RadarScanningBlockBehavior` actualiza cada 5 ticks, y aplica rango horizontal, rango vertical y FOV. No encontré un raycast de línea de visión en este escáner. El `getTracks()` del radar bearing puede aplicar su API de spoofing; la fábrica fijada examinada devuelve la colección vacía, por lo que no ofrece un track Sable autorizable.
- Better Targeting `Sensor.scan` no es un sensor radar que pueda usarse para este objetivo: barre entidades vivas y Sable mediante búsquedas de nivel/radio; no entrega los tracks de `IRadar`, ni el FOV/rango del radar físico. CMI no lo utiliza para adquisición y tampoco usa `CombatServices.contacts()` en `RadarSpearAutomation`.
- No encontré una asociación existente Create: Radars ↔ Warium Ordinance Controller. Se incorporó un vínculo explícito y acotado: `/cmi radarspearradar <hardpoint> <radar>`. Acepta solo los dos block entities reales, cargados y en la misma dimensión; radar y hardpoint deben tener owner CMI conocido e idéntico. Esta ruta de comandos usa block entities del `ServerLevel`; hardpoints/radares dentro de plots Sable no están soportados por este ciclo.
- `/cmi radarspearauto <pos> true|false` activa el modo solo sobre un hardpoint con owner del ejecutor, `cmiManaged`, perfil Sable válido y radar enlazado. No hay activación por defecto. Desactivar borra el lock CMI y restaura `TargetX/Y/Z` anteriores solo si aún coinciden exactamente con los valores que escribió CMI; conserva cambios externos posteriores. Modo ausente/falso deja intactos el trigger, redstone y la designación manual de Warium; un marcador presente pero malformado bloquea el disparo de esa montura.
- `RadarSpearAutoTriggerMixin` toma el control solo cuando el byte NBT `cmiRadarAuto` es explícitamente true. Ejecuta la adquisición CMI y cancela el copiado/trigger del Ordinance Controller para ese hardpoint. `RadarSpearAutoFireGuardMixin` bloquea entradas Warium de redstone/nativas en ese hardpoint salvo un permiso de un solo uso CMI vinculado a ese `LevelAccessor` y `BlockPos`. Otros hardpoints Warium no pasan por esta guarda.
- La adquisición lee exclusivamente `IRadar.getTracks()` del radar enlazado, admite solo `TrackCategory.SABLE`, UUID parseable, scan de no más de un tick, Sable resuelto de nuevo, owner conocido, platform UUID coincidente, plataforma distinta a la propia, relación actual `ENEMY` y `CombatServices.canEngage` (que vuelve a comprobar IFF y territorio). Ordena por UUID y procesa como máximo 128 tracks Sable por barrido; si se excede el presupuesto, falla cerrado en vez de elegir un subconjunto según el orden de colección. Tracks ausentes, caducados, neutrales, aliados, desconocidos, sin owner o protegidos no adquieren ni disparan.
- El lock conserva el blanco vigente si sigue elegible; si se pierde, escoge el contacto actual más cercano al hardpoint y desempata con UUID lexicográfico. Se exigen dos timestamps de scan distintos; un mismo track cacheado no suma. CMI procesa en `gameTime % 5 == 2`, justo después de la cadencia del scanner fijado (`% 5 == 1`), y exige antigüedad 0–1 tick. La designación es el centro actual y finito de `MilitaryContact.bounds()`.
- En `MissileHardpointFireProcedure` el bytecode fijado construye el proyectil Radar Spear, invoca la fábrica y `BlockWeaponRecoil.spawn`, añade LX/LY/LZ, y reemplaza el hardpoint cargado por el vacío conservando NBT. El procedimiento de trigger de Warium copia TargetX/Y/Z desde Ordinance Controller y llama al procedimiento de disparo según Trigger. Para auto, CMI escribe esos mismos keys Warium y llama a `MissileHardpointFireProcedure.execute`; no crea la entidad ni invoca `BlockWeaponRecoil.spawn`. La captura ya existente `RadarSpearLaunchMixin` sigue siendo el único punto CMI de captura. El Bridge de lanzamiento ahora exige para auto el mismo UUID Sable seleccionado y revalida el track físico/IFF antes del spawn. La salida de Warium consume/reemplaza el hardpoint, y CMI deduplica por timestamp de scan; el bytecode no muestra un cooldown independiente dentro del procedimiento de disparo.
- `RadarSpearLockRegression` verifica ausencia de candidato cuando la lista del sensor está vacía, freshness/caducidad, lock persistente, pérdida, nueva adquisición, necesidad de dos sweeps, una sola solicitud por timestamp y desempate determinista. `Verify-RadarAutomation.ps1` contrasta hashes, API radar, categorías y timestamps, métodos de trigger/fire Warium, mixins y la ausencia de scans globales/spawn directo.
- **ACREDITADO ESTÁTICAMENTE:** interfaz/clases radar y tracks, rutas TargetX/Y/Z y fire de Warium examinadas; nueva ruta CMI compila y las guardas de fuente/bytecode pasan. No se inició Minecraft, servidor ni GameTest.
- **PENDIENTE DE VALIDACIÓN EN JUEGO:** tejido efectivo de mixins, vinculación real de blocks, tracks Sable dentro de un mundo activo, lock temporal, inserción/captura del misil y disparo real. El escáner Radar no implementa línea de visión; el ciclo solo acredita el FOV y rangos del mod. El modo automático es para hardpoints y radares accesibles como block entities del mundo/dimensión, no para bloques guardados dentro de plots Sable.

| Capacidad | Estado |
|---|---|
| API radar real inspeccionada | IMPLEMENTADO |
| Contacto físico radar | IMPLEMENTADO; el runtime queda pendiente |
| Correlación radar → Sable | IMPLEMENTADO |
| Filtro IFF | IMPLEMENTADO; reutiliza `relation` + `canEngage` |
| Lock estable | IMPLEMENTADO en política estática de dos barridos |
| Designación TargetX/Y/Z | IMPLEMENTADO con keys Warium y punto de bounds actual |
| Trigger Warium nativo | IMPLEMENTADO; se invoca su procedimiento de disparo desde el hook de trigger |
| Captura RadarSpearBridge | IMPLEMENTADO; revalida UUID, track, perfil e IFF |
| direct_sable automático | PENDIENTE DE VALIDACIÓN EN JUEGO |
| explosive_sable automático | PENDIENTE DE VALIDACIÓN EN JUEGO |
| Compatibilidad manual | IMPLEMENTADO por guardas condicionadas a auto=true |

## Ciclo 2E.2: validación runtime pausada por reinicio seguro

- HEAD remoto actual: `d46a7cb0d4783a1160a284fbcda372b0a0471154`. Tiene el mismo árbol de código del ciclo 2E.1 solicitado como `0d736ea701724765a7590fc0deee7dd518c6d0f9`; solo se reescribió el mensaje del commit a español.
- Se confirmó de nuevo que PID 6016 ejecuta Java desde `C:\Users\panch\Documents\MinecraftProfiles\CMI-Cycle-2E-Test\server`, con argumentos NeoForge 21.1.265. Su padre es `cmd.exe`, pero no hay ventana de consola visible/accesible; `enable-rcon=false`.
- No se detuvo el proceso ni se reemplazó el JAR activo. El JAR de la carpeta de prueba tiene SHA-256 `5FB3397CAB1760F5367A5F0F8701949D9BCD4F31B3617060FE2E2F0FEE431333`; el artefacto de HEAD tiene SHA-256 `CF00AA0E5E9B9249B508C0CF1264B2ACC615F440A764D80B2C4A0C0180F84AA3`. El servidor debe apagarse normalmente antes de sustituirlo.
- **ACREDITADO EN JUEGO:** solo los hechos históricos de 2E: arranque hasta `Done`, cliente dentro, CMI cargado y ensamblado Sable. No se ejecutaron los nuevos comandos con el artefacto de HEAD ni hubo lanzamiento en esta reanudación.
- **ACREDITADO ESTÁTICAMENTE:** artefacto corresponde al árbol actual; para ese código pasaron `clean build`, nueve regresiones y ocho verificadores en el ciclo anterior. La inspección de IFF y API de subniveles se conserva documentada en 2E.1.
- **PENDIENTE:** detener manualmente `CMI-Cycle-2E-Test` usando `stop` en su consola normal; colocar después el artefacto nuevo y reiniciar ese servidor. Luego validar `/cmi ships`, `/cmi shipowner`, `/cmi hostileuuid`, `/cmi relation`, y solo con `ENEMY` continuar Fase A, B, C1 y C2. El Ciclo 2E sigue abierto.

## Ciclo 2E.1: administración explícita de propiedad Sable (sin validación runtime nueva)

- En HEAD `83d97789d26c90dc3cfc0992ab99a630ef531b70` se agregó `/cmi ships`, que enumera solo subniveles Sable cargados con UUID, nombre expuesto por Sable y owner CMI o `unowned`.
- `/cmi shipowner <shipUuid> <ownerUuid>` resuelve la nave en el `SubLevelContainer` del `ServerLevel`, rechaza UUID no cargado/retirado y escribe exclusivamente en `CmiData.ships` mediante `bindShip`, conservando el IFF anterior y fijando `Actor.platform` al UUID de la nave. No busca block entities del plot ni escribe NBT privado de Sable.
- `/cmi hostileuuid <ownerUuid> <enabled>` registra hostilidad desde el UUID del jugador que ejecuta el comando hacia el UUID propietario indicado, aunque ese jugador blanco esté offline. `/cmi hostile <player> <enabled>` permanece intacto. `/cmi relation <shipUuid>` es de solo lectura y muestra owner, plataforma y relación para el ejecutor actual.
- La versión fijada de Better Targeting implementa `Iff.playerId(ServerLevel, Allegiance, UUID)` mediante igualdad/lista de UUID, consulta `FtbCompat.teamFriend(owner, id)` y no requiere que el jugador blanco esté conectado. El acceso de FTB Teams/Chunks también usa UUID (`arePlayersInSameTeam`, `getTeamForPlayerID`, `isAlly`). En CMI, FRIEND por owner/IFF/FTB continúa precediendo a la hostilidad explícita; una nave sin owner continúa como UNKNOWN. No se añadió bypass de IFF.
- Regresión Java nueva `shipOwnershipRegression`: asociación solo a UUID enumerados como cargados, cambio de owner, preservación de IFF y platform, round-trip SavedData NBT, hostilidad true/false, rechazo/null/UNKNOWN, FRIEND por mismo owner y precedencia de aliado, además de guardas estáticas sobre comandos y `/cmi claim`.
- `clean build` pasó con Java 21; `check` pasó las nueve regresiones JavaExec. Los ocho verificadores actuales aprobaron: artefacto, targets de mixin, bytecode Radar Spear, colisión AbstractArrow, bytecode Sable, geometría, bytecode de guiado y área Radar Spear. El artefacto resultante tiene SHA-256 `CF00AA0E5E9B9249B508C0CF1264B2ACC615F440A764D80B2C4A0C0180F84AA3`.
- El proceso 6016 fue comprobado de nuevo como `java.exe` dentro de `CMI-Cycle-2E-Test` (NeoForge 21.1.265), pero esta sesión no tiene una consola `stop` accesible. No se fuerza su terminación ni se reemplaza su JAR en caliente. Hasta reiniciar el servidor de prueba con el artefacto nuevo, no se establece ENEMY ni se reanuda la Fase A.
- **ACREDITADO EN JUEGO:** sin evidencia nueva en este ciclo. Los resultados runtime anteriores siguen limitados a arranque, ingreso y ensamblado; no hubo lanzamiento Radar Spear.
- **ACREDITADO ESTÁTICAMENTE:** política UUID offline inspeccionada en el código descompilado fijado; compilación, regresiones y verificadores indicados por este ciclo.
- **PENDIENTE:** reinicio seguro del servidor aislado, uso de `/cmi ships`, asignación owner real, comprobación `/cmi relation <uuid>` = ENEMY y toda la cadena de impacto Radar Spear. Ciclo 2E permanece abierto.

## Ciclo 2E: primera ejecución integrada (abierta)

- Se añadió `RadarSpearDiagnostics`, con líneas `[CMI-RadarSpear]` que incluyen launch/effect abreviados, misil, perfil, objetivo y tiempo. `launch_rejected` solo se emite para una solicitud explícitamente gestionada; los lanzamientos nativos no producen estos registros.
- Los eventos cubren captura y armado, primera activación de guiado, cambios de contacto/primer obstáculo, endpoint de bloque convertido para `findHitEntity`, `onHitBlock` con cancelación Warium, comienzo de cada perfil, confirmación de colisión, consumo de autorización, conteo/aprobación de candidatos y cada cambio efectivo. El primer obstáculo y el contacto de guiado se registran solo al cambiar de estado; no hay logging periódico por tick.
- `RadarSpearStructural` registra una etapa de rechazo concreta para los bloqueos del recorrido directo y deja en el mismo evento las coordenadas mundial/plot, bloque y cara cuando supera la autorización. `RadarSpearAreaDamage` informa excepciones y rechazos por fase, cantidad de candidatos/aprobados y bloques cambiados. La instrumentación no modifica IFF, perfiles, cálculos, daño ni callbacks.
- Procedimiento y primer intento documentados en `VALIDATION.md`. Se usó el servidor/clientes aislados `CMI-Cycle-2E-Test`; producción no se abrió ni modificó.
- En runtime, NeoForge llegó a `Done`, el cliente autenticó y entró, CMI apareció en el listado de mods y la nave Sable de 75 bloques se ensambló. No hubo un error fatal de carga de CMI en este arranque. El log no confirma por sí solo que cada mixin se haya aplicado.
- El primer bloqueo de la Fase A fue preparar propiedad/hostilidad: `/cmi claim 34 142 10`, ejecutado después de ensamblar el cofre dentro del plot, respondió `No block entity at position`. El comando consulta `ServerLevel.getBlockEntity` y no resuelve directamente un block entity del subnivel. Solo había un jugador conectado y `/cmi hostile` requiere otro jugador online. No se lanzó ningún misil ni se ejecutó A, B, C1 o C2.
- Durante el primer ingreso, Distant Horizons falló al procesar un chunk porque el cliente de prueba no tenía Chunky cargado. Se retiró Chunky únicamente de la copia cliente aislada; el siguiente ingreso funcionó. No se tocó el cliente habitual ni el servidor real.
- `clean build`, las ocho regresiones Java y los ocho verificadores estáticos pasaron para el artefacto actual. **Pendiente en juego:** aplicación efectiva de los mixins, captura/guiado/colisión Radar Spear, callback, cambio de bloque, ausencia de efectos Warium, rotación e IFF. El Ciclo 2E sigue abierto.

## Ciclo 2D.1: cardinalidad geométrica de Radar Spear

- Se corrigió en `RadarSpearAreaDamage.impactChecked` el factor Z del AABB candidato. La expresión previa era `(long)(x1-x0+1)*(y1-y0+1)*(z1-y0+1)`: mezclaba `y0` con el eje Z. Para límites X=10..12, Y=60..64 y Z=200..207 contaba 2.220 celdas en lugar de 120; otros desplazamientos podían subestimar el volumen y permitir recorrer más celdas que el límite previsto, o producir un conteo no positivo y rechazar la detonación.
- El conteo ahora pasa por `RadarSpearBlastGeometry.inclusiveCellCount`, que deriva cada dimensión de su propio par de límites, amplía antes de restar, rechaza rangos invertidos y usa `Math.multiplyExact` para no permitir overflow silencioso. Un error de rango o desbordamiento aborta la operación de forma conservadora. El límite existente de 4.096 celdas y el radio no cambian.
- La auditoría del recorrido confirma que los bucles enumeran X=`x0..x1`, Y=`y0..y1`, Z=`z0..z1`; las cotas del cubo mundial transformado se acumulan en sus ejes respectivos; y la pertenencia física sigue decidiéndose por `intersectsWorldSphere` en unidades mundiales. `worldSphereBoundsInPlot` solo acota enumeración: transforma las ocho esquinas del cubo envolvente mundial. La pose de Sable se usa en las conversiones inversa/directa y el radio de 3 bloques no se escala con la nave.
- `PlotView` sigue tratando la posición de bloque en el plot como entrada para obtener su `ChunkPos`, valida ese chunk con `LevelPlot.contains`, convierte el chunk global a local con `LevelPlot.toLocal` únicamente al llamar `LevelPlot.getChunk`, y lee estado/forma desde ese chunk y el `EmbeddedPlotLevelAccessor`. No mezcla coordenadas locales del plot con bloques del mundo principal.
- `radarSpearBlastGeometryRegression` ahora cubre cardinalidad de ejes muy distintos (120), rangos alrededor de cero y negativos, una celda, 4.095/4.096/4.097 celdas, rangos invertidos en X/Y/Z y overflow de producto. También corrige la comprobación de frontera de chunk para convertir posiciones de bloque 15 y 16, y verifica la frontera negativa. Continúan sin cambios los perfiles estrictos, la detonación solo desde colisión real, el Sable designado, el radio de 3 bloques y las exclusiones de Warium, explosiones vanilla, fragmentación, ERA, tareas diferidas y fuerzas artificiales.
- No se añaden perfiles ni efectos. Radar Spear nativo continúa en Warium. La ejecución y las reacciones físicas dentro de Minecraft siguen sin validarse en este ciclo.

## Ciclo 2D: ojiva estructural de área Radar Spear

### Ruta nativa examinada

- JAR cliente/servidor `wariumce-1.0.4.jar`, SHA-256 `AA0DD2FA8B5C8C2B4FFF40B4FE2189D94BC9D07240F43A5E6AF95D109B39E27E`.
- `RadarSpearMissileProjectileEntity.onHitBlock/onHitEntity` llama primero a `AbstractArrow` y después a `TankFireProjectileHitsBlockProcedure.execute`. En un proyectil no gestionado, `RadarSpearFlightProcedure.execute` también inicia ese procedimiento al exceder el tiempo, tocar agua, acercarse a entidades `wariumce:warm` o detectar la etiqueta `wariumce:bullet`; la rama bullet/warm puede diferir un tick.
- `TankFireProjectileHitsBlockProcedure` ejecuta `DamagesProcedure` y `HeavyCrackProcedureProcedure` sincrónicamente y programa, un tick después, 25 proyectiles `HVParticleProjectileEntity` con daño base 5, knockback 1, crítico, velocidad 4 e inexactitud 22; después llama a `SmallExplosionProcedure` y descarta el misil. Esos proyectiles conservan el movimiento del misil, pero esta ruta no fija su propietario CMI.
- `SmallExplosionProcedure` endurece dirt dentro de un volumen discreto X/Z de -6 a +5 y Y de -3 a -2 si hay un bloque no aire encima. En seco crea un efecto cliente y llama a una explosión vanilla de fuerza 3/TNT y otra de fuerza 7/NONE; en líquido añade una segunda explosión 3/TNT y un proyectil de efecto de agua. Produce flash/sonidos y 75 proyectiles HV adicionales (daño base 0, knockback 3, pierce 2, velocidad 1–1.2, inexactitud 45). El procedimiento de impacto de HV tiene lógica de daño propia, así que esos proyectiles no se consideran visuales inocuos.
- `HeavyCrackProcedureProcedure` tiene conversiones escalonadas de concreto y muros, degradación de piedra, ladrillos, pizarra y piedra negra, destruye obsidiana/obsidiana llorosa, y delega bloques etiquetados `wariumce:era` a `ERAProcedureProcedure`. Esa ruta ERA puede degradar/destruir ERA y metal adyacente y llama explosiones/procedimientos secundarios.
- `DamagesProcedure` incluye daños especiales a motores/tanques/tambores de munición y trabajos diferidos que pueden invocar `MicroExplosionProcedure` en +20, +40 y +50 ticks. Las colas guardan mundo/coordenadas sin el contexto de autorización CMI.

### Implementación CMI

- Se añadió el perfil independiente `explosive_sable`. Solo `/cmi radarspearprofile <pos> explosive_sable` lo configura en un `RadarSpearMissileHardpointBlockEntity`; el comando lo marca gestionado y asigna al emisor. `direct_sable` sigue siendo un único impacto puntual. Si el perfil no existe, los lanzadores gestionados conservan el comportamiento previo; un perfil malformado o incompatible bloquea el lanzamiento. `interceptor`, `direct_living_base`, `direct_sable` y `explosive_sable` no se convierten entre sí.
- `RadarSpearBridge.impactBlock` envía exclusivamente `explosive_sable` a `RadarSpearAreaDamage`. El handler solo se llama desde el callback nativo real `onHitBlock`; la predicción/preflight no detona. `RadarSpearEntityMixin` y `RadarSpearFlightMixin` continúan suprimiendo las rutas nativas para misiles gestionados. Misiles no gestionados siguen en Warium.
- El radio geométrico de 3 bloques mundiales es una **envolvente CMI** tomada del valor 3.0/TNT que Warium pasa a `Level.explode`; no se presenta como radio exacto de la explosión vanilla. El adaptador transforma esa esfera al plot con la pose actual, examina formas de colisión transformadas (incluidas naves rotadas/escaladas) mediante `LevelPlot.getEmbeddedLevelAccessor`, limita celdas y operaciones, exige exposición por línea de vista dentro del plot, y lee chunks mediante `LevelPlot.toLocal/getChunk`. Los vecinos de las formas deben estar disponibles; chunks ausentes invalidan la detonación. Solo examina y modifica el plot/UUID designado; no escribe en las coordenadas de mundo que casualmente coincidan con el plot.
- El daño de área es una **adaptación CMI** de las conversiones explícitas de `HeavyCrackProcedureProcedure`, una degradación por bloque y por detonación. Solo procede con al menos dos bloques elegibles. Incluye los escalones conocidos de piedra/concreto/muros y la destrucción de obsidianas definida allí. Los bloques con block entity, desconocidos, ERA/blindajes sin ruta segura, obstáculos, posiciones sin chunk disponible y bloques protegidos quedan intactos. Cada bloque se revalida individualmente frente a FTB Chunks, MineColonies, propietario/IFF y objetivo actual; los tripulantes conocidos desconocidos, amigos o neutrales bloquean el efecto. El efecto usa `CommonHooks.fireBlockBreak`, `Level.setBlock(..., 3)` y `Level.destroyBlock(..., false)`, conservando actualizaciones normales de Minecraft/Sable.
- CMI consume el permiso antes de llamar listeners; cada modificación vuelve a comprobar contexto, pose, dueño, hostilidad, crew, chunk, estado y protección. La llamada no crea tareas diferidas y no invoca `Level.explode`, `TankFireProjectileHitsBlockProcedure`, `SmallExplosionProcedure`, `DamagesProcedure`, ERA, fragmentos ni entidades nuevas.
- **Sin implementación en este ciclo:** daño por explosión a entidades, fragmentación, hardening del área de dirt, ERA, daño especial de motores/tanques, explosiones diferidas, fuerza física y efectos visuales Warium. No hay fuerza explosiva Sable añadida. La ojiva estructural solo altera estados que aparecen en el mapa de HeavyCrack; los bloques no mapeados permanecen intactos.

### Estado

| Capacidad | Estado |
|---|---|
| Guiado Radar Spear | IMPLEMENTADO; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Impacto puntual Sable (`direct_sable`) | IMPLEMENTADO; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Ojiva estructural de área (`explosive_sable`) | IMPLEMENTADO en código acotado; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Daño explosivo a entidades | BLOQUEADO; no se reproduce para evitar daño a aliados/terceros |
| Fragmentación | BLOQUEADO; rutas nativas diferidas carecen de procedencia y afectan entidades |
| ERA | BLOQUEADO; su procedimiento causa destrucción adyacente y explosiones secundarias |
| Efectos diferidos | BLOQUEADO/suprimidos para la ruta gestionada |
| Fuerzas Sable | BLOQUEADO/no añadidas; no se encontró una fuerza Warium/Sable atribuible y acotable |
| Efectos visuales | BLOQUEADO/no reproducidos; el procedimiento original también crea proyectiles con daño |
| Radar Spear nativo | IMPLEMENTADO sin intervención de CMI cuando no está gestionado |

La implementación y regresiones estáticas demuestran la selección del perfil, los límites geométricos y las rutas de escritura del código; **no demuestran que los mixins se apliquen ni que un impacto dañe una nave dentro de Minecraft**. Las actualizaciones físicas que Sable active tras cada cambio y sus consecuencias indirectas siguen pendientes de validación en juego.

## Ciclo 2C.1: callback y perfiles estrictos

- En `AbstractArrow.tick`, el resultado de `Level.clip` se guarda como impacto de bloque, se consulta `HitResult.getLocation` y el punto devuelto se usa como extremo de `findHitEntity`. Si esta encuentra una entidad, sustituye el impacto de bloque. Sable puede devolver un resultado de casco cuya ubicación y `BlockPos` pertenecen al plot, mientras el inicio del rayo de entidades está en el mundo.
- `RadarSpearArrowCollisionMixin` envuelve exclusivamente esa llamada a `getLocation`: para un Radar Spear gestionado del servidor, identifica un plot cargado y convierte su punto a mundo para la búsqueda de entidades. Conserva el `BlockHitResult` original para el callback y la búsqueda nativa de entidades. En un misil nativo devuelve el resultado original; un plot ambiguo cancela y descarta el misil gestionado. Si otra integración ya alteró el punto, no intenta transformarlo otra vez.
- `RadarSpearCollisionCoordinates` distingue punto de mundo y de plot y verifica que el punto de plot pertenezca al bloque; la pose se copia antes de transformar. `SableFirstHit.Result` conserva punto mundial, punto y bloque de plot, nave, cara, estado, tick y copia de pose. `RadarSpearStructural` vuelve a confirmar la identidad, el tick y la pose antes del evento y antes de cambiar el bloque. La coincidencia espacial tolera hasta 1e-5 bloques tanto en mundo como en plot, además de exigir idénticos UUID, bloque y cara; empates de primer impacto dentro de esa distancia se rechazan.
- `RadarSpearImpact` ahora exige un perfil exacto en cada sitio de efecto: `direct_sable` (impacto puntual), `explosive_sable` (solo área estructural), `direct_living_base` o `interceptor`. El seguimiento puede conservar cualquiera de los perfiles válidos de Sable, pero no autoriza por sí mismo ningún efecto. Los contextos antiguos `direct_sable` no reciben permiso explosivo.
- **No acreditado en ejecución:** que la pose no cambie entre el `clip` nativo de Sable y el wrapper, la aplicación real y prioridad de los mixins, ni las interacciones con otros mods. Las pruebas geométricas aisladas y el análisis del bytecode no equivalen a una colisión observada en el juego.

## Ciclo 2C: impacto estructural Sable

- `SableFirstHit.evaluate` compara la primera colisión de bloques del mundo, entidades y formas de bloques de cada plot Sable. Distingue trayectoria libre, blanco, plataforma propia, aliado, otra nave enemiga, neutral, mundo y datos desconocidos. Las poses convierten la trayectoria mundial al plot y el impacto de vuelta al mundo. Empates ambiguos, datos descargados y presupuesto agotado se rechazan. `corridorClear` comprueba el tramo previo con el radio del misil; para el mundo principal revisa además formas de colisión y posiciones protegidas dentro del volumen.
- `RadarSpearBridge.allowedPath/preflight` permite que el tick nativo alcance el primer casco solo cuando es el Sable designado y el corredor previo es seguro. `RadarSpearEntityMixin` cancela la detonación Warium y entrega el `BlockHitResult` real a `RadarSpearStructural.impact`. Interceptores y blancos de entidad conservan sus rutas. CBC no utiliza el nuevo evaluador.
- `RadarSpearStructural` contrasta UUID, cara, bloque y punto mundial con el callback real; resuelve de nuevo el objetivo, IFF, propietario, plot, chunk y estado; rechaza claims FTB de cualquier equipo, colonias, entidades o datos no acreditados. Exige propietario conectado y vivo. Consume el contexto antes del evento externo `fireBlockBreak` para impedir reentrada y vuelve a verificar el estado y la autorización capturada antes de modificar el bloque.
- **Perfil estructural limitado:** reproduce tres conversiones directas de `HeavyCrackProcedureProcedure` de Warium: piedra → adoquín, ladrillos de piedra → ladrillos agrietados y hormigón reforzado Warium → hormigón agrietado. Otros bloques no reciben daño. `Level.setBlock(..., 3)` conserva notificaciones, callbacks de bloques y `SableCommonEvents.handleBlockChange`; no se ejecutan la explosión, ERA ni fragmentos originales, ni se añade impulso artificial. Las reacciones indirectas de bloques y física no están totalmente acotadas.
- Se corrigió también `TrajectorySafety`: `LevelPlot.contains` recibe el chunk global, pero `getChunk` requiere `toLocal`. El bytecode de `SubLevelContainer.getChunk` confirma esa conversión; antes podía rechazar bloques de un plot válido. La política de tiro de CBC permanece igual, salvo que ahora consulta el chunk correcto.
- **Sin acreditar en juego:** la aplicación de mixins, la relación completa entre colisión nativa de flecha y callback en naves rotadas o móviles, la resolución de protección FTB/MineColonies y las reacciones posteriores de Sable y otros mods. El ciclo 2C.1 adapta el extremo de búsqueda de entidades que 2C recibía en coordenadas de plot. El trazado propio usa una forma de colisión de contexto vacío que puede diferir de la nativa y rechazar el callback. Estas situaciones necesitan prueba integrada; las pruebas aisladas no demuestran todos los casos de primer obstáculo o concurrencia.

## Ciclo 2B: procedencia e impacto Radar Spear (parcial)

**Estado al terminar 2B:** el impacto explosivo y el daño estructural a Sable seguían pendientes. El ciclo 2C añade únicamente el perfil estructural puntual descrito arriba; no invoca explosiones ni fragmentos nativos.

- `RadarSpearLaunchMixin.cmi$captureLaunch` intercepta la cuarta llamada `BlockWeaponRecoil.spawn` dentro de `MissileHardpointFireProcedure.execute`: la fábrica de esa rama construye `RadarSpearMissileProjectileEntity`. Se captura el misil exacto y el block entity original antes de insertar la entidad y reemplazar el hardpoint. La marca nativa/gestionada procede de la configuración del hardpoint, nunca de proximidad ni solo de `cmiOwner`. Un candidato gestionado incompleto o no autorizado no se inserta; un lanzamiento nativo sigue la llamada original. Ya no existe una decisión irreversible por falta temporal de `LX/LY/LZ` en el primer tick.
- `RadarSpearImpact.arm/valid/matches/consume/cancel` conserva en el NBT del misil UUID de lanzamiento y efecto, dueño, plataforma opcional, tipo de munición, objetivo, modo, autorización, vencimiento a 130 ticks y estado. El guiado vuelve a contrastar identidad y datos capturados, y la acción se consume antes de ejecutarse. El estado se cancela al descartar. El contexto no mantiene referencias de entidades ni acredita persistencia ante restauración de copias anteriores del mundo.
- **Ofensiva parcial:** `RadarSpearImpact.impact` puede causar daño directo solo a una entidad viva hostil designada que colisiona con el misil después del armado, con dueño vivo en el mismo nivel, IFF y zona protegida reevaluados. El perfil propio usa `AbstractArrow.getBaseDamage()` finito y acotado a 10, valor establecido por la fábrica Warium del hardpoint; no reproduce el multiplicador de velocidad ni el daño explosivo nativos. En estructura Sable o bloques no produce daño.
- Al terminar 2B, `preflight` rechazaba también el casco enemigo. El ciclo 2C introduce una evaluación separada del primer impacto para el Radar Spear con objetivo Sable, manteniendo `TrajectorySafety.clear` como política general.
- **Interceptor:** contexto de perfil separado; solo se neutraliza el proyectil hostil designado, sin detonación. La neutralización y el impacto ofensivo consumen la autorización una sola vez. No se generan fragmentos ni se programan tareas destructivas diferidas: por ello no se pretende que el contexto ya propague la autoría de los efectos diferidos nativos.
- Sable `BlockGetterMixin.clip` transforma el rayo a coordenadas del plot y devuelve el resultado local. El método propio de Radar Spear pasa el `BlockHitResult` al impacto sin convertirlo, pero la ruta completa todavía no se ha ejecutado. `LevelChunk.setBlockState` llama a `onRemove/onPlace` y Sable `LevelChunkMixin`/`SableCommonEvents.handleBlockChange` actualiza estructuras, física y bloques flotantes. Falta un adaptador que acredite la asociación exacta de la colisión y limite estas consecuencias frente a aliados antes de habilitar destrucción de bloques.
- Neo HEAP/HEF usan `HEAPExplosion`/`ShellExplosion` y, después, una ráfaga distinta de fragmentos. El contexto puede servir como identidad, pero no existe todavía una transferencia y validación por fragmento ni se habilitaron esas ojivas.
- La captura depende de que el block entity del hardpoint exista al invocarse `BlockWeaponRecoil.spawn`; si falta, no puede conocerse si la solicitud era gestionada y se preserva la llamada nativa. Asimismo, el NBT no es un registro global que permita recuperar una marca si otro código elimina simultáneamente tanto el contexto como la marca. La aplicación real del mixin y estas transiciones siguen sin probarse.


## Ciclo 2: impactos y explosiones

**Resultado parcial:** las explosiones automáticas de las familias prioritarias siguen bloqueadas. No se ha acreditado un volumen seguro que incluya fragmentos, mutaciones privadas y fuerzas Sable, ni se ha implementado un impacto explosivo seguro sobre una aeronave. No se añade una segunda explosión ni una fuerza artificial.

| Munición | Impacto nativo | Daño Sable | Adaptación CMI | Automatización |
|---|---|---|---|---|
| Radar Spear Warium | `RadarSpearMissileProjectileEntity.onHitEntity/onHitBlock` llama a la flecha y luego a `TankFireProjectileHitsBlockProcedure`, que ejecuta efectos propios y programa fragmentos y `SmallExplosionProcedure` | Colisión y daño estructural completos **no acreditados** | `RadarSpearBridge.initialize/impactEntity` y `RadarSpearEntityMixin`: estado gestionado únicamente tras validar; impacto gestionado cancela efectos nativos, neutraliza solo proyectil hostil designado cuando corresponde y descarta el misil. Contra bloques y otras entidades descarta sin explosión | Guiado condicionado; impacto explosivo **bloqueado**, por lo que no equivale a daño de ojiva |
| Neo Warfare, lanzador con AP Shot o Solid Shot CBC | `tickWarheads`/`clipAndDamage` gestionan ojiva e impacto de contraption | Integración CBC/Sable de impactos y fuerzas presente; recorrido completo de esta contraption **no acreditado** | `LauncherSupport.inspect` acepta únicamente clases inertes concretas y el guiado permitido | **Condicionada** a selección, IFF, geometría y carga; sin prueba en juego |
| Neo Warfare, HEAP / HEF | `HEAPShellProjectile.detonate` usa `HEAPExplosion` y ráfaga HEAP; `HEFShellProjectile.detonate` usa `ShellExplosion` y fragmentación | CBC/Sable integra fuerzas y colisiones; propagación completa y seguridad de cada efecto **no acreditadas** | No se vuelve a ejecutar la explosión nativa ni se inventa un radio | **Bloqueada**: los valores configurables de potencia no son un límite seguro de fragmentación/daño |
| Otras ojivas CBC y municiones de CBC: Warium Projectiles | Según clase, pueden usar espoletas CBC o procedimientos Warium distintos; `IRSeekerBCProjectile` llama a `ArtilleryHitProcedure`, `MediumBombBCProjectile` a `MediumExplosionProcedure` | Impactos CBC tienen integración Sable; los efectos privados de Warium no quedan acreditados por ello | Sin adaptación explosiva adicional en este ciclo | **Bloqueada** si no figura en la lista explícita inerte; efectos especiales pendientes de revisión individual |

`CombatServices.canEngage` vuelve a resolver el contacto y comprueba su posición y relación actuales antes de autorizar una neutralización. La atribución `cmiOwner` no identifica por sí sola un disparo gestionado: `CreateMilitaryIntegration.explosion/spawned`, `ShotContext` y `CannonInterceptionMixin` distinguen la marca de gestión explícita. El contexto se propaga para disparos síncronos efectuados por `WeaponAdapters.fire`; los pulsos diferidos de Better Targeting no quedan acreditados como marcados por ese contexto.

En la versión del ciclo 2, la decisión de Radar Spear se tomaba en el primer tick usando `LX/LY/LZ` y el bloque del lanzador. Esa limitación se aborda en el ciclo 2B mediante la captura anterior a la sustitución; los límites de esa captura y las pruebas pendientes se describen arriba. La copia NBT de las regresiones es en memoria, no un lanzamiento real.

Los eventos `ExplosionEvent.Detonate` solo cubren explosiones que realmente los emiten y conservan una entidad fuente marcada. Los procedimientos Warium pueden modificar bloques, crear fragmentos y programar trabajo por otras rutas, incluso sin fuente; filtrar el evento no protege esos efectos ni demuestra protección de todas las fuerzas Sable. La neutralización de CMI sigue siendo no explosiva y limitada a un contacto hostil identificado.


## Ciclo 1: guiado IR y geometría Sable

- **Defecto confirmado:** los hooks `InfraredGuidanceMixin.cmi$acquire` y `cmi$guide` cancelaban siempre `tickGuidance` y `guideEntity`, incluso para buscadores sin control de CMI. El indicador `cmiOwner` es atribución y no autoriza sustituir el guiado. Ahora se reconoce un indicador explícito persistente `cmiManagedGuidance`, además de `cmiDesignated` y `cmiInterceptor` como modos existentes. El lanzador CMI escribe el indicador en el NBT del buscador antes del lanzamiento. Un NBT de modo malformado mantiene el control conservador de CMI.
- Al perder la designación, se conserva el indicador de modo para impedir el retorno silencioso a la adquisición nativa. En vuelo, un blanco inválido o perdido, el propietario ausente o la pérdida de autorización provocan la liberación de reservas identificables y `discard()` del misil administrado. El bytecode revisado no muestra una llamada directa a `detonate` desde esa eliminación, pero los efectos indirectos de `Contraption.stop()` aún requieren comprobación en juego. `IRSeekerTargetLossMixin` también limita su intervención a estados explícitamente administrados. Los casos sin indicador conservan los métodos nativos y no requieren `cmiOwner`.
- **Defecto confirmado:** la búsqueda de naves consultaba una caja mundial sin ampliar por el radio de seguridad, aunque luego ampliaba sus formas de colisión; podía omitir una estructura cercana. La consulta de estado tampoco acreditaba el `LevelPlot` de la nave. Ahora `TrajectorySafety.clear` amplía la búsqueda mundial, invierte la pose al espacio de plot, verifica `plot.contains` y obtiene `plot.getChunk`; el estado procede de ese `LevelChunk` y la forma se evalúa en el `ServerLevel` padre de Sable. Chunk ausente, geometría inválida o presupuesto agotado rechazan el tiro.
- El código conserva el control de entidades, IFF, zonas FTB y MineColonies y continúa evaluando la trayectoria tras geometría enemiga penetrable. El límite de 12.000 operaciones sigue vigente.
- La búsqueda local divide segmentos largos en tramos contiguos para evitar una caja cúbica excesiva en diagonales; cada tramo conserva el margen completo y comparte el presupuesto. También comprueba los extremos dentro de una forma antes del raycast, ya que `AABB.clip` puede omitir un segmento que comienza dentro.
- Los plots son regiones de chunks reservados del mundo padre, no un `Level` independiente. Comprobar el plot resulta necesario para no confundir coordenadas del plot con bloques de otro almacenamiento. La semántica de `getLevel`, `getPlot`, `contains`, `getChunk` y `transformPositionInverse` fue contrastada con el bytecode de Sable fijado.
- **Pendiente:** la aplicación real de los mixins, el traslado del NBT durante el lanzamiento, el comportamiento de chunks de plot durante carga/descarga y las colisiones rotadas requieren validación en una JVM de Minecraft. La inspección estática no demuestra que un disparo sea seguro en todas las maniobras.

| Área | Implementado | Limitaciones |
|---|---|---|
| Build | Gradle, JDK 21, NeoForge 21.1.265, hashes de dependencias, JAR y fuentes | Requiere originales externos; no redistribuye mods |
| CBC | Reutiliza cálculo/control Better Targeting, atribución de disparos, autorización y seguridad nueva | La automatización rechaza munición explosiva con radio no acreditado y proyectiles sin vida finita acreditada |
| Neo medianos/rotativos | Lectura de munición y materiales, parámetros reales, mezcla localizada en CannonProbe | No todas las municiones especiales tienen un perfil de seguridad aprobado |
| Montura compacta | Control específico por su propia clase, límites nativos, cálculo y disparo verificado, sistema local Sable | Presupuesto y carga de geometría pueden rechazar tiros válidos; no probado en juego |
| Lanzador Neo | Coordinador propio, designación estable de buscador IR, velocidad nativa, disparo nativo | Solo configuración explícita de munición/ojiva inerte conocida; otros guiados y explosivos bloqueados |
| Sable | Transformaciones reutilizadas, corrección de azimut del radar giratorio, geometría en coordenadas locales | No acredita corrección total de todos los casos de los issues #122/#136/#140 |
| Contactos | Identidad Entity/Sable diferenciada, caché compartida, resolución actual antes de actuar | Escaneo acotado; no puede observar cuerpos o chunks descargados |
| IFF | Propietarios, equipos/listas de Better Targeting/FTB, códigos, hostilidad explícita, neutros y desconocidos | Todas las colonias protegidas conservadoramente; no implementa diplomacia detallada MineColonies |
| Guiado IR Neo | Adquisición hostil/caliente, identidad en NBT, transferencia, corrección nativa, pérdida segura | Depende de que el flujo de munición preserve NBT; no validado jugando |
| Radar Spear | Captura explícita, contexto de un solo uso, guiado, neutralización, daño directo a entidad y perfil estructural puntual Sable | Solo tres sustituciones Warium; explosiones desactivadas y funcionamiento integrado sin probar en juego |
| Intercepción CBC | Exclusión limitada de la protección de colisión para amenazas identificadas, neutralización sin explosión | Selección/tiro dependen de filtros y perfil autorizado; no convierte cualquier entidad en interceptable |
| Explosiones gestionadas | Evento NeoForge de detonación: amenazas soportadas y hostiles con exposición pueden neutralizarse; se excluyen entidades no hostiles y bloques protegidos de las listas del evento | Solo explosiones que pasan por este evento y conservan marca y atribución CMI; no acredita explosiones privadas de cada mod ni fuerzas calculadas por rutas ajenas |
| Misil contra misil | Modo IR optativo, amenaza entrante, reserva previa, transferencia de reserva y contacto barrido | Neutralización no explosiva; configuración nativa y gestión de blanco deben comprobarse en juego |
| Warium CBC | Inspección de parámetros de arma distinta y limpieza de buscador IR tras perder entidad | Su trayectoria de flecha especial no se presenta como CBC estándar ni queda automatizada por el perfil genérico |
| CIWS Warium | Comportamiento hostil original conservado | No se añade unidad controlable; defensa cercana se canaliza mediante cañones compatibles |
| Persistencia | Datos propios, propietarios/hostilidades/calor, NBT local, referencias resueltas por identidad | Ensamblar/desmontar y migraciones de posiciones requieren validación; no promete persistencia universal |
| FireController | Sustituye llamada errónea a `super.read` dentro de `write` por serialización del padre | Aplicación del redirect no ejecutada en juego |

## Límites de seguridad importantes

- Se exige identidad hostil acreditada antes de automatizar, incluido el contacto de respaldo de Radars.
- Se vuelve a consultar geometría y entidades al disparar. Chunks desconocidos o presupuestos agotados rechazan el disparo.
- Se inspecciona la vida finita conocida del proyectil, no solo el punto de intercepción. La geometría enemiga penetrable no termina artificialmente la inspección.
- Predecir todas las maniobras futuras de terceros durante el vuelo no está demostrado. Este código no certifica ausencia de fuego amigo en una partida.
- Las referencias a proyectiles interceptables se limitan por clase/registro; no se habilita daño global a cualquier proyectil vanilla.
- No se ha aplicado Mixins en una JVM Minecraft ni probado el conjunto con dos clientes. Es una entrega de desarrollo con funciones y bloqueos explícitos, no la finalización de las ocho fases solicitadas.

## Aceptación pendiente, sin ejecutar

Carga del mod y mixins sin Simulated Missiles; radar terrestre y a bordo; blancos Sable en rotación; tiro CBC y Neo con munición real; montura compacta; atribución de proyectiles manuales; dos jugadores aliados/neutrales/hostiles; una colonia y reclamación; reserva entre dos lanzadores; misil IR contra nave caliente y contra proyectil; descarga y restauración del mundo. Cada prueba debe comprobar ausencia de fuego al perder identidad o geometría, además del impacto previsto.
## Ciclo 2I — radar de misiles multibloque Neo Warfare

Estado: implementado a nivel estático; la detección e intercepción real quedan **PENDIENTES DE VALIDACIÓN EN JUEGO**. No se inició Minecraft, cliente, servidor ni GameTest.

| Capacidad | Estado |
|---|---|
| Entity type Neo Warfare | IMPLEMENTADO: `cbcmodernwarfare:munitions_contraption`, registrado por `CBCModernWarfareEntityTypes.MUNITIONS_CONTRAPTION` como `MunitionsPhysicsContraptionEntity`. |
| Tag radar PROJECTILE | IMPLEMENTADO: `data/create_radar/tags/entity_type/radar_projectile.json` añade únicamente ese tipo a `create_radar:radar_projectile`, con `replace:false`. |
| Inclusión en barrido físico | CONDICIONADO: Radars consulta `AbstractContraptionEntity` cuando `scanContraptions` está activo. CMI exige `scanProjectiles` para todo candidato y, tras resolver la entidad, exige también `scanContraptions` solo si es una contraption. Un proyectil normal sigue siendo elegible con `scanContraptions=false`. |
| UUID del track | IMPLEMENTADO: `RadarTrack(Entity)` guarda el UUID real de la entidad en formato canónico (`UUID.toString()`); CMI vuelve a resolver esa misma entidad en el `ServerLevel`. |
| Velocidad | CONDICIONADO: el misil publica su movimiento físico actual mediante `setContraptionMotion`/`getDeltaMovement`; el solver usa esa velocidad como snapshot lineal. Thrust y guiado pueden cambiarla durante el vuelo, así que no se garantiza una intercepción de un blanco maniobrante. |
| AABB | CONDICIONADO: Warium deriva los límites de los bloques de la contraption, añade su margen nativo y Create usa esos límites para la caja de la entidad. CMI reutiliza la caja real del entity contact; no inventa dimensiones. |
| Ownership/provenance | IMPLEMENTADO CONDICIONADO A OWNER CONOCIDO: `ShotOwnershipMixin` envuelve el `fireShot` real del lanzador y `EntityJoinLevelEvent` copia owner/IFF/platform desde ese contexto. Owner ausente conserva UNKNOWN y no habilita fuego. |
| IFF y selección CIWS | IMPLEMENTADO EN CÓDIGO: se mantiene la ruta de 2G (`PROJECTILE` → UUID → entity viva → contacto actual → owner/IFF ENEMY → `canEngage` → aproximación, lock, solver y trayectoria segura). |
| Colisión/neutralización | CONDICIONADO: el CBC projectile gestionado solo neutraliza en `canHitEntity/onHitEntity` después de una colisión física y revalidación IFF. El solver por sí solo nunca descarta el misil. Pendiente confirmar la colisión efectiva en juego. |
| Compatibilidad WORLD 2F/2G y Sable 2H | Sin cambios de arquitectura; Sable embarcado sigue bloqueado como antes. |

Validación estática del ciclo: `clean build --offline` y las 12 regresiones JavaExec pasaron. Pasaron los 11 verificadores anteriores más `Verify-MultiblockMissileRadar.ps1` (12 en total). `git diff --check` pasó. El artefacto compilado tuvo SHA-256 `8AD901B6C5986E13C1BE455795C45DC9346CDD22FB2E9987D40D8AF52A443134`.

Detalle de filtros de Radars 0.4.9.4: el misil no hereda `Projectile`, por lo que su **consulta física** entra por `scanContraptions`. El tag cambia la clasificación posterior a PROJECTILE antes del fallback CONTRAPTION. CMI siempre requiere `scanProjectiles`; después de resolver el UUID al entity real requiere `scanContraptions` solo para `AbstractContraptionEntity`. Así se conserva el filtro físico sin bloquear los proyectiles CBC normales. El radar conserva intactos sus filtros y parámetros.

## Ciclo 2I.1 — corrección del tag 1.21.1 y filtro CIWS

Se corrigió la ubicación del recurso al directorio singular `data/create_radar/tags/entity_type/radar_projectile.json`, requerido por Minecraft 1.21.1. El contenido permanece exactamente `replace:false` y el único valor `cbcmodernwarfare:munitions_contraption`; la ruta plural obsoleta se elimina y se rechaza tanto en fuentes como en el JAR.

El filtro CIWS diferencia el tipo real después de resolver el UUID del track: `scanProjectiles` es obligatorio siempre; `scanContraptions` es adicional solo para entidades `AbstractContraptionEntity`. La misma política se aplica al adquirir candidatos y revalidar un lock/reserva. IFF, frescura, ownership, solución, corredor y disparo nativo no cambian. Validación runtime pendiente.

**Validación estática 2I.1:** `clean build --offline` pasó con las 12 regresiones Java. Pasaron los 12 verificadores y `git diff --check`. El JAR contiene la etiqueta en la ruta singular y no contiene la ruta plural. SHA-256: `7C54267AFDF6402B57FA2CC87A975DD2C5A559559CFD154AE611BC57B1460C5F`. La comprobación de juego sigue pendiente; no se modificaron instalaciones activas.

## Ciclo 2J — predicción de movimiento CIWS (implementación estática)

El seguimiento avanzado se aplica solo a `MunitionsPhysicsContraptionEntity` que ya llegó como track PROJECTILE del `IRadar` enlazado y superó resolución UUID, entidad viva, identidad de proyectil, owner conocido, IFF ENEMY y `canEngage`. Los proyectiles CBC normales conservan la ruta lineal y el lock de dos barridos de 2G; no se exige una tercera muestra para ellos.

`CiwsMotionTracker` conserva únicamente valores (dimensión, montura, radar, propietario/plataforma, UUID del blanco, procedencia, `scannedTime`, posición y velocidad), sin referencias a niveles o entidades. Cada muestra requiere un timestamp de barrido distinto, finito, no futuro y exactamente cinco ticks posterior al anterior. Dos registros con el mismo timestamp no incrementan la historia. Saltos, retrocesos, datos malformados, cambio de radar/owner/plataforma o pérdida de autorización reinician o eliminan la historia. La ventana es de tres muestras, el máximo 2.048 historiales, se retira lo que sale de los tracks actualmente autorizados y se purga por dimensión, chunk, montura, edad o parada del servidor.

`CiwsMotionPolicy` calcula aceleración vectorial como `(v₂-v₁)/Δt` con Δt tomado de `RadarTrack.scannedTime()`; los tres ejes usan la misma fórmula en bloques/tick² inferidos del espacio de `TargetMotion`. Con dos barridos no se atribuye una aceleración estable: CMI conserva un solver lineal y solo admite el disparo si el error por la aceleración observada cabe en el margen real de impacto. Con tres, compara las dos aceleraciones consecutivas y el error de posición extrapolada; una divergencia que consume el margen de la hitbox clasifica la maniobra como UNRELIABLE y no produce plan de fuego.

Bytecode del SHA fijado de Better Targeting 1.0.0.1 confirma que el tercer campo de `TargetMotion` se guarda como `acceleration`; `at(t)` calcula `p + v·t + a·B(t)` en X/Y/Z, con aceleración limitada al horizonte de 30 ticks y continuación posterior a velocidad constante equivalente. El solver usa `TargetMotion.at()` tanto al resolver el disparo como en `approach()`; `finish()` construye la solución a partir de ese mismo `approach` y su `intercept`. `path(direction,lifetime)` simula exclusivamente el proyectil CBC mediante `ShellModel.step`, no la posición del blanco. El bytecode no declara unidades textuales; los ticks discretos del solver y la suma dimensional de posición/velocidad/tiempo/aceleración respaldan las unidades de juego anteriores, que se describen como inferidas del contrato matemático.

La estimación de incertidumbre se compara contra `CiwsPolicy.tolerance` derivada de la esfera inscrita en la AABB real y contra el miss del solver. CMI aplica `0.5 × incertidumbreAceleración × vuelo²` y descuenta el miss del margen. En movimiento lineal la magnitud de aceleración observada también cuenta como incertidumbre no modelada. En aceleración confirmada se usa el vector medido en el solver y la divergencia observada como incertidumbre. Esta tendencia no garantiza que el misil mantenga la maniobra durante el vuelo: un cambio posterior puede invalidar la predicción.

El solver continúa siendo `FiringSolver.Arc.LOW` con física, vida útil y balística nativas del cañón; la misma instancia/modelo se usa para el `approach(aim.barrel())`, y el corredor sigue siendo la trayectoria del proyectil. Antes del fuego se vuelve a leer el track del radar vinculado y se exige el mismo UUID, timestamp reciente y filtros habilitados; también se revalidan entidad, owner, plataforma, relación, permiso, historial/procedencia idénticos, arco, solución, seguridad y reserva. El permiso scoped continúa siendo de un solo uso. El solver no crea, guía ni neutraliza munición. Si se pierde radar, entidad, ownership/IFF o historial, CMI limpia la autorización/lock; una maniobra abrupta o un error que supera el margen inhibe el disparo.

| Capacidad | Estado |
|---|---|
| Seguimiento entre barridos | IMPLEMENTADO; solo tracks físicos autorizados, ventana de tres muestras y límites/limpieza explícitos. |
| Estimación de aceleración | IMPLEMENTADO; diferencia vectorial de velocidades y timestamps reales. |
| Confirmación de tendencia | IMPLEMENTADO; comparación de dos aceleraciones más residuo de posición. |
| Evaluación de incertidumbre | IMPLEMENTADO; contra AABB real, miss del solver y vuelo previsto. |
| Predicción acelerada | IMPLEMENTADO ESTÁTICAMENTE para tendencia estable, usando el modelo fijado; posible maniobra futura no queda garantizada. |
| Solver nativo | IMPLEMENTADO; `FiringSolver` resuelve y `approach` valida con el mismo `TargetMotion`; el path es la trayectoria de la bala. |
| Alineación actual | CONDICIONADO a orientación efectiva de la montura; se reevalúa su cañón real antes de fuego. |
| Disparo CIWS | CONDICIONADO a balística/loadout soportado, lock, autorización, corredor y cooldown nativo. |
| Intercepción de proyectil normal | CONDICIONADO a colisión real y hook existente; sin neutralización predictiva. |
| Intercepción de contraption maniobrante | CONDICIONADO a tres barridos consistentes; giro brusco inhibe fuego. |

No se introducen sensores, threads, predicción de impacto como neutralización, cambios de física, daños ni cambios en filtros radar. Sin trayectoria runtime observada, esta entrega no acredita interceptaciones en partida.

## CMI-PE-01 — compatibilidad observacional con Project: Explosive 0.7.2-beta

CMI reconoce nueve IDs de entidad exactos desde `ProjectExplosiveCompatibility`; no importa clases Java de Project: Explosive, no usa reflexión y no añade ese mod a Gradle ni a `dependency-hashes.properties`. El reconocimiento solo da una clasificación de compatibilidad futura. Todas las clases devuelven `acquirable=false` y `neutralizable=false`; además, el CIWS rechaza cualquier entidad del namespace `projectexplosive`, incluidos IDs futuros no catalogados. El gate se comprueba durante adquisición, retención de lock/reserva y revalidación del track antes del disparo. El radar físico `IRadar.getTracks()` sigue siendo la única fuente de detección.

| EntityType | Clasificación CMI | Adquisición automática | Neutralización |
|---|---|---|---|
| `projectexplosive:small_bomb` | Reconocido; candidato para futura interoperabilidad | BLOQUEADA | BLOQUEADA |
| `projectexplosive:big_bomb` | Reconocido; candidato para futura interoperabilidad | BLOQUEADA | BLOQUEADA |
| `projectexplosive:fire_bomb` | Reconocido; candidato para futura interoperabilidad | BLOQUEADA | BLOQUEADA |
| `projectexplosive:nuclear_bomb` | Reconocido; candidato para futura interoperabilidad | BLOQUEADA | BLOQUEADA |
| `projectexplosive:icbm` | Reconocido; candidato para futura interoperabilidad | BLOQUEADA | BLOQUEADA |
| `projectexplosive:icbm_booster` | Reconocido; entidad auxiliar excluida del CIWS | BLOQUEADA | BLOQUEADA |
| `projectexplosive:smoketrail` | Reconocido; estela excluida del CIWS | BLOQUEADA | BLOQUEADA |
| `projectexplosive:fire_trail` | Reconocido; estela excluida del CIWS | BLOQUEADA | BLOQUEADA |
| `projectexplosive:multi_block` | Reconocido; entidad auxiliar excluida del CIWS | BLOQUEADA | BLOQUEADA |

La lista se contrastó con el registro de tipos de entidad de la decompilación local: `ProjectExplosive-0.7.2-beta/lil1llilol11/lo1i1iloioil/l1loi01l110i1o10ooiliol0.java`, líneas 10–46. El README de esa inspección identifica el JAR de origen SHA-256 `3CBF5A76D8B6123A489DF54F0165C31DF6C5F6993DD07E9506232FCB3F3CEBB2` y advierte que Vineflower produjo una reconstrucción aproximada. No se copió código PE. Su licencia indicada en el inventario local es All Rights Reserved; CMI solo incorpora IDs del registro y contratos propios.

El código decompilado del JAR fijado de Create: Radars 0.4.9.4 (`RadarScanningBlockBehavior.scanForEntityTracks`) consulta `getEntitiesOfClass(Projectile.class)` cuando `scanProjectiles` está activo; el escaneo se actualiza cada cinco ticks y limita después por alcance, FOV y rango vertical. `TrackCategory.get` clasifica como `PROJECTILE` por la clase `Projectile` salvo que una etiqueta aplicable tenga precedencia. Los cinco tipos principales PE están registrados sobre `ThrowableProjectile`; las dos estelas son `AbstractArrow`, también proyectiles, así que esos siete pueden aparecer en el barrido físico si el sensor está activo y cubre la posición. Esto solo acredita compatibilidad estática del contrato de consulta, no una pista en partida. `icbm_booster` y `multi_block` están registrados como `Entity` y no entran por la consulta selectiva `Projectile.class`; siguen excluidos de CMI.

### Contrato de colisión auditado estáticamente

En CBC 5.11.7, `AbstractCannonProjectile.clipAndDamage()` recorre entidades candidatas en una AABB barrida, consulta `canHitEntity`, valida intersección/segmento y luego despacha `onHitEntity` para las colisiones aceptadas. El filtro nativo de `canHitEntity` pasa por `Projectile.canHitEntity`, rechaza blancos `Projectile` y entidades no impactables; la cadena vanilla consultada usa `canBeHitByProjectile()`/`isPickable()`, no `isAttackable()`. La Mixin CMI existente solo sustituye el filtro para disparos gestionados y blancos que ya acepta `CombatServices.projectile`.

Los IDs PE no se añadieron a `CombatServices.projectile`, por lo que la Mixin no les concede la colisión/neutralización gestionada. Los tipos de bombas registrados como `ThrowableProjectile` siguen excluidos por el filtro nativo CBC para otros proyectiles; no se habilitó un bypass. `discard()` elimina la entidad a través de la ruta normal de Minecraft, pero la auditoría estática no demuestra que eliminar una ojiva nuclear cancele cualquier efecto o trabajo ya creado fuera de su tick. La continuidad de efectos post-detonación, callbacks de todas las bombas PE, interacción completa con el CBC runtime y resultado de explosiones quedan sin autorización y sin prueba runtime.

La ruta de CBC acumula impactos de una pasada y puede llamar a `onHitEntity` para cada candidato recolectado; la revisión de bytecode no encontró una salida inmediata del bucle tras la primera llamada. La posibilidad real de que una pasada neutralice múltiples objetivos está **INFERIDA**, no validada en juego; no cambia el alcance de PE-01 y queda como riesgo de auditoría del hook CBC existente.

La revisión de las clases PE Mk 82, Mk 84 y Fat Man encontró overrides de `remove(RemovalReason)` que limpian tickets y llaman a `super.remove`; no llaman por sí mismos al handler de impacto. Por tanto, la inspección no muestra que un `discard()` antes del impacto detone esas cargas. Eso no acredita cancelar efectos nucleares que ya hayan sido programados: Fat Man contiene código de crater schedule y creación diferida de entidades/bloques; el vínculo de cancelación de esos trabajos con `remove` no está demostrado. La ruta de Mk 82 que expone `isAttackable() == false` no es consultada por el filtro CBC revisado; `isPickable`/`canBeHitByProjectile` sigue siendo una pregunta separada. La bomba de fuego además puede crear estelas de fuego al detonar; se mantienen excluidas.

En el ICBM, la decompilación muestra fases de vuelo y separación de un booster con UUID propio; algunas ojivas generan otra entidad de bomba al llegar, mientras Fat Man conserva lógica de detonación propia. No se acredita una intercepción segura durante todas las fases. Las rutas de uso de objeto pueden pasar un jugador como owner, pero airstrike, bloque colocado y comando construyen bombas con coordenadas sin propietario visible en esos call sites. CMI no infiere propietario por la plataforma, destino o tipo.

| Capacidad | Estado |
|---|---|
| Clasificación de los nueve IDs PE | IMPLEMENTADO ESTÁTICAMENTE |
| Compatibilidad opcional; PE ausente | IMPLEMENTADO ESTÁTICAMENTE; sin referencias de clases ni dependencia obligatoria |
| Adquisición CIWS automática de bombas/ICBM PE | BLOQUEADO intencionalmente |
| Neutralización de cualquier entidad PE | BLOQUEADO intencionalmente |
| Detección física en radar de bombas/ICBM/estelas `Projectile` | CONDICIONADO a radar activo, `scanProjectiles`, alcance/FOV/rango vertical; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Detección de booster y `multi_block` con filtros selectivos | BLOQUEADO por no ser `Projectile` y no entrar por `Projectile.class`; su presencia no crea candidato |
| Colisión PE/CBC y cancelación segura de ojivas | BLOQUEADO hasta auditar detonación, efectos secundarios y procedencia |
| Funcionamiento junto al mod PE en runtime | PENDIENTE DE VALIDACIÓN EN JUEGO |

## CMI-PE-02A — procedencia de Mk 82/Mk 84 y permiso destructivo separado

CMI observa Mk 82 (`projectexplosive:small_bomb`) y Mk 84 (`projectexplosive:big_bomb`) solo dentro de tracks PROJECTILE recientes de un radar físico Create: Radars. El adaptador informa el owner que `Projectile.getOwner()` logra resolver, relación IFF, protección y motivo de rechazo, pero no se conecta al selector CIWS y siempre deja `acquisitionAuthorized=false` y `neutralizationAuthorized=false`. El lanzamiento directo del ítem PE transmite al constructor el jugador mediante `Projectile.setOwner`; airstrike, comando, bloque activador y bombas hijas de ICBM usan rutas por coordenadas sin transferir owner en los call sites inspeccionados. El UUID base se serializa; si `getOwner()` no puede resolver la entidad, CMI mantiene UNKNOWN. La identidad resuelta no demuestra contexto de lanzamiento CMI ni permite distinguir por sí sola la ruta de creación.

`NeutralizationPolicy` separa autorización destructiva de reconocimiento: los tipos PE consultan `Classification.neutralizable()` y el namespace PE completo falla cerrado; CBC/Warium no PE conserva el resultado de la whitelist anterior. `CombatServices.neutralize`, la intercepción CBC gestionada y las explosiones gestionadas consultan esa política. No se agregaron IDs PE a `CombatServices.projectile()` ni se alteró el CIWS. Los detalles y la certeza por ruta están en `PROJECT_EXPLOSIVE_PE_02A.md`.

| Capacidad | Estado |
|---|---|
| Reconocer Mk 82/Mk 84 por ID exacto | IMPLEMENTADO |
| Observar tracks del radar físico | IMPLEMENTADO ESTÁTICAMENTE; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Identificar owner en lanzamiento directo desde ítem | CONDICIONADO: `Projectile.getOwner()` debe resolverlo |
| Owner de airstrike/comando/bloque/bomba hija ICBM | UNKNOWN en las rutas inspeccionadas |
| Adquisición automática CIWS PE | BLOQUEADO |
| Neutralización PE por colisión o explosión gestionada | BLOQUEADO |
| Neutralización CBC/Warium existente | Conservada estáticamente; runtime pendiente |

Validación PE-02A: `clean build --offline` aprobó con Java 21 y las 15 suites JavaExec; los 16 verificadores estáticos aprobaron; el JAR incluye la política y el adaptador sin empaquetar clases PE. SHA-256 del JAR binario: `93DC6BA5EB744A6C565A9C386BEADD12E88181A8C6F168917877BAE11C6B1CB9`. No se inició Minecraft, servidor, cliente ni GameTest. No hubo cambios de dependencia ni en instalaciones.

## CMI-PE-02B — intercepción física experimental Mk 82 / Mk 84

Base: `aa4745469e253f58cf13a559854d2bd6bb93c559`. El alcance se limita a `projectexplosive:small_bomb` y `projectexplosive:big_bomb`; ICBM, nuclear/Fat Man, bombas incendiarias, boosters, humo, trails, debris e IDs PE desconocidos continúan bloqueados.

### Implementación estática

- La política separa adquisición y destrucción. La ruta genérica `CombatServices.projectile()`/`CombatServices.neutralize()` no admite PE. Mk 82/Mk 84 solo entran por gates específicos: ID exacto, track PROJECTILE actual del radar físico, UUID que resuelve a la misma entidad viva, `Projectile.getOwner()` que resuelve a un `Player` en el mismo `ServerLevel`, metadatos CMI sin contradicciones, relación ENEMY, `canEngage`, zona no protegida y estado PE legible previo a impacto/detonación. `cmiLaunchContextVerified=false`: owner resuelto no demuestra qué ruta de Project: Explosive creó la bomba.
- La configuración de servidor `experimental.projectExplosiveConventionalBombInterception` tiene valor predeterminado `false`; también queda inerte si PE no está cargado. No fue habilitada en ninguna instalación.
- El adaptador usa exclusivamente `IRadar.getTracks()` y rechaza tracks, UUIDs o categorías ambiguas. Relee y reautoriza antes del fuego y en el callback CBC real.
- CBC 5.11.7 obtiene candidatos con `Level.getEntities(self, sweptAABB)`, llama `canHitEntity`, valida intersección AABB o recorte del segmento, acumula los impactos y luego invoca `onHitEntity`. El filtro nativo llama `Projectile.canHitEntity()` y rechaza toda entidad `Projectile`, incluidas las bombas PE `ThrowableProjectile`. El hook CMI sustituye ese rechazo solo para un tiro CBC gestionado con permiso de un solo uso ligado a un UUID Mk 82/Mk 84; CBC conserva su conjunto de candidatos y su geometría de impacto real. Para tiros sin `cmiManagedShot`, el hook devuelve el control a CBC sin cambiar su filtro/callback nativo. El disparo usa `WeaponAdapters.fire()` y la creación del permiso requiere un CBC real visto durante el contexto gestionado.
- El callback repite radar, owner, IFF, protección, estado PE, cañón y vínculo; consume el permiso antes de validar. El flag `cmiPeImpactConsumed` impide una segunda neutralización cuando CBC ya había recolectado más candidatos en la misma pasada. El permiso tiene límite de cantidad y caduca dentro de 200 ticks.
- Bytecode PE exacto: ambas bombas tienen un estado privado de impacto que `onHit` activa antes de continuar la detonación y un método `HasExploded` que consulta el estado autoritativo. Los accessors/invokers `@Pseudo` leen ambos estados. Estado inaccesible o desconocido, impacto pendiente o detonación iniciada rechaza la acción.
- `remove(DISCARDED)` revisado limpia metadatos/tickets y llama a `ThrowableProjectile.remove`; no llama directamente a `onHit`. Esto no acredita cancelación de trabajos externos ya iniciados: por ello CMI no acepta bombas con el estado de impacto/detonación activado y no ejecuta callbacks de PE.
- Las dos bombas heredan `ThrowableProjectile` y su bytecode fija gravedad `0.1f`. CIWS las somete al historial de movimiento 2J y exige una tendencia medida compatible con la hitbox real antes de producir plan de disparo. No se cambió el solver ni la física de munición.
- Se conserva la autorización IFF, la seguridad de trayectoria, las reservas y los límites CIWS existentes. No se usa predicción como neutralización.

| Capacidad | Estado |
|---|---|
| Reconocimiento Registry ID Mk 82/Mk 84 | IMPLEMENTADO |
| Adquisición por radar físico, owner e IFF | IMPLEMENTADO estáticamente; opt-in servidor OFF |
| Lectura del estado previo a detonación | IMPLEMENTADO estáticamente para el JAR PE fijado; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Camino CBC candidato → geometría → callback | IMPLEMENTADO estáticamente; PENDIENTE DE VALIDACIÓN EN JUEGO |
| Neutralización solo por colisión CBC real | IMPLEMENTADO estáticamente, opt-in OFF; PENDIENTE DE VALIDACIÓN EN JUEGO |
| PE ausente y entidad desconocida | Fail-closed estático; carga runtime PENDIENTE DE VALIDACIÓN EN JUEGO |
| ICBM/nuclear/incendiaria/desconocidos | BLOQUEADO |

### ACREDITADO ESTÁTICAMENTE

- `gradlew.bat clean build --offline -PmodpackDir="C:\Users\panch\OneDrive\Desktop\KxCraft\server\mods" -PcandidatesDir="C:\Users\panch\OneDrive\Desktop\KxCraft\server\mods"` con Java 21: **BUILD SUCCESSFUL**; pasaron las **16 suites JavaExec**.
- Pasaron los **17 verificadores estáticos**, incluidos `Verify-ProjectExplosiveCompatibility.ps1` (53 comprobaciones) y `Verify-ProjectExplosiveInterception.ps1` (53 comprobaciones). `git diff --check` pasó.
- JAR binario `build/libs/create-military-integration-0.1.0-dev.jar`: SHA-256 `D48CD0C3EAE8AB02AE37A05E4AC3EFDE37EA22B9A1403C7C35D31C5C9D54D38F`. JAR de fuentes: SHA-256 `A10C619B2E92EBD88C97AF7F9673A8BFFC3BF39C7313D74B34C458BD31438FBB`.
- El artefacto no contiene clases de Project: Explosive ni JARs de terceros. Los hashes de las dependencias fijadas fueron aceptados.

### ACREDITADO EN JUEGO

Ningún resultado. No se inició Minecraft, cliente, servidor ni GameTest; no se instaló el JAR y no se activó el opt-in.

### PENDIENTE

- Aplicación runtime de los mixins opcionales PE y del hook CBC; track, adquisición IFF, disparo, colisión y callback reales.
- Que `discard()` después de una colisión preimpacto evite todo efecto PE en runtime. El bytecode examinado muestra que `remove(DISCARDED)` no llama directamente a `onHit`, pero no acredita cancelar trabajos externos ya iniciados ni todos los efectos secundarios.
- En consecuencia, la intercepción real permanece pendiente de validación en juego. La opción experimental está desactivada por defecto.

## CMI-PE-02C — identidad y ciclo de vida del permiso convencional

PE-02C endurece exclusivamente los permisos experimentales para Mk 82/Mk 84. El permiso guarda valores inmutables de dimensión, UUID de bomba, owner y plataforma resueltos al disparar, posición de montaje, identidad de instancia del radar físico, tipo/posición/owner/IFF/plataforma del radar, UUID de cañón y actor autorizado. No conserva referencias a entidad/mundo y no usa NBT como fuente de autoridad.

La identidad del radar se asigna en memoria a la instancia cargada del block entity mediante una clave débil; se invalida al descargar su dimensión o chunk. Antes de aceptar el filtro CBC y otra vez en el callback geométrico, CMI re-resuelve la bomba y el owner, exige igualdad exacta con la identidad emitida, vuelve a leer el track del radar vinculado, confirma ENEMY, protección, estado preimpacto, montaje, actor y cañón. Un cambio de owner —incluso a otro owner también ENEMY—, plataforma, relación, radar, cañón, actor o dimensión revoca el permiso.

La captura ocurre dentro de la llamada síncrona al firing nativo y solo una entidad elegible dentro de `ShotContext.managed()` puede recibir permiso. Todas las altas de proyectiles CBC observadas en ese ámbito se cuentan antes de validar metadatos: si aparece más de una, incluso una segunda sin metadatos CMI completos, se revoca cualquier permiso provisional. El permiso solo queda activo si el firing reporta éxito, hubo exactamente una alta CBC, la entidad quedó insertada y todas las revalidaciones siguen pasando. Rechazo, excepción o ambigüedad revoca el permiso. El callback consume el token antes de validar y puede neutralizar como máximo una vez.

Permisos y tokens radar son temporales en memoria. Se limpian por descarga de dimensión/chunk, descarga/reload de la configuración de servidor y detención del servidor. La caducidad sigue limitada a 200 ticks. Las mixins de estado conservan targets exactos y `@Pseudo`; la ausencia de Project: Explosive está soportada por diseño estático. Solo se inspeccionó el contrato PE 0.7.2-beta fijado: otra versión con clases presentes pero nombres/descriptores incompatibles puede rechazar la aplicación del mixin; su comportamiento de arranque no se declara validado y queda fuera del contrato.

El opt-in `experimental.projectExplosiveConventionalBombInterception` permanece OFF por defecto. No se habilitaron ICBM, bombas nucleares/incendiarias, boosters, estelas ni entidades desconocidas. CBC mantiene su geometría de barrido; los tiros CBC no gestionados conservan sus filtros y callbacks nativos.

### Estado de PE-02C

| Capacidad | Estado |
|---|---|
| Owner original ligado desde disparo hasta impacto | IMPLEMENTADO ESTÁTICAMENTE |
| Identidad y procedencia del radar físico | IMPLEMENTADO ESTÁTICAMENTE; runtime pendiente |
| Revalidación de actor, cañón, plataforma, dimensión e IFF | IMPLEMENTADO ESTÁTICAMENTE |
| Spawn rechazado o múltiple | Fail-closed estático |
| Descarga de chunks/dimensiones y reload del opt-in | Invalidación estática implementada |
| Callback CBC de un solo uso | IMPLEMENTADO ESTÁTICAMENTE; colisión real pendiente |
| Aplicación de mixins opcionales PE | PENDIENTE DE VALIDACIÓN EN JUEGO |
| ICBM/nuclear/incendiaria/desconocidos | BLOQUEADO |

Resultados de compilación, regresiones, verificadores y SHA-256 de artefactos: ver la entrada PE-02C en `VALIDATION.md`.
