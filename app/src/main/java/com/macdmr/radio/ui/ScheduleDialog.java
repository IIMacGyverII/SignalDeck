/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.Scheduler;
import com.macdmr.radio.i18n.Txt;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** PKT → Scheduled operations: the list (next start, on/off) and the editor. */
public final class ScheduleDialog {
    private ScheduleDialog() { }
    private static final int[] DAY = { R.string.intercom_schedule_day_mon_short, R.string.intercom_schedule_day_tue_short, R.string.intercom_schedule_day_wed_short,
            R.string.intercom_schedule_day_thu_short, R.string.intercom_schedule_day_fri_short, R.string.intercom_schedule_day_sat_short, R.string.intercom_schedule_day_sun_short };
    private static final int[] DAY_FULL = { R.string.intercom_schedule_day_mon, R.string.intercom_schedule_day_tue, R.string.intercom_schedule_day_wed,
            R.string.intercom_schedule_day_thu, R.string.intercom_schedule_day_fri, R.string.intercom_schedule_day_sat, R.string.intercom_schedule_day_sun };

    public static void show(Context c, RadioService svc) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.intercom_schedule_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(com.macdmr.radio.DeviceProfile.inBands(com.macdmr.radio.PacketEngine.APRS_FREQ_HZ) ? R.string.intercom_schedule_hint : R.string.intercom_schedule_hint_no_aprs)));
        final AlertDialog[] self = { null };
        List<Scheduler.Entry> l = Scheduler.list(c);
        SimpleDateFormat f = Txt.dates("EEE d MMM, HH:mm", "EEEdMMMHHmm", false);
        long now = System.currentTimeMillis();
        int running = Scheduler.runningEnd(c) > now ? Scheduler.runningId(c) : 0;
        if (l.isEmpty()) body.addView(Neo.sheetHint(c, Txt.s(R.string.intercom_schedule_empty)));
        for (Scheduler.Entry e : l) {
            long next = Scheduler.nextStart(e, now, java.util.TimeZone.getDefault());
            String sub = e.id == running ? Txt.s(R.string.intercom_schedule_sub_running, e.when(), new SimpleDateFormat("HH:mm", Locale.US).format(new Date(Scheduler.runningEnd(c))))
                    : !e.enabled ? Txt.s(R.string.intercom_schedule_sub_off, e.when()) : next > 0 ? Txt.s(R.string.intercom_schedule_sub_next, e.when(), f.format(new Date(next))) : Txt.s(R.string.intercom_schedule_sub_done, e.when());
            final Neo.NeonSwitch sw = new Neo.NeonSwitch(c); sw.setChecked(e.enabled);
            sw.setOnChange(on -> { e.enabled = on; if (on && e.oneShot() && Scheduler.nextStart(e, System.currentTimeMillis(), java.util.TimeZone.getDefault()) < 0) { Toast.makeText(c, Txt.s(R.string.intercom_schedule_past_toast), Toast.LENGTH_SHORT).show(); sw.setChecked(false); e.enabled = false; } Scheduler.put(c, e); });
            LinearLayout row = Neo.switchRow(c, icon(e.action), e.id == running ? Neo.GREEN : Neo.CYAN, withChannel(svc, e, e.label.isEmpty() ? e.actionName() : e.label), sub, sw);
            row.setBackground(Neo.rowCard(c)); row.setClickable(true);
            row.setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); edit(c, svc, e); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (8 * d);
            body.addView(row, lp);
        }
        TextView add = Neo.button(c, Txt.s(R.string.intercom_schedule_add), "plus", Neo.GREEN); body.addView(add);
        add.setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); edit(c, svc, new Scheduler.Entry()); });
        self[0] = Neo.showSheet(c, body);
    }

    private static String icon(String a) { return Scheduler.SCAN.equals(a) ? "scan" : Scheduler.APRS.equals(a) || Scheduler.BEACON.equals(a) ? "packet" : Scheduler.SSTV.equals(a) ? "sstv" : "radio"; }
    private static String withChannel(RadioService svc, Scheduler.Entry e, String title) {
        if (e.channel < 0 || !Scheduler.usesChannel(e.action)) return title;
        ChannelStore.Channel ch = svc.store().byNumber(e.channel);
        return Txt.s(R.string.intercom_schedule_row_channel, title, ch == null ? Txt.s(R.string.intercom_schedule_channel_n, e.channel) : ch.name);
    }

    private static void edit(Context c, RadioService svc, Scheduler.Entry e) {
        float d = Theme.dp(c);
        boolean isNew = e.id <= 0;
        LinearLayout body = Neo.sheetBody(c, Txt.s(isNew ? R.string.intercom_schedule_new : R.string.intercom_schedule_edit), "radio");
        // what
        body.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_schedule_what)));
        final TextView act = Neo.label(c, "", Neo.CYAN_LIGHT, 17, true); act.setPadding((int) (4 * d), (int) (6 * d), 0, (int) (6 * d)); body.addView(act);
        final LinearLayout chRow = new LinearLayout(c); chRow.setOrientation(LinearLayout.VERTICAL); body.addView(chRow);
        final TextView chV = Neo.label(c, "", Neo.CYAN_LIGHT, 16, true); chV.setPadding((int) (4 * d), (int) (6 * d), 0, (int) (6 * d));
        chRow.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_schedule_channel))); chRow.addView(chV);
        final Neo.NeonSwitch rec = new Neo.NeonSwitch(c); rec.setChecked(e.record);
        final LinearLayout recRow = Neo.switchRow(c, "rec", Neo.RED, Txt.s(R.string.intercom_schedule_record), Txt.s(R.string.intercom_schedule_record_sub), rec);
        body.addView(recRow);
        final List<ChannelStore.Channel> chans = ChannelStore.tunable(svc.store().all());   // only channels this radio can tune
        final boolean sstv144 = svc.profile().tunable(com.macdmr.radio.PacketEngine.SSTV_FREQ_HZ);   // the W6 tunes UHF only: SSTV then runs on the channel the radio is on
        final List<String> acts = new java.util.ArrayList<>(), actNames = new java.util.ArrayList<>();
        final String[] allNames = Scheduler.Entry.actionNames(svc.aprsHz());     // APRS monitoring names the region's frequency
        for (int i = 0; i < Scheduler.ACTIONS.length; i++) if (Scheduler.available(svc.profile(), Scheduler.ACTIONS[i], svc.aprsHz()) || Scheduler.ACTIONS[i].equals(e.action)) { acts.add(Scheduler.ACTIONS[i]); actNames.add(allNames[i]); }
        final Runnable showCh = () -> {
            boolean need = Scheduler.usesChannel(e.action);
            chRow.setVisibility(need ? android.view.View.VISIBLE : android.view.View.GONE);
            recRow.setVisibility(Scheduler.LISTEN.equals(e.action) ? android.view.View.VISIBLE : android.view.View.GONE);
            ChannelStore.Channel ch = e.channel >= 0 ? svc.store().byNumber(e.channel) : null;
            chV.setText(Txt.s(R.string.intercom_schedule_tap_to_change, ch != null ? ch.number + "  " + ch.name : Scheduler.SSTV.equals(e.action) && sstv144 ? Txt.s(R.string.intercom_schedule_sstv_freq, "144.500") : Txt.s(R.string.intercom_schedule_current_channel)));
            act.setText(Txt.s(R.string.intercom_schedule_tap_to_change, e.actionName()));
        };
        act.setOnClickListener(v -> new AlertDialog.Builder(c).setTitle(Txt.s(R.string.intercom_schedule_what_title)).setItems(actNames.toArray(new String[0]), (dd, w) -> { e.action = acts.get(w); showCh.run(); }).show());
        chV.setOnClickListener(v -> {
            String[] names = new String[chans.size() + 1];
            names[0] = Scheduler.SSTV.equals(e.action) && sstv144 ? Txt.s(R.string.intercom_schedule_sstv_freq_item, "144.500") : Txt.s(R.string.intercom_schedule_current_channel);
            for (int i = 0; i < chans.size(); i++) names[i + 1] = chans.get(i).number + "  " + chans.get(i).name;
            new AlertDialog.Builder(c).setTitle(Txt.s(R.string.intercom_schedule_which_channel)).setItems(names, (dd, w) -> { e.channel = w == 0 ? -1 : chans.get(w - 1).number; showCh.run(); }).show();
        });
        showCh.run();
        // when
        body.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_schedule_days)));
        LinearLayout days = new LinearLayout(c);
        final TextView dateV = Neo.label(c, "", Neo.CYAN_LIGHT, 16, true);
        final Runnable showDate = () -> {
            dateV.setVisibility(e.days == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            if (e.date.isEmpty()) e.date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            dateV.setText(Txt.s(R.string.intercom_schedule_once_on, e.date));
        };
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            final TextView p = Neo.label(c, Txt.s(DAY[i]), Neo.AMBER, 15, true); p.setGravity(Gravity.CENTER); p.setContentDescription(Txt.s(DAY_FULL[i]));
            p.setPadding(0, (int) (8 * d), 0, (int) (8 * d)); Neo.pillStyle(p, (e.days & bit) != 0, Neo.AMBER);
            p.setOnClickListener(v -> { e.days ^= bit; Neo.pillStyle(p, (e.days & bit) != 0, Neo.AMBER); showDate.run(); });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); pl.setMargins((int) (2 * d), 0, (int) (2 * d), 0);
            days.addView(p, pl);
        }
        body.addView(days);
        dateV.setPadding((int) (4 * d), (int) (8 * d), 0, (int) (4 * d)); body.addView(dateV); showDate.run();
        dateV.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance(); try { String[] q = e.date.split("-"); cal.set(Integer.parseInt(q[0]), Integer.parseInt(q[1]) - 1, Integer.parseInt(q[2])); } catch (Throwable ignored) { }
            new android.app.DatePickerDialog(c, (dp, y, mo, dd) -> { e.date = String.format(Locale.US, "%04d-%02d-%02d", y, mo + 1, dd); showDate.run(); }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });
        body.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_schedule_starts)));
        final TextView time = Neo.label(c, "", Neo.CYAN_LIGHT, 18, true); time.setPadding((int) (4 * d), (int) (6 * d), 0, (int) (6 * d)); body.addView(time);
        final Runnable showT = () -> time.setText(Txt.s(R.string.intercom_schedule_tap_to_change, android.text.format.DateFormat.getTimeFormat(c).format(at(e.hour, e.minute))));
        showT.run();
        time.setOnClickListener(v -> new android.app.TimePickerDialog(c, (tp, h, mi) -> { e.hour = h; e.minute = mi; showT.run(); }, e.hour, e.minute, android.text.format.DateFormat.is24HourFormat(c)).show());
        final android.widget.EditText len = field(c, body, Txt.s(R.string.intercom_schedule_minutes), String.valueOf(e.minutes));
        len.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final android.widget.EditText label = field(c, body, Txt.s(R.string.intercom_schedule_name), e.label);
        LinearLayout btns = new LinearLayout(c);
        TextView save = Neo.button(c, Txt.s(R.string.common_save), "check", Neo.GREEN), del = Neo.button(c, Txt.s(isNew ? R.string.common_cancel : R.string.common_delete), isNew ? "close" : "stop", isNew ? Neo.MUTED : Neo.RED);
        for (TextView b : new TextView[] { save, del }) { Neo.fit(b, true); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (48 * d), 1f); lp.setMargins((int) (4 * d), (int) (14 * d), (int) (4 * d), 0); btns.addView(b, lp); }
        body.addView(btns);
        final AlertDialog dlg = Neo.showSheet(c, body);
        save.setOnClickListener(v -> {
            try { e.minutes = Math.max(1, Math.min(24 * 60, Integer.parseInt(len.getText().toString().trim()))); } catch (NumberFormatException x) { e.minutes = 60; }
            e.label = label.getText().toString().trim(); e.record = rec.isChecked() && Scheduler.LISTEN.equals(e.action); e.enabled = true;
            if (!Scheduler.usesChannel(e.action)) e.channel = -1;
            if (e.days != 0) e.date = "";
            { String bp = Scheduler.bandProblem(svc.profile(), e, e.channel >= 0 ? svc.store().byNumber(e.channel) : null, svc.aprsHz()); if (bp != null) { Toast.makeText(c, bp, Toast.LENGTH_LONG).show(); return; } }
            long next = Scheduler.nextStart(e, System.currentTimeMillis(), java.util.TimeZone.getDefault());
            if (next < 0) { Toast.makeText(c, Txt.s(R.string.intercom_schedule_time_past_toast), Toast.LENGTH_LONG).show(); return; }
            Scheduler.put(c, e);
            dlg.dismiss();
            Toast.makeText(c, Txt.s(R.string.intercom_schedule_next_toast, Txt.dates("EEE d MMM, HH:mm", "EEEdMMMHHmm", false).format(new Date(next))), Toast.LENGTH_SHORT).show();
            show(c, svc);
        });
        del.setOnClickListener(v -> {
            if (isNew) { dlg.dismiss(); show(c, svc); return; }
            new AlertDialog.Builder(c).setTitle(Txt.s(R.string.intercom_schedule_delete_title)).setMessage(e.when()).setPositiveButton(Txt.s(R.string.common_delete), (dd, w) -> { Scheduler.remove(c, e.id); dlg.dismiss(); show(c, svc); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        });
    }
    private static Date at(int h, int m) { Calendar cal = Calendar.getInstance(); cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); return cal.getTime(); }
    private static android.widget.EditText field(Context c, LinearLayout body, String label, String value) {
        float d = Theme.dp(c);
        body.addView(Neo.sheetSection(c, label));
        android.widget.EditText e = new android.widget.EditText(c); e.setText(value); e.setSingleLine(true);
        e.setTextColor(Neo.TEXT); e.setHintTextColor(Neo.DIM); e.setTextSize(16); e.setBackground(Neo.rowCard(c)); e.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        body.addView(e);
        return e;
    }
}
