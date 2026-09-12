// ============================================================================
//  CONFIGURACION DEL PANEL — datos del proyecto Supabase (obbehfdwuufydtzaqslb)
//  (Supabase Dashboard > Settings > API > Project URL + anon public key)
// ============================================================================
window.LOCATOR_CONFIG = {
  // Pega aqui la URL de tu proyecto, ej: "https://abcdefgh.supabase.co"
  supabaseUrl: "https://obbehfdwuufydtzaqslb.supabase.co",

  // Pega aqui la clave "anon public" (es publica; la seguridad la da RLS)
  supabaseAnonKey: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im9iYmVoZmR3dXVmeWR0emFxc2xiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkyMTA4MjcsImV4cCI6MjEwNDc4NjgyN30.uNEzfcuclT3fXd0Da8UFqXo2jm_w9Tz3uy4rctiYUSw",

  // Frecuencia de sondeo en milisegundos (el agente reporta cada 5 s)
  pollMs: 5000,

  // Centro inicial del mapa [lat, lon] y zoom (13 = calle/colonia)
  mapCenter: [40.4168, -3.7038],
  mapZoom: 14,

  // Segundos sin datos antes de marcar la conexion como "SIN SEÑAL RECIENTE"
  staleAfterSec: 120,

  // Modo demo: mapa con posicion simulada (para probar sin backend).
  // Tambien puedes forzarlo con ?demo=1 en la URL.
  demo: false,

  // --- Analisis del rastro (opcional; estos son los valores por defecto) -----
  maxKmh: 250,          // un salto mas rapido que esto es ruido de GPS, no un viaje
  maxAccuracyM: 150,    // fixes con precision peor que esto no se dibujan
  stopRadiusM: 75,      // radio (m) para considerar que sigue en el mismo sitio
  stopMin: 5,           // minutos minimos dentro del radio para contar una parada
  gapMin: 10,           // minutos de silencio que se marcan como hueco

  // --- Avisos de la columna Dispositivos ------------------------------------
  alertBatteryPct: 15,  // bateria por debajo de la cual avisa (y sin cargador)
  alertSilentMin: 360   // minutos sin reportar que se consideran "sin senal"
};
