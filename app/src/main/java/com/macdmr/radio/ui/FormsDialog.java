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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.ham.FormStore;
import com.macdmr.radio.ham.Forms;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** PKT → Message forms: ICS-213 and ARRL radiogram — the log, read on air, send by APRS or DMR text, reply. */
public final class FormsDialog {
    private FormsDialog() { }

    public static void show(Context c, RadioService svc) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_forms_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(svc.profile().dmr() ? R.string.modes_forms_hint_dmr : R.string.modes_forms_hint)));
        final AlertDialog[] self = { null };
        LinearLayout r = new LinearLayout(c); body.addView(r);
        btn(c, r, Txt.s(R.string.modes_forms_new_ics), "plus", Neo.GREEN, v -> { if (self[0] != null) self[0].dismiss(); edit(c, svc, blank(Forms.ICS213)); });
        btn(c, r, Txt.s(R.string.modes_forms_new_radiogram), "plus", Neo.AMBER, v -> { if (self[0] != null) self[0].dismiss(); edit(c, svc, blank(Forms.RADIOGRAM)); });
        List<FormStore.Row> rows = svc.forms().list(100);
        body.addView(Neo.sheetSection(c, rows.isEmpty() ? Txt.s(R.string.modes_forms_none) : Txt.s(R.string.modes_forms_list)));
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
        for (FormStore.Row row : rows) {
            String subj = row.form.f.containsKey("SU") ? row.form.f.get("SU") : row.form.f.containsKey("NR") ? Txt.s(R.string.modes_forms_number, row.form.f.get("NR")) : Forms.title(row.form.kind);
            String peer = row.peer.isEmpty() ? "—" : row.peer, when = f.format(new Date(row.ts));
            String sub = row.status.isEmpty() ? Txt.s(row.outgoing ? R.string.modes_forms_row_to : R.string.modes_forms_row_from, peer, row.via, when)
                : Txt.s(row.outgoing ? R.string.modes_forms_row_to_status : R.string.modes_forms_row_from_status, peer, row.via, when, row.status);
            LinearLayout t = Neo.tile(c, row.outgoing ? "send" : "messages", row.outgoing ? Neo.CYAN : Neo.GREEN, Txt.s(Forms.RADIOGRAM.equals(row.form.kind) ? R.string.modes_forms_row_radiogram : R.string.modes_forms_row_ics, subj), sub);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (6 * d);
            body.addView(t, lp);
            t.setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); view(c, svc, row); });
        }
        self[0] = Neo.showSheet(c, body);
    }

    static Forms.Form blank(String kind) {
        Forms.Form f = new Forms.Form(); f.kind = kind;
        String now = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()), hm = new SimpleDateFormat("HHmm", Locale.US).format(new Date());
        f.f.put("DT", now); f.f.put(Forms.RADIOGRAM.equals(kind) ? "TF" : "TM", hm); if (Forms.RADIOGRAM.equals(kind)) f.f.put("PR", "R");
        return f;
    }

    private static void view(Context c, RadioService svc, FormStore.Row row) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Forms.title(row.form.kind));
        body.addView(Neo.sheetHint(c, Txt.s(row.outgoing ? R.string.modes_forms_view_sent : R.string.modes_forms_view_received, row.peer.isEmpty() ? "—" : row.peer, row.via, new SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(row.ts)))));
        String[][] fields = Forms.fields(row.form.kind);
        for (int i = 0; i < fields.length; i++) {
            String[] k = fields[i];
            String v = row.form.f.get(k[0]); if (v == null || v.trim().isEmpty()) continue;
            body.addView(Neo.sheetSection(c, Forms.label(row.form.kind, i).replaceAll(" \\(.*\\)", "")));
            TextView t = Neo.text(c, v, Neo.TEXT, 16, Neo.uiMedium(c)); t.setTextIsSelectable(true); body.addView(t);
        }
        final AlertDialog[] self = { null };
        LinearLayout r = new LinearLayout(c); body.addView(r);
        btn(c, r, Txt.s(R.string.modes_forms_read_on_air), "voice", Neo.GREEN, v -> readOnAir(c, row.form));
        btn(c, r, Txt.s(R.string.common_share), "send", Neo.CYAN, v -> c.startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, Forms.text(row.form)), Txt.s(R.string.modes_forms_share_title))));
        LinearLayout r2 = new LinearLayout(c); body.addView(r2);
        btn(c, r2, Txt.s(R.string.modes_forms_reply), "messages", Neo.AMBER, v -> {
            if (self[0] != null) self[0].dismiss();
            Forms.Form n = blank(row.form.kind);
            if (Forms.ICS213.equals(row.form.kind)) { n.f.put("TO", nz(row.form.f.get("FR"))); n.f.put("FR", nz(row.form.f.get("TO"))); n.f.put("SU", "Re: " + nz(row.form.f.get("SU"))); }   // i18n: subject sent in the form
            else { n.f.put("TO", nz(row.form.f.get("SG"))); }
            edit(c, svc, n);
        });
        btn(c, r2, Txt.s(R.string.common_delete), "stop", Neo.RED, v -> new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_forms_delete_confirm)).setPositiveButton(Txt.s(R.string.common_delete), (dd, w) -> { svc.forms().delete(row.id); if (self[0] != null) self[0].dismiss(); show(c, svc); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show());
        self[0] = Neo.showSheet(c, body);
    }
    private static String nz(String s) { return s == null ? "" : s; }

    /** Large, plain text to read over the air. */
    private static void readOnAir(Context c, Forms.Form f) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_forms_read_on_air), "radio");
        TextView t = Neo.text(c, Forms.text(f), Neo.TEXT, 24, Neo.uiMedium(c)); t.setLineSpacing(0, 1.25f); t.setPadding((int) (4 * d), (int) (6 * d), (int) (4 * d), (int) (6 * d));
        body.addView(t);
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_forms_read_hint)));
        Neo.showSheet(c, body);
    }

    private static void edit(Context c, RadioService svc, Forms.Form form) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Forms.title(form.kind));
        final Map<String, EditText> in = new LinkedHashMap<>();
        String[][] fields = Forms.fields(form.kind);
        for (int i = 0; i < fields.length; i++) {
            String[] k = fields[i];
            body.addView(Neo.sheetSection(c, Forms.label(form.kind, i)));
            EditText e = new EditText(c); e.setText(nz(form.f.get(k[0]))); e.setTextColor(Neo.TEXT); e.setTextSize(16); e.setBackground(Neo.rowCard(c)); e.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
            if (k[0].equals("MS")) { e.setMinLines(4); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); }
            else e.setSingleLine(true);
            body.addView(e); in.put(k[0], e);
        }
        if (Forms.RADIOGRAM.equals(form.kind)) body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_forms_check_hint)));
        LinearLayout r = new LinearLayout(c); body.addView(r);
        final AlertDialog dlg;
        final Runnable collect = () -> { for (Map.Entry<String, EditText> e : in.entrySet()) form.f.put(e.getKey(), e.getValue().getText().toString().trim()); if (Forms.RADIOGRAM.equals(form.kind)) form.f.put("CK", String.valueOf(Forms.words(form.f.get("MS")))); };
        TextView aprs = Neo.button(c, Txt.s(R.string.modes_forms_send_aprs), "packet", Neo.GREEN), dmr = Neo.button(c, Txt.s(R.string.modes_forms_send_dmr), "messages", Neo.CYAN), save = Neo.button(c, Txt.s(R.string.common_save), "check", Neo.MUTED);
        TextView wl = Neo.button(c, Txt.s(R.string.modes_forms_send_winlink), "mail", Neo.VIOLET);
        boolean dmrOk = svc.profile().dmr();                                   // no DMR texts on an analog-only radio (the W6)
        for (TextView b : dmrOk ? new TextView[] { aprs, dmr } : new TextView[] { aprs }) { b.setTextSize(14); Neo.fit(b); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (48 * d), 1f); lp.setMargins((int) (3 * d), (int) (12 * d), (int) (3 * d), 0); r.addView(b, lp); }
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (48 * d)); wlp.topMargin = (int) (8 * d); body.addView(wl, wlp);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (48 * d)); sl.topMargin = (int) (8 * d); body.addView(save, sl);
        dlg = Neo.showSheet(c, body);
        save.setOnClickListener(v -> { collect.run(); String e = svc.sendForm(form, "save", null, null); toast(c, e == null ? Txt.s(R.string.modes_forms_saved) : e); if (e == null) { dlg.dismiss(); show(c, svc); } });
        wl.setOnClickListener(v -> {
            collect.run();
            final EditText to = new EditText(c); to.setHint(Txt.s(R.string.modes_forms_winlink_to_hint)); to.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_forms_send_winlink)).setMessage(Txt.s(R.string.modes_forms_winlink_message)).setView(to)
                .setPositiveButton(Txt.s(R.string.modes_forms_winlink_put), (dd, w) -> { String e = svc.sendForm(form, "winlink", to.getText().toString(), null); toast(c, e == null ? Txt.s(R.string.modes_forms_winlink_done) : e); if (e == null) { dlg.dismiss(); show(c, svc); } })
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        });
        aprs.setOnClickListener(v -> {
            collect.run();
            final EditText to = new EditText(c); to.setHint(Txt.s(R.string.modes_forms_aprs_to_hint)); to.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            int n = Forms.split(form, 67).size();
            new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_forms_send_aprs)).setMessage(Txt.p(R.plurals.modes_forms_aprs_message, n, n)).setView(to)
                .setPositiveButton(Txt.s(R.string.common_send), (dd, w) -> { String e = svc.sendForm(form, "aprs", to.getText().toString(), null); toast(c, e == null ? Txt.p(R.plurals.modes_forms_aprs_sending, n, n) : e); if (e == null) { dlg.dismiss(); show(c, svc); } })
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        });
        dmr.setOnClickListener(v -> {
            collect.run();
            final List<ChannelStore.Contact> cs = svc.store().contacts();
            if (cs.isEmpty()) { toast(c, Txt.s(R.string.modes_forms_no_contacts)); return; }
            String[] names = new String[cs.size()]; for (int i = 0; i < names.length; i++) names[i] = Txt.s(cs.get(i).type == 1 ? R.string.modes_forms_contact_tg : R.string.modes_forms_contact_id, cs.get(i).name.isEmpty() ? String.valueOf(cs.get(i).number) : cs.get(i).name, String.valueOf(cs.get(i).number));
            int n = Forms.split(form, 120).size();
            new AlertDialog.Builder(c).setTitle(Txt.p(R.plurals.modes_forms_dmr_title, n, n)).setItems(names, (dd, w) -> { String e = svc.sendForm(form, "dmr", null, cs.get(w)); toast(c, e == null ? Txt.s(R.string.modes_forms_sending) : e); if (e == null) { dlg.dismiss(); show(c, svc); } }).show();
        });
    }

    private static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); }
    private static void btn(Context c, LinearLayout r, String label, String icon, int accent, android.view.View.OnClickListener l) {
        float d = Theme.dp(c); TextView b = Neo.button(c, label, icon, accent); b.setTextSize(14); Neo.fit(b); b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (48 * d), 1f); lp.setMargins((int) (3 * d), (int) (8 * d), (int) (3 * d), 0); r.addView(b, lp);
    }
}
