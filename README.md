# CraftBalance

Mod de servidor Fabric para gestionar el tiempo de juego y promover sesiones compartidas.

## Plataforma

- Minecraft Java 1.21.1
- Fabric Loader 0.16.10 o superior y Fabric API 0.115.6 o superior para Minecraft 1.21.1
- Java 21
- Configuración en `config/craftbalance/config.yml`
- Persistencia local SQLite en `config/craftbalance/craftbalance.db`

## Estado

El mod ya se integra con eventos de Fabric: mide tiempo real por intervalos, aplica multiplicadores a jugadores activos, cuenta desplazamiento, orientación e interacciones para AFK, acredita recuperación offline solo mientras hay actividad en el servidor, aplica el cambio de día y limita el acceso al llegar al saldo o al límite diario. Incluye Action Bar, resumen al entrar, avisos configurables, comandos de consulta y administración, auditoría de ajustes y SQLite por UUID. El acceso a SQLite se serializa en un hilo de trabajo y se guarda una instantánea al menos cada 10 segundos mientras el servidor sigue activo. La base usa WAL con `synchronous=FULL`; CraftBalance comprueba el ajuste al abrir SQLite para reforzar la durabilidad de cada transacción confirmada.

Al iniciar con una base existente, CraftBalance añade automáticamente las columnas para conservar las fracciones de recuperación y consumo, la columna de tipo de ajuste en la auditoría y el índice de consultas por jugador; también convierte una sola vez las fracciones de recuperación guardadas al cálculo corregido. No es necesario borrar ni recrear `craftbalance.db`. Los saldos ya acreditados se conservan tal como están: no se reconstruye retroactivamente la recuperación anterior porque la base no registra cuánto saldo se gastó ni separa los ajustes manuales.

El JAR incluye los avisos y licencias de las bibliotecas empaquetadas en `THIRD-PARTY-NOTICES.md` y `META-INF/licenses/`.

Los mensajes del juego y la salida de comandos usan el idioma del cliente cuando está disponible. Se incluyen traducciones `es_es` y `en_us`; el español es el idioma de referencia.

CraftBalance requiere SQLite en modo WAL y comprueba que quedó activo al abrir la base. Mantén `config/craftbalance/` en un disco local del mismo equipo que ejecuta el servidor; WAL no es compatible con sistemas de archivos de red y el mod no iniciará si SQLite no puede confirmarlo. Para un respaldo sencillo, apaga el servidor de forma ordenada y luego copia `craftbalance.db`. No copies solo el `.db` mientras el servidor está activo: las transacciones confirmadas pueden seguir en `craftbalance.db-wal`; para respaldos en caliente se deben incluir los archivos auxiliares o usar la API de backup de SQLite.

Si SQLite falla al guardar, CraftBalance desconecta las sesiones y bloquea nuevos ingresos hasta reiniciar el servidor, evitando continuar con saldos que no se pueden persistir.

Si el servidor se cierra inesperadamente mientras un jugador seguía conectado, su espera de recuperación comienza de nuevo al arrancar. No se acredita tiempo durante la parada del servidor, incluso si `recovery.only-after-normal-exhaustion` está desactivado.

Con `recovery.enabled: false`, el saldo recuperado que ya existe todavía se puede gastar, pero no se genera más. Si también se agota el saldo normal, el jugador podrá volver en el reinicio diario o tras un ajuste administrativo.

Con `recovery.only-after-normal-exhaustion: false`, los jugadores desconectados pueden generar recuperación aunque todavía tengan saldo normal; la marca de desconexión se conserva al cambiar de día para mantener esa regla.

Con `time.enabled: false` se suspenden el consumo y la generación de recuperación, pero presencia y AFK siguen actualizándose para `/cb status`; los reinicios diarios continúan y los nuevos jugadores reciben su saldo inicial. Al reactivarlo, el tiempo desactivado no se cuenta de forma retroactiva.

La salida al agotar saldo expulsa al jugador y explica el regreso tras recuperar el mínimo configurado (15 minutos por defecto). La detección AFK considera desplazamiento, cambios de orientación de cámara mayores de 1° entre muestras, cambios del inventario personal/cursor y de la barra rápida, chat, comandos, uso de ítems, interacción con bloques/entidades, intentos de golpear bloques y romperlos. No usa revisiones de contenedores ajenos como señal, porque pueden cambiar automáticamente; otras acciones de modpacks todavía pueden requerir integración adicional.

## Compilar

Con JDK 21:

```sh
./gradlew remapJar
```

El JAR remapeado para instalar en el servidor se genera en `build/libs/`. La versión actual es `craftbalance-0.1.54.jar`, compilada con `./gradlew remapJar`; aún falta validar su comportamiento en un servidor real.

Para generar un paquete de instalación con el JAR, las guías de instalación y aceptación, la especificación y los avisos de licencia:

```sh
./gradlew serverBundle
```

El ZIP se genera en `build/distributions/` y contiene el mod dentro de `mods/`.
Descomprímelo en la carpeta raíz del servidor y combina su directorio `mods/` con el existente. Los clientes no necesitan instalar CraftBalance.
La tarea `serverBundle` genera una huella `.sha256` junto al ZIP y otra dentro de `mods/`, junto al JAR. Si recibiste solo el ZIP, descomprímelo y comprueba el JAR con `cd mods && sha256sum -c craftbalance-*.jar.sha256`; desde el proyecto, también puedes verificar el JAR con `cd build/libs && sha256sum -c craftbalance-*.jar.sha256` y el ZIP con `cd ../distributions && sha256sum -c craftbalance-*-server-bundle.zip.sha256`.
Los factores de consumo deben ser al menos `1e-18` para que la acumulación fraccional mantenga precisión.
Consulta `CHANGELOG.md` para ver los cambios incluidos en la versión actual.
El paquete de servidor también incluye `PRUEBA_ACEPTACION_SERVIDOR.md`, una hoja pendiente de rellenar para validar el comportamiento en staging.
Incluye además `staging/config.yml`, un perfil rápido, `staging/config-a30.yml` para el escenario de agotamiento y un cliente Mineflayer con su `package-lock.json` para simular entradas y comandos. `staging/compose.yaml` define un servidor aislado de Minecraft 1.21.1 con Fabric, modo offline y el puerto TCP 25565 publicado; los datos persisten en `staging/data/` junto al archivo Compose. El modo offline permite los clientes sintéticos de prueba; úsalo solo en esta red privada, ya que Minecraft no autentica los nombres de usuario. Para ejecutarlo, coloca el JAR en `staging/data/mods/`, copia el perfil deseado a `staging/data/config/craftbalance/config.yml` y ejecuta `docker compose -f staging/compose.yaml up -d`. Usa una base separada de la producción y restaura el perfil de temporada antes de iniciar el servidor normal. Los perfiles reducidos no reemplazan los escenarios que requieren los valores predeterminados.

Gradle verifica las dependencias con SHA-256 usando `gradle/verification-metadata.xml`. Al actualizar una biblioteca, regenera las huellas con `./gradlew --write-verification-metadata sha256` y revisa el archivo antes de conservarlo.
El Gradle Wrapper también verifica el SHA-256 oficial de la distribución 8.13 antes de usarla.

## Primer arranque

1. Instala Fabric Loader, Fabric API y el JAR de CraftBalance en el servidor. Los clientes no necesitan el mod.
2. Inicia el servidor una vez para generar `config/craftbalance/config.yml`.
3. Ajusta `time.daily-reset.timezone` a la zona horaria acordada por el grupo y ejecuta `/cb reload` (o reinicia el servidor).

Al actualizar CraftBalance, las opciones que no existan en un `config.yml` antiguo toman el valor predeterminado de esta versión; el archivo no se reescribe y el registro enumera las opciones completadas. Las claves desconocidas y duplicadas siguen provocando un error para que se puedan corregir.

El registro también avisa sin bloquear el inicio cuando el saldo diario supera 24 horas, el límite real supera la duración habitual de un día local, un factor social activo cae por debajo de `0.01` o supera `10`, o la recuperación activa puede superar 60 segundos de saldo por minuto elegible.

El saldo y el límite diario se reinician en el instante configurado aunque la persona siga conectada; esta política es provisional y no tiene una opción para aplazar el reinicio.

## Comandos

- `/cb` o `/cb time`: saldos, tiempo real jugado y estimación del tiempo jugable al ritmo actual. Alias en español: `tiempo`.
- `/cb status`: jugadores considerados activos para otros y factor de consumo propio. Alias: `estado`.
- `/cb help`: lista breve de comandos. Alias: `ayuda`.
- `/cb reload`: recarga YAML validando antes de sustituir la configuración activa.
- `/cb info <player|uuid>`: consulta estado; admite UUID de jugadores desconectados.
- `/cb audit <player|uuid> [count]`: muestra los últimos ajustes administrativos registrados (5 por defecto, máximo 20). Alias en español: `auditoria`.
- `/cb set <player|uuid> <normal|recovered> <minutes>`: ajuste administrativo auditado. Se acepta `recuperado` como alias de `recovered`.

Los comandos administrativos requieren nivel de permiso 2. Los ajustes hechos desde consola quedan auditados con UUID cero. `/cb set` permite reponer el saldo hasta los topes configurados, confirma el cambio cuando SQLite completa la transacción y restaura el saldo anterior si falla, evitando persistir un ajuste sin auditoría. Si el UUID todavía no existe en SQLite, parte del saldo normal diario configurado y guarda el ajuste y su auditoría en una sola transacción. `/cb audit` consulta las entradas más recientes por jugador y muestra `consola` para los ajustes hechos desde la consola. El límite absoluto de tiempo real sigue aplicándose. `/cb reload` mantiene los saldos dentro de topes reducidos y aplica los cambios de reinicio y recuperación desde el momento de la recarga. Cambiar `database.file` requiere reiniciar el servidor. La zona horaria inicial UTC debe cambiarse a la acordada por el grupo. Los valores y objetivos provienen de `CRAFTBALANCE_ESPECIFICACION (1).md`; Cobblemon no es una dependencia y el mod no altera mecánicas de Pokémon.
