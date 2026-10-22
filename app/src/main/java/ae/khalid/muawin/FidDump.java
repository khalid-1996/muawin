package ae.khalid.muawin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Command-line entry point, run over ADB as the shell user:
 *   CLASSPATH=<this apk> app_process /system/bin ae.khalid.muawin.FidDump
 *
 * Prints the car's own feature-id catalog ("Class.FIELD=value" lines) from the BYD
 * framework classes. Fid numbers differ between models/firmwares, so CarData uses
 * this catalog instead of trusting numbers taken from another car. Hidden-API checks
 * are not active under app_process, so plain reflection works (same approach as BYDMate).
 */
public final class FidDump {
    public static void main(String[] args) {
        String[] targets = {
                "android.hardware.bydauto.BYDAutoFeatureIds",
                "android.hardware.bydauto.BYDAutoConstants",
        };
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String name : targets) {
            Class<?> root;
            try { root = Class.forName(name); } catch (Throwable t) { continue; }
            java.util.List<Class<?>> scan = new java.util.ArrayList<Class<?>>();
            scan.add(root);
            try { java.util.Collections.addAll(scan, root.getDeclaredClasses()); } catch (Throwable ignored) {}
            for (Class<?> c : scan) {
                try {
                    String prefix = c.getSimpleName().isEmpty() ? c.getName() : c.getSimpleName();
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers()) || f.getName().startsWith("$")) continue;
                        Class<?> t = f.getType();
                        if (t != int.class && t != long.class) continue;
                        f.setAccessible(true);
                        out.append(prefix).append('.').append(f.getName()).append('=').append(f.get(null)).append('\n');
                        count++;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        out.append("#count=").append(count).append('\n');
        System.out.print(out);
        System.out.flush();
    }
}
