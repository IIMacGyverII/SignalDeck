/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.car;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.RootSetup;

/**
 * Whether Android Auto will show this app in the car. Android Auto only trusts apps installed from the Play Store;
 * for any other app it still plays the sound and takes the media buttons, but leaves it off the car's app list and
 * opens the phone app when its card is tapped, unless its developer setting Unknown sources is on. The setting is
 * read with root from Android Auto's own preferences (the desktop head unit skips this check, a real car does not).
 */
public final class AutoTrust {
    private AutoTrust() { }

    public static final String GEARHEAD = "com.google.android.projection.gearhead";
    public enum State { OK, OFF, LOCKED, NO_AUTO, UNKNOWN }
    private static volatile State last = State.UNKNOWN;
    public static State last() { return last; }

    /** Runs su: call off the main thread. */
    public static State check(Context c) {
        State s;
        PackageManager pm = c.getPackageManager();
        try { pm.getPackageInfo(GEARHEAD, 0); } catch (PackageManager.NameNotFoundException e) { return last = State.NO_AUTO; }
        if (fromPlay(c)) return last = State.OK;
        // the switch is in action_developer_settings.xml; whether the developer settings are unlocked can be in either file
        String dir = "/data/data/" + GEARHEAD + "/shared_prefs/";
        String x = RootSetup.run("cat " + dir + "action_developer_settings.xml 2>/dev/null; echo SPLIT; cat " + dir + "action_developer_settings_temp.xml 2>/dev/null; echo END");
        String main = x.contains("SPLIT") ? x.substring(0, x.indexOf("SPLIT")) : x;
        if (!x.contains("END") || x.contains("su failed")) s = State.UNKNOWN;
        else if (main.contains("name=\"allow_unknown_sources\" value=\"true\"")) s = State.OK;
        else if (x.contains("name=\"developer_settings_enabled\" value=\"true\"")) s = State.OFF;
        else s = State.LOCKED;   // developer settings never unlocked (or no prefs file yet): the switch is off
        android.util.Log.i("AutoTrust", "Android Auto unknown sources: " + s);
        return last = s;
    }

    private static boolean fromPlay(Context c) {
        try {
            String inst = Build.VERSION.SDK_INT >= 30 ? c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getInstallingPackageName()
                    : c.getPackageManager().getInstallerPackageName(c.getPackageName());
            return "com.android.vending".equals(inst);
        } catch (Throwable t) { return false; }
    }

    /** What to do, for the Device warning and the notification. */
    public static String steps(State s) {
        return Txt.s(s == State.LOCKED ? R.string.intercom_autotrust_steps_locked : R.string.intercom_autotrust_steps_off);
    }

    /** Android Auto's settings screen, or its app page when that cannot be opened directly. */
    public static Intent settingsIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(GEARHEAD);
        if (i.resolveActivity(pm) == null) i = pm.getLaunchIntentForPackage(GEARHEAD);
        if (i == null) i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + GEARHEAD));
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** On the phone: Android Auto is hiding the app (posted when the car connects in that state). */
    public static void notifyHidden(Context c, State s) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) nm.createNotificationChannel(new NotificationChannel("car", "Android Auto", NotificationManager.IMPORTANCE_DEFAULT));   // i18n: product name
            String text = steps(s);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, "car").setSmallIcon(R.drawable.ic_stat_radio).setColor(0xFFFFB020)
                    .setContentTitle(Txt.s(R.string.intercom_autotrust_hidden_title)).setContentText(Txt.s(R.string.intercom_autotrust_hidden_text))
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, 0x7A57, settingsIntent(c), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            NotificationManagerCompat.from(c).notify("autotrust", 8, b.build());
        } catch (SecurityException se) { /* notifications not allowed */ }
        catch (Throwable ignored) { }
    }

    /** The radio's transmission knocked out the USB cable (measured on one 2 m frequency in the owner's car). */
    public static void notifyUsbDrop(Context c, String channel, String freq, boolean car) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) nm.createNotificationChannel(new NotificationChannel("car", "Android Auto", NotificationManager.IMPORTANCE_DEFAULT));   // i18n: product name
            String title = Txt.s(car ? R.string.intercom_autotrust_usb_title_car : R.string.intercom_autotrust_usb_title);
            String text = Txt.s(car ? R.string.intercom_autotrust_usb_text_car : R.string.intercom_autotrust_usb_text, channel, freq);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, "car").setSmallIcon(R.drawable.ic_stat_radio).setColor(0xFFFFB020)
                    .setContentTitle(title).setContentText(Txt.s(R.string.intercom_autotrust_usb_short, channel, freq))
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, 0x7A58, new Intent(c, com.macdmr.radio.MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            NotificationManagerCompat.from(c).notify("usbdrop", 9, b.build());
        } catch (SecurityException se) { /* notifications not allowed */ }
        catch (Throwable ignored) { }
    }

    /** Android Auto connected but never bound the media service (the radio is missing from the car this time). */
    public static void notifyNotLoaded(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) nm.createNotificationChannel(new NotificationChannel("car", "Android Auto", NotificationManager.IMPORTANCE_DEFAULT));   // i18n: product name
            String text = Txt.s(R.string.intercom_autotrust_notloaded_text);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, "car").setSmallIcon(R.drawable.ic_stat_radio).setColor(0xFFFFB020)
                    .setContentTitle(Txt.s(R.string.intercom_autotrust_notloaded_title)).setContentText(Txt.s(R.string.intercom_autotrust_notloaded_short))
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setTimeoutAfter(10 * 60_000L)
                    .setContentIntent(PendingIntent.getActivity(c, 0x7A59, new Intent(c, com.macdmr.radio.MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            NotificationManagerCompat.from(c).notify("notloaded", 10, b.build());
        } catch (SecurityException se) { /* notifications not allowed */ }
        catch (Throwable ignored) { }
    }

    public static void clearNotice(Context c) { try { NotificationManagerCompat.from(c).cancel("autotrust", 8); } catch (Throwable ignored) { } }
}
