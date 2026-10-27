/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Restricted mode's policy: what stays visible and allowed while the mode is on. A guard rail the user sets for
 * themselves (a voluntary PIN lock), not security: it hides and refuses, it does not check any licence.
 *
 * <p>Zones: when {@link #allZones} is false only the zone keys in {@link #zones} are visible, and a channel outside
 * them cannot be selected or transmitted on. Transmit: {@link #transmit} is {@link #TX_ALL}, {@link #TX_VISIBLE} (only
 * on channels of a visible zone, the default when zones are limited) or {@link #TX_NONE} (receive only). Modes: the
 * ids in {@link #hiddenModes} are removed from the PKT sheet and refused when started. Admin: the ids in
 * {@link #hiddenAdmin} are removed from the Device page and the tabs.
 *
 * <p>The object is immutable in use (the setup page builds a new one); {@link #toJson()} and {@link #fromJson(String)}
 * carry it through the app's preferences and host tests.
 */
public final class Restriction {
    public static final int TX_ALL = 0, TX_VISIBLE = 1, TX_NONE = 2;

    /** Mode ids, one per PKT entry (and the two non-PKT items at the end). The screens use the same strings. */
    public static final String[] MODES = {
        "aprs", "kiss", "aprsis", "digipeater", "parrot", "dtmf", "sstv", "ft8", "textmodes", "m17", "pocsag", "winlink",
        "terminal", "nets", "logbook", "keyer", "trainer", "schedules", "satellites", "scope", "fox", "exam", "pages",
        "pota", "finder", "wx", "forms", "dmrtext", "captions" };
    /** Admin ids: items on the Device page or elsewhere that can be removed. */
    public static final String[] ADMIN = {
        "editChannels", "editZones", "editContacts", "backup", "firmware", "advanced", "web", "report", "exit", "vfo", "scan" };

    public final boolean allZones;
    public final Set<String> zones;          // zone keys visible when allZones is false
    public final int transmit;
    public final Set<String> hiddenModes;
    public final Set<String> hiddenAdmin;

    public Restriction(boolean allZones, Set<String> zones, int transmit, Set<String> hiddenModes, Set<String> hiddenAdmin) {
        this.allZones = allZones;
        this.zones = Collections.unmodifiableSet(new LinkedHashSet<>(zones == null ? Collections.<String>emptySet() : zones));
        this.transmit = transmit < TX_ALL || transmit > TX_NONE ? TX_ALL : transmit;
        this.hiddenModes = Collections.unmodifiableSet(new LinkedHashSet<>(hiddenModes == null ? Collections.<String>emptySet() : hiddenModes));
        this.hiddenAdmin = Collections.unmodifiableSet(new LinkedHashSet<>(hiddenAdmin == null ? Collections.<String>emptySet() : hiddenAdmin));
    }

    /** No restriction at all: everything visible, everything allowed. */
    public static Restriction none() { return new Restriction(true, null, TX_ALL, null, null); }

    /** Receive only: no transmit anywhere, every data mode and every editing or admin item hidden; the zones as given. */
    public static Restriction receiveOnly(boolean allZones, Set<String> zones) {
        return new Restriction(allZones, zones, TX_NONE, set(MODES), set(ADMIN));
    }

    /** Simple radio: the visible zones, talk on them, no data modes, no editing; Web, Device report and Exit stay. */
    public static Restriction simpleRadio(boolean allZones, Set<String> zones) {
        Set<String> admin = set("editChannels", "editZones", "editContacts", "backup", "firmware", "advanced", "vfo");
        return new Restriction(allZones, zones, allZones ? TX_ALL : TX_VISIBLE, set(MODES), admin);
    }

    /** Is this zone visible? A null or empty key (channels without a zone) follows {@link #allZones}. */
    public boolean zoneVisible(String zoneKey) {
        if (allZones) return true;
        return zoneKey != null && !zoneKey.isEmpty() && zones.contains(zoneKey);
    }

    /** May the radio transmit on a channel of this zone? */
    public boolean mayTransmit(String zoneKey) {
        if (transmit == TX_NONE) return false;
        if (transmit == TX_VISIBLE) return zoneVisible(zoneKey);
        return true;
    }

    public boolean modeAllowed(String modeId) { return modeId == null || !hiddenModes.contains(modeId); }
    public boolean adminAllowed(String adminId) { return adminId == null || !hiddenAdmin.contains(adminId); }

    /** True when the policy restricts nothing (the same as {@link #none()}). */
    public boolean isEmpty() { return allZones && transmit == TX_ALL && hiddenModes.isEmpty() && hiddenAdmin.isEmpty(); }

    /** The one-line reason shown wherever something is refused. */
    public static final String REFUSED = "Hidden by Restricted mode";

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("allZones", allZones);
            o.put("zones", new JSONArray(zones));
            o.put("transmit", transmit);
            o.put("hiddenModes", new JSONArray(hiddenModes));
            o.put("hiddenAdmin", new JSONArray(hiddenAdmin));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** The policy from its JSON; a missing or broken string is no restriction. */
    public static Restriction fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return none();
        try {
            JSONObject o = new JSONObject(json);
            return new Restriction(o.optBoolean("allZones", true), strings(o.optJSONArray("zones")), o.optInt("transmit", TX_ALL),
                    strings(o.optJSONArray("hiddenModes")), strings(o.optJSONArray("hiddenAdmin")));
        } catch (Exception e) { return none(); }
    }

    private static Set<String> strings(JSONArray a) {
        Set<String> s = new LinkedHashSet<>();
        if (a != null) for (int i = 0; i < a.length(); i++) { String v = a.optString(i, ""); if (!v.isEmpty()) s.add(v); }
        return s;
    }

    static Set<String> set(String... v) { Set<String> s = new LinkedHashSet<>(); Collections.addAll(s, v); return s; }

    @Override public String toString() { return toJson(); }
}
