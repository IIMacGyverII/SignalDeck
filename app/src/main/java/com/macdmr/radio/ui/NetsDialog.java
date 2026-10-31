/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.NetWatch;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.ham.NetFinder;
import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.repeaters.RepeaterModels.Repeater;

import java.util.List;
import java.util.Locale;

/** Nets: on the air near you now (NetLogger + what the radio hears), coming up, and elsewhere; reminders. */
public final class NetsDialog {
    private final Context c; private final RadioService svc; private final float d;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list; private TextView status; private AlertDialog dlg; private boolean showElsewhere, showMaybe;
    private final Runnable refresh = this::load;

    public static void show(Context c, RadioService svc) { if (svc != null) new NetsDialog(c, svc).open(); }
    private NetsDialog(Context c, RadioService svc) { this.c = c; this.svc = svc; d = Theme.dp(c); }

    private void open() {
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.intercom_nets_title), "contacts");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.intercom_nets_hint)));
        final NetWatch w = svc.netWatch();
        Neo.NeonSwitch remind = new Neo.NeonSwitch(c), live = new Neo.NeonSwitch(c);
        remind.setChecked(w.remind()); live.setChecked(w.liveAlerts());
        remind.setOnChange(w::setRemind); live.setOnChange(w::setLiveAlerts);
        body.addView(Neo.switchRow(c, "alert", Neo.AMBER, Txt.s(R.string.intercom_nets_remind), Txt.s(R.string.intercom_nets_remind_sub), remind));
        body.addView(Neo.switchRow(c, "intercom", Neo.GREEN, Txt.s(R.string.intercom_nets_live), Txt.s(R.string.intercom_nets_live_sub), live));
        status = Neo.label(c, Txt.s(R.string.intercom_nets_looking), Neo.MUTED, 12.5f, false); status.setPadding(0, (int) (8 * d), 0, 0);
        body.addView(status);
        list = new LinearLayout(c); list.setOrientation(LinearLayout.VERTICAL); body.addView(list);
        LinearLayout row = new LinearLayout(c);
        TextView again = Neo.button(c, Txt.s(R.string.intercom_nets_refresh), "update", Neo.CYAN), run = Neo.button(c, Txt.s(R.string.intercom_nets_run), "contacts", Neo.VIOLET); Neo.fit(again, true); Neo.fit(run, true);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); bl.setMargins((int) (3 * d), (int) (12 * d), (int) (3 * d), 0);
        row.addView(again, bl); row.addView(run, new LinearLayout.LayoutParams(bl));
        body.addView(row);
        TextView add = Neo.button(c, Txt.s(R.string.intercom_nets_add), "plus", Neo.AMBER);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); al.setMargins((int) (3 * d), (int) (8 * d), (int) (3 * d), 0);
        body.addView(add, al);
        add.setOnClickListener(v -> { NetFinder.Manual m = new NetFinder.Manual(); com.macdmr.radio.ChannelStore.Channel cur = svc.current(); if (cur != null) m.channel = cur.number; editManual(c, svc.store().all(), m, this::load); });
        again.setOnClickListener(v -> { status.setText(Txt.s(R.string.intercom_nets_looking)); load(); });
        run.setOnClickListener(v -> { if (dlg != null) dlg.dismiss(); NetDialog.show(c, svc); });
        dlg = Neo.showSheet(c, body);
        dlg.setOnDismissListener(x -> ui.removeCallbacks(refresh));
        load();
    }

    private void load() {
        ui.removeCallbacks(refresh);
        new Thread(() -> {
            NetWatch.Snapshot s;
            try { s = svc.netWatch().snapshot(); } catch (Throwable t) { s = new NetWatch.Snapshot(); s.error = String.valueOf(t.getMessage()); }
            final NetWatch.Snapshot r = s;
            ui.post(() -> { if (dlg != null && dlg.isShowing()) { render(r); ui.postDelayed(refresh, 60_000); } });
        }, "nets-load").start();
    }

    private void render(NetWatch.Snapshot s) {
        list.removeAllViews();
        long now = System.currentTimeMillis();
        status.setText(s.error != null ? (s.total > 0 ? Txt.s(R.string.intercom_nets_status_error_old, s.error) : Txt.s(R.string.intercom_nets_status_error, s.error)) : Txt.p(R.plurals.intercom_nets_status, s.total, s.total));

        list.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_nets_near_section)));
        int shown = 0;
        if (s.heard != null) {
            NetFinder.Session h = s.heard;
            String title = h.name.isEmpty() ? Txt.s(R.string.intercom_nets_heard_unnamed, s.heardLabel) : Txt.s(R.string.intercom_nets_heard_named, h.name, s.heardLabel);
            String sub = h.stations > 0 ? Txt.p(R.plurals.intercom_nets_heard_sub_stations, h.receptions, h.receptions, h.minutes(), h.stations) : Txt.p(R.plurals.intercom_nets_heard_sub, h.receptions, h.receptions, h.minutes());
            LinearLayout cd = card(title, sub, Neo.GREEN); final int ch = h.channel;
            cd.setOnClickListener(v -> tuneChannel(ch));
            list.addView(cd, lp(6)); shown++;
        }
        for (NetWatch.Found f : s.yours) {
            NetFinder.Net n = f.net;
            String sub = join(NetFinder.where(n), f.place.km >= 0 ? Txt.s(R.string.intercom_nets_place_miles, f.place.label, String.format(Locale.US, "%.0f", f.place.km * 0.621371)) : f.place.label,
                    n.netControl.isEmpty() ? null : Txt.s(R.string.intercom_nets_net_control, n.netControl), Txt.s(R.string.intercom_nets_following, n.followers),
                    n.opened > 0 ? Txt.s(R.string.intercom_nets_started_ago, Math.max(0, (now - n.opened) / 60000)) : null, f.near == 0 ? Txt.s(R.string.intercom_nets_place_unknown_long) : null);
            LinearLayout cd = card(n.name, sub, Neo.CYAN);
            cd.setOnClickListener(v -> netActions(f));
            cd.setOnLongClickListener(v -> { askNear(f.net.name); return true; });
            list.addView(cd, lp(6)); shown++;
        }
        if (shown == 0) list.addView(Neo.sheetHint(c, Txt.s(R.string.intercom_nets_none_near)));

        list.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_nets_coming_section)));
        if (s.schedule.isEmpty()) list.addView(Neo.sheetHint(c, Txt.s(R.string.intercom_nets_coming_none)));
        for (final NetFinder.Slot sl : s.schedule) {
            String sub = join(NetFinder.when(sl), NetWatch.relative(sl, now), sl.place != null ? sl.place.label : null, Txt.s(R.string.intercom_nets_about_min, sl.durationMin),
                    sl.stations > 0 ? Txt.p(R.plurals.intercom_nets_stations, sl.stations, sl.stations) : null, sl.source, sl.near == 0 ? Txt.s(R.string.intercom_nets_place_unknown) : null) + (sl.notes.isEmpty() ? "" : "\n" + sl.notes);
            LinearLayout cd = card(sl.name, sub, Neo.AMBER);
            cd.setOnClickListener(v -> tune(sl.place));
            cd.setOnLongClickListener(v -> { slotActions(sl); return true; });
            list.addView(cd, lp(6));
        }

        if (!s.maybe.isEmpty() || !s.unsure.isEmpty()) {
            list.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_nets_maybe_section)));
            int nMaybe = s.maybe.size() + s.unsure.size();
            TextView more = Neo.label(c, Txt.p(showMaybe ? R.plurals.intercom_nets_maybe_hide : R.plurals.intercom_nets_maybe_show, nMaybe, nMaybe), Neo.CYAN_LIGHT, 13, false);
            more.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
            more.setOnClickListener(v -> { showMaybe = !showMaybe; render(s); });
            list.addView(more);
            if (showMaybe) for (final NetFinder.Slot sl : s.unsure) {
                LinearLayout cd = card(sl.name, join(NetFinder.when(sl), sl.place != null ? Txt.s(R.string.intercom_nets_same_freq_as, sl.place.label) : null, sl.source), Neo.MUTED);
                cd.setOnClickListener(v -> tune(sl.place));
                cd.setOnLongClickListener(v -> { slotActions(sl); return true; });
                list.addView(cd, lp(4));
            }
            if (showMaybe) for (NetWatch.Found f : s.maybe) {
                LinearLayout cd = card(f.net.name, join(NetFinder.where(f.net), Txt.s(R.string.intercom_nets_same_freq_as, f.place.label), f.net.netControl.isEmpty() ? null : Txt.s(R.string.intercom_nets_net_control, f.net.netControl),
                        Txt.s(R.string.intercom_nets_following, f.net.followers)), Neo.MUTED);
                cd.setOnClickListener(v -> netActions(f));
                cd.setOnLongClickListener(v -> { askNear(f.net.name); return true; });
                list.addView(cd, lp(4));
            }
        }
        if (!s.elsewhere.isEmpty()) {
            list.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_nets_elsewhere_section)));
            int nElse = s.elsewhere.size();
            TextView more = Neo.label(c, Txt.p(showElsewhere ? R.plurals.intercom_nets_elsewhere_hide : R.plurals.intercom_nets_elsewhere_show, nElse, nElse), Neo.CYAN_LIGHT, 13, false);
            more.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
            more.setOnClickListener(v -> { showElsewhere = !showElsewhere; render(s); });
            list.addView(more);
            if (showElsewhere) for (NetFinder.Net n : s.elsewhere) {
                LinearLayout cd = card(n.name, join(NetFinder.where(n), n.mode.isEmpty() ? null : n.mode, n.netControl.isEmpty() ? null : Txt.s(R.string.intercom_nets_net_control, n.netControl),
                        Txt.s(R.string.intercom_nets_following, n.followers)), Neo.MUTED);
                cd.setOnClickListener(v -> checkins(n));
                list.addView(cd, lp(4));
            }
        }
    }

    /** Your answer for a net matched by frequency: near me, not near me (moved to "probably elsewhere"), or forget it. */
    private void askNear(String name) {
        new AlertDialog.Builder(c).setTitle(name).setItems(new String[] { Txt.s(R.string.intercom_nets_near_yes), Txt.s(R.string.intercom_nets_near_no), Txt.s(R.string.intercom_nets_near_unknown) }, (dd, w) -> { svc.netWatch().setNear(name, w == 0 ? 1 : w == 1 ? -1 : 0); load(); }).show();
    }

    private void slotActions(NetFinder.Slot sl) {
        if (sl.manualId != 0) {
            NetFinder.Manual m = null; for (NetFinder.Manual x : NetWatch.manual(c)) if (x.id == sl.manualId) m = x;
            if (m == null) return; final NetFinder.Manual fm = m;
            new AlertDialog.Builder(c).setTitle(sl.name).setItems(new String[] { Txt.s(R.string.common_edit), Txt.s(R.string.common_delete) }, (dd, w) -> {
                if (w == 0) editManual(c, svc.store().all(), fm, this::load);
                else new AlertDialog.Builder(c).setTitle(Txt.s(R.string.intercom_nets_delete_confirm, fm.name)).setPositiveButton(Txt.s(R.string.common_delete), (d2, w2) -> { NetWatch.deleteManual(c, fm.id); load(); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
            }).show();
            return;
        }
        new AlertDialog.Builder(c).setTitle(sl.name).setItems(new String[] { Txt.s(R.string.intercom_nets_add_mine), Txt.s(R.string.intercom_nets_near_yes), Txt.s(R.string.intercom_nets_near_no) }, (dd, w) -> {
            if (w == 0) {
                if (sl.channel == 0) { Toast.makeText(c, Txt.s(R.string.intercom_nets_not_channel_toast), Toast.LENGTH_LONG).show(); return; }
                NetFinder.Manual m = new NetFinder.Manual(); m.name = sl.name; m.channel = sl.channel; m.minute = sl.minute; m.durationMin = sl.durationMin;
                m.days = sl.dow == 0 ? NetFinder.ALL_DAYS : 1 << sl.dow;
                editManual(c, svc.store().all(), m, this::load);
            } else { svc.netWatch().setNear(sl.name, w == 1 ? 1 : -1); load(); }
        }).show();
    }

    private static final int[] DAY_LETTERS = { R.string.intercom_netedit_day_sun, R.string.intercom_netedit_day_mon, R.string.intercom_netedit_day_tue, R.string.intercom_netedit_day_wed,
            R.string.intercom_netedit_day_thu, R.string.intercom_netedit_day_fri, R.string.intercom_netedit_day_sat };

    /** The parts of a card subtitle joined with the separator; null parts are left out. */
    private static String join(String... parts) {
        StringBuilder b = new StringBuilder(); String sep = Txt.s(R.string.intercom_nets_sep);
        boolean first = true;
        for (String p : parts) { if (p == null) continue; if (!first) b.append(sep); b.append(p); first = false; }
        return b.toString();
    }
    /**
     * Add or edit a net on a channel: name, channel (fixed when {@code channels} is null), weekdays, start time, length,
     * notes. Saved nets show in Coming up and get the reminders.
     */
    public static void editManual(final Context c, final List<com.macdmr.radio.ChannelStore.Channel> channels, final NetFinder.Manual src, final Runnable after) {
        final float d = Theme.dp(c);
        final NetFinder.Manual m = new NetFinder.Manual();
        m.id = src.id; m.channelId = src.channelId; m.name = src.name; m.notes = src.notes; m.channel = src.channel; m.days = src.days; m.durationMin = src.durationMin;
        m.minute = src.id == 0 && src.minute == 0 ? 19 * 60 : src.minute;          // a new net: 7:00 PM to start with
        LinearLayout body = Neo.sheetBody(c, Txt.s(src.id == 0 ? R.string.intercom_netedit_add_title : R.string.intercom_netedit_edit_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.intercom_netedit_hint)));
        final android.widget.EditText name = field(c, body, Txt.s(R.string.intercom_netedit_name), m.name, Txt.s(R.string.intercom_netedit_name_hint));
        body.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_netedit_channel)));
        final TextView chV = Neo.label(c, "", Neo.CYAN_LIGHT, 15, true); chV.setPadding((int) (4 * d), (int) (6 * d), 0, (int) (6 * d)); body.addView(chV);
        final Runnable showCh = () -> {
            com.macdmr.radio.ChannelStore.Channel f = null;
            if (channels != null) for (com.macdmr.radio.ChannelStore.Channel x : channels) if (x.number == m.channel) f = x;
            if (m.channel == 0) chV.setText(Txt.s(R.string.intercom_netedit_choose_channel));
            else if (f == null) chV.setText(Txt.s(channels != null ? R.string.intercom_netedit_channel_plain_tap : R.string.intercom_netedit_channel_plain, m.channel));
            else chV.setText(Txt.s(channels != null ? R.string.intercom_netedit_channel_named_tap : R.string.intercom_netedit_channel_named, m.channel, f.name, Txt.freqMhz(f.rxFreq, 4)));
        };
        showCh.run();
        if (channels != null) chV.setOnClickListener(v -> {
            String[] names = new String[channels.size()]; for (int i = 0; i < names.length; i++) names[i] = channels.get(i).number + "  " + channels.get(i).name;
            new AlertDialog.Builder(c).setTitle(Txt.s(R.string.intercom_netedit_which_channel)).setItems(names, (dd, w) -> { m.channel = channels.get(w).number; m.channelId = channels.get(w).id; showCh.run(); }).show();
        });
        body.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_netedit_days)));
        LinearLayout days = new LinearLayout(c);
        String[] full = new java.text.DateFormatSymbols(Locale.US).getWeekdays();
        for (int i = 0; i < 7; i++) {
            final int dow = i + 1;
            final TextView p = Neo.label(c, Txt.s(DAY_LETTERS[i]), Neo.AMBER, 15, true); p.setGravity(android.view.Gravity.CENTER);
            p.setPadding(0, (int) (8 * d), 0, (int) (8 * d)); Neo.pillStyle(p, m.on(dow), Neo.AMBER);
            p.setContentDescription(full[dow]);
            p.setOnClickListener(v -> { m.days ^= 1 << dow; Neo.pillStyle(p, m.on(dow), Neo.AMBER); });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); pl.setMargins((int) (2 * d), 0, (int) (2 * d), 0);
            days.addView(p, pl);
        }
        body.addView(days);
        body.addView(Neo.sheetSection(c, Txt.s(R.string.intercom_netedit_starts)));
        final TextView time = Neo.label(c, "", Neo.CYAN_LIGHT, 18, true); time.setPadding((int) (4 * d), (int) (6 * d), 0, (int) (6 * d)); body.addView(time);
        final Runnable showT = () -> { int h = m.minute / 60, mi = m.minute % 60; time.setText(Txt.s(R.string.intercom_netedit_time, h % 12 == 0 ? 12 : h % 12, mi, Txt.s(h < 12 ? R.string.intercom_netedit_am : R.string.intercom_netedit_pm))); };
        showT.run();
        time.setOnClickListener(v -> new android.app.TimePickerDialog(c, (tp, h, mi) -> { m.minute = h * 60 + mi; showT.run(); }, m.minute / 60, m.minute % 60, android.text.format.DateFormat.is24HourFormat(c)).show());
        final android.widget.EditText len = field(c, body, Txt.s(R.string.intercom_netedit_length), String.valueOf(m.durationMin), "60");
        len.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final android.widget.EditText notes = field(c, body, Txt.s(R.string.intercom_netedit_notes), m.notes, Txt.s(R.string.intercom_netedit_notes_hint));
        TextView save = Neo.button(c, Txt.s(R.string.common_save), "check", Neo.GREEN);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); sl.topMargin = (int) (14 * d);
        body.addView(save, sl);
        final AlertDialog dlg = Neo.showSheet(c, body);
        save.setOnClickListener(v -> {
            m.name = name.getText().toString().trim(); m.notes = notes.getText().toString().trim();
            try { m.durationMin = Math.max(5, Math.min(600, Integer.parseInt(len.getText().toString().trim()))); } catch (NumberFormatException e) { m.durationMin = 60; }
            if (m.name.isEmpty()) { Toast.makeText(c, Txt.s(R.string.intercom_netedit_need_name), Toast.LENGTH_SHORT).show(); return; }
            if (m.channel == 0) { Toast.makeText(c, Txt.s(R.string.intercom_netedit_choose_channel), Toast.LENGTH_SHORT).show(); return; }
            if (m.days == 0) { Toast.makeText(c, Txt.s(R.string.intercom_netedit_need_day), Toast.LENGTH_SHORT).show(); return; }
            NetWatch.saveManual(c, m);
            dlg.dismiss();
            Toast.makeText(c, m.name + ": " + NetFinder.when(m), Toast.LENGTH_SHORT).show();
            if (after != null) after.run();
        });
    }
    private static android.widget.EditText field(Context c, LinearLayout body, String label, String value, String hint) {
        float d = Theme.dp(c);
        body.addView(Neo.sheetSection(c, label));
        android.widget.EditText e = new android.widget.EditText(c); e.setText(value); e.setHint(hint); e.setSingleLine(true);
        e.setTextColor(Neo.TEXT); e.setHintTextColor(Neo.DIM); e.setTextSize(16); e.setBackground(Neo.rowCard(c)); e.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        body.addView(e);
        return e;
    }

    private void netActions(NetWatch.Found f) {
        String tune = f.place.channel > 0 ? Txt.s(R.string.intercom_nets_tune_to, f.place.label) : Txt.s(R.string.intercom_nets_repeater_details);
        new AlertDialog.Builder(c).setTitle(f.net.name).setItems(new String[] { tune, Txt.s(R.string.intercom_nets_who_checked_in) },(dd, w) -> { if (w == 0) tune(f.place); else checkins(f.net); }).show();
    }

    private void tune(NetFinder.Place p) {
        if (p == null) { Toast.makeText(c, Txt.s(R.string.intercom_nets_not_on_channel_toast), Toast.LENGTH_SHORT).show(); return; }
        if (p.channel > 0) { tuneChannel(p.channel); return; }
        if (p.data instanceof Repeater) {
            Repeater r = (Repeater) p.data; android.location.Location me = svc.lastKnownLocation();
            RepeatersDialog.showDetails(c, svc, r, me != null ? me.getLatitude() : r.lat, me != null ? me.getLongitude() : r.lon);
        }
    }
    private void tuneChannel(int number) {
        String e = svc.trySelectChannel(number);                              // a channel outside this radio's bands is refused, never reported as tuned
        Toast.makeText(c, e != null ? e : Txt.s(R.string.intercom_nets_tuned_toast, number), e != null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    private void checkins(final NetFinder.Net n) {
        LinearLayout body = Neo.sheetBody(c, n.name);
        body.addView(Neo.sheetHint(c, join(NetFinder.where(n), n.netControl.isEmpty() ? null : Txt.s(R.string.intercom_nets_net_control, n.netControl), Txt.s(R.string.intercom_nets_checkins_from))));
        final LinearLayout rows = new LinearLayout(c); rows.setOrientation(LinearLayout.VERTICAL); body.addView(rows);
        final TextView st = Neo.label(c, Txt.s(R.string.intercom_nets_loading), Neo.MUTED, 12.5f, false); rows.addView(st);
        Neo.showSheet(c, body);
        new Thread(() -> {
            String err = null; List<NetFinder.Checkin> l = null; int[] ptr = { 0 };
            try { l = svc.netWatch().checkins(n, ptr); } catch (Throwable t) { err = t.getMessage(); }
            final List<NetFinder.Checkin> fl = l; final String fe = err;
            ui.post(() -> {
                rows.removeAllViews();
                if (fe != null) { rows.addView(Neo.sheetHint(c, Txt.s(R.string.intercom_nets_load_failed, fe))); return; }
                if (fl == null || fl.isEmpty()) { rows.addView(Neo.sheetHint(c, Txt.s(R.string.intercom_net_no_checkins))); return; }
                rows.addView(Neo.sheetHint(c, Txt.p(R.plurals.intercom_nets_checked_in, fl.size(), fl.size())));
                for (NetFinder.Checkin k : fl) {
                    String line = (k.serial == ptr[0] ? "▶ " : "") + k.serial + ".  " + k.call + (k.name.isEmpty() ? "" : "  " + cap(k.name)) + (k.state.isEmpty() ? "" : " · " + k.state) + (k.status.trim().isEmpty() ? "" : " · " + k.status.trim());
                    TextView t = Neo.label(c, line, k.serial == ptr[0] ? Neo.GREEN_LIGHT : Neo.TEXT_SOFT, 14, k.serial == ptr[0]); t.setPadding(0, (int) (3 * d), 0, (int) (3 * d));
                    rows.addView(t);
                }
            });
        }, "nets-checkins").start();
    }
    private static String cap(String s) { return s.length() < 2 ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.US); }

    private LinearLayout card(String title, String sub, int accent) {
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); v.setBackground(Neo.rowCard(c)); v.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        TextView t = Neo.text(c, title, Neo.light(accent), 15.5f, Neo.ui(c)); v.addView(t);
        v.addView(Neo.text(c, sub, Neo.MUTED, 12.5f, Neo.uiMedium(c)));
        v.setClickable(true); return v;
    }
    private LinearLayout.LayoutParams lp(int top) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); l.topMargin = (int) (top * d); return l; }
}
