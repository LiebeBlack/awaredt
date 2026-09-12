/* ============================================================================
 *  Panel de Localización — página pública (GitHub Pages)
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
    clock: $('clock'), deviceSelect: $('deviceSelect'),
    battery: $('stBattery'), charging: $('stCharging'), lastSeen: $('stLastSeen'),
    trail: $('trailCount'), speed: $('stSpeed'), accuracy: $('stAccuracy'),
    source: $('stSource'), coords: $('stCoords'), follow: $('followBtn')
  };

  if (!configured) els.banner.classList.remove('hidden');
  if (demoMode) els.demoNote.classList.remove('hidden');

  // ---- Mapa ---------------------------------------------------------------
  var map = L.map('map', { zoomControl: true }).setView(cfg.mapCenter || [40.4168, -3.7038], cfg.mapZoom || 14);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap'
  }).addTo(map);

  var marker = null, trailLine = L.polyline([], { color: '#38bdf8', weight: 3, opacity: .85 }).addTo(map);
  var trail = [];              // [[lat,lon],...] de la sesión actual
  var devices = [];            // [{id,label}]
  var currentDevice = null;
  var following = false;
  var timer = null;

  function icon() {
    return L.divIcon({
      className: '',
      html: '<div class="marker-wrap"><div class="marker-pulse"></div><div class="marker-dot"></div></div>',
      iconSize: [24, 24], iconAnchor: [12, 12]
    });
  }

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
    var s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 60) return 'hace ' + Math.round(s) + ' s';
    if (s < 3600) return 'hace ' + Math.round(s / 60) + ' min';
    if (s < 86400) return 'hace ' + Math.round(s / 3600) + ' h';
    return 'hace ' + Math.round(s / 86400) + ' d';
  }

  function render(pos) {
    if (!pos) return;
    var latlng = [pos.lat, pos.lon];

    if (!marker) {
      marker = L.marker(latlng, { icon: icon(), zIndexOffset: 1000 }).addTo(map);
      map.setView(latlng, Math.max(map.getZoom(), 16));
    } else {
      marker.setLatLng(latlng);
    }

    var sameDevicePos = !currentDevice || pos.device_id === currentDevice.id;
    if (sameDevicePos) {
      var last = trail[trail.length - 1];
      if (!last || last[0] !== pos.lat || last[1] !== pos.lon) {
        trail.push(latlng);
        if (trail.length > 600) trail.shift();
        trailLine.setLatLngs(trail);
      }
    }

    els.battery.textContent = pos.battery_pct != null ? pos.battery_pct + '%' : '—';
    els.charging.textContent = pos.charging === true ? '⚡ cargando' : (pos.charging === false ? 'batería' : '—');
    els.lastSeen.textContent = fmtAgo(pos.recorded_at);
    els.speed.textContent = fmtSpeed(pos.speed);
    els.accuracy.textContent = pos.accuracy != null ? '±' + Math.round(pos.accuracy) + ' m' : '—';
    els.source.textContent = pos.source || pos.provider || '—';
    els.source.dataset.src = pos.source || 'GPS';
    els.coords.textContent = pos.lat.toFixed(6) + ', ' + pos.lon.toFixed(6);
    els.trail.textContent = String(trail.length);

    var staleSec = cfg.staleAfterSec || 120;
    var age = (Date.now() - new Date(pos.recorded_at).getTime()) / 1000;
    setBadge(age < staleSec ? 'live' : 'stale', age < staleSec ? 'EN VIVO' : 'SIN SEÑAL RECIENTE');

    if (following && marker) map.panTo(latlng, { animate: true });
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

  function loadDevices() {
    if (demoMode) {
      devices = [{ id: 'demo', label: 'Dispositivo demo' }];
      fillDevices();
      return Promise.resolve();
    }
    return rest('/rest/v1/latest_positions?select=device_id,label&order=label.asc')
      .then(function (rows) {
        var seen = {};
        devices = [];
        (rows || []).forEach(function (r) {
          if (!seen[r.device_id]) { seen[r.device_id] = true; devices.push({ id: r.device_id, label: r.label }); }
        });
        fillDevices();
      });
  }

  function fillDevices() {
    els.deviceSelect.innerHTML = '';
    devices.forEach(function (d) {
      var o = document.createElement('option');
      o.value = d.id;
      o.textContent = d.label || d.id.slice(0, 8);
      els.deviceSelect.appendChild(o);
    });
    currentDevice = devices[0] || null;
  }

  function poll() {
    if (demoMode) { demoStep(); return; }
    rest('/rest/v1/latest_positions?select=*')
      .then(function (rows) {
        setBadge('live', 'EN VIVO');
        (rows || []).forEach(function (pos) {
          if (!currentDevice || pos.device_id === currentDevice.id) render(pos);
        });
      })
      .catch(function () { setBadge('offline', 'SIN CONEXIÓN'); });
  }

  // ---- Demo ---------------------------------------------------------------
  var demoIdx = 0, demoTimer = null;
  var demoRoute = [
    [40.4168, -3.7038], [40.4172, -3.7045], [40.4178, -3.7052], [40.4185, -3.7058],
    [40.4192, -3.7061], [40.4199, -3.7060], [40.4205, -3.7055], [40.4210, -3.7048],
    [40.4214, -3.7040], [40.4216, -3.7031], [40.4215, -3.7022], [40.4211, -3.7014],
    [40.4205, -3.7009], [40.4198, -3.7007], [40.4191, -3.7010], [40.4185, -3.7016]
  ];
  function demoStep() {
    var p = demoRoute[demoIdx % demoRoute.length];
    demoIdx++;
    render({
      device_id: 'demo', lat: p[0] + (Math.random() - .5) * 0.00008,
      lon: p[1] + (Math.random() - .5) * 0.00008,
      accuracy: 6 + Math.random() * 8, speed: 1.2 + Math.random() * 3,
      battery_pct: Math.max(5, 87 - demoIdx), charging: demoIdx > 12,
      source: demoIdx % 3 === 0 ? 'NETWORK' : 'FUSED',
      recorded_at: new Date().toISOString()
    });
  }

  // ---- Eventos ------------------------------------------------------------
  els.deviceSelect.addEventListener('change', function () {
    var d = devices.filter(function (x) { return x.id === els.deviceSelect.value; })[0] || null;
    currentDevice = d;
    trail = []; trailLine.setLatLngs([]);
    if (marker) { map.removeLayer(marker); marker = null; }
    poll();
  });

  els.follow.addEventListener('click', function () {
    following = !following;
    els.follow.textContent = following ? 'Siguiendo ✔ (clic para soltar)' : 'Seguir marcador ▶';
    if (following && marker) map.panTo(marker.getLatLng());
  });

  setInterval(function () {
    var n = new Date();
    els.clock.textContent = n.toTimeString().slice(0, 8);
  }, 1000);

  // ---- Arranque -----------------------------------------------------------
  if (demoMode) {
    devices = [{ id: 'demo', label: 'Dispositivo demo' }];
    fillDevices();
    demoStep();
    demoTimer = setInterval(demoStep, 2500);
  } else if (configured) {
    loadDevices()
      .then(function () { poll(); })
      .catch(function () { setBadge('offline', 'SIN CONEXIÓN'); });
    timer = setInterval(poll, cfg.pollMs || 5000);
  } else {
    setBadge('offline', 'SIN CONFIGURAR');
  }
  window.addEventListener('beforeunload', function () { clearInterval(timer); clearInterval(demoTimer); });
})();
