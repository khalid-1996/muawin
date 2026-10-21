package ae.khalid.muawin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Car start (head unit boot) or after an update: start the background link and open المعاون. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LinkService.start(context);
        CarLink link = CarLink.get(context);
        link.start();
        link.requestShow();
    }
}
