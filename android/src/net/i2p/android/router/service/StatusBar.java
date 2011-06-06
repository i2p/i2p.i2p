package net.i2p.android.router.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import net.i2p.android.router.R;
import net.i2p.android.router.activity.MainActivity;

public class StatusBar {

    private final Context ctx;
    private final Intent intent;
    private final Notification notif;
    private final NotificationManager mgr;

    private static final int ID = 1;

    StatusBar(Context cx) {
        ctx = cx;
        String ns = Context.NOTIFICATION_SERVICE;
        mgr = (NotificationManager)ctx.getSystemService(ns);

        int icon = R.drawable.ic_launcher_itoopie;
        String text = "Starting I2P";
        long now = System.currentTimeMillis();
        notif = new Notification(icon, text, now);
        notif.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public void update(String details) {
        String title = "I2P Status";
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        notif.setLatestEventInfo(ctx, title, details, pi);
        mgr.notify(ID, notif);
    }

    public void off(Context ctx) {
        mgr.cancel(ID);
    }
}
