/* ============================================================================
 *  Panel de Localización — página pública (GitHub Pages)
 *  Mapa FAMILIAR: todos los dispositivos a la vez, cada uno con su color,
 *  su batería y su última señal. La ficha lateral muestra el detalle del
 *  miembro seleccionado (incluida la ruta de la sesión).
 *  Leaflet + Supabase REST. Sin build step. Config en config.js
 * ==========================================================================*/
(function () {
  'use strict';

  var qs = new URLSearchParams(location.search);
  var cfg = window.LOCATOR_CONFIG || {};
  var demoMode = qs.get('demo') === '1' || cfg.demo === true;

  var configured = demoMode || (
    typeof cfg.supabaseUrl === 'string' &&
    cfg.supabaseUrl.indexOf('TU-PROYECTO') === -1 &&
    typeof cfg.supabaseAnonKey === 'string' &&
    cfg.supabaseAnonKey.indexOf('PEGA-TU-CLAVE') === -1 &&
    cfg.supabaseAnonKey.length > 20
  );

  var $ = function (id) { return document.getElementById(id); };
  var els = {
    banner: $('banner'), demoNote: $('demoNote'), badge: $('connBadge'),
    clock: $('clock'), deviceSelect: $('deviceSelect'), familyList: $('familyList'),
    focusName: $('focusName'),
    battery: $('stBattery'), charging: $('stCharging'), lastSeen: $('stLastSeen'),
    trail: $('trailCount'), speed: $('stSpeed'), accuracy: $('stAccuracy'),
    source: $('stSource'), coords: $('stCoords'), follow: $('followBtn')
  };

  if (!configured) els.banner.classList.remove('hidden');
  if (demoMode) els.demoNote.classList.remove('hidden');

  var STALE_SEC = cfg.staleAfterSec || 120;
  var PALETTE = ['#38bdf8', '#f472b6', '#34d399', '#fbbf24',
                 '#a78bfa', '#fb7185', '#22d3ee', '#facc15'];

  // ---- Mapa ---------------------------------------------------------------
  var map = L.map('map', { zoomControl: true })
    .setView(cfg.mapCenter || [40.4168, -3.7038], cfg.mapZoom || 14);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap'
  }).addTo(map);

  var trailLine = L.polyline([], { color: '#38bdf8', weight: 3, opacity: .85 }).addTo(map);
  var markers = {};          // device_id -> { marker, color, fresh }
  var family = [];           // [{ id, label, color }] en orden de leyenda
  var latestRows = [];       // ultima foto por dispositivo
  var focusId = null;        // miembro cuyo detalle se muestra
  var trail = [];            // ruta de la sesion del miembro enfocado
  var following = false;
  var timer = null;

  // ---- Utilidades ---------------------------------------------------------
  function rgba(hex, alpha) {
    var m = /^#([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return 'rgba(56,189,248,' + alpha + ')';
    var n = parseInt(m[1], 16);
    return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + alpha + ')';
  }

  function colorOf(id) {
    for (var i = 0; i < family.length; i++) {
      if (family[i].id === id) return family[i].color;
    }
    return PALETTE[0];
  }

  function ageSec(iso) {
    if (!iso) return Infinity;
    return Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  }

  function isFresh(row) { return ageSec(row.recorded_at) < STALE_SEC; }

  function setBadge(state, text) {
    els.badge.dataset.state = state;
    els.badge.textContent = text;
  }

  function fmtSpeed(mps) {
    if (mps == null) return '—';
    var kmh = mps * 3.6;
    return kmh < 1 ? 'quieto' : kmh.toFixed(1) + ' km/h';
  }

  function fmtAgo(iso) {
    if (!iso) return 'nunca';
    var s = ageSec(iso);
    if (s < 60) return 'hace ' + Math.round(s) + ' s';
    if (s < 3600) return 'hace ' + Math.round(s / 60) + ' min';
    if (s < 86400) return 'hace ' + Math.round(s / 3600) + ' h';
    return 'hace ' + Math.round(s / 86400) + ' d';
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // ---- Marcadores ---------------------------------------------------------
  function markerIcon(color, fresh) {
    var pulse = fresh
      ? '<div class="marker-pulse" style="background:' + rgba(color, .55) + '"></div>'
      : '';
    return L.divIcon({
      className: '',
      html: '<div class="marker-wrap">' + pulse +
        '<div class="marker-dot" style="background:' + color + ';border-color:#fff;' +
        'box-shadow:0 0 8px ' + rgba(color, .9) + '"></div></div>',
      iconSize: [24, 24], iconAnchor: [12, 12]
    });
  }

  function popupHtml(row, color) {
    return '<div style="min-width:150px">' +
      '<div style="font-weight:700;color:' + color + '">' + escapeHtml(row.label || '—') + '</div>' +
      '<div style="font-size:11px;color:#64748b">' + fmtAgo(row.recorded_at) + '</div>' +
      '<div style="font-size:12px;margin-top:2px">' + row.lat.toFixed(6) + ', ' + row.lon.toFixed(6) + '</div>' +
      '<div style="font-size:12px">🔋 ' + (row.battery_pct != null ? row.battery_pct + '%' : '—') +
      (row.charging === true ? ' ⚡' : '') + ' · ' + fmtSpeed(row.speed) +
      ' · ±' + (row.accuracy != null ? Math.round(row.accuracy) : '—') + ' m</div>' +
      '</div>';
  }

  function upsertMarker(row) {
    // Sin fix conocido (movil apagado, sin cobertura, recien emparejado) no hay
    // marcador que poner: el directorio lo sigue listando, pero en el mapa no se
    // inventa una posicion.
    if (row.lat == null || row.lon == null) return;
    var color = colorOf(row.device_id);
    var fresh = isFresh(row);
    var ll = [row.lat, row.lon];
    var entry = markers[row.device_id];

    if (!entry) {
      entry = markers[row.device_id] = {
        marker: L.marker(ll, { icon: markerIcon(color, fresh) }).addTo(map)
      };
    } else {
      entry.marker.setLatLng(ll);
      if (entry.color !== color || entry.fresh !== fresh) {
        entry.marker.setIcon(markerIcon(color, fresh));
      }
    }
    entry.color = color;
    entry.fresh = fresh;
    entry.marker.bindPopup(popupHtml(row, color));
  }

  function pruneMarkers(rows) {
    var seen = {};
    // Solo cuentan los que tienen fix: un movil apagado pierde su marcador
    // (sigue en la leyenda con "sin posición"), pero no los demas.
    rows.forEach(function (r) { if (r.lat != null) seen[r.device_id] = true; });
    Object.keys(markers).forEach(function (id) {
      if (!seen[id]) { map.removeLayer(markers[id].marker); delete markers[id]; }
    });
  }

  // ---- Leyenda familiar ----------------------------------------------------
  function renderFamily(rows) {
    if (!els.familyList) return;
    if (!rows.length) {
      els.familyList.innerHTML = '<div class="text-xs text-slate-500">Nadie emparejado todavía.</div>';
      return;
    }
    els.familyList.innerHTML = '';
    rows.forEach(function (row) {
      var color = colorOf(row.device_id);
      var fresh = isFresh(row);
      var noFix = row.lat == null;
      var statusText = noFix ? 'sin posición' : fmtAgo(row.recorded_at);
      var statusClass = noFix ? 'text-slate-500' : (fresh ? 'text-emerald-300' : 'text-amber-400');

      var row_ = document.createElement('button');
      row_.type = 'button';
      row_.className = 'w-full flex items-center gap-2 text-left px-2 py-1.5 rounded-lg border ' +
        (row.device_id === focusId
          ? 'bg-slate-800 border-slate-600'
          : 'bg-slate-900 border-slate-800 hover:bg-slate-800');
      row_.innerHTML =
        '<span class="fam-dot" style="background:' + color + (noFix ? ';opacity:.35' : '') + '"></span>' +
        '<span class="text-sm truncate">' + escapeHtml(row.label || String(row.device_id).slice(0, 8)) + '</span>' +
        '<span class="ml-auto text-[11px] tabular-nums ' + statusClass + '">' + statusText + '</span>' +
        '<span class="text-[11px] text-slate-400 tabular-nums w-9 text-right">' +
        (row.battery_pct != null ? row.battery_pct + '%' : '—') + '</span>';
      row_.addEventListener('click', function () { focusDevice(row.device_id, true); });
      els.familyList.appendChild(row_);
    });
  }

  function fillDevices(rows) {
    // Solo se reconstruye si cambio el conjunto: evita parpadeos cada 5 s
    var ids = rows.map(function (r) { return r.device_id; }).join(',');
    if (!ids) return;
    if (els.deviceSelect.dataset.ids === ids) return;
    els.deviceSelect.dataset.ids = ids;
    els.deviceSelect.innerHTML = '';
    rows.forEach(function (r) {
      var o = document.createElement('option');
      o.value = r.device_id;
      o.textContent = r.label || r.device_id.slice(0, 8);
      els.deviceSelect.appendChild(o);
    });
    if (focusId) els.deviceSelect.value = focusId;
  }

  // ---- Detalle del miembro enfocado ---------------------------------------
  function renderFocus(row) {
    if (!row) return;
    if (row.lat == null || row.lon == null) {
      // Sin fix: se limpian las cifras del anterior, no se dejan colgando
      els.focusName.textContent = (row.label || '—') + ' (sin posición todavía)';
      els.battery.textContent = '—';
      els.charging.textContent = '—';
      els.lastSeen.textContent = row.recorded_at ? fmtAgo(row.recorded_at) : 'nunca';
      els.speed.textContent = '—';
      els.accuracy.textContent = '—';
      els.source.textContent = '—';
      els.source.dataset.src = '';
      els.coords.textContent = '—';
      trail = []; trailLine.setLatLngs([]);
      els.trail.textContent = '0';
      setBadge('stale', 'SIN POSICIÓN');
      return;
    }
    var latlng = [row.lat, row.lon];

    els.focusName.textContent = row.label || '—';
    els.battery.textContent = row.battery_pct != null ? row.battery_pct + '%' : '—';
    els.charging.textContent = row.charging === true ? '⚡ cargando'
      : (row.charging === false ? 'batería' : '—');
    els.lastSeen.textContent = fmtAgo(row.recorded_at);
    els.speed.textContent = fmtSpeed(row.speed);
    els.accuracy.textContent = row.accuracy != null ? '±' + Math.round(row.accuracy) + ' m' : '—';
    els.source.textContent = row.source || row.provider || '—';
    els.source.dataset.src = row.source || 'GPS';
    els.coords.textContent = row.lat.toFixed(6) + ', ' + row.lon.toFixed(6);

    var last = trail[trail.length - 1];
    if (!last || last[0] !== row.lat || last[1] !== row.lon) {
      trail.push(latlng);
      if (trail.length > 600) trail.shift();
      trailLine.setLatLngs(trail);
      trailLine.setStyle({ color: colorOf(row.device_id) });
    }
    els.trail.textContent = String(trail.length);

    if (following && markers[row.device_id]) {
      map.panTo(markers[row.device_id].marker.getLatLng(), { animate: true });
    }
  }

  function focusDevice(id, pan) {
    if (!id) return;
    focusId = id;
    trail = []; trailLine.setLatLngs([]);
    if (els.deviceSelect.value !== id) els.deviceSelect.value = id;
    var row = null;
    for (var i = 0; i < latestRows.length; i++) {
      if (latestRows[i].device_id === id) row = latestRows[i];
    }
    renderFamily(latestRows);
    if (row) {
      renderFocus(row);
      if (pan && markers[id]) map.panTo(markers[id].marker.getLatLng(), { animate: true });
    }
  }

  // ---- Datos --------------------------------------------------------------
  function rest(path) {
    var base = cfg.supabaseUrl.replace(/\/+$/, '');
    return fetch(base + path, {
      headers: {
        apikey: cfg.supabaseAnonKey,
        Authorization: 'Bearer ' + cfg.supabaseAnonKey
      }
    }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  /**
   * Cruza el directorio (todos los emparejados) con las ultimas posiciones:
   * asi la leyenda familiar muestra tambien a quien lleva horas sin dar senal.
   */
  function applyData(directory, positions) {
    var byId = {};
    (positions || []).forEach(function (p) { byId[p.device_id] = p; });

    var list = (directory || []).slice();
    if (!list.length) {
      // Backend antiguo (sin public_devices): se usa lo que haya en posiciones
      Object.keys(byId).forEach(function (id) {
        list.push({ id: id, label: byId[id].label, color: byId[id].color, last_seen: byId[id].recorded_at });
      });
    }
    list.sort(function (a, b) {
      return String(a.label || '').localeCompare(String(b.label || ''));
    });

    // Color estable por persona: el suyo si lo definiste, si no la paleta
    family = list.map(function (d, i) {
      return {
        id: d.id,
        label: d.label || String(d.id).slice(0, 8),
        color: d.color || PALETTE[i % PALETTE.length]
      };
    });

    latestRows = list.map(function (d) {
      var pos = byId[d.id];
      if (pos) {
        pos.last_seen = d.last_seen || pos.recorded_at;
        return pos;
      }
      return {
        device_id: d.id, label: d.label, color: d.color,
        lat: null, lon: null, recorded_at: d.last_seen,
        battery_pct: null, charging: null, speed: null, accuracy: null, source: null
      };
    });

    pruneMarkers(latestRows);
    latestRows.forEach(upsertMarker);
    fillDevices(latestRows);

    var focused = null;
    for (var i = 0; i < latestRows.length; i++) {
      if (latestRows[i].device_id === focusId) focused = latestRows[i];
    }
    if (!focused && latestRows.length) {
      // El enfocado desaparecio (p. ej. lo ocultaste con 👁): se pasa al primero
      focusId = latestRows[0].device_id;
      focused = latestRows[0];
      trail = [];
      trailLine.setLatLngs([]);
    }
    renderFamily(latestRows);
    if (focused) {
      renderFocus(focused);
      var noFix = focused.lat == null;
      setBadge(isFresh(focused) ? 'live' : 'stale',
        noFix ? 'SIN POSICIÓN'
          : (isFresh(focused) ? 'EN VIVO' : 'SIN SEÑAL RECIENTE'));
    } else {
      setBadge('stale', 'SIN DISPOSITIVOS');
    }
  }

  function poll() {
    if (demoMode) { demoStep(); return Promise.resolve(); }
    return Promise.all([
      rest('/rest/v1/public_devices?select=id,label,color,last_seen'),
      rest('/rest/v1/latest_positions?select=*')
    ])
      .then(function (res) {
        applyData(res[0], res[1]);
      })
      .catch(function () {
        // Si el directorio no existe (SQL sin actualizar), se sigue con posiciones
        return rest('/rest/v1/latest_positions?select=*')
          .then(function (rows) { applyData([], rows); })
          .catch(function () { setBadge('offline', 'SIN CONEXIÓN'); });
      });
  }

  // ---- Demo ---------------------------------------------------------------
  var demoIdx = 0, demoTimer = null;
  var demoBase = [
    [40.4168, -3.7038], [40.4172, -3.7045], [40.4178, -3.7052], [40.4185, -3.7058],
    [40.4192, -3.7061], [40.4199, -3.7060], [40.4205, -3.7055], [40.4210, -3.7048],
    [40.4214, -3.7040], [40.4216, -3.7031], [40.4215, -3.7022], [40.4211, -3.7014],
    [40.4205, -3.7009], [40.4198, -3.7007], [40.4191, -3.7010], [40.4185, -3.7016]
  ];
  var demoPeople = [
    { id: 'demo-1', label: 'Móvil de casa', color: PALETTE[2], dLat: 0, dLon: 0, bat: 82 },
    { id: 'demo-2', label: 'Móvil del trabajo', color: PALETTE[0], dLat: 0.0042, dLon: 0.0031, bat: 54 },
    { id: 'demo-3', label: 'Tablet del salón', color: PALETTE[3], dLat: -0.0028, dLon: 0.0047, bat: 96 }
  ];

  function demoStep() {
    var base = demoBase[demoIdx % demoBase.length];
    demoIdx++;
    var directory = demoPeople.map(function (p) {
      return { id: p.id, label: p.label, color: p.color, last_seen: new Date().toISOString() };
    });
    var rows = demoPeople.map(function (p, i) {
      var jitter = (Math.random() - .5) * 0.00008;
      return {
        device_id: p.id, label: p.label, color: p.color,
        lat: base[0] + p.dLat + jitter,
        lon: base[1] + p.dLon + jitter,
        accuracy: 6 + Math.random() * 8,
        speed: i === 2 ? 0 : 1.2 + Math.random() * 3,
        battery_pct: Math.max(5, p.bat - demoIdx),
        charging: i === 2,
        source: demoIdx % 3 === 0 ? 'NETWORK' : 'FUSED',
        recorded_at: new Date().toISOString()
      };
    });
    applyData(directory, rows);
  }

  // ---- Eventos ------------------------------------------------------------
  els.deviceSelect.addEventListener('change', function () {
    focusDevice(els.deviceSelect.value, true);
  });

  els.follow.addEventListener('click', function () {
    following = !following;
    els.follow.textContent = following ? 'Siguiendo ✔ (clic para soltar)' : 'Seguir marcador ▶';
    if (following && markers[focusId]) map.panTo(markers[focusId].marker.getLatLng());
  });

  setInterval(function () {
    var n = new Date();
    els.clock.textContent = n.toTimeString().slice(0, 8);
  }, 1000);

  // ---- Arranque -----------------------------------------------------------
  if (demoMode) {
    demoStep();
    demoTimer = setInterval(demoStep, 2500);
  } else if (configured) {
    poll().catch(function () { setBadge('offline', 'SIN CONEXIÓN'); });
    timer = setInterval(poll, cfg.pollMs || 5000);
  } else {
    setBadge('offline', 'SIN CONFIGURAR');
  }

  window.addEventListener('beforeunload', function () {
    clearInterval(timer);
    clearInterval(demoTimer);
  });
})();
