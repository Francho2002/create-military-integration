# Estado de desarrollo

Esta tabla describe el código, no una prueba dentro de Minecraft. Compilación no demuestra aplicación correcta de mixins.

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
| Radar Spear | Puente optativo al contrato existente, identidad estable, movimiento gestionado y exclusión de destrucción indiscriminada | Impactos explosivos gestionados desactivados; no equivale a daño aire-aire contra aeronaves |
| Intercepción CBC | Exclusión limitada de la protección de colisión para amenazas identificadas, neutralización sin explosión | Selección/tiro dependen de filtros y perfil autorizado; no convierte cualquier entidad en interceptable |
| Explosiones atribuidas | Evento NeoForge de detonación: amenazas soportadas y hostiles con exposición pueden neutralizarse; se excluyen entidades no hostiles y bloques protegidos de las listas del evento | Solo explosiones que pasan por este evento y conservan atribución CMI; no acredita explosiones privadas de cada mod |
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
