/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Restricted mode's stored state and the checks the engine and the screens ask. The mode is on or off; while on,
 * the policy applies unless the PIN has unlocked it for a while. Zones are identified by name (zone ids change when
 * the stock app's channels are re-imported; names survive). Nothing here reports anywhere or can be set remotely.
 */
public final class Restricted {
    static final String PREFS = "restricted";

    private Restricted() { }

    static SharedPreferences p(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Is the mode turned on (a PIN set and the policy saved)? */
    public static boolean on(Context c) { return p(c).getBoolean("on", false) && lock(c).hasPin(); }

    /** The saved policy, whether or not the mode is on. */
    public static Restriction policy(Context c) { return Restriction.fromJson(p(c).getString("policy", "")); }

    public static RestrictionLock.State lock(Context c) { return RestrictionLock.State.unpack(p(c).getString("lock", "")); }
    public static void saveLock(Context c, RestrictionLock.State st) { p(c).edit().putString("lock", st.pack()).apply(); }

    /** Turn the mode on with this policy and PIN (the PIN is chosen when the mode is first turned on). */
    public static void turnOn(Context c, Restriction r, String pin) {
        RestrictionLock.State st = RestrictionLock.setPin(pin);
        p(c).edit().putString("policy", r.toJson()).putString("lock", st.pack()).putBoolean("on", true).apply();
    }

    /** Change the policy while the mode stays on (after the PIN unlocked the setup). */
    public static void savePolicy(Context c, Restriction r) { p(c).edit().putString("policy", r.toJson()).apply(); }

    /** Turn the mode off and forget the PIN. */
    public static void turnOff(Context c) { p(c).edit().putBoolean("on", false).remove("lock").apply(); }

    /** Unlocked for a while by the PIN? */
    public static boolean unlocked(Context c) { return on(c) && RestrictionLock.unlocked(lock(c), System.currentTimeMillis()); }

    /** The policy in force right now: the saved one while the mode is on and locked, otherwise no restriction. */
    public static Restriction effective(Context c) {
        if (!on(c)) return Restriction.none();
        if (RestrictionLock.unlocked(lock(c), System.currentTimeMillis())) return Restriction.none();
        return policy(c);
    }

    /** Is the mode on and in force (not unlocked)? */
    public static boolean active(Context c) { return !effective(c).isEmpty() || (on(c) && !unlocked(c)); }

    // ---- zone helpers, pure (host tests pass zone lists) -------------------------------------------------------

    /** Names of the zones that contain this channel number. */
    public static Set<String> zoneNamesOf(List<ChannelStore.Zone> zones, int number) {
        Set<String> out = new LinkedHashSet<>();
        if (zones != null) for (ChannelStore.Zone z : zones) { if (z == null || z.numbers == null) continue; for (int n : z.numbers) if (n == number) { out.add(z.name); break; } }
        return out;
    }

    /** Is a stored channel visible under this policy: every channel when zones are not limited, else one in a visible zone. */
    public static boolean channelVisible(Restriction r, List<ChannelStore.Zone> zones, int number) {
        if (r == null || r.allZones) return true;
        for (String name : zoneNamesOf(zones, number)) if (r.zoneVisible(name)) return true;
        return false;
    }

    /** May the radio transmit on this channel: the VFO (number 0) counts as outside every zone. */
    public static boolean mayTransmitOn(Restriction r, List<ChannelStore.Zone> zones, ChannelStore.Channel ch) {
        if (r == null || r.transmit == Restriction.TX_ALL) return true;
        if (r.transmit == Restriction.TX_NONE) return false;
        return ch != null && ch.number > 0 && channelVisible(r, zones, ch.number);
    }

    /** The zones of this list the policy in force lets the user see. */
    public static List<ChannelStore.Zone> filterZones(Context c, List<ChannelStore.Zone> zones) {
        Restriction r = effective(c); java.util.List<ChannelStore.Zone> out = new java.util.ArrayList<>();
        if (zones != null) for (ChannelStore.Zone z : zones) if (zoneVisible(r, z)) out.add(z);
        return out;
    }
    /** The channels of this list the policy in force lets the user see. */
    public static List<ChannelStore.Channel> filterChannels(Context c, List<ChannelStore.Zone> zones, List<ChannelStore.Channel> all) {
        Restriction r = effective(c); if (r.allZones || all == null) return all == null ? new java.util.ArrayList<>() : all;
        java.util.List<ChannelStore.Channel> out = new java.util.ArrayList<>();
        for (ChannelStore.Channel ch : all) if (channelVisible(r, zones, ch.number)) out.add(ch);
        return out;
    }

    /** Is a zone visible by name. */
    public static boolean zoneVisible(Restriction r, ChannelStore.Zone z) { return r == null || z == null || r.zoneVisible(z.name); }
}
