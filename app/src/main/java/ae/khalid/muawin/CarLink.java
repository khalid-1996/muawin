package ae.khalid.muawin;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps an always-on ADB link to the car's own adbd and runs the "home guard":
 * while المعاون is pinned to a screen, it watches that screen and, as soon as the
 * system's own home appears there (the Home button was pressed), brings المعاون back.
 *
 * One background thread does everything: connect (and reconnect forever with backoff),
 * one-time grants after each connect, then a guard tick every 2 seconds.
 */
public final class CarLink {

    private static CarLink instance;

    public static synchronized CarLink get(Context c) {
        if (instance == null) instance = new CarLink(c.getApplicationContext());
        return instance;
    }

    private static final String SELF = "ae.khalid.muawin";
    private static final Pattern DISPLAY = Pattern.compile("Display #(\\d+)");
    private static final Pattern RESUMED =
            Pattern.compile("ResumedActivity\\s*[:=]\\s*ActivityRecord\\{\\S+ \\S+ ([\\w.]+)/");

    final AdbClient adb;
    private final Context ctx;
    private final SharedPreferences sp;
    private final Object wake = new Object();

    private volatile boolean started = false;
    private volatile String state = "off"; // off | connecting | connected | error
    private volatile String lastError = "";
    private volatile String topOnHome = "";
    private volatile long lastReclaim = 0;
    private volatile boolean showPending = false;
    private volatile String car = "{}";
    private final CarData carData;
    private volatile long lastCarRead = 0;
    private Set<String> systemHomes;

    private CarLink(Context c) {
        ctx = c;
        adb = new AdbClient(c);
        sp = c.getSharedPreferences("link", Context.MODE_PRIVATE);
        carData = new CarData(c.getSharedPreferences("car", Context.MODE_PRIVATE));
    }

    public void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() { loop(); }
        }, "carlink");
        t.setDaemon(true);
        t.start();
    }

    /** Skip the current wait and try to (re)connect now. */
    public void kick() {
        synchronized (wake) { wake.notifyAll(); }
    }

    public boolean connected() { return adb.isConnected(); }

    /** Ask for المعاون to be (re)opened on its pinned screen as soon as ADB allows (car start/wake). */
    public void requestShow() {
        if (!autoStart()) return;
        showPending = true;
        kick();
    }

    public boolean autoStart() { return sp.getBoolean("autostart", true); }

    public void setAutoStart(boolean on) { sp.edit().putBoolean("autostart", on).apply(); }

    public String state() { return adb.isConnected() ? "connected" : state; }

    public String lastError() { return lastError; }

    /** Latest car values as JSON (see CarData); "{}" until ADB has read them. */
    public String car() { return car; }

    // ------------------------------------------------------------- home display

    public int homeDisplay() { return sp.getInt("homeDisplay", -1); }

    public void setHomeDisplay(int id) { sp.edit().putInt("homeDisplay", id).apply(); }

    public boolean guardOn() { return sp.getBoolean("guard", true); }

    public void setGuard(boolean on) { sp.edit().putBoolean("guard", on).apply(); }

    public void markHome(String pkg) {
        Set<String> s = new HashSet<String>(sp.getStringSet("extraHomes", new HashSet<String>()));
        s.add(pkg);
        sp.edit().putStringSet("extraHomes", s).apply();
    }

    public String info() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state());
            o.put("error", lastError);
            o.put("home", homeDisplay());
            o.put("guard", guardOn());
            o.put("autostart", autoStart());
            o.put("top", topOnHome);
            o.put("topIsHome", topOnHome.length() > 0 && isSystemHome(topOnHome));
            JSONArray a = new JSONArray();
            for (String p : homes()) a.put(p);
            o.put("homes", a);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    // ------------------------------------------------------------- loop

    private void loop() {
        long backoff = 3000;
        while (true) {
            try {
                if (!adb.isConnected()) {
                    state = "connecting";
                    adb.connect("127.0.0.1", 5555);
                    state = "connected";
                    lastError = "";
                    backoff = 3000;
                    afterConnect();
                }
                guardTick();
                if (System.currentTimeMillis() - lastCarRead > 15000) {
                    lastCarRead = System.currentTimeMillis();
                    if (!carData.hasCatalog()) loadFidCatalog();
                    car = carData.parse(adb.exec(carData.command())).toString();
                }
                pause(2000);
            } catch (Throwable e) {
                adb.close();
                state = "error";
                lastError = String.valueOf(e.getMessage());
                pause(backoff);
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    private void pause(long ms) {
        synchronized (wake) {
            try { wake.wait(ms); } catch (InterruptedException ignored) {}
        }
    }

    /** Grants that need shell rights; harmless to repeat after every reconnect. */
    private void afterConnect() throws Exception {
        adb.exec("cmd notification allow_listener " + SELF + "/" + SELF + ".MediaListener");
        adb.exec("pm grant " + SELF + " android.permission.ACCESS_FINE_LOCATION");
        adb.exec("pm grant " + SELF + " android.permission.ACCESS_COARSE_LOCATION");
        // Keep the background link alive: no battery optimisation, background running allowed,
        // and "display over other apps" so a direct start works even without ADB later.
        adb.exec("cmd deviceidle whitelist +" + SELF);
        adb.exec("appops set " + SELF + " RUN_IN_BACKGROUND allow");
        adb.exec("appops set " + SELF + " RUN_ANY_IN_BACKGROUND allow");
        adb.exec("appops set " + SELF + " SYSTEM_ALERT_WINDOW allow");
        TripTracker.get(ctx).start();
    }

    /**
     * Reads this car's fid catalog by running FidDump from our own APK under app_process
     * (shell user, where the BYD framework classes can be reflected). Done once per process.
     */
    private void loadFidCatalog() {
        try {
            String apk = ctx.getApplicationInfo().sourceDir;
            String out = adb.exec("CLASSPATH=" + apk + " app_process /system/bin " + SELF + ".FidDump 2>/dev/null");
            carData.loadCatalog(out == null ? "" : out);
        } catch (Exception e) {
            carData.loadCatalog(""); // fall back to the built-in numbers
        }
    }

    private void guardTick() throws Exception {
        int home = homeDisplay();
        if (home < 0) { topOnHome = ""; return; }
        String out = adb.exec("dumpsys activity activities | grep -E 'Display #|ResumedActivity'");
        String pkg = resumedOn(out, home);
        topOnHome = pkg == null ? "" : pkg;
        long now = System.currentTimeMillis();

        // Car started / woke up: open on the pinned screen unless we're already there.
        if (showPending) {
            showPending = false;
            if (!SELF.equals(pkg)) { lastReclaim = now; show(home); }
            return;
        }
        if (!guardOn() || SELF.equals(pkg)) return;
        // Home pressed (system home on our screen) -> come back quickly.
        // Nothing at all on our screen (e.g. after wake) -> open us, but more gently.
        boolean sysHome = pkg != null && isSystemHome(pkg);
        boolean empty = pkg == null && autoStart();
        if (!sysHome && !empty) return;
        if (now - lastReclaim < (sysHome ? 2500 : 10000)) return; // don't hammer if the start fails
        lastReclaim = now;
        show(home);
    }

    private void show(int display) throws Exception {
        adb.exec("am start -n " + SELF + "/.MainActivity --display " + display);
    }

    /** The package resumed on the given display, from `dumpsys activity activities`. */
    static String resumedOn(String out, int display) {
        int cur = -1;
        for (String line : out.split("\n")) {
            Matcher d = DISPLAY.matcher(line);
            if (d.find()) { cur = Integer.parseInt(d.group(1)); continue; }
            if (cur != display) continue;
            Matcher m = RESUMED.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    // ------------------------------------------------------------- home packages

    private synchronized Set<String> homes() {
        if (systemHomes == null) {
            systemHomes = new HashSet<String>();
            PackageManager pm = ctx.getPackageManager();
            for (String cat : new String[]{Intent.CATEGORY_HOME, "android.intent.category.SECONDARY_HOME"}) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(cat);
                try {
                    List<ResolveInfo> l = pm.queryIntentActivities(i, PackageManager.MATCH_ALL);
                    for (ResolveInfo r : l) systemHomes.add(r.activityInfo.packageName);
                } catch (Exception ignored) {
                }
            }
            systemHomes.remove(SELF);
        }
        Set<String> all = new HashSet<String>(systemHomes);
        all.addAll(sp.getStringSet("extraHomes", new HashSet<String>()));
        return all;
    }

    private boolean isSystemHome(String pkg) { return homes().contains(pkg); }
}
