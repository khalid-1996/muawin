package ae.khalid.muawin;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live car values read from the BYD `autoservice` Binder over ADB:
 * `service call autoservice <tx> i32 <device> i32 <fid>` (tx 5 = int, 7 = float).
 *
 * Addresses and sentinels come from BYDMate (AndyShaman/BYDMate, FidMap/SentinelDecoder),
 * whose notes confirm these compiled addresses answer on a Leopard 5. Every value is
 * range-checked; anything implausible shows as a dash, and the raw reply is kept
 * for the settings page so a wrong field can be spotted and corrected.
 */
final class CarData {

    private static final class Field {
        final String key; final int tx, dev, fid;
        Field(String key, int tx, int dev, int fid) { this.key = key; this.tx = tx; this.dev = dev; this.fid = fid; }
    }

    private static final Field[] FIELDS = {
            new Field("soc",       7, 1014, 1246777400), // Statistic.STATISTIC_ELEC_PERCENTAGE, float %
            new Field("cabin",     5, 1000, 1031798832), // Ac.AC_TEMP_INSIDE, °C
            new Field("outside",   5, 1000, 1077936184), // Ac.AC_TEMP_OUT, °C
            new Field("evRange",   5, 1014, 1246765118), // STATISTIC_ELEC_DRIVING_RANGE, km
            new Field("fuelRange", 5, 1014, 1246773304), // STATISTIC_FUEL_DRIVING_RANGE, km (cockpit range on Leopard 5)
            new Field("fuelPct",   5, 1014, 1246785600), // fuel %, 255 on an EV
    };

    private static final Pattern PARCEL = Pattern.compile("Parcel\\(\\s*00000000\\s+([0-9a-fA-F]{8})");
    private static final int FEATURE_LINK_ERROR = 0x0000FFFF, NOT_INIT_20 = 0x000FFFFF,
            WRONG_TX = -10013, WRONG_DIR = -10011, RANGE_ABSENT = 2046;

    /** One shell line reading every field, each preceded by a marker. */
    static String command() {
        StringBuilder sb = new StringBuilder();
        for (Field f : FIELDS) {
            sb.append("echo @").append(f.key).append("; service call autoservice ")
              .append(f.tx).append(" i32 ").append(f.dev).append(" i32 ").append(f.fid).append("; ");
        }
        return sb.toString();
    }

    /** Parses the output of {@link #command()} into decoded values plus raw words. */
    static JSONObject parse(String out) {
        JSONObject o = new JSONObject();
        JSONObject raw = new JSONObject();
        try {
            for (Field f : FIELDS) {
                String section = section(out, f.key);
                Matcher m = PARCEL.matcher(section);
                if (!m.find()) { raw.put(f.key, section.trim().isEmpty() ? "none" : "err"); continue; }
                int word = (int) Long.parseLong(m.group(1), 16);
                raw.put(f.key, String.format("%08x", word));
                Object v = decode(f, word);
                if (v != null) o.put(f.key, v);
            }
            o.put("raw", raw);
            o.put("at", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String section(String out, String key) {
        int i = out.indexOf("@" + key);
        if (i < 0) return "";
        int start = i + key.length() + 1;
        int next = out.indexOf("\n@", start);
        return next < 0 ? out.substring(start) : out.substring(start, next);
    }

    private static Object decode(Field f, int word) {
        if (word == FEATURE_LINK_ERROR || word == NOT_INIT_20 || word == WRONG_TX || word == WRONG_DIR) return null;
        if (f.tx == 7) {
            float v = Float.intBitsToFloat(word);
            if (Float.isNaN(v) || Float.isInfinite(v) || v == -1f) return null;
            if ("soc".equals(f.key)) return (v >= 0 && v <= 100) ? (double) Math.round(v) : null;
            return (double) v;
        }
        switch (f.key) {
            case "cabin":
            case "outside":
                return (word >= -50 && word <= 80) ? word : null;
            case "evRange":
            case "fuelRange":
                return (word != RANGE_ABSENT && word >= 0 && word < 2000) ? word : null;
            case "fuelPct":
                return (word >= 0 && word <= 100) ? word : null;
            default:
                return word;
        }
    }
}
