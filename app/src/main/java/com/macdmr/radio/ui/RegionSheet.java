/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.RadioService;
import com.macdmr.radio.region.Region;
import com.macdmr.radio.region.RegionTable;

import java.util.List;
import java.util.Locale;

/**
 * Device → Region: the country whose defaults SignalDeck uses (APRS frequency, calling and SSTV frequencies, licence-free
 * services, weather radio, units, station-ID reminder), each value with Change (the user's own) and Reset. Choosing a
 * region never keys, starts a mode or changes a channel. An admin item in restricted mode.
 */
public final class RegionSheet {
    private RegionSheet() { }

    /** Restricted mode: the region is an admin item (a lent phone cannot be moved to another region's frequencies). */
    private static boolean allowed(Context c, RadioService svc) {
        if (svc == null || svc.adminAllowed("region")) return true;
        Toast.makeText(c, "The region is locked in Restricted mode", Toast.LENGTH_LONG).show();
        return false;
    }

    /** "Belgium" / "Custom: IARU Region 1 …" / "Not chosen". */
    public static String title(Region r) { return !r.chosen ? "Not chosen" : r.id.startsWith("iaru") ? "Custom: " + r.name : r.name; }
    /** One line for the Device row. */
    public static String summary(Context c, Region r) {
        if (!r.chosen) return "Not chosen: the North American defaults (APRS 144.390). Tap to choose";
        return title(r) + " · APRS " + r.aprsMhz() + (r.overridden.isEmpty() ? "" : " · " + r.overridden.size() + " changed by you");
    }

    public static void show(Context c, RadioService svc, Runnable changed) {
        float d = Theme.dp(c);
        Region r = Region.current(c);
        LinearLayout body = Neo.sheetBody(c, "Region");
        body.addView(Neo.sheetHint(c, "The region sets SignalDeck's defaults: the APRS frequency, the calling and SSTV frequencies, which licence-free services are offered first, whether weather radio exists, the amateur band edges for the Morse ID and the ID reminder, and kilometres or miles. It never transmits, starts a mode or changes your channels. You remain responsible for your licence and your country's rules."));
        String how = Region.how(c), roam = Region.roamingIn(c);
        TextView cur = Neo.text(c, title(r), Neo.CYAN_LIGHT, 20, Neo.ui(c)); body.addView(cur);
        if (r.chosen) body.addView(Neo.sheetHint(c, Region.howText(how) + (roam != null ? " (the network says " + RegionTable.countryName(roam) + ")" : "")
                + (r.generic ? ". No entry of its own yet: the IARU Region " + r.iaru + " defaults" : "")));
        else body.addView(Neo.sheetHint(c, "The phone did not say where you are, so the North American values are in use until you choose."));
        final AlertDialog[] self = new AlertDialog[1];
        Runnable redo = () -> { if (self[0] != null) self[0].dismiss(); if (changed != null) changed.run(); show(c, svc, changed); };
        TextView pick = Neo.button(c, r.chosen ? "Choose another region" : "Choose your region", "globe", Neo.CYAN);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); bl.topMargin = (int) (6 * d);
        body.addView(pick, bl);
        pick.setOnClickListener(v -> { if (allowed(c, svc)) picker(c, id -> choose(c, id, redo)); });

        body.addView(Neo.sheetSection(c, "What it sets"));
        for (String k : Region.VALUES) {
            LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL); row.setBackground(Neo.rowCard(c));
            row.setPadding((int) (14 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
            LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Neo.text(c, Region.label(k), Neo.MUTED, 13, Neo.uiMedium(c)));
            boolean own = r.overridden.contains(k), unv = r.unconfirmed(key(k), part(k));
            col.addView(Neo.text(c, r.show(k) + (own ? "  (yours)" : unv ? "  (to be confirmed)" : ""), own ? Neo.AMBER_LIGHT : Neo.TEXT, 16, Neo.ui(c)));
            row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (own) { TextView reset = Neo.chip(c, "Reset", Neo.AMBER); row.addView(reset); reset.setOnClickListener(v -> { if (allowed(c, svc)) { Region.setOverride(c, k, null); redo.run(); } }); }
            TextView ch = Neo.chip(c, "Change", Neo.CYAN); LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); cl.leftMargin = (int) (6 * d);
            row.addView(ch, cl); ch.setOnClickListener(v -> { if (allowed(c, svc)) edit(c, svc, r, k, redo); });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); rl.topMargin = (int) (6 * d);
            body.addView(row, rl);
        }
        if (svc != null && !svc.aprsTunable()) body.addView(Neo.sheetHint(c, "This radio tunes " + svc.profile().bandsText() + ", not " + r.aprsMhz() + " MHz: APRS monitoring is not offered; APRS is still decoded on your own channels."));

        body.addView(Neo.sheetSection(c, "Licence-free radio here"));
        if (!r.chosen) body.addView(Neo.sheetHint(c, "Choose a region to see its licence-free services. Add a zone lists all of them meanwhile."));
        else if (r.licenseFree.isEmpty()) body.addView(Neo.sheetHint(c, "None listed for " + r.name + " yet."));
        else for (Region.Service s : r.licenseFree)
            body.addView(Neo.sheetHint(c, s.name + ": " + String.format(Locale.US, "%.4f–%.4f MHz", s.lowHz / 1e6, s.highHz / 1e6) + (s.channels > 0 ? ", " + s.channels + " ch" : "")
                    + (s.maxPowerW > 0 ? ", " + (s.maxPowerW < 1 ? Math.round(s.maxPowerW * 1000) + " mW" : trim(s.maxPowerW) + " W") : "") + ". " + s.notes
                    + (r.unconfirmed("licenseFree", s.name) ? " (To be confirmed for " + r.name + ".)" : "")));
        if (r.chosen && !r.licenseFree.isEmpty()) body.addView(Neo.sheetHint(c, "A service listed here means the band is licence-free in " + r.name + " for radios approved for it. It does not mean this phone is one: licence-free use usually needs approved equipment, a power limit and a fixed antenna. Check before you transmit."));
        body.addView(Neo.sheetHint(c, "Add a zone (Channels) offers these lists first; the others are still there, marked \"not legal here\". Values marked \"to be confirmed\" are still being checked against the regulator's own pages."));
        self[0] = Neo.showSheet(c, body);
    }
    /** The regions.json field an overridable value belongs to (for "unverified"). */
    private static String key(String k) { return k.equals(Region.CALL2) || k.equals(Region.CALL70) ? "callingHz" : k.equals(Region.UNITS) ? "units" : k; }
    /** The part of that field ("callingHz.2m" / "callingHz.70cm"), or null. */
    private static String part(String k) { return k.equals(Region.CALL2) ? "2m" : k.equals(Region.CALL70) ? "70cm" : null; }
    private static String trim(double w) { return w == Math.rint(w) ? String.valueOf((int) w) : String.valueOf(w); }

    /** Pick a region; asks first whether the user's own values go when they have any. */
    private static void choose(Context c, String id, Runnable done) {
        int n = Region.overrideCount(c);
        if (n == 0) { Region.choose(c, id, false); done.run(); return; }
        new AlertDialog.Builder(c).setTitle("Your own values")
                .setMessage("You changed " + n + " value" + (n == 1 ? "" : "s") + " for the old region. Use the new region's values instead, or keep yours?")
                .setPositiveButton("Use the region's", (dd, w) -> { Region.choose(c, id, true); done.run(); })
                .setNegativeButton("Keep mine", (dd, w) -> { Region.choose(c, id, false); done.run(); }).show();
    }

    /** One value of the user's own. */
    private static void edit(Context c, RadioService svc, Region r, String k, Runnable done) {
        if (k.equals(Region.WEATHER) || k.equals(Region.UNITS)) {
            boolean wx = k.equals(Region.WEATHER);
            String[] names = wx ? new String[] { "Yes: offer the weather channels and SAME alerts", "No: hide them (still under Show all)" } : new String[] { "Kilometres", "Miles" };
            new AlertDialog.Builder(c).setTitle(Region.label(k)).setItems(names, (dd, w) -> { Region.setOverride(c, k, wx ? String.valueOf(w == 0) : w == 0 ? "km" : "mi"); done.run(); }).show();
            return;
        }
        float d = Theme.dp(c);
        boolean minutes = k.equals(Region.STATION_ID);
        LinearLayout b = Neo.sheetBody(c, Region.label(k));
        b.addView(Neo.sheetHint(c, minutes ? "Minutes between identifications (1 to 60): the reminder and the Morse ID use it. The region's: " + r.show(k) + "."
                : "In MHz. " + (k.equals(Region.APRS) ? "APRS monitoring, beacons, telemetry and the iGate use it." : "0 for none.") + " Your region's value stays one tap away (Reset)."));
        EditText e = new EditText(c); e.setTextColor(Neo.TEXT); e.setTypeface(Neo.mono(c)); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (minutes ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL));
        int cur = k.equals(Region.APRS) ? r.aprsHz : k.equals(Region.CALL2) ? r.call2mHz : k.equals(Region.CALL70) ? r.call70Hz : k.equals(Region.SSTV) ? r.sstvHz : r.stationIdMinutes;
        e.setText(minutes ? String.valueOf(cur > 0 ? cur : 10) : String.format(Locale.US, "%.4f", cur / 1e6));
        b.addView(e);
        final AlertDialog[] dlg = new AlertDialog[1];
        TextView save = Neo.button(c, "Save", "check", Neo.GREEN);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); bl.topMargin = (int) (8 * d);
        b.addView(save, bl);
        save.setOnClickListener(v -> {
            String err = check(k, e.getText().toString(), svc);
            if (err != null && !err.startsWith("!")) { Toast.makeText(c, err, Toast.LENGTH_LONG).show(); return; }
            Region.setOverride(c, k, value(k, e.getText().toString()));
            if (err != null) Toast.makeText(c, err.substring(1), Toast.LENGTH_LONG).show();
            dlg[0].dismiss(); done.run();
        });
        dlg[0] = Neo.showSheet(c, b);
    }
    /** The stored text for an entered value (Hz, or minutes). */
    static String value(String k, String in) {
        in = in.trim().replace(',', '.');
        if (k.equals(Region.STATION_ID)) return String.valueOf(Integer.parseInt(in));
        return String.valueOf(Math.round(Double.parseDouble(in) * 1e6));
    }
    /** Why an entered value cannot be used (null: fine; a leading "!" = saved, with a warning). */
    static String check(String k, String in, RadioService svc) {
        in = in.trim().replace(',', '.');
        try {
            if (k.equals(Region.STATION_ID)) { int m = Integer.parseInt(in); return m >= 1 && m <= 60 ? null : "1 to 60 minutes"; }
            double mhz = Double.parseDouble(in);
            if (mhz == 0 && !k.equals(Region.APRS)) return null;
            if (mhz < 30 || mhz > 1000) return "A frequency in MHz, e.g. 144.800";
            long hz = Math.round(mhz * 1e6);
            if (svc != null && !svc.profile().tunable(hz)) return "!Saved. This radio tunes " + svc.profile().bandsText() + ", so it cannot use " + Region.mhz((int) hz) + " MHz";
            return null;
        } catch (NumberFormatException x) { return k.equals(Region.STATION_ID) ? "1 to 60 minutes" : "A frequency in MHz, e.g. 144.800"; }
    }

    public interface Picked { void id(String id); }
    /** The searchable country list (names only), then the three IARU defaults as "Custom" starting points. */
    public static void picker(Context c, Picked picked) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, "Choose your region");
        body.addView(Neo.sheetHint(c, "Countries with their own entry have checked values; any other country uses its IARU region's defaults. Custom starts from an IARU region's values; change any of them afterwards."));
        LinearLayout sf = new LinearLayout(c); sf.setGravity(Gravity.CENTER_VERTICAL);
        sf.setBackground(Neo.card(c, Neo.alpha(Neo.CYAN, 0x33), 12)); sf.setPadding((int) (14 * d), 0, (int) (10 * d), 0);
        ImageView si = new ImageView(c); si.setImageDrawable(new Neo.IconDrawable("search", Neo.MUTED)); sf.addView(si, new LinearLayout.LayoutParams((int) (18 * d), (int) (18 * d)));
        EditText search = new EditText(c); search.setHint("Country"); search.setSingleLine(true); search.setTextSize(16); search.setTypeface(Neo.uiSemi(c));
        search.setTextColor(Neo.TEXT); search.setHintTextColor(Neo.DIM); search.setBackground(null); search.setPadding((int) (10 * d), 0, 0, 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sf.addView(search, new LinearLayout.LayoutParams(0, (int) (44 * d), 1f));
        body.addView(sf, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (46 * d)));
        LinearLayout list = new LinearLayout(c); list.setOrientation(LinearLayout.VERTICAL); body.addView(list);
        RegionTable t = Region.table(c);
        List<String[]> all = t.pickList();
        final AlertDialog[] self = new AlertDialog[1];
        Runnable fill = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT); int shown = 0;
            for (String[] e : all) {
                if (!q.isEmpty() && !e[1].toLowerCase(Locale.ROOT).contains(q) && !e[0].equalsIgnoreCase(q)) continue;
                boolean own = t.hasEntry(e[0]);
                if (q.isEmpty() && !own && !e[0].startsWith("iaru")) continue;          // every country on a search; those with their own entry and Custom otherwise
                shown++;
                TextView row = Neo.text(c, e[1], own ? Neo.TEXT : Neo.TEXT_SOFT, 16, own ? Neo.ui(c) : Neo.uiMedium(c));
                row.setPadding((int) (12 * d), (int) (11 * d), (int) (12 * d), (int) (11 * d)); row.setBackground(Neo.rowCard(c));
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); rl.topMargin = (int) (5 * d);
                list.addView(row, rl);
                row.setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); picked.id(e[0]); });
            }
            if (q.isEmpty()) list.addView(Neo.sheetHint(c, "Type to find any other country."), Math.max(0, list.getChildCount() - 3));
            else if (shown == 0) list.addView(Neo.sheetHint(c, "No country matches."));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { } @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable e) { fill.run(); }
        });
        fill.run();
        self[0] = Neo.showSheet(c, body);
    }
}
