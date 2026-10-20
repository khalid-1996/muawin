package ae.khalid.muawin;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.Display;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Everything the web page can ask Android for. Exposed to JS as window.Android. */
public class Bridge {

    private final Activity activity;
    private final WebView web;

    Bridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    // ---------------------------------------------------------------- apps

    /** Launchable apps as JSON: [{pkg, label}]. */
    @JavascriptInterface
    public String apps() {
        JSONArray out = new JSONArray();
        try {
            PackageManager pm = activity.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            String self = activity.getPackageName();
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self)) continue;
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("label", String.valueOf(ri.loadLabel(pm)));
                out.put(o);
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    /** App icon as a PNG data URL (96 px), or "" if unavailable. */
    @JavascriptInterface
    public String icon(String pkg) {
        try {
            Drawable d = activity.getPackageManager().getApplicationIcon(pkg);
            return "data:image/png;base64," + toBase64(drawableToBitmap(d, 96));
        } catch (Exception e) {
            return "";
        }
    }

    /** Opens an app on the same screen this launcher is shown on. */
    @JavascriptInterface
    public void launch(final String pkg) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent i = activity.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) {
                    toast("التطبيق غير متاح");
                    return;
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setLaunchDisplayId(currentDisplay());
                    activity.startActivity(i, o.toBundle());
                } catch (Exception e) {
                    try {
                        activity.startActivity(i);
                    } catch (Exception e2) {
                        toast("تعذر فتح التطبيق");
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------- displays

    @SuppressWarnings("deprecation")
    @JavascriptInterface
    public int currentDisplay() {
        return activity.getWindowManager().getDefaultDisplay().getDisplayId();
    }

    /** All displays as JSON: [{id, name, w, h}]. */
    @JavascriptInterface
    public String displays() {
        JSONArray out = new JSONArray();
        try {
            DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
            for (Display d : dm.getDisplays()) {
                android.graphics.Point p = new android.graphics.Point();
                d.getRealSize(p);
                JSONObject o = new JSONObject();
                o.put("id", d.getDisplayId());
                o.put("name", d.getName());
                o.put("w", p.x);
                o.put("h", p.y);
                out.put(o);
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    /** Re-opens the launcher on another display, then closes this copy. */
    @JavascriptInterface
    public void moveTo(final int displayId) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(activity, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setLaunchDisplayId(displayId);
                    activity.startActivity(i, o.toBundle());
                    activity.finish();
                } catch (SecurityException e) {
                    toast("النظام رفض النقل. افتحه بالأمر: am start -n ae.khalid.muawin/.MainActivity --display " + displayId);
                } catch (Exception e) {
                    toast("تعذر النقل: " + e.getMessage());
                }
            }
        });
    }

    // ---------------------------------------------------------------- media

    private MediaController controller() {
        MediaSessionManager msm = (MediaSessionManager) activity.getSystemService(Context.MEDIA_SESSION_SERVICE);
        List<MediaController> list = msm.getActiveSessions(new ComponentName(activity, MediaListener.class));
        if (list == null || list.isEmpty()) return null;
        for (MediaController c : list) {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return c;
        }
        return list.get(0);
    }

    /** Current media as JSON: {perm, active, title, artist, playing, pos, dur, pkg}. */
    @JavascriptInterface
    public String media() {
        JSONObject o = new JSONObject();
        try {
            MediaController c;
            try {
                c = controller();
                o.put("perm", true);
            } catch (SecurityException se) {
                o.put("perm", false);
                return o.toString();
            }
            if (c == null) {
                o.put("active", false);
                return o.toString();
            }
            o.put("active", true);
            o.put("pkg", c.getPackageName());
            MediaMetadata md = c.getMetadata();
            if (md != null) {
                o.put("title", nz(md.getString(MediaMetadata.METADATA_KEY_TITLE)));
                String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                o.put("artist", nz(artist));
                o.put("dur", md.getLong(MediaMetadata.METADATA_KEY_DURATION));
            }
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            o.put("playing", playing);
            if (st != null) {
                long pos = st.getPosition();
                if (playing && st.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                            * st.getPlaybackSpeed());
                }
                o.put("pos", Math.max(0, pos));
            }
        } catch (Exception e) {
            try {
                o.put("active", false);
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    /** Album art of the current track as a data URL, or "". */
    @JavascriptInterface
    public String mediaArt() {
        try {
            MediaController c = controller();
            if (c == null || c.getMetadata() == null) return "";
            MediaMetadata md = c.getMetadata();
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b == null) return "";
            Bitmap s = Bitmap.createScaledBitmap(b, 168, 168, true);
            return "data:image/png;base64," + toBase64(s);
        } catch (Exception e) {
            return "";
        }
    }

    /** cmd: "toggle" | "next" | "prev". */
    @JavascriptInterface
    public void mediaCmd(String cmd) {
        try {
            MediaController c = controller();
            if (c == null) return;
            MediaController.TransportControls t = c.getTransportControls();
            if ("next".equals(cmd)) {
                t.skipToNext();
            } else if ("prev".equals(cmd)) {
                t.skipToPrevious();
            } else {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause();
                else t.play();
            }
        } catch (Exception ignored) {
        }
    }

    /** Opens the system screen where notification access is granted (may not exist on DiLink). */
    @JavascriptInterface
    public void openMediaPermission() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Exception e) {
                    toast("فعّلها بالأمر: cmd notification allow_listener ae.khalid.muawin/ae.khalid.muawin.MediaListener");
                }
            }
        });
    }

    // ---------------------------------------------------------------- misc

    @JavascriptInterface
    public void toast(final String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    @JavascriptInterface
    public String version() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Bitmap drawableToBitmap(Drawable d, int size) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), size, size, true);
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    private static String toBase64(Bitmap b) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }
}
