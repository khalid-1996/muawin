// car-api: receives driving data from المعاون (the Leopard 5 passenger-screen app)
// and stores it in MongoDB.
//
//   POST /api/car/sync                  {device, day:{date, km}, points:[{ts,date,lat,lon,kmh,acc,alt}]}
//   GET  /api/car/days?from=&to=        daily totals (YYYY-MM-DD, inclusive)
//   GET  /api/car/days/:date/points     that day's points, oldest first
//   GET  /health                        no auth, for Docker/Portainer
//
// Every /api route needs the header  X-API-Key: <CAR_API_KEY>.
//
// Collections (database MONGO_DB, default "muawin"):
//   days   _id "<device>:<date>"  {device, date, km, points, firstAt, lastAt, updatedAt}
//   points _id "<device>:<ts>"    {device, date, ts, lat, lon, kmh, acc, alt, loc(GeoJSON)}
// Point ids make uploads idempotent: a batch resent after a dropped connection is ignored.

"use strict";

const crypto = require("crypto");
const express = require("express");
const { MongoClient } = require("mongodb");

const PORT = Number(process.env.PORT || 8090);
const MONGO_URL = process.env.MONGO_URL || "mongodb://127.0.0.1:27017";
const MONGO_DB = process.env.MONGO_DB || "muawin";
const API_KEY = process.env.CAR_API_KEY || "";
const MAX_POINTS = 1000;

if (API_KEY.length < 16) {
  console.error("CAR_API_KEY must be set and at least 16 characters long.");
  process.exit(1);
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const DEVICE_RE = /^[a-z0-9_-]{1,32}$/i;
const num = (v) => (typeof v === "number" && Number.isFinite(v) ? v : null);

function keyOk(given) {
  const a = Buffer.from(String(given || ""));
  const b = Buffer.from(API_KEY);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

/** Validates one point from the app; returns a document or null. */
function toPointDoc(device, p) {
  if (!p || typeof p !== "object") return null;
  const ts = num(p.ts), lat = num(p.lat), lon = num(p.lon);
  if (ts === null || lat === null || lon === null) return null;
  if (ts < Date.UTC(2020, 0, 1) || ts > Date.now() + 86400000) return null;
  if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
  const date = typeof p.date === "string" && DATE_RE.test(p.date) ? p.date : null;
  if (!date) return null;
  const doc = {
    _id: `${device}:${ts}`,
    device,
    date,
    ts: new Date(ts),
    lat,
    lon,
    loc: { type: "Point", coordinates: [lon, lat] },
  };
  for (const k of ["kmh", "acc", "alt"]) if (num(p[k]) !== null) doc[k] = p[k];
  return doc;
}

async function main() {
  const client = new MongoClient(MONGO_URL);
  await client.connect();
  const db = client.db(MONGO_DB);
  const days = db.collection("days");
  const points = db.collection("points");
  await points.createIndex({ device: 1, ts: 1 });
  await points.createIndex({ device: 1, date: 1 });
  await points.createIndex({ loc: "2dsphere" });
  await days.createIndex({ device: 1, date: 1 }, { unique: true });

  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: "1mb" }));

  app.get("/health", async (_req, res) => {
    try {
      await db.command({ ping: 1 });
      res.json({ ok: true });
    } catch (e) {
      res.status(503).json({ ok: false });
    }
  });

  app.use("/api", (req, res, next) => {
    if (!keyOk(req.get("X-API-Key"))) return res.status(401).json({ error: "bad api key" });
    next();
  });

  app.post("/api/car/sync", async (req, res) => {
    const body = req.body || {};
    const device = typeof body.device === "string" && DEVICE_RE.test(body.device) ? body.device : null;
    if (!device) return res.status(400).json({ error: "device" });
    const raw = Array.isArray(body.points) ? body.points : [];
    if (raw.length > MAX_POINTS) return res.status(413).json({ error: `max ${MAX_POINTS} points per request` });

    const docs = raw.map((p) => toPointDoc(device, p)).filter(Boolean);
    let inserted = 0;
    if (docs.length) {
      try {
        const r = await points.insertMany(docs, { ordered: false });
        inserted = r.insertedCount;
      } catch (e) {
        // Duplicates (an already-uploaded batch) are fine; anything else is a real error.
        if (e.code !== 11000 && !(e.writeErrors || []).every((w) => w.code === 11000)) throw e;
        inserted = e.result ? e.result.insertedCount : (e.insertedCount || 0);
      }
    }

    // Per-day point counts from what was actually inserted, plus first/last time.
    const perDay = new Map();
    for (const d of docs) {
      const cur = perDay.get(d.date) || { first: d.ts, last: d.ts };
      if (d.ts < cur.first) cur.first = d.ts;
      if (d.ts > cur.last) cur.last = d.ts;
      perDay.set(d.date, cur);
    }

    const now = new Date();
    const ops = [];
    for (const [date, span] of perDay) {
      ops.push({
        updateOne: {
          filter: { _id: `${device}:${date}` },
          update: {
            $setOnInsert: { device, date, km: 0 },
            $min: { firstAt: span.first },
            $max: { lastAt: span.last },
            $set: { updatedAt: now },
          },
          upsert: true,
        },
      });
    }
    const day = body.day || {};
    const km = num(day.km);
    if (typeof day.date === "string" && DATE_RE.test(day.date) && km !== null && km >= 0 && km < 3000) {
      // $max: the app's total only grows during a day; a late or repeated upload can't lower it.
      ops.push({
        updateOne: {
          filter: { _id: `${device}:${day.date}` },
          update: { $setOnInsert: { device, date: day.date }, $max: { km }, $set: { updatedAt: now } },
          upsert: true,
        },
      });
    }
    if (ops.length) await days.bulkWrite(ops, { ordered: true });

    // Keep the per-day point count exact (cheap: one count per touched day).
    for (const date of perDay.keys()) {
      const n = await points.countDocuments({ device, date });
      await days.updateOne({ _id: `${device}:${date}` }, { $set: { points: n } });
    }

    res.json({ ok: true, received: raw.length, accepted: docs.length, inserted });
  });

  app.get("/api/car/days", async (req, res) => {
    const q = {};
    const { from, to, device } = req.query;
    if (device) q.device = String(device);
    if (from || to) {
      q.date = {};
      if (from && DATE_RE.test(from)) q.date.$gte = from;
      if (to && DATE_RE.test(to)) q.date.$lte = to;
    }
    const list = await days.find(q).sort({ date: -1 }).limit(366).toArray();
    res.json(list);
  });

  app.get("/api/car/days/:date/points", async (req, res) => {
    const { date } = req.params;
    if (!DATE_RE.test(date)) return res.status(400).json({ error: "date" });
    const q = { date };
    if (req.query.device) q.device = String(req.query.device);
    const list = await points
      .find(q, { projection: { loc: 0 } })
      .sort({ ts: 1 })
      .limit(5000)
      .toArray();
    res.json(list);
  });

  app.use((err, _req, res, _next) => {
    console.error(err);
    res.status(500).json({ error: "server error" });
  });

  app.listen(PORT, () => console.log(`car-api listening on ${PORT} (db ${MONGO_DB})`));
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
