# CraftBalance — Especificación funcional y técnica

> **Versión del documento:** 0.1 · **Estado:** especificación de referencia; implementación inicial disponible, validación de aceptación pendiente
> **Proyecto:** mod de gestión de tiempo y progreso social para un servidor privado de Minecraft Java con Fabric y Cobblemon.

## 1. Visión

CraftBalance busca evitar que unos pocos jugadores avancen mucho más rápido que el resto debido a que pueden dedicar más horas al servidor. La propuesta consiste en limitar moderadamente el tiempo diario, recompensar la coincidencia de amigos conectados y permitir recuperar parte del saldo agotado mientras otros siguen jugando.

**Principio de diseño:** favorecer jugar juntos sin obligar a todos a conectarse a la misma hora ni impedir completamente el juego individual.

### Objetivos

1. Reducir las diferencias extremas de progreso originadas por sesiones muy largas.
2. Incentivar el juego en grupo con un consumo de saldo más lento.
3. Permitir el regreso de quien agota su saldo si otros amigos siguen jugando.
4. Mantener todas las reglas configurables mediante `config.yml`.
5. Guardar los tiempos individualmente, incluso tras reiniciar el servidor.
6. Funcionar como **mod solo de servidor** en Fabric, sin exigir instalar CraftBalance en los clientes.

### Fuera del alcance de la primera versión

- No modificar la experiencia (XP), el botín o la generación de minerales.
- No limitar todavía niveles, capturas, evoluciones ni otros sistemas de Cobblemon.
- No implementar una página web conectada en tiempo real al servidor.
- No implementar sincronización de datos entre varios servidores.

La **progresión comunitaria** y las integraciones específicas con Cobblemon quedan como posibles fases posteriores.

## 2. Plataforma y tecnologías

| Elemento | Decisión |
|---|---|
| Edición | Minecraft Java Edition |
| Cargador | Fabric |
| Versión objetivo inicial | Minecraft 1.21.1, fijada para la implementación inicial |
| Lenguaje | Java |
| API | Fabric API |
| Herramientas de compilación | Gradle + Fabric Loom |
| Configuración editable | YAML (`config.yml`) |
| Persistencia de jugadores | SQLite local |
| Tipo de instalación | Mod de servidor (`.jar`) |
| Compatibilidad con Cobblemon | Objetivo de compatibilidad; integración profunda opcional |

El cargador, la versión de Minecraft y las dependencias concretas están fijados en el proyecto. **Paper/Spigot no es el objetivo de este mod.** Cobblemon sigue siendo una meta de compatibilidad, no una dependencia obligatoria.

## 3. Conceptos y unidades

| Término | Definición |
|---|---|
| **Saldo normal** | Presupuesto diario que se consume durante el juego; valor inicial: 240 minutos. |
| **Saldo recuperado** | Minutos adicionales generados al estar desconectado después de agotar el saldo normal. |
| **Tiempo real jugado** | Tiempo efectivo conectado durante el día, independientemente de los multiplicadores. |
| **Jugador activo** | Jugador conectado que no cumple las condiciones de inactividad (AFK). |
| **Amigos activos** | Otros jugadores activos, sin incluir al propietario del saldo que está desconectado. |
| **Consumo** | Proporción de saldo normal gastada por minuto real conectado. |
| **Límite absoluto** | Máximo de minutos reales de conexión permitidos en un día. |
| **Reinicio diario** | Momento en que se restablecen saldos y contadores diarios. |

Internamente se recomienda contabilizar tiempo en **milisegundos** y redondear solo al mostrarlo. Los valores del YAML se expresan en minutos, segundos y proporciones según su nombre.

## 4. Reglas del tiempo normal

### Valores iniciales

- Saldo normal diario: **240 minutos (4 horas)**.
- Límite absoluto: **360 minutos reales (6 horas)**.
- Al comenzar un nuevo día, se repone el saldo normal hasta 240 minutos; no se arrastra saldo del día anterior.
- Cada jugador consume su propio saldo; no existe una bolsa de tiempo común.

### Multiplicadores por cantidad de jugadores activos

El número incluye al jugador que está conectado y consumiendo saldo.

| Jugadores activos | Consumo de saldo | Tiempo máximo aproximado usando solo el saldo normal* |
|---:|---:|---:|
| 1 | 100 % | 4 h |
| 2 | 90 % | 4 h 27 min |
| 3 | 85 % | 4 h 42 min |
| 4 | 80 % | 5 h |
| 5 o más | 75 % | 5 h 20 min |

\* Supone que la cantidad de jugadores activos se mantiene constante y que el jugador no alcanza antes el límite absoluto.

**Fórmula:**

```text
saldo_normal_consumido = tiempo_real_transcurrido × factor_consumo
tiempo_real_estimado = saldo_normal_restante ÷ factor_consumo
```

**Ejemplo:** con 10 minutos de saldo y 2 jugadores activos, el saldo se gasta al 90 %; eso permite aproximadamente 11 minutos y 7 segundos reales, no 20 minutos.

Los multiplicadores se recalculan cuando cambian los jugadores activos. Nunca se multiplican realmente los minutos almacenados: simplemente se altera la velocidad de consumo.

## 5. Recuperación comunitaria

### Condiciones para recuperar

Un jugador puede generar saldo recuperado cuando se cumplen **todas** estas condiciones:

1. Ha agotado su saldo normal del día.
2. Está desconectado.
3. Han transcurrido al menos **10 minutos** desde la última desconexión; si `only-after-normal-exhaustion` está activado, esa desconexión debe ocurrir después de agotar el saldo normal.
4. Existe al menos un **amigo activo** dentro del servidor.
5. No ha consumido todavía todo su cupo de recuperación generado ese día.
6. No ha alcanzado el límite absoluto de tiempo real del día.

Si `recovery.only-after-normal-exhaustion` está en `false`, se omite la primera condición y un jugador desconectado puede generar recuperación aunque conserve saldo normal.

La recuperación ocurre por la actividad de **otros jugadores**, no por estar simplemente desconectado. Si el servidor está apagado, o nadie está activo, no se genera tiempo.

### Velocidad inicial

- Se ganan **6 segundos de saldo** por cada minuto desconectado y por cada amigo activo.
- El máximo es **30 segundos recuperados por cada minuto desconectado**, aunque haya más de cinco amigos activos.
- Se pueden generar como máximo **60 minutos de saldo recuperado durante un mismo día**.
- El saldo recuperado disponible nunca puede superar 60 minutos.

| Amigos activos | Recuperación por minuto desconectado | Recuperación por hora elegible* |
|---:|---:|---:|
| 0 | 0 s | 0 min |
| 1 | 6 s | 6 min |
| 2 | 12 s | 12 min |
| 3 | 18 s | 18 min |
| 4 | 24 s | 24 min |
| 5 o más | 30 s | 30 min |

\* Una hora **después de la espera inicial**, siempre que no se haya alcanzado el cupo diario.

**Fórmula por intervalo elegible:**

```text
segundos_recuperados = minutos_desconectado_elegibles
                       × MIN(amigos_activos × segundos_por_amigo, tope_por_minuto)
```

Se aplica además el máximo de 60 minutos generados en el día. El cupo **no vuelve a quedar disponible** solo porque el jugador haya gastado parte de lo recuperado.

**Ejemplo:** un jugador agota su saldo, se desconecta durante 120 minutos y hay 4 amigos activos todo el tiempo. Los primeros 10 minutos no generan saldo. En los 110 minutos restantes recupera `110 × 24 s = 2.640 s`, es decir, **44 minutos**.

### Regreso al servidor

- Si el saldo normal está agotado, se requieren al menos **15 minutos recuperados** para volver a entrar.
- Si aún hay saldo normal válido, el umbral de 15 minutos no aplica.
- El saldo recuperado se consume por defecto al **100 %**, sin bonificaciones multijugador. El factor se puede ajustar con `recovery.recovered-consumption`.
- Si al regresar se agota el saldo recuperado y sigue sin saldo normal, se termina la sesión conforme a la política de salida.
- Haber recuperado saldo **no evita** el límite absoluto de 6 horas reales por día.

**Ejemplo:** con cuatro amigos activos de manera continua, el jugador necesita unos **48 minutos desconectado en total** para obtener los 15 minutos mínimos (10 minutos de espera y unos 38 minutos de recuperación).

### Caso especial: todos sin saldo

Si todos los jugadores dejan de estar activos o salen del servidor, la generación de saldo recuperado se detiene. No existe recuperación automática basada solamente en esperar.

## 6. Orden de consumo y límites

Mientras el jugador esté conectado:

1. Se descuenta primero el **saldo normal** con el factor correspondiente a los jugadores activos.
2. Cuando termina el saldo normal, se usa el **saldo recuperado**, si existe, a razón de 1 minuto de saldo por 1 minuto real.
3. Si ambos saldos llegan a cero, se impide seguir jugando hasta reunir saldo suficiente o producirse el reinicio diario.
4. Si alcanza **360 minutos reales conectados** durante el día, se impide continuar incluso si conserva saldo normal o recuperado.

El tiempo real conectado cuenta también para el máximo diario si el jugador está AFK. La inactividad no debe permitir extender artificialmente el límite.

**Nota de equilibrio:** con 5 o más jugadores activos, los 240 minutos normales duran 5 h 20 min. Por lo tanto, el límite absoluto de 6 horas deja como máximo unos 40 minutos reales adicionales ese mismo día, aunque se hayan recuperado 60 minutos de saldo.

## 7. Actividad y prevención de abusos

### AFK

- Umbral inicial de inactividad: **5 minutos**.
- Un jugador AFK **no cuenta** para reducir el consumo de otros ni para generar recuperación a otros.
- Detectar actividad significativa mediante desplazamiento, cambios de orientación de cámara mayores de **1° entre muestras**, cambios de inventario e interacciones; no basta con consultar quién está conectado.
- Los métodos simples de detección no garantizan impedir macros, cuentas secundarias o mecanismos anti-AFK. Se deberá probar con el grupo real.
- Política propuesta para la v1: el jugador AFK sigue consumiendo su tiempo real y saldo, pero no aporta beneficios sociales. La expulsión automática por AFK queda como opción futura.
- Si `counts-as-active-for-others` se activa, un jugador AFK sí cuenta para el multiplicador de los demás y para su recuperación. Esto no cambia su propio factor: al seguir consumiendo saldo AFK, se calcula como un jugador solo. `consume-own-time-while-afk` controla si su saldo se consume; el tiempo real diario siempre cuenta.

### Otras protecciones

- Identificar jugadores por **UUID**, no por nombre.
- Aplicar límites y recuperación por jugador, no por conexión o IP.
- No otorgar saldo retroactivamente por intervalos durante los que el servidor estuvo apagado.
- Si el servidor se cierra inesperadamente mientras una persona seguía conectada y no quedó registrada su desconexión, la espera inicial comienza al arrancar el servidor; el tiempo de apagado no cuenta, incluso si `recovery.only-after-normal-exhaustion` está desactivado.
- Validar que los factores de consumo sean mayores que 0 y que los topes y esperas no sean negativos.
- Registrar los ajustes manuales del administrador para poder auditar problemas.
- Evitar que `/reload` duplique tareas programadas o aplique dos veces un intervalo.
- Si SQLite falla al cargar o guardar, no permitir que CraftBalance siga descontando tiempo sin poder persistirlo; bloquear nuevas conexiones y desconectar las sesiones activas.

## 8. Reinicio diario

El reinicio se realizará a las **00:00 de la zona horaria configurada**, no necesariamente la zona horaria del equipo donde está alojado el servidor.

Si la hora configurada cae en una hora local repetida por el cambio de horario de verano, se usa su primera ocurrencia. Si cae en una hora inexistente, se desplaza hacia delante por la duración del salto. Así cada fecha local tiene un único instante de reinicio.

La fecha diaria persistida nunca retrocede. Si el reloj se ajusta hacia atrás o se cambia la zona horaria a una fecha diaria anterior, se conserva el estado del día ya aplicado; los reinicios se reanudan cuando la fecha calculada vuelva a avanzar.

Al cambiar el día:

1. Restaurar el saldo normal diario a 240 minutos.
2. Poner en cero el tiempo real jugado durante el nuevo día.
3. Poner en cero el saldo recuperado y el contador de recuperación generada del día anterior.
4. Finalizar cualquier espera pendiente del día anterior cuando corresponda.
5. Guardar el nuevo identificador de fecha local de cada jugador.

Si el servidor estuvo apagado al llegar la medianoche, el reinicio se regulariza al arrancar o cuando ese jugador vuelva a ser procesado. Debe ejecutarse **una sola vez por día**.

La hora y la zona horaria son configurables. La política de reinicio mientras una persona está jugando aún no es configurable: la implementación provisional divide el intervalo en el instante exacto del reinicio y aplica de inmediato los saldos del nuevo día, aunque la persona siga conectada. El grupo debe confirmar esta política antes de iniciar la temporada.

## 9. Interfaz y comandos

### Comandos propuestos

| Comando | Resultado | Permiso |
|---|---|---|
| `/craftbalance` o `/cb` | Resumen del estado propio | Jugador |
| `/cb tiempo` | Saldos, tiempo real consumido y estimación del tiempo jugable al ritmo actual; indica si el consumo está pausado | Jugador |
| `/cb estado` | Número de jugadores activos y factor de consumo, o estado pausado | Jugador |
| `/cb ayuda` | Lista de comandos | Jugador |
| `/cb reload` | Validar y recargar `config.yml` sin reiniciar | Administrador |
| `/cb info <jugador\|uuid>` | Consultar saldo, acceso y tiempo jugable estimado; UUID para jugadores desconectados | Administrador |
| `/cb auditoria <jugador\|uuid> [cantidad]` | Consultar los últimos ajustes manuales registrados (5 por defecto, máximo 20); UUID para desconectados | Administrador |
| `/cb set <jugador\|uuid> <tipo> <minutos>` | Ajuste manual de saldo auditado; confirma tipo y minutos aplicados; UUID para jugadores desconectados | Administrador |

Los nombres y permisos son una propuesta y deben revisarse para evitar conflictos con otros mods.

El autocompletado de argumentos administrativos ofrece nombres de jugadores conectados y UUIDs guardados para jugadores desconectados.

Si `/cb set` recibe un UUID que aún no tiene registro, el comando crea el estado inicial con el saldo normal diario configurado y aplica el ajuste con su auditoría en una sola transacción.

### Información mostrada

- **Action Bar:** tiempo normal o recuperado restante y multiplicador actual.
- **Mensajes:** avisos a 30, 10 y 5 minutos antes de agotar el saldo efectivo.
- **Al entrar:** un resumen del saldo, del límite real disponible y del tiempo jugable estimado al ritmo actual.
- **Al agotar el saldo:** mensaje comprensible que explique el bloqueo, la recuperación y cuándo podrá regresar.
- **Al consultar estando bloqueado:** indicar cuánto saldo necesita para volver a conectarse, si existe una vía compatible con Fabric para consultar sin entrar.

La presentación debe ser discreta y configurable, para no interferir con la interfaz de Cobblemon.

## 10. Configuración YAML propuesta

Ruta: `config/craftbalance/config.yml`.

```yaml
# CraftBalance — valores iniciales sujetos a pruebas

time:
  # false suspende consumo y recuperación, pero no los reinicios diarios; el tiempo desactivado no se acredita después.
  enabled: true
  daily-minutes: 240
  max-real-minutes: 360

  daily-reset:
    hour: "00:00"
    timezone: "UTC" # Cambiar a la zona horaria acordada por el grupo

multiplayer:
  enabled: true
  consumption:
    "1": 1.00
    "2": 0.90
    "3": 0.85
    "4": 0.80
    "5+": 0.75

recovery:
  enabled: true
  # false permite recuperar saldo desconectado aunque aún quede saldo normal.
  only-after-normal-exhaustion: true
  seconds-per-friend-per-minute: 6
  max-seconds-per-minute: 30
  initial-wait-minutes: 10
  max-daily-minutes: 60
  max-bank-minutes: 60
  minimum-rejoin-minutes: 15
  recovered-consumption: 1.00

afk:
  enabled: true
  inactivity-minutes: 5
  counts-as-active-for-others: false
  consume-own-time-while-afk: true

display:
  action-bar: true
  login-summary: true
  warnings-minutes: [30, 10, 5]

database:
  type: sqlite
  file: "craftbalance.db"
```

### Validación y recarga

- El mod genera este archivo automáticamente si falta.
- Los cambios se pueden aplicar con `/cb reload`.
- Antes de aplicar la nueva configuración, el servidor contabiliza el intervalo pendiente con las reglas que estaban activas.
- Si algún valor es inválido, se informa del error y se mantiene **la última configuración válida**.
- Se rechazan claves YAML duplicadas para evitar que un valor sobrescriba silenciosamente a otro.
- Se rechazan claves no reconocidas y se indica su ruta para detectar errores tipográficos en el YAML.
- `recovery.minimum-rejoin-minutes` no puede superar `recovery.max-bank-minutes`, porque el umbral de regreso debe poder alcanzarse.
- Con recuperación activada, `recovery.minimum-rejoin-minutes` tampoco puede superar `recovery.max-daily-minutes`.
- Con recuperación activada, el máximo diario, el máximo del banco, la tasa por amigo y el tope de segundos por minuto deben ser mayores que cero. Para desactivar la función, se usa `recovery.enabled: false`.
- Los factores de consumo deben ser al menos `1e-18`; el límite evita que el residuo fraccional quede por debajo de la precisión numérica acumulable.
- Aunque `recovery.minimum-rejoin-minutes` sea cero, hace falta algún saldo recuperado positivo para volver; sin saldo no hay tiempo de juego que autorizar.
- El YAML se debe leer con una biblioteca incluida y empaquetada para el entorno Fabric.
- La persistencia de jugadores **no** se guarda en YAML, sino en SQLite.

## 11. Persistencia y arquitectura propuesta

### Datos por jugador

La base SQLite debe conservar, como mínimo:

- `uuid`: identificador permanente.
- `daily_date`: fecha del último reinicio aplicado.
- `normal_balance_ms`: saldo normal disponible.
- `recovered_balance_ms`: saldo recuperado disponible.
- `recovery_generated_today_ms`: recuperación generada durante el día, aunque ya se haya gastado.
- `real_played_today_ms`: tiempo real de conexión acumulado.
- `last_disconnect_at`: inicio del último período de desconexión relevante.
- `normal_exhausted_today`: condición que habilita la recuperación.
- `recovery_fractional_numerator`: fracción submilisegundo pendiente de la tasa de recuperación, para no perderla al procesar intervalos cortos ni reiniciar el servidor.
- `consumption_fractional_ms`: fracción de milisegundo de consumo normal o recuperado acumulada entre intervalos y reinicios.
- Datos mínimos para reanudar correctamente tras una desconexión y auditar ajustes de administración.

### Módulos internos

| Módulo | Responsabilidad |
|---|---|
| `CraftBalanceMod` | Inicialización y registro del mod de Fabric |
| `TimeManager` | Saldo normal, saldo recuperado y límite real |
| `PresenceManager` | Jugadores conectados, actividades y AFK |
| `RecoveryManager` | Generación de saldo estando desconectado |
| `DailyResetService` | Cambio de día según zona horaria |
| `ConfigManager` | YAML, validación y recarga |
| `PlayerRepository` | Consultas y escrituras en SQLite |
| `CommandManager` | Comandos y permisos |
| `DisplayManager` | Mensajes y Action Bar |

### Ciclo de actualización

**Frecuencia sugerida:** una vez por segundo para cálculo de tiempo y estado; guardar periódicamente y ante desconexiones/paradas del servidor. No realizar operaciones lentas de disco en el hilo principal.

```text
Cada segundo:
  1. Medir tiempo real transcurrido desde el ciclo anterior.
  2. Actualizar presencia y estado AFK.
  3. Determinar el número de jugadores activos.
  4. Descontar saldo y tiempo real a cada jugador conectado.
  5. Generar recuperación a desconectados que cumplan condiciones.
  6. Aplicar límites y avisos.
  7. Marcar estados modificados para persistirlos de forma segura.
```

Para evitar que el lag altere los resultados, calcular con tiempo real transcurrido, no suponiendo que siempre se ejecutaron exactamente 20 ticks por segundo.

## 12. Casos de prueba de aceptación

Antes de publicar la primera versión, deberían cumplirse estos escenarios:

1. **Jugador solo:** con 240 minutos normales y sin desconexiones, puede jugar 4 horas reales.
2. **Dos jugadores activos:** 10 minutos normales duran aproximadamente 11 minutos y 7 segundos reales si no cambia el grupo.
3. **Cinco jugadores activos:** el saldo normal completo dura 5 horas y 20 minutos, salvo que el límite absoluto se haya alcanzado antes.
4. **Desconexión sin amigos activos:** tras agotar el saldo, no recupera ningún minuto.
5. **Desconexión con 4 amigos activos:** después de 120 minutos desconectado, recupera 44 minutos si todavía tiene cupo.
6. **Espera mínima:** los primeros 10 minutos desconectado tras el agotamiento no dan saldo.
7. **Umbral de regreso:** si solo tiene 14 minutos recuperados y el saldo normal sigue agotado, no puede entrar; con 15 minutos sí.
8. **Consumo recuperado:** con el factor predeterminado de 100 %, 20 minutos recuperados permiten 20 minutos reales, con independencia del tamaño del grupo.
9. **Cupo de recuperación:** no puede generar más de 60 minutos durante el mismo día, aunque gaste saldo y vuelva a desconectarse.
10. **Límite absoluto:** al llegar a 6 horas reales, no puede seguir jugando ese día aunque tenga saldo disponible.
11. **AFK:** al cumplirse el umbral, deja de generar beneficios para otros jugadores.
12. **Servidor apagado:** no se acredita recuperación por ese intervalo; si hubo un cierre inesperado con una persona aún marcada como conectada, su espera inicial comienza al arrancar.
13. **Reinicio del servidor:** recupera exactamente sus saldos persistidos, sin duplicaciones.
14. **Cambio de día:** saldos y contadores se restablecen exactamente una vez en la zona horaria configurada.
15. **Archivo YAML inválido:** un `/cb reload` fallido no altera las reglas que ya estaban funcionando.
16. **Precisión de recuperación:** un amigo activo durante 60 minutos elegibles genera 6 minutos recuperados aunque el cálculo ocurra en intervalos de un segundo.
17. **AFK por orientación:** un cambio de cámara mayor de 1° entre muestras restablece el contador de inactividad.

## 13. Roadmap

En esta lista, `[x]` indica que la función está implementada en el código; no implica que haya superado validación en juego. Los casos de aceptación de la sección 12 siguen siendo la referencia para esa validación.

### v1.0 — Sistema base (prioridad alta)

- [x] Crear proyecto Java/Fabric.
- [x] Cargar y validar `config.yml`.
- [x] Persistencia SQLite por UUID.
- [x] Saldo normal y multiplicadores sociales.
- [x] Recuperación de saldo desconectado.
- [x] Límite real diario y reinicio.
- [x] Comando de consulta y mensajes de bloqueo.
- [ ] Pruebas automatizadas de fórmulas y límites.

### v1.1 — Calidad de vida

- [x] Detección AFK mediante desplazamiento, orientación e interacciones de servidor.
- [x] Action Bar y avisos configurables.
- [x] Comandos administrativos y `/cb reload`.
- [x] Registros de auditoría y manejo de errores.
- [x] Consulta administrativa del historial de ajustes por jugador.
- [ ] Pruebas en servidor real con amigos.

### v1.2+ — Progreso compartido (opcional)

- [ ] Objetivos comunitarios para desbloqueos.
- [ ] Integración con las APIs/eventos compatibles de Cobblemon.
- [ ] Límites opcionales de nivel o hitos de progreso Pokémon.
- [ ] Reglas adicionales según la experiencia de juego real.
- [ ] Exportación YAML desde la calculadora de CraftBalance.

## 14. Decisiones abiertas

Estas cuestiones siguen pendientes de confirmación del grupo. La implementación inicial usa los siguientes comportamientos provisionales; no implican una decisión final del grupo:

1. **Hora y zona horaria exactas del reinicio diario:** el valor inicial es 00:00 UTC; cambiarlo a la zona horaria acordada antes de iniciar la temporada.
2. **Política al agotar el saldo:** actualmente desconecta al jugador en el siguiente ciclo de cálculo, sin margen de gracia.
3. **Cómo informar a un jugador bloqueado:** el mensaje de desconexión muestra cuánto saldo recuperado le falta. `/cb info <uuid>` permite que un administrador consulte su saldo; no existe un comando de jugador que funcione mientras está desconectado.
4. **Qué actividades determinan el AFK:** actualmente se cuentan movimiento, orientación, cambios de inventario/barra rápida, chat, intentos de golpear bloques y varias interacciones de Fabric; otras acciones de modpacks pueden requerir integración.
5. **Si un admin puede omitir límites:** los comandos administrativos requieren nivel de permiso 2 y registran ajustes en SQLite. Pueden reponer el saldo normal hasta el máximo diario configurado o el recuperado hasta el tope del banco; no pueden superar el límite absoluto de tiempo real.
6. **Configuraciones muy agresivas:** se validan tipos, rangos no negativos, factores de consumo de al menos `1e-18`, claves y algunas relaciones entre valores. También se registran avisos no bloqueantes para saldos diarios superiores a 24 horas, límites reales superiores a la duración habitual del día local, factores sociales activos menores que `0.01` o mayores que `10`, y recuperación activa potencial superior a 60 segundos por minuto elegible.
7. **Qué parte del progreso de Cobblemon controlar:** fuera del alcance inicial; no existe integración ni dependencia de Cobblemon.
8. **Reinicio mientras se juega:** la implementación provisional aplica el nuevo día en el instante configurado, incluso con la persona conectada; todavía no existe una opción para aplazarlo.

---

## 15. Criterio de éxito

CraftBalance cumple su objetivo si, tras varias sesiones reales, el grupo observa que:

- Los jugadores que tienen menos tiempo libre pueden participar en actividades relevantes.
- Seguir jugando acompañado resulta atractivo.
- Las sesiones prolongadas tienen un límite predecible.
- Se entiende fácilmente cuánto tiempo queda y cómo se recupera.
- Nadie necesita manipular archivos o perder sus datos para seguir jugando.

**Estado actual:** la implementación inicial existe en el proyecto Fabric y compila como JAR remapeado 0.1.53 para Minecraft 1.21.1. Con el sistema temporalmente desactivado, el mod sigue actualizando presencia y AFK sin consumir ni generar saldo; aplica los reinicios diarios y crea saldo inicial para jugadores nuevos. Cada inicio del servidor limpia el estado de la instancia anterior y los callbacks SQLite tardíos no pueden modificar la instancia siguiente. SQLite se abre en WAL con `synchronous=FULL`; ambos ajustes se verifican al inicio y una base que no confirme WAL se rechaza. Los reinicios diarios usan la misma marca temporal del evento que contabiliza el intervalo. El cálculo de recuperación convierte segundos de saldo a milisegundos correctamente y conserva la precisión fraccional. Una reducción del límite real diario conserva el total ya jugado aunque el tope cambie después. Los cursores de recuperación son monotónicos para evitar volver a procesar intervalos si el reloj del sistema retrocede, y los intervalos offline se dividen en cada reinicio diario para aplicar topes por fecha. El último intervalo antes de una desconexión conserva al jugador que se va dentro del conteo de amigos activos. Los comandos administrativos pueden inicializar y auditar un UUID todavía no registrado sin crear registros si falla la transacción. Los ajustes del saldo normal mantienen el cursor de recuperación en el momento de la operación y no vuelven a acreditar tiempo offline previo. Si SQLite confirma una transacción y falla una operación de limpieza posterior, el runtime no revierte en memoria el resultado que ya quedó confirmado. El código actualiza el conteo social inmediatamente al entrar o salir un jugador, al recargar la configuración AFK e ignora conexiones que aún no tienen presencia inicializada. Las sesiones conectadas se liquidan en el límite temporal de cada entrada y salida usando el grupo previo al cambio; esa liquidación también conserva los avisos de saldo al cruzar umbrales. Cuando una liquidación previa a la revisión del jugador alcanza el saldo vacío o el límite real diario, el motivo se conserva y provoca la salida sin volver a contabilizar el intervalo. Los factores de consumo se muestran con precisión decimal o notación científica para no presentar tasas pequeñas como consumo pausado ni expandir factores extremos en mensajes demasiado largos. El movimiento de poca distancia se acumula entre muestras para detectar desplazamientos lentos en la regla AFK. También cubre configuración YAML, persistencia SQLite, saldos, multiplicadores, recuperación, límites diarios, detección AFK, comandos y mensajes descritos arriba. La detección AFK observa el inventario personal y el cursor, sin considerar cambios automáticos en contenedores externos como actividad. En un apagado ordenado se regulariza la fecha y recuperación de jugadores desconectados, y se contabiliza el intervalo conectado final antes de persistir el estado. La compilación no demuestra por sí sola el comportamiento en juego: los casos de aceptación, especialmente con varios jugadores, reinicios y cambio horario, siguen pendientes de validación en un servidor real.
