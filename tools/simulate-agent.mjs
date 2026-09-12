#!/usr/bin/env node
/* ============================================================================
 *  Simulador del agente Android — herramientas de prueba (sin dependencias)
 *
 *  Imita EXACTAMENTE el payload de LocationService/SupabaseClient: lotes al
 *  RPC ingest_positions cada N segundos. Sirve para probar los paneles
 *  (publico y admin) ANTES de instalar el APK.
 *
 *  Uso (Node 18+):
 *    node tools/simulate-agent.mjs \
 *      --url https://abcdxyz.supabase.co \
 *      --key eyJhbGci... \
 *      --device UUID-DEL-DISPOSITIVO \
 *      --token TU-TOKEN-DE-EMPAREJAMIENTO \
 *      [--interval 5] [--lat 40.4168] [--lon -3.7038] [--laps 0]
 *
 *  0 vueltas = correr indefinidamente. Ctrl+C para parar.
 * ==========================================================================*/
import { setTimeout as sleep } from 'node:timers/promises';

// ----------------------------------------------------------------- args ---
const args = process.argv.slice(2);
function arg(name, def = undefined) {
  const i = args.indexOf('--' + name);
  if (i === -1 || i + 1 >= args.length) return def;
  return args[i + 1];
}
const CFG = {
  url: (arg('url') || '').replace(/\/+$/, ''),
  key: arg('key') || '',
  device: arg('device') || '',
  token: arg('token') || '',
  interval: Number(arg('interval', '5')),
  lat: Number(arg('lat', '40.4168')),
  lon: Number(arg('lon', '-3.7038')),
  laps: Number(arg('laps', '0'))
};

for (const [k, v] of Object.entries(CFG)) {
  if (!v && v !== 0) { console.error(`Falta --${k}`); process.exit(1); }
}
if (!CFG.url.startsWith('https://')) { console.error('--url debe ser https://...'); process.exit(1); }
if (CFG.interval < 1) CFG.interval = 1;

// ----------------------------------------------------------------- ruta ---
// Camino cuadrado de ~350 m alrededor del punto inicial, a paso ligero.
const STEP = 0.003;           // grados por segmento (~330 m)
const HOLD = 12;              // fixes por segmento
const route = [];
const corners = [[0, 0], [STEP, 0], [STEP, STEP], [0, STEP]];
for (let c = 0; c < corners.length; c++) {
  const [dx, dy] = corners[c];
  const [ex, ey] = corners[(c + 1) % corners.length];
  for (let i = 0; i < HOLD; i++) {
    route.push([CFG.lat + dx + (ex - dx) * (i / HOLD), CFG.lon + dy + (ey - dy) * (i / HOLD)]);
  }
}

// ----------------------------------------------------------------- estado --
let idx = 0, batch = [], sent = 0, fail = 0, lap = 0, battery = 92;
const BATCH_MAX = 5; // 5 fixes * 5 s = un lote por ~25 s (o menor si errors)

function nextFix() {
  const p = route[idx % route.length];
  idx++;
  if (idx % route.length === 0) { lap++; console.log(`  vuelta ${lap} completada`); }
  battery = Math.max(8, battery - 0.01);
  return {
    lat: p[0] + (Math.random() - 0.5) * 0.00004,
    lon: p[1] + (Math.random() - 0.5) * 0.00004,
    accuracy: 5 + Math.random() * 10,
    speed: 1.1 + Math.random() * 0.9,
    battery_pct: Math.round(battery),
    charging: false,
    source: Math.random() < 0.15 ? 'NETWORK' : 'FUSED',
    provider: 'fused',
    recorded_at: new Date().toISOString()
  };
}

async function flush() {
  if (!batch.length) return;
  const body = JSON.stringify({
    p_device_id: CFG.device,
    p_token: CFG.token,
    p_positions: batch
  });
  const n = batch.length;
  batch = [];
  try {
    const res = await fetch(`${CFG.url}/rest/v1/rpc/ingest_positions`, {
      method: 'POST',
      headers: {
        apikey: CFG.key,
        Authorization: `Bearer ${CFG.key}`,
        'Content-Type': 'application/json'
      },
      body
    });
    const text = await res.text();
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 160)}`);
    sent += n;
    console.log(`✓ lote de ${n} aceptado · total ${sent} · fallos ${fail}`);
  } catch (e) {
    fail++;
    console.error(`✗ envio fallido (${fail}): ${e.message}`);
  }
}

// ----------------------------------------------------------------- loop ----
console.log(`Simulador iniciado → ${CFG.url}`);
console.log(`  dispositivo: ${CFG.device.slice(0, 8)}… · fix cada ${CFG.interval}s · Ctrl+C para parar`);

let sinceFlush = 0;
process.on('SIGINT', async () => { await flush(); console.log(`\nTotal enviado: ${sent} (fallos ${fail})`); process.exit(0); });

while (CFG.laps === 0 || lap < CFG.laps) {
  batch.push(nextFix());
  sinceFlush++;
  if (sinceFlush >= BATCH_MAX) { sinceFlush = 0; await flush(); }
  await sleep(CFG.interval * 1000);
}
await flush();
console.log(`Simulación terminada · total ${sent} (fallos ${fail})`);
