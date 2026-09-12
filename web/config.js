// ============================================================================
//  CONFIGURACION DEL PANEL — pega aqui los datos de TU proyecto Supabase
//  (Supabase Dashboard > Settings > API > Project URL + anon public key)
// ============================================================================
window.LOCATOR_CONFIG = {
  // Pega aqui la URL de tu proyecto, ej: "https://abcdefgh.supabase.co"
  supabaseUrl: "https://TU-PROYECTO.supabase.co",

  // Pega aqui la clave "anon public" (es publica; la seguridad la da RLS)
  supabaseAnonKey: "PEGA-TU-CLAVE-ANON-PUBLICA",

  // Frecuencia de sondeo en milisegundos (el agente reporta cada 5 s)
  pollMs: 5000,

  // Centro inicial del mapa [lat, lon] y zoom (13 = calle/colonia)
  mapCenter: [40.4168, -3.7038],
  mapZoom: 14,

  // Segundos sin datos antes de marcar la conexion como "SIN SEÑAL RECIENTE"
  staleAfterSec: 120,

  // Modo demo: mapa con posicion simulada (para probar sin backend).
  // Tambien puedes forzarlo con ?demo=1 en la URL.
  demo: false
};
