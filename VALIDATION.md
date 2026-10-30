# Validación realizada

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
