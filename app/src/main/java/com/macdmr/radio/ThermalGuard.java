/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import com.macdmr.radio.i18n.Msg;
import com.macdmr.radio.i18n.Txt;
import java.util.Locale;

/**
 * The cool-down guard the bench measured (2026-10-25): a 120 s high-power key heats the phone's AP thermal zone by
 * about 11 °C and it takes about 17 minutes to come back. So after any transmission longer than 30 s, the next one
 * longer than 30 s waits until that zone ({@code mtktsAP} under {@code /sys/class/thermal}, read over su) is back
 * within 1 °C of its value before that key; when the zone cannot be read, a 17 minute timer stands in. Short
 * transmissions are never held. The guard is advice to the sender: it says how long to wait and why, the sender
 * refuses with that text and writes a history line.
 */
public final class ThermalGuard {
    public static final int LONG_MS = 30_000;
    public static final long FALLBACK_MS = 17 * 60_000L;
    public static final double WITHIN_C = 1.0;
    /**
     * The product's stops (owner, 2026-10-25): a long key ends when the AP zone passes 70 C, the battery 48 C, or either
     * climbs 2 C inside 10 s. These sit below where the phone itself throttles (AP zones in the 70s to 80s, Android's
     * battery overheat flag in the mid 50s) and above the bench's own stricter 60 / 45 rules, which stay in the bench runner.
     */
    public static final double ZONE_STOP_C = 70.0, BATTERY_STOP_C = 48.0, RISE_STOP_C = 2.0;
    /** A long key adds about 11 C to the AP zone (bench, 120 s high power) and 2 C to the battery: it does not start closer than that to the stops. */
    public static final double ZONE_START_C = 60.0, BATTERY_START_C = 45.0;

    private volatile int zoneIndex = -1;          // the mtktsAP zone, found once; -2 = none on this phone
    private volatile double beforeC = Double.NaN; // the zone before the last long key
    private volatile long lastLongEnd;            // when the last long key ended (0 = none)
    private volatile int lastLongMs;

    /** The AP zone in °C, or NaN when it cannot be read (no root, no such zone). */
    public double readC() {
        try {
            if (zoneIndex == -2) return Double.NaN;
            if (zoneIndex < 0) {
                String types = RootSetup.run("for z in /sys/class/thermal/thermal_zone*; do echo \"$z $(cat $z/type 2>/dev/null)\"; done");
                int found = -2;
                if (types != null) for (String l : types.split("\n")) { String[] p = l.trim().split("\\s+"); if (p.length == 2 && p[1].equals("mtktsAP")) { try { found = Integer.parseInt(p[0].replaceAll(".*thermal_zone", "")); } catch (Exception ignored) { } } }
                zoneIndex = found;
                if (found < 0) return Double.NaN;
            }
            String t = RootSetup.run("cat /sys/class/thermal/thermal_zone" + zoneIndex + "/temp");
            if (t == null) return Double.NaN;
            String s = t.trim(); int nl = s.indexOf('\n'); if (nl > 0) s = s.substring(0, nl).trim();
            double v = Double.parseDouble(s);
            return v > 1000 ? v / 1000.0 : v;          // millidegrees on this kernel; degrees on some others
        } catch (Throwable e) { return Double.NaN; }
    }

    /** The battery in C from the sticky battery broadcast (tenths of a degree), or NaN. */
    public static double batteryC(android.content.Context c) {
        try {
            android.content.Intent i = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int t = i == null ? -1 : i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1);
            return t < 0 ? Double.NaN : t / 10.0;
        } catch (Throwable e) { return Double.NaN; }
    }

    /** Called before a key that will run longer than 30 s: remembers the zone's value. */
    public void beforeLong() { beforeC = readC(); }
    /** The same with a reading taken earlier: a key whose length was not known at its start (voice) is judged from there. */
    public void beforeLong(double c) { beforeC = c; }

    /**
     * Why a long key must not start now because of where the phone already is (not the cool-down): the AP zone at or
     * over {@link #ZONE_START_C} or the battery at or over {@link #BATTERY_START_C}; null when it may.
     */
    public static String tooHotToStart(double zoneC, double batteryC) { return text(tooHotToStartMsg(zoneC, batteryC)); }
    /** As {@link #tooHotToStart}, as a message (a history line stores its English and key). */
    public static Msg tooHotToStartMsg(double zoneC, double batteryC) {
        if (!Double.isNaN(zoneC) && zoneC >= ZONE_START_C) return Msg.of(R.string.device_thermal_zone_hot, f1(zoneC), f0(ZONE_STOP_C), f0(ZONE_START_C));
        if (!Double.isNaN(batteryC) && batteryC >= BATTERY_START_C) return Msg.of(R.string.device_thermal_battery_hot, f1(batteryC), f0(BATTERY_STOP_C), f0(BATTERY_START_C));
        return null;
    }
    private static String f0(double v) { return String.format(Locale.US, "%.0f", v); }
    private static String f1(double v) { return String.format(Locale.US, "%.1f", v); }
    private static String text(Msg m) { return m == null ? null : m.text(); }

    /**
     * The in-key stop, from the readings a watchdog takes every few seconds during a long key: the reason to end the
     * transmission now, or null. {@code prevZone}/{@code prevBattery} are the readings about 10 s earlier (NaN when none).
     */
    public static String stopNow(double zoneC, double batteryC, double prevZoneC, double prevBatteryC) { return text(stopNowMsg(zoneC, batteryC, prevZoneC, prevBatteryC)); }
    /** As {@link #stopNow}, as a message. */
    public static Msg stopNowMsg(double zoneC, double batteryC, double prevZoneC, double prevBatteryC) {
        if (!Double.isNaN(zoneC) && zoneC > ZONE_STOP_C) return Msg.of(R.string.device_thermal_stop_zone, f0(ZONE_STOP_C), f1(zoneC));
        if (!Double.isNaN(batteryC) && batteryC > BATTERY_STOP_C) return Msg.of(R.string.device_thermal_stop_battery, f0(BATTERY_STOP_C), f1(batteryC));
        if (!Double.isNaN(zoneC) && !Double.isNaN(prevZoneC) && zoneC - prevZoneC > RISE_STOP_C) return Msg.of(R.string.device_thermal_stop_zone_rise, f1(zoneC - prevZoneC));
        if (!Double.isNaN(batteryC) && !Double.isNaN(prevBatteryC) && batteryC - prevBatteryC > RISE_STOP_C) return Msg.of(R.string.device_thermal_stop_battery_rise, f1(batteryC - prevBatteryC));
        return null;
    }

    /** Called after a transmission ends, with its length; only long ones are remembered. */
    public void after(int ms) { if (ms > LONG_MS) { lastLongEnd = System.currentTimeMillis(); lastLongMs = ms; } }

    /**
     * Why a transmission of {@code ms} must wait, or null when it may go now. Never holds a short one. The text is
     * for the user: the zone's excess or the time left on the timer.
     */
    public String gate(int ms) { return gate(ms, Double.NaN); }
    /** As {@link #gate(int)}, with the battery reading when the caller has one (the absolute start gate uses it). */
    public String gate(int ms, double batteryC) { return text(gateMsg(ms, batteryC)); }
    /** As {@link #gate(int, double)}, as a message. */
    public Msg gateMsg(int ms, double batteryC) {
        if (ms <= LONG_MS) return null;
        double now = readC();
        Msg hot = tooHotToStartMsg(now, batteryC);
        if (hot != null) return hot;
        if (lastLongEnd == 0) return null;
        long since = System.currentTimeMillis() - lastLongEnd;
        if (!Double.isNaN(now) && !Double.isNaN(beforeC)) {
            double excess = now - beforeC;
            if (excess <= WITHIN_C) return null;
            return Msg.of(R.string.device_thermal_cooling_zone, lastLongMs / 1000, f1(excess), f1(now));
        }
        if (since >= FALLBACK_MS) return null;
        long left = (FALLBACK_MS - since + 59_999) / 60_000;
        return Msg.of(R.string.device_thermal_cooling_timer, lastLongMs / 1000, left);
    }

    /** For the Device report and the history line: what the guard knows. */
    public String state() {
        double now = readC();
        return (Double.isNaN(now) ? "AP zone unreadable" : String.format(Locale.US, "AP zone %.1f °C", now))   // i18n: Device report / history detail (developer text)
                + (lastLongEnd == 0 ? ", no long transmission yet" : String.format(Locale.US, ", last long key %d s, %d min ago, zone before it %s", lastLongMs / 1000, (System.currentTimeMillis() - lastLongEnd) / 60_000, Double.isNaN(beforeC) ? "unknown" : String.format(Locale.US, "%.1f °C", beforeC)));   // i18n: Device report / history detail (developer text)
    }
}
