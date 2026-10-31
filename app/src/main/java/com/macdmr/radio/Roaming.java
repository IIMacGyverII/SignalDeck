/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.util.List;

/**
 * DMR roaming, the choice: among the zone's DMR channels that carry the talkgroup you are on and have a location, the
 * nearest one, and only when it is clearly nearer than the current repeater (at least {@link #MIN_GAIN_KM} and
 * {@link #RATIO} of the distance), so the radio does not flip between two repeaters at the same distance. The service
 * asks every 30 s while moving and never during a call. Pure: host-tested.
 */
public final class Roaming {
    private Roaming() { }
    public static final double MIN_GAIN_KM = 5, RATIO = 0.7;

    /** Does {@code b} carry the talkgroup (or private call) that {@code a} transmits on? */
    static boolean sameTalkgroup(ChannelStore.Channel a, ChannelStore.Channel b) {
        if (a.txContact <= 0) return false;
        if (b.txContact == a.txContact && b.contactType == a.contactType) return true;
        if (a.contactType == 1 && b.groups != null) for (int g : b.groups) if (g == a.txContact) return true;    // group calls: in its RX list
        return false;
    }

    /** The channel to switch to, or null to stay. */
    public static ChannelStore.Channel choose(ChannelStore.Channel cur, List<ChannelStore.Channel> candidates, double lat, double lon) {
        if (cur == null || !cur.isDigital() || (cur.lat == 0 && cur.lon == 0)) return null;
        double here = km(lat, lon, cur.lat, cur.lon);
        ChannelStore.Channel best = null; double bestKm = Double.MAX_VALUE;
        for (ChannelStore.Channel c : candidates) {
            if (c.number == cur.number || !c.isDigital() || (c.lat == 0 && c.lon == 0) || !sameTalkgroup(cur, c) || !ChannelStore.tunable(c)) continue;   // never roam to a repeater this radio cannot tune
            double d = km(lat, lon, c.lat, c.lon);
            if (d < bestKm) { bestKm = d; best = c; }
        }
        if (best == null) return null;
        return bestKm <= here - MIN_GAIN_KM && bestKm <= here * RATIO ? best : null;
    }

    public static double km(double a, double b, double c, double d) {
        double r = Math.PI / 180, x = Math.sin((c - a) * r / 2), y = Math.sin((d - b) * r / 2), h = x * x + Math.cos(a * r) * Math.cos(c * r) * y * y;
        return 2 * 6371 * Math.asin(Math.sqrt(h));
    }
}
