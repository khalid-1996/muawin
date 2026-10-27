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

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Kilometres driven today (resets at local midnight), from GPS. While the car moves it also
 * records one location point per minute into {@link CloudSync}'s queue; stopped minutes are
 * skipped. Everything is saved locally first, so nothing is lost without internet.
 */
public final class TripTracker implements LocationListener {

    private static TripTracker instance;

    public static synchronized TripTracker get(Context c) {
        if (instance == null) instance = new TripTracker(c.getApplicationContext());
        return instance;
    }

    private static final long POINT_EVERY_MS = 60_000;
    private static final float MAX_ACCURACY_M = 40f;
    private static final double MAX_SPEED_MS = 70;     // 252 km/h: anything faster is a GPS jump
    private static final float MOVING_SPEED_MS = 1.4f; // 5 km/h
    private static final float MOVING_DISTANCE_M = 30f;

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started = false;
    private Location last;          // last accepted fix, for distance
    private Location lastPoint;     // last logged point, for "is it moving"
    private long lastPointCheck;
    private long lastFixMs;
    private String day;
    private double meters;

    private TripTracker(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("trip", Context.MODE_PRIVATE);
        day = sp.getString("day", today());
        meters = Double.longBitsToDouble(sp.getLong("meters", 0));
        rollDay();
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public synchronized String day() { return day; }

    public synchronized double km() { rollDay(); return meters / 1000.0; }

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
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 3, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            started = false;
        }
    }

    /** New local day: start today's total from zero (yesterday's total was already queued). */
    private synchronized void rollDay() {
        String t = today();
        if (!t.equals(day)) {
            day = t;
            meters = 0;
            last = null;
            save();
        }
    }

    public synchronized void reset() {
        meters = 0;
        last = null;
        save();
    }

    private void save() {
        sp.edit().putString("day", day).putLong("meters", Double.doubleToLongBits(meters)).apply();
    }

    @Override
    public synchronized void onLocationChanged(Location l) {
        rollDay();
        if (l.hasAccuracy() && l.getAccuracy() > MAX_ACCURACY_M) return;
        long now = System.currentTimeMillis();
        lastFixMs = now;
        if (last != null) {
            float d = last.distanceTo(l);
            long dt = l.getTime() - last.getTime();
            if (dt > 0 && d >= 3 && d / (dt / 1000.0) < MAX_SPEED_MS) {
                meters += d;
                save();
            }
        }
        last = l;

        // One point per minute, only while moving.
        if (now - lastPointCheck >= POINT_EVERY_MS) {
            lastPointCheck = now;
            boolean moving = (l.hasSpeed() && l.getSpeed() >= MOVING_SPEED_MS)
                    || (lastPoint != null && lastPoint.distanceTo(l) >= MOVING_DISTANCE_M);
            if (lastPoint == null || moving) {
                lastPoint = l;
                if (moving) CloudSync.get(ctx).addPoint(day, l);
            }
        }
    }

    public synchronized String json() {
        rollDay();
        JSONObject o = new JSONObject();
        try {
            o.put("km", meters / 1000.0);
            o.put("day", day);
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
