# 📋 SETUP.md — Todo lo que hay que configurar, completar, ajustar, crear y hacer

Guía única y ordenada para dejar **funcionando el sistema completo**:
agente Android → Supabase (gratis) → panel web en GitHub Pages (gratis).

> Marca cada casilla `[ ]` → `[x]` a medida que la completas.

---

## 0. Requisitos previos (lo que necesitas antes de empezar)

- [ ] Una cuenta de **GitHub** (gratis) — https://github.com/signup
- [ ] Una cuenta de **Supabase** (gratis) — https://supabase.com
- [ ] Un teléfono Android **8.0 a 14** para instalar el agente
- [ ] (Opcional) **Android Studio** solo si quieres compilar en local;
      **no es obligatorio**: GitHub Actions compila el APK por ti (sección 4)
- [ ] 15–20 minutos

---

## 1. CREAR el backend en Supabase

1.1. [ ] Crear proyecto nuevo en https://supabase.com → *New project*
     (guarda la contraseña de la BD; la región más cercana es suficiente)

1.2. [ ] Abrir **SQL Editor → New query**, pegar **todo** el contenido de
     [`web/supabase-setup.sql`](web/supabase-setup.sql) y pulsar **Run**
     - Debe terminar sin errores. Crea: tablas `devices`, `positions` y `device_commands`,
       vistas `latest_positions`, `public_devices` y `device_health`, políticas RLS, funciones
       `pair_device`, `ingest_positions`, `prune_positions`, las de control remoto
       (`enqueue_command`, `pull_commands`, `ack_command`), las de revocación
       (`revoke_self`, `revoke_device`), las de estado (`report_health`, con la postura de
       seguridad del dispositivo: bloqueo, parche, root, USB, Play Protect, apps con
       accesibilidad/notificaciones/admins y la lista de apps instaladas solo por nombre),
       **control parental** (`geofences`, `geofence_events`, `geofences_eval`) y
       **avisos del teléfono** (`device_events`, `report_event`), más el cron de retención.
     - Si ya tenías el sistema funcionando: reejecutar este archivo es lo que actualiza
       el backend (es idempotente). No borra datos.

1.3. [ ] **Emparejar tu dispositivo** (aún en el SQL Editor):
     ```sql
     select public.pair_device('Mi Telefono', 'TOKEN-LARGO-ALEATORIO-123456');
     ```
     - El token: mínimo **16 caracteres**, aleatorio, y **guárdalo** — no se
       puede volver a leer (solo se guarda su hash SHA-256).
     - **Copia el `uuid`** que devuelve la consulta: lo necesitas en el paso 4.3.

1.4. [ ] Obtener las credenciales de la API:
     **Settings → API** → copiar
     - `Project URL` → algo como `https://abcdxyz.supabase.co`
     - `anon public` key (la clave larga `eyJhbGci...`)

1.5. [ ] **Crear el usuario administrador** del panel:
     **Authentication → Users → Add user** (email + contraseña).
     Ese login es el que usa `admin.html`.

1.6. [ ] (Verificación opcional) Que RLS está activa:
     **Authentication → Policies** → `devices` y `positions` deben mostrar
     políticas solo para `authenticated`, y la vista `latest_positions` para `anon`.

---

## 2. CONFIGURAR y publicar el panel web en GitHub Pages

2.1. [ ] Crear un repositorio en GitHub (público, p. ej. `localizador`)
     y subir el contenido del proyecto (o al menos la carpeta `web/` completa:
     `index.html`, `admin.html`, `app.js`, `admin.js`, `config.js`).

2.2. [ ] Editar **`web/config.js`** con TUS datos (único archivo a tocar):
     ```js
     supabaseUrl: "https://abcdxyz.supabase.co",   // del paso 1.4
     supabaseAnonKey: "eyJhbGci...",               // del paso 1.4
     mapCenter: [lat, lon],                        // ciudad inicial del mapa
     mapZoom: 14,
     pollMs: 5000,                                 // sondeo cada 5 s
     staleAfterSec: 120,                           // umbral de "SIN SEÑAL"
     demo: false,                                  // dejar en false

     // Opcionales (ya tienen estos valores por defecto): umbrales del rastro
     maxKmh: 250, maxAccuracyM: 150, stopRadiusM: 75, stopMin: 5, gapMin: 10,
     // y de los avisos del panel
     alertBatteryPct: 15, alertSilentMin: 360
     ```

2.3. [ ] Activar Pages (UNA sola vez): **Settings → Pages → Build and
     deployment → Source = "GitHub Actions"**. Desde ese momento CADA push a
     `main` recompila y republica el panel solo (workflow **Deploy panel**).
     No vuelvas a tocar nada: el despliegue es automático.

2.4. [ ] Verificar (sin datos aún):
     - `https://TU-USUARIO.github.io/TU-REPO/` → mapa visible + banner rojo
       "Falta configurar" **solo si** no subiste el `config.js` editado;
       con config correcta el badge pasa a **EN VIVO** o **SIN SEÑAL RECIENTE**.
     - Prueba rápida sin backend: `.../index.html?demo=1` → dispositivo simulado.
     - `.../admin.html` → inicia sesión con el usuario del paso 1.5.

2.5. [ ] Confirmar en **Actions** que los tres workflows quedan verdes:
     - **Web CI** — sintaxis JS/SQL y estructura de páginas, sin placeholders
       en `config.js`.
     - **Android CI** — firma y **publica** el APK en el release «Última
       compilación» con cada push (artifact `locator-agent-apk`).
     - **Deploy panel** — publica `web/` en GitHub Pages automáticamente.

---

## 3. CREAR el APK del agente Android (dos caminos)

### 3.A — Compilar en GitHub (recomendado, sin instalar nada)

3.A.1 [ ] Hacer push del repo (incluye `.github/workflows/android.yml`).
3.A.1b [ ] (Una sola vez) Para que las actualizaciones se instalen sin desinstalar,
      sigue [RELEASE.md](RELEASE.md): crea el secreto `ANDROID_KEYSTORE_PASSWORD`.

3.A.2 [ ] Ir a **Actions → Android CI** → esperar el ✅ (JDK 17 + Gradle 8.9,
      `assembleDebug` + `lintDebug`).
3.A.3 [ ] Descargar el APK publicado: **Releases → «Última compilación»**
      (`locator-agent-<versión>.apk`, firmado), o el artifact
      **`locator-agent-apk`** de la pestaña **Actions**
      (abajo de la ejecución del workflow) → contiene `app-debug.apk`.
3.A.4 [ ] Pasar el APK al teléfono e instalarlo
      (aceptar "instalar de fuentes desconocidas" la primera vez).

### 3.B — Compilar en local

3.B.1 [ ] Abrir la carpeta `android/` en Android Studio (JDK 17), sincronizar Gradle.
3.B.2 [ ] `Build → Build APK(s)` → `app/build/outputs/apk/debug/app-debug.apk`.

> Si el workflow **falla**: abre el log del paso `Build debug APK + lint`;
> casi siempre es una versión de dependencia. Corrige, push, y se recompila solo.

---

## 4. CONFIGURAR el agente en el teléfono

4.1. [ ] Abrir la app **Location Agent** (icono visible en el lanzador).

4.2. [ ] Sección **Emparejamiento (Supabase)** — completar los 4 campos:
     | Campo de la app | Valor | Origen |
     |---|---|---|
     | URL Supabase | `https://abcdxyz.supabase.co` | paso 1.4 |
     | Clave anon public | `eyJhbGci...` | paso 1.4 |
     | UUID del dispositivo | `xxxxxxxx-xxxx-...` | devuelto por `pair_device` (paso 1.3) |
     | Token de emparejamiento | tu token en claro | el que pusiste en 1.3 |

4.3. [ ] Ajustes de **Comportamiento** (opcionales, hay defaults sanos):
     - Intervalo de reporte: **5 s** (tiempo real) / 10 / 30 / 60 / 300 s.
     - **Precisión+**: adjunta metadatos de celdas/WiFi a cada fix (auditoría).
     - **Batería adaptativa**: bajo 20 % sin cargador ralentiza solo (recomendado ON).
     - **Notificación discreta**: notificación mínima (sigue SIEMPRE visible).
     - **Control remoto**: acepta comandos del panel (ubicar, bloquear, alarma, detener).
     - **Rastreo inteligente**: si no te mueves, no gasta GPS; manda un "sigo vivo" cada 5 min.

4.4. [ ] Pulsar **Guardar configuración** (toast "Configuración guardada").

4.5. [ ] Pulsar **Iniciar rastreo** y conceder permisos cuando el sistema pregunte:
     - [ ] **Ubicación** (mientras se usa)
     - [ ] **"Permitir todo el tiempo"** (imprescindible para reportar con pantalla apagada;
           la app abre el diálogo para llevarte a Ajustes)
     - [ ] **Notificaciones** (Android 13+; necesaria para la notificación del servicio)

4.6. [ ] Pulsar **Optimizar batería (exención)** y aceptar el diálogo oficial del
      sistema → evita que el fabricante mate el servicio.

4.7. [ ] (Recomendado) **Seguridad y control → Definir PIN del propietario** (4-8 dígitos).
      A partir de ahí, detener el rastreo, cambiar la configuración, desactivar el modo
      antirrobo o desvincular pedirán ese PIN. No se puede recuperar: apúntalo.

4.8. [ ] (Opcional, teléfono **tuyo**) **Activar modo antirrobo**: abre el diálogo oficial de
      Android y acepta. Sirve para bloquear la pantalla en remoto desde el panel y hace que
      Android exija desactivar la protección antes de desinstalar la app. No oculta nada:
      el icono y la notificación siguen visibles y no hay borrado remoto.

4.9. [ ] (Opcional) **Detener y desvincular dispositivo**: pide el PIN, para el rastreo,
      borra el buffer local y **invalida el token en el servidor** (elige si borras también
      el historial). Es la salida limpia: después, el teléfono ya no puede enviar nada.

4.7. [ ] Confirmar en la barra de estado la notificación **"Seguimiento activo"**
      (con botón **Detener**). Esa notificación es la prueba visible de que corre.

---

## 5. VERIFICAR el sistema completo (de punta a punta)

- [ ] Abrir el panel público: el marcador aparece en ≤ 10 s y el badge dice **EN VIVO**.
- [ ] Caminar unos metros: la **ruta de sesión** suma puntos y se dibuja la línea.
- [ ] Tarjeta de estado: batería, velocidad, precisión (± m) y fuente coherentes
      (GPS al aire libre, NETWORK/FUSED en interiores).
- [ ] Apagar WiFi/datos 1–2 min: el badge pasa a **SIN SEÑAL RECIENTE**;
      al volver la red, el buffer cifrado se vacía solo (posiciones sin perder).
- [ ] `admin.html`: **Consultar** → tabla del historial, mapa con la ruta,
      gráficas de batería y distancia, **replay** con el slider, exportar **CSV/GPX**.
- [ ] **Control remoto**: en `admin.html` → *Dispositivos*, pulsa 📍 en tu dispositivo y
      en ≤ 60 s aparece un punto nuevo en el mapa; la caja **Últimos comandos** debe pasar
      de `pending` a `delivered` y luego a `done`.
      - Prueba 🔔 (suena la alarma del teléfono, se para sola en 2 min o con 🔕).
      - Prueba 🔒: solo funciona si activaste el **modo antirrobo** en la app (paso 4.8).
      - Prueba ⚡: en el teléfono la notificación debe cambiar a "Persecución activa · 3 s
        (temporal)" y los puntos aparecen cada 3 s; al expirar vuelve solo al ritmo normal.
      - Prueba ⏹: el rastreo se detiene y **no se relanza** (el watchdog queda apagado).
- [ ] **Panel familiar** (`index.html`): con dos o más dispositivos emparejados deben verse
      **todos los marcadores a la vez**, cada uno con su color, y la leyenda **Familia** con
      nombre, última señal y batería. Pulsar una fila centra a esa persona.
- [ ] **Rastro** (`admin.html`): tras unas horas de datos, la tarjeta **Rastro** debe mostrar
      paradas (con duración), huecos, puntos descartados y tiempo en movimiento/parado.
      La casilla **Suavizar** solo cambia la línea; CSV/GPX/replay no se tocan.
- [ ] **Avisos**: apaga un móvil (o quítale la cobertura) y comprueba que el panel lo marca como
      **sin señal desde hace X** en la línea de avisos, sin que desaparezca de la lista.
- [ ] **Modo discreto de verdad**: activa *Notificación discreta* en la app y comprueba que la
      notificación queda plegada y silenciosa (sigue visible y con su botón **Detener**).
      Con un APK antiguo este ajuste no hacía nada: el canal de notificación no cambiaba.
- [ ] **Mandos visibles en el teléfono**: lanza ⚡ o 📍 desde el panel y mira la app: en el estado
      debe aparecer `último mando: burst OK` / `locate_now OK`.
- [ ] **Zonas seguras**: en `admin.html` → *Zonas seguras* → ➕ Añadir zona, pulsa **Usar última
      posición**, radio 200 m y guarda como "Casa". En la siguiente comprobación la zona debe decir
      `dentro`. Después aléjate más de 200 m y espera un envío: debe aparecer el aviso **salió de Casa**
      en *Avisos recientes* y el círculo ponerse ámbar. El primer dato tras crearla **no** avisa
      (solo fija el estado): eso es intencionado.
- [ ] **Avisos del teléfono**: pulsa **✅ Llegué bien** en la app → aparece en *Avisos recientes*.
      Pulsa **🆘 SOS** (confirma el diálogo) → aviso en rojo, y la notificación pasa a
      *Persecución activa · 2 s* durante 10 minutos.
- [ ] **Rangos rápidos**: *Hoy* / *Ayer* / *7 días* deben recargar la consulta y el resumen del tramo
      mostrar distancia, batería mín.–máx. y primer/último dato.
- [ ] **Estado del dispositivo**: abre la app en el teléfono (los datos viajan al abrirse) y mira la
      tarjeta *Estado del dispositivo* en `admin.html`: versión de Android y parche, bloqueo de
      pantalla, Play Protect, root, depuración USB y las listas de apps con **accesibilidad**,
      **lectura de notificaciones** y **administradores**. Con el teléfono normal debe decir
      `bloqueo: sí`, `root: no`, `depuración USB: no` y `ninguna` en las tres listas.
- [ ] **Control parental (lista de apps)**: en la tarjeta *Aplicaciones instaladas* del panel debe
      aparecer el número de apps y sus nombres (`Juegos`, `WhatsApp`…), con el buscador filtrando.
      Abre la app en el teléfono, desmarca *Compartir la lista de apps instaladas*, **Guarda** (pide
      PIN) y abre la app otra vez: el panel debe pasar a `no compartida`, no a un `0` falso.
- [ ] **Detección de manipulación** (lo importante si te lo desactivan): en el teléfono, quita el
      permiso de ubicación (o desactiva el modo antirrobo) y **abre la app** para que reporte al
      momento. En `admin.html` debe salir en la fila del dispositivo `⚠️ N problema(s)` y en la
      línea de avisos el motivo con `hace X` (p. ej. *permiso de ubicación revocado*).
      Restaura el permiso y el aviso desaparece en la siguiente comprobación (o al abrir la app).
- [ ] **Resistencia del sistema**: reinicia el móvil, actualiza el APK por encima y desliza la app
      fuera de *Recientes*: en los tres casos el rastreo debe volver solo. Comprueba también que
      al pulsar **Detener** (con PIN) **no** vuelve: parar es parar.
- [ ] Reiniciar el teléfono: el agente se relanza solo (`BootReceiver`) y
      la última conexión se actualiza en el panel.

---

## 6. AJUSTES finos y seguridad (recomendado tras verificar)

- [ ] **Rotar token** si lo compartiste por accidente: `admin.html → Dispositivos →
      ➕ Emparejar / rotar token` con el MISMO label → pega el nuevo uuid+token en la app.
- [ ] **Retención**: por defecto 7 días de posiciones y 30 días de comandos resueltos
      (cron diario). Cambiarla manteniendo la limpieza de comandos:
      ```sql
      -- p. ej. 30 días de posiciones:
      create or replace function public.prune_positions() returns void
      language plpgsql security definer set search_path = public, extensions as $$
      begin
        delete from public.positions where recorded_at < now() - interval '30 days';
        delete from public.device_commands
         where created_at < now() - interval '30 days' and status <> 'pending';
      end;
      $$;
      ```
- [ ] **Revocar un teléfono perdido**: `admin.html` → *Dispositivos* → 🚫. El hash del token
      se rota a un valor aleatorio: ese teléfono deja de poder enviar. Para volver a
      rastrearlo hay que emparejarlo de nuevo y reconfigurarlo.
- [ ] **Poner nombre, color y visibilidad a cada persona**: en *Dispositivos*, usa el color
      (el mismo en los dos mapas), 👁/🙈 (ocultarlo del panel público sin perder su historial
      en el admin) y **Renombrar** ("Móvil de Ana", "Tablet del salón"…).
- [ ] **Persecución (`burst`)**: ⚡ en el panel. Recuerda sus límites: 10 minutos por defecto
      (30 como máximo), vuelve sola al ritmo normal, se anuncia en la notificación y **no
      puede reactivar** un rastreo que hayas detenido en el teléfono.
- [ ] **Vista pública**: si prefieres que el mapa público no muestre nada sin login,
      elimina el grant a `anon` de `latest_positions` en el SQL y usa solo `admin.html`.
- [ ] **Varios dispositivos**: repetir el paso 1.3 con otro label/token y
      configurar cada teléfono con su propio uuid+token; el panel los lista solos.
- [ ] **Uso legítimo**: instala el agente solo en dispositivos propios o con el
      consentimiento informado de quien lo lleva. La app es visible por diseño.

---

## 7. PROBLEMAS frecuentes → solución

| Síntoma | Causa probable | Solución |
|---|---|---|
| Banner rojo "Falta configurar config.js" | `config.js` sin editar o sin subir | Pasos 2.2 y commit a GitHub |
| Badge **SIN CONEXIÓN** permanente | URL o anon key mal copiadas | Revisar paso 1.4; sin espacios ni comillas extra |
| Badge **SIN SEÑAL RECIENTE** | El agente no reporta | Revisar pasos 4.5–4.7 (permisos/exención) |
| "Credenciales invalidas" en el envío | UUID o token incorrectos, o dispositivo no emparejado | Repetir paso 1.3 y 4.2 |
| `function pair_device(...) does not exist` | SQL ejecutado parcialmente | Re-ejecutar `supabase-setup.sql` completo |
| `function digest(text, unknown) does not exist` al enviar posiciones | Funciones RPC antiguas con `search_path` sin el schema `extensions` (pgcrypto) | Re-ejecutar `supabase-setup.sql` COMPLETO (usa `set search_path = public, extensions`) |
| El panel no muestra historial | No hay sesión admin, o RLS lo bloquea | Iniciar sesión en `admin.html` (paso 1.5) |
| El servicio se apaga tras un rato | Optimización de batería del fabricante | Paso 4.6 + permitir autoinicio en Ajustes del fabricante |
| Los comandos quedan en `pending` y nunca pasan a `done` | El agente no está sondeando: servicio parado, o control remoto apagado en la app | Comprobar que el rastreo está activo y el ajuste **Control remoto** marcado (paso 4.3) |
| El panel admin dice *"Reejecuta supabase-setup.sql: falta la vista device_health"* | Backend antiguo: la columna `color`, `show_on_public` y la vista nueva no existen | Reejecutar `web/supabase-setup.sql` completo (es idempotente) |
| ⚡ responde `failed: el rastreo está detenido en el dispositivo` | Alguien detuvo el rastreo en ese teléfono | Iniciarlo a mano en la app: la persecución no resucita un rastreo detenido |
| Todos los puntos aparecen descartados en el rastro | `accuracy` peor que 150 m en una zona con mala señal | Normal en interiores/sin GPS; sube `maxAccuracyM` en `config.js` |
| El panel familiar marca a alguien como *sin posición* para siempre | Ese móvil está emparejado pero nunca ha enviado (sin permisos, sin datos, app sin iniciar) o el `uuid`/token no coinciden | Revisar en el teléfono: permiso de ubicación, **Iniciar rastreo** y que el uuid del panel sea el de la app |
| *Notificación discreta* marcada y la notificación sigue igual de visible | APK antiguo: el modo discreto no cambiaba de canal | Recompilar/instalar el APK nuevo (Actions → Android CI) |
| El panel **no** avisa de permisos revocados ni de antirrobo desactivado | Backend o APK antiguos: `device_checks` y `report_health` son nuevos | Reejecutar `supabase-setup.sql` completo + APK nuevo; luego abrir la app una vez |
| Las zonas seguras nunca avisan | La zona pertenece a **otro** dispositivo, o el primer fix solo fijó el estado | Comprueba que la zona es del dispositivo seleccionado y muévete > radio en un envío posterior |
| *Aplicaciones instaladas* queda a `0` o vacía | El APK no es el nuevo (no tiene el bloque `<queries>`), o en ese teléfono desactivaste *Compartir la lista de apps* | Recompilar el APK y revisar el interruptor en la app; el panel dirá `no compartida` si es lo segundo |
| Quiero **tiempo de uso** de cada app, no solo el nombre | No existe a propósito: eso describiría su día, no su lista | Para límites de tiempo, Google Family Link (ver §6 del README) |
| *Estado del dispositivo* dice "Sin datos todavía" | El APK no es el nuevo, o el SQL no se reejecutó (faltan las columnas de postura) | Reejecutar `supabase-setup.sql` completo + APK nuevo y abrir la app una vez |
| El rastreo **no** vuelve tras reiniciar o actualizar el APK | Autoinicio bloqueado por la capa del fabricante | Ajustes → Batería → permitir autoinicio para la app (paso 4.6) y exención de batería |
| 🔒 responde `failed: modo antirrobo no activo` | DeviceAdmin no activado en ese teléfono | Paso 4.8 |
| La acción **Detener** de la notificación no hace nada | Tienes un APK antiguo (el PendingIntent apuntaba a un receptor inexistente) | Recompila e instala el APK nuevo (Actions → Android CI) |
| `permission denied for function pair_device` al emparejar desde el panel | Se aplicó el endurecimiento de permisos de la v1.1 | Empareja con la sesión de admin iniciada en `admin.html` (no con la anon key por REST) |
| No llega nada tras reboot | Autoinicio bloqueado por capa del fabricante | Habilitar autoinicio para la app en Ajustes → Batería |
| CI Android en rojo | Versión de dependencia o toolchain | Abrir log del paso de build; ajustar versión; push |
| Latencia > 5 s en el mapa | Red del móvil o sondeo del panel | `pollMs` bajo; el agente envía igual cada 5 s |

---

## 8. MAPA del proyecto (qué es cada archivo)

El inventario completo —cada archivo, tabla, vista, función, política, comando, ajuste y permiso— está
en **[`docs/MAPA-RECURSOS.md`](docs/MAPA-RECURSOS.md)**, y se comprueba contra el código con
`pwsh -File scripts/check-docs.ps1` (también corre en la CI web). En corto:

```
web/
  index.html  app.js   → panel PÚBLICO: mapa FAMILIAR (todos a la vez, color y estado)
  admin.html  admin.js → panel ADMIN (login, historial, rastro, replay, gráficas, CSV/GPX, control)
  config.js            → ÚNICO archivo de configuración de la web
  supabase-setup.sql   → esquema completo del backend (ejecutar 1 vez)
android/
  .../sync/LocationService.kt   → núcleo: Foreground Service + FusedLocation
  .../sync/CommandChannel.kt    → control remoto (pull + lista blanca + ack)
  .../sync/TamperCheck.kt       → detección de manipulación + postura del dispositivo
  .../sync/InstalledApps.kt     → control parental: apps instaladas (solo nombres)
  .../security/PinStore.kt      → PIN con PBKDF2 + bloqueo por intentos
  .../admin/DeviceAdmin.kt      → antirrobo: DeviceAdminReceiver + bloqueo remoto
tools/simulate-agent.mjs        → simulador del agente para probar sin teléfono
```

Documentación: [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) (cómo funciona),
[`docs/API-BACKEND.md`](docs/API-BACKEND.md) (contrato del backend) y
[`docs/OPERACION.md`](docs/OPERACION.md) (uso diario).

---

## 9. PROBAR sin APK (simulador del agente)

Antes de instalar nada en el teléfono puedes llenar los paneles con datos reales
usando el simulador (Node 18+, imita el mismo RPC que el agente):

```bash
node tools/simulate-agent.mjs \
  --url https://TU-PROYECTO.supabase.co \
  --key TU_CLAVE_ANON \
  --device UUID_DEL_DISPOSITIVO \
  --token TU_TOKEN
```

- Dibuja un recorrido cuadrado de ~350 m a paso ligero, fix cada 5 s, lotes de 5.
- El marcador del panel público y el historial del admin se mueven en vivo.
- Parar con `Ctrl+C`. Para borrar los datos de prueba:
  `delete from positions where device_id = 'UUID';` (SQL Editor).

**Releases firmados:** al crear un tag (`git tag v1.0.0 && git push --tags`) el workflow
`release.yml` compila `assembleRelease` y adjunta el APK a un GitHub Release.
(Sin keystore genera `app-release-unsigned.apk`; para firmar, añade los secrets
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` y un paso de
signing — pídelo y lo agregamos.)

---

## 10. LÍMITES del plan gratuito (referencia)

- **Supabase free**: ~500 MB de BD y ~2 GB de ancho de banda/mes → holgado para
  1–3 dispositivos a 5 s con retención de 7 días (≈ 17 k filas/dispositivo/día,
  ~1–2 KB cada una). El cron `prune_positions` mantiene el tamaño plano.
- **GitHub Pages**: 100 GB/mes de tráfico y uso no comercial → de sobra para el panel.
- **GitHub Actions**: 2.000 min/mes en repos privados, ilimitado en públicos →
  cada build de APK tarda ~4–6 min.

---

✅ **Sistema completo cuando:**
`pair_device` devolvió un uuid · `config.js` tiene tus credenciales · Pages sirve el
panel · el APK está instalado con permisos y exención de batería · el badge del panel
dice **EN VIVO** con el marcador sobre la calle correcta.
