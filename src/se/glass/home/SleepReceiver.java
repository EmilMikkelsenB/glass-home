package se.glass.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Sleep timer: when the alarm fires, ask Home Assistant to turn this TV off (`tv` in ha.json). */
public class SleepReceiver extends BroadcastReceiver {
    /** Schedule (minutes > 0) or cancel (0) the sleep timer. */
    static void set(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, SleepReceiver.class), PendingIntent.FLAG_IMMUTABLE);
        long at = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        if (at > 0) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);   // inexact is fine for a sleep timer
        else am.cancel(pi);
        new Settings(c).setSleepAt(at);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        Settings s = new Settings(c);
        s.setSleepAt(0);
        HomeAssistant ha = HomeAssistant.load(c, s);
        if (ha == null || ha.tv == null) return;
        PendingResult done = goAsync();
        new Thread(() -> {
            try {
                ha.call("media_player", "turn_off", ha.tv);
                Log.i("GlassHome", "sleep timer: turned off " + ha.tv);
            } catch (Exception e) {
                Log.w("GlassHome", "sleep timer", e);
            } finally {
                done.finish();
            }
        }).start();
    }
}
