# Validación realizada

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
