#!/usr/bin/env node
/* ============================================================================
 *  Smoke test del backend — se ejecuta en CI con cada push.
 *
 *  Verifica, contra el proyecto Supabase REAL:
 *    1. config.js es sintácticamente válido y tiene valores con sentido.
 *    2. La URL y la clave anon funcionan: GET /rest/v1/latest_positions → 200.
 *
 *  Origen de credenciales (en orden): variables de entorno SUPABASE_URL /
 *  SUPABASE_ANON_KEY (secrets de GitHub) o, si no existen, web/config.js.
 *
 *  Uso local:  node scripts/smoke-backend.mjs
 * ==========================================================================*/
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const fail = (msg) => { console.error('✗ ' + msg); process.exit(1); };

// ---- 1) config.js -----------------------------------------------------------
let cfg = {};
try {
  const src = readFileSync(join(root, 'web', 'config.js'), 'utf8');
  const sandbox = { window: {} };
  new Function('window', src)(sandbox);           // SyntaxError aquí = config roto
  cfg = sandbox.window.LOCATOR_CONFIG || {};
} catch (e) {
  fail('web/config.js no se puede evaluar: ' + e.message);
}

const url = process.env.SUPABASE_URL || cfg.supabaseUrl || '';
const key = process.env.SUPABASE_ANON_KEY || cfg.supabaseAnonKey || '';

if (!/^https:\/\/[a-z0-9]{20}\.supabase\.co$/.test(url)) {
  fail(`supabaseUrl con formato inesperado: "${url}"`);
}
if (!/^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(key)) {
  fail('supabaseAnonKey no parece un JWT válido');
}
for (const [k, v] of Object.entries({ pollMs: cfg.pollMs, mapZoom: cfg.mapZoom, staleAfterSec: cfg.staleAfterSec })) {
  if (typeof v !== 'number' || !Number.isFinite(v)) fail(`config.${k} debe ser numérico`);
}
if (!Array.isArray(cfg.mapCenter) || cfg.mapCenter.length !== 2) fail('config.mapCenter debe ser [lat, lon]');
console.log('✓ config.js válido');

// ---- 2) REST vivo -----------------------------------------------------------
const base = url.replace(/\/+$/, '');
const res = await fetch(`${base}/rest/v1/latest_positions?select=*&limit=1`, {
  headers: { apikey: key, Authorization: `Bearer ${key}` },
  signal: AbortSignal.timeout(15000)
});
if (!res.ok) fail(`REST /latest_positions respondió HTTP ${res.status}`);
const rows = await res.json();
if (!Array.isArray(rows)) fail('REST no devolvió un array');
console.log(`✓ Supabase accesible · latest_positions OK (${rows.length} fila(s) en la primera página)`);
console.log('SMOKE OK');
