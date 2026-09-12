# Manual de operación

Guía de uso diario, una vez que el sistema está instalado (la instalación paso a paso está en
[`../SETUP.md`](../SETUP.md)). Para entender por dentro qué hace cada cosa, ver
[`ARQUITECTURA.md`](ARQUITECTURA.md) y [`MAPA-RECURSOS.md`](MAPA-RECURSOS.md).

## 1. Los dos papeles

| Papel | Dónde actúa | Qué puede hacer |
|---|---|---|
| **Dueño del teléfono** (quien lo lleva) | La app del móvil | Iniciar/detener el rastreo, PIN, modo antirrobo, SOS, “llegué bien”, cambiar ajustes, desvincular |
| **Administrador del panel** (el adulto responsable) | `admin.html` con su cuenta | Ver todo, controlar el teléfono a distancia, definir zonas, ocultar del panel familiar, revocar tokens |
| **Familia** (cualquiera con la URL) | `index.html` | Ver el mapa y la última señal de los dispositivos **visibles** |

El panel familiar no pide cuenta: solo muestra lo que cada dispositivo permite (`show_on_public`) y
nunca el historial. Todo lo demás vive detrás del login.

## 2. Rutina recomendada

**Cada día (30 segundos).** Abrir `admin.html` y mirar la columna *Dispositivos*: la **línea de avisos**
resume lo que importa — falta de señal (`sin señal hace X h`), batería baja sin cargar, mandos sin
recoger y manipulación (`⚠️ N problema(s)`). Si no hay nada, cerrar.

**Cuando algo te preocupe.** Seleccionar la persona ⇒ el **rastro del día** con paradas, huecos y puntos
descartados; el **Estado del dispositivo**; y si hace falta detalle, **⚡** (persecución) durante unos
minutos.

**Cada pocas semanas.** Revisar en *Estado del dispositivo* que no haya accesibilidad, lectores de
notificaciones ni administradores inesperados; y en *Aplicaciones instaladas* si aparece algo nuevo que
no conozcas (eso es la señal de “toca hablar”).

**Mantenimiento del backend.** Reejecutar `web/supabase-setup.sql` cuando el README lo pida (idempotente,
no borra datos). La retención se encarga sola si `pg_cron` está disponible: posiciones 7 días, comandos
30, avisos 90. Si no, ejecutar `select public.prune_positions();` de vez en cuando.

## 3. Control remoto

Desde la ficha de cada dispositivo en el panel:

| Botón | Qué hace | Ojo con |
|---|---|---|
| 📍 | **Ubicar ahora**: fix inmediato de alta precisión | Envía **un** punto y no reactiva el rastreo, así que funciona aunque esté detenido |
| 🔒 | **Bloquear pantalla** | Requiere modo antirrobo activo; da `failed` con el motivo si no lo está |
| 🔔 | **Alarma** a volumen de alarma, aunque esté en silencio | Se detiene sola a los 2 minutos |
| 🔕 | Detener la alarma | — |
| ⚡ | **Persecución**: cada 3 s, alta precisión | Máximo 30 min, gasta más batería, la notificación lo muestra |
| 🔋 | Cortar la persecución antes de tiempo | — |
| ⏹ | **Detener el rastreo** | Sigue detenido: hay que reiniciarlo a mano en el teléfono |
| 👁 / 🙈 | Mostrar u ocultar del panel familiar | El historial se conserva para el admin |
| Renombrar | Cambiar la etiqueta | — |
| 🚫 | **Revocar el token**: el teléfono deja de poder enviar | Para un móvil perdido o robado |

Los comandos se recogen en el **próximo sondeo** (cada 60 s) y el resultado real (`done`/`failed` +
motivo) aparece en *Últimos comandos*. Un teléfono apagado o sin red no ejecuta órdenes viejas: las que
esperan más de 10 minutos caducan. El propio teléfono muestra `último mando: alarma OK` en su pantalla:
quien lo lleva ve que el panel le acaba de mandar algo.

> **Expectativa honesta:** nada de esto impide que alguien con el teléfono desbloqueado en la mano apague
> lo que quiera. Lo que sí garantiza es que **no lo haga sin que te enteres**: el aviso de manipulación
> llega con hora y motivo.

## 4. Zonas seguras (control parental)

1. En el panel, elegir la persona y **📍 Usar centro del mapa** o **Usar última posición**.
2. Poner nombre reconocible (`Casa`, `Colegio`, `Casa de la abuela`), radio entre 50 m y 20 km, y avisar
   al **entrar**, al **salir** o **ambos**. Guardar.
3. Los avisos aparecen en *Avisos recientes* y el círculo se pinta verde (dentro) o ámbar (fuera).

Detalles que evitan sustos: la evaluación ocurre **en el servidor** con cada envío de posición (cero
batería extra, funciona con la app cerrada y tras un reinicio), y el **primer dato tras crear la zona
solo fija el estado**: no hay un “entró” falso por acabar de crearla.

## 5. Avisos del teléfono (las dos direcciones)

- **🆘 SOS** (en el móvil): envía el aviso con la posición y activa **10 minutos** de seguimiento cada
  2 s. Si el rastreo estaba parado, lo inicia: lo pide quien tiene el teléfono, no el panel.
- **✅ Llegué bien**: check-in sin cambiar el ritmo de muestreo.

Ambos quedan registrados con hora. Es la parte de supervisión que funciona en las dos direcciones: el
menor también puede avisar.

## 6. Aplicaciones instaladas (solo nombres)

Tarjeta *Aplicaciones instaladas*: número total y buscador por nombre o paquete. Sirve para saber si hay
que sentarse a hablar, no para vigilar.

- Se comparte **solo el nombre y el paquete**: no hay tiempos de uso, horas de apertura ni frecuencia.
- El interruptor *«Compartir la lista de apps instaladas»* está en la pantalla del teléfono. Si se apaga,
  el panel dice `no compartida` (y distingue el caso de un APK antiguo).
- Si quieres **limitar** apps y tiempos, la herramienta correcta es Google Family Link.

## 7. Ajustar los umbrales

Todo lo que decide “qué es un rastro raro” está en `web/config.js`, sin tocar la lógica:

| Clave | Por defecto | Qué controla |
|---|---|---|
| `maxKmh` | 250 | Saltos más rápidos = ruido de GPS, se descartan |
| `maxAccuracyM` | 150 | Fixes peores no se dibujan |
| `stopRadiusM` | 75 | Radio de “sigue en el mismo sitio” |
| `stopMin` | 5 | Minutos mínimos dentro del radio para contar parada |
| `gapMin` | 10 | Silencio que se marca como hueco |
| `alertBatteryPct` | 15 | Batería por debajo de la cual avisa (sin cargador) |
| `alertSilentMin` | 360 | Minutos sin reportar = “sin señal” |
| `staleAfterSec` | 120 | Segundos para marcar la conexión como sin señal reciente |

En el teléfono, los ajustes útiles son: **intervalo** (5–300 s), **intervalo en reposo** (15–900 s),
**Precision+** (celdas/WiFi, más batería), **ahorro adaptativo**, **notificación discreta** (baja
importancia; **nunca** la oculta), **rastreo inteligente** y **radio de quieto** (5–500 m).

## 8. Problemas frecuentes

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| El panel dice *sin posición* para alguien | Nunca ha enviado: sin permisos, sin datos o app sin iniciar | Revisar en el teléfono permiso de ubicación, **Iniciar rastreo** y que el uuid coincida |
| `⚠️ permiso de ubicación revocado` | Alguien lo quitó (o el sistema tras una actualización) | Restaurar; el aviso desaparece en la siguiente comprobación (o al abrir la app) |
| `modo antirrobo desactivado` | Se desactivó a propósito | Es la señal fuerte de manipulación: hablar con quien lo tiene |
| El rastreo no vuelve tras reiniciar | Autoinicio bloqueado por la capa del fabricante | Batería → permitir autoinicio; conceder la exención de batería |
| `🔒 failed: modo antirrobo no activo` | DeviceAdmin no activado en ese teléfono | Activarlo en la app, o usar solo 📍/🔔 |
| Zonas que nunca avisan | La zona es de **otro** dispositivo, o el primer fix solo fijó el estado | Comprobar el dispositivo seleccionado y moverse más del radio en un envío posterior |
| *Aplicaciones instaladas* vacía o `0` | APK antiguo o interruptor desactivado | Recompilar el APK / revisar el interruptor en la app |
| *Estado del dispositivo*: “Sin datos todavía” | Backend o APK antiguos | Reejecutar `supabase-setup.sql` completo y abrir la app una vez |
| Los comandos se quedan *pending* | El móvil está apagado, sin red o con la app congelada por el fabricante | Esperar; caducan a los 10 min. Revisar exención de batería |
| `rastreo sin acceso a ubicación: abre la app una vez` | Android 14+: tras un reinicio el sistema no deja que el servicio de ubicación arranque desde segundo plano | Abrir la app una vez en ese teléfono; recupera el GPS y el aviso desaparece |
| El aviso `sin acceso a ubicación` no se va aunque abra la app | Falta el permiso de ubicación, o el teléfono bloqueó el arranque de la app | Conceder ubicación (y «todo el tiempo») y revisar el autoinicio del fabricante |

## 9. Privacidad, legalidad y límites

**Lo que este sistema hace:** ubicación en tiempo real e histórico, zonas con aviso, rastro analizado,
estado y postura del teléfono, lista de apps **solo por nombre**, y avisos de manipulación.

**Lo que no hace, por diseño:** no oculta la app ni la notificación, no suplanta otra aplicación, no
impide el force-stop, no se reinstala sola, no borra el teléfono en remoto, no lee mensajes, contactos,
fotos, cámara ni micrófono, y **no sigue rastreando después de una parada deliberada**. Tampoco sube
tiempos de uso de aplicaciones.

**Condiciones de uso legítimo**, que hay que cumplir las tres:

1. **Dispositivo propio o de un menor bajo tu responsabilidad**, no de un adulto sin su conocimiento.
2. **El usuario del teléfono lo sabe**: el icono está en el lanzador, la notificación es permanente con
   su botón Detener y el aviso de uso responsable se muestra al instalar y enumera todo lo que se comparte.
3. **Proporcionalidad**: para proteger (un menor, un móvil perdido), no para controlar la vida privada de
   alguien. Si la instalación es a escondidas en el teléfono de otra persona adulta, es *stalkerware* y es
   ilegal en la mayoría de países; este proyecto no lo soporta.

**Límites técnicos que conviene tener claros:** el PIN es una barrera práctica (no criptografía fuerte)
frente a quien controla físicamente un dispositivo desbloqueado; la detección de root es de mejor
esfuerzo (Magisk y similares la ocultan); en algunos fabricantes leer ajustes globales como la depuración
USB o Play Protect puede devolver el valor por defecto; y con el teléfono apagado o sin red la única
señal posible es el aviso de **sin señal**, que detecta por ausencia.

## 10. Comprobación rápida de salud del sistema

Antes de dar por bueno un despliegue (o tras actualizar algo), en 5 minutos:

1. **Localizador en la app** ⇒ aparece el fix en el panel en menos de un minuto.
2. **✅ Llegué bien** ⇒ aviso en *Avisos recientes*.
3. **🔔** ⇒ suena la alarma del teléfono, y 🔕 la corta.
4. **📍** ⇒ el comando pasa a `done` con su hora.
5. **Zona** alrededor de donde estás ⇒ al salir del radio llega el aviso `exit`.
6. **Quita el permiso de ubicación** y abre la app ⇒ el panel muestra `⚠️` con el motivo.
7. **Reinicia el móvil** ⇒ el rastreo vuelve solo.
8. **Detener** en la app (con PIN) ⇒ **no** vuelve solo. Parar es parar.
