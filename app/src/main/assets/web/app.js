(() => {
  "use strict";

  const $ = (s) => document.querySelector(s);
  const A = window.Android || null; // missing when the page is opened in a desktop browser

  // ------------------------------------------------------------ settings
  const DEFAULTS = { city: "أبوظبي", lat: 24.4539, lon: 54.3773, method: 8, team: "" };
  const cfg = Object.assign({}, DEFAULTS, readJSON("cfg", {}));

  function readJSON(key, fallback) {
    try { const v = localStorage.getItem(key); return v ? JSON.parse(v) : fallback; }
    catch (e) { return fallback; }
  }
  function writeJSON(key, val) {
    try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) {}
  }

  // ------------------------------------------------------------ helpers
  // Western digits -> Arabic-Indic, keeping leading zeros ("05" -> "٠٥").
  const n = (x) => String(x).replace(/[0-9]/g, (d) => "٠١٢٣٤٥٦٧٨٩"[d]);
  const pad = (x) => String(x).padStart(2, "0");
  const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

  function toast(msg) {
    const t = $("#toast");
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast.h);
    toast.h = setTimeout(() => (t.hidden = true), 3500);
  }

  async function getJSON(url, timeoutMs = 12000) {
    const c = new AbortController();
    const h = setTimeout(() => c.abort(), timeoutMs);
    try {
      const r = await fetch(url, { signal: c.signal });
      if (!r.ok) throw new Error("HTTP " + r.status);
      return await r.json();
    } finally {
      clearTimeout(h);
    }
  }

  function longPress(el, ms, onLong, onTap) {
    let timer = null, fired = false, sx = 0, sy = 0;
    el.addEventListener("pointerdown", (e) => {
      fired = false; sx = e.clientX; sy = e.clientY;
      timer = setTimeout(() => { fired = true; onLong(e); }, ms);
    });
    el.addEventListener("pointermove", (e) => {
      if (timer && (Math.abs(e.clientX - sx) > 12 || Math.abs(e.clientY - sy) > 12)) { clearTimeout(timer); timer = null; }
    });
    const end = (e) => {
      if (timer) { clearTimeout(timer); timer = null; if (!fired && onTap && e.type === "pointerup") onTap(e); }
    };
    el.addEventListener("pointerup", end);
    el.addEventListener("pointercancel", end);
    el.addEventListener("contextmenu", (e) => e.preventDefault());
  }

  // ------------------------------------------------------------ fit to screen
  function fit() {
    const s = Math.min(window.innerWidth / 1920, window.innerHeight / 720);
    $("#stage").style.transform = `translate(-50%, -50%) scale(${s})`;
  }
  window.addEventListener("resize", fit);

  // ------------------------------------------------------------ background photos
  const PHOTOS = [1, 2, 3, 4, 5, 6].map((i) => `photos/bg${i}.jpg`);
  let bgFront = $("#bgA"), bgBack = $("#bgB");
  function showPhoto() {
    const slot = Math.floor(Date.now() / (30 * 60 * 1000)); // a new photo every 30 minutes
    const url = PHOTOS[slot % PHOTOS.length];
    if (showPhoto.last === url) return;
    showPhoto.last = url;
    const img = new Image();
    img.onload = () => {
      bgBack.style.backgroundImage = `url(${url})`;
      bgBack.classList.add("on");
      bgFront.classList.remove("on");
      [bgFront, bgBack] = [bgBack, bgFront];
    };
    img.src = url;
  }

  // ------------------------------------------------------------ clock & dates
  const fWeekday = new Intl.DateTimeFormat("ar", { weekday: "long" });
  const fHijri = new Intl.DateTimeFormat("ar-SA-u-ca-islamic-umalqura-nu-arab", { day: "numeric", month: "long", year: "numeric" });
  const fGregMonth = new Intl.DateTimeFormat("ar-u-ca-gregory", { month: "long" });

  function hijriText(d) {
    const p = {};
    fHijri.formatToParts(d).forEach((x) => (p[x.type] = x.value));
    return `${fWeekday.format(d)}، ${p.day} ${p.month} ${p.year} هـ`;
  }
  function gregText(d) {
    return `${fWeekday.format(d)}، ${n(d.getDate())} ${fGregMonth.format(d)} ${n(d.getFullYear())}`;
  }
  function time12(h, m) {
    return { h: n(h % 12 || 12), m: n(pad(m)), ap: h < 12 ? "ص" : "م" };
  }

  let lastDay = "";
  function tickClock() {
    const d = new Date();
    const t = time12(d.getHours(), d.getMinutes());
    $("#hh").textContent = t.h;
    $("#mm").textContent = t.m;
    $("#ap").textContent = t.ap;
    const day = ymd(d);
    if (day !== lastDay) {
      lastDay = day;
      $("#hijri").textContent = hijriText(d);
      $("#greg").textContent = gregText(d);
      showHadith(d);
      loadPrayers();
    }
    renderPrayer();
    showPhoto();
  }

  // ------------------------------------------------------------ prayer times (Aladhan)
  const PRAYERS = [["Fajr", "الفجر"], ["Dhuhr", "الظهر"], ["Asr", "العصر"], ["Maghrib", "المغرب"], ["Isha", "العشاء"]];
  const prayerDays = {}; // "yyyy-mm-dd" -> {Fajr:"04:58", ...}

  async function fetchPrayerDay(d) {
    const day = ymd(d);
    const key = `pt:${day}:${cfg.lat}:${cfg.lon}:${cfg.method}`;
    const cached = readJSON(key, null);
    if (cached) { prayerDays[day] = cached; return; }
    const url = `https://api.aladhan.com/v1/timings/${pad(d.getDate())}-${pad(d.getMonth() + 1)}-${d.getFullYear()}` +
      `?latitude=${cfg.lat}&longitude=${cfg.lon}&method=${cfg.method}`;
    const j = await getJSON(url);
    const t = {};
    PRAYERS.forEach(([k]) => (t[k] = String(j.data.timings[k]).slice(0, 5)));
    prayerDays[day] = t;
    writeJSON(key, t);
  }

  async function loadPrayers() {
    const today = new Date();
    const tomorrow = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1);
    try { await fetchPrayerDay(today); } catch (e) {}
    try { await fetchPrayerDay(tomorrow); } catch (e) {}
    renderPrayer();
  }

  function at(day, hhmm) {
    const [h, m] = hhmm.split(":").map(Number);
    return new Date(day.getFullYear(), day.getMonth(), day.getDate(), h, m);
  }

  function arDuration(totalMin) {
    const h = Math.floor(totalMin / 60), m = totalMin % 60;
    const hs = h === 0 ? "" : h === 1 ? "ساعة" : h === 2 ? "ساعتين" : h <= 10 ? `${n(h)} ساعات` : `${n(h)} ساعة`;
    const ms = m === 0 ? "" : m === 1 ? "دقيقة" : m === 2 ? "دقيقتين" : m <= 10 ? `${n(m)} دقائق` : `${n(m)} دقيقة`;
    if (!hs && !ms) return "الآن";
    return "بعد " + [hs, ms].filter(Boolean).join(" و");
  }

  function renderPrayer() {
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const tomorrow = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1);
    let next = null;
    const t = prayerDays[ymd(today)];
    if (t) {
      for (const [k, name] of PRAYERS) {
        const when = at(today, t[k]);
        if (when > now) { next = { name, when }; break; }
      }
    }
    if (!next) {
      const t2 = prayerDays[ymd(tomorrow)];
      if (t2) next = { name: "الفجر", when: at(tomorrow, t2.Fajr) };
    }
    if (!next) { $("#prayerName").textContent = "—"; $("#prayerIn").textContent = ""; return; }
    const tt = time12(next.when.getHours(), next.when.getMinutes());
    $("#prayerName").textContent = `${next.name} ${tt.h}:${tt.m} ${tt.ap}`;
    const mins = Math.max(0, Math.ceil((next.when - now) / 60000));
    $("#prayerIn").textContent = arDuration(mins);
  }

  // ------------------------------------------------------------ weather (Open-Meteo)
  function wmoText(c) {
    if (c === 0) return "صحو";
    if (c === 1) return "صحو غالباً";
    if (c === 2) return "غائم جزئياً";
    if (c === 3) return "غائم";
    if (c === 45 || c === 48) return "ضباب";
    if (c >= 51 && c <= 57) return "رذاذ";
    if (c >= 61 && c <= 67) return "مطر";
    if (c >= 71 && c <= 77) return "ثلج";
    if (c >= 80 && c <= 82) return "زخات مطر";
    if (c >= 95) return "عواصف رعدية";
    return "";
  }

  function renderWeather(w) {
    if (!w) return;
    $("#wxTemp").textContent = `${n(Math.round(w.t))}°`;
    $("#wxText").textContent = wmoText(w.c);
    $("#wxRange").textContent = `${cfg.city} · العظمى ${n(Math.round(w.max))}° / الصغرى ${n(Math.round(w.min))}°`;
  }

  async function loadWeather() {
    renderWeather(readJSON("wx", null));
    try {
      const url = `https://api.open-meteo.com/v1/forecast?latitude=${cfg.lat}&longitude=${cfg.lon}` +
        `&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1`;
      const j = await getJSON(url);
      const w = { t: j.current.temperature_2m, c: j.current.weather_code, max: j.daily.temperature_2m_max[0], min: j.daily.temperature_2m_min[0] };
      writeJSON("wx", w);
      renderWeather(w);
    } catch (e) {}
  }

  // ------------------------------------------------------------ hadith
  function showHadith(d) {
    const list = window.HADITH || [];
    if (!list.length) return;
    const start = new Date(d.getFullYear(), 0, 0);
    const dayOfYear = Math.floor((d - start) / 86400000);
    const h = list[dayOfYear % list.length];
    $("#hadithText").textContent = h.t;
    $("#hadithSrc").textContent = h.s;
  }

  // ------------------------------------------------------------ matches (TheSportsDB, optional)
  async function loadMatch() {
    const el = $("#match");
    if (!cfg.team) { el.hidden = true; return; }
    const base = "https://www.thesportsdb.com/api/v1/json/123";
    try {
      const [last, next] = await Promise.all([
        getJSON(`${base}/eventslast.php?id=${cfg.team}`).catch(() => null),
        getJSON(`${base}/eventsnext.php?id=${cfg.team}`).catch(() => null),
      ]);
      const ev = (last && last.results && last.results[0]) || null;
      const up = (next && next.events && next.events[0]) || null;
      const recent = ev && ev.dateEvent && (Date.now() - new Date(ev.dateEvent + "T00:00:00Z")) < 36 * 3600 * 1000;
      el.innerHTML = "";
      const add = (tag, cls, txt) => { const s = document.createElement(tag); if (cls) s.className = cls; s.textContent = txt; el.appendChild(s); };
      if (recent && ev.intHomeScore != null) {
        add("span", "", ev.strHomeTeam);
        add("b", "", `${n(ev.intHomeScore)} - ${n(ev.intAwayScore)}`);
        add("span", "", ev.strAwayTeam);
        add("span", "when", "النتيجة النهائية");
      } else if (up) {
        add("span", "", up.strHomeTeam);
        add("b", "", "×");
        add("span", "", up.strAwayTeam);
        let when = "";
        if (up.strTimestamp) {
          const d = new Date(up.strTimestamp.endsWith("Z") ? up.strTimestamp : up.strTimestamp + "Z");
          const tt = time12(d.getHours(), d.getMinutes());
          when = `${fWeekday.format(d)} ${tt.h}:${tt.m} ${tt.ap}`;
        } else if (up.dateEvent) {
          when = up.dateEvent;
        }
        add("span", "when", when);
      } else {
        el.hidden = true;
        return;
      }
      el.hidden = false;
    } catch (e) {
      el.hidden = true;
    }
  }

  // ------------------------------------------------------------ apps
  const ICONS = {};
  let apps = [];
  let favs = readJSON("favs", null);
  const PREFERRED = ["com.autonavi.amapauto", "com.autonavi.minimap", "com.google.android.youtube", "com.spotify.music",
    "com.anghami", "com.google.android.apps.maps", "com.waze", "com.netflix.mediaclient"];

  function iconFor(pkg) {
    if (ICONS[pkg] !== undefined) return ICONS[pkg];
    let v = "";
    try { v = A ? A.icon(pkg) : ""; } catch (e) {}
    ICONS[pkg] = v;
    return v;
  }

  function iconEl(pkg) {
    const src = iconFor(pkg);
    if (src) { const img = document.createElement("img"); img.alt = ""; img.src = src; return img; }
    const ph = document.createElement("div"); ph.className = "ph"; return ph;
  }

  function loadApps() {
    try {
      apps = A ? JSON.parse(A.apps()) : [
        { pkg: "demo.maps", label: "الخرائط" }, { pkg: "demo.yt", label: "يوتيوب" },
        { pkg: "demo.music", label: "الموسيقى" }, { pkg: "demo.qari", label: "القارئ" }, { pkg: "demo.home", label: "البيت" },
      ];
    } catch (e) { apps = []; }
    apps.sort((a, b) => a.label.localeCompare(b.label, "ar"));
    const installed = new Set(apps.map((a) => a.pkg));
    if (!favs) {
      favs = PREFERRED.filter((p) => installed.has(p));
      for (const a of apps) { if (favs.length >= 5) break; if (!favs.includes(a.pkg)) favs.push(a.pkg); }
      writeJSON("favs", favs);
    }
    favs = favs.filter((p) => installed.has(p));
    renderDock();
    if (!$("#drawer").hidden) renderDrawer();
  }

  function launch(pkg) {
    if (A) A.launch(pkg); else toast("فتح " + pkg);
  }

  function renderDock() {
    const box = $("#favs");
    box.innerHTML = "";
    const max = $("#match").hidden ? 6 : 5;
    favs.slice(0, max).forEach((pkg) => {
      const a = apps.find((x) => x.pkg === pkg);
      if (!a) return;
      const b = document.createElement("button");
      b.className = "app";
      b.appendChild(iconEl(pkg));
      const s = document.createElement("span"); s.textContent = a.label; b.appendChild(s);
      b.addEventListener("click", () => launch(pkg));
      box.appendChild(b);
    });
  }

  function toggleFav(pkg) {
    const i = favs.indexOf(pkg);
    if (i >= 0) { favs.splice(i, 1); toast("أُزيل من المفضلة"); }
    else if (favs.length >= 6) { toast("المفضلة ممتلئة. أزل تطبيقاً أولاً"); return; }
    else { favs.push(pkg); toast("أُضيف للمفضلة"); }
    writeJSON("favs", favs);
    renderDock();
    renderDrawer();
  }

  function renderDrawer() {
    const grid = $("#grid");
    grid.innerHTML = "";
    apps.forEach((a) => {
      const t = document.createElement("div");
      t.className = "tile" + (favs.includes(a.pkg) ? " fav" : "");
      t.appendChild(iconEl(a.pkg));
      const s = document.createElement("span"); s.textContent = a.label; t.appendChild(s);
      longPress(t, 550, () => toggleFav(a.pkg), () => { closeSheets(); launch(a.pkg); });
      grid.appendChild(t);
    });
  }

  // ------------------------------------------------------------ media
  let lastTitle = null;
  function renderMedia() {
    const box = $("#media");
    let m = null;
    try { m = A ? JSON.parse(A.media()) : { perm: true, active: false }; } catch (e) { m = null; }
    if (!m) return;
    if (m.perm === false) {
      box.classList.add("dim");
      $("#mediaTitle").textContent = "عرض الوسائط غير مفعّل";
      $("#mediaArtist").textContent = "اضغط مطولاً على الساعة ثم فعّله من الإعدادات";
      $("#mediaProg").style.width = "0";
      return;
    }
    if (!m.active) {
      box.classList.add("dim");
      $("#mediaTitle").textContent = "لا يوجد تشغيل";
      $("#mediaArtist").textContent = "افتح تطبيق صوت ليظهر هنا";
      $("#mediaProg").style.width = "0";
      if (lastTitle !== "") { lastTitle = ""; $("#mediaArt").style.backgroundImage = ""; }
      return;
    }
    box.classList.remove("dim");
    $("#mediaTitle").textContent = m.title || "بدون عنوان";
    $("#mediaArtist").textContent = m.artist || "";
    $("#mediaProg").style.width = m.dur > 0 ? Math.min(100, (m.pos / m.dur) * 100) + "%" : "0";
    $("#playIcon").innerHTML = m.playing ? '<path d="M7 5h4v14H7zM13 5h4v14h-4z"/>' : '<path d="M8 5v14l11-7z"/>';
    if (m.title !== lastTitle) {
      lastTitle = m.title;
      let art = "";
      try { art = A ? A.mediaArt() : ""; } catch (e) {}
      $("#mediaArt").style.backgroundImage = art ? `url(${art})` : "";
    }
  }

  document.querySelectorAll(".ctrl button").forEach((b) =>
    b.addEventListener("click", () => { if (A) A.mediaCmd(b.dataset.cmd); setTimeout(renderMedia, 300); })
  );

  // ------------------------------------------------------------ car info (phase 3: read over ADB)
  // Car values come from the car itself over ADB (autoservice); dashes until read.
  let carRaw = null;
  function renderCar() {
    let c = null;
    try { c = A ? JSON.parse(A.car()) : { soc: 78, cabin: 23, outside: 34, evRange: 96, fuelRange: 640 }; } catch (e) {}
    if (!c) return;
    carRaw = c.raw || null;
    if (c.soc != null) {
      $("#carBat").innerHTML = `${n(c.soc)}<small>٪</small>`;
      $("#carBatBar").style.width = Math.max(0, Math.min(100, c.soc)) + "%";
    } else {
      $("#carBat").textContent = "—";
      $("#carBatBar").style.width = "0";
    }
    const range = [];
    if (c.evRange != null) range.push(`كهرباء ${n(c.evRange)} كم`);
    if (c.fuelRange != null) range.push(`المدى ${n(c.fuelRange)} كم`);
    $("#carBatNote").textContent = range.join(" · ");
    $("#carCabin").textContent = c.cabin != null ? `${n(c.cabin)}°` : "—";
    $("#carOutside").textContent = c.outside != null ? `الخارج ${n(c.outside)}°` : "";
  }

  // ------------------------------------------------------------ trip (GPS, new trip every car start)
  function km(x) {
    const s = x < 10 ? x.toFixed(1) : String(Math.round(x));
    return n(s).replace(".", "٫");
  }
  function sinceText(ms) {
    const m = Math.floor(ms / 60000);
    if (m < 1) return "بدأ الآن";
    return "منذ " + arDuration(m).replace(/^بعد /, "");
  }
  function renderTrip() {
    let t = null;
    try { t = A ? JSON.parse(A.trip()) : { km: 12.4, elapsed: 25 * 60000, tracking: true, fix: true }; } catch (e) {}
    if (!t) return;
    $("#tripKm").innerHTML = `${km(t.km || 0)}<small>كم</small>`;
    $("#tripNote").textContent = !t.tracking ? "الموقع غير مفعّل" : (!t.fix && !(t.km > 0)) ? "بانتظار GPS" : sinceText(t.elapsed || 0);
  }
  longPress($("#tripCell"), 900, () => { if (A) A.tripReset(); setTimeout(renderTrip, 200); });

  // ------------------------------------------------------------ ADB + updates
  const REPO = "khalid-1996/muawin";
  let updateUrl = null;

  function parseVer(s) {
    const m = String(s || "").match(/(\d+)\.(\d+)\.(\d+)/);
    return m ? [+m[1], +m[2], +m[3]] : [0, 0, 0];
  }
  function newer(a, b) {
    const x = parseVer(a), y = parseVer(b);
    for (let i = 0; i < 3; i++) { if (x[i] !== y[i]) return x[i] > y[i]; }
    return false;
  }

  async function checkUpdate(manual) {
    const cur = A ? A.version() : "1.0.0";
    try {
      const j = await getJSON(`https://api.github.com/repos/${REPO}/releases/latest`);
      const tag = j.tag_name || "";
      const asset = (j.assets || []).find((a) => a.name && a.name.endsWith(".apk"));
      $("#sVerNow").textContent = `الحالي ${cur} · المتوفر ${tag.replace(/^v/, "") || "—"}`;
      if (asset && newer(tag, cur)) {
        updateUrl = asset.browser_download_url;
        $("#bannerText").textContent = `تحديث جديد متوفر (${tag})`;
        $("#banner").hidden = false;
      } else if (manual) {
        toast("أنت على آخر إصدار");
      }
    } catch (e) {
      $("#sVerNow").textContent = `الحالي ${cur}`;
      if (manual) toast("تعذر التحقق من التحديثات");
    }
  }

  const ADB_AR = { connected: "مربوط ✓", connecting: "جاري الربط… (وافق على طلب السماح إن ظهر)", error: "غير مربوط، يعيد المحاولة تلقائياً", off: "غير مربوط" };
  let linkTop = "";
  function renderAdb() {
    if (!A) { $("#sAdbNow").textContent = "الحالة: غير متاح في المعاينة"; return; }
    let i = {};
    try { i = JSON.parse(A.linkInfo()); } catch (e) {}
    $("#sAdbNow").textContent = "الحالة: " + (ADB_AR[i.state] || i.state || "—") + (i.state === "error" && i.error ? ` (${i.error})` : "");
    $("#sAdbConnect").hidden = i.state === "connected";
    $("#sGuard").checked = !!i.guard;
    $("#sAuto").checked = i.autostart !== false;
    linkTop = i.top || "";
    let g;
    if (i.home == null || i.home < 0) g = "المعاون غير مثبّت على شاشة بعد. اختر شاشة الراكب من «شاشة العرض».";
    else if (i.state !== "connected") g = `مثبّت على الشاشة ${n(i.home)}. الحماية تعمل عند ربط ADB.`;
    else g = `مثبّت على الشاشة ${n(i.home)}. الظاهر عليها الآن: ${linkTop || "—"}`;
    $("#sGuardNow").textContent = g;
    if (carRaw) $("#sCarRaw").textContent = "قراءات السيارة: " + Object.entries(carRaw).map(([k, v]) => `${k}=${v}`).join("  ");
    $("#sMarkHome").hidden = !(i.state === "connected" && linkTop && linkTop !== "ae.khalid.muawin" && !i.topIsHome);
  }

  $("#sAdbConnect").addEventListener("click", () => { if (A) { A.adbConnect(); setTimeout(renderAdb, 1500); } });
  $("#sGuard").addEventListener("change", (e) => { if (A) A.setGuard(e.target.checked); });
  $("#sAuto").addEventListener("change", (e) => { if (A) A.setAutoStart(e.target.checked); });
  $("#sMarkHome").addEventListener("click", () => { if (A && linkTop) { A.markHome(linkTop); renderAdb(); } });
  $("#sCheckUpdate").addEventListener("click", () => checkUpdate(true));
  $("#bannerBtn").addEventListener("click", () => { if (A && updateUrl) A.installUpdate(updateUrl); });
  $("#bannerX").addEventListener("click", () => { $("#banner").hidden = true; });

  // ------------------------------------------------------------ sheets
  function closeSheets() { document.querySelectorAll(".sheet").forEach((s) => (s.hidden = true)); }
  window.onBack = closeSheets;
  document.querySelectorAll("[data-close]").forEach((b) => b.addEventListener("click", closeSheets));

  $("#allBtn").addEventListener("click", () => { closeSheets(); renderDrawer(); $("#drawer").hidden = false; });

  function openSettings() {
    closeSheets();
    $("#sCity").value = cfg.city;
    $("#sLat").value = cfg.lat;
    $("#sLon").value = cfg.lon;
    $("#sMethod").value = String(cfg.method);
    $("#sTeam").value = cfg.team;
    $("#ver").textContent = A ? "الإصدار " + A.version() : "";
    renderAdb();
    $("#sVerNow").textContent = "الحالي " + (A ? A.version() : "1.0.0");
    const box = $("#sDisplays");
    box.innerHTML = "";
    let list = [], cur = 0;
    try { list = A ? JSON.parse(A.displays()) : [{ id: 0, name: "Built-in", w: 1920, h: 1080 }]; cur = A ? A.currentDisplay() : 0; } catch (e) {}
    let pinned = -1;
    try { pinned = A ? JSON.parse(A.linkInfo()).home : -1; } catch (e) {}
    $("#sDisplayNow").textContent = `المعاون الآن على الشاشة ${n(cur)}. اضغط على شاشة لتثبيته عليها (●= المثبّتة).`;
    list.forEach((d) => {
      const b = document.createElement("button");
      b.className = "btn" + (d.id === pinned ? " cur" : "");
      const label = document.createElement("span");
      label.textContent = `${d.id === pinned ? "● " : ""}شاشة ${n(d.id)}: `;
      const meta = document.createElement("bdi");
      meta.textContent = `${d.name} (${d.w}×${d.h})`;
      b.append(label, meta);
      b.addEventListener("click", () => { if (A) { A.moveTo(d.id); setTimeout(openSettings, 400); } });
      box.appendChild(b);
    });
    $("#settings").hidden = false;
  }
  longPress($("#clock"), 800, openSettings);

  $("#sMediaPerm").addEventListener("click", () => { if (A) A.openMediaPermission(); });

  $("#sSave").addEventListener("click", () => {
    const lat = parseFloat($("#sLat").value), lon = parseFloat($("#sLon").value);
    if (!isFinite(lat) || !isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
      toast("خط العرض أو الطول غير صحيح");
      return;
    }
    cfg.city = $("#sCity").value.trim() || DEFAULTS.city;
    cfg.lat = lat;
    cfg.lon = lon;
    cfg.method = parseInt($("#sMethod").value, 10) || DEFAULTS.method;
    cfg.team = $("#sTeam").value.replace(/\D/g, "");
    writeJSON("cfg", cfg);
    closeSheets();
    toast("تم الحفظ");
    Object.keys(prayerDays).forEach((k) => delete prayerDays[k]);
    loadPrayers();
    loadWeather();
    loadMatch().then(renderDock);
  });

  // ------------------------------------------------------------ lifecycle
  window.onResumeApp = () => { loadApps(); renderMedia(); };

  fit();
  tickClock();
  loadWeather();
  loadApps();
  loadMatch().then(renderDock);
  renderMedia();
  checkUpdate(false);
  renderTrip();
  renderCar();

  setInterval(tickClock, 1000);
  setInterval(renderMedia, 1000);
  setInterval(loadWeather, 30 * 60 * 1000);
  setInterval(loadMatch, 15 * 60 * 1000);
  setInterval(loadPrayers, 6 * 60 * 60 * 1000);
  setInterval(checkUpdate, 6 * 60 * 60 * 1000);
  setInterval(() => { if (!$("#settings").hidden) renderAdb(); }, 2000);
  setInterval(renderTrip, 2000);
  setInterval(renderCar, 3000);
})();
