package ae.khalid.muawin;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live car values read from the BYD `autoservice` Binder over ADB:
 * `service call autoservice <tx> i32 <device> i32 <fid>` (tx 5 = int, 7 = float).
 *
 * Fid numbers differ between BYD models, so each value is looked up by its symbol in the
 * car's own catalog (see {@link FidDump}); the Leopard 3 numbers from BYDMate stay as a
 * fallback. Each candidate is read and the first plausible answer wins. A value that
 * flickers to a sentinel for a moment keeps its last good reading for a while instead
 * of blanking the screen.
 */
final class CarData {

    private static final class Spec {
        final String key, symbol; final int dev, constFid; final int[] txs;
        Spec(String key, String symbol, int dev, int constFid, int... txs) {
            this.key = key; this.symbol = symbol; this.dev = dev; this.constFid = constFid; this.txs = txs;
        }
    }

    private static final Spec[] SPECS = {
            new Spec("soc",       "Statistic.STATISTIC_ELEC_PERCENTAGE",    1014, 1246777400, 7, 5),
            new Spec("cabin",     "Ac.AC_TEMP_INSIDE",                      1000, 1031798832, 5),
            new Spec("outside",   "Ac.AC_TEMP_OUT",                         1000, 1077936184, 5),
            new Spec("evRange",   "Statistic.STATISTIC_ELEC_DRIVING_RANGE", 1014, 1246765118, 5),
            new Spec("fuelRange", "Statistic.STATISTIC_FUEL_DRIVING_RANGE", 1014, 1246773304, 5),
            new Spec("fuelPct",   "Statistic.STATISTIC_FUEL_PERCENTAGE",    1014, 1246785600, 5),
    };

    /** One address to try: transact code + fid, and where the fid came from. */
    private static final class Cand {
        final int tx, fid; final String src;
        Cand(int tx, int fid, String src) { this.tx = tx; this.fid = fid; this.src = src; }
    }

    private static final Pattern PARCEL = Pattern.compile("Parcel\\(\\s*00000000\\s+([0-9a-fA-F]{8})");
    private static final int FEATURE_LINK_ERROR = 0x0000FFFF, NOT_INIT_20 = 0x000FFFFF,
            WRONG_TX = -10013, WRONG_DIR = -10011, RANGE_ABSENT = 2046;
    private static final long HOLD_MS = 90_000;

    private final Map<String, Integer> catalog = new HashMap<String, Integer>();
    private int catalogSize = -1; // -1 = not read yet
    private final Map<String, Object> lastValue = new HashMap<String, Object>();
    private final Map<String, Long> lastAt = new HashMap<String, Long>();

    // ----------------------------------------------------------------- catalog

    boolean hasCatalog() { return catalogSize >= 0; }

    /** Loads the output of FidDump ("Class.FIELD=value" lines). */
    synchronized void loadCatalog(String dump) {
        catalog.clear();
        for (String line : dump.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("#")) continue;
            try { catalog.put(line.substring(0, eq).trim(), (int) Long.parseLong(line.substring(eq + 1).trim())); }
            catch (Exception ignored) {}
        }
        catalogSize = catalog.size();
    }

    private List<Cand> candidates(Spec s) {
        List<Cand> l = new ArrayList<Cand>();
        Integer cat = catalog.get(s.symbol);
        for (int tx : s.txs) {
            if (cat != null) l.add(new Cand(tx, cat, "catalog"));
            if (cat == null || cat != s.constFid) l.add(new Cand(tx, s.constFid, "const"));
        }
        return l;
    }

    // ----------------------------------------------------------------- read

    /** One shell line reading every candidate of every value, each preceded by a marker. */
    synchronized String command() {
        StringBuilder sb = new StringBuilder();
        for (Spec s : SPECS) {
            List<Cand> cs = candidates(s);
            for (int i = 0; i < cs.size(); i++) {
                Cand c = cs.get(i);
                sb.append("echo @").append(s.key).append('.').append(i)
                  .append("; service call autoservice ").append(c.tx)
                  .append(" i32 ").append(s.dev).append(" i32 ").append(c.fid).append("; ");
            }
        }
        return sb.toString();
    }

    /** Parses the output of {@link #command()}; returns current values (held briefly if a read blinks). */
    synchronized JSONObject parse(String out) {
        JSONObject o = new JSONObject();
        JSONObject raw = new JSONObject();
        long now = System.currentTimeMillis();
        try {
            for (Spec s : SPECS) {
                List<Cand> cs = candidates(s);
                Object value = null;
                StringBuilder seen = new StringBuilder();
                for (int i = 0; i < cs.size() && value == null; i++) {
                    Cand c = cs.get(i);
                    Matcher m = PARCEL.matcher(section(out, s.key + "." + i));
                    String word = m.find() ? m.group(1) : null;
                    if (seen.length() > 0) seen.append(',');
                    seen.append("catalog".equals(c.src) ? "cat" : "def").append(c.tx).append(':').append(word == null ? "err" : word);
                    if (word == null) continue;
                    value = decode(s.key, c.tx, (int) Long.parseLong(word, 16));
                    if (value != null) seen.append('✓');
                }
                raw.put(s.key, seen.toString());
                if (value != null) {
                    lastValue.put(s.key, value);
                    lastAt.put(s.key, now);
                }
                Long at = lastAt.get(s.key);
                if (at != null && now - at < HOLD_MS) o.put(s.key, lastValue.get(s.key));
            }
            raw.put("catalog", catalogSize < 0 ? "?" : String.valueOf(catalogSize));
            o.put("raw", raw);
            o.put("at", now);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String section(String out, String marker) {
        String tag = "@" + marker + "\n";
        int i = out.indexOf(tag);
        if (i < 0) {
            tag = "@" + marker + "\r\n";
            i = out.indexOf(tag);
            if (i < 0) return "";
        }
        int start = i + tag.length();
        int next = out.indexOf("\n@", start);
        return next < 0 ? out.substring(start) : out.substring(start, next);
    }

    private static Object decode(String key, int tx, int word) {
        if (word == FEATURE_LINK_ERROR || word == NOT_INIT_20 || word == WRONG_TX || word == WRONG_DIR) return null;
        if (tx == 7) {
            float v = Float.intBitsToFloat(word);
            if (Float.isNaN(v) || Float.isInfinite(v) || v == -1f) return null;
            if ("soc".equals(key)) return (v > 0 && v <= 100) ? (int) Math.round(v) : null;
            return null; // only the battery is read as a float
        }
        switch (key) {
            case "soc":
                return (word > 0 && word <= 100) ? word : null;
            case "cabin":
            case "outside":
                return (word >= -50 && word <= 80) ? word : null;
            case "evRange":
            case "fuelRange":
                return (word != RANGE_ABSENT && word >= 0 && word < 2000) ? word : null;
            case "fuelPct":
                return (word >= 0 && word <= 100) ? word : null;
            default:
                return null;
        }
    }
}
