/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * The one station identity: your callsign (with the APRS SSID), your DMR ID and your pager RICs. APRS, M17 transmit,
 * the POCSAG sender tag and auto-acknowledgements all read it from here. The values live where they always did
 * (the {@code packet} preferences for the callsign and SSID, {@code radio} for the DMR ID), so nothing already set
 * is lost; the RICs are new.
 */
public final class Station {
    private Station() { }
    private static SharedPreferences packet(Context c) { return c.getSharedPreferences("packet", Context.MODE_PRIVATE); }

    /** The callsign without SSID, upper case; "" when not set. */
    public static String callsign(Context c) {
        String s = packet(c).getString("callsign", "").trim().toUpperCase(Locale.US);
        int d = s.indexOf('-'); return d > 0 ? s.substring(0, d) : s;
    }
    public static int ssid(Context c) { return Math.max(0, Math.min(15, packet(c).getInt("ssid", 7))); }
    /** "CALL-7", or "CALL" when the SSID is 0; "" when no callsign is set. */
    public static String withSsid(Context c) { String b = callsign(c); int s = ssid(c); return b.isEmpty() ? "" : s == 0 ? b : b + "-" + s; }
    public static boolean callsignSet(Context c) { return !callsign(c).isEmpty(); }
    public static int dmrId(Context c) { int id = c.getSharedPreferences("radio", Context.MODE_PRIVATE).getInt("dmrId", 1); return id >= 1 && id <= ChannelStore.MAX_DMR_ID ? id : 1; }
    /** Your pager RICs, as typed (the POCSAG page listens for them). */
    public static int[] rics(Context c) { return ChannelStore.parseRics(packet(c).getString("myRics", "")); }
    public static String ricText(Context c) { return ChannelStore.ricText(rics(c)); }
    public static void setRics(Context c, String text) { packet(c).edit().putString("myRics", ChannelStore.ricText(ChannelStore.parseRics(text))).apply(); }

    /** True when the callsign (with or without an SSID) is this station's. */
    public static boolean isMine(Context c, String call) {
        if (call == null) return false;
        String s = call.trim().toUpperCase(Locale.US); int d = s.indexOf('-'); if (d > 0) s = s.substring(0, d);
        return !s.isEmpty() && s.equals(callsign(c));
    }
    public static boolean isMyRic(Context c, int ric) { for (int r : rics(c)) if (r == ric) return true; return false; }
}
