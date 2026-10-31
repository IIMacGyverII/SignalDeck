/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.ham.Maidenhead;
import com.macdmr.radio.repeaters.GeoUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The contact editor, the contact sheet (details, history, actions) and the identity link: any callsign, DMR ID or
 * pager RIC the app shows is a tap target that opens the matching contact, or offers to add one with the identity
 * filled in. Shared by the Contacts page, Last heard, the APRS and M17 screens and the POCSAG page.
 */
public final class ContactEditor {
    private ContactEditor() { }

    /** What an identity tap carries: whichever of the three is known, plus the position and time it was heard with. */
    public static final class Identity {
        public String callsign = ""; public int dmrId; public int ric = -1;
        public double lat = Double.NaN, lon = Double.NaN; public long ts; public String source = "";
        public static Identity call(String c) { Identity i = new Identity(); i.callsign = c == null ? "" : c.trim().toUpperCase(Locale.US); return i; }
        public static Identity dmr(int id) { Identity i = new Identity(); i.dmrId = id; return i; }
        public static Identity ric(int r) { Identity i = new Identity(); i.ric = r; return i; }
        public Identity at(double la, double lo, long t, String src) { lat = la; lon = lo; ts = t; source = src == null ? "" : src; return this; }
        public String text() { return !callsign.isEmpty() ? callsign : dmrId > 0 ? "ID " + dmrId : ric >= 0 ? "RIC " + ric : ""; }   // i18n: protocol words (ID, RIC)
        public boolean hasPosition() { return !Double.isNaN(lat) && !Double.isNaN(lon); }
    }

    /** Finds the contact an identity belongs to, or null. */
    public static ChannelStore.Contact find(RadioService svc, Identity id) {
        ChannelStore store = svc.store();
        ChannelStore.Contact ct = null;
        if (!id.callsign.isEmpty()) ct = store.contactByCallsign(id.callsign);
        if (ct == null && id.dmrId > 0) ct = store.contactByNumber(id.dmrId, 0);
        if (ct == null && id.ric >= 0) ct = store.contactByRic(id.ric);
        return ct;
    }

    /** The tap on an identity: the contact's sheet when it is known, else the offer to add it. */
    public static void open(Context c, RadioService svc, Identity id, Runnable onChanged) {
        if (svc == null) return;
        ChannelStore.Contact ct = find(svc, id);
        if (ct != null) { sheet(c, svc, ct, onChanged); return; }
        float d = Theme.dp(c);
        LinearLayout box = Neo.sheetBody(c, id.text());
        box.addView(Neo.sheetHint(c, !id.hasPosition() ? Txt.s(R.string.channels_contact_unknown)
                : id.ts > 0 ? Txt.s(R.string.channels_contact_unknown_heard_ago, fmtPos(id.lat, id.lon), ago(id.ts)) : Txt.s(R.string.channels_contact_unknown_heard, fmtPos(id.lat, id.lon))));
        final AlertDialog[] dlg = new AlertDialog[1];
        TextView add = Neo.button(c, Txt.s(R.string.channels_contact_add), "plus", Neo.GREEN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.setMargins(0, (int) (12 * d), 0, 0);
        box.addView(add, lp);
        add.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            ChannelStore.Contact n = new ChannelStore.Contact();
            n.callsign = id.callsign; n.number = id.dmrId; n.type = 0; if (id.ric >= 0) n.rics = String.valueOf(id.ric);
            if (!id.callsign.isEmpty()) { n.name = id.callsign; n.callsignAuto = 2; }
            else if (id.dmrId > 0) { com.macdmr.radio.RadioIdStore.Record r = svc.radioId() != null ? svc.radioId().lookup(id.dmrId) : null; if (r != null) { n.name = r.display(); n.callsign = r.callsign; n.callsignAuto = 1; } }
            if (id.hasPosition()) { n.lastLat = id.lat; n.lastLon = id.lon; n.lastTs = id.ts; n.lastSource = id.source; }
            edit(c, svc, n, saved -> { if (id.hasPosition()) svc.store().contactHeard(saved.id, id.ts, id.source, "", id.lat, id.lon); if (onChanged != null) onChanged.run(); });
        });
        if (!id.callsign.isEmpty() || id.ric >= 0) {                       // a DMR ID cannot be merged: a contact holds one
            TextView merge = Neo.button(c, Txt.s(R.string.channels_contact_merge), "contacts", Neo.CYAN);
            box.addView(merge, new LinearLayout.LayoutParams(lp));
            merge.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); pick(c, svc, id, onChanged); });
        }
        dlg[0] = Neo.showSheet(c, box);
    }

    private static void pick(Context c, RadioService svc, Identity id, Runnable onChanged) {
        final List<ChannelStore.Contact> all = svc.store().contacts();
        if (all.isEmpty()) { android.widget.Toast.makeText(c, Txt.s(R.string.channels_contact_none_yet), android.widget.Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[all.size()];
        for (int i = 0; i < names.length; i++) { ChannelStore.Contact ct = all.get(i); names[i] = (ct.name.isEmpty() ? (ct.number > 0 ? "ID " + ct.number : Txt.s(R.string.channels_contact_no_name)) : ct.name) + (ct.callsign.isEmpty() ? "" : "  ·  " + ct.callsign); }   // i18n: protocol word (ID) and data
        new AlertDialog.Builder(c).setTitle(Txt.s(R.string.channels_contact_merge_title, id.text())).setItems(names, (dd, w) -> {
            ChannelStore.Contact ct = all.get(w);
            if (!id.callsign.isEmpty()) {
                if (!ct.callsign.isEmpty() && !ct.callsign.equalsIgnoreCase(id.callsign)) { ct.notes = (ct.notes.isEmpty() ? "" : ct.notes + "\n") + Txt.s(R.string.channels_contact_also, id.callsign); }
                else { ct.callsign = id.callsign; ct.callsignAuto = 2; }
            }
            if (id.ric >= 0) { int[] have = ct.ricList(); boolean has = false; for (int r : have) if (r == id.ric) has = true; if (!has) ct.rics = ChannelStore.ricText(have) + (have.length > 0 ? ";" : "") + id.ric; }
            svc.store().updateContact(ct);
            if (id.hasPosition()) svc.store().contactHeard(ct.id, id.ts, id.source, "", id.lat, id.lon);
            android.widget.Toast.makeText(c, Txt.s(R.string.channels_contact_added_to, ct.name.isEmpty() ? id.text() : ct.name), android.widget.Toast.LENGTH_SHORT).show();
            if (onChanged != null) onChanged.run();
        }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    public interface Saved { void saved(ChannelStore.Contact ct); }

    /** The editor: identity, locations and notes. {@code existing.id == 0} means a new contact. */
    public static void edit(final Context c, final RadioService svc, final ChannelStore.Contact ct, final Saved onSaved) {
        final boolean isNew = ct.id == 0;
        float d = Theme.dp(c);
        ScrollView sv = new ScrollView(c);
        LinearLayout box = new LinearLayout(c); box.setOrientation(LinearLayout.VERTICAL); box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        sv.addView(box);
        final EditText name = field(c, box, Txt.s(R.string.channels_contact_f_name), ct.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        final EditText num = field(c, box, Txt.s(R.string.channels_contact_f_number), ct.number > 0 ? String.valueOf(ct.number) : "", InputType.TYPE_CLASS_NUMBER);
        LinearLayout types = new LinearLayout(c); types.setBaselineAligned(false);
        final int[] t = { Math.min(2, Math.max(0, ct.type)) };
        final TextView[] tb = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            tb[i] = Neo.button(c, ContactsView.type(i), null, ContactsView.typeColor(i)); tb[i].setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins(i == 0 ? 0 : (int) (3 * d), (int) (10 * d), i == 2 ? 0 : (int) (3 * d), (int) (4 * d));
            types.addView(tb[i], lp);
            tb[i].setOnClickListener(v -> { t[0] = k; for (int j = 0; j < 3; j++) tb[j].setAlpha(j == k ? 1f : 0.45f); });
        }
        for (int j = 0; j < 3; j++) tb[j].setAlpha(j == t[0] ? 1f : 0.45f);
        box.addView(types);
        final EditText call = field(c, box, Txt.s(R.string.channels_contact_f_call), ct.callsign, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (ct.callsignAuto == 1 && !ct.callsign.isEmpty()) box.addView(Neo.sheetHint(c, Txt.s(R.string.channels_contact_call_auto)));
        final EditText rics = field(c, box, Txt.s(R.string.channels_contact_f_rics), ct.rics, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText home = field(c, box, Txt.s(R.string.channels_contact_f_home), ct.hasHome() ? fmtHome(ct) : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText homeLabel = field(c, box, Txt.s(R.string.channels_contact_f_home_label), ct.homeLabel, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (ct.hasLast()) box.addView(Neo.sheetHint(c, ct.lastSource.isEmpty() ? Txt.s(R.string.channels_contact_last_hint, fmtPos(ct.lastLat, ct.lastLon), ago(ct.lastTs))
                : Txt.s(R.string.channels_contact_last_hint_by, fmtPos(ct.lastLat, ct.lastLon), ago(ct.lastTs), ct.lastSource)));
        final EditText notes = field(c, box, Txt.s(R.string.channels_contact_f_notes), ct.notes, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setSingleLine(false); notes.setMinLines(2); notes.setMaxLines(6);
        new AlertDialog.Builder(c).setTitle(Txt.s(isNew ? R.string.channels_contact_new_title : R.string.channels_contact_edit_title)).setView(sv)
                .setPositiveButton(Txt.s(R.string.common_save), (dd, w) -> {
                    try {
                        String cs = call.getText().toString().trim().toUpperCase(Locale.US);
                        if (!cs.isEmpty() && !cs.matches("[A-Z0-9]{1,9}(-[0-9]{1,2})?")) throw new IllegalArgumentException(Txt.s(R.string.channels_contact_err_call));
                        String ns = num.getText().toString().trim();
                        int n = ns.isEmpty() ? 0 : Integer.parseInt(ns);
                        if (n < 0 || n > 16776415) throw new IllegalArgumentException(Txt.s(R.string.channels_contact_err_id));
                        String rt = rics.getText().toString().trim();
                        int[] rl = ChannelStore.parseRics(rt);
                        if (!rt.isEmpty() && rl.length == 0) throw new IllegalArgumentException(Txt.s(R.string.channels_contact_err_rics));
                        if (n == 0 && cs.isEmpty() && rl.length == 0) throw new IllegalArgumentException(Txt.s(R.string.channels_contact_err_empty));
                        String h = home.getText().toString().trim();
                        double[] hp = h.isEmpty() ? null : parseLocation(h);
                        if (!h.isEmpty() && hp == null) throw new IllegalArgumentException(Txt.s(R.string.channels_contact_err_home));
                        ct.name = name.getText().toString().trim(); ct.number = n; ct.type = t[0];
                        if (!cs.equals(ct.callsign)) ct.callsignAuto = 2;
                        ct.callsign = cs; ct.rics = ChannelStore.ricText(rl); ct.notes = notes.getText().toString().trim(); ct.homeLabel = homeLabel.getText().toString().trim();
                        ct.homeLat = hp == null ? Double.NaN : hp[0]; ct.homeLon = hp == null ? Double.NaN : hp[1];
                        if (ct.name.isEmpty()) ct.name = !cs.isEmpty() ? cs : n > 0 ? "ID " + n : "RIC " + rl[0];   // i18n: protocol words (ID, RIC), stored as the name
                        if (isNew) svc.store().insertContact(ct); else svc.store().updateContact(ct);
                        if (onSaved != null) onSaved.saved(ct);
                    } catch (Exception e) { android.widget.Toast.makeText(c, Txt.s(R.string.channels_contact_not_saved, String.valueOf(e.getMessage())), android.widget.Toast.LENGTH_LONG).show(); }
                }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    /** The contact's sheet: identity, locations, notes, history, and the actions (message, edit, delete). */
    public static void sheet(final Context c, final RadioService svc, final ChannelStore.Contact ct, final Runnable onChanged) {
        float d = Theme.dp(c);
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout box = Neo.sheetBody(c, ct.name.isEmpty() ? (ct.callsign.isEmpty() ? "ID " + ct.number : ct.callsign) : ct.name);   // i18n: protocol word (ID) and data
        LinearLayout top = new LinearLayout(c); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ContactsView.avatar(c, ct.type, ct.name, 48), new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d)));
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setPadding((int) (12 * d), 0, 0, 0);
        LinearLayout l2 = new LinearLayout(c); l2.setGravity(Gravity.CENTER_VERTICAL);
        if (ct.number > 0) { l2.addView(Neo.chip(c, ContactsView.type(ct.type), ContactsView.typeColor(ct.type))); TextView idv = Neo.text(c, String.valueOf(ct.number), Neo.TEXT_SOFT, 15, Neo.mono(c)); idv.setPadding((int) (8 * d), 0, (int) (8 * d), 0); l2.addView(idv); }
        if (!ct.callsign.isEmpty()) l2.addView(Neo.chip(c, ct.callsign, Neo.GREEN));
        col.addView(l2);
        int[] rl = ct.ricList();
        if (rl.length > 0) { TextView r = Neo.text(c, Txt.s(R.string.channels_contact_pager, ChannelStore.ricText(rl).replace(";", ", ")), Neo.AMBER_LIGHT, 13, Neo.mono(c)); r.setPadding(0, (int) (4 * d), 0, 0); col.addView(r); }
        top.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(top);
        android.location.Location me = svc.lastKnownLocation();
        if (ct.hasHome()) box.addView(line(c, Txt.s(R.string.channels_contact_home), (ct.homeLabel.isEmpty() ? "" : ct.homeLabel + "  ·  ") + fmtPos(ct.homeLat, ct.homeLon) + fromMe(me, ct.homeLat, ct.homeLon)));
        String when = ct.lastSource.isEmpty() ? ago(ct.lastTs) : Txt.s(R.string.channels_contact_ago_by, ago(ct.lastTs), ct.lastSource);
        if (ct.hasLast()) box.addView(line(c, Txt.s(R.string.channels_contact_last_heard), fmtPos(ct.lastLat, ct.lastLon) + fromMe(me, ct.lastLat, ct.lastLon) + "  ·  " + when));
        else if (ct.lastTs > 0) box.addView(line(c, Txt.s(R.string.channels_contact_last_heard), when));
        if (!ct.notes.isEmpty()) { TextView n = Neo.label(c, ct.notes, 0xFFD6DEE6, 13.5f, false); n.setPadding(0, (int) (8 * d), 0, 0); box.addView(n); }
        List<ChannelStore.Heard> hist = svc.store().contactHeard(ct.id, 40);
        if (!hist.isEmpty()) {
            box.addView(Neo.sheetSection(c, Txt.s(R.string.channels_contact_history_n, svc.store().contactHeardCount(ct.id))));
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            for (ChannelStore.Heard h : hist) {
                LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.VERTICAL); row.setBackground(Neo.rowCard(c)); row.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), (int) (6 * d));
                row.addView(Neo.label(c, Txt.s(h.sent ? R.string.channels_contact_hist_sent : R.string.channels_contact_hist_heard, h.source) + (h.detail.isEmpty() ? "" : "  ·  " + h.detail), h.sent ? Neo.AMBER_LIGHT : Neo.CYAN, 13, true));
                row.addView(Neo.label(c, f.format(new Date(h.ts)) + (h.hasPosition() ? "  ·  " + fmtPos(h.lat, h.lon) + fromMe(me, h.lat, h.lon) : ""), 0xFFBFC8D0, 12, false));
                if (h.hasPosition()) { final ChannelStore.Heard hh = h; row.setClickable(true); row.setOnClickListener(v -> openMap(c, hh.lat, hh.lon, ct.name)); }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (4 * d);
                box.addView(row, lp);
            }
        }
        LinearLayout btns = new LinearLayout(c); btns.setBaselineAligned(false);
        String[] names = { Txt.s(R.string.channels_contact_message), Txt.s(R.string.common_edit), Txt.s(R.string.common_delete) }; int[] accents = { Neo.GREEN, Neo.CYAN, Neo.RED };
        for (int i = 0; i < 3; i++) {
            final int k = i;
            if (k == 0 && ct.number <= 0) continue;
            TextView b = Neo.button(c, names[i], null, accents[i]); b.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins((int) (3 * d), (int) (16 * d), (int) (3 * d), 0);
            btns.addView(b, lp);
            b.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                if (k == 0) { if (c instanceof com.macdmr.radio.MainActivity) ((com.macdmr.radio.MainActivity) c).openThread(ct); }
                else if (k == 1) edit(c, svc, ct, x -> { if (onChanged != null) onChanged.run(); });
                else new AlertDialog.Builder(c).setTitle(Txt.s(R.string.channels_contact_delete_title, ct.name)).setPositiveButton(Txt.s(R.string.common_delete), (dd, w) -> { svc.store().deleteContact(ct); if (onChanged != null) onChanged.run(); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
            });
        }
        box.addView(btns);
        dlg[0] = Neo.showSheet(c, box);
    }

    // ---- helpers ----
    private static EditText field(Context c, LinearLayout box, String label, String value, int inputType) {
        float d = Theme.dp(c);
        TextView l = Neo.label(c, label, 0xFF9AA4AE, 12, false); l.setPadding(0, (int) (10 * d), 0, 0); box.addView(l);
        EditText e = new EditText(c); e.setText(value == null ? "" : value); e.setInputType(inputType); e.setTextSize(16); e.setTextColor(Neo.TEXT); e.setTypeface(Neo.uiSemi(c)); e.setSingleLine(true);
        box.addView(e);
        return e;
    }
    private static TextView line(Context c, String k, String v) { float d = Theme.dp(c); TextView t = Neo.label(c, Txt.s(R.string.channels_contact_line, k, v), 0xFFD6DEE6, 13.5f, false); t.setPadding(0, (int) (8 * d), 0, 0); return t; }
    private static void openMap(Context c, double lat, double lon, String label) {
        try { c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)", lat, lon, lat, lon, android.net.Uri.encode(label))))); } catch (Throwable t) { }   // i18n: geo URI
    }

    /** "EM12ab" or "32.7767, -96.7970" to {lat, lon}; null when neither. */
    public static double[] parseLocation(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        double[] g = GeoUtil.maidenheadToLatLon(t);
        if (g != null) return g;
        String[] p = t.split("[,\\s]+");
        if (p.length != 2) return null;
        try { double la = Double.parseDouble(p[0]), lo = Double.parseDouble(p[1]); if (Math.abs(la) > 90 || Math.abs(lo) > 180) return null; return new double[] { la, lo }; } catch (Exception e) { return null; }
    }
    static String fmtHome(ChannelStore.Contact ct) { return String.format(Locale.US, "%.5f, %.5f", ct.homeLat, ct.homeLon); }
    public static String fmtPos(double lat, double lon) { return Maidenhead.grid(lat, lon, 6) + String.format(Locale.US, " (%.4f, %.4f)", lat, lon); }
    static String fromMe(android.location.Location me, double lat, double lon) {
        if (me == null) return "";
        double km = Maidenhead.km(me.getLatitude(), me.getLongitude(), lat, lon);
        return "  ·  " + com.macdmr.radio.repeaters.GeoUtil.dist(km * 1000) + " " + (Maidenhead.compass(Maidenhead.bearing(me.getLatitude(), me.getLongitude(), lat, lon)));
    }
    public static String ago(long ts) {
        long s = (System.currentTimeMillis() - ts) / 1000;
        if (s < 60) return Txt.s(R.string.channels_contact_just_now); if (s < 3600) return Txt.s(R.string.channels_contact_min_ago, s / 60); if (s < 86400) return Txt.s(R.string.channels_contact_h_ago, s / 3600);

        return new SimpleDateFormat("MMM d, HH:mm", Locale.US).format(new Date(ts));
    }
}
