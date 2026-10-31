/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.macdmr.radio.i18n.Msg;
import com.macdmr.radio.i18n.Txt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Scheduled operations: listen to a channel (and record it), scan, APRS monitoring, SSTV receive or an APRS beacon at a
 * set time, once or on chosen weekdays, for a set number of minutes. One Android alarm is armed for the next start or
 * end (exact when the system allows it; SignalDeck asks for that with root), so it runs with the screen off. When the
 * time is up the radio goes back to what it was doing. A satellite pass being tracked and a transmission in progress
 * always win: the operation is skipped (or, while talking, started 20 s later) with a history line.
 */
public final class Scheduler {
    public static final String LISTEN = "listen", SCAN = "scan", APRS = "aprs", SSTV = "sstv", BEACON = "beacon", WINLINK = "winlink", WINLINK_NET = "winlinknet", FINDER = "finder";
    public static final String[] ACTIONS = { LISTEN, SCAN, APRS, SSTV, BEACON, WINLINK, WINLINK_NET, FINDER };
    /** The action names' strings, in {@link #ACTIONS} order. */
    static final int[] ACTION_NAME_IDS = { R.string.service_sched_listen, R.string.service_sched_scan, R.string.service_sched_aprs, R.string.service_sched_sstv, R.string.service_sched_beacon, R.string.service_sched_winlink_radio, R.string.service_sched_winlink_net, R.string.service_sched_finder };
    /** The action names in the language at start-up; {@link Entry#actionNames} and {@link Entry#actionName} read them in the language now. */
    public static final String[] ACTION_NAMES = names();
    private static String[] names() { String[] n = new String[ACTION_NAME_IDS.length]; for (int i = 0; i < n.length; i++) n[i] = Txt.s(ACTION_NAME_IDS[i]); return n; }
    /** Actions that use a channel of their own (the rest work on the current one). */
    public static boolean usesChannel(String a) { return LISTEN.equals(a) || SSTV.equals(a) || WINLINK.equals(a); }
    /** Whether the radio of {@code dp} can run the action at all: APRS monitoring parks on 144.390, which a UHF-only radio (the W6) does not tune. */
    public static boolean available(DeviceProfile dp, String a) { return available(dp, a, PacketEngine.APRS_FREQ_HZ); }
    /** {@code aprsHz}: the region's APRS frequency (Device → Region), where APRS monitoring parks the radio. */
    public static boolean available(DeviceProfile dp, String a, int aprsHz) { return !APRS.equals(a) || dp.tunable(aprsHz); }
    /** Why the radio of {@code dp} cannot run the entry ({@code ch}: its channel, null for none or the current one), or null when it can. */
    public static String bandProblem(DeviceProfile dp, Entry e, ChannelStore.Channel ch) { return bandProblem(dp, e, ch, PacketEngine.APRS_FREQ_HZ); }
    public static String bandProblem(DeviceProfile dp, Entry e, ChannelStore.Channel ch, int aprsHz) {
        if (!available(dp, e.action, aprsHz)) return Txt.s(R.string.service_sched_no_aprs, dp.bandsText(), com.macdmr.radio.region.Region.mhzShown(aprsHz));
        if (usesChannel(e.action) && ch != null && !ChannelStore.tunable(dp, ch)) return Txt.s(R.string.service_sched_channel_outside, ch.name, dp.bandsText());
        return null;
    }

    public static final class Entry {
        public int id; public String action = LISTEN, label = ""; public int hour = 19, minute = 0;
        public int days;                // bit 0 Monday … bit 6 Sunday; 0 = once, on {@link #date}
        public String date = "";        // yyyy-MM-dd when once
        public int minutes = 60, channel = -1; public boolean record, enabled = true;
        JSONObject json() throws Exception { return new JSONObject().put("id", id).put("action", action).put("label", label).put("hour", hour).put("minute", minute).put("days", days).put("date", date).put("minutes", minutes).put("channel", channel).put("record", record).put("enabled", enabled); }
        static Entry of(JSONObject o) { Entry e = new Entry(); e.id = o.optInt("id"); e.action = o.optString("action", LISTEN); e.label = o.optString("label", ""); e.hour = o.optInt("hour", 19); e.minute = o.optInt("minute"); e.days = o.optInt("days"); e.date = o.optString("date", ""); e.minutes = o.optInt("minutes", 60); e.channel = o.optInt("channel", -1); e.record = o.optBoolean("record"); e.enabled = o.optBoolean("enabled", true); return e; }
        public boolean oneShot() { return days == 0; }
        public boolean hasDuration() { return !BEACON.equals(action) && !WINLINK.equals(action) && !WINLINK_NET.equals(action); }
        /** The names with the APRS frequency in use, e.g. "APRS monitoring (144.800)". */
        public static String[] actionNames(int aprsHz) { String[] n = names(); n[2] = Txt.s(R.string.service_sched_aprs_freq, com.macdmr.radio.region.Region.mhzShown(aprsHz)); return n; }
        public String actionName() { for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(action)) return Txt.s(ACTION_NAME_IDS[i]); return action; }
        /** The action's name as a message (history lines keep it in English and show it in the app's language); an unknown action as it is. */
        public Object actionMsg() { for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(action)) return Msg.of(ACTION_NAME_IDS[i]); return action; }
        /** "Tue, Thu 19:00 for 60 min" / "23 Oct 19:00". */
        public String when() {
            StringBuilder b = new StringBuilder();
            if (oneShot()) b.append(date.isEmpty() ? Txt.s(R.string.service_sched_once) : date);
            else if (days == 0x7F) b.append(Txt.s(R.string.service_sched_every_day));
            else if (days == 0x1F) b.append(Txt.s(R.string.service_sched_weekdays));
            else if (days == 0x60) b.append(Txt.s(R.string.service_sched_weekends));
            else { int[] n = { R.string.service_sched_mon, R.string.service_sched_tue, R.string.service_sched_wed, R.string.service_sched_thu, R.string.service_sched_fri, R.string.service_sched_sat, R.string.service_sched_sun }; for (int i = 0; i < 7; i++) if ((days & (1 << i)) != 0) b.append(b.length() > 0 ? Txt.s(R.string.service_sched_day_sep) : "").append(Txt.s(n[i])); }
            String time = String.format(Locale.US, "%02d:%02d", hour, minute);
            return hasDuration() ? Txt.s(R.string.service_sched_when_for, b.toString(), time, minutes) : Txt.s(R.string.service_sched_when, b.toString(), time);
        }
    }

    /** The next start at or after {@code now} (ms), or -1 (a one-shot in the past, or disabled). Pure: host-tested. */
    public static long nextStart(Entry e, long now, java.util.TimeZone tz) {
        if (!e.enabled) return -1;
        Calendar c = Calendar.getInstance(tz, Locale.US);
        if (e.oneShot()) {
            try {
                String[] p = e.date.split("-"); c.clear(); c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), e.hour, e.minute, 0);
                return c.getTimeInMillis() >= now - 1000 ? c.getTimeInMillis() : -1;
            } catch (Throwable t) { return -1; }
        }
        c.setTimeInMillis(now); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        for (int d = 0; d < 8; d++) {
            Calendar t = (Calendar) c.clone(); t.add(Calendar.DAY_OF_MONTH, d); t.set(Calendar.HOUR_OF_DAY, e.hour); t.set(Calendar.MINUTE, e.minute);
            int dow = (t.get(Calendar.DAY_OF_WEEK) + 5) % 7;                    // Calendar: Sunday 1 … Saturday 7 → Monday 0 … Sunday 6
            if ((e.days & (1 << dow)) != 0 && t.getTimeInMillis() >= now - 1000) return t.getTimeInMillis();
        }
        return -1;
    }

    // ---- storage ----
    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("schedules", Context.MODE_PRIVATE); }
    public static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs(c).getString("list", "[]")); for (int i = 0; i < a.length(); i++) out.add(Entry.of(a.getJSONObject(i))); } catch (Throwable ignored) { }
        return out;
    }
    public static synchronized void save(Context c, List<Entry> l) {
        try { JSONArray a = new JSONArray(); for (Entry e : l) a.put(e.json()); prefs(c).edit().putString("list", a.toString()).apply(); } catch (Throwable ignored) { }
        arm(c);
    }
    public static synchronized Entry put(Context c, Entry e) {
        List<Entry> l = list(c);
        if (e.id <= 0) { int max = 0; for (Entry x : l) max = Math.max(max, x.id); e.id = max + 1; l.add(e); }
        else { boolean found = false; for (int i = 0; i < l.size(); i++) if (l.get(i).id == e.id) { l.set(i, e); found = true; } if (!found) l.add(e); }
        save(c, l); return e;
    }
    public static synchronized void remove(Context c, int id) { List<Entry> l = list(c); l.removeIf(x -> x.id == id); save(c, l); }
    public static Entry byId(Context c, int id) { for (Entry e : list(c)) if (e.id == id) return e; return null; }
    public static JSONArray json(Context c) {
        JSONArray a = new JSONArray(); long now = System.currentTimeMillis();
        for (Entry e : list(c)) try { a.put(e.json().put("when", e.when()).put("actionName", e.actionName()).put("next", nextStart(e, now, java.util.TimeZone.getDefault()))); } catch (Throwable ignored) { }
        return a;
    }

    // ---- the alarm: the next start, or the end of the one running ----
    static final String ACTION_FIRE = "com.macdmr.radio.SCHEDULE";   // i18n: intent action
    public static long runningEnd(Context c) { return prefs(c).getLong("runningEnd", 0); }
    public static int runningId(Context c) { return prefs(c).getInt("runningId", 0); }
    static void setRunning(Context c, int id, long end) { prefs(c).edit().putInt("runningId", id).putLong("runningEnd", end).apply(); }

    public static void arm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); if (am == null) return;
            long now = System.currentTimeMillis(), when = Long.MAX_VALUE; int id = 0; boolean end = false;
            for (Entry e : list(c)) { long t = nextStart(e, now + 1000, java.util.TimeZone.getDefault()); if (t > 0 && t < when) { when = t; id = e.id; } }
            long re = runningEnd(c); if (re > 0 && re < when) { when = Math.max(re, now + 1000); id = runningId(c); end = true; }
            PendingIntent pi = PendingIntent.getBroadcast(c, 4711, new Intent(c, Receiver.class).setAction(ACTION_FIRE).putExtra("id", id).putExtra("end", end), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
            if (when == Long.MAX_VALUE) return;
            boolean exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (!exact && RootSetup.haveRoot()) { RootSetup.run("appops set " + c.getPackageName() + " SCHEDULE_EXACT_ALARM allow"); exact = am.canScheduleExactAlarms(); }   // root: grant it ourselves   // i18n: shell
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi); else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (Throwable t) { android.util.Log.w("Scheduler", "arm: " + t); }
    }

    /** The alarm: hands the start or the end to the radio service (which runs while SignalDeck owns the radio). */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int id = i.getIntExtra("id", 0); boolean end = i.getBooleanExtra("end", false);
            RadioService s = RadioService.running;
            if (s != null) { if (end) s.endSchedule(id); else s.runSchedule(id); }
            else android.util.Log.w("Scheduler", "schedule " + id + ": SignalDeck does not own the radio right now");
            if (!end) { Entry e = byId(c, id); if (e != null && e.oneShot()) { e.enabled = false; put(c, e); return; } }
            arm(c);
        }
    }
}
