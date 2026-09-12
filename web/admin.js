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
    pairToken: $('pairToken'), pairBtn: $('pairBtn')
  };

  if (!configured) els.banner.classList.remove('hidden');

  var sb = configured ? supabase.createClient(cfg.supabaseUrl, cfg.supabaseAnonKey) : null;

  // ---- Estado -------------------------------------------------------------
  var PAGE = 200;
  var page = 0, totalCount = 0;
  var rowsDesc = [];   // consulta actual, descendente (tabla)
  var route = [];      // misma consulta, ascendente (mapa/replay)
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
  var replayMarker = null;

  var replayIcon = L.divIcon({
    className: '', html: '<div class="replay-dot"></div>',
    iconSize: [18, 18], iconAnchor: [9, 9]
  });

  function drawRoute() {
    routeLine.setLatLngs(route);
    routeDots.clearLayers();
    route.forEach(function (p, i) {
      if (i % Math.max(1, Math.floor(route.length / 120)) !== 0 && i !== route.length - 1) return;
      L.circleMarker(p, { radius: 3, color: '#38bdf8', weight: 1, fillOpacity: .7 })
        .bindPopup('<b>' + fmtDateTime(p[2]) + '</b><br>' + p[0].toFixed(6) + ', ' + p[1].toFixed(6))
        .addTo(routeDots);
    });
    if (route.length) map.fitBounds(routeLine.getBounds().pad(0.12));
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
  function loadDevices() {
    return sb.from('devices').select('id,label,last_seen,created_at').order('label')
      .then(function (r) {
        if (r.error) { setConn('ERROR: ' + r.error.message, false); return; }
        devices = r.data || [];
        els.deviceSelect.innerHTML = '';
        devices.forEach(function (d) {
          var o = document.createElement('option');
          o.value = d.id;
          o.textContent = d.label;
          els.deviceSelect.appendChild(o);
        });
        renderDeviceList();
        if (devices.length) query();
        else setConn('Sin dispositivos emparejados', false);
      });
  }

  function renderDeviceList() {
    els.deviceList.innerHTML = '';
    devices.forEach(function (d) {
      var row = document.createElement('div');
      row.className = 'flex items-center gap-2 bg-slate-900 border border-slate-800 rounded-lg px-3 py-2';
      var seen = d.last_seen ? fmtDateTime(d.last_seen) : 'nunca';
      row.innerHTML =
        '<div class="min-w-0"><div class="text-sm font-medium truncate">' + escapeHtml(d.label) + '</div>' +
        '<div class="text-[11px] text-slate-500 truncate">' + d.id.slice(0, 8) + '… · visto ' + seen + '</div></div>';
      var btn = document.createElement('button');
      btn.className = 'ml-auto text-xs bg-slate-800 border border-slate-700 rounded px-2 py-1 hover:bg-slate-700';
      btn.textContent = 'Renombrar';
      btn.addEventListener('click', function () {
        var nuevo = prompt('Nueva etiqueta para "' + d.label + '":', d.label);
        if (!nuevo || nuevo === d.label) return;
        sb.from('devices').update({ label: nuevo.trim() }).eq('id', d.id)
          .then(function (r) {
            if (r.error) alert('Error: ' + r.error.message);
            else loadDevices();
          });
      });
      row.appendChild(btn);
      els.deviceList.appendChild(row);
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
        alert('Dispositivo emparejado.\n\nUUID: ' + r.data + '\n\nPega este UUID y el token en la app Android (Ajustes → Emparejamiento).');
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
        route = rowsDesc.slice().reverse().map(function (p) { return [p.lat, p.lon, p.recorded_at, p]; });
        setConn('OK · ' + totalCount + ' puntos', true);
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

    // Stats + ruta + gráficas + replay
    var km = 0;
    for (var i = 1; i < route.length; i++) km += haversineKm(route[i - 1], route[i]);
    els.statCount.textContent = totalCount.toLocaleString();
    els.statKm.textContent = km.toFixed(2) + ' km';
    els.statDur.textContent = route.length > 1
      ? isoDuration(new Date(route[route.length - 1][2]) - new Date(route[0][2])) : '—';

    drawRoute();
    drawCharts();
    setupReplay();
  }

  els.queryBtn.addEventListener('click', function () { page = 0; query(); });
  els.deviceSelect.addEventListener('change', function () { page = 0; query(); });
  els.prevPage.addEventListener('click', function () { if (page > 0) { page--; query(); } });
  els.nextPage.addEventListener('click', function () { if ((page + 1) * PAGE < totalCount) { page++; query(); } });

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
