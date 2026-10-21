package ae.khalid.muawin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

/**
 * Keeps المعاون's process alive in the background (DiLink kills idle apps), so the
 * ADB link, home guard and trip tracking survive, and reopens المعاون on its screen
 * whenever the car wakes up (screen on / unlocked).
 */
public class LinkService extends Service {

    private static final String CHANNEL = "link";

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, LinkService.class));
        } catch (Exception ignored) {
            // Background-start limits on some builds; the activity starts it on open.
        }
    }

    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            CarLink.get(context).requestShow();
            TripTracker.get(context).start();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "المعاون", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("المعاون يعمل")
                .setOngoing(true)
                .build();
        startForeground(1, n);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(wake, f);

        CarLink.get(this).start();
        TripTracker.get(this).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // restart if the system kills us
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(wake); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
