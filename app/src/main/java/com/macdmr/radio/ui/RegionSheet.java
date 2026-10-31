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

import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.i18n.Txt;
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
        Toast.makeText(c, Txt.s(R.string.channels_region_locked), Toast.LENGTH_LONG).show();
        return false;
    }

    /** "Belgium" / "Custom: IARU Region 1 …" / "Not chosen". */
    public static String title(Region r) { return !r.chosen ? Txt.s(R.string.channels_region_not_chosen) : r.id.startsWith("iaru") ? Txt.s(R.string.channels_region_custom, r.name) : r.name; }
    /** One line for the Device row. */
    public static String summary(Context c, Region r) {
        if (!r.chosen) return Txt.s(R.string.channels_region_summary_unchosen);
        return r.overridden.isEmpty() ? Txt.s(R.string.channels_region_summary, title(r), r.aprsMhz())
                : Txt.p(R.plurals.channels_region_summary_changed, r.overridden.size(), title(r), r.aprsMhz(), r.overridden.size());
    }

    public static void show(Context c, RadioService svc, Runnable changed) {
        float d = Theme.dp(c);
        Region r = Region.current(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.channels_region_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_intro)));
        String how = Region.how(c), roam = Region.roamingIn(c);
        TextView cur = Neo.text(c, title(r), Neo.CYAN_LIGHT, 20, Neo.ui(c)); body.addView(cur);
        if (r.chosen) body.addView(Neo.sheetHint(c, roam == null && !r.generic ? Region.howText(how)
                : roam == null ? Txt.s(R.string.channels_region_how_generic, Region.howText(how), r.iaru)
                : Txt.s(r.generic ? R.string.channels_region_how_roam_generic : R.string.channels_region_how_roam, Region.howText(how), RegionTable.countryName(roam), r.iaru)));

        else body.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_unchosen_hint)));
        final AlertDialog[] self = new AlertDialog[1];
        Runnable redo = () -> { if (self[0] != null) self[0].dismiss(); if (changed != null) changed.run(); show(c, svc, changed); };
        TextView pick = Neo.button(c, Txt.s(r.chosen ? R.string.channels_region_choose_another : R.string.channels_region_choose), "globe", Neo.CYAN);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); bl.topMargin = (int) (6 * d);
        body.addView(pick, bl);
        pick.setOnClickListener(v -> { if (allowed(c, svc)) picker(c, id -> choose(c, id, redo)); });

        body.addView(Neo.sheetSection(c, Txt.s(R.string.channels_region_section_sets)));
        for (String k : Region.VALUES) {
            LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL); row.setBackground(Neo.rowCard(c));
            row.setPadding((int) (14 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
            LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Neo.text(c, Region.label(k), Neo.MUTED, 13, Neo.uiMedium(c)));
            boolean own = r.overridden.contains(k), unv = r.unconfirmed(key(k), part(k));
            col.addView(Neo.text(c, own ? Txt.s(R.string.channels_region_value_yours, r.show(k)) : unv ? Txt.s(R.string.channels_region_value_unconfirmed, r.show(k)) : r.show(k), own ? Neo.AMBER_LIGHT : Neo.TEXT, 16, Neo.ui(c)));
            row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (own) { TextView reset = Neo.chip(c, Txt.s(R.string.channels_region_reset), Neo.AMBER); row.addView(reset); reset.setOnClickListener(v -> { if (allowed(c, svc)) { Region.setOverride(c, k, null); redo.run(); } }); }
            TextView ch = Neo.chip(c, Txt.s(R.string.channels_region_change), Neo.CYAN); LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); cl.leftMargin = (int) (6 * d);
            row.addView(ch, cl); ch.setOnClickListener(v -> { if (allowed(c, svc)) edit(c, svc, r, k, redo); });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); rl.topMargin = (int) (6 * d);
            body.addView(row, rl);
        }
        if (svc != null && !svc.aprsTunable()) body.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_aprs_untunable, svc.profile().bandsText(), r.aprsMhz())));

        body.addView(Neo.sheetSection(c, Txt.s(R.string.channels_region_section_free)));
        if (!r.chosen) body.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_free_unchosen)));
        else if (r.licenseFree.isEmpty()) body.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_free_none, r.name)));
        else for (Region.Service s : r.licenseFree) {
            String sep = Txt.s(R.string.channels_region_list_sep);
            String what = Txt.freqMhz(s.lowHz, 4) + "–" + Txt.freqMhz(s.highHz, 4) + " MHz" + (s.channels > 0 ? sep + Txt.s(R.string.channels_region_service_ch, s.channels) : "")
                    + (s.maxPowerW > 0 ? sep + (s.maxPowerW < 1 ? Math.round(s.maxPowerW * 1000) + " mW" : Txt.decimalText(trim(s.maxPowerW)) + " W") : "");
            body.addView(Neo.sheetHint(c, r.unconfirmed("licenseFree", s.name) ? Txt.s(R.string.channels_region_service_unconfirmed, s.name, what, s.notes, r.name)
                    : Txt.s(R.string.channels_region_service, s.name, what, s.notes)));
        }
        if (r.chosen && !r.licenseFree.isEmpty()) body.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_free_caution, r.name)));
        body.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_free_footer)));
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
        new AlertDialog.Builder(c).setTitle(Txt.s(R.string.channels_region_own_title))
                .setMessage(Txt.p(R.plurals.channels_region_own_message, n, n))
                .setPositiveButton(Txt.s(R.string.channels_region_own_use), (dd, w) -> { Region.choose(c, id, true); done.run(); })
                .setNegativeButton(Txt.s(R.string.channels_region_own_keep), (dd, w) -> { Region.choose(c, id, false); done.run(); }).show();
    }

    /** One value of the user's own. */
    private static void edit(Context c, RadioService svc, Region r, String k, Runnable done) {
        if (k.equals(Region.WEATHER) || k.equals(Region.UNITS)) {
            boolean wx = k.equals(Region.WEATHER);
            String[] names = wx ? new String[] { Txt.s(R.string.channels_region_wx_yes), Txt.s(R.string.channels_region_wx_no) } : new String[] { Txt.s(R.string.channels_region_km), Txt.s(R.string.channels_region_miles) };
            new AlertDialog.Builder(c).setTitle(Region.label(k)).setItems(names, (dd, w) -> { Region.setOverride(c, k, wx ? String.valueOf(w == 0) : w == 0 ? "km" : "mi"); done.run(); }).show();
            return;
        }
        float d = Theme.dp(c);
        boolean minutes = k.equals(Region.STATION_ID);
        LinearLayout b = Neo.sheetBody(c, Region.label(k), minutes ? "globe" : k.equals(Region.SSTV) ? "report" : "radio");
        b.addView(Neo.sheetHint(c, minutes ? Txt.s(R.string.channels_region_edit_minutes, r.show(k))
                : Txt.s(k.equals(Region.APRS) ? R.string.channels_region_edit_aprs : R.string.channels_region_edit_mhz)));
        EditText e = new EditText(c); e.setTextColor(Neo.TEXT); e.setTypeface(Neo.mono(c)); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (minutes ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL));
        int cur = k.equals(Region.APRS) ? r.aprsHz : k.equals(Region.CALL2) ? r.call2mHz : k.equals(Region.CALL70) ? r.call70Hz : k.equals(Region.SSTV) ? r.sstvHz : r.stationIdMinutes;
        e.setText(minutes ? String.valueOf(cur > 0 ? cur : 10) : String.format(Locale.US, "%.4f", cur / 1e6));
        b.addView(e);
        final AlertDialog[] dlg = new AlertDialog[1];
        TextView save = Neo.button(c, Txt.s(R.string.common_save), "check", Neo.GREEN);
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
            if (k.equals(Region.STATION_ID)) { int m = Integer.parseInt(in); return m >= 1 && m <= 60 ? null : Txt.s(R.string.channels_region_err_minutes); }
            double mhz = Double.parseDouble(in);
            if (mhz == 0 && !k.equals(Region.APRS)) return null;
            if (mhz < 30 || mhz > 1000) return Txt.s(R.string.channels_region_err_mhz);
            long hz = Math.round(mhz * 1e6);
            if (svc != null && !svc.profile().tunable(hz)) return "!" + Txt.s(R.string.channels_region_saved_untunable, svc.profile().bandsText(), Region.mhz((int) hz));   // "!": saved, with a warning
            return null;
        } catch (NumberFormatException x) { return Txt.s(k.equals(Region.STATION_ID) ? R.string.channels_region_err_minutes : R.string.channels_region_err_mhz); }
    }

    public interface Picked { void id(String id); }
    /** The searchable country list (names only), then the three IARU defaults as "Custom" starting points. */
    public static void picker(Context c, Picked picked) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.channels_region_picker_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_picker_hint)));
        LinearLayout sf = new LinearLayout(c); sf.setGravity(Gravity.CENTER_VERTICAL);
        sf.setBackground(Neo.card(c, Neo.alpha(Neo.CYAN, 0x33), 12)); sf.setPadding((int) (14 * d), 0, (int) (10 * d), 0);
        ImageView si = new ImageView(c); si.setImageDrawable(new Neo.IconDrawable("search", Neo.MUTED)); sf.addView(si, new LinearLayout.LayoutParams((int) (18 * d), (int) (18 * d)));
        EditText search = new EditText(c); search.setHint(Txt.s(R.string.channels_region_picker_search)); search.setSingleLine(true); search.setTextSize(16); search.setTypeface(Neo.uiSemi(c));
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
            if (q.isEmpty()) list.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_picker_type)), Math.max(0, list.getChildCount() - 3));
            else if (shown == 0) list.addView(Neo.sheetHint(c, Txt.s(R.string.channels_region_picker_none)));

        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { } @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable e) { fill.run(); }
        });
        fill.run();
        self[0] = Neo.showSheet(c, body);
    }
}
