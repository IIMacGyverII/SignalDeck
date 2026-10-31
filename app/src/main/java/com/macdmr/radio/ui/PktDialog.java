/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.PacketEngine;
import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.pkt.PacketStore;
import com.macdmr.radio.pkt.SSTVEncoder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** PKT: the packet-radio tools — APRS (monitor, message, beacon, KISS TNC, stations, log, settings), DTMF, weather alerts. */
public final class PktDialog {
    private final Context c; private final RadioService svc; private final PacketEngine pe; private final float d;
    private AlertDialog dlg;

    public static void show(Context c, RadioService svc) { if (svc.packet() != null) new PktDialog(c, svc).open(); }
    /** One of the packet sheets on its own, for the mode screens: message[:CALL], settings, sstvSend. */
    public static void sheet(Context c, RadioService svc, String which) {
        if (svc.packet() == null) return;
        PktDialog p = new PktDialog(c, svc);
        if (which.startsWith("message")) p.message(which.startsWith("message:") ? which.substring(8) : "");
        else if (which.equals("settings")) p.settings();
        else if (which.equals("sstvSend")) p.sstvSend();
    }
    /** After a mode that takes the radio started: its full-screen page instead of the intercom. */
    private void openScreen() { dlg.dismiss(); if (c instanceof com.macdmr.radio.MainActivity) ((com.macdmr.radio.MainActivity) c).openModeScreen(); }
    private PktDialog(Context c, RadioService svc) { this.c = c; this.svc = svc; this.pe = svc.packet(); d = Theme.dp(c); }

    private void open() {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_title), "pkt");
        // ---- running now: one tap to each running mode's page ----
        if (c instanceof com.macdmr.radio.MainActivity) {
            java.util.List<String[]> run = ((com.macdmr.radio.MainActivity) c).runningModes();
            if (!run.isEmpty()) {
                body.addView(section(Txt.s(R.string.packet_pkt_running_now)));
                for (int k = 0; k < run.size(); k += 2) {
                    LinearLayout rr = row();
                    for (int q = k; q < Math.min(run.size(), k + 2); q++) {
                        final String key = run.get(q)[0];
                        button(rr, run.get(q)[1] + "  \u203A", Theme.PKT, x -> { dlg.dismiss(); ((com.macdmr.radio.MainActivity) c).openMode(key); });
                    }
                    body.addView(rr);
                }
            }
        }
        // ---- digital voice ----
        body.addView(section(Txt.s(R.string.packet_pkt_digital_voice)));
        LinearLayout m17r = row();
        button(m17r, Txt.s(svc.m17Running() ? R.string.packet_pkt_m17_listening : R.string.packet_pkt_m17_receive), Theme.SOFTSQ, x -> { if (!svc.m17Running()) { String e = svc.setM17(true); if (e != null) { toast(e); return; } } dlg.dismiss(); if (c instanceof com.macdmr.radio.MainActivity) ((com.macdmr.radio.MainActivity) c).openM17(); });
        body.addView(m17r);
        body.addView(hint(Txt.s(R.string.packet_pkt_m17_hint)));
        // ---- APRS ----
        body.addView(section("APRS"));
        body.addView(status());
        LinearLayout r1 = row();
        final boolean aprsHere = svc.aprsTunable();          // the region's APRS frequency (Device → Region); the W6 tunes UHF only
        final String aprsMhz = com.macdmr.radio.region.Region.mhzShown(svc.aprsHz());
        if (aprsHere || pe.monitoring()) button(r1, pe.monitoring() ? Txt.s(R.string.packet_pkt_stop_monitoring) : Txt.s(R.string.packet_pkt_monitor, aprsMhz), Theme.PKT, x -> { boolean start = !pe.monitoring(); toast(svc.aprsMonitor(start)); if (start) openScreen(); else dlg.dismiss(); });
        button(r1, Txt.s(pe.kiss().isActive() ? R.string.packet_pkt_kiss_stop : R.string.packet_pkt_kiss), Theme.PKT, x -> {
            if (pe.kiss().isActive()) { pe.kissStop(); toast(Txt.s(R.string.packet_pkt_kiss_stopped)); }
            else { if (!pe.monitoring()) svc.markModeStart(); String e = pe.kissStart(); toast(e == null ? Txt.s(R.string.packet_pkt_kiss_on, pe.kissPort()) : e); }
            svc.aprsMonitor(pe.monitoring()); if (pe.kiss().isActive()) openScreen(); else dlg.dismiss();
        });
        body.addView(r1);
        LinearLayout r2 = row();
        button(r2, Txt.s(R.string.packet_pkt_send_message), Theme.VFO, x -> message());
        button(r2, Txt.s(R.string.packet_pkt_position_beacon), Theme.VFO, x -> { String e = pe.sendBeacon(); toast(e == null ? Txt.s(R.string.packet_pkt_beacon_queued) : e); });
        body.addView(r2);
        LinearLayout r2b = row();
        button(r2b, Txt.s(R.string.packet_pkt_object), Theme.VFO, x -> objectSheet());
        button(r2b, Txt.s(R.string.packet_pkt_bulletin), Theme.VFO, x -> bulletinSheet());
        body.addView(r2b);
        r2b = row();                                                         // two per row: the labels stay whole
        button(r2b, Txt.s(R.string.packet_pkt_telemetry), Theme.VFO, x -> { String e = pe.sendTelemetry(); toast(e == null ? Txt.s(R.string.packet_pkt_telemetry_queued) : e); });
        if (pe.hasBarometer()) button(r2b, Txt.s(R.string.packet_pkt_weather), Theme.VFO, x -> { String e = pe.sendWeather(); toast(e == null ? Txt.s(R.string.packet_pkt_weather_queued) : e); });
        body.addView(r2b);
        LinearLayout rSat = row();
        button(rSat, svc.satState() != null ? Txt.s(R.string.packet_pkt_sat_tracking, svc.satState().name) : Txt.s(R.string.packet_pkt_sat_passes), Theme.PKT, x -> { dlg.dismiss(); SatelliteDialog.show(c, svc); });
        body.addView(rSat);
        rSat = row();
        if (svc.profile().rssi) button(rSat, Txt.s(R.string.packet_pkt_band_scope), Theme.PKT, x -> { dlg.dismiss(); if (c instanceof com.macdmr.radio.MainActivity) ((com.macdmr.radio.MainActivity) c).openBandScope(); });
        body.addView(rSat);
        rSat = row();
        button(rSat, Txt.s(R.string.packet_pkt_conditions), Theme.PKT, x -> { dlg.dismiss(); ConditionsDialog.show(c, svc); });
        button(rSat, Txt.s(R.string.packet_pkt_decode_recording), Theme.PKT, x -> { dlg.dismiss(); RecordingsDialog.show(c, svc, com.macdmr.radio.RecordingDecodeJob.APRS); });
        body.addView(rSat);
        LinearLayout rIss = row();
        final boolean issMon = svc.satMode() == com.macdmr.radio.ham.Satellites.APRS;
        final long issAprsHz = com.macdmr.radio.ham.Satellites.issHz(c, "issAprs", com.macdmr.radio.ham.Satellites.ISS_APRS_HZ);
        final String issAprs = Txt.freqMhz(issAprsHz, 3);
        final boolean issTunable = com.macdmr.radio.DeviceProfile.inBands(issAprsHz);   // 145.825 on a UHF-only radio (the W6): neither listen nor beacon
        button(rIss, issMon ? Txt.s(R.string.packet_pkt_iss_aprs_stop) : Txt.s(R.string.packet_pkt_iss_aprs, issAprs), Theme.PKT, x -> {
            if (issMon) { svc.endSatTracking(); toast(Txt.s(R.string.packet_pkt_iss_aprs_stopped)); dlg.dismiss(); return; }
            String e = svc.startIssMode(com.macdmr.radio.ham.Satellites.APRS);
            if (e != null) { toast(e); return; }
            svc.setIssRepeat(com.macdmr.radio.ham.Satellites.APRS); openScreen();
        });
        if (issTunable) button(rIss, Txt.s(R.string.packet_pkt_iss_beacon), Theme.VFO, x -> toast(svc.issBeacon()));
        if (issTunable || issMon) body.addView(rIss);
        String[] autoNames = { Txt.s(R.string.packet_pkt_auto_off), Txt.s(R.string.packet_pkt_auto_every, pe.beaconMin()), Txt.s(R.string.packet_pkt_auto_smart) };
        List<String> autoParts = new java.util.ArrayList<>(); autoParts.add(autoNames[Math.max(0, Math.min(2, pe.autoBeacon()))]);
        if (pe.telemetryOn()) autoParts.add(Txt.s(R.string.packet_pkt_auto_telemetry));
        if (pe.weatherOn()) autoParts.add(Txt.s(R.string.packet_pkt_auto_weather));
        body.addView(hint(sentences(Txt.s(aprsHere ? R.string.packet_pkt_auto_here : R.string.packet_pkt_auto_away, list(autoParts), aprsMhz),
                pe.txIgate() ? Txt.p(R.plurals.packet_pkt_igate_on, pe.gatedToRfCount(), pe.gatedToRfCount()) : null,
                pe.groups().isEmpty() ? null : Txt.s(R.string.packet_pkt_groups, String.join(", ", pe.groups())))));
        LinearLayout rIs = row();
        button(rIs, Txt.s(pe.isEnabled() ? R.string.packet_pkt_is_stop : R.string.packet_pkt_is_start), Theme.PKT, x -> {
            if (pe.isEnabled()) { pe.isStop(); toast(Txt.s(R.string.packet_pkt_is_off)); } else { String e = pe.isStart(); toast(e == null ? Txt.s(R.string.packet_pkt_is_connecting, pe.isServer()) : e); }
            dlg.dismiss();
        });
        body.addView(rIs);
        body.addView(hint(Txt.s(R.string.packet_pkt_is_hint, pe.isStatus())));
        LinearLayout r3 = row();
        button(r3, Txt.s(R.string.packet_pkt_stations_n, pe.store().stationCount()), Theme.SOFTSQ, x -> stations());
        button(r3, Txt.s(R.string.packet_pkt_aprs_log), Theme.SOFTSQ, x -> aprsLog());
        body.addView(r3);
        r3 = row();
        button(r3, Txt.s(R.string.packet_pkt_bulletins), Theme.SOFTSQ, x -> bulletinsHeard());
        button(r3, Txt.s(R.string.packet_pkt_settings), Theme.MON, x -> settings());
        body.addView(r3);
        String where = Txt.s(pe.passiveMode() == 2 ? R.string.packet_pkt_passive_every : pe.passiveMode() == 1 ? R.string.packet_pkt_passive_flagged : R.string.packet_pkt_passive_none);
        body.addView(hint(Txt.s(aprsHere ? (pe.digipeat() ? R.string.packet_pkt_passive_here_digi : R.string.packet_pkt_passive_here) : (pe.digipeat() ? R.string.packet_pkt_passive_away_digi : R.string.packet_pkt_passive_away), where, aprsMhz)));
        // ---- simplex repeater ----
        body.addView(section(Txt.s(R.string.packet_pkt_parrot_section)));
        LinearLayout pr = row();
        final boolean parrotOn = svc.parrot().enabled();
        button(pr, Txt.s(parrotOn ? R.string.packet_pkt_parrot_stop : R.string.packet_pkt_parrot_start), Theme.PKT, x -> { toast(svc.setParrot(!parrotOn)); if (svc.parrot().enabled()) openScreen(); else dlg.dismiss(); });
        body.addView(pr);
        body.addView(hint(sentences(parrotOn ? Txt.s(R.string.packet_pkt_parrot_on, svc.parrot().played()) : null, Txt.s(R.string.packet_pkt_parrot_hint))));
        // ---- text modes ----
        body.addView(section(Txt.s(R.string.packet_pkt_text_modes)));
        LinearLayout xr = row();
        button(xr, Txt.s(R.string.packet_pkt_rtty_psk), Theme.SOFTSQ, x -> { dlg.dismiss(); TextModesDialog.show(c, svc); });
        button(xr, Txt.s(svc.ft8Running() ? R.string.packet_pkt_ft8_running : R.string.packet_pkt_ft8), Theme.SOFTSQ, x -> { if (!svc.ft8Running()) svc.setFt8(true); dlg.dismiss(); if (c instanceof com.macdmr.radio.MainActivity) ((com.macdmr.radio.MainActivity) c).openFt8(); });
        body.addView(xr);
        body.addView(hint(Txt.s(R.string.packet_pkt_text_hint, pe.textStatus())));
        // ---- packet terminal ----
        body.addView(section(Txt.s(R.string.packet_pkt_terminal_section)));
        LinearLayout tr = row();
        button(tr, Txt.s(R.string.packet_pkt_terminal), Theme.SOFTSQ, x -> { dlg.dismiss(); TerminalDialog.show(c, svc); });
        body.addView(tr);
        body.addView(hint(Txt.s(R.string.packet_pkt_terminal_hint, pe.terminalState())));
        // ---- packet finder ----
        body.addView(section(Txt.s(R.string.packet_pkt_finder_section)));
        LinearLayout pfr = row();
        int pfn = svc.finder().stations().size();
        button(pfr, svc.finder().running() ? Txt.s(R.string.packet_pkt_finder_listening) : pfn > 0 ? Txt.s(R.string.packet_pkt_finder_found, pfn) : Txt.s(R.string.packet_pkt_finder), Theme.SOFTSQ, x -> { dlg.dismiss(); PacketFinderDialog.show(c, svc); });
        body.addView(pfr);
        body.addView(hint(Txt.s(R.string.packet_pkt_finder_hint)));
        // ---- Winlink ----
        body.addView(section(Txt.s(R.string.packet_pkt_winlink_section)));
        LinearLayout wr = row();
        int wlUnread = svc.winlink().store().unread(), wlOut = svc.winlink().store().count(com.macdmr.radio.winlink.WinlinkStore.OUTBOX);
        button(wr, wlUnread > 0 ? Txt.s(R.string.packet_pkt_winlink_new, wlUnread) : Txt.s(R.string.packet_pkt_winlink), Theme.SOFTSQ, x -> { dlg.dismiss(); WinlinkDialog.show(c, svc); });
        body.addView(wr);
        body.addView(hint(Txt.s(R.string.packet_pkt_winlink_hint, svc.winlink().busy() ? svc.winlink().state() : wlOut == 0 ? Txt.s(R.string.packet_pkt_winlink_outbox_empty) : Txt.s(R.string.packet_pkt_winlink_outbox, wlOut))));
        // ---- licence exam practice ----
        body.addView(section(Txt.s(R.string.packet_pkt_exam_section)));
        LinearLayout xr2 = row();
        button(xr2, Txt.s(R.string.packet_pkt_exam), Theme.SOFTSQ, x -> { dlg.dismiss(); ExamDialog.show(c); });
        body.addView(xr2);
        body.addView(hint(Txt.s(R.string.packet_pkt_exam_hint)));
        // ---- logbook ----
        body.addView(section(Txt.s(R.string.packet_pkt_logbook_section)));
        LinearLayout lr = row();
        int lbn = svc.logbook().count(true);
        button(lr, Txt.p(R.plurals.packet_pkt_logbook_n, lbn, lbn), Theme.SOFTSQ, x -> { dlg.dismiss(); LogbookDialog.show(c, svc); });
        com.macdmr.radio.ham.Pota.Activation act = com.macdmr.radio.ham.Pota.active(c);
        button(lr, act != null ? Txt.s(R.string.packet_pkt_activating, act.ref) : Txt.s(R.string.packet_pkt_pota), Theme.SOFTSQ, x -> { dlg.dismiss(); PotaDialog.show(c, svc); });
        body.addView(lr);
        body.addView(hint(Txt.s(R.string.packet_pkt_logbook_hint)));
        // ---- fox hunt ----
        body.addView(section(Txt.s(R.string.packet_pkt_fox_section)));
        LinearLayout fr = row();
        button(fr, Txt.s(R.string.packet_pkt_fox), Theme.SOFTSQ, x -> { dlg.dismiss(); FoxHuntDialog.show(c, svc); });
        body.addView(fr);
        body.addView(hint(sentences(svc.foxTxOn() ? Txt.s(R.string.packet_pkt_fox_on) : null, Txt.s(svc.profile().rssi ? R.string.packet_pkt_fox_hint : R.string.packet_pkt_fox_hint_tx))));
        // ---- net control ----
        body.addView(section(Txt.s(R.string.packet_pkt_forms_section)));
        LinearLayout formRow = row();
        button(formRow, Txt.s(R.string.packet_pkt_forms, svc.forms().list(1000).size()), Theme.SOFTSQ, x -> { dlg.dismiss(); FormsDialog.show(c, svc); });
        body.addView(formRow);
        body.addView(hint(Txt.s(R.string.packet_pkt_forms_hint)));
        body.addView(section(Txt.s(R.string.packet_pkt_nets_section)));
        LinearLayout nr = row();
        button(nr, Txt.s(R.string.packet_pkt_nets_near), Theme.SOFTSQ, x -> { dlg.dismiss(); NetsDialog.show(c, svc); });
        com.macdmr.radio.ham.NetStore.Net running = svc.nets().running();
        button(nr, running != null ? Txt.s(R.string.packet_pkt_net_running, running.name) : Txt.s(R.string.packet_pkt_net_control), Theme.SOFTSQ, x -> { dlg.dismiss(); NetDialog.show(c, svc); });
        body.addView(nr);
        body.addView(hint(Txt.s(R.string.packet_pkt_nets_hint)));
        // ---- voice keyer ----
        body.addView(section(Txt.s(R.string.packet_pkt_keyer_section)));
        LinearLayout kr = row();
        button(kr, Txt.s(R.string.packet_pkt_keyer), Theme.SOFTSQ, x -> { dlg.dismiss(); KeyerDialog.show(c, svc); });
        body.addView(kr);
        long due = svc.idDueInMs();
        String idLine = due == Long.MAX_VALUE ? Txt.s(R.string.packet_pkt_id_timer_idle) : due > 0 ? Txt.s(R.string.packet_pkt_id_within, due / 60000, String.format(Locale.US, "%02d", due / 1000 % 60)) : Txt.s(R.string.packet_pkt_id_due);
        body.addView(hint(sentences(Txt.s(R.string.packet_pkt_keyer_hint), idLine)));
        // ---- Morse ----
        body.addView(section(Txt.s(R.string.packet_pkt_morse_section)));
        LinearLayout mr = row();
        button(mr, Txt.s(R.string.packet_pkt_morse_id), Theme.VFO, x -> { String e = pe.sendCwId(); toast(e == null ? Txt.s(R.string.packet_pkt_morse_sending, pe.callsign()) : e); });
        button(mr, Txt.s(R.string.packet_pkt_morse_trainer), Theme.SOFTSQ, x -> { dlg.dismiss(); MorseTrainerDialog.show(c); });
        body.addView(mr);
        body.addView(hint(sentences(Txt.s(pe.cwEnabled() ? R.string.packet_pkt_morse_rx_on : R.string.packet_pkt_morse_rx_off), Txt.s(pe.cwIdEnabled() ? R.string.packet_pkt_morse_id_on : R.string.packet_pkt_morse_id_off))));
        // ---- SSTV ----
        body.addView(section(Txt.s(R.string.packet_pkt_schedules_section)));
        LinearLayout sch = row();
        int nSch = com.macdmr.radio.Scheduler.list(c).size();
        button(sch, nSch == 0 ? Txt.s(R.string.packet_pkt_schedules) : Txt.s(R.string.packet_pkt_schedules_n, nSch), Theme.SOFTSQ, x -> { dlg.dismiss(); ScheduleDialog.show(c, svc); });
        body.addView(sch);
        if (svc.profile().symbolEngine()) {                                  // pages go out through the bench firmware's symbol engine
        body.addView(section(Txt.s(R.string.packet_pkt_pager_section)));
        LinearLayout pg = row();
        button(pg, Txt.s(R.string.packet_pkt_pocsag_page), Theme.PKT, x -> { dlg.dismiss(); if (c instanceof com.macdmr.radio.MainActivity) ((com.macdmr.radio.MainActivity) c).openPager(); });
        button(pg, Txt.s(svc.pagerRunning() ? R.string.packet_pkt_pager_stop : R.string.packet_pkt_pager_hear), Theme.PKT, x -> { String e = svc.setPager(!svc.pagerRunning()); toast(e == null ? Txt.s(svc.pagerRunning() ? R.string.packet_pkt_pager_listening : R.string.packet_pkt_pager_off) : e); });
        body.addView(pg);
        body.addView(hint(Txt.s(R.string.packet_pkt_pager_hint)));
        }
        body.addView(section(Txt.s(R.string.packet_pkt_sstv_section)));
        body.addView(hint("\uD83D\uDD38 " + pe.sstvStatusText()));
        LinearLayout s1 = row();
        if (pe.sstvRx() && svc.satMode() != com.macdmr.radio.ham.Satellites.SSTV) button(s1, Txt.s(R.string.packet_pkt_sstv_stop), Theme.PKT, x -> { toast(svc.sstvMonitor(false, false)); dlg.dismiss(); });
        else if (svc.satMode() == com.macdmr.radio.ham.Satellites.SSTV) button(s1, Txt.s(R.string.packet_pkt_iss_sstv_stop), Theme.PKT, x -> { svc.endSatTracking(); toast(Txt.s(R.string.packet_pkt_iss_sstv_stopped)); dlg.dismiss(); });
        else { if (com.macdmr.radio.DeviceProfile.inBands(com.macdmr.radio.PacketEngine.SSTV_FREQ_HZ)) button(s1, Txt.s(R.string.packet_pkt_sstv_rx_144500), Theme.PKT, x -> { toast(svc.sstvMonitor(true, true)); openScreen(); }); button(s1, Txt.s(R.string.packet_pkt_sstv_rx_here), Theme.PKT, x -> { toast(svc.sstvMonitor(true, false)); openScreen(); }); }
        body.addView(s1);
        if (svc.satMode() != com.macdmr.radio.ham.Satellites.SSTV && !pe.sstvRx() && com.macdmr.radio.DeviceProfile.inBands(com.macdmr.radio.ham.Satellites.issHz(c, "issSstv", com.macdmr.radio.ham.Satellites.ISS_SSTV_HZ))) {
            LinearLayout s1b = row();
            button(s1b, Txt.s(R.string.packet_pkt_iss_sstv, Txt.freqMhz(com.macdmr.radio.ham.Satellites.issHz(c, "issSstv", com.macdmr.radio.ham.Satellites.ISS_SSTV_HZ), 3)), Theme.PKT, x -> {
                String e = svc.startIssMode(com.macdmr.radio.ham.Satellites.SSTV);
                if (e != null) { toast(e); return; }
                svc.setIssRepeat(com.macdmr.radio.ham.Satellites.SSTV); openScreen();
            });
            body.addView(s1b);
        }
        LinearLayout s2 = row();
        button(s2, Txt.s(R.string.packet_pkt_sstv_live), Theme.SOFTSQ, x -> sstvView());
        button(s2, Txt.s(R.string.packet_pkt_sstv_send), Theme.VFO, x -> sstvSend());

        body.addView(s2);
        LinearLayout s2d = row();
        button(s2d, Txt.s(R.string.packet_pkt_decode_recording), Theme.PKT, x -> { dlg.dismiss(); RecordingsDialog.show(c, svc, com.macdmr.radio.RecordingDecodeJob.SSTV); });
        body.addView(s2d);
        body.addView(hint(Txt.s(R.string.packet_pkt_sstv_hint)));
        // ---- DTMF ----
        body.addView(section("DTMF"));
        LinearLayout r4 = row();
        button(r4, Txt.s(R.string.packet_pkt_dtmf_send), Theme.VFO, x -> dtmfSend());
        button(r4, Txt.s(R.string.packet_pkt_dtmf_heard), Theme.SOFTSQ, x -> dtmfLog());
        button(r4, Txt.s(R.string.packet_pkt_dtmf_remote), Theme.MON, x -> remoteSheet());
        body.addView(r4);
        body.addView(hint(Txt.s(pe.dtmfEnabled() ? R.string.packet_pkt_dtmf_hint : R.string.packet_pkt_dtmf_hint_off)));
        // ---- Weather ----
        boolean wxRegion = com.macdmr.radio.region.Region.current(c).weatherRadio;  // NOAA-style weather radio exists in the region (Device → Region)
        if (com.macdmr.radio.DeviceProfile.inBands(162_400_000L) && !wxRegion) {     // elsewhere: one tap away
            LinearLayout r6 = row();
            button(r6, Txt.s(R.string.packet_pkt_wx_show), Theme.REC, x -> wxLog());
            body.addView(r6);
            body.addView(hint(Txt.s(pe.wxEnabled() ? R.string.packet_pkt_wx_away_hint : R.string.packet_pkt_wx_away_hint_off)));
        }
        if (com.macdmr.radio.DeviceProfile.inBands(162_400_000L) && wxRegion) {     // the weather channels: VHF
        body.addView(section(Txt.s(R.string.packet_pkt_wx_section)));
        LinearLayout r5 = row();
        button(r5, Txt.s(R.string.packet_pkt_wx_heard), Theme.REC, x -> wxLog());
        body.addView(r5);
        body.addView(hint(Txt.s(pe.wxEnabled() ? R.string.packet_pkt_wx_hint : R.string.packet_pkt_wx_hint_off)));
        }
        dlg = dialog(body);
    }

    private TextView status() {
        String call = pe.callsignSet() ? pe.myCall() : Txt.s(R.string.packet_pkt_status_no_call);
        String mode = pe.monitoring() ? Txt.s(R.string.packet_pkt_status_monitoring, com.macdmr.radio.region.Region.mhzShown(svc.aprsMonitorHz())) : Txt.s(R.string.packet_pkt_status_passive);
        String st = pe.kiss().isActive() ? Txt.s(R.string.packet_pkt_status_kiss, mode, Txt.s(pe.kiss().clientConnected() ? R.string.packet_pkt_status_kiss_client : R.string.packet_pkt_status_kiss_waiting), pe.kiss().rxFrames(), pe.kiss().txFrames()) : mode;
        return hint("🔸 " + call + "  ·  " + st);
    }

    // ---- APRS message ----
    private void message() { message(""); }
    private void message(String toCall) {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_msg_title), "radio");
        final EditText to = field(body, Txt.s(R.string.packet_pkt_msg_to), toCall, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        final EditText text = field(body, Txt.s(R.string.packet_pkt_msg_text), "", InputType.TYPE_CLASS_TEXT);
        body.addView(hint(Txt.s(R.string.packet_pkt_msg_hint, pe.callsignSet() ? pe.myCall() : Txt.s(R.string.packet_pkt_msg_no_call), String.join(",", pe.path()))));
        LinearLayout r = row();
        button(r, Txt.s(R.string.common_send), Theme.PKT, x -> { String e = pe.sendMessage(to.getText().toString(), text.getText().toString()); toast(e == null ? Txt.s(R.string.packet_pkt_msg_queued) : e); if (e == null) sub.dismiss(); });
        button(r, Txt.s(R.string.common_cancel), Theme.MON, x -> sub.dismiss());
        body.addView(r);
        body.addView(section(Txt.s(R.string.packet_pkt_gw_section)));
        LinearLayout g = row();
        button(g, Txt.s(R.string.packet_pkt_gw_sms), Theme.VFO, x -> { sub.dismiss(); gateway(true); });
        button(g, Txt.s(R.string.packet_pkt_gw_email), Theme.VFO, x -> { sub.dismiss(); gateway(false); });
        body.addView(g);
        body.addView(hint(Txt.s(R.string.packet_pkt_gw_hint)));
        sub = dialog(body);
    }
    private AlertDialog sub;

    /** SMSGTE ("@number text") or EMAIL-2 ("address text"), sent as an APRS message to the gateway. */
    private void gateway(final boolean sms) {
        LinearLayout body = page(Txt.s(sms ? R.string.packet_pkt_gw_sms_title : R.string.packet_pkt_gw_email_title), "radio");
        final EditText to = field(body, Txt.s(sms ? R.string.packet_pkt_gw_phone : R.string.packet_pkt_gw_address), "", sms ? InputType.TYPE_CLASS_PHONE : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText text = field(body, Txt.s(R.string.packet_pkt_gw_message), "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        final TextView left = hint(""); body.addView(left);
        Runnable count = () -> { int n = 67 - gatewayText(sms, to.getText().toString(), text.getText().toString()).length(); left.setText(n >= 0 ? Txt.p(R.plurals.packet_pkt_gw_left, n, n) : Txt.p(R.plurals.packet_pkt_gw_too_long, -n, -n)); };
        android.text.TextWatcher w = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { } @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(android.text.Editable e) { count.run(); }
        };
        to.addTextChangedListener(w); text.addTextChangedListener(w); count.run();
        body.addView(hint(Txt.s(sms ? R.string.packet_pkt_gw_sms_hint : R.string.packet_pkt_gw_email_hint)));
        LinearLayout r = row();
        button(r, Txt.s(R.string.common_send), Theme.PKT, x -> {
            String addr = to.getText().toString().trim();
            if (sms && addr.replaceAll("[^0-9]", "").length() < 10) { toast(Txt.s(R.string.packet_pkt_gw_err_phone)); return; }
            if (!sms && !addr.contains("@")) { toast(Txt.s(R.string.packet_pkt_gw_err_email)); return; }
            String e = pe.sendMessage(sms ? "SMSGTE" : "EMAIL-2", gatewayText(sms, addr, text.getText().toString()));
            toast(e == null ? Txt.s(R.string.packet_pkt_gw_queued) : e); if (e == null) sub.dismiss();
        });
        button(r, Txt.s(R.string.common_cancel), Theme.MON, x -> sub.dismiss());
        body.addView(r);
        sub = dialog(body);
    }
    static String gatewayText(boolean sms, String to, String text) {
        String a = sms ? "@" + to.replaceAll("[^0-9]", "") : to.trim();
        return (a + " " + text.trim()).trim();
    }

    // ---- DTMF remote control ----
    private void remoteSheet() {
        final SharedPreferences p = pe.prefs();
        LinearLayout body = page(Txt.s(R.string.packet_pkt_remote_title), "channels");
        final boolean[] on = { pe.remoteOn() };
        toggle(body, Txt.s(R.string.packet_pkt_remote_answer), on, 0);
        final EditText pin = field(body, Txt.s(R.string.packet_pkt_remote_pin), pe.remotePin(), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        StringBuilder sb = new StringBuilder(Txt.s(R.string.packet_pkt_remote_intro)).append('\n');
        for (int i = 1; i < com.macdmr.radio.ham.DtmfRemote.COMMANDS.length; i++) sb.append("\n").append(i).append("   ").append(com.macdmr.radio.ham.DtmfRemote.commandName(i));
        sb.append("\n\n").append(Txt.s(R.string.packet_pkt_remote_outro));
        body.addView(hint(sb.toString()));
        LinearLayout r = row();
        button(r, Txt.s(R.string.common_save), Theme.PKT, x -> {
            String v = pin.getText().toString().trim();
            if (on[0] && !v.matches("[0-9]{4,6}")) { toast(Txt.s(R.string.packet_pkt_remote_err_pin)); return; }
            p.edit().putBoolean("remote", on[0]).putString("remotePin", v).apply();
            toast(Txt.s(on[0] ? R.string.packet_pkt_remote_on : R.string.packet_pkt_remote_off)); sub.dismiss();
        });
        button(r, Txt.s(R.string.common_cancel), Theme.MON, x -> sub.dismiss());
        body.addView(r);
        sub = dialog(body);
    }

    // ---- object, bulletin ----
    private void objectSheet() {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_obj_title), "radio");
        final EditText name = field(body, Txt.s(R.string.packet_pkt_obj_name), "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        android.location.Location me = svc.lastKnownLocation();
        final EditText pos = field(body, Txt.s(R.string.packet_pkt_obj_position), me == null ? "" : String.format(Locale.US, "%.5f, %.5f", me.getLatitude(), me.getLongitude()), InputType.TYPE_CLASS_TEXT);
        final EditText sym = field(body, Txt.s(R.string.packet_pkt_obj_symbol), "/.", InputType.TYPE_CLASS_TEXT);
        final EditText comment = field(body, Txt.s(R.string.packet_pkt_obj_comment), "", InputType.TYPE_CLASS_TEXT);
        body.addView(hint(Txt.s(R.string.packet_pkt_obj_hint)));
        LinearLayout r = row();
        button(r, Txt.s(R.string.packet_pkt_obj_place), Theme.PKT, x -> objectSend(name, pos, sym, comment, true));
        button(r, Txt.s(R.string.packet_pkt_obj_remove), Theme.REC, x -> objectSend(name, pos, sym, comment, false));
        button(r, Txt.s(R.string.common_cancel), Theme.MON, x -> sub.dismiss());
        body.addView(r);
        sub = dialog(body);
    }
    private void objectSend(EditText name, EditText pos, EditText sym, EditText comment, boolean live) {
        try {
            String[] p = pos.getText().toString().split(",");
            double lat = Double.parseDouble(p[0].trim()), lon = Double.parseDouble(p[1].trim());
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new NumberFormatException();
            String e = pe.sendObject(name.getText().toString(), live, lat, lon, sym.getText().toString().trim(), comment.getText().toString());
            toast(e == null ? Txt.s(live ? R.string.packet_pkt_obj_queued : R.string.packet_pkt_obj_removal_queued) : e); if (e == null) sub.dismiss();
        } catch (Throwable t) { toast(Txt.s(R.string.packet_pkt_obj_err_position)); }
    }
    private void bulletinSheet() {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_bln_title), "radio");
        final EditText num = field(body, Txt.s(R.string.packet_pkt_bln_number), "0", InputType.TYPE_CLASS_NUMBER);
        final EditText text = field(body, Txt.s(R.string.packet_pkt_bln_text), "", InputType.TYPE_CLASS_TEXT);
        body.addView(hint(Txt.s(R.string.packet_pkt_bln_hint)));
        LinearLayout r = row();
        button(r, Txt.s(R.string.common_send), Theme.PKT, x -> { int n; try { n = Integer.parseInt(num.getText().toString().trim()); } catch (Throwable t) { n = 0; } String e = pe.sendBulletin(n, text.getText().toString()); toast(e == null ? Txt.s(R.string.packet_pkt_bln_queued) : e); if (e == null) sub.dismiss(); });
        button(r, Txt.s(R.string.common_cancel), Theme.MON, x -> sub.dismiss());
        body.addView(r);
        sub = dialog(body);
    }
    private void bulletinsHeard() {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_blnheard_title), "radio");
        List<PacketStore.LogEntry> list = pe.bulletins(100);
        if (list.isEmpty()) body.addView(hint(Txt.s(R.string.packet_pkt_blnheard_empty)));
        SimpleDateFormat f = Txt.dates("MM-dd HH:mm", "MMddHHmm");
        for (PacketStore.LogEntry e : list) {
            LinearLayout rowv = shell();
            rowv.addView(Neo.label(c, Txt.s(R.string.packet_pkt_blnheard_row, e.to, e.from), Theme.AMBER, 14, true));
            rowv.addView(Neo.label(c, f.format(new Date(e.ts)) + "  " + e.text, 0xFFBFC8D0, 13, false));
            body.addView(rowv);
        }
        sub = dialog(body);
    }

    // ---- DTMF send ----
    private void dtmfSend() {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_dtmf_title), "channels");
        final EditText seq = field(body, Txt.s(R.string.packet_pkt_dtmf_keys), "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout pad = new LinearLayout(c); pad.setOrientation(LinearLayout.VERTICAL);
        String[] rows = { "123A", "456B", "789C", "*0#D" };
        for (String rr : rows) {
            LinearLayout line = row();
            for (char k : rr.toCharArray()) { final String ks = String.valueOf(k); TextView b = button(line, ks, Theme.SOFTSQ, x -> seq.append(ks)); b.setTextSize(18); }
            pad.addView(line);
        }
        body.addView(pad);
        LinearLayout r = row();
        button(r, Txt.s(R.string.packet_pkt_dtmf_transmit), Theme.PKT, x -> { String e = pe.sendDtmf(seq.getText().toString()); toast(e == null ? Txt.s(R.string.packet_pkt_dtmf_queued) : e); if (e == null) sub.dismiss(); });
        button(r, Txt.s(R.string.common_clear), Theme.MON, x -> seq.setText(""));
        button(r, Txt.s(R.string.packet_pkt_dtmf_saved), Theme.SOFTSQ, x -> {
            final java.util.LinkedHashMap<String, String> m = pe.dtmfSaved();
            final java.util.List<String> names = new java.util.ArrayList<>(m.keySet());
            String[] items = new String[names.size() + 1]; for (int i = 0; i < names.size(); i++) items[i] = names.get(i) + "  \u2192  " + m.get(names.get(i)); items[names.size()] = Txt.s(R.string.packet_pkt_dtmf_save_as);
            new AlertDialog.Builder(c).setTitle(Txt.s(R.string.packet_pkt_dtmf_saved_title)).setItems(items, (dd, w) -> {
                if (w < names.size()) { seq.setText(m.get(names.get(w))); return; }
                final EditText nm = new EditText(c); nm.setHint(Txt.s(R.string.packet_pkt_dtmf_name_hint));
                new AlertDialog.Builder(c).setTitle(Txt.s(R.string.packet_pkt_dtmf_save_title, seq.getText())).setView(nm).setPositiveButton(Txt.s(R.string.common_save), (d3, w3) -> { String n = nm.getText().toString().trim(); if (!n.isEmpty() && seq.getText().length() > 0) { pe.dtmfSave(n, seq.getText().toString().toUpperCase(Locale.US)); toast(Txt.s(R.string.packet_pkt_saved)); } }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
            }).setNegativeButton(Txt.s(R.string.packet_pkt_dtmf_delete_one), (dd, w) -> { if (names.isEmpty()) return; new AlertDialog.Builder(c).setTitle(Txt.s(R.string.packet_pkt_dtmf_delete_which)).setItems(names.toArray(new String[0]), (d4, w4) -> { pe.dtmfSave(names.get(w4), null); toast(Txt.s(R.string.packet_pkt_deleted)); }).show(); }).show();
        });
        body.addView(r);
        sub = dialog(body);
    }

    // ---- SSTV ----
    private void sstvView() {
        LinearLayout body = page("SSTV", "report");   // i18n: mode name
        final TextView st = hint("\uD83D\uDD38 " + pe.sstvStatusText()); body.addView(st);
        final android.widget.ImageView iv = new android.widget.ImageView(c); iv.setAdjustViewBounds(true); iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setBackground(Theme.rounded(c, 0xFF000000, 0x8000BFFF, 1, 6)); iv.setMinimumHeight(dp(220));
        if (pe.sstvLast() != null) iv.setImageBitmap(pe.sstvLast());
        body.addView(iv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(hint(Txt.s(R.string.packet_pkt_sstv_live_hint)));
        pe.setSstvListener((status, live, lines, total, done) -> { st.setText("\uD83D\uDD38 " + pe.sstvStatusText()); if (live != null) iv.setImageBitmap(live); });
        sub = dialog(body);
        sub.setOnDismissListener(d -> pe.setSstvListener(null));
    }

    private void sstvSend() {
        if (!(c instanceof com.macdmr.radio.MainActivity)) { toast(Txt.s(R.string.packet_pkt_sstv_err_main)); return; }
        final int[] modes = SSTVEncoder.MODES;
        final String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) names[i] = SSTVEncoder.isLong(modes[i]) ? Txt.s(R.string.packet_pkt_sstv_mode_long, SSTVEncoder.label(modes[i])) : SSTVEncoder.label(modes[i]);
        new AlertDialog.Builder(c).setTitle(Txt.s(R.string.packet_pkt_sstv_mode_title)).setItems(names, (dd, w) -> ((com.macdmr.radio.MainActivity) c).pickImage(uri -> {
            try (java.io.InputStream in = c.getContentResolver().openInputStream(uri)) {
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                String e = pe.sendSstv(bmp, modes[w]); toast(e == null ? Txt.s(R.string.packet_pkt_sstv_sending, names[w]) : e);
            } catch (Throwable t) { toast(Txt.s(R.string.packet_pkt_sstv_err_read)); }
        })).show();
    }

    // ---- lists ----
    private void stations() {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_stations_title), "globe");
        List<PacketStore.Station> list = pe.store().stations(200);
        android.location.Location me = svc.lastKnownLocation();
        if (list.isEmpty()) body.addView(hint(Txt.s(R.string.packet_pkt_stations_empty)));
        SimpleDateFormat f = Txt.dates("MM-dd HH:mm", "MMddHHmm");
        for (PacketStore.Station s : list) {
            String head = s.call;
            if (me != null) { float[] r = new float[2]; android.location.Location.distanceBetween(me.getLatitude(), me.getLongitude(), s.lat, s.lon, r); head = Txt.s(R.string.packet_pkt_station_dist, s.call, Txt.decimal(r[0] / 1609.344, 1), compass(r[1])); }
            List<String> info = new java.util.ArrayList<>();
            info.add(f.format(new Date(s.lastTs))); info.add(String.format(Locale.US, "%.4f, %.4f", s.lat, s.lon));
            if (s.speedKmh >= 0) info.add(Txt.s(R.string.packet_pkt_station_mph, String.format(Locale.US, "%.0f", s.speedKmh * 0.621371)));
            if (s.alt != 0) info.add(Txt.s(R.string.packet_pkt_station_ft, Math.round(s.alt * 3.28084)));
            if (s.count > 1) info.add(s.count + "×");
            if (!s.via.isEmpty()) info.add(Txt.s(R.string.packet_pkt_station_via, s.via));
            LinearLayout rowv = shell();
            rowv.addView(Neo.label(c, head, Theme.CYAN, 15, true));
            rowv.addView(Neo.label(c, String.join(" · ", info), 0xFFBFC8D0, 12, false));
            if (!s.comment.isEmpty()) rowv.addView(Neo.label(c, s.comment, 0xFF8A98A6, 12, false));
            final PacketStore.Station fs = s;
            rowv.setClickable(true); rowv.setOnClickListener(x -> {
                try { c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)", fs.lat, fs.lon, fs.lat, fs.lon, android.net.Uri.encode(fs.call))))); } catch (Throwable t) { toast(Txt.s(R.string.packet_pkt_no_map_app)); }   // i18n: geo URI
            });
            body.addView(rowv);
        }
        if (!list.isEmpty()) {
            LinearLayout r = row();
            button(r, Txt.s(R.string.packet_pkt_export_gpx), Theme.SOFTSQ, x -> {
                try {
                    StringBuilder g = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"SignalDeck\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");   // i18n: file format (GPX)
                    SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    for (PacketStore.Station s : list) g.append(String.format(Locale.US, "  <wpt lat=\"%.6f\" lon=\"%.6f\">%s<time>%s</time><name>%s</name><desc>%s</desc></wpt>\n", s.lat, s.lon, s.alt != 0 ? "<ele>" + s.alt + "</ele>" : "", iso.format(new Date(s.lastTs)), xml(s.call), xml(s.comment)));   // i18n: file format (GPX)
                    g.append("</gpx>\n");
                    java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DMR/APRS"); dir.mkdirs();
                    java.io.File out = new java.io.File(dir, "aprs_stations_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".gpx");
                    try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(out), java.nio.charset.StandardCharsets.UTF_8)) { w.write(g.toString()); }
                    toast(Txt.s(R.string.packet_pkt_gpx_saved, out.getName()));
                    c.startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(android.content.Intent.EXTRA_SUBJECT, out.getName()).putExtra(android.content.Intent.EXTRA_TEXT, g.toString()), Txt.s(R.string.packet_pkt_share_gpx)));
                } catch (Throwable t) { toast(Txt.s(R.string.packet_pkt_export_failed, t.getMessage())); }
            });
            button(r, Txt.s(R.string.packet_pkt_clear_list), Theme.REC, x -> { pe.store().clearStations(); sub.dismiss(); });
            body.addView(r);
        }
        sub = dialog(body);
    }

    private static String xml(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String compass(float bearing) { float b = (bearing + 360) % 360; String[] dirs = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" }; return dirs[(int) Math.round(b / 45.0) % 8]; }

    private void aprsLog() {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_log_title), "diag");
        List<PacketStore.LogEntry> list = pe.store().aprsLog(150);
        if (list.isEmpty()) body.addView(hint(Txt.s(R.string.packet_pkt_log_empty)));
        SimpleDateFormat f = Txt.dates("MM-dd HH:mm:ss", "MMddHHmmss");
        for (PacketStore.LogEntry e : list) {
            String icon = e.kind.startsWith("msg") ? (e.kind.equals("msg-tx") ? "⬆️" : (e.mine ? "✉️" : "💬")) : e.kind.startsWith("ack") ? "✅" : e.kind.equals("beacon") ? "📍" : e.kind.equals("digi") ? "🔁" : e.kind.equals("pos") ? "📍" : "ℹ️";
            LinearLayout rowv = shell();
            String who = icon + " " + e.from + (e.to == null || e.to.isEmpty() ? "" : " → " + e.to);
            rowv.addView(Neo.label(c, e.mine ? Txt.s(R.string.packet_pkt_log_for_you, who) : who, e.mine ? Theme.AMBER : Theme.CYAN, 14, true));
            rowv.addView(Neo.label(c, f.format(new Date(e.ts)) + "  " + e.text + (e.id != null ? " {" + e.id : ""), 0xFFBFC8D0, 12, false));
            body.addView(rowv);
        }
        sub = dialog(body);
    }

    private void dtmfLog() {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_dtmflog_title), "channels");
        List<PacketStore.Dtmf> list = pe.store().dtmfLog(100);
        if (list.isEmpty()) body.addView(hint(Txt.s(R.string.packet_pkt_dtmflog_empty)));
        SimpleDateFormat f = Txt.dates("MM-dd HH:mm:ss", "MMddHHmmss");
        for (PacketStore.Dtmf e : list) {
            LinearLayout rowv = shell();
            rowv.addView(Neo.label(c, e.digits, Theme.CYAN, 18, true));
            rowv.addView(Neo.label(c, Txt.s(R.string.packet_pkt_dtmflog_row, f.format(new Date(e.ts)), e.channel, e.channelName), 0xFFBFC8D0, 12, false));
            body.addView(rowv);
        }
        sub = dialog(body);
    }

    private void wxLog() {
        LinearLayout body = page(Txt.s(R.string.packet_pkt_wxlog_title), "alert");
        List<PacketStore.Alert> list = pe.store().alerts(100);
        if (list.isEmpty()) body.addView(hint(Txt.s(R.string.packet_pkt_wxlog_empty)));
        SimpleDateFormat f = Txt.dates("MM-dd HH:mm", "MMddHHmm");
        for (PacketStore.Alert a : list) {
            LinearLayout rowv = shell();
            rowv.addView(Neo.label(c, (a.eom ? Txt.s(R.string.packet_same_end_of_message) : a.summary), a.eom ? 0xFF8A98A6 : Theme.AMBER, 14, true));
            rowv.addView(Neo.label(c, f.format(new Date(a.ts)) + "  " + a.raw, 0xFFBFC8D0, 11, false));
            body.addView(rowv);
        }
        sub = dialog(body);
    }

    // ---- settings ----
    private void settings() {
        final SharedPreferences p = pe.prefs();
        LinearLayout body = page(Txt.s(R.string.packet_set_title), "pkt");
        final EditText call = field(body, Txt.s(R.string.packet_set_callsign), p.getString("callsign", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        final EditText ssid = field(body, Txt.s(R.string.packet_set_ssid), String.valueOf(pe.ssid()), InputType.TYPE_CLASS_NUMBER);
        final EditText myRics = field(body, Txt.s(R.string.packet_set_rics), com.macdmr.radio.Station.ricText(c), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(hint(Txt.s(R.string.packet_set_identity_hint)));
        final EditText sym = field(body, Txt.s(R.string.packet_set_symbol), p.getString("symTable", "/") + p.getString("symCode", "["), InputType.TYPE_CLASS_TEXT);
        final EditText comment = field(body, Txt.s(R.string.packet_set_comment), pe.comment(), InputType.TYPE_CLASS_TEXT);
        final EditText path = field(body, Txt.s(R.string.packet_set_path), p.getString("path", "WIDE1-1,WIDE2-1"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        final EditText port = field(body, Txt.s(R.string.packet_set_kiss_port), String.valueOf(pe.kissPort()), InputType.TYPE_CLASS_NUMBER);
        body.addView(section(Txt.s(R.string.packet_set_is_section)));
        final EditText isServer = field(body, Txt.s(R.string.packet_set_is_server), pe.isServer(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText isPort = field(body, Txt.s(R.string.packet_set_is_port), String.valueOf(pe.isPort()), InputType.TYPE_CLASS_NUMBER);
        final EditText isPass = field(body, Txt.s(R.string.packet_set_is_passcode), p.getString("isPasscode", "-1"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText isMin = field(body, Txt.s(R.string.packet_set_is_beacon_min), String.valueOf(pe.isBeaconMin()), InputType.TYPE_CLASS_NUMBER);
        final EditText isComment = field(body, Txt.s(R.string.packet_set_is_comment), p.getString("isComment", pe.comment()), InputType.TYPE_CLASS_TEXT);
        body.addView(section(Txt.s(R.string.packet_set_auto_section, com.macdmr.radio.region.Region.mhzShown(svc.aprsHz()))));
        final String[] autoModes = { Txt.s(R.string.common_off), Txt.s(R.string.packet_set_auto_every), Txt.s(R.string.packet_pkt_auto_smart) };
        final int[] auto = { Math.max(0, Math.min(2, pe.autoBeacon())) };
        cycle(body, Txt.s(R.string.packet_set_beacon), autoModes, auto[0], v -> auto[0] = v);
        final EditText bMin = field(body, Txt.s(R.string.packet_set_beacon_min), String.valueOf(pe.beaconMin()), InputType.TYPE_CLASS_NUMBER);
        final boolean[] extra = { pe.telemetryOn(), pe.weatherOn(), pe.txIgate() };
        toggle(body, Txt.s(R.string.packet_set_telemetry), extra, 0);
        if (pe.hasBarometer()) toggle(body, Txt.s(R.string.packet_set_weather), extra, 1);
        toggle(body, Txt.s(R.string.packet_set_igate), extra, 2);
        final EditText groups = field(body, Txt.s(R.string.packet_set_groups), String.join(",", pe.groups()), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        body.addView(section(Txt.s(R.string.packet_set_passive_section)));
        final String[] modes = { Txt.s(R.string.common_off), Txt.s(R.string.packet_set_passive_flagged), Txt.s(R.string.packet_set_passive_every) };
        final int[] passive = { pe.passiveMode() };
        final TextView modeV = cycle(body, Txt.s(R.string.packet_set_decode_on), modes, passive[0], v -> passive[0] = v);
        body.addView(section(Txt.s(R.string.packet_set_power_section)));
        final int[] power = { pe.dataTxPower() == 0 ? 1 : 0 };
        cycle(body, Txt.s(R.string.packet_set_power), new String[] { Txt.s(R.string.packet_set_power_high), Txt.s(R.string.packet_set_power_low) }, power[0], v -> power[0] = v);
        body.addView(hint(Txt.s(R.string.packet_set_power_hint, com.macdmr.radio.region.Region.mhzShown(svc.aprsHz()))));
        body.addView(section(Txt.s(R.string.packet_set_lead_section)));
        final int[] lead = { 0 };
        for (int k = 0; k < PacketEngine.LEAD_CHOICES.length; k++) if (PacketEngine.LEAD_CHOICES[k] == pe.packetLeadMs()) lead[0] = k;
        final int[] kind = { pe.packetLeadKind() };
        cycle(body, Txt.s(R.string.packet_set_lead_sound), PacketEngine.leadKindNames(), kind[0], v -> kind[0] = v);
        cycle(body, Txt.s(R.string.packet_set_lead_length), PacketEngine.leadNames(), lead[0], v -> lead[0] = v);
        body.addView(hint(Txt.s(R.string.packet_set_lead_hint)));
        final boolean[] flags = { pe.autoAck(), pe.digipeat(), pe.dtmfEnabled(), pe.wxEnabled(), pe.cwEnabled(), pe.cwIdEnabled() };
        if (pe.autoAckAllowed()) toggle(body, Txt.s(R.string.packet_set_autoack), flags, 0);
        else body.addView(hint(Txt.s(R.string.packet_set_autoack_off)));
        toggle(body, Txt.s(R.string.packet_set_digi), flags, 1);
        toggle(body, Txt.s(R.string.packet_set_dtmf), flags, 2);
        toggle(body, Txt.s(R.string.packet_set_same), flags, 3);
        toggle(body, Txt.s(R.string.packet_set_morse), flags, 4);
        toggle(body, Txt.s(R.string.packet_set_morse_id), flags, 5);
        LinearLayout r = row();
        button(r, Txt.s(R.string.common_save), Theme.PKT, x -> {
            try {
                String s = sym.getText().toString().trim();
                String cs = call.getText().toString().trim().toUpperCase(Locale.US), ss = ssid.getText().toString().trim();
                int dash = cs.indexOf('-');
                if (dash > 0) { ss = cs.substring(dash + 1).trim(); cs = cs.substring(0, dash).trim(); call.setText(cs); ssid.setText(ss); }   // "CALL-7" typed in one box
                if (!cs.isEmpty() && !cs.matches("[A-Z0-9]{1,6}")) { toast(Txt.s(R.string.packet_set_err_callsign)); return; }
                int ssidV = Integer.parseInt(ss);
                if (ssidV < 0 || ssidV > 15) { toast(Txt.s(R.string.packet_set_err_ssid)); return; }
                p.edit().putString("callsign", cs)
                        .putInt("ssid", ssidV)
                        .putString("symTable", s.length() >= 1 ? s.substring(0, 1) : "/").putString("symCode", s.length() >= 2 ? s.substring(1, 2) : "[")
                        .putString("comment", comment.getText().toString().trim()).putString("path", path.getText().toString().trim().toUpperCase(Locale.US))
                        .putInt("kissPort", Integer.parseInt(port.getText().toString().trim())).putInt("passive", passive[0])
                        .putString("isServer", isServer.getText().toString().trim()).putInt("isPort", Integer.parseInt(isPort.getText().toString().trim()))
                        .putString("isPasscode", isPass.getText().toString().trim().isEmpty() ? "-1" : isPass.getText().toString().trim())
                        .putInt("isBeaconMin", Integer.parseInt(isMin.getText().toString().trim())).putString("isComment", isComment.getText().toString().trim())
                        .putInt("txLeadMs", PacketEngine.LEAD_CHOICES[lead[0]]).putInt("txLeadKind", kind[0]).putInt("txPower", power[0] == 1 ? 0 : 1).putInt("autoBeacon", auto[0]).putInt("beaconMin", Math.max(1, Integer.parseInt(bMin.getText().toString().trim())))
                        .putBoolean("telemetry", extra[0]).putBoolean("weather", extra[1]).putBoolean("txIgate", extra[2]).putString("groups", groups.getText().toString().trim().toUpperCase(Locale.US))
                        .putBoolean("autoAck", pe.autoAckAllowed() ? flags[0] : pe.prefs().getBoolean("autoAck", false)).putBoolean("digipeat", flags[1]).putBoolean("dtmf", flags[2]).putBoolean("wx", flags[3]).putBoolean("cw", flags[4]).putBoolean("cwId", flags[5]).apply();
                com.macdmr.radio.Station.setRics(c, myRics.getText().toString());
                pe.applySettings();
                toast(Txt.s(R.string.packet_pkt_saved)); sub.dismiss();
            } catch (NumberFormatException e) { toast(Txt.s(R.string.packet_set_err_numbers)); }
        });
        button(r, Txt.s(R.string.common_cancel), Theme.MON, x -> sub.dismiss());
        body.addView(r);
        sub = dialog(body);
    }

    private void toggle(LinearLayout body, String label, final boolean[] flags, final int i) {
        LinearLayout rowv = shell(); rowv.setOrientation(LinearLayout.HORIZONTAL); rowv.setGravity(Gravity.CENTER_VERTICAL);
        rowv.addView(Neo.label(c, label, 0xFFBFC8D0, 14, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView v = Neo.label(c, Txt.s(flags[i] ? R.string.packet_toggle_on : R.string.packet_toggle_off), flags[i] ? Theme.PKT[3] : 0xFF8A98A6, 13, true);
        v.setBackground(Theme.rounded(c, flags[i] ? Theme.PKT[0] : 0x18000000, flags[i] ? Theme.PKT[2] : 0x3300BFFF, 1, 14)); v.setPadding(dp(12), dp(6), dp(12), dp(6));
        rowv.addView(v);
        View.OnClickListener cl = x -> { flags[i] = !flags[i]; v.setText(Txt.s(flags[i] ? R.string.packet_toggle_on : R.string.packet_toggle_off)); v.setTextColor(flags[i] ? Theme.PKT[3] : 0xFF8A98A6); v.setBackground(Theme.rounded(c, flags[i] ? Theme.PKT[0] : 0x18000000, flags[i] ? Theme.PKT[2] : 0x3300BFFF, 1, 14)); };
        rowv.setClickable(true); rowv.setOnClickListener(cl); v.setOnClickListener(cl);
        body.addView(rowv);
    }

    // ---- widgets ----
    private interface IntSetter { void set(int v); }
    private int dp(float x) { return Math.round(x * d); }
    private void toast(String s) { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); }

    /** A sheet with its title and icon (the icon is named: a translated title no longer picks it). */
    private LinearLayout page(String title, String icon) { return Neo.sheetBody(c, title, icon); }
    /** Whole sentences one after the other, the empty ones left out (the separator is a string of its own: a space in English). */
    private static String sentences(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) { if (p == null || p.isEmpty()) continue; if (b.length() > 0) b.append(Txt.s(R.string.packet_sentence_sep)); b.append(p); }
        return b.toString();
    }
    /** Phrases in a list ("every 15 min, telemetry every 30 min"). */
    private static String list(List<String> items) { return String.join(Txt.s(R.string.packet_list_sep), items); }
    private AlertDialog dialog(LinearLayout body) { return Neo.showSheet(c, body); }
    private TextView hint(String s) { return Neo.sheetHint(c, s); }
    private TextView section(String s) { return Neo.sheetSection(c, s); }
    private LinearLayout row() {
        LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(2); lp.bottomMargin = dp(4); r.setLayoutParams(lp);
        return r;
    }
    private TextView button(LinearLayout row, String text, int[] accent, View.OnClickListener l) { TextView b = Neo.button(c, text, null, Neo.accentOf(accent)); b.setTextSize(14); Neo.fit(b); b.setOnClickListener(l); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins(dp(3), dp(4), dp(3), dp(2)); row.setBaselineAligned(false); row.addView(b, lp); return b; }
    private LinearLayout shell() {
        LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.VERTICAL);
        r.setBackground(Neo.rowCard(c)); r.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(6); r.setLayoutParams(lp);
        return r;
    }
    private EditText field(LinearLayout target, String label, String value, int inputType) {
        LinearLayout r = shell(); r.addView(Neo.label(c, label, 0xFF9AA4AE, 12, false));
        EditText e = new EditText(c); e.setText(value); e.setInputType(inputType); e.setTextSize(17); e.setTextColor(Theme.WHITE); e.setSingleLine(true); e.setBackground(null); e.setPadding(0, dp(4), 0, dp(4));
        r.addView(e); target.addView(r);
        return e;
    }
    private TextView cycle(LinearLayout target, String label, final String[] options, int index, final IntSetter setter) {
        LinearLayout r = shell(); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(Neo.label(c, label, 0xFFBFC8D0, 14, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView v = Neo.label(c, options[index], Theme.CYAN, 14, true); v.setBackground(Neo.outline(c, Neo.CYAN, 0x16, 12, 1)); v.setPadding(dp(12), dp(6), dp(12), dp(6));
        r.addView(v);
        final int[] cur = { index };
        View.OnClickListener cl = x -> { cur[0] = (cur[0] + 1) % options.length; v.setText(options[cur[0]]); setter.set(cur[0]); };
        r.setClickable(true); r.setOnClickListener(cl); v.setOnClickListener(cl);
        target.addView(r);
        return v;
    }
}
