package ae.khalid.muawin;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the system lets us read the active media sessions
 * (MediaSessionManager.getActiveSessions needs an enabled notification listener).
 * Grant once over ADB:
 *   cmd notification allow_listener ae.khalid.muawin/ae.khalid.muawin.MediaListener
 */
public class MediaListener extends NotificationListenerService {
}
