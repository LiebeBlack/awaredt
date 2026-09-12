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
     - Debe terminar sin errores. Crea: tablas `devices` y `positions`,
       vista `latest_positions`, políticas RLS, funciones
       `pair_device`, `ingest_positions`, `prune_positions` y el cron de retención.

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
     demo: false                                   // dejar en false
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
     - **Web CI** — sintaxis JS/SQL, sin placeholders en config.js y smoke del
       backend real (si defines los secrets `SUPABASE_URL` y
       `SUPABASE_ANON_KEY`; si no, valida config.js).
     - **Android CI** — APK en cada push (artifact `locator-agent-debug-apk`).
     - **Deploy panel** — publica `web/` en GitHub Pages automáticamente.

---

## 3. CREAR el APK del agente Android (dos caminos)

### 3.A — Compilar en GitHub (recomendado, sin instalar nada)

3.A.1 [ ] Hacer push del repo (incluye `.github/workflows/android.yml`).
3.A.2 [ ] Ir a **Actions → Android CI** → esperar el ✅ (JDK 17 + Gradle 8.9,
      `assembleDebug` + `lintDebug`).
3.A.3 [ ] Descargar el artifact **`locator-agent-debug-apk`**
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

4.4. [ ] Pulsar **Guardar configuración** (toast "Configuración guardada").

4.5. [ ] Pulsar **Iniciar rastreo** y conceder permisos cuando el sistema pregunte:
     - [ ] **Ubicación** (mientras se usa)
     - [ ] **"Permitir todo el tiempo"** (imprescindible para reportar con pantalla apagada;
           la app abre el diálogo para llevarte a Ajustes)
     - [ ] **Notificaciones** (Android 13+; necesaria para la notificación del servicio)

4.6. [ ] Pulsar **Optimizar batería (exención)** y aceptar el diálogo oficial del
      sistema → evita que el fabricante mate el servicio.

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
- [ ] Reiniciar el teléfono: el agente se relanza solo (`BootReceiver`) y
      la última conexión se actualiza en el panel.

---

## 6. AJUSTES finos y seguridad (recomendado tras verificar)

- [ ] **Rotar token** si lo compartiste por accidente: `admin.html → Dispositivos →
      ➕ Emparejar / rotar token` con el MISMO label → pega el nuevo uuid+token en la app.
- [ ] **Retención**: por defecto 7 días (cron diario). Cambiarla:
      ```sql
      -- p. ej. 30 días:
      create or replace function public.prune_positions() returns void
      language sql security definer set search_path = public, extensions as $$
        delete from public.positions where recorded_at < now() - interval '30 days';
      $$;
      ```
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
| No llega nada tras reboot | Autoinicio bloqueado por capa del fabricante | Habilitar autoinicio para la app en Ajustes → Batería |
| CI Android en rojo | Versión de dependencia o toolchain | Abrir log del paso de build; ajustar versión; push |
| Latencia > 5 s en el mapa | Red del móvil o sondeo del panel | `pollMs` bajo; el agente envía igual cada 5 s |

---

## 8. MAPA del proyecto (qué es cada archivo)

```
web/
  index.html  app.js   → panel PÚBLICO (mapa en vivo, sondeo 5 s, ?demo=1)
  admin.html  admin.js → panel ADMIN (login, historial, replay, gráficas, CSV/GPX, tokens)
  config.js            → ÚNICO archivo de configuración de la web
  supabase-setup.sql   → esquema completo del backend (ejecutar 1 vez)
android/
  app/build.gradle.kts → dependencias fijadas (Room+SQLCipher, Work, OkHttp, Play Services)
  .../sync/LocationService.kt   → núcleo: Foreground Service + FusedLocation
  .../sync/SyncManager.kt       → buffer cifrado + lotes + backoff + flush al volver la red
  .../sync/SupabaseClient.kt    → HTTP POST al RPC ingest_positions
  .../sync/{Boot,Watchdog}Receiver.kt → autoarranque y re-kick
  .../MainActivity.kt           → emparejamiento, permisos, estado
.github/workflows/
  android.yml → compila el APK en cada push (artifact descargable)
  web.yml     → valida JS y estructura de la web
scripts/
  serve.ps1   → servidor local de pruebas de la web (127.0.0.1:8765)
```

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
