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
import com.macdmr.radio.RadioService;
import com.macdmr.radio.Scheduler;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** PKT → Scheduled operations: the list (next start, on/off) and the editor. */
public final class ScheduleDialog {
    private ScheduleDialog() { }
    private static final String[] DAY = { "M", "T", "W", "T", "F", "S", "S" };
    private static final String[] DAY_FULL = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };

    public static void show(Context c, RadioService svc) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, "Scheduled operations");
        body.addView(Neo.sheetHint(c, "Start something at a set time, once or every week: listen to a channel (and record it), scan the zone, " + (com.macdmr.radio.DeviceProfile.inBands(com.macdmr.radio.PacketEngine.APRS_FREQ_HZ) ? "APRS monitoring, " : "") + "SSTV receive, or send an APRS beacon. When the time is up the radio goes back to what it was doing. It runs with the screen off; a satellite pass being tracked always comes first."));
        final AlertDialog[] self = { null };
        List<Scheduler.Entry> l = Scheduler.list(c);
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
        long now = System.currentTimeMillis();
        int running = Scheduler.runningEnd(c) > now ? Scheduler.runningId(c) : 0;
        if (l.isEmpty()) body.addView(Neo.sheetHint(c, "Nothing scheduled yet."));
        for (Scheduler.Entry e : l) {
            long next = Scheduler.nextStart(e, now, java.util.TimeZone.getDefault());
            String sub = e.when() + " · " + (e.id == running ? "running now, until " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(Scheduler.runningEnd(c)))
                    : !e.enabled ? "off" : next > 0 ? "next " + f.format(new Date(next)) : "done");
            final Neo.NeonSwitch sw = new Neo.NeonSwitch(c); sw.setChecked(e.enabled);
            sw.setOnChange(on -> { e.enabled = on; if (on && e.oneShot() && Scheduler.nextStart(e, System.currentTimeMillis(), java.util.TimeZone.getDefault()) < 0) { Toast.makeText(c, "That date has passed: edit it first", Toast.LENGTH_SHORT).show(); sw.setChecked(false); e.enabled = false; } Scheduler.put(c, e); });
            LinearLayout row = Neo.switchRow(c, icon(e.action), e.id == running ? Neo.GREEN : Neo.CYAN, (e.label.isEmpty() ? e.actionName() : e.label) + channelSuffix(svc, e), sub, sw);
            row.setBackground(Neo.rowCard(c)); row.setClickable(true);
            row.setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); edit(c, svc, e); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (8 * d);
            body.addView(row, lp);
        }
        TextView add = Neo.button(c, "Add a schedule", "plus", Neo.GREEN); body.addView(add);
        add.setOnClickListener(v -> { if (self[0] != null) self[0].dismiss(); edit(c, svc, new Scheduler.Entry()); });
        self[0] = Neo.showSheet(c, body);
    }

    private static String icon(String a) { return Scheduler.SCAN.equals(a) ? "scan" : Scheduler.APRS.equals(a) || Scheduler.BEACON.equals(a) ? "packet" : Scheduler.SSTV.equals(a) ? "sstv" : "radio"; }
    private static String channelSuffix(RadioService svc, Scheduler.Entry e) {
        if (e.channel < 0 || !Scheduler.usesChannel(e.action)) return "";
        ChannelStore.Channel ch = svc.store().byNumber(e.channel); return " · " + (ch == null ? "channel " + e.channel : ch.name);
    }

    private static void edit(Context c, RadioService svc, Scheduler.Entry e) {
        float d = Theme.dp(c);
        boolean isNew = e.id <= 0;
        LinearLayout body = Neo.sheetBody(c, isNew ? "New schedule" : "Edit schedule");
        // what
        body.addView(Neo.sheetSection(c, "What"));
        final TextView act = Neo.label(c, "", Neo.CYAN_LIGHT, 17, true); act.setPadding((int) (4 * d), (int) (6 * d), 0, (int) (6 * d)); body.addView(act);
        final LinearLayout chRow = new LinearLayout(c); chRow.setOrientation(LinearLayout.VERTICAL); body.addView(chRow);
        final TextView chV = Neo.label(c, "", Neo.CYAN_LIGHT, 16, true); chV.setPadding((int) (4 * d), (int) (6 * d), 0, (int) (6 * d));
        chRow.addView(Neo.sheetSection(c, "Channel")); chRow.addView(chV);
        final Neo.NeonSwitch rec = new Neo.NeonSwitch(c); rec.setChecked(e.record);
        final LinearLayout recRow = Neo.switchRow(c, "rec", Neo.RED, "Record it", "REC for the whole time (Download/DMR/Audio/<channel>)", rec);
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
            chV.setText((ch != null ? ch.number + "  " + ch.name : Scheduler.SSTV.equals(e.action) && sstv144 ? "144.500 MHz (the SSTV calling frequency)" : "The channel the radio is on") + "  ·  tap to change");
            act.setText(e.actionName() + "  ·  tap to change");
        };
        act.setOnClickListener(v -> new AlertDialog.Builder(c).setTitle("What should happen?").setItems(actNames.toArray(new String[0]), (dd, w) -> { e.action = acts.get(w); showCh.run(); }).show());
        chV.setOnClickListener(v -> {
            String[] names = new String[chans.size() + 1];
            names[0] = Scheduler.SSTV.equals(e.action) && sstv144 ? "144.500 MHz (SSTV calling frequency)" : "The channel the radio is on";
            for (int i = 0; i < chans.size(); i++) names[i + 1] = chans.get(i).number + "  " + chans.get(i).name;
            new AlertDialog.Builder(c).setTitle("Which channel?").setItems(names, (dd, w) -> { e.channel = w == 0 ? -1 : chans.get(w - 1).number; showCh.run(); }).show();
        });
        showCh.run();
        // when
        body.addView(Neo.sheetSection(c, "Days (none ticked: once, on the date)"));
        LinearLayout days = new LinearLayout(c);
        final TextView dateV = Neo.label(c, "", Neo.CYAN_LIGHT, 16, true);
        final Runnable showDate = () -> {
            dateV.setVisibility(e.days == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            if (e.date.isEmpty()) e.date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            dateV.setText("Once, on " + e.date + "  ·  tap to change");
        };
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            final TextView p = Neo.label(c, DAY[i], Neo.AMBER, 15, true); p.setGravity(Gravity.CENTER); p.setContentDescription(DAY_FULL[i]);
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
        body.addView(Neo.sheetSection(c, "Starts"));
        final TextView time = Neo.label(c, "", Neo.CYAN_LIGHT, 18, true); time.setPadding((int) (4 * d), (int) (6 * d), 0, (int) (6 * d)); body.addView(time);
        final Runnable showT = () -> time.setText(android.text.format.DateFormat.getTimeFormat(c).format(at(e.hour, e.minute)) + "  ·  tap to change");
        showT.run();
        time.setOnClickListener(v -> new android.app.TimePickerDialog(c, (tp, h, mi) -> { e.hour = h; e.minute = mi; showT.run(); }, e.hour, e.minute, android.text.format.DateFormat.is24HourFormat(c)).show());
        final android.widget.EditText len = field(c, body, "For how many minutes (not for a beacon or a mail check)", String.valueOf(e.minutes));
        len.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final android.widget.EditText label = field(c, body, "Name (optional)", e.label);
        LinearLayout btns = new LinearLayout(c);
        TextView save = Neo.button(c, "Save", "check", Neo.GREEN), del = Neo.button(c, isNew ? "Cancel" : "Delete", isNew ? "close" : "stop", isNew ? Neo.MUTED : Neo.RED);
        for (TextView b : new TextView[] { save, del }) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (48 * d), 1f); lp.setMargins((int) (4 * d), (int) (14 * d), (int) (4 * d), 0); btns.addView(b, lp); }
        body.addView(btns);
        final AlertDialog dlg = Neo.showSheet(c, body);
        save.setOnClickListener(v -> {
            try { e.minutes = Math.max(1, Math.min(24 * 60, Integer.parseInt(len.getText().toString().trim()))); } catch (NumberFormatException x) { e.minutes = 60; }
            e.label = label.getText().toString().trim(); e.record = rec.isChecked() && Scheduler.LISTEN.equals(e.action); e.enabled = true;
            if (!Scheduler.usesChannel(e.action)) e.channel = -1;
            if (e.days != 0) e.date = "";
            { String bp = Scheduler.bandProblem(svc.profile(), e, e.channel >= 0 ? svc.store().byNumber(e.channel) : null, svc.aprsHz()); if (bp != null) { Toast.makeText(c, bp, Toast.LENGTH_LONG).show(); return; } }
            long next = Scheduler.nextStart(e, System.currentTimeMillis(), java.util.TimeZone.getDefault());
            if (next < 0) { Toast.makeText(c, "That time has already passed: choose a later date or tick some days", Toast.LENGTH_LONG).show(); return; }
            Scheduler.put(c, e);
            dlg.dismiss();
            Toast.makeText(c, "Next: " + new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new Date(next)), Toast.LENGTH_SHORT).show();
            show(c, svc);
        });
        del.setOnClickListener(v -> {
            if (isNew) { dlg.dismiss(); show(c, svc); return; }
            new AlertDialog.Builder(c).setTitle("Delete this schedule?").setMessage(e.when()).setPositiveButton("Delete", (dd, w) -> { Scheduler.remove(c, e.id); dlg.dismiss(); show(c, svc); }).setNegativeButton("Cancel", null).show();
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
