package ae.khalid.muawin;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends driving data to the user's own server (car-api in front of MongoDB):
 * today's kilometres and the per-minute location points.
 *
 * Points are appended to a local queue file first and removed only after the server
 * confirms them, so drives without internet are uploaded later. Every minute, if a
 * server is configured: POST {device, day:{date, km}, points:[...]} to <url>/api/car/sync.
 */
public final class CloudSync {

    private static CloudSync instance;

    public static synchronized CloudSync get(Context c) {
        if (instance == null) instance = new CloudSync(c.getApplicationContext());
        return instance;
    }

    private static final int BATCH = 300;
    private static final int MAX_QUEUE = 20000; // ~2 weeks of driving; oldest dropped beyond that
    private static final long EVERY_MS = 60_000;

    private final Context ctx;
    private final SharedPreferences sp;
    private final File queue;
    private boolean started = false;
    private volatile String status = "";
    private volatile long lastOk = 0;

    private CloudSync(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("cloud", Context.MODE_PRIVATE);
        queue = new File(c.getFilesDir(), "points.jsonl");
    }

    // ------------------------------------------------------------- settings

    public String url() { return sp.getString("url", ""); }

    public String key() { return sp.getString("key", ""); }

    public void configure(String url, String key) {
        url = url == null ? "" : url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        sp.edit().putString("url", url).putString("key", key == null ? "" : key.trim()).apply();
        status = url.isEmpty() ? "" : "سيتم الإرسال خلال دقيقة";
    }

    public String info() {
        JSONObject o = new JSONObject();
        try {
            o.put("url", url());
            o.put("hasKey", !key().isEmpty());
            o.put("queued", countLines());
            o.put("status", status);
            o.put("lastOk", lastOk);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    // ------------------------------------------------------------- queue

    synchronized void addPoint(String day, Location l) {
        try {
            JSONObject p = new JSONObject();
            p.put("ts", l.getTime());
            p.put("date", day);
            p.put("lat", l.getLatitude());
            p.put("lon", l.getLongitude());
            if (l.hasSpeed()) p.put("kmh", Math.round(l.getSpeed() * 3.6 * 10) / 10.0);
            if (l.hasAccuracy()) p.put("acc", Math.round(l.getAccuracy()));
            if (l.hasAltitude()) p.put("alt", Math.round(l.getAltitude()));
            FileOutputStream os = new FileOutputStream(queue, true);
            try { os.write((p.toString() + "\n").getBytes("UTF-8")); } finally { os.close(); }
        } catch (Exception ignored) {
        }
    }

    private synchronized List<String> readLines(int max) {
        List<String> out = new ArrayList<String>();
        if (!queue.exists()) return out;
        try {
            BufferedReader r = new BufferedReader(new FileReader(queue));
            try {
                String line;
                while ((line = r.readLine()) != null && out.size() < max) if (!line.isEmpty()) out.add(line);
            } finally { r.close(); }
        } catch (Exception ignored) {
        }
        return out;
    }

    private synchronized int countLines() {
        return readLines(Integer.MAX_VALUE).size();
    }

    /** Removes the first n lines (sent and confirmed) and trims the queue to MAX_QUEUE. */
    private synchronized void dropFirst(int n) {
        List<String> all = readLines(Integer.MAX_VALUE);
        List<String> rest = all.subList(Math.min(n, all.size()), all.size());
        if (rest.size() > MAX_QUEUE) rest = rest.subList(rest.size() - MAX_QUEUE, rest.size());
        try {
            FileOutputStream os = new FileOutputStream(queue, false);
            try { for (String l : rest) os.write((l + "\n").getBytes("UTF-8")); } finally { os.close(); }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- upload loop

    public synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try { syncOnce(); } catch (Throwable ignored) {}
                    try { Thread.sleep(EVERY_MS); } catch (InterruptedException ignored) {}
                }
            }
        }, "cloud-sync");
        t.setDaemon(true);
        t.start();
    }

    /** Sends one batch (today's total plus up to BATCH queued points). */
    void syncOnce() {
        String url = url();
        if (url.isEmpty()) return;
        TripTracker trip = TripTracker.get(ctx);
        List<String> lines = readLines(BATCH);
        try {
            JSONArray points = new JSONArray();
            for (String l : lines) points.put(new JSONObject(l));
            JSONObject day = new JSONObject();
            day.put("date", trip.day());
            day.put("km", Math.round(trip.km() * 100) / 100.0);
            JSONObject body = new JSONObject();
            body.put("device", "leopard5");
            body.put("day", day);
            body.put("points", points);

            int code = post(url + "/api/car/sync", body.toString());
            if (code >= 200 && code < 300) {
                dropFirst(lines.size());
                lastOk = System.currentTimeMillis();
                status = "تمت المزامنة";
            } else if (code == 401 || code == 403) {
                status = "المفتاح غير صحيح (" + code + ")";
            } else {
                status = "رد السيرفر " + code;
            }
        } catch (Exception e) {
            status = "لا يوجد اتصال بالسيرفر";
        }
    }

    private int post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("X-API-Key", key());
        try {
            OutputStream os = c.getOutputStream();
            try { os.write(json.getBytes("UTF-8")); } finally { os.close(); }
            int code = c.getResponseCode();
            try {
                InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                if (is != null) is.close();
            } catch (Exception ignored) {
            }
            return code;
        } finally {
            c.disconnect();
        }
    }
}
