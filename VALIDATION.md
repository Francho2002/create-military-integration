# Validación realizada

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
