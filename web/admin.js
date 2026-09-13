/* ============================================================================
 *  Panel de Localización — Administración (GitHub Pages)
 *  Auth + historial + reproducción + gráficas + exportación + dispositivos
 * ==========================================================================*/
(function () {
  'use strict';

  var cfg = window.LOCATOR_CONFIG || {};
  var configured = typeof cfg.supabaseUrl === 'string' &&
    cfg.supabaseUrl.indexOf('TU-PROYECTO') === -1 &&
    typeof cfg.supabaseAnonKey === 'string' &&
    cfg.supabaseAnonKey.indexOf('PEGA-TU-CLAVE') === -1 &&
    cfg.supabaseAnonKey.length > 20;

  var $ = function (id) { return document.getElementById(id); };
  var els = {
    loginView: $('loginView'), appView: $('appView'), banner: $('adminBanner'),
    form: $('loginForm'), email: $('loginEmail'), pass: $('loginPass'),
    loginError: $('loginError'), loginBtn: $('loginBtn'), logout: $('logoutBtn'),
    userBadge: $('userBadge'), conn: $('connBadge'),
    deviceSelect: $('deviceSelect'), dateFrom: $('dateFrom'), dateTo: $('dateTo'),
    queryBtn: $('queryBtn'), csvBtn: $('csvBtn'), gpxBtn: $('gpxBtn'),
    statCount: $('statCount'), statKm: $('statKm'), statDur: $('statDur'),
    historyBody: $('historyBody'), pageLabel: $('pageLabel'),
    prevPage: $('prevPage'), nextPage: $('nextPage'),
    replayBar: $('replayBar'), replayPlay: $('replayPlay'),
    replaySlider: $('replaySlider'), replayTime: $('replayTime'),
    batteryCanvas: $('batteryChart'), distanceCanvas: $('distanceChart'),
    deviceList: $('deviceList'), pairLabel: $('pairLabel'),
    pairToken: $('pairToken'), pairBtn: $('pairBtn'),
    commandLog: $('commandLog'), cmdRefresh: $('cmdRefresh'),
    smoothToggle: $('smoothToggle'), trailSummary: $('trailSummary'), stopList: $('stopList'),
    alertsBox: $('alertsBox'),
    rangeToday: $('rangeToday'), rangeYesterday: $('rangeYesterday'), range7d: $('range7d'),
    rangeInfo: $('rangeInfo'), fenceList: $('fenceList'), fenceLabel: $('fenceLabel'),
    fenceLat: $('fenceLat'), fenceLon: $('fenceLon'), fenceRadius: $('fenceRadius'),
    fenceNotify: $('fenceNotify'), fenceFromMap: $('fenceFromMap'),
    fenceFromLast: $('fenceFromLast'), fenceAdd: $('fenceAdd'),
    eventList: $('eventList'), eventsRefresh: $('eventsRefresh'),
    postureBox: $('postureBox'),
    appsBox: $('appsBox'), appsCount: $('appsCount'), appsFilter: $('appsFilter')
  };

  if (!configured) els.banner.classList.remove('hidden');

  var sb = configured ? supabase.createClient(cfg.supabaseUrl, cfg.supabaseAnonKey) : null;

  // ---- Estado -------------------------------------------------------------
  function num(v, fallback) {
    return (typeof v === 'number' && isFinite(v)) ? v : fallback;
  }

  var PAGE = 200;
  // Umbrales ajustables desde config.js sin tocar la logica:
  //   maxKmh, maxAccuracyM, stopRadiusM, stopMin, gapMin
  //   alertBatteryPct, alertSilentMin
  var MAX_KMH = num(cfg.maxKmh, 250);            // mas rapido que esto = ruido de GPS
  var MAX_ACCURACY_M = num(cfg.maxAccuracyM, 150); // fixes peores no aportan al rastro
  var STOP_RADIUS_M = num(cfg.stopRadiusM, 75);  // radio de "sigue en el mismo sitio"
  var STOP_MIN_MS = num(cfg.stopMin, 5) * 60 * 1000;  // minimo para contar parada
  var GAP_MS = num(cfg.gapMin, 10) * 60 * 1000;       // silencio = hueco de datos
  var ALERT_BATTERY = num(cfg.alertBatteryPct, 15);   // % de bateria que avisa
  var ALERT_SILENT_MS = num(cfg.alertSilentMin, 360) * 60 * 1000; // 6 h sin senal

  var latestByDevice = {};   // device_id -> ultima posicion (bateria, hora)
  var checksByDevice = {};   // device_id -> ultima comprobacion de manipulacion
  var fences = [];           // zonas seguras del dispositivo seleccionado
  var fenceCircles = {};     // id -> circulo (se crea despues del mapa)
  var page = 0, totalCount = 0;
  var rowsDesc = [];   // consulta actual, descendente (tabla)
  var route = [];      // misma consulta, ascendente y ya limpia (mapa/replay)
  var trailInfo = { raw: 0, kept: 0, discarded: 0, stops: [], gaps: [] };
  var devices = [];
  var batteryChart = null, distanceChart = null;
  var replayTimer = null, replayIdx = 0;

  // ---- Utilidades ---------------------------------------------------------
  function toIsoLocal(input) {
    if (!input) return null;
    var d = new Date(input);
    return isNaN(d) ? null : d.toISOString();
  }
  function fmtTime(iso) {
    var d = new Date(iso);
    return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2) + ':' + ('0' + d.getSeconds()).slice(-2);
  }
  function fmtDateTime(iso) {
    var d = new Date(iso);
    return d.toLocaleDateString() + ' ' + fmtTime(iso);
  }
  function haversineKm(a, b) {
    var R = 6371, toRad = Math.PI / 180;
    var dLat = (b[0] - a[0]) * toRad, dLon = (b[1] - a[1]) * toRad;
    var s = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(a[0] * toRad) * Math.cos(b[0] * toRad) *
      Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.sqrt(s));
  }
  function setConn(text, ok) {
    els.conn.textContent = text;
    els.conn.className = 'ml-auto text-xs font-bold px-2.5 py-1 rounded-full border ' +
      (ok ? 'bg-emerald-950 text-emerald-300 border-emerald-700'
          : 'bg-red-950 text-red-300 border-red-800');
  }
  function isoDuration(ms) {
    if (!ms || ms < 0) return '—';
    var m = Math.floor(ms / 60000), h = Math.floor(m / 60);
    if (h >= 24) return Math.floor(h / 24) + 'd ' + (h % 24) + 'h';
    if (h > 0) return h + 'h ' + (m % 60) + 'm';
    return m + ' min';
  }
  function fmtAgo(iso) {
    if (!iso) return 'nunca';
    var s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 60) return 'hace ' + Math.round(s) + ' s';
    if (s < 3600) return 'hace ' + Math.round(s / 60) + ' min';
    if (s < 86400) return 'hace ' + Math.round(s / 3600) + ' h';
    return 'hace ' + Math.round(s / 86400) + ' d';
  }
  function download(name, text, mime) {
    var blob = new Blob([text], { type: mime || 'text/plain' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 2000);
  }

  // ---- Mapa ---------------------------------------------------------------
  var map = L.map('map').setView(cfg.mapCenter || [40.4168, -3.7038], cfg.mapZoom || 13);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '&copy; OpenStreetMap'
  }).addTo(map);
  var routeLine = L.polyline([], { color: '#38bdf8', weight: 3, opacity: .8 }).addTo(map);
  var routeDots = L.layerGroup().addTo(map);
  // Capa de zonas seguras: se declara aqui porque necesita el mapa ya creado
  var fenceLayer = L.layerGroup().addTo(map);
  var replayMarker = null;

  var replayIcon = L.divIcon({
    className: '', html: '<div class="replay-dot"></div>',
    iconSize: [18, 18], iconAnchor: [9, 9]
  });

  function drawRoute() {
    // Suavizado SOLO para dibujar: los datos, el replay y las exportaciones
    // siguen usando los puntos originales.
    var line = (els.smoothToggle && els.smoothToggle.checked) ? smoothLine(route) : route;
    routeLine.setLatLngs(line);
    routeDots.clearLayers();
    var step = Math.max(1, Math.floor(route.length / 150));
    route.forEach(function (p, i) {
      if (i % step !== 0 && i !== route.length - 1) return;
      var c = speedColor(p[3] && p[3].speed);
      L.circleMarker(p, { radius: 3, weight: 1, color: c, fillColor: c, fillOpacity: .85 })
        .bindPopup('<b>' + fmtDateTime(p[2]) + '</b><br>' + p[0].toFixed(6) + ', ' + p[1].toFixed(6) +
          '<br>±' + (p[3] && p[3].accuracy != null ? Math.round(p[3].accuracy) : '—') + ' m · ' +
          fmtSpeedKmh(p[3] && p[3].speed))
        .addTo(routeDots);
    });
    if (route.length) map.fitBounds(routeLine.getBounds().pad(0.12));
  }

  /** Media movil (ventana 3) sobre la linea dibujada: quita el zigzag del GPS. */
  function smoothLine(points) {
    if (points.length < 3) return points.map(function (p) { return [p[0], p[1]]; });
    var out = [[points[0][0], points[0][1]]];
    for (var i = 1; i < points.length - 1; i++) {
      out.push([
        (points[i - 1][0] + points[i][0] * 2 + points[i + 1][0]) / 4,
        (points[i - 1][1] + points[i][1] * 2 + points[i + 1][1]) / 4
      ]);
    }
    out.push([points[points.length - 1][0], points[points.length - 1][1]]);
    return out;
  }

  /** Color del punto segun velocidad: quieto, a pie, urbano, rapido. */
  function speedColor(mps) {
    if (mps == null) return '#64748b';
    var kmh = mps * 3.6;
    if (kmh < 1) return '#94a3b8';
    if (kmh < 15) return '#34d399';
    if (kmh < 50) return '#fbbf24';
    return '#f87171';
  }

  function fmtSpeedKmh(mps) {
    if (mps == null) return '—';
    var kmh = mps * 3.6;
    return kmh < 1 ? 'quieto' : kmh.toFixed(1) + ' km/h';
  }

  /**
   * Limpia el rastro: fuera fixes imprecisos y "teletransportes".
   * No inventa datos: solo descarta lo que fisicamente no puede ser cierto.
   */
  function cleanTrail(points) {
    var out = [];
    for (var i = 0; i < points.length; i++) {
      var p = points[i];
      if (p.accuracy != null && p.accuracy > MAX_ACCURACY_M) continue;
      var prev = out[out.length - 1];
      if (prev) {
        var km = haversineKm([prev.lat, prev.lon], [p.lat, p.lon]);
        var hours = (new Date(p.recorded_at).getTime() - new Date(prev.recorded_at).getTime()) / 3600000;
        if (hours > 0 && km / hours > MAX_KMH) continue;
      }
      out.push(p);
    }
    return out;
  }

  /**
   * Detecta paradas (racimos de puntos dentro de STOP_RADIUS_M durante
   * STOP_MIN_MS) y huecos (silencio > GAP_MS). Un hueco corta la parada:
   * no se puede afirmar que estuvo quieto en un tramo sin datos.
   */
  function analyzeTrail(points) {
    var stops = [], gaps = [], cluster = null;

    function closeCluster(c) {
      if (!c) return;
      var ms = new Date(c.end).getTime() - new Date(c.start).getTime();
      if (ms >= STOP_MIN_MS) {
        stops.push({ lat: c.lat, lon: c.lon, start: c.start, end: c.end, ms: ms, n: c.n });
      }
    }

    for (var i = 0; i < points.length; i++) {
      var cur = points[i];

      if (i > 0) {
        var gapMs = new Date(cur.recorded_at).getTime() -
                    new Date(points[i - 1].recorded_at).getTime();
        if (gapMs > GAP_MS) {
          gaps.push({ from: points[i - 1].recorded_at, to: cur.recorded_at, ms: gapMs });
          closeCluster(cluster);
          cluster = null;
        }
      }

      if (!cluster) {
        cluster = { lat: cur.lat, lon: cur.lon, start: cur.recorded_at, end: cur.recorded_at, n: 1 };
        continue;
      }

      var distM = haversineKm([cluster.lat, cluster.lon], [cur.lat, cur.lon]) * 1000;
      if (distM <= STOP_RADIUS_M) {
        // Centroide incremental: el racimo sigue al punto medio real
        cluster.lat = (cluster.lat * cluster.n + cur.lat) / (cluster.n + 1);
        cluster.lon = (cluster.lon * cluster.n + cur.lon) / (cluster.n + 1);
        cluster.n++;
        cluster.end = cur.recorded_at;
      } else {
        closeCluster(cluster);
        cluster = { lat: cur.lat, lon: cur.lon, start: cur.recorded_at, end: cur.recorded_at, n: 1 };
      }
    }
    closeCluster(cluster);
    return { stops: stops, gaps: gaps };
  }

  function chip(label, value) {
    return '<span class="inline-block bg-slate-900 border border-slate-800 rounded px-2 py-0.5 mr-1 mb-1">' +
      '<span class="text-slate-500">' + label + ':</span> ' +
      '<span class="text-slate-200 font-semibold">' + escapeHtml(value) + '</span></span>';
  }

  /** Resumen del rastro + lista de paradas y huecos del tramo consultado. */
  function renderTrailReport() {
    var info = trailInfo;
    var spanMs = route.length > 1
      ? new Date(route[route.length - 1][2]).getTime() - new Date(route[0][2]).getTime() : 0;
    var stoppedMs = 0;
    info.stops.forEach(function (s) { stoppedMs += s.ms; });
    var movingMs = Math.max(0, spanMs - stoppedMs);

    var km = 0;
    for (var i = 1; i < route.length; i++) km += haversineKm(route[i - 1], route[i]);
    var batMin = null, batMax = null;
    rowsDesc.forEach(function (p) {
      if (p.battery_pct == null) return;
      if (batMin == null || p.battery_pct < batMin) batMin = p.battery_pct;
      if (batMax == null || p.battery_pct > batMax) batMax = p.battery_pct;
    });

    els.trailSummary.innerHTML =
      chip('distancia', km.toFixed(2) + ' km') +
      chip('batería', batMin == null ? '—'
        : (batMin === batMax ? batMin + '%' : batMin + '–' + batMax + '%')) +
      chip('primer dato', route.length ? fmtDateTime(route[0][2]) : '—') +
      chip('último dato', route.length ? fmtDateTime(route[route.length - 1][2]) : '—') +
      chip('usados', info.kept + (info.discarded ? ' / ' + info.raw : '')) +
      chip('descartados', String(info.discarded)) +
      chip('paradas', String(info.stops.length)) +
      chip('huecos', String(info.gaps.length)) +
      chip('en movimiento', isoDuration(movingMs)) +
      chip('parado', stoppedMs > 0 ? isoDuration(stoppedMs) : '0 min') +
      '<div class="text-[11px] text-slate-500 mt-1 leading-relaxed">' +
      '⚪ quieto · 🟢 a pie · 🟡 urbano · 🔴 rápido. Se descartan saltos de más de ' +
      MAX_KMH + ' km/h y fixes con precisión peor que ' + MAX_ACCURACY_M +
      ' m. El suavizado solo afecta a la línea dibujada; CSV, GPX y replay usan los puntos reales.' +
      '</div>';

    var items = [];
    info.gaps.forEach(function (g) {
      items.push('<div class="flex gap-2 items-baseline">' +
        '<span class="text-amber-400">hueco</span>' +
        '<span class="text-slate-400 truncate">' + fmtDateTime(g.from) + ' → ' + fmtDateTime(g.to) + '</span>' +
        '<span class="ml-auto text-slate-500">' + isoDuration(g.ms) + '</span></div>');
    });
    info.stops.slice().reverse().forEach(function (s) {
      items.push('<div class="flex gap-2 items-baseline">' +
        '<span class="text-sky-300">parada</span>' +
        '<span class="text-slate-400">' + fmtTime(s.start) + ' → ' + fmtTime(s.end) + '</span>' +
        '<span class="text-slate-500 truncate">' + s.lat.toFixed(5) + ', ' + s.lon.toFixed(5) + '</span>' +
        '<span class="ml-auto text-slate-400">' + isoDuration(s.ms) + '</span></div>');
    });
    els.stopList.innerHTML = items.length ? items.join('')
      : '<div class="text-slate-500">Sin paradas de más de 5 min ni huecos grandes en este tramo.</div>';
  }

  // ---- Auth ---------------------------------------------------------------
  function showLogin() {
    els.appView.classList.add('hidden');
    els.loginView.classList.remove('hidden');
  }
  function showApp(user) {
    els.loginView.classList.add('hidden');
    els.appView.classList.remove('hidden');
    els.userBadge.textContent = user ? user.email : '';
    initDefaults();
    loadDevices();
  }

  els.form.addEventListener('submit', function (ev) {
    ev.preventDefault();
    if (!configured) { els.loginError.textContent = 'Configura config.js primero.'; els.loginError.classList.remove('hidden'); return; }
    els.loginBtn.disabled = true;
    els.loginError.classList.add('hidden');
    sb.auth.signInWithPassword({ email: els.email.value.trim(), password: els.pass.value })
      .then(function (r) {
        if (r.error) throw r.error;
        els.pass.value = '';
      })
      .catch(function (e) {
        els.loginError.textContent = 'Error: ' + (e.message || 'credenciales inválidas');
        els.loginError.classList.remove('hidden');
      })
      .finally(function () { els.loginBtn.disabled = false; });
  });

  els.logout.addEventListener('click', function () { sb && sb.auth.signOut(); });

  if (configured) {
    sb.auth.onAuthStateChange(function (event, session) {
      if (event === 'SIGNED_IN' && session) showApp(session.user);
      if (event === 'SIGNED_OUT') showLogin();
    });
    sb.auth.getSession().then(function (r) {
      if (r.data && r.data.session) showApp(r.data.session.user);
      else showLogin();
    });
  } else {
    showLogin();
  }

  // ---- Dispositivos -------------------------------------------------------
  /** Ultima posicion por dispositivo: es lo que permite avisar de bateria baja. */
  function loadLatest() {
    return sb.from('latest_positions')
      .select('device_id,battery_pct,charging,recorded_at,lat,lon')
      .then(function (r) {
        latestByDevice = {};
        if (r.error) return;        // sin vista/permiso: se sigue sin bateria
        (r.data || []).forEach(function (p) { latestByDevice[p.device_id] = p; });
      });
  }

  function loadDevices() {
    return loadLatest().then(loadChecks).then(loadDevicesList);
  }

  /**
   * Ultima comprobacion de cada agente: permisos, antirrobo, notificaciones,
   * servicio. Es lo que convierte "me lo han desactivado" en un aviso con hora.
   */
  function loadChecks() {
    return sb.from('device_checks').select('*')
      .then(function (r) {
        checksByDevice = {};
        if (r.error) return;   // SQL sin actualizar: se sigue sin esta capa
        (r.data || []).forEach(function (c) { checksByDevice[c.device_id] = c; });
      });
  }

  function loadDevicesList() {
    // device_health: etiqueta, color, visibilidad, contadores y cola del dispositivo
    return sb.from('device_health')
      .select('id,label,color,show_on_public,last_seen,created_at,positions_count,pending_commands,last_command')
      .order('label')
      .then(function (r) {
        if (r.error) {
          // Instalacion antigua (sin la vista device_health): se sigue funcionando
          // con la tabla devices, sin contadores, hasta reejecutar el SQL.
          return sb.from('devices').select('id,label,last_seen,created_at').order('label')
            .then(function (legacy) {
              if (legacy.error) { setConn('ERROR: ' + legacy.error.message, false); return; }
              applyDevices(legacy.data);
              setConn('Reejecuta supabase-setup.sql: falta la vista device_health', false);
            });
        }
        applyDevices(r.data);
      });
  }

  function applyDevices(list) {
    devices = list || [];
    els.deviceSelect.innerHTML = '';
    devices.forEach(function (d) {
      var o = document.createElement('option');
      o.value = d.id;
      o.textContent = d.label;
      els.deviceSelect.appendChild(o);
    });
    renderDeviceList();
    renderAlerts();
    if (devices.length) query();
    else setConn('Sin dispositivos emparejados', false);
  }

  /**
   * Avisos de un vistazo: quien lleva horas sin dar senal, quien se queda sin
   * bateria y quien tiene mandos sin recoger. Sin correo ni push: solo el panel,
   * que no cuesta ni una llamada extra (los datos ya estan cargados).
   */
  function renderAlerts() {
    if (!els.alertsBox) return;
    var out = [];
    devices.forEach(function (d) {
      var label = escapeHtml(d.label || String(d.id).slice(0, 8));

      // Primero lo que ha dicho el propio agente (manipulacion)
      var check = checksByDevice[d.id];
      if (check && (check.alerts || []).length) {
        var marks = (check.alerts || []).join(', ');
        if (check.admin_removed) marks = 'DESACTIVARON EL MODO ANTIRROBO — ' + marks;
        out.push(label + ': ' + marks + ' (' + fmtAgo(check.checked_at) + ')');
      }
      // El silencio se mide con last_seen (lo ven todos los dispositivos, tambien
      // los ocultos); la bateria solo con la ultima posicion, que los ocultos no
      // publican: sin dato no se inventa aviso.
      if (!d.last_seen) {
        out.push(label + ': todavía sin posición');
      } else if (Date.now() - new Date(d.last_seen).getTime() > ALERT_SILENT_MS) {
        out.push(label + ': sin señal desde ' + fmtAgo(d.last_seen));
      }
      var latest = latestByDevice[d.id];
      if (latest && latest.battery_pct != null &&
          latest.battery_pct <= ALERT_BATTERY && latest.charging !== true) {
        out.push(label + ': batería ' + latest.battery_pct + '% y sin cargar');
      }
      if (d.pending_commands) {
        out.push(label + ': ' + d.pending_commands + ' mando(s) sin recoger (¿app cerrada?)');
      }
    });

    els.alertsBox.innerHTML = out.length
      ? '<span class="text-amber-400 font-semibold">⚠️ ' + out.length + ' aviso(s):</span> ' +
        out.join(' · ')
      : '<span class="text-emerald-400">✓ Todo en orden: todos reportan y con batería.</span>';
  }

  function renderDeviceList() {
    els.deviceList.innerHTML = '';
    devices.forEach(function (d) {
      var row = document.createElement('div');
      row.className = 'flex items-center gap-2 bg-slate-900 border border-slate-800 rounded-lg px-3 py-2';
      var seen = d.last_seen ? fmtDateTime(d.last_seen) : 'nunca';
      var latest = latestByDevice[d.id];
      var meta = d.id.slice(0, 8) + '… · visto ' + seen;
      if (latest) {
        if (latest.battery_pct != null) meta += ' · 🔋 ' + latest.battery_pct + '%';
        if (latest.charging === true) meta += ' ⚡';
      }
      var check = checksByDevice[d.id];
      if (check) {
        var issues = (check.alerts || []).length;
        meta += issues ? ' · ⚠️ ' + issues + ' problema(s)' : ' · ✓ sin problemas';
      }
      if (d.positions_count != null) meta += ' · ' + Number(d.positions_count).toLocaleString() + ' ptos';
      if (d.pending_commands) meta += ' · ' + d.pending_commands + ' cmd en cola';
      if (d.show_on_public === false) meta += ' · oculto en el panel público';
      row.innerHTML =
        '<div class="min-w-0"><div class="text-sm font-medium truncate">' + escapeHtml(d.label) + '</div>' +
        '<div class="text-[11px] text-slate-500 truncate">' + escapeHtml(meta) + '</div></div>';
      var actions = document.createElement('div');
      actions.className = 'ml-auto flex flex-wrap items-center justify-end gap-1';

      // Color de este miembro en los mapas
      var colorInput = document.createElement('input');
      colorInput.type = 'color';
      colorInput.value = d.color || '#38bdf8';
      colorInput.title = 'Color de este miembro en los mapas';
      colorInput.className = 'w-7 h-7 bg-slate-900 border border-slate-700 rounded cursor-pointer p-0';
      colorInput.addEventListener('change', function () {
        sb.from('devices').update({ color: colorInput.value }).eq('id', d.id)
          .then(function (r) {
            if (r.error) alert('Error: ' + r.error.message);
            else loadDevices();
          });
      });
      actions.appendChild(colorInput);

      // Visibilidad en el panel público (el admin lo sigue viendo siempre)
      var eye = document.createElement('button');
      eye.className = 'text-xs bg-slate-800 border border-slate-700 rounded px-2 py-1 hover:bg-slate-700';
      eye.textContent = d.show_on_public === false ? '🙈' : '👁';
      eye.title = d.show_on_public === false
        ? 'Oculto en el panel público (clic para mostrarlo)'
        : 'Visible en el panel público (clic para ocultarlo)';
      eye.addEventListener('click', function () {
        sb.from('devices').update({ show_on_public: d.show_on_public === false }).eq('id', d.id)
          .then(function (r) {
            if (r.error) alert('Error: ' + r.error.message);
            else loadDevices();
          });
      });
      actions.appendChild(eye);

      // Control remoto: el telefono recoge estos comandos en su proximo sondeo
      // (pull cada 60 s) y reporta el resultado en "Ultimos comandos".
      [
        { cmd: 'locate_now', label: '📍', title: 'Ubicar ahora (fix inmediato de alta precision)' },
        { cmd: 'lock', label: '🔒', title: 'Bloquear la pantalla (requiere modo antirrobo activo)' },
        { cmd: 'alarm', label: '🔔', title: 'Sonar la alarma del telefono (max. 2 min)' },
        { cmd: 'stop_alarm', label: '🔕', title: 'Detener la alarma' },
        {
          cmd: 'burst', label: '⚡', args: { minutes: 10, interval_sec: 3 },
          title: 'Persecución: cada 3 s durante 10 min (temporal y visible)'
        },
        { cmd: 'stop_burst', label: '🔋', title: 'Detener la persecución' },
        { cmd: 'stop_tracking', label: '⏹', title: 'Detener el rastreo en ese telefono' }
      ].forEach(function (a) {
        var b = document.createElement('button');
        b.className = 'text-xs bg-slate-800 border border-slate-700 rounded px-2 py-1 hover:bg-slate-700';
        b.textContent = a.label;
        b.title = a.title;
        b.addEventListener('click', function () { sendCommand(d.id, a.cmd, a.args); });
        actions.appendChild(b);
      });

      var rename = document.createElement('button');
      rename.className = 'text-xs bg-slate-800 border border-slate-700 rounded px-2 py-1 hover:bg-slate-700';
      rename.textContent = 'Renombrar';
      rename.addEventListener('click', function () {
        var nuevo = prompt('Nueva etiqueta para "' + d.label + '":', d.label);
        if (!nuevo || nuevo === d.label) return;
        sb.from('devices').update({ label: nuevo.trim() }).eq('id', d.id)
          .then(function (r) {
            if (r.error) alert('Error: ' + r.error.message);
            else loadDevices();
          });
      });
      actions.appendChild(rename);

      var revoke = document.createElement('button');
      revoke.className = 'text-xs bg-red-950 border border-red-800 text-red-300 rounded px-2 py-1 hover:bg-red-900';
      revoke.textContent = '🚫';
      revoke.title = 'Revocar el token de este dispositivo';
      revoke.addEventListener('click', function () { revokeDevice(d.id, d.label); });
      actions.appendChild(revoke);

      row.appendChild(actions);
      els.deviceList.appendChild(row);
    });
  }

  // ---- Control remoto -----------------------------------------------------
  function sendCommand(deviceId, command, args) {
    if (!deviceId) return;
    if (command === 'stop_tracking' &&
        !confirm('¿Detener el rastreo en ese teléfono?\n\nSeguirá detenido hasta que lo inicies a mano en el dispositivo.')) return;
    if (command === 'burst' &&
        !confirm('¿Persecución agresiva?\n\nReportará cada 3 s durante 10 minutos con alta precisión ' +
          '(gasta más batería) y después volverá solo a su ritmo normal. La notificación del teléfono ' +
          'lo mostrará como "Persecución activa".')) return;

    sb.rpc('enqueue_command', { p_device_id: deviceId, p_command: command, p_args: args || {} })
      .then(function (r) {
        if (r.error) { alert('No se pudo encolar el comando: ' + r.error.message); return; }
        setConn('Comando "' + command + '" en cola', true);
        loadCommands(deviceId);
      })
      .catch(function (e) { alert('Error de red: ' + (e.message || e)); });
  }

  function revokeDevice(deviceId, label) {
    if (!confirm('Revocar "' + label + '"?\n\nEl teléfono dejará de poder enviar posiciones hasta que lo ' +
      'vuelvas a emparejar. El historial se conserva.')) return;

    sb.rpc('revoke_device', { p_device_id: deviceId, p_purge: false })
      .then(function (r) {
        if (r.error) { alert('Error al revocar: ' + r.error.message); return; }
        alert('Token revocado.\n\nPara volver a rastrear: empareja de nuevo (➕ Emparejar / rotar token) ' +
          'y reconfigura el teléfono con el uuid y el token nuevos.');
        loadDevices();
      })
      .catch(function (e) { alert('Error de red: ' + (e.message || e)); });
  }

  /** Cola de comandos del dispositivo seleccionado, con su resultado real. */
  function loadCommands(deviceId) {
    if (!deviceId) { els.commandLog.textContent = '—'; return; }
    sb.from('device_commands')
      .select('id,command,status,result,created_at')
      .eq('device_id', deviceId)
      .order('created_at', { ascending: false })
      .limit(8)
      .then(function (r) {
        if (r.error) { els.commandLog.textContent = 'Error: ' + r.error.message; return; }
        var rows = r.data || [];
        if (!rows.length) { els.commandLog.textContent = 'Sin comandos todavía.'; return; }
        els.commandLog.innerHTML = rows.map(function (c) {
          var color = c.status === 'done' ? 'text-emerald-300'
            : c.status === 'failed' ? 'text-red-300'
              : c.status === 'delivered' ? 'text-sky-300' : 'text-slate-400';
          return '<div class="flex gap-2 items-baseline">' +
            '<span class="' + color + '">' + escapeHtml(c.status) + '</span>' +
            '<span class="text-slate-300">' + escapeHtml(c.command) + '</span>' +
            '<span class="text-slate-500 truncate">' + escapeHtml(c.result || '') + '</span>' +
            '<span class="ml-auto text-slate-600">' + fmtTime(c.created_at) + '</span></div>';
        }).join('');
      });
  }

  els.pairBtn.addEventListener('click', function () {
    var label = els.pairLabel.value.trim();
    var token = els.pairToken.value.trim();
    if (!label || token.length < 16) { alert('Etiqueta obligatoria y token de al menos 16 caracteres.'); return; }
    sb.rpc('pair_device', { p_label: label, p_token: token })
      .then(function (r) {
        if (r.error) { alert('Error: ' + r.error.message); return; }
        els.pairLabel.value = ''; els.pairToken.value = '';
        var cfg = window.LOCATOR_CONFIG || {};
        var datos = [
          'URL: ' + (cfg.supabaseUrl || ''),
          'CLAVE: ' + (cfg.supabaseAnonKey || ''),
          'UUID: ' + r.data,
          'TOKEN: ' + token
        ].join('\n');
        alert('Dispositivo emparejado.\n\nCopia este mensaje completo:\n\n' + datos + '\n\n…y usa «Pegar credenciales» en la app Android (Emparejamiento): rellena los 4 campos sola.');
        loadDevices();
      })
      .catch(function (e) { alert('Error de red al emparejar: ' + (e.message || e)); });
  });

  // ---- Consulta de historial ----------------------------------------------
  function initDefaults() {
    var now = new Date(), ago = new Date(now.getTime() - 24 * 3600 * 1000);
    function toInput(d) {
      return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2) +
        'T' + ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
    }
    if (!els.dateFrom.value) els.dateFrom.value = toInput(ago);
    if (!els.dateTo.value) els.dateTo.value = toInput(now);
  }

  function query() {
    var deviceId = els.deviceSelect.value;
    if (!deviceId) return;
    loadCommands(deviceId);
    var fromIso = toIsoLocal(els.dateFrom.value) || new Date(Date.now() - 864e5).toISOString();
    var toIso = toIsoLocal(els.dateTo.value) || new Date().toISOString();

    setConn('Consultando…', true);
    sb.from('positions')
      .select('id,device_id,lat,lon,accuracy,speed,battery_pct,charging,source,provider,recorded_at', { count: 'exact' })
      .eq('device_id', deviceId)
      .gte('recorded_at', fromIso)
      .lte('recorded_at', toIso)
      .order('recorded_at', { ascending: false })
      .range(page * PAGE, page * PAGE + PAGE - 1)
      .then(function (r) {
        if (r.error) { setConn('ERROR: ' + r.error.message, false); return; }
        totalCount = r.count || 0;
        rowsDesc = r.data || [];

        // Rastro: se limpia el ruido y se analiza antes de dibujar
        var raw = rowsDesc.slice().reverse();
        var kept = cleanTrail(raw);
        var analysis = analyzeTrail(kept);
        trailInfo = {
          raw: raw.length,
          kept: kept.length,
          discarded: raw.length - kept.length,
          stops: analysis.stops,
          gaps: analysis.gaps
        };
        route = kept.map(function (p) { return [p.lat, p.lon, p.recorded_at, p]; });

        setConn('OK · ' + totalCount + ' puntos' +
          (trailInfo.discarded ? ' · ' + trailInfo.discarded + ' descartados' : ''), true);
        renderResults();
      });
  }

  function renderResults() {
    // Tabla
    els.historyBody.innerHTML = '';
    rowsDesc.forEach(function (p) {
      var tr = document.createElement('tr');
      tr.className = 'hover:bg-slate-800/60';
      tr.innerHTML =
        '<td class="px-2 py-1.5">' + fmtTime(p.recorded_at) + '</td>' +
        '<td class="px-2 py-1.5">' + p.lat.toFixed(5) + ', ' + p.lon.toFixed(5) + '</td>' +
        '<td class="px-2 py-1.5">' + (p.battery_pct != null ? p.battery_pct + '%' : '—') + '</td>' +
        '<td class="px-2 py-1.5">' + (p.accuracy != null ? Math.round(p.accuracy) : '—') + '</td>' +
        '<td class="px-2 py-1.5">' + (p.source || p.provider || '—') + '</td>';
      var td = document.createElement('td');
      td.className = 'px-2 py-1.5';
      var go = document.createElement('button');
      go.className = 'text-sky-400 hover:text-sky-300';
      go.textContent = '📍';
      go.title = 'Ver en el mapa';
      go.addEventListener('click', function () {
        map.flyTo([p.lat, p.lon], Math.max(map.getZoom(), 17));
        L.popup().setLatLng([p.lat, p.lon])
          .setContent('<b>' + fmtDateTime(p.recorded_at) + '</b><br>Batería: ' +
            (p.battery_pct != null ? p.battery_pct + '%' : '—'))
          .openOn(map);
      });
      td.appendChild(go);
      tr.appendChild(td);
      els.historyBody.appendChild(tr);
    });

    els.pageLabel.textContent = (page + 1) + ' / ' + Math.max(1, Math.ceil(totalCount / PAGE));
    els.prevPage.disabled = page === 0;
    els.nextPage.disabled = (page + 1) * PAGE >= totalCount;
    els.prevPage.classList.toggle('opacity-40', els.prevPage.disabled);
    els.nextPage.classList.toggle('opacity-40', els.nextPage.disabled);

    // Stats + ruta + análisis + gráficas + replay
    var km = 0;
    for (var i = 1; i < route.length; i++) km += haversineKm(route[i - 1], route[i]);
    els.statCount.textContent = totalCount.toLocaleString();
    els.statKm.textContent = km.toFixed(2) + ' km';
    els.statDur.textContent = route.length > 1
      ? isoDuration(new Date(route[route.length - 1][2]) - new Date(route[0][2])) : '—';

    renderTrailReport();
    renderPosture();
    renderApps();
    loadFences();
    loadEvents();
    drawRoute();
    drawCharts();
    setupReplay();
  }

  if (els.smoothToggle) els.smoothToggle.addEventListener('change', function () { drawRoute(); });
  // ---- Rangos rapidos ------------------------------------------------------
  function localInput(d) {
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2) +
      'T' + ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
  }
  function setRange(from, to, label) {
    els.dateFrom.value = localInput(from);
    els.dateTo.value = localInput(to);
    if (els.rangeInfo) els.rangeInfo.textContent = label;
    page = 0;
    query();
  }
  if (els.rangeToday) {
    els.rangeToday.addEventListener('click', function () {
      var now = new Date(), start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      setRange(start, now, 'hoy');
    });
    els.rangeYesterday.addEventListener('click', function () {
      var now = new Date();
      var start = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
      setRange(start, new Date(now.getFullYear(), now.getMonth(), now.getDate()), 'ayer');
    });
    els.range7d.addEventListener('click', function () {
      var now = new Date();
      setRange(new Date(now.getTime() - 7 * 864e5), now, 'últimos 7 días');
    });
    els.fenceAdd.addEventListener('click', addFence);
    els.fenceFromMap.addEventListener('click', function () {
      var c = map.getCenter();
      els.fenceLat.value = c.lat.toFixed(6);
      els.fenceLon.value = c.lng.toFixed(6);
    });
    els.fenceFromLast.addEventListener('click', function () {
      var id = els.deviceSelect.value;
      for (var i = 0; i < rowsDesc.length; i++) {
        if (rowsDesc[i].device_id === id) {
          els.fenceLat.value = rowsDesc[i].lat.toFixed(6);
          els.fenceLon.value = rowsDesc[i].lon.toFixed(6);
          return;
        }
      }
      alert('No hay posiciones en la consulta actual para ese dispositivo. Pulsa Consultar primero.');
    });
    if (els.eventsRefresh) els.eventsRefresh.addEventListener('click', function () { loadEvents(); });
    if (els.appsFilter) els.appsFilter.addEventListener('input', function () {
      appFilter = els.appsFilter.value || '';
      renderApps();
    });
  }

  els.cmdRefresh.addEventListener('click', function () { loadCommands(els.deviceSelect.value); });
  els.queryBtn.addEventListener('click', function () { page = 0; query(); });
  els.deviceSelect.addEventListener('change', function () { page = 0; query(); });
  els.prevPage.addEventListener('click', function () { if (page > 0) { page--; query(); } });
  els.nextPage.addEventListener('click', function () { if ((page + 1) * PAGE < totalCount) { page++; query(); } });

  /**
   * Postura de seguridad del dispositivo seleccionado: configuracion y accesos
   * peligrosos. Es informacion del TELEFONO, no de la vida de quien lo lleva.
   * La lista de apps instaladas va aparte (renderApps) y SOLO con nombres:
   * sin uso, sin horarios y desactivable en el telefono (ver README 6).
   */
  function renderPosture() {
    if (!els.postureBox) return;
    var check = checksByDevice[els.deviceSelect.value];
    if (!check) {
      els.postureBox.textContent =
        'Sin datos todavía. La app los envía al abrirse, al arrancar el servicio y cada 6 h.';
      return;
    }

    function pkgList(list, cls) {
      if (!list || !list.length) return '<span class="text-slate-500">ninguna</span>';
      return list.map(function (p) {
        return '<span class="' + cls + '">' + escapeHtml(p) + '</span>';
      }).join(', ');
    }

    els.postureBox.innerHTML =
      chip('Android', check.android_version || '—') +
      chip('parche', check.security_patch || '—') +
      chip('bloqueo', check.device_secure === false ? 'SIN PIN' : (check.device_secure === true ? 'sí' : '—')) +
      chip('Play Protect', check.play_protect === false ? 'NO' : (check.play_protect === true ? 'sí' : '—')) +
      chip('root', check.rooted === true ? 'SÍ' : 'no') +
      chip('opciones desarrollo', check.developer_options === true ? 'activadas' : 'no') +
      chip('depuración USB', check.usb_debugging === true ? 'ACTIVADA' : 'no') +
      chip('origen', check.install_source || '—') +
      '<div class="mt-2 space-y-0.5">' +
      '<div>Accesibilidad (keyloggers): ' + pkgList(check.accessibility_apps, 'text-amber-300') + '</div>' +
      '<div>Leen notificaciones: ' + pkgList(check.notification_listeners, 'text-amber-300') + '</div>' +
      '<div>Administradores del dispositivo: ' + pkgList(check.device_admins, 'text-slate-300') + '</div>' +
      '</div>' +
      '<div class="text-slate-500 mt-1">Comprobado ' + fmtAgo(check.checked_at) + '</div>';
  }

  /**
   * Control parental: lista de apps instaladas, SOLO NOMBRES.
   * No hay tiempos, horarios ni frecuencia de uso en ningun punto del sistema,
   * y si el telefono apago el envio se dice tal cual en vez de inventar un cero.
   */
  var appFilter = '';
  function renderApps() {
    if (!els.appsBox) return;
    var check = checksByDevice[els.deviceSelect.value];
    if (!check) {
      els.appsCount.textContent = '—';
      els.appsBox.innerHTML = '<span class="text-slate-500">Sin datos todavía.</span>';
      return;
    }
    if (check.app_list_shared === false) {
      var old = (check.installed_count === null || check.installed_count === undefined);
      els.appsCount.textContent = 'no compartida';
      els.appsBox.innerHTML = old
        ? '<span class="text-slate-500">Sin datos de la lista de apps: o el APK es anterior a esta ' +
          'función, o está desactivado el interruptor «Compartir la lista de apps» en el teléfono.</span>'
        : '<span class="text-slate-500">Este teléfono no comparte la lista de apps (interruptor ' +
          'desactivado en la app). El resto del estado del dispositivo sí llega.</span>';
      return;
    }
    var apps = check.installed_apps || [];
    var total = (check.installed_count === null || check.installed_count === undefined)
      ? apps.length : check.installed_count;
    if (!apps.length) {
      els.appsCount.textContent = '0';
      els.appsBox.innerHTML = '<span class="text-slate-500">Sin apps que mostrar.</span>';
      return;
    }

    var q = (appFilter || '').trim().toLowerCase();
    var shown = !q ? apps : apps.filter(function (a) {
      var label = String((a && a.n) || '').toLowerCase();
      var pkg = String((a && a.p) || '').toLowerCase();
      return label.indexOf(q) !== -1 || pkg.indexOf(q) !== -1;
    });

    els.appsCount.textContent = total + ' app(s)' +
      (q ? ' · ' + shown.length + ' coinciden' : '') +
      (total > apps.length ? ' · se envían las primeras ' + apps.length : '');

    if (!shown.length) {
      els.appsBox.innerHTML = '<span class="text-slate-500">Ninguna app coincide con «' +
        escapeHtml(appFilter.trim()) + '».</span>';
      return;
    }

    els.appsBox.innerHTML = '<div class="flex flex-wrap gap-1">' +
      shown.map(function (a) {
        var label = escapeHtml(String((a && a.n) || '(sin nombre)'));
        var pkg = escapeHtml(String((a && a.p) || ''));
        return '<span class="inline-flex items-center gap-1 bg-slate-800/70 border border-slate-700 ' +
          'rounded px-1.5 py-0.5 max-w-full" title="' + pkg + '">' + label +
          '<span class="text-slate-500 font-mono text-[10px] truncate max-w-[7rem]">' + pkg + '</span></span>';
      }).join('') + '</div>';
  }

  // ---- Zonas seguras (geovallas, control parental) -------------------------
  function loadFences() {
    var deviceId = els.deviceSelect.value;
    fenceLayer.clearLayers();
    fenceCircles = {};
    if (!deviceId) { fences = []; renderFences(); return; }
    sb.from('geofences')
      .select('id,label,lat,lon,radius_m,notify_on,inside,last_event_at')
      .eq('device_id', deviceId)
      .order('label')
      .then(function (r) {
        fences = r.error ? [] : (r.data || []);
        renderFences();
        drawFences();
      });
  }

  function drawFences() {
    fenceLayer.clearLayers();
    fenceCircles = {};
    fences.forEach(function (f) {
      var color = f.inside === false ? '#fbbf24' : (f.inside === true ? '#34d399' : '#64748b');
      var when = f.notify_on === 'both' ? 'entrar y salir'
        : (f.notify_on === 'enter' ? 'solo entrar' : 'solo salir');
      var state = f.inside === true ? 'dentro' : (f.inside === false ? 'fuera' : 'sin datos todavía');
      var c = L.circle([f.lat, f.lon], {
        radius: f.radius_m, color: color, weight: 2, fillColor: color, fillOpacity: .10
      });
      c.bindPopup('<b>' + escapeHtml(f.label) + '</b><br>' + f.radius_m + ' m · ' + when +
        '<br>Ahora: ' + state);
      c.addTo(fenceLayer);
      fenceCircles[f.id] = c;
    });
  }

  function renderFences() {
    if (!els.fenceList) return;
    if (!fences.length) {
      els.fenceList.innerHTML = '<div class="text-slate-500">Sin zonas. Añade una (Casa, Colegio…) y recibirás aviso de entrada y salida.</div>';
      return;
    }
    els.fenceList.innerHTML = '';
    fences.forEach(function (f) {
      var state = f.inside === true ? '<span class="text-emerald-300">dentro</span>'
        : f.inside === false ? '<span class="text-amber-400">fuera</span>'
          : '<span class="text-slate-500">sin datos</span>';
      var row = document.createElement('div');
      row.className = 'flex items-center gap-2 bg-slate-900 border border-slate-800 rounded-lg px-2 py-1';
      row.innerHTML = '<span class="truncate">' + escapeHtml(f.label) + '</span>' +
        '<span class="text-slate-500">' + f.radius_m + ' m</span>' +
        '<span class="ml-auto">' + state + '</span>';
      var del = document.createElement('button');
      del.className = 'text-slate-400 hover:text-red-300';
      del.textContent = '🗑';
      del.title = 'Borrar zona';
      del.addEventListener('click', function () {
        if (!confirm('¿Borrar la zona "' + f.label + '"? Los avisos ya registrados se conservan.')) return;
        sb.from('geofences').delete().eq('id', f.id).then(function (r) {
          if (r.error) { alert('Error: ' + r.error.message); return; }
          loadFences();
        });
      });
      row.appendChild(del);
      els.fenceList.appendChild(row);
    });
  }

  function addFence() {
    var deviceId = els.deviceSelect.value;
    if (!deviceId) { alert('Elige un dispositivo primero.'); return; }

    var label = els.fenceLabel.value.trim();
    var lat = parseFloat(els.fenceLat.value);
    var lon = parseFloat(els.fenceLon.value);
    var radius = parseInt(els.fenceRadius.value, 10);

    if (!label) { alert('Ponle un nombre a la zona (Casa, Colegio…).'); return; }
    if (isNaN(lat) || lat < -90 || lat > 90 || isNaN(lon) || lon < -180 || lon > 180) {
      alert('Latitud/longitud no válidas: usa "Usar centro del mapa" o "Usar última posición".');
      return;
    }
    if (isNaN(radius) || radius < 50 || radius > 20000) {
      alert('El radio debe estar entre 50 y 20000 metros.');
      return;
    }

    sb.from('geofences').insert({
      device_id: deviceId,
      label: label,
      lat: lat,
      lon: lon,
      radius_m: radius,
      notify_on: els.fenceNotify.value
    }).then(function (r) {
      if (r.error) { alert('Error al guardar la zona: ' + r.error.message); return; }
      els.fenceLabel.value = '';
      loadFences();
    });
  }

  // ---- Gráficas -----------------------------------------------------------
  function drawCharts() {
    if (!window.Chart) return;
    var labels = rowsDesc.slice().reverse().map(function (p) { return fmtTime(p.recorded_at); });

    if (batteryChart) batteryChart.destroy();
    batteryChart = new Chart(els.batteryCanvas, {
      type: 'line',
      data: {
        labels: labels,
        datasets: [{
          label: 'Batería %',
          data: rowsDesc.slice().reverse().map(function (p) { return p.battery_pct; }),
          borderColor: '#34d399', backgroundColor: 'rgba(52,211,153,.15)',
          fill: true, tension: .3, pointRadius: 0, borderWidth: 2
        }]
      },
      options: {
        responsive: true, animation: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { ticks: { color: '#64748b', maxTicksLimit: 8 }, grid: { color: 'rgba(51,65,85,.4)' } },
          y: { min: 0, max: 100, ticks: { color: '#64748b' }, grid: { color: 'rgba(51,65,85,.4)' } }
        }
      }
    });

    // Distancia por hora
    var byHour = {};
    for (var i = 1; i < route.length; i++) {
      var hourKey = route[i][2].slice(0, 13) + ':00';
      byHour[hourKey] = (byHour[hourKey] || 0) + haversineKm(route[i - 1], route[i]);
    }
    var hourKeys = Object.keys(byHour).sort();
    if (distanceChart) distanceChart.destroy();
    distanceChart = new Chart(els.distanceCanvas, {
      type: 'bar',
      data: {
        labels: hourKeys,
        datasets: [{
          label: 'km',
          data: hourKeys.map(function (k) { return byHour[k]; }),
          backgroundColor: 'rgba(56,189,248,.55)', borderRadius: 3
        }]
      },
      options: {
        responsive: true, animation: false,
        plugins: { legend: { display: false } },
        scales: {
          x: { ticks: { color: '#64748b', maxTicksLimit: 8 }, grid: { display: false } },
          y: { ticks: { color: '#64748b' }, grid: { color: 'rgba(51,65,85,.4)' } }
        }
      }
    });
  }

  // ---- Avisos: zonas, SOS y "llegué bien" -----------------------------------
  function loadEvents() {
    var deviceId = els.deviceSelect.value;
    if (!deviceId) { els.eventList.textContent = '—'; return; }

    Promise.all([
      sb.from('geofence_events').select('kind,label,at,lat,lon')
        .eq('device_id', deviceId).order('at', { ascending: false }).limit(15),
      sb.from('device_events').select('kind,note,at,lat,lon')
        .eq('device_id', deviceId).order('at', { ascending: false }).limit(15)
    ]).then(function (res) {
      var items = [];
      if (!res[0].error) {
        (res[0].data || []).forEach(function (e) {
          items.push({ kind: e.kind === 'enter' ? 'entró en' : 'salió de', text: e.label || 'zona', at: e.at });
        });
      }
      if (!res[1].error) {
        (res[1].data || []).forEach(function (e) {
          items.push({ kind: e.kind, text: e.note || 'sin nota', at: e.at });
        });
      }
      items.sort(function (a, b) { return new Date(b.at) - new Date(a.at); });
      renderEvents(items.slice(0, 20));
    });
  }

  function renderEvents(items) {
    if (!els.eventList) return;
    if (!items.length) {
      els.eventList.innerHTML = '<div class="text-slate-500">Sin avisos todavía. Aquí aparecen las entradas y salidas de zona, los SOS y los "llegué bien".</div>';
      return;
    }
    els.eventList.innerHTML = items.map(function (e) {
      var cls = e.kind === 'sos' ? 'text-red-400 font-semibold'
        : e.kind === 'checkin' ? 'text-emerald-300'
          : e.kind === 'entró en' ? 'text-sky-300' : 'text-amber-400';
      var icon = e.kind === 'sos' ? '🆘' : (e.kind === 'checkin' ? '✅' : '🚪');
      return '<div class="flex gap-2 items-baseline">' +
        '<span class="' + cls + '">' + icon + ' ' + escapeHtml(e.kind) + '</span>' +
        '<span class="text-slate-300 truncate">' + escapeHtml(e.text) + '</span>' +
        '<span class="ml-auto text-slate-500">' + fmtDateTime(e.at) + '</span></div>';
    }).join('');
  }

  // ---- Reproducción de ruta ----------------------------------------------
  function setupReplay() {
    stopReplay();
    if (!route.length) { els.replayBar.classList.add('hidden'); return; }
    els.replayBar.classList.remove('hidden');
    els.replaySlider.max = String(route.length - 1);
    els.replaySlider.value = '0';
    replayIdx = 0;
    placeReplay();
  }
  function placeReplay() {
    var p = route[replayIdx];
    if (!p) return;
    if (!replayMarker) replayMarker = L.marker(p, { icon: replayIcon, zIndexOffset: 2000 }).addTo(map);
    else replayMarker.setLatLng(p);
    els.replayTime.textContent = fmtTime(p[2]);
  }
  function stopReplay() {
    if (replayTimer) { clearInterval(replayTimer); replayTimer = null; }
    els.replayPlay.textContent = '▶ Reproducir ruta';
  }
  els.replayPlay.addEventListener('click', function () {
    if (replayTimer) { stopReplay(); return; }
    els.replayPlay.textContent = '⏸ Pausar';
    replayTimer = setInterval(function () {
      replayIdx++;
      if (replayIdx >= route.length) { replayIdx = 0; }
      els.replaySlider.value = String(replayIdx);
      placeReplay();
      map.panTo(route[replayIdx], { animate: true });
    }, 700);
  });
  els.replaySlider.addEventListener('input', function () {
    stopReplay();
    replayIdx = parseInt(els.replaySlider.value, 10) || 0;
    placeReplay();
  });

  // ---- Exportaciones ------------------------------------------------------
  els.csvBtn.addEventListener('click', function () {
    if (!rowsDesc.length) { alert('No hay datos en la consulta actual.'); return; }
    var head = 'recorded_at,lat,lon,accuracy_m,speed_mps,battery_pct,charging,source,provider';
    var lines = rowsDesc.map(function (p) {
      return [p.recorded_at, p.lat, p.lon, p.accuracy, p.speed, p.battery_pct, p.charging, p.source, p.provider]
        .map(function (v) { return v == null ? '' : String(v); }).join(',');
    });
    download('historial_' + Date.now() + '.csv', [head].concat(lines).join('\n'), 'text/csv');
  });

  els.gpxBtn.addEventListener('click', function () {
    if (!route.length) { alert('No hay datos en la consulta actual.'); return; }
    var pts = route.map(function (p) {
      return '    <trkpt lat="' + p[0] + '" lon="' + p[1] + '"><time>' + p[2] + '</time></trkpt>';
    }).join('\n');
    var gpx = '<?xml version="1.0" encoding="UTF-8"?>\n' +
      '<gpx version="1.1" creator="Panel de Localizacion" xmlns="http://www.topografix.com/GPX/1/1">\n' +
      '  <trk><name>Historial ' + new Date().toISOString() + '</name><trkseg>\n' + pts + '\n  </trkseg></trk>\n</gpx>';
    download('historial_' + Date.now() + '.gpx', gpx, 'application/gpx+xml');
  });

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
})();
