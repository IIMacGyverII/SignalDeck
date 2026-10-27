/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.Restricted;
import com.macdmr.radio.Restriction;
import com.macdmr.radio.RestrictionLock;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Restricted mode: the setup sheet (presets, zones, transmit, modes, tools, the PIN) and the PIN sheet with
 * Turn off / Edit restrictions / Unlock for a while. Device → Restricted mode opens it. Voluntary and self-imposed;
 * the help says what it cannot do.
 */
public final class RestrictedDialog {
    private RestrictedDialog() { }

    /** Entry: setup when the mode is off, the PIN sheet when it is on. */
    public static void show(Context c, RadioService svc, Runnable changed) {
        if (Restricted.on(c)) pinSheet(c, svc, changed); else setup(c, svc, Restricted.policy(c), false, changed);
    }

    // ---- the PIN sheet -------------------------------------------------------------------------------------------

    private static void pinSheet(final Context c, final RadioService svc, final Runnable changed) {
        final RestrictionLock.State st = Restricted.lock(c);
        long wait = RestrictionLock.waitLeft(st, System.currentTimeMillis());
        if (wait > 0) { Toast.makeText(c, "Wrong PIN five times: wait " + ((wait + 999) / 1000) + " s", Toast.LENGTH_LONG).show(); return; }
        final EditText pin = pinField(c);
        new AlertDialog.Builder(c).setTitle("Restricted mode").setMessage(Restricted.unlocked(c) ? "Unlocked for a while. Enter the PIN to change it." : "Enter the PIN.")
                .setView(pin)
                .setPositiveButton("Continue", (d, w) -> {
                    RestrictionLock.Try t = RestrictionLock.check(st, pin.getText().toString().trim(), System.currentTimeMillis());
                    Restricted.saveLock(c, st);
                    if (t == RestrictionLock.Try.OK) choices(c, svc, changed);
                    else if (t == RestrictionLock.Try.WAIT) Toast.makeText(c, "Wrong PIN five times: wait a minute", Toast.LENGTH_LONG).show();
                    else Toast.makeText(c, "Wrong PIN", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static void choices(final Context c, final RadioService svc, final Runnable changed) {
        LinearLayout body = Neo.sheetBody(c, "Restricted mode");
        body.addView(Neo.sheetHint(c, "The PIN is right. What now?"));
        final AlertDialog[] self = new AlertDialog[1];
        TextView edit = Neo.button(c, "Edit restrictions", "device", Neo.CYAN);
        TextView off = Neo.button(c, "Turn off Restricted mode", "stop", Neo.RED);
        TextView u5 = Neo.button(c, "Unlock for 5 minutes", "info", Neo.AMBER);
        TextView u30 = Neo.button(c, "Unlock for 30 minutes", "info", Neo.AMBER);
        TextView uR = Neo.button(c, "Unlock until SignalDeck restarts", "info", Neo.AMBER);
        TextView lock = Neo.button(c, "Lock again now", "shield", Neo.GREEN);
        for (TextView b : new TextView[] { edit, off, u5, u30, uR, lock }) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = (int) (8 * Theme.dp(c)); body.addView(b, lp); }
        edit.setOnClickListener(v -> { self[0].dismiss(); setup(c, svc, Restricted.policy(c), true, changed); });
        off.setOnClickListener(v -> new AlertDialog.Builder(c).setTitle("Turn off Restricted mode?").setMessage("Everything becomes visible and allowed again, and the PIN is forgotten.")
                .setPositiveButton("Turn off", (d, w) -> { Restricted.turnOff(c); self[0].dismiss(); done(c, svc, changed, "Restricted mode is off"); }).setNegativeButton("Cancel", null).show());
        u5.setOnClickListener(v -> { unlock(c, 5 * 60_000L); self[0].dismiss(); done(c, svc, changed, "Unlocked for 5 minutes"); });
        u30.setOnClickListener(v -> { unlock(c, 30 * 60_000L); self[0].dismiss(); done(c, svc, changed, "Unlocked for 30 minutes"); });
        uR.setOnClickListener(v -> { unlock(c, 365L * 24 * 3_600_000L); self[0].dismiss(); done(c, svc, changed, "Unlocked until SignalDeck restarts"); });
        lock.setOnClickListener(v -> { RestrictionLock.State st = Restricted.lock(c); RestrictionLock.lock(st); Restricted.saveLock(c, st); self[0].dismiss(); done(c, svc, changed, "Locked"); });
        self[0] = Neo.showSheet(c, body);
    }

    private static void unlock(Context c, long ms) { RestrictionLock.State st = Restricted.lock(c); RestrictionLock.unlockFor(st, System.currentTimeMillis(), ms); Restricted.saveLock(c, st); }

    private static void done(Context c, RadioService svc, Runnable changed, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
        if (svc != null) svc.restrictionChanged();
        if (changed != null) changed.run();
    }

    // ---- the setup sheet -----------------------------------------------------------------------------------------

    /** @param editing true when the mode is already on (Save keeps the PIN); false sets the PIN and turns the mode on. */
    private static void setup(final Context c, final RadioService svc, Restriction start, final boolean editing, final Runnable changed) {
        final float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, editing ? "Edit restrictions" : "Restricted mode");
        body.addView(Neo.sheetHint(c, "A guard rail you set for yourself: what stays visible and allowed while the mode is on. Hidden things are removed from the app and refused by the radio engine, on the phone, in SignalDeck Web and in the car. It is not security and it checks no licence: knowing the rules of your licence stays your job."));

        // state the switches edit
        final boolean[] allZones = { start.allZones };
        final Set<String> zones = new LinkedHashSet<>(start.zones);
        final int[] tx = { start.transmit };
        final Set<String> hiddenModes = new LinkedHashSet<>(start.hiddenModes);
        final Set<String> hiddenAdmin = new LinkedHashSet<>(start.hiddenAdmin);
        final java.util.Map<String, Neo.NeonSwitch> modeSw = new java.util.LinkedHashMap<>();
        final java.util.Map<String, Neo.NeonSwitch> adminSw = new java.util.LinkedHashMap<>();
        final java.util.Map<String, Neo.NeonSwitch> zoneSw = new java.util.LinkedHashMap<>();
        final Neo.NeonSwitch[] allZonesSw = new Neo.NeonSwitch[1];
        final TextView[] txLabel = new TextView[1];

        // presets
        body.addView(Neo.sheetSection(c, "Start from"));
        LinearLayout pr = row(c);
        TextView pRx = Neo.button(c, "Receive only", null, Neo.AMBER); TextView pSimple = Neo.button(c, "Simple radio", null, Neo.CYAN); TextView pAll = Neo.button(c, "Everything", null, Neo.GREEN);
        for (TextView b : new TextView[] { pRx, pSimple, pAll }) { b.setTextSize(14); pr.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); }
        body.addView(pr);
        body.addView(Neo.sheetHint(c, "Presets are starting points, nothing more: Receive only hides every tool and never transmits; Simple radio keeps talking on the visible zones and hides the data modes and editing; Everything hides nothing (then limit zones below)."));

        // zones
        body.addView(Neo.sheetSection(c, "Zones"));
        allZonesSw[0] = new Neo.NeonSwitch(c); allZonesSw[0].setChecked(allZones[0]);
        body.addView(Neo.switchRow(c, "zone", Neo.CYAN, "All zones visible", "Off: only the zones ticked below exist in the app", allZonesSw[0]));
        final LinearLayout zoneList = new LinearLayout(c); zoneList.setOrientation(LinearLayout.VERTICAL); body.addView(zoneList);
        java.util.List<ChannelStore.Zone> zl = svc != null ? svc.store().zones() : new java.util.ArrayList<>();
        if (zl.isEmpty()) zoneList.addView(Neo.sheetHint(c, "No zones yet: Channels → ZONE → Add a zone makes one from your channels."));
        for (ChannelStore.Zone z : zl) {
            Neo.NeonSwitch sw = new Neo.NeonSwitch(c); sw.setChecked(zones.contains(z.name)); zoneSw.put(z.name, sw);
            zoneList.addView(Neo.switchRow(c, "zone", Neo.GREEN, z.name, z.numbers.length + " channels", sw));
            final String name = z.name; sw.setOnChange(on -> { if (on) zones.add(name); else zones.remove(name); });
        }
        allZonesSw[0].setOnChange(on -> { allZones[0] = on; zoneList.setVisibility(on ? android.view.View.GONE : android.view.View.VISIBLE); });
        zoneList.setVisibility(allZones[0] ? android.view.View.GONE : android.view.View.VISIBLE);
        body.addView(Neo.sheetHint(c, "Channels in no visible zone cannot be selected or transmitted on by any path: the arrows, the list, scan, the VFO, the car, the web, the side key."));

        // transmit
        body.addView(Neo.sheetSection(c, "Transmit"));
        txLabel[0] = Neo.text(c, "", Neo.TEXT, 15, Neo.ui(c)); body.addView(txLabel[0]);
        LinearLayout tr = row(c);
        TextView tAll = Neo.button(c, "Anywhere", null, Neo.GREEN); TextView tVis = Neo.button(c, "Visible zones only", null, Neo.CYAN); TextView tNone = Neo.button(c, "Never", null, Neo.RED);
        for (TextView b : new TextView[] { tAll, tVis, tNone }) { b.setTextSize(14); tr.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); }
        body.addView(tr);
        Runnable txText = () -> txLabel[0].setText(tx[0] == Restriction.TX_NONE ? "Never: receive only, every transmit path refuses" : tx[0] == Restriction.TX_VISIBLE ? "Only on channels of a visible zone (the VFO counts as outside every zone)" : "Anywhere the channel allows");
        tAll.setOnClickListener(v -> { tx[0] = Restriction.TX_ALL; txText.run(); }); tVis.setOnClickListener(v -> { tx[0] = Restriction.TX_VISIBLE; txText.run(); }); tNone.setOnClickListener(v -> { tx[0] = Restriction.TX_NONE; txText.run(); });
        txText.run();

        // modes
        body.addView(Neo.sheetSection(c, "Packet tools and modes"));
        body.addView(Neo.sheetHint(c, "Off = hidden from the PKT sheet, the pages and SignalDeck Web, and refused when started."));
        String[][] modes = {
            { "aprs", "APRS (monitor, messages, beacons, objects, log)" }, { "kiss", "KISS TNC" }, { "aprsis", "APRS-IS (internet)" }, { "satellites", "Satellite passes and ISS" },
            { "scope", "Band scope" }, { "parrot", "Simplex repeater (parrot)" }, { "textmodes", "RTTY and PSK31" }, { "ft8", "FT8 over FM" }, { "terminal", "Packet terminal" },
            { "finder", "Packet finder" }, { "winlink", "Winlink e-mail" }, { "exam", "Licence exam practice" }, { "logbook", "Logbook" }, { "pota", "POTA / SOTA" },
            { "fox", "Fox hunt" }, { "forms", "Message forms" }, { "nets", "Nets" }, { "keyer", "Voice keyer and ID timer" }, { "morse", "Morse (CW ID, trainer)" },
            { "schedules", "Scheduled operations" }, { "pocsag", "Pager (POCSAG) send" }, { "pages", "Pager receive" }, { "sstv", "SSTV pictures" }, { "dtmf", "DTMF" },
            { "wx", "NOAA weather alerts" }, { "m17", "M17 digital voice and texts" }, { "dmrtext", "DMR texts" }, { "captions", "Live captions" } };
        for (String[] m : modes) {
            Neo.NeonSwitch sw = new Neo.NeonSwitch(c); sw.setChecked(!hiddenModes.contains(m[0])); modeSw.put(m[0], sw);
            body.addView(Neo.switchRow(c, "pkt", Neo.GREEN, m[1], "", sw));
            final String id = m[0]; sw.setOnChange(on -> { if (on) hiddenModes.remove(id); else hiddenModes.add(id); });
        }

        // admin
        body.addView(Neo.sheetSection(c, "Editing and tools"));
        String[][] admin = {
            { "editChannels", "Channel editing (add, edit, delete)" }, { "editZones", "Zone editing" }, { "editContacts", "Contact editing" }, { "backup", "Backup, restore, re-import" },
            { "vfo", "VFO" }, { "scan", "Scan" }, { "firmware", "Module firmware page" }, { "advanced", "Advanced (firmware page)" }, { "web", "SignalDeck Web card" },
            { "report", "Device report and diagnostics" }, { "exit", "Exit SignalDeck" } };
        for (String[] a : admin) {
            Neo.NeonSwitch sw = new Neo.NeonSwitch(c); sw.setChecked(!hiddenAdmin.contains(a[0])); adminSw.put(a[0], sw);
            body.addView(Neo.switchRow(c, "device", Neo.AMBER, a[1], "", sw));
            final String id = a[0]; sw.setOnChange(on -> { if (on) hiddenAdmin.remove(id); else hiddenAdmin.add(id); });
        }

        // presets set the switches
        Runnable applyAll = () -> {
            for (java.util.Map.Entry<String, Neo.NeonSwitch> e : modeSw.entrySet()) e.getValue().setChecked(!hiddenModes.contains(e.getKey()));
            for (java.util.Map.Entry<String, Neo.NeonSwitch> e : adminSw.entrySet()) e.getValue().setChecked(!hiddenAdmin.contains(e.getKey()));
            txText.run();
        };
        pRx.setOnClickListener(v -> { Restriction p = Restriction.receiveOnly(allZones[0], zones); tx[0] = p.transmit; hiddenModes.clear(); hiddenModes.addAll(p.hiddenModes); hiddenAdmin.clear(); hiddenAdmin.addAll(p.hiddenAdmin); applyAll.run(); });
        pSimple.setOnClickListener(v -> { Restriction p = Restriction.simpleRadio(allZones[0], zones); tx[0] = p.transmit; hiddenModes.clear(); hiddenModes.addAll(p.hiddenModes); hiddenAdmin.clear(); hiddenAdmin.addAll(p.hiddenAdmin); applyAll.run(); });
        pAll.setOnClickListener(v -> { tx[0] = Restriction.TX_ALL; hiddenModes.clear(); hiddenAdmin.clear(); applyAll.run(); });

        // PIN and the switch
        body.addView(Neo.sheetSection(c, editing ? "Save" : "PIN and turn on"));
        body.addView(Neo.sheetHint(c, editing ? "The restrictions above replace the saved ones; the mode stays on with the same PIN."
                : "Choose a PIN of 4 to 8 digits. It is the only way to turn the mode off or to change it. There is no back door: if you forget it, clearing SignalDeck's app data (Android Settings → Apps) removes the PIN and everything else the app stored."));
        final AlertDialog[] self = new AlertDialog[1];
        TextView go = Neo.button(c, editing ? "Save restrictions" : "Set the PIN and turn on", "shield", Neo.GREEN);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); gl.topMargin = (int) (8 * d);
        body.addView(go, gl);
        go.setOnClickListener(v -> {
            final Restriction r = new Restriction(allZones[0], zones, tx[0], hiddenModes, hiddenAdmin);
            if (!allZones[0] && zones.isEmpty()) { Toast.makeText(c, "Tick at least one zone, or leave all zones visible", Toast.LENGTH_LONG).show(); return; }
            if (editing) { Restricted.savePolicy(c, r); self[0].dismiss(); done(c, svc, changed, "Restrictions saved"); return; }
            askPin(c, pin -> askPinAgain(c, pin, () -> { Restricted.turnOn(c, r, pin); self[0].dismiss(); done(c, svc, changed, "Restricted mode is on"); }));
        });
        self[0] = Neo.showSheet(c, body);
    }

    private static LinearLayout row(Context c) { LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL); return r; }

    private static EditText pinField(Context c) {
        EditText e = new EditText(c); e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); e.setHint("PIN"); e.setSingleLine(true);
        return e;
    }

    interface PinSink { void pin(String pin); }

    private static void askPin(final Context c, final PinSink sink) {
        final EditText pin = pinField(c);
        new AlertDialog.Builder(c).setTitle("Choose a PIN").setMessage("4 to 8 digits.").setView(pin)
                .setPositiveButton("Next", (d, w) -> { String p = pin.getText().toString().trim(); if (!RestrictionLock.validPin(p)) { Toast.makeText(c, "4 to 8 digits", Toast.LENGTH_SHORT).show(); return; } sink.pin(p); })
                .setNegativeButton("Cancel", null).show();
    }

    private static void askPinAgain(final Context c, final String first, final Runnable ok) {
        final EditText pin = pinField(c);
        new AlertDialog.Builder(c).setTitle("Enter the PIN again").setView(pin)
                .setPositiveButton("Turn on", (d, w) -> { if (first.equals(pin.getText().toString().trim())) ok.run(); else Toast.makeText(c, "The two PINs differ; start again", Toast.LENGTH_LONG).show(); })
                .setNegativeButton("Cancel", null).show();
    }
}
