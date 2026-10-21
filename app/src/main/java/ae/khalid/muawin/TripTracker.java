package ae.khalid.muawin;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Distance of the current drive, from GPS. A new trip starts on every car start:
 * when the head unit rebooted (boot count changed) or after a long silence
 * (the head unit sleeps when the car is off, so heartbeats stop).
 */
public final class TripTracker implements LocationListener {

    private static TripTracker instance;

    public static synchronized TripTracker get(Context c) {
        if (instance == null) instance = new TripTracker(c.getApplicationContext());
        return instance;
    }

    private static final long NEW_TRIP_GAP_MS = 20 * 60 * 1000; // car off for 20+ min = new trip
    private static final long BEAT_MS = 60 * 1000;
    private static final float MAX_ACCURACY_M = 40f;
    private static final double MAX_SPEED_MS = 70; // 252 km/h: anything faster is a GPS jump

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started = false;
    private Location last;
    private double meters;
    private long startMs;
    private long lastFixMs;

    private TripTracker(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("trip", Context.MODE_PRIVATE);
        meters = Double.longBitsToDouble(sp.getLong("meters", 0));
        startMs = sp.getLong("start", System.currentTimeMillis());
    }

    /** Safe to call repeatedly; does nothing until location permission is granted. */
    public void start() {
        main.post(new Runnable() {
            @Override
            public void run() { startOnMain(); }
        });
    }

    private void startOnMain() {
        if (started) return;
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        started = true;
        rollIfNewDrive();
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 3, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            started = false;
            return;
        }
        main.postDelayed(beat, BEAT_MS);
    }

    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            rollIfNewDrive();
            main.postDelayed(this, BEAT_MS);
        }
    };

    /** Starts a new trip if the head unit rebooted or was asleep long enough; then records a heartbeat. */
    private void rollIfNewDrive() {
        long now = System.currentTimeMillis();
        int boot = Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        long lastBeat = sp.getLong("beat", 0);
        if (boot != sp.getInt("boot", Integer.MIN_VALUE) || now - lastBeat > NEW_TRIP_GAP_MS) {
            reset();
            sp.edit().putInt("boot", boot).apply();
        }
        sp.edit().putLong("beat", now).apply();
    }

    public synchronized void reset() {
        meters = 0;
        startMs = System.currentTimeMillis();
        last = null;
        save();
    }

    private void save() {
        sp.edit().putLong("meters", Double.doubleToLongBits(meters)).putLong("start", startMs).apply();
    }

    @Override
    public synchronized void onLocationChanged(Location l) {
        if (l.hasAccuracy() && l.getAccuracy() > MAX_ACCURACY_M) return;
        lastFixMs = System.currentTimeMillis();
        if (last != null) {
            float d = last.distanceTo(l);
            long dt = l.getTime() - last.getTime();
            if (dt > 0 && d >= 3 && d / (dt / 1000.0) < MAX_SPEED_MS) {
                meters += d;
                save();
            }
        }
        last = l;
    }

    public synchronized String json() {
        JSONObject o = new JSONObject();
        try {
            o.put("km", meters / 1000.0);
            o.put("start", startMs);
            o.put("elapsed", System.currentTimeMillis() - startMs);
            o.put("tracking", started);
            o.put("fix", lastFixMs > 0 && System.currentTimeMillis() - lastFixMs < 15000);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
}
