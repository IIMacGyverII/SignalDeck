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
 * The longest transmission (Device → Longest transmission; owner, 2026-10-30): one rule for every transmit path. The
 * setting is whole seconds, {@link #NONE} (0) for no SignalDeck limit; the default is 2 minutes. The module gets the
 * same limit on its own timer (command 0x3B, one byte of seconds, 0 = off) a little longer than SignalDeck's, so
 * SignalDeck always releases first and the module's timer is only the backstop. The heat guard ({@link ThermalGuard})
 * is separate and stays in force for every key longer than 30 s, whatever this says.
 */
public final class TxLimit {
    private TxLimit() { }

    /** The setting's value for no SignalDeck time limit. */
    public static final int NONE = 0;
    public static final int DEFAULT_S = 120;
    /** The shortest custom value: anything shorter cuts a normal over in half. */
    public static final int MIN_CUSTOM_S = 10;
    /** Longer than this (or no limit) asks once with the heat warning: 2 minutes is what the bench measured. */
    public static final int WARN_ABOVE_S = 120;
    /** The module's timer runs this much longer than SignalDeck's watchdog, so SignalDeck releases cleanly first. */
    public static final int MODULE_MARGIN_S = 2;
    /** The 0x3B byte's largest value. */
    public static final int MODULE_MAX_S = 255;
    /** The transmit test session's own limit for its whole run: its limit check expects the key to end at 28 s. */
    public static final int TEST_SESSION_S = 28;
    /** The choices offered, in order; Custom… follows them on the screen. */
    public static final int[] PRESETS_S = { 30, 60, 120, 180, 300, 600, NONE };

    /** English only, for callers not yet moved to {@link #heatWarning()} (DeviceView, WebHost). */
    public static final String HEAT_WARNING = "Long transmissions heat the phone. A 2-minute key at high power warmed the radio area about 11 °C on the bench. "   // i18n: kept for old callers; screens use heatWarning()
            + "SignalDeck still stops any transmission if the phone gets too hot (radio area 70 °C, battery 48 °C).";   // i18n: kept for old callers; screens use heatWarning()

    /** The heat warning shown once when a long limit (or none) is picked, in the app's language. */
    public static String heatWarning() { return Txt.s(R.string.device_txlimit_heat_warning); }

    /** A stored value made valid: negative means never set (the default), 1–9 s is raised to the custom minimum. */
    public static int valid(int s) { return s < 0 ? DEFAULT_S : s == NONE ? NONE : Math.max(MIN_CUSTOM_S, s); }

    /** The limit in force: the test session's override when one is set (> 0), else the setting. */
    public static int effective(int settingS, int overrideS) { return overrideS > 0 ? overrideS : valid(settingS); }

    /** SignalDeck's limit in ms, or -1 for no limit. */
    public static long limitMs(int s) { s = valid(s); return s == NONE ? -1 : s * 1000L; }

    /** The cap for a sender that refuses audio longer than the limit: the limit in ms, Integer.MAX_VALUE for none. */
    public static int capMs(int s) { long ms = limitMs(s); return ms < 0 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, ms); }

    /** The module timer byte (0x3B): the limit plus the margin, at most 255; 0 (timer off) for no limit or a limit over 255 s, where SignalDeck's watchdog alone ends the key. */
    public static int moduleTot(int s) { s = valid(s); return s == NONE || s > MODULE_MAX_S ? 0 : Math.min(MODULE_MAX_S, s + MODULE_MARGIN_S); }

    /** Whether picking this asks once with {@link #HEAT_WARNING}: no limit, or longer than 2 minutes. */
    public static boolean needsWarning(int s) { s = valid(s); return s == NONE || s > WARN_ABOVE_S; }

    /** The custom field's text parsed: whole seconds of at least {@link #MIN_CUSTOM_S}, or -1 when it is not one. */
    public static int parseCustom(String t) {
        try { int s = Integer.parseInt(t == null ? "" : t.trim()); return s >= MIN_CUSTOM_S ? s : -1; } catch (NumberFormatException e) { return -1; }
    }

    /** The value's name: "30 s", "1 min", "2 min", "1 min 30 s", "No limit". */
    public static String name(int s) { return nameMsg(s).text(); }
    /** As {@link #name}, as a message (for a stored history line). */
    public static Msg nameMsg(int s) {
        s = valid(s);
        if (s == NONE) return Msg.of(R.string.device_txlimit_none);
        if (s < 60) return Msg.of(R.string.device_txlimit_seconds, s);
        int m = s / 60, r = s % 60;
        return r == 0 ? Msg.of(R.string.device_txlimit_minutes, m) : Msg.of(R.string.device_txlimit_minutes_seconds, m, r);
    }

    /** The preset's index in {@link #PRESETS_S}, or -1 for a custom value. */
    public static int presetIndex(int s) { s = valid(s); for (int i = 0; i < PRESETS_S.length; i++) if (PRESETS_S[i] == s) return i; return -1; }
}
