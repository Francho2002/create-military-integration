# Create Military Integration

Complemento experimental para Minecraft 1.21.1 / NeoForge **21.1.265**, con las versiones locales fijadas en `dependency-hashes.properties`.

**No es una integración militar completa ni una versión lista para el servidor habitual.** Se ha compilado y revisado estáticamente; no se ha iniciado Minecraft para comprobar mixins o comportamiento. El detalle funcional está en [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

## Compilar

Requiere un JDK 21. Los originales se leen desde sus directorios y nunca se empaquetan en el complemento.

```powershell
.\gradlew.bat --gradle-user-home .gradle-home `
  -PmodpackDir="RUTA_AL_PERFIL/mods" `
  -PcandidatesDir="RUTA_A/_descompiled/mods" build
```

Los candidatos externos deben llamarse `create-radars-0.4.9.4.jar` y `create-better-targeting-1.0.0.1.jar`; se verifica su contenido por SHA-256, no solo por nombre. Las dependencias de desarrollo de Minecraft/NeoForge se descargan en la caché del proyecto. Los comandos de ejecución del juego están bloqueados por el build.

Salida: `build/libs/create-military-integration-0.1.0-dev.jar`, junto al JAR de fuentes. Ninguna tarea instala esos archivos en el modpack.

## Política del servidor

Los blancos desconocidos y neutrales se rechazan. Una relación hostil explícita nunca invalida una relación aliada acreditada. Se protegen ciudadanos MineColonies, todas las zonas reconocidas como colonia y reclamaciones FTB amigas de manera conservadora. Esto puede impedir disparos desde o a través de esas zonas: no equivale a un sistema flexible de reglas de guerra.

Comandos de administración (`permission level 2`, requieren jugador):

- `/cmi claim x y z`: registra propietario de una montura/computadora/bloque y su plataforma Sable identificable.
- `/cmi hostile <jugador> <true|false>`: relación dirigida desde el operador a ese jugador. No convierte a todos los jugadores fuera del equipo en enemigos.
- `/cmi heat <UUID-Sable> <true|false>`: firma térmica explícita. No se presume que cualquier aeronave sea caliente.
- `/cmi interceptor x y z <true|false>`: modo interceptor en una montura lanzadora o buscador IR Neo. El coordinador copia el modo al buscador; su NBT debe conservarse al cargar la munición.
- `/cmi managed x y z <true|false>`: opta un bloque lanzador al puente Radar Spear; requiere su contrato nativo de coordenadas.

No se alteran globalmente las unidades CIWS hostiles de Warium. La automatización de Radars necesita la autorización y el corredor verificados por CMI. Las misiones de coordenadas sin blanco identificado se rechazan.

## Dependencias

Create 6.0.10; Aeronautics 1.3.2; Sable 2.0.6; CBC 5.11.7; CBC Neo Warfare 0.0.6v; CBC Warium Projectiles 1.2.0; Warium CE 1.0.4; Create Radars 0.4.9.4; Better Targeting 1.0.0.1. FTB Teams/Chunks y MineColonies corresponden al perfil auditado. No requiere Simulated Missiles ni Create Aero Radars.

## Código externo

No contiene fuentes decompiladas ni JAR de los mods originales. El código de integración usa firmas e interfaces de las versiones existentes. Consulta [THIRD_PARTY.md](THIRD_PARTY.md).
