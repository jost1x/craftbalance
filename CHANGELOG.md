# Changelog

## 0.1.53

- Los mensajes del juego y de comandos están localizados según el idioma del cliente, con traducciones en español e inglés.
- La ayuda y las consultas de estado dividen los datos en líneas más cortas; los avisos de saldo también son más concisos.
- La contabilidad de intervalos usa tiempo monotónico para no perder ni adelantar saldo si cambia el reloj del sistema durante la ejecución.
- Si el saldo se agota exactamente al llegar el reinicio diario, se aplica el saldo del nuevo día antes de decidir expulsar al jugador.
- La salida por saldo agotado o límite diario también se aplica cuando el límite se alcanza durante la liquidación previa de la sesión; ya no se pierde el motivo de detención antes de revisar al jugador.
- El paquete de servidor incluye un perfil rápido de configuración y una base SQLite independiente para facilitar las pruebas manuales de staging.

## 0.1.52

- Si el límite real diario se alcanza exactamente en el instante de reinicio, se aplica el nuevo día sin expulsar al jugador por el límite anterior.

## 0.1.51

- El conteo compartido de jugadores activos usa una sola regla AFK para el consumo conectado y la recuperación offline.

## 0.1.50

- Los cierres y la recuperación offline usan el registro de presencia además de la lista del gestor de jugadores, evitando perder sesiones aún no liquidadas al apagar.

## 0.1.49

- La liquidación final usa las sesiones de presencia aunque el gestor de jugadores ya haya quitado las entidades durante el apagado.

## 0.1.48

- El apagado ordenado liquida la sesión final con los mismos tramos AFK y factores sociales que la contabilidad normal.

## 0.1.47

- Los avisos de opciones predeterminadas se emiten solo después de validar el YAML completo, evitando anunciar ajustes de una recarga rechazada.

## 0.1.46

- Los avisos de valores extremos de multijugador y recuperación solo aparecen cuando esas funciones están activadas.
- El aviso del límite real aclara que compara con la duración habitual del día, que puede variar por horario de verano.

## 0.1.45

- Se registran avisos no bloqueantes cuando los límites, multiplicadores o tasas de recuperación configurados son extremos.

## 0.1.44

- El registro del servidor enumera las opciones que se completan con valores predeterminados al cargar una configuración antigua.

## 0.1.43

- Las opciones que falten en un `config.yml` de una versión anterior heredan los valores predeterminados; las claves desconocidas y duplicadas siguen rechazándose.

## 0.1.42

- La generación de recuperación también divide el intervalo cuando un amigo pasa a AFK o vuelve a estar activo, aplicando la tasa correspondiente a cada tramo.

## 0.1.41

- `time.enabled: false` sigue muestreando movimiento, cámara e inventario para que la detección AFK permanezca activa durante la pausa.

## 0.1.40

- La contabilidad conecta los cambios de AFK al instante en que se cumplen: divide los intervalos en cada transición y toma la muestra de movimiento después de liquidar el período anterior.

## 0.1.39

- Al volver a estar activo después de AFK, el intervalo anterior se liquida antes de actualizar el grupo social, usando el estado AFK que estaba vigente.

## 0.1.38

- Las rutas de reinicio diario usan la misma marca de tiempo del evento, evitando aplicar el cambio de día antes o después del instante contabilizado.
- El paquete de servidor incluye la huella SHA-256 del JAR junto al mod para poder verificarlo tras recibir o extraer el ZIP.

## 0.1.37

- SQLite ahora confirma que `journal_mode=WAL` quedó activo al abrir la base; el mod falla al iniciar si el almacenamiento no lo admite.
- El paquete de servidor incluye una hoja manual para registrar la validación de aceptación en staging.

## 0.1.36

- El intervalo de recuperación que termina con una desconexión cuenta al jugador que se va como amigo activo hasta ese instante.
- `serverBundle` genera huellas SHA-256 para verificar la integridad del JAR y del paquete ZIP.

## 0.1.35

- El porcentaje de consumo conserva precisión sin expandir factores extremos a cadenas desmesuradas; usa notación científica cuando corresponde.

## 0.1.34

- `/cb estado` y la Action Bar muestran los factores pequeños con precisión decimal, sin redondear consumo activo a `0 %`.

## 0.1.33

- Los intervalos de recuperación de jugadores desconectados se dividen en cada reinicio diario, aplicando los topes y saldos correspondientes a cada fecha.
- El apagado liquida primero la recuperación y las sesiones finales antes de aplicar el reinicio diario pendiente y guardar SQLite.

## 0.1.32

- Con `time.enabled: false`, los reinicios diarios siguen aplicándose y los jugadores nuevos reciben su saldo inicial mientras el consumo permanece pausado.

## 0.1.31

- Los avisos de saldo se envían también cuando un cruce de umbral ocurre en el intervalo regularizado al entrar o salir alguien.

## 0.1.30

- Las sesiones conectadas se regularizan al instante de entrada o salida antes de recalcular el grupo activo, para que el nuevo multiplicador no altere tiempo anterior al evento.

## 0.1.29

- La recuperación de jugadores desconectados se regulariza antes de quitar una sesión, usando la cantidad de amigos activos previa al evento de desconexión.

## 0.1.28

- Los comandos de otros mods enviados por jugadores también reinician su contador AFK.

## 0.1.27

- La confirmación de `/cb set` indica el tipo de saldo y los minutos aplicados tanto al administrador como al jugador conectado.

## 0.1.26

- `/cb info` también muestra el tiempo jugable estimado al ritmo actual para ayudar a administrar jugadores desconectados.

## 0.1.25

- El consumo conserva fracciones de milisegundo entre intervalos para que factores pequeños no redondeen el gasto a cero en cada actualización.
- SQLite migra automáticamente la columna de fracción de consumo y la persiste junto con el saldo.
- Se rechazan factores menores que `1e-18`, por debajo de la precisión fiable del acumulador fraccional.

## 0.1.24

- El autocompletado de comandos administrativos incluye los UUIDs de jugadores guardados, también cuando están desconectados.

## 0.1.23

- El apagado resuelve los ajustes administrativos pendientes antes del snapshot final y contabiliza una sola vez el intervalo posterior al ajuste.

## 0.1.22

- El resumen al entrar también muestra el tiempo jugable estimado al ritmo actual.

## 0.1.21

- `/cb tiempo` muestra una estimación del tiempo jugable al ritmo actual, limitada por el saldo y el tope real diario.

## 0.1.20

- La configuración y la especificación aclaran que el consumo recuperado predeterminado es 100 % y que su factor puede ajustarse sin bonificaciones multijugador.

## 0.1.19

- `/cb tiempo` y `/cb estado` indican que el consumo está pausado cuando `time.enabled` es `false`, en vez de mostrar una tasa activa.

## 0.1.18

- SQLite configura y verifica `synchronous=FULL` junto con WAL para reforzar la durabilidad de transacciones ante fallos del sistema.

## 0.1.17

- Con `time.enabled: false`, mantiene el conteo de presencia y AFK actualizado sin consumir ni generar saldo.

## 0.1.16

- Reiniciar un servidor integrado en el mismo proceso reinicia el estado en memoria y descarta callbacks SQLite de instancias anteriores.

## 0.1.15

- Reducir el límite real diario por debajo del tiempo ya jugado ya no recorta el contador ni perdona tiempo si luego se aumenta el límite.

## 0.1.14

- Los cursores de recuperación solo avanzan; un retroceso del reloj del sistema ya no hace que se vuelva a procesar un intervalo offline.

## 0.1.13

- `/cb reload` vuelve a calcular de inmediato cuántos jugadores cuentan como activos según la nueva configuración AFK.

## 0.1.12

- `/cb set ... normal` conserva el cursor de recuperación en el instante del ajuste para no volver a acreditar intervalos offline ya procesados.

## 0.1.11

- El movimiento lento ahora se acumula entre muestras para que desplazamientos pequeños y continuos también cuenten como actividad AFK.

## 0.1.10

- El conteo social ignora las conexiones que aún no tienen una sesión de CraftBalance inicializada.

## 0.1.9

- El conteo de jugadores activos se actualiza inmediatamente al entrar o salir, sin depender del orden interno del evento de desconexión de Fabric.

## 0.1.8

- Si SQLite confirma una transacción pero falla al restaurar `autoCommit`, CraftBalance conserva el resultado confirmado en memoria y cierra la conexión para fallar de forma segura en operaciones posteriores.

## 0.1.7

- El apagado ordenado regulariza la fecha diaria y la recuperación pendiente de jugadores desconectados antes de guardar.
- `/cb estado` describe la tasa de consumo actual, que puede corresponder al saldo recuperado.

## 0.1.6

- La detección AFK usa cambios del inventario personal y del cursor; las actualizaciones automáticas de cofres y máquinas ya no cuentan como actividad.

## 0.1.5

- Al apagar el servidor de forma ordenada, contabiliza el intervalo conectado desde el último tick y marca la desconexión antes del guardado final.

## 0.1.4

- Updated bundled SnakeYAML to 2.7 and SQLite JDBC to 3.54.1.0.

## 0.1.3

- Failed `/cb set` operations for a new UUID no longer leave an initial balance row behind.
- The server bundle includes installation instructions and the bundled libraries' license texts.

## 0.1.2

- `/cb set <uuid>` can initialize a player who has not joined before, using the configured daily normal balance as the starting state.
- The initial balance and its audit entry are saved in one SQLite transaction.

## 0.1.1

- Corrected offline recovery conversion from seconds of balance to milliseconds.
- Added a one-time SQLite migration for pending fractional recovery credit.
- Daily recovery cursors now start at the configured reset boundary when a player is processed after reset.
- Added player and balance-type command suggestions for administrators.
