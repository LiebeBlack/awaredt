# Publicación automática del APK

Con cada push a `main`, la CI compila, **firma** y publica el APK en el release
**«Última compilación»** del repositorio (y cada etiqueta `v1.x` genera su release
estable). Esta guía explica la firma y la configuración única que necesita.

## Cómo funciona la firma

- La CI genera un keystore con `keytool` **solo si no existe** y lo reutiliza
  (cache `agent-keystore-v1`) mientras exista.
- Android solo acepta instalar una actualización si el APK está firmado con la
  **misma clave** que el instalado. Por eso la clave debe **persistir entre builds**.
- La contraseña sale del secreto `ANDROID_KEYSTORE_PASSWORD`; si no existe, se usa
  una contraseña por defecto para que el pipeline funcione desde el minuto cero.
  **Cámbiala por un secreto** antes de dar acceso al APK a nadie de la familia.

## Configuración única (recomendada): secreto

1. En GitHub: **Settings → Secrets and variables → Actions → New repository secret**.
2. Nombre: `ANDROID_KEYSTORE_PASSWORD`. Valor: una contraseña larga y tuya.
3. Nada más: el siguiente push genera el keystore con esa contraseña y **mientras la
   cache viva**, todas las versiones firman igual.

> **Límite de la cache**: el keystore vive en la cache de Actions y se puede perder
> (purge manual, inactividad de 7 días). Si se pierde, Android exigirá
> **desinstalar** la app antes de instalar el APK nuevo (una sola vez).

## Configuración permanente (la de verdad): keystore en el repo, cifrado

Para que la clave nunca cambie ni se pierda:

1. Genera el keystore en tu PC (o cópialo de una ejecución de la CI que aparezca en
   el log de *Create signing keystore*):
   ```bash
   keytool -genkeypair -v -keystore agent.keystore -alias locator-agent \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -storepass TU_PASSWORD -keypass TU_PASSWORD \
     -dname "CN=Locator Agent, OU=Familia, O=Privado, L=Madrid, C=ES"
   ```
2. Cífralo y sube el fichero cifrado al repositorio (por ejemplo en
   `ci/keystore/agent.keystore.enc`):
   ```bash
   openssl enc -aes-256-cbc -pbkdf2 -salt -in agent.keystore \
     -out ci/keystore/agent.keystore.enc -pass pass:OTRA_PASSWORD_LARGA
   ```
3. Añade el secreto `KEYSTORE_ENC_PASSWORD` con `OTRA_PASSWORD_LARGA` y descomenta
   el paso «Decrypt keystore» del workflow si lo añades (o descífralo en un paso
   `run:` antes del build). Guarda `agent.keystore` (sin cifrar) en un sitio seguro
   fuera del repositorio: es LA clave de tu familia.

## Qué publica cada workflow

| Workflow | Cuándo | Qué publica |
|---|---|---|
| **Android CI** (`android.yml`) | cada push a `main` | release **«Última compilación»** con el APK firmado (`locator-agent-<fecha>-<n>.apk`), marcado *latest* |
| **Android Release** (`release.yml`) | cada etiqueta `v1.x` | release estable con notas generadas y el APK firmado |
| Ambos | PRs | solo compilan y suben artifact; **no** publican |

## Versionado

- Push continuo: `versionName = <AAAA.M.D>-<n>` (fecha UTC + contador de
  `android/app/ci-version-code`), `versionCode` incremental. Cada instalación sabe
  de qué día y de qué ejecución viene.
- Etiqueta: `versionName = <tag>` (p. ej. `v1.4.0`), `versionCode = run_number`.

## Instalación en el teléfono

1. Descarga el APK de **Releases → «Última compilación»**.
2. Ábrelo en el teléfono y acepta «instalar de fuentes desconocidas» la primera vez.
3. Las siguientes versiones se instalan **encima**, sin desinstalar, siempre que la
   clave no haya cambiado (ver arriba).

## Variables de entorno que entiende `build.gradle.kts`

| Variable | Efecto |
|---|---|
| `SIGNING_KEYSTORE_PATH` | ruta del keystore; si falta, el release sale sin firmar (build local) |
| `SIGNING_KEYSTORE_PASSWORD` | contraseña del almacén y de la clave |
| `SIGNING_KEY_ALIAS` | alias de la clave (por defecto `locator-agent`) |
| `VERSION_CODE` / `VERSION_NAME` | versionado inyectado por la CI |
