# Hoja de aceptación manual — CraftBalance 0.1.53

Esta hoja sirve para registrar la validación en un servidor Fabric de staging con Minecraft 1.21.1. Los escenarios aún no están ejecutados. No uses una base de datos de producción: la prueba de ajustes y de fallos puede desconectar jugadores o cambiar saldos.

## Preparación

- [ ] Servidor Fabric 1.21.1 con Java 21, Fabric Loader y Fabric API compatibles.
- [ ] Instalar el JAR 0.1.53 y arrancar una vez para crear `config/craftbalance/config.yml` y SQLite.
- [ ] Definir `time.daily-reset.timezone` según la zona acordada por el grupo; no asumir UTC.
- [ ] Guardar una copia de la base SQLite y de la configuración antes de probar.
- [ ] Usar cuentas separadas para jugador, amigo activo y administrador.
- [ ] Anotar versión, commit, zona horaria, configuración aplicada, fecha y nombres/UUIDs usados.

Para acelerar escenarios largos, se puede usar un archivo de configuración exclusivo de staging con valores reducidos. Anota esos cambios y restaura la configuración de temporada al terminar. Los valores reducidos sirven para comprobar el flujo, pero no sustituyen los cálculos de referencia de abajo.

El paquete incluye `staging/config.yml` con un perfil rápido, `staging/config-a30.yml` con los valores exactos del escenario A30 y una base llamada `craftbalance-staging.db`. Para usar un perfil, cópialo a `config/craftbalance/config.yml` en una instancia desechable. Restaura el perfil rápido o el de temporada entre escenarios.

## Matriz de escenarios

| ID | Escenario y procedimiento | Resultado esperado | Resultado real |
|---|---|---|---|
| A1 | Con un jugador activo, anotar el saldo, jugar 10 minutos y consultar `/cb tiempo`. | Aproximadamente 10 minutos menos de saldo normal y 10 minutos reales más. | Pendiente |
| A2 | Con dos jugadores activos en total, incluido el jugador cuyo saldo se mide, consumir 10 minutos normales sin cambiar el grupo. | Tarda aproximadamente 11 min 7 s reales. | Pendiente |
| A3 | Con cinco o más jugadores activos en total, incluido el jugador cuyo saldo se mide, consumir los 240 minutos normales sin llegar antes al tope real. | Aproximadamente 5 h 20 min reales. | Pendiente |
| A4 | Agotar el saldo de un jugador, desconectarlo y dejar cero amigos activos durante la espera. | No genera recuperación. | Pendiente |
| A5 | Agotar el saldo, dejar cuatro amigos activos durante 120 minutos desconectado y volver a entrar. | Tras la espera de 10 minutos, genera 44 minutos recuperados; puede reingresar al alcanzar el mínimo configurado. | Pendiente |
| A6 | Desconectar a un jugador con recuperación habilitada antes de completar la espera inicial. | Los primeros 10 minutos no generan saldo. | Pendiente |
| A7 | Probar reingreso con 14 y luego 15 minutos recuperados, manteniendo el saldo normal agotado. | Con 14 no entra; con 15 sí, usando valores predeterminados. | Pendiente |
| A8 | Con saldo normal agotado, consumir 20 minutos recuperados con distintos tamaños de grupo. | Dura 20 minutos reales con el factor recuperado predeterminado de 100 %. | Pendiente |
| A9 | Generar recuperación, gastarla, volver a desconectar y seguir generando durante el mismo día. | La suma generada no supera 60 minutos, aunque se haya gastado saldo. | Pendiente |
| A10 | Alcanzar 360 minutos reales conectados conservando saldo. | Se desconecta y no puede volver hasta el reinicio diario. | Pendiente |
| A11 | Dejar a un jugador quieto y sin interacciones por 5 minutos mientras otro juega; luego mover la cámara más de 1°. | El AFK deja de contar para los beneficios sociales; la actividad de cámara reinicia su inactividad. | Pendiente |
| A12 | Desconectar el servidor de forma ordenada con una persona conectada; reiniciar y revisar el saldo. | Se guarda el intervalo final una vez; el tiempo de servidor apagado no genera recuperación. | Pendiente |
| A13 | Reiniciar normalmente con saldo normal, recuperado y fracciones pendientes; repetir consulta tras iniciar. | Se restauran los valores persistidos sin acreditar dos veces un intervalo. | Pendiente |
| A14 | Configurar un reinicio próximo, asegurar que el jugador conectado conserve saldo y margen del tope real, y observar también a uno desconectado elegible al cruzarlo. | Cada balance pasa al nuevo día una sola vez; el conectado contabiliza cada lado del límite y el offline aplica topes por fecha. | Pendiente |
| A15 | Introducir una clave inválida o duplicada en YAML y ejecutar `/cb reload`; después restaurar el archivo. | El reload informa el error y mantiene activa la última configuración válida. | Pendiente |
| A16 | Mantener un amigo activo 60 minutos elegibles con un jugador desconectado; usar una tasa de prueba equivalente a 6 s por amigo/min. | Se generan 6 minutos recuperados, también si el cálculo se procesa en intervalos cortos. | Pendiente |
| A17 | Cambiar la orientación más de 1° entre muestras sin desplazarse; repetir con cambios menores acumulados de posición. | La cámara y el desplazamiento acumulado restablecen actividad según la regla AFK. | Pendiente |
| A18 | Con `time.enabled: false`, cruzar un reinicio y conectar un UUID nuevo; consultar `/cb tiempo` y `/cb estado`. Mover la cámara y cambiar el inventario después del umbral AFK. | Se aplica el reinicio, existe saldo inicial, movimiento/cámara/inventario actualiza presencia/AFK y no se acredita tiempo pausado. | Pendiente |
| A19 | En una copia desechable, impedir que SQLite abra o guarde y observar sesiones actuales y un nuevo ingreso. | El mod falla cerrado: desconecta sesiones y rechaza nuevos ingresos si no puede persistir. | Pendiente |
| A20 | Instalar solo en el servidor y conectar un cliente sin CraftBalance. | El cliente puede entrar sin instalar este mod. | Pendiente |
| A21 | Simular un cierre abrupto del proceso con alguien conectado y reiniciar con `recovery.only-after-normal-exhaustion: false`. | No se acredita el tiempo apagado; la espera de recuperación comienza de nuevo al arrancar. | Pendiente |
| A22 | Con un UUID nunca conectado, ejecutar `/cb set <uuid> normal 120`, luego `/cb info <uuid>` y `/cb auditoria <uuid>`. | Se crea el saldo inicial y el ajuste en una sola transacción; la auditoría muestra antes/después, tipo, minutos y administrador. | Pendiente |
| A23 | Desconectar un administrador mientras consulta `/cb auditoria` o mientras completa `/cb set`; apagar también con un ajuste pendiente en staging. | La consulta pendiente no muta saldos; el ajuste queda confirmado y auditado o se revierte completo, sin duplicar contabilización al cerrar. | Pendiente |
| A24 | Mantener `afk.enabled: true` y probar `afk.counts-as-active-for-others: true` y `afk.consume-own-time-while-afk: false` en una copia de staging. | El jugador AFK aporta beneficios sociales cuando la primera opción está activa; la segunda pausa su saldo propio, pero el tope de tiempo real diario sigue contando. | Pendiente |
| A25 | En una zona con horario de verano, configurar el reinicio dentro de la hora local repetida y observar el instante aplicado. | Se usa la primera ocurrencia de esa hora y la fecha diaria no se reinicia dos veces. | Pendiente |
| A26 | En una zona con horario de verano, configurar el reinicio dentro de una hora local inexistente por el salto primaveral. | El instante se desplaza hacia delante por la duración del salto y el reinicio ocurre una sola vez. | Pendiente |
| A27 | En staging, adelantar la fecha del reloj del sistema para aplicar un reinicio y luego atrasarla a una fecha anterior. | La fecha diaria persistida no retrocede ni vuelve a conceder saldos; los reinicios continúan cuando la fecha calculada vuelve a avanzar. | Pendiente |
| A28 | Con `time.enabled: true`, `recovery.initial-wait-minutes: 0`, `recovery.seconds-per-friend-per-minute: 6`, `afk.enabled: true` y `afk.counts-as-active-for-others: false`, mantener un amigo activo mientras el jugador objetivo está desconectado. Mantenerse activo 5 min, quedar AFK 5 min y reactivarse otros 5 min. | El objetivo acumula 1 min recuperado en total; el tramo AFK no genera y ambos cambios de estado se aplican en su instante. | Pendiente |
| A29 | En staging, usar `afk.inactivity-minutes: 1`, `multiplayer.consumption.2: 0.90` y `.1: 1.00`. Mantener activos a dos jugadores, dejar quieto al jugador medido durante 2 minutos mientras el otro sigue activo y apagar el servidor ordenadamente. | La sesión final del jugador medido consume 54 s de saldo normal el primer minuto y 60 s el segundo; el tiempo real final suma 2 min. | Pendiente |
| A30 | En staging, aplicar `staging/config-a30.yml`, entrar con un jugador y esperar a que se agote el saldo normal. | El jugador es expulsado al agotarse el saldo; el motivo de detención no se pierde al liquidar la sesión antes de procesar el tick. | **Correcto en staging 1.21.1 / CraftBalance 0.1.53 (2026-11-16):** `cba30` recibió el mensaje de saldo agotado al llegar a 0 s; RCON confirmó 0 jugadores conectados. Recuperación desactivada, próximo reinicio 2026-11-17 00:00 UTC. |

## Registro por ejecución

- Fecha/hora y zona del servidor:
- Operadores y UUIDs de prueba:
- Versión de Minecraft / Loader / Fabric API:
- Configuración de staging utilizada:
- Escenarios ejecutados y evidencias (logs, capturas o valores de `/cb info`):
- Fallos observados:
- Configuración y base restauradas al terminar: sí / no
