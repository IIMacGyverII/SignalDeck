/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.web;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthTdscdma;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import org.json.JSONObject;

import java.util.List;

/**
 * The phone's own state for SignalDeck Web's Phone panel: battery (level, charging, current, voltage, temperature, how
 * fast the level moves and when it will be full or empty), the cell signal and the Wi-Fi link. The battery is sampled
 * once a minute while the web server runs, so the rate covers the last half hour even if no browser was open.
 * No permission beyond ACCESS_WIFI_STATE; the Wi-Fi name needs location (SignalDeck has it for APRS) and is only shown
 * to the logged-in browser.
 */
final class PhoneStats {
    static final int KEEP = 180;                       // 3 h of one-minute samples
    static final long SAMPLE_MS = 60_000, WINDOW_MS = 30 * 60_000L, MIN_SPAN_MS = 5 * 60_000L;

    private final Context app;
    private final long[] when = new long[KEEP];
    private final double[] level = new double[KEEP];  // percent, finer than the 1 % steps when the charge counter is known
    private int count, head; private long lastSample; private boolean lastCharging;

    PhoneStats(Context app) { this.app = app.getApplicationContext(); }

    /** Called from the web ticker; takes a sample when a minute has passed (or the charger was plugged in or out). */
    synchronized void sampleIfDue() {
        Intent b = battery(); if (b == null) return;
        long now = SystemClock.elapsedRealtime(); boolean charging = charging(b);
        if (count > 0 && charging != lastCharging) { count = 0; head = 0; }      // a new trend starts with the charger
        if (count > 0 && now - lastSample < SAMPLE_MS && charging == lastCharging) return;
        double pct = percent(b); if (pct < 0) return;
        when[head] = now; level[head] = pct; head = (head + 1) % KEEP; if (count < KEEP) count++;
        lastSample = now; lastCharging = charging;
    }

    /** Percent per hour over the last half hour (least squares), or NaN when the samples span less than 5 minutes. */
    static double ratePerHour(long[] t, double[] v, int n, long now) {
        double st = 0, sv = 0, stt = 0, stv = 0; int k = 0; long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (now - t[i] > WINDOW_MS) continue;
            double x = (t[i] - now) / 3_600_000.0; st += x; sv += v[i]; stt += x * x; stv += x * v[i]; k++;
            first = Math.min(first, t[i]); last = Math.max(last, t[i]);
        }
        if (k < 2 || last - first < MIN_SPAN_MS) return Double.NaN;
        double d = k * stt - st * st; if (d == 0) return Double.NaN;
        return (k * stv - st * sv) / d;
    }

    /** Minutes until 100 % (charging) or 0 % (discharging) at this rate, or -1. */
    static int minutesLeft(double pct, double perHour, boolean charging) {
        if (Double.isNaN(perHour) || pct < 0) return -1;
        if (charging) return perHour > 0.2 && pct < 100 ? (int) Math.round((100 - pct) / perHour * 60) : -1;
        return perHour < -0.2 ? (int) Math.round(pct / -perHour * 60) : -1;
    }

    synchronized JSONObject json() throws Exception {
        JSONObject o = new JSONObject();
        o.put("battery", batteryJson());
        o.put("cell", cellJson());
        o.put("wifi", wifiJson());
        return o;
    }

    private Intent battery() { try { return app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); } catch (Throwable t) { return null; } }
    private static boolean charging(Intent b) {
        int s = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL;
    }
    private double percent(Intent b) {
        int l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (l < 0 || sc <= 0) return -1;
        return 100.0 * l / sc;
    }

    private JSONObject batteryJson() throws Exception {
        JSONObject o = new JSONObject();
        Intent b = battery(); if (b == null) return o.put("ok", false);
        double pct = percent(b); boolean charging = charging(b);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1), plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        o.put("ok", true).put("level", pct < 0 ? -1 : Math.round(pct));
        o.put("state", status == BatteryManager.BATTERY_STATUS_FULL ? "full" : status == BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                : plugged != 0 ? "plugged, not charging" : "on battery");   // i18n: state code: the page translates it (and compares charging, full)
        o.put("plug", plugged == BatteryManager.BATTERY_PLUGGED_AC ? "charger" : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless" : plugged == 8 ? "dock" : "");
        int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE); if (t != Integer.MIN_VALUE) o.put("tempC", t / 10.0);
        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1); if (mv > 0) o.put("volts", (mv > 100 ? mv / 1000.0 : mv));
        o.put("health", health(b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)));
        BatteryManager bm = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int ma = currentMa(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), charging);
            if (ma != Integer.MIN_VALUE) o.put("currentMa", ma);
            int cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (cc != Integer.MIN_VALUE && cc / 1000 >= 100) o.put("chargeMah", Math.round(cc / 1000.0));   // some phones report it in another unit: only a plausible value (≥ 100 mAh) is shown
        }
        double rate = ratePerHour(when, level, count, SystemClock.elapsedRealtime());
        if (!Double.isNaN(rate)) o.put("ratePerHour", Math.round(rate * 10) / 10.0);
        int left = minutesLeft(pct, rate, charging); if (left >= 0) o.put(charging ? "minutesToFull" : "minutesToEmpty", left);
        return o;
    }

    /**
     * Phones disagree on the unit (µA or mA) and on the sign; shown as mA, positive into the battery while it charges
     * and negative while it drains, which is what the charging state already says.
     */
    static int currentMa(int raw, boolean charging) {
        if (raw == Integer.MIN_VALUE || raw == 0) return Integer.MIN_VALUE;
        long a = Math.abs((long) raw); int ma = (int) (a > 20_000 ? Math.round(a / 1000.0) : a);   // > 20 A as mA is impossible: it was µA
        return charging ? ma : -ma;
    }

    private static String health(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "overheating";
            case BatteryManager.BATTERY_HEALTH_COLD: return "cold";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "over voltage";   // i18n: health code: the page translates it (and compares good)
            default: return "";
        }
    }

    private JSONObject cellJson() throws Exception {
        JSONObject o = new JSONObject();
        TelephonyManager tm = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
        boolean airplane = android.provider.Settings.Global.getInt(app.getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        o.put("airplane", airplane);
        if (tm == null) return o.put("ok", false);
        int sim = tm.getSimState();
        o.put("sim", sim == TelephonyManager.SIM_STATE_READY ? "ready" : sim == TelephonyManager.SIM_STATE_ABSENT ? "no SIM" : "not ready");
        String op = tm.getNetworkOperatorName(); if (op != null && !op.isEmpty()) o.put("operator", op);
        SignalStrength ss = null; try { ss = tm.getSignalStrength(); } catch (Throwable ignored) { }
        if (ss == null) return o.put("ok", false);
        o.put("ok", true).put("bars", ss.getLevel());
        List<CellSignalStrength> l = ss.getCellSignalStrengths();
        CellSignalStrength best = null;
        for (CellSignalStrength c : l) if (best == null || c.getLevel() > best.getLevel()) best = c;
        if (best != null) {
            o.put("tech", best instanceof CellSignalStrengthNr ? "5G" : best instanceof CellSignalStrengthLte ? "LTE"
                    : best instanceof CellSignalStrengthWcdma || best instanceof CellSignalStrengthTdscdma ? "3G" : best instanceof CellSignalStrengthGsm ? "2G" : "");
            int dbm = best.getDbm(); if (dbm != Integer.MAX_VALUE && dbm < 0) o.put("dbm", dbm);
            if (best instanceof CellSignalStrengthLte) {
                CellSignalStrengthLte lte = (CellSignalStrengthLte) best;
                if (lte.getRsrq() != Integer.MAX_VALUE) o.put("rsrq", lte.getRsrq());
                if (lte.getRssnr() != Integer.MAX_VALUE) o.put("snr", lte.getRssnr());
            }
        }
        return o;
    }

    @SuppressWarnings("deprecation")
    private JSONObject wifiJson() throws Exception {
        JSONObject o = new JSONObject();
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return o.put("ok", false);
        o.put("enabled", wm.isWifiEnabled());
        WifiInfo wi = wm.getConnectionInfo();
        if (wi == null || wi.getNetworkId() == -1 || wi.getRssi() <= -127) return o.put("ok", true).put("connected", false);
        o.put("ok", true).put("connected", true).put("dbm", wi.getRssi()).put("bars", WifiManager.calculateSignalLevel(wi.getRssi(), 5));
        if (wi.getLinkSpeed() > 0) o.put("mbps", wi.getLinkSpeed());
        int f = wi.getFrequency(); if (f > 0) o.put("band", f >= 5925 ? "6 GHz" : f >= 4900 ? "5 GHz" : "2.4 GHz");
        String ssid = wi.getSSID();
        if (ssid != null && !ssid.contains("unknown ssid")) o.put("ssid", ssid.replaceAll("^\"|\"$", ""));
        return o;
    }
}
