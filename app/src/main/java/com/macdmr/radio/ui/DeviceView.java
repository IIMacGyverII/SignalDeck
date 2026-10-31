/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.RootSetup;
import com.macdmr.radio.i18n.Txt;

/**
 * Device tab: a link card (who owns the radio, port and speed, take over / hand back, boot persistence),
 * radio settings as neon switches (boot ownership, side and volume PTT keys, scan-time Soft SQ, voice,
 * intercom look), the DMR ID, and tool tiles (backup, restore, stock re-import, RadioID list, device
 * report, updates, help, diagnostics).
 */
public class DeviceView extends LinearLayout implements RadioService.Listener {
    public interface Host { void openHelp(); void openDiagnostics(); void openFirmware(); void intercomStyleChanged(); void animatedPanelChanged(boolean on); }

    private final LinearLayout header, hero;
    private final TextView linkText, profileText, idText, firmwareText, variantText;
    private final View linkDot;
    private final ImageView heroIcon;
    private final TextView takeOver, handBack, reportNow;
    private Neo.NeonSwitch customPttSw; private TextView customPttSub, regionSub;
    private final Neo.NeonSwitch ownAtBoot, voiceSw, hwPttSw, volPttSw, scanSqSw, styleSw, animSw, covSw, carMicSw, carCovSw, wheelSw, carScreenSw, roamSw;
    private TextView nextTalkValue, wheelMicValue, voxValue;
    private final LinearLayout radioIdTile;
    private RadioService svc;
    private Host host;

    public DeviceView(Context c) {
        super(c);
        setOrientation(VERTICAL);   // background: the window's
        float d = Theme.dp(c);
        header = Neo.header(c, Txt.s(R.string.device_title), "");
        addView(header);

        ScrollView sv = new ScrollView(c); sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(c); body.setOrientation(VERTICAL); body.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (28 * d));
        sv.addView(body); addView(sv, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- link hero ----
        hero = new LinearLayout(c); hero.setOrientation(VERTICAL); hero.setPadding((int) (14 * d), (int) (14 * d), (int) (14 * d), (int) (14 * d));
        LinearLayout top = new LinearLayout(c); top.setGravity(Gravity.CENTER_VERTICAL);
        heroIcon = Neo.iconTile(c, "module", Neo.GREEN, 52);
        top.addView(heroIcon, new LayoutParams((int) (52 * d), (int) (52 * d)));
        LinearLayout tc = new LinearLayout(c); tc.setOrientation(VERTICAL); tc.setPadding((int) (12 * d), 0, 0, 0);
        LinearLayout st = new LinearLayout(c); st.setGravity(Gravity.CENTER_VERTICAL);
        linkDot = new View(c); st.addView(linkDot, new LayoutParams((int) (9 * d), (int) (9 * d)));
        linkText = Neo.text(c, "", Neo.GREEN_LIGHT, 18, Neo.ui(c)); linkText.setPadding((int) (8 * d), 0, 0, 0);
        st.addView(linkText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        tc.addView(st);
        profileText = Neo.text(c, "", 0xFF9FB4C8, 13, Neo.mono(c)); profileText.setPadding(0, (int) (5 * d), 0, 0);
        tc.addView(profileText);
        profileText.setOnClickListener(v -> profileDialog());
        firmwareText = Neo.text(c, "", 0xFF9FB4C8, 13, Neo.mono(c)); firmwareText.setPadding(0, (int) (3 * d), 0, 0);
        tc.addView(firmwareText);
        firmwareText.setOnClickListener(v -> {                               // the loader exists only on phones whose flash process was bench-tested
            if (host == null) return;
            if (com.macdmr.radio.DeviceProfile.detect().firmwareLoader()) host.openFirmware();
            else android.widget.Toast.makeText(getContext(), Txt.s(R.string.device_link_fw_not_certified), android.widget.Toast.LENGTH_LONG).show();
        });
        variantText = Neo.text(c, "", Neo.AMBER_LIGHT, 13, Neo.ui(c)); variantText.setPadding(0, (int) (5 * d), 0, 0); variantText.setVisibility(GONE);
        tc.addView(variantText);
        variantText.setOnClickListener(v -> { com.macdmr.radio.FirmwareVariantCheck.Finding f = com.macdmr.radio.FirmwareVariantCheck.finding(getContext()); if (f != null) offerVariant(getContext(), f, this::refresh); });
        top.addView(tc, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(top);
        TextView contributeTop = Neo.button(c, Txt.s(R.string.device_contribute), "heart", Neo.VIOLET);
        LayoutParams ctl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ctl.topMargin = (int) (12 * d);
        hero.addView(contributeTop, ctl);
        contributeTop.setOnClickListener(v -> contributeSheet());
        LinearLayout hb = new LinearLayout(c); hb.setBaselineAligned(false);
        takeOver = Neo.button(c, Txt.s(R.string.device_link_take_over), "takeover", Neo.GREEN); handBack = Neo.button(c, Txt.s(R.string.device_link_hand_back), "handback", Neo.AMBER);
        Neo.fit(takeOver, true); Neo.fit(handBack, true);                  // side by side: a long word (German) never breaks inside
        LayoutParams hl = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f); hl.setMargins(0, (int) (12 * d), (int) (4 * d), 0);
        LayoutParams hr = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f); hr.setMargins((int) (4 * d), (int) (12 * d), 0, 0);
        hb.addView(takeOver, hl); hb.addView(handBack, hr);
        hero.addView(hb);
        takeOver.setOnClickListener(v -> {
            if (svc == null) return;
            if (svc.profile().verified || svc.isReady()) { svc.bringUp(); return; }
            new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.device_link_untested_title))
                    .setMessage(Txt.s(R.string.device_link_untested_msg, svc.profile().name, svc.profile().stockApp))
                    .setPositiveButton(Txt.s(R.string.device_link_take_over), (dd, w) -> svc.bringUp()).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        });
        handBack.setOnClickListener(v -> { if (svc != null) svc.shutDown(true); });
        TextView exit = Neo.button(c, Txt.s(R.string.device_exit), "power", Neo.RED);
        LayoutParams el = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); el.topMargin = (int) (8 * d);
        hero.addView(exit, el);
        exit.setOnClickListener(v -> exitSheet());
        TextView boot = Neo.button(c, Txt.s(R.string.device_link_keep_access), "shield", Neo.CYAN);
        LayoutParams bl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); bl.topMargin = (int) (8 * d);
        hero.addView(boot, bl);
        boot.setOnClickListener(v -> new Thread(() -> {
            final String r = svc != null ? RootSetup.installBootModule(svc.profile(), getContext().getCacheDir()) : Txt.s(R.string.device_link_boot_not_ready);
            post(() -> new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.device_link_boot_title)).setMessage(r).setPositiveButton(Txt.s(R.string.common_ok), null).show());
        }).start());
        reportNow = Neo.button(c, Txt.s(R.string.device_support), "report", Neo.VIOLET);
        hero.addView(reportNow, new LayoutParams(bl)); reportNow.setVisibility(GONE);
        reportNow.setOnClickListener(v -> supportMenu());
        TextView note = Neo.text(c, Txt.s(R.string.device_link_note), Neo.MUTED, 13, Neo.uiMedium(c));
        note.setLineSpacing(0, 1.1f); note.setPadding(0, (int) (10 * d), 0, 0);
        hero.addView(note);
        body.addView(hero, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ---- radio settings ----
        LinearLayout set = card(body, Txt.s(R.string.device_set_title));
        ownAtBoot = sw(); hwPttSw = sw(); volPttSw = sw(); scanSqSw = sw(); voiceSw = sw(); styleSw = sw(); animSw = sw(); covSw = sw(); carMicSw = sw(); carCovSw = sw(); wheelSw = sw(); carScreenSw = sw();
        LinearLayout regionRow = Neo.switchRow(c, "globe", Neo.CYAN, Txt.s(R.string.device_set_region), "", null);   // the region's defaults: APRS frequency, presets, units (Device → Region)
        regionSub = (TextView) ((LinearLayout) regionRow.getChildAt(1)).getChildAt(1);
        regionRow.setOnClickListener(v -> RegionSheet.show(c, svc, this::updateRegion));
        addRow(set, regionRow); updateRegion();
        addRow(set, languageRow(c));
        addRow(set, Neo.switchRow(c, "power", Neo.GREEN, Txt.s(R.string.device_set_own_boot), Txt.s(R.string.device_set_own_boot_sub), ownAtBoot));
        addRow(set, Neo.switchRow(c, "ptt", Neo.AMBER, Txt.s(R.string.device_set_side_ptt), Txt.s(R.string.device_set_side_ptt_sub), hwPttSw));
        addRow(set, Neo.switchRow(c, "volume", Neo.AMBER, Txt.s(R.string.device_set_vol_ptt), Txt.s(R.string.device_set_vol_ptt_sub), volPttSw));
        customPttSw = sw();
        LinearLayout keyRow = Neo.switchRow(c, "ptt", Neo.AMBER, Txt.s(R.string.device_set_custom_ptt), Txt.s(R.string.device_set_custom_ptt_sub), customPttSw);
        customPttSub = (TextView) ((LinearLayout) keyRow.getChildAt(1)).getChildAt(1);
        keyRow.setOnClickListener(v -> learnKeySheet());                      // the row learns; the switch turns the learned key on and off
        customPttSw.setOnChange(on -> { if (on && com.macdmr.radio.CustomPttKey.saved(c) == null) { customPttSw.setChecked(false); learnKeySheet(); return; } com.macdmr.radio.CustomPttKey.setEnabled(c, on); if (svc != null) svc.customPttChanged(); });
        addRow(set, keyRow);
        addRow(set, Neo.switchRow(c, "softsq", Neo.CYAN, Txt.s(R.string.device_set_scan_sq), Txt.s(R.string.device_set_scan_sq_sub), scanSqSw));
        addRow(set, Neo.switchRow(c, "voice", Neo.VIOLET, Txt.s(R.string.device_set_voice), Txt.s(R.string.device_set_voice_sub), voiceSw));
        addRow(set, rogerRow(c));
        addRow(set, txLimitRow(c));
        addRow(set, Neo.switchRow(c, "intercom", Neo.CYAN, Txt.s(R.string.device_set_new_look), Txt.s(R.string.device_set_new_look_sub), styleSw));
        addRow(set, layoutRow(c));
        addRow(set, Neo.switchRow(c, "diag", Neo.CYAN, Txt.s(R.string.device_set_anim), Txt.s(R.string.device_set_anim_sub), animSw));
        addRow(set, Neo.switchRow(c, "signal", Neo.VIOLET, Txt.s(R.string.device_set_coverage), com.macdmr.radio.DeviceProfile.detect().rssi ? Txt.s(R.string.device_set_coverage_sub_rssi)
                : Txt.s(R.string.device_set_coverage_sub_no_rssi), covSw));
        roamSw = sw();
        if (com.macdmr.radio.DeviceProfile.detect().dmr())                       // talkgroups: DMR only
            addRow(set, Neo.switchRow(c, "repeaters", Neo.GREEN, Txt.s(R.string.device_set_roaming), Txt.s(R.string.device_set_roaming_sub), roamSw));
        roamSw.setOnChange(on -> { if (svc != null) { svc.setRoaming(on); android.widget.Toast.makeText(c, on ? Txt.s(R.string.device_set_roaming_on) : Txt.s(R.string.device_set_roaming_off), android.widget.Toast.LENGTH_SHORT).show(); } });
        addRow(set, Neo.switchRow(c, "ptt", Neo.AMBER, Txt.s(R.string.device_set_car_mic), Txt.s(R.string.device_set_car_mic_sub), carMicSw));
        carMicSw.setOnChange(on -> { if (svc != null) svc.setCarMic(on); });
        autoWarn = Neo.switchRow(c, "alert", Neo.AMBER, Txt.s(R.string.device_set_auto_hidden), Txt.s(R.string.device_set_auto_hidden_sub), null);
        autoWarnDiv = new View(c); autoWarnDiv.setBackgroundColor(Neo.DIVIDER);
        set.addView(autoWarnDiv, new LayoutParams(LayoutParams.MATCH_PARENT, 1)); set.addView(autoWarn);
        autoWarn.setOnClickListener(v -> autoTrustSheet());
        autoWarn.setVisibility(GONE); autoWarnDiv.setVisibility(GONE);
        checkAutoTrust();
        addRow(set, Neo.switchRow(c, "signal", Neo.VIOLET, Txt.s(R.string.device_set_car_cov), Txt.s(R.string.device_set_car_cov_sub), carCovSw));
        carCovSw.setOnChange(on -> { if (svc != null) svc.setCoverageInCar(on); });
        addRow(set, Neo.switchRow(c, "ptt", Neo.RED, Txt.s(R.string.device_set_wheel), Txt.s(R.string.device_set_wheel_sub), wheelSw));
        wheelSw.setOnChange(on -> { if (svc != null) svc.setWheelTalk(on); });
        LinearLayout nextRow = Neo.switchRow(c, "ptt", Neo.RED, Txt.s(R.string.device_set_wheel_next), Txt.s(R.string.device_set_wheel_next_sub), null);
        nextTalkValue = Neo.chip(c, com.macdmr.radio.RadioService.nextTalkNames()[com.macdmr.radio.ham.WheelNext.DOUBLE], Neo.RED); nextRow.addView(nextTalkValue);
        nextRow.setOnClickListener(v -> { if (svc == null) return; int m = (svc.nextTalkMode() + 1) % 3; svc.setNextTalkMode(m); nextTalkValue.setText(com.macdmr.radio.RadioService.nextTalkNames()[m]); });
        addRow(set, nextRow);
        LinearLayout micRow = Neo.switchRow(c, "voice", Neo.RED, Txt.s(R.string.device_set_wheel_mic), Txt.s(R.string.device_set_wheel_mic_sub), null);
        wheelMicValue = Neo.chip(c, com.macdmr.radio.RadioService.wheelMicNames()[1], Neo.RED); micRow.addView(wheelMicValue);
        micRow.setOnClickListener(v -> { if (svc == null) return; int m = (svc.wheelMic() + 1) % 2; svc.setWheelMic(m); wheelMicValue.setText(com.macdmr.radio.RadioService.wheelMicNames()[m]); });
        addRow(set, micRow);
        LinearLayout voxRow = Neo.switchRow(c, "mon", Neo.RED, Txt.s(R.string.device_set_vox), Txt.s(R.string.device_set_vox_sub), null);
        voxValue = Neo.chip(c, com.macdmr.radio.RadioService.voxNames()[1], Neo.RED); voxRow.addView(voxValue);
        voxRow.setOnClickListener(v -> { if (svc == null) return; int m = (svc.voxStop() + 1) % com.macdmr.radio.RadioService.voxNames().length; svc.setVoxStop(m); voxValue.setText(com.macdmr.radio.RadioService.voxNames()[m]); });
        addRow(set, voxRow);
        addRow(set, Neo.switchRow(c, "intercom", Neo.CYAN, Txt.s(R.string.device_set_car_screen), Txt.s(R.string.device_set_car_screen_sub), carScreenSw));
        carScreenSw.setOnChange(on -> { if (svc != null) svc.setCarControlsScreen(on); });
        covSw.setOnChange(on -> { if (svc != null) { svc.setCoverageLogging(on); android.widget.Toast.makeText(c, on ? Txt.s(R.string.device_set_coverage_on) : Txt.s(R.string.device_set_coverage_off), android.widget.Toast.LENGTH_SHORT).show(); } });
        animSw.setOnChange(on -> { if (host != null) host.animatedPanelChanged(on); });
        ownAtBoot.setOnChange(on -> { if (svc != null) svc.setOwnAtBoot(on); });
        hwPttSw.setOnChange(on -> { if (svc != null) svc.setHwPtt(on); });
        volPttSw.setOnChange(on -> c.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putBoolean("volPtt", on).apply());
        scanSqSw.setOnChange(on -> { if (svc != null) svc.setScanSoftSq(on); });
        voiceSw.setOnChange(on -> { if (svc != null) svc.setVoiceOn(on); });
        styleSw.setOnChange(on -> { IntercomView.setNewLook(c, on); if (host != null) host.intercomStyleChanged(); });

        // ---- SignalDeck Web ----
        LinearLayout web = card(body, "SignalDeck Web");   // i18n: product name
        webSw = sw(); webTxSw = sw();
        LinearLayout webRow = Neo.switchRow(c, WEB_ICON, Neo.CYAN, Txt.s(R.string.device_web_access), Txt.s(R.string.device_web_access_sub), webSw);
        webSub = (TextView) ((LinearLayout) webRow.getChildAt(1)).getChildAt(1);
        addRow(web, webRow);
        addRow(web, Neo.switchRow(c, "ptt", Neo.RED, Txt.s(R.string.device_web_allow_tx), Txt.s(R.string.device_web_allow_tx_sub), webTxSw));
        LinearLayout pinRow = Neo.switchRow(c, "shield", Neo.AMBER, Txt.s(R.string.device_web_new_pin), Txt.s(R.string.device_web_new_pin_sub), null);
        pinRow.setOnClickListener(v -> { if (svc != null) { svc.web().newPin(); updateWeb(); } });
        addRow(web, pinRow);
        webSw.setOnChange(on -> { if (svc == null) return; String e = svc.web().setEnabled(on); if (e != null) android.widget.Toast.makeText(c, e, android.widget.Toast.LENGTH_LONG).show(); updateWeb(); });
        webTxSw.setOnChange(on -> { if (svc != null) svc.web().setAllowTx(on); });

        // ---- identity ----
        LinearLayout id = card(body, Txt.s(R.string.device_id_title));
        LinearLayout idRow = new LinearLayout(c); idRow.setGravity(Gravity.CENTER_VERTICAL); idRow.setPadding((int) (14 * d), (int) (4 * d), (int) (14 * d), (int) (12 * d));
        idRow.addView(Neo.iconTile(c, "id", Neo.CYAN, 38), new LayoutParams((int) (38 * d), (int) (38 * d)));
        idText = Neo.text(c, "", Neo.CYAN, 22, Neo.mono(c)); idText.setPadding((int) (12 * d), 0, 0, 0);
        idRow.addView(idText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView setId = Neo.button(c, Txt.s(R.string.device_id_change), null, Neo.CYAN); setId.setMinHeight((int) (40 * d));
        idRow.addView(setId, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        id.addView(idRow);
        if (!com.macdmr.radio.DeviceProfile.detect().dmr()) id.setVisibility(GONE);    // no digital channels to send it on
        setId.setOnClickListener(v -> {
            final EditText e = new EditText(c); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setText(svc != null ? String.valueOf(svc.localId()) : "");
            new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_id_title)).setMessage(Txt.s(R.string.device_id_msg)).setView(e)
                    .setPositiveButton(Txt.s(R.string.common_save), (dd, w) -> { try { int v2 = Integer.parseInt(e.getText().toString().trim()); if (v2 < 1 || v2 > 16776415) throw new NumberFormatException(); svc.setLocalId(v2); refresh(); } catch (Exception ex) { android.widget.Toast.makeText(c, Txt.s(R.string.device_id_bad), android.widget.Toast.LENGTH_LONG).show(); } })
                    .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        });

        // ---- the project's mission statement: its own button, above the tools ----
        TextView mission = Neo.button(c, Txt.s(R.string.device_mission), "star", Neo.VIOLET);
        LayoutParams ml = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ml.topMargin = (int) (18 * d);
        body.addView(mission, ml);
        mission.setOnClickListener(v -> missionSheet());

        // ---- tools ----
        TextView th = Neo.caps(c, Txt.s(R.string.device_tools)); th.setPadding((int) (2 * d), (int) (18 * d), 0, (int) (8 * d)); body.addView(th);
        LinearLayout backup = Neo.tile(c, "backup", Neo.GREEN, Txt.s(R.string.device_tool_backup), Txt.s(R.string.device_tool_backup_sub));
        LinearLayout restore = Neo.tile(c, "restore", Neo.AMBER, Txt.s(R.string.device_restore), Txt.s(R.string.device_tool_restore_sub));
        LinearLayout reimport = Neo.tile(c, "import", Neo.AMBER, Txt.s(R.string.device_tool_reimport), Txt.s(R.string.device_tool_reimport_sub));
        radioIdTile = Neo.tile(c, "globe", Neo.CYAN, Txt.s(R.string.device_radioid), "");
        LinearLayout report = Neo.tile(c, "report", Neo.VIOLET, Txt.s(R.string.device_report), Txt.s(R.string.device_tool_report_sub));
        LinearLayout about = Neo.tile(c, "module", Neo.GREEN, Txt.s(R.string.device_about_radio), Txt.s(R.string.device_tool_about_sub));
        LinearLayout upd = Neo.tile(c, "update", Neo.CYAN, Txt.s(R.string.device_tool_updates), Txt.s(R.string.device_tool_updates_sub));
        LinearLayout help = Neo.tile(c, "help", Neo.GREEN, Txt.s(R.string.device_tool_help), Txt.s(R.string.device_tool_help_sub));
        LinearLayout diag = Neo.tile(c, "diag", Neo.AMBER, Txt.s(R.string.device_tool_diag), Txt.s(R.string.device_tool_diag_sub));
        LinearLayout widget = Neo.tile(c, "widget", Neo.VIOLET, Txt.s(R.string.device_widget), Txt.s(R.string.device_tool_widget_sub));
        LinearLayout sos = Neo.tile(c, "alert", Neo.RED, Txt.s(R.string.device_tool_sos), Txt.s(R.string.device_tool_sos_sub));
        LinearLayout contribute = Neo.tile(c, "heart", Neo.VIOLET, Txt.s(R.string.device_contribute), Txt.s(R.string.device_tool_contribute_sub));
        java.util.List<LinearLayout> tl = new java.util.ArrayList<>(java.util.Arrays.asList(backup, restore, reimport, radioIdTile, report, about, upd, help, diag, widget, sos, contribute));
        if (!com.macdmr.radio.DeviceProfile.detect().dmr()) tl.remove(radioIdTile);   // names for DMR IDs: nothing to look up without DMR
        if (tl.size() % 2 == 1) tl.add(null);
        LinearLayout[] tiles = tl.toArray(new LinearLayout[0]);
        for (int i = 0; i < tiles.length; i += 2) {
            LinearLayout r = new LinearLayout(c); r.setBaselineAligned(false);
            LayoutParams l1 = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f); l1.setMargins(0, 0, (int) (4 * d), (int) (8 * d));
            LayoutParams l2 = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f); l2.setMargins((int) (4 * d), 0, 0, (int) (8 * d));
            r.addView(tiles[i], l1); r.addView(tiles[i + 1] != null ? tiles[i + 1] : new android.view.View(c), l2);
            body.addView(r);
        }
        backup.setOnClickListener(v -> doBackup());
        restore.setOnClickListener(v -> doRestore());
        reimport.setOnClickListener(v -> doReimport());
        radioIdTile.setOnClickListener(v -> doRadioId());
        report.setOnClickListener(v -> reportMenu());
        about.setOnClickListener(v -> radioNotesSheet());
        upd.setOnClickListener(v -> com.macdmr.radio.Updater.show(c));
        help.setOnClickListener(v -> { if (host != null) host.openHelp(); });
        diag.setOnClickListener(v -> { if (host != null) host.openDiagnostics(); });
        widget.setOnClickListener(v -> widgetSheet());
        sos.setOnClickListener(v -> EmergencyDialog.show(c, svc));
        contribute.setOnClickListener(v -> contributeSheet());

        TextView foot = Neo.text(c, Txt.s(com.macdmr.radio.DeviceProfile.detect().dmr() ? R.string.device_foot_dmr : R.string.device_foot_analog, com.macdmr.radio.BuildConfig.VERSION_NAME), Neo.DIM, 12.5f, Neo.uiMedium(c));
        foot.setLineSpacing(0, 1.1f); foot.setPadding((int) (2 * d), (int) (8 * d), (int) (2 * d), 0);
        body.addView(foot);
    }

    /** About this radio: the active profile's notes (assets/radios/radios.json, also docs/radios in the repository). */
    private void radioNotesSheet() {
        Context c = getContext(); float d = Theme.dp(c);
        com.macdmr.radio.RadioNotes.Entry e = com.macdmr.radio.RadioNotes.forActive(c);
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.device_about_radio), "radio");
        if (e == null) { box.addView(Neo.sheetHint(c, Txt.s(R.string.device_notes_none))); Neo.showSheet(c, box); return; }
        TextView name = Neo.text(c, e.name + (e.variant != null ? " · " + e.variant : ""), Neo.TEXT, 18, Neo.ui(c));
        name.setPadding((int) (2 * d), (int) (8 * d), 0, (int) (4 * d)); box.addView(name);
        int sc = "ok".equals(e.status) ? Neo.GREEN : "beta".equals(e.status) || "prog".equals(e.status) ? Neo.AMBER : Neo.RED;
        LinearLayout chips = new LinearLayout(c); chips.addView(Neo.chip(c, e.statusText(), sc)); box.addView(chips);
        box.addView(notesText(c, e.summary, Neo.TEXT, 15));
        if (e.radio != null) {
            box.addView(Neo.sheetSection(c, Txt.s(R.string.device_notes_radio)));
            if (e.radio.module != null) box.addView(specRow(c, Txt.s(R.string.device_notes_module), e.radio.module));
            if (!e.radio.modes.isEmpty()) box.addView(specRow(c, Txt.s(R.string.device_notes_modes), android.text.TextUtils.join(", ", e.radio.modes)));
            if (!e.radio.bands.isEmpty()) box.addView(specRow(c, Txt.s(R.string.device_notes_bands), com.macdmr.radio.RadioNotes.bandsText(e.radio)));
            box.addView(specRow(c, Txt.s(R.string.device_notes_power), e.radio.txPowerW == null ? Txt.s(R.string.device_notes_power_unknown) : android.text.TextUtils.join(" / ", e.radio.txPowerW) + " W"));
            if (e.radio.link != null) box.addView(specRow(c, Txt.s(R.string.device_notes_link), e.radio.link));
        }
        if (e.firmware != null) {
            if (e.firmware.stock != null) box.addView(specRow(c, Txt.s(R.string.device_notes_fw_stock), e.firmware.stock));
            if (e.firmware.modified != null) box.addView(specRow(c, Txt.s(R.string.device_notes_fw_mod), e.firmware.modified));
            if (e.firmware.note != null) box.addView(notesText(c, e.firmware.note, Neo.MUTED, 13));
        }
        if (!e.features.isEmpty()) {
            box.addView(Neo.sheetSection(c, Txt.s(R.string.device_notes_works)));
            for (com.macdmr.radio.RadioNotes.Feature f : e.features) {
                int fc = "yes".equals(f.state) ? Neo.GREEN : "partial".equals(f.state) ? Neo.AMBER : "no".equals(f.state) ? Neo.RED : Neo.CYAN;
                String st = "yes".equals(f.state) ? Txt.s(R.string.device_notes_yes) : "partial".equals(f.state) ? Txt.s(R.string.device_notes_partly) : "no".equals(f.state) ? Txt.s(R.string.device_notes_no) : Txt.s(R.string.device_notes_untested);
                box.addView(specRow(c, f.name, st + (f.note != null ? " · " + f.note : ""), fc));
            }
        }
        if (!e.quirks.isEmpty()) {
            box.addView(Neo.sheetSection(c, Txt.s(R.string.device_notes_quirks)));
            for (com.macdmr.radio.RadioNotes.Quirk q : e.quirks) noteCard(c, box, q.title, q.text, null);
        }
        if (!e.measured.isEmpty()) {
            box.addView(Neo.sheetSection(c, Txt.s(R.string.device_notes_measured)));
            box.addView(Neo.sheetHint(c, Txt.s(R.string.device_notes_measured_hint)));
            for (com.macdmr.radio.RadioNotes.Measured m : e.measured) noteCard(c, box, m.what, m.note, m.value);
        }
        for (com.macdmr.radio.RadioNotes.Section s : e.sections) { box.addView(Neo.sheetSection(c, s.head)); box.addView(notesText(c, s.text, Neo.TEXT, 14)); }
        box.addView(Neo.sheetHint(c, Txt.s(R.string.device_notes_source, e.id)));
        Neo.showSheet(c, box);
    }
    private static TextView notesText(Context c, String s, int color, float sp) {
        float d = Theme.dp(c); TextView t = Neo.text(c, s, color, sp, Neo.uiMedium(c));
        t.setLineSpacing(0, 1.12f); t.setTextIsSelectable(true); t.setPadding((int) (2 * d), (int) (6 * d), (int) (2 * d), (int) (4 * d)); return t;
    }
    private static LinearLayout specRow(Context c, String label, String value) { return specRow(c, label, value, Neo.TEXT); }
    private static LinearLayout specRow(Context c, String label, String value, int accent) {
        float d = Theme.dp(c);
        LinearLayout r = new LinearLayout(c); r.setPadding((int) (2 * d), (int) (3 * d), 0, (int) (3 * d));
        TextView l = Neo.text(c, label, Neo.MUTED, 13.5f, Neo.uiSemi(c)); l.setTextIsSelectable(true);
        TextView v = Neo.text(c, value, accent == Neo.TEXT ? Neo.TEXT : Neo.light(accent), 13.5f, Neo.uiMedium(c)); v.setTextIsSelectable(true); v.setLineSpacing(0, 1.08f);
        r.addView(l, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.38f)); r.addView(v, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.62f));
        return r;
    }
    private static void noteCard(Context c, LinearLayout box, String title, String text, String value) {
        float d = Theme.dp(c);
        LinearLayout card = new LinearLayout(c); card.setOrientation(VERTICAL); card.setBackground(Neo.rowCard(c));
        card.setPadding((int) (12 * d), (int) (9 * d), (int) (12 * d), (int) (9 * d));
        TextView t = Neo.text(c, title, Neo.TEXT, 15, Neo.ui(c)); t.setTextIsSelectable(true); card.addView(t);
        if (value != null) { TextView v = Neo.text(c, value, Neo.CYAN_LIGHT, 14.5f, Neo.uiSemi(c)); v.setTextIsSelectable(true); card.addView(v); }
        if (text != null) { TextView x = Neo.text(c, text, Neo.MUTED, 13.5f, Neo.uiMedium(c)); x.setLineSpacing(0, 1.1f); x.setTextIsSelectable(true); x.setPadding(0, (int) (3 * d), 0, 0); card.addView(x); }
        LayoutParams cl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); cl.setMargins(0, 0, 0, (int) (8 * d));
        box.addView(card, cl);
    }

    /** The project's mission statement (also at the top of the README). */
    public static final String MISSION = "This project is a showcase of what an Android ham radio can do. It is built for innovation, and built on the innovation, discovery and hard work of those who came before me. I hope it inspires others to create, and to look for the next step.";   // i18n: also served to the web page (/api/mission); the sheet shows device_mission_text
    private void missionSheet() {
        Context c = getContext(); float d = Theme.dp(c);
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.device_mission), "radio");
        TextView t = Neo.text(c, Txt.s(R.string.device_mission_text), Neo.TEXT, 20, Neo.uiMedium(c));
        t.setLineSpacing(0, 1.18f); t.setPadding((int) (4 * d), (int) (14 * d), (int) (4 * d), (int) (10 * d));
        box.addView(t);
        TextView by = Neo.text(c, "\u2014 IIMacGyverII, SignalDeck", Neo.VIOLET_LIGHT, 15, Neo.ui(c));   // i18n: the author's name
        by.setGravity(android.view.Gravity.END); by.setPadding(0, 0, (int) (4 * d), (int) (6 * d));
        box.addView(by);
        box.addView(Neo.sheetSection(c, Txt.s(R.string.device_mission_legal)));
        TextView legal = Neo.text(c, com.macdmr.radio.About.legal(), Neo.MUTED, 13, Neo.uiMedium(c));
        legal.setLineSpacing(0, 1.12f); legal.setTextIsSelectable(true); legal.setPadding((int) (4 * d), 0, (int) (4 * d), (int) (6 * d));
        box.addView(legal);
        Neo.showSheet(c, box);
    }

    /** Where donations go: Ko-fi, and GitHub Sponsors (shown already; the page answers once GitHub approves the account). */
    static final String DONATE_KOFI = "https://ko-fi.com/iimacgyverii";
    static final String DONATE_SPONSORS = "https://github.com/sponsors/IIMacGyverII";   // i18n: URL. owner 2026-10-30: in place before approval, so the link works the day GitHub approves it
    static final String SITE = "https://signaldeckradio.com";
    static final String REPO = "https://github.com/IIMacGyverII/SignalDeck";   // i18n: URL

    /** Device → Contribute (top of the page and among the tools): the three ways to help the project, each with its link. */
    private void contributeSheet() {
        Context c = getContext();
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.device_contribute), "radio");
        box.addView(Neo.sheetHint(c, Txt.s(R.string.device_contrib_intro)));
        box.addView(Neo.sheetSection(c, Txt.s(R.string.device_contrib_donate)));
        box.addView(para(c, Txt.s(R.string.device_contrib_donate_text)));
        box.addView(linkButton(c, Txt.s(R.string.device_contrib_kofi), "heart", Neo.VIOLET, DONATE_KOFI));
        if (DONATE_SPONSORS != null) box.addView(linkButton(c, "GitHub Sponsors", "heart", Neo.VIOLET, DONATE_SPONSORS));   // i18n: product name
        box.addView(Neo.sheetSection(c, Txt.s(R.string.device_contrib_develop)));
        box.addView(para(c, Txt.s(R.string.device_contrib_develop_text)));
        box.addView(linkButton(c, "signaldeckradio.com", "globe", Neo.CYAN, SITE));
        box.addView(linkButton(c, Txt.s(R.string.device_contrib_issue), "report", Neo.CYAN, REPO + "/issues"));
        box.addView(Neo.sheetSection(c, Txt.s(R.string.device_contrib_phone)));
        box.addView(para(c, Txt.s(R.string.device_contrib_phone_text)));
        box.addView(linkButton(c, Txt.s(R.string.device_contrib_repo), "globe", Neo.GREEN, REPO));
        Neo.showSheet(c, box);
    }
    private static TextView para(Context c, String s) {
        float d = Theme.dp(c);
        TextView t = Neo.text(c, s, Neo.TEXT, 14, Neo.uiMedium(c));
        t.setLineSpacing(0, 1.12f); t.setPadding((int) (4 * d), 0, (int) (4 * d), (int) (8 * d));
        return t;
    }
    private static TextView linkButton(Context c, String label, String icon, int accent, String url) {
        float d = Theme.dp(c);
        TextView b = Neo.button(c, label, icon, accent);
        LayoutParams l = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); l.bottomMargin = (int) (8 * d);
        b.setLayoutParams(l);
        b.setOnClickListener(v -> {
            try { c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
            catch (Throwable t) { android.widget.Toast.makeText(c, Txt.s(R.string.device_no_browser, url), android.widget.Toast.LENGTH_LONG).show(); }
        });
        return b;
    }

    /** Device → Home widget: the live widget as it will look, and a button that asks the launcher to add it. */
    private void widgetSheet() {
        Context c = getContext(); float d = Theme.dp(c);
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.device_widget), "widget");
        box.addView(Neo.sheetHint(c, Txt.s(R.string.device_widget_hint)));
        android.widget.FrameLayout holder = new android.widget.FrameLayout(c);
        try { holder.addView(com.macdmr.radio.RadioWidget.build(c, com.macdmr.radio.RadioService.running).apply(c.getApplicationContext(), holder)); /* app context: the activity inflater swaps in AppCompat views, which RemoteViews refuses */ } catch (Throwable t) { holder.addView(Neo.sheetHint(c, Txt.s(R.string.device_widget_no_preview, t.getMessage()))); }
        LayoutParams hl = new LayoutParams(LayoutParams.MATCH_PARENT, (int) (150 * d)); hl.setMargins(0, (int) (10 * d), 0, (int) (12 * d));
        box.addView(holder, hl);
        TextView add = Neo.button(c, Txt.s(R.string.device_widget_add), "widget", Neo.VIOLET);
        box.addView(add, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        final android.app.Dialog dlg = Neo.showSheet(c, box);
        add.setOnClickListener(v -> {
            android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(c);
            boolean asked = false;
            try { if (m.isRequestPinAppWidgetSupported()) asked = m.requestPinAppWidget(new android.content.ComponentName(c, com.macdmr.radio.RadioWidget.class), null, null); } catch (Throwable ignored) { }
            if (dlg != null) dlg.dismiss();
            if (!asked) android.widget.Toast.makeText(c, Txt.s(R.string.device_widget_manual), android.widget.Toast.LENGTH_LONG).show();
        });
    }

    private TextView rogerValue;
    /** Roger beep: tap cycles Off / Beep / Two-tone / Morse K and plays the tone on the phone speaker. */
    private LinearLayout rogerRow(Context c) {
        LinearLayout row = new LinearLayout(c); row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setMinimumHeight(Theme.dp(c, 60));
        row.setPadding(Theme.dp(c, 14), Theme.dp(c, 8), Theme.dp(c, 14), Theme.dp(c, 8));
        LayoutParams il = new LayoutParams(Theme.dp(c, 38), Theme.dp(c, 38)); il.rightMargin = Theme.dp(c, 12);
        row.addView(Neo.iconTile(c, "volume", Neo.GREEN, 38), il);
        LinearLayout col = new LinearLayout(c); col.setOrientation(VERTICAL);
        col.addView(Neo.label(c, Txt.s(R.string.device_roger), Neo.TEXT, 16, true));
        TextView sub = Neo.label(c, Txt.s(R.string.device_roger_sub), Neo.MUTED, 13, false); sub.setPadding(0, Theme.dp(c, 2), 0, 0); col.addView(sub);
        row.addView(col, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        rogerValue = Neo.chip(c, com.macdmr.radio.RadioService.rogerNames()[svc != null ? svc.rogerStyle() : 0], Neo.GREEN);
        row.addView(rogerValue);
        row.setOnClickListener(v -> {
            if (svc == null) return;
            int s = (svc.rogerStyle() + 1) % com.macdmr.radio.RadioService.rogerNames().length;
            svc.setRogerStyle(s); rogerValue.setText(com.macdmr.radio.RadioService.rogerNames()[s]);
            com.macdmr.radio.RadioService.previewRoger(s);
        });
        return row;
    }

    private TextView txLimitValue;
    /**
     * Longest transmission (owner, 2026-10-30): the presets, No limit and Custom…; anything over 2 minutes asks once with
     * the heat warning. An admin setting in Restricted mode ({@code svc.setTxLimit} refuses there).
     */
    private LinearLayout txLimitRow(final Context c) {
        LinearLayout row = Neo.switchRow(c, "ptt", Neo.AMBER, Txt.s(R.string.device_txlimit), Txt.s(R.string.device_txlimit_sub), null);
        txLimitValue = Neo.chip(c, com.macdmr.radio.TxLimit.name(svc != null ? svc.txLimitSetting() : com.macdmr.radio.TxLimit.DEFAULT_S), Neo.AMBER); row.addView(txLimitValue);
        row.setOnClickListener(v -> {
            if (svc == null) return;
            if (!svc.adminAllowed("txlimit")) { android.widget.Toast.makeText(c, Txt.s(R.string.device_txlimit_locked), android.widget.Toast.LENGTH_LONG).show(); return; }
            final int[] presets = com.macdmr.radio.TxLimit.PRESETS_S;
            int cur = svc.txLimitSetting(), sel = com.macdmr.radio.TxLimit.presetIndex(cur);
            String[] names = new String[presets.length + 1];
            for (int i = 0; i < presets.length; i++) names[i] = (presets[i] == com.macdmr.radio.TxLimit.DEFAULT_S ? Txt.s(R.string.device_txlimit_default, com.macdmr.radio.TxLimit.name(presets[i])) : com.macdmr.radio.TxLimit.name(presets[i]));
            names[presets.length] = sel < 0 ? Txt.s(R.string.device_txlimit_custom_value, com.macdmr.radio.TxLimit.name(cur)) : Txt.s(R.string.device_txlimit_custom);
            new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_txlimit))
                    .setSingleChoiceItems(names, sel < 0 ? presets.length : sel, (dd, w) -> {
                        dd.dismiss();
                        if (w < presets.length) { confirmTxLimit(c, presets[w]); return; }
                        final EditText e = new EditText(c); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setText(String.valueOf(cur == com.macdmr.radio.TxLimit.NONE ? com.macdmr.radio.TxLimit.DEFAULT_S : cur));
                        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_txlimit_custom_title)).setMessage(Txt.s(R.string.device_txlimit_custom_msg, com.macdmr.radio.TxLimit.MIN_CUSTOM_S)).setView(e)
                                .setPositiveButton(Txt.s(R.string.common_save), (d2, w2) -> { int s = com.macdmr.radio.TxLimit.parseCustom(e.getText().toString()); if (s < 0) android.widget.Toast.makeText(c, Txt.s(R.string.device_txlimit_custom_bad, com.macdmr.radio.TxLimit.MIN_CUSTOM_S), android.widget.Toast.LENGTH_LONG).show(); else confirmTxLimit(c, s); })
                                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
                    }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        });
        return row;
    }
    /** Longer than 2 minutes or No limit: the heat warning once, Continue / Cancel; then save. */
    private void confirmTxLimit(final Context c, final int s) {
        if (!com.macdmr.radio.TxLimit.needsWarning(s)) { saveTxLimit(c, s); return; }
        new android.app.AlertDialog.Builder(c).setTitle(s == com.macdmr.radio.TxLimit.NONE ? Txt.s(R.string.device_txlimit_none_title) : Txt.s(R.string.device_txlimit_long_title, com.macdmr.radio.TxLimit.name(s))).setMessage(com.macdmr.radio.TxLimit.heatWarning())
                .setPositiveButton(Txt.s(R.string.common_continue), (dd, w) -> saveTxLimit(c, s)).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }
    private void saveTxLimit(Context c, int s) {
        if (svc == null) return;
        String err = svc.setTxLimit(s);
        if (err != null) { android.widget.Toast.makeText(c, err, android.widget.Toast.LENGTH_LONG).show(); return; }
        txLimitValue.setText(com.macdmr.radio.TxLimit.name(svc.txLimitSetting()));
    }

    private Neo.NeonSwitch sw() { return new Neo.NeonSwitch(getContext()); }

    private LinearLayout card(LinearLayout body, String title) {
        Context c = getContext(); float d = Theme.dp(c);
        LinearLayout s = new LinearLayout(c); s.setOrientation(VERTICAL); s.setBackground(Neo.card(c, Neo.LINE, 18));
        s.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
        TextView t = Neo.caps(c, title); t.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (6 * d)); s.addView(t);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.topMargin = (int) (12 * d);
        body.addView(s, lp);
        return s;
    }

    private void addRow(LinearLayout card, LinearLayout row) {
        View div = new View(getContext()); div.setBackgroundColor(Neo.DIVIDER);
        card.addView(div, new LayoutParams(LayoutParams.MATCH_PARENT, 1));
        card.addView(row);
    }

    // ---- actions ----
    private void doBackup() {
        final Context c = getContext(); if (svc == null) return;
        new Thread(() -> {
            try { java.io.File dir = com.macdmr.radio.Backup.export(svc.store(), svc.packet() != null ? svc.packet().dtmfSaved() : null); post(() -> android.widget.Toast.makeText(c, Txt.s(R.string.device_backup_done, dir.getName()), android.widget.Toast.LENGTH_LONG).show()); }
            catch (Throwable t) { post(() -> android.widget.Toast.makeText(c, Txt.s(R.string.device_backup_failed, t.getMessage()), android.widget.Toast.LENGTH_LONG).show()); }
        }).start();
    }

    private void doRestore() {
        final Context c = getContext(); if (svc == null) return;
        final java.util.List<java.io.File> list = com.macdmr.radio.Backup.backups();
        final String[] names = new String[list.size() + 1]; for (int i = 0; i < list.size(); i++) names[i] = list.get(i).getName(); names[list.size()] = Txt.s(R.string.device_restore_browse);
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_restore_which)).setItems(names, (dd, w) -> {
            if (w == list.size()) {
                if (!(c instanceof com.macdmr.radio.MainActivity)) return;
                ((com.macdmr.radio.MainActivity) c).pickFolder(uri -> new Thread(() -> {
                    try { java.io.File dir = com.macdmr.radio.Backup.copyTree(c, uri); final String r = com.macdmr.radio.Backup.restore(svc.store(), dir); if (svc.packet() != null) for (java.util.Map.Entry<String, String> e : com.macdmr.radio.Backup.readDtmf(dir).entrySet()) svc.packet().dtmfSave(e.getKey(), e.getValue()); svc.selectChannel(svc.store().activeNumber()); post(() -> android.widget.Toast.makeText(c, r, android.widget.Toast.LENGTH_LONG).show()); }
                    catch (Throwable t) { post(() -> android.widget.Toast.makeText(c, Txt.s(R.string.device_restore_failed, t.getMessage()), android.widget.Toast.LENGTH_LONG).show()); }
                }).start());
                return;
            }
            new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_restore_confirm, names[w]))
                .setMessage(Txt.s(R.string.device_restore_msg))
                .setPositiveButton(Txt.s(R.string.device_restore), (d3, w3) -> new Thread(() -> {
                    try { final String r = com.macdmr.radio.Backup.restore(svc.store(), list.get(w)); if (svc.packet() != null) for (java.util.Map.Entry<String, String> e : com.macdmr.radio.Backup.readDtmf(list.get(w)).entrySet()) svc.packet().dtmfSave(e.getKey(), e.getValue()); svc.selectChannel(svc.store().activeNumber()); post(() -> android.widget.Toast.makeText(c, r, android.widget.Toast.LENGTH_LONG).show()); }
                    catch (Throwable t) { post(() -> android.widget.Toast.makeText(c, Txt.s(R.string.device_restore_failed, t.getMessage()), android.widget.Toast.LENGTH_LONG).show()); }
                }).start()).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        }).show();
    }

    private void doReimport() {
        final Context c = getContext(); if (svc == null) return;
        if (!svc.stockHasAreas()) {                                      // the W6's and the Xplore 1's stock app: one channel list
            new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_reimport_title)).setMessage(Txt.s(R.string.device_reimport_msg))
                    .setPositiveButton(Txt.s(R.string.common_import), (dd, w) -> new Thread(() -> { final String r = svc.reimportFromStock(null); post(() -> android.widget.Toast.makeText(c, r, android.widget.Toast.LENGTH_LONG).show()); }).start())
                    .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
            return;
        }
        new Thread(() -> {
            final java.util.List<String> areas = svc.stockAreas();
            post(() -> {
                String[] names = new String[areas.size()]; for (int i = 0; i < names.length; i++) names[i] = svc.stockImportsChannelsOnly() ? areas.get(i) : areas.get(i).replace('_', ' ').toUpperCase(java.util.Locale.US);
                new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_reimport_which)).setItems(names, (d1, w1) -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_reimport_confirm, names[w1])).setMessage(svc.stockImportsChannelsOnly() ? Txt.s(R.string.device_reimport_msg_channels) : Txt.s(R.string.device_reimport_msg_all))
                        .setPositiveButton(Txt.s(R.string.common_import), (dd, w) -> new Thread(() -> { final String r = svc.reimportFromStock(areas.get(w1)); post(() -> android.widget.Toast.makeText(c, r, android.widget.Toast.LENGTH_LONG).show()); }).start()).setNegativeButton(Txt.s(R.string.common_cancel), null).show()).show();
            });
        }).start();
    }

    private void doRadioId() {
        final Context c = getContext(); if (svc == null) return;
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_radioid)).setMessage(Txt.s(R.string.device_radioid_msg, svc.radioId().status()))
                .setPositiveButton(Txt.s(R.string.device_radioid_download), (dd, w) -> {
                    final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_radioid)).setMessage(Txt.s(R.string.device_radioid_connecting)).setCancelable(false).create(); pd.show();
                    new Thread(() -> {
                        try { int n = svc.radioId().downloadAndImport(t -> post(() -> pd.setMessage(t))); post(() -> { pd.dismiss(); refresh(); android.widget.Toast.makeText(c, Txt.p(R.plurals.device_radioid_loaded, n, String.format(java.util.Locale.US, "%,d", n)), android.widget.Toast.LENGTH_LONG).show(); }); }
                        catch (Throwable t) { post(() -> { pd.dismiss(); android.widget.Toast.makeText(c, Txt.s(R.string.device_radioid_failed, t.getMessage()), android.widget.Toast.LENGTH_LONG).show(); }); }
                    }).start();
                }).setNegativeButton(Txt.s(R.string.common_close), null).show();
    }

    private void reportMenu() {
        final Context c = getContext();
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_report))
                .setItems(new String[] { Txt.s(R.string.device_report_support), Txt.s(R.string.device_report_generate), Txt.s(R.string.device_report_share_last), Txt.s(R.string.device_report_capture), Txt.s(R.string.device_report_probe), Txt.s(R.string.device_report_issue), Txt.s(R.string.device_report_what), Txt.s(R.string.device_report_audio) , Txt.s(R.string.device_report_second), Txt.s(R.string.device_report_fwfiles), Txt.s(R.string.device_report_txtest)}, (dd, w) -> {
                    if (w == 10) { TxTestScreen.open(c, svc, this::shareReport); return; }
                    if (w == 9) { firmwareFilesIntro(); return; }
                    if (w == 7) { dmrAudioCheckIntro(); return; }
                    if (w == 8) { secondRadioIntro(); return; }
                    if (w == 0) supportMenu();
                    else if (w == 1) generateReport();
                    else if (w == 2) shareReport();
                    else if (w == 3) captureKeys();
                    else if (w == 4) probe();
                    else if (w == 5) com.macdmr.radio.DeviceReport.openIssue(c);
                    else new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_report)).setMessage(Txt.s(R.string.device_report_what_msg)).setPositiveButton(Txt.s(R.string.common_ok), null).show();
                }).show();
    }

    /**
     * Txt.s(R.string.device_support): the stages a new phone goes through, in order, each with its instructions and a status.
     * 1 Run all checks (receive only), 2 Listen check (a real station on a channel the owner picks), 3 Send the results.
     */
    public void supportMenu() {
        final Context c = getContext(); if (svc == null) return;
        final android.content.SharedPreferences sp = c.getSharedPreferences("radio", Context.MODE_PRIVATE);
        final boolean s1 = sp.getLong("supportChecks", 0) > 0, s2 = sp.getLong("supportListen", 0) > 0;
        String[] rows = {
                (s1 ? "✓ " : "") + Txt.s(R.string.device_support_step1),
                (sp.getLong("supportSecond", 0) > 0 ? "✓ " : s1 ? "" : "🔒 ") + Txt.s(R.string.device_support_step2),
                (s2 ? "✓ " : s1 ? "" : "🔒 ") + Txt.s(R.string.device_support_step2b),
                ((s1 || s2 || sp.getLong("supportSecond", 0) > 0) ? "" : "🔒 ") + Txt.s(R.string.device_support_step3),
                Txt.s(R.string.device_support_what) };
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_support_title, svc.profile().name))
                .setItems(rows, (dd, w) -> {
                    if (w == 0) offerChecks(false);
                    else if (w == 1) { if (s1) secondRadioIntro(); else locked(Txt.s(R.string.device_support_locked)); }
                    else if (w == 2) { if (s1) listenCheckIntro(); else locked(Txt.s(R.string.device_support_locked)); }
                    else if (w == 3) { if (s1 || s2 || sp.getLong("supportSecond", 0) > 0) shareReport(); else locked(Txt.s(R.string.device_support_locked_send)); }
                    else new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_support))
                            .setMessage(Txt.s(R.string.device_support_what_msg))
                            .setPositiveButton(Txt.s(R.string.common_ok), null).show();
                }).setNegativeButton(Txt.s(R.string.common_close), null).show();
    }

    /** Which profile this phone uses, the profile list and its advisories; the owner decides about every advisory. */
    /** Exit: radio off (the usual way to finish), or back to the stock app. */
    private void exitSheet() {
        final Context c = getContext();
        LinearLayout b = Neo.sheetBody(c, Txt.s(R.string.device_exit), "radio");
        b.addView(Neo.sheetHint(c, Txt.s(R.string.device_exit_hint)));
        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        TextView off = Neo.button(c, Txt.s(R.string.device_exit_off), "power", Neo.RED);
        off.setOnClickListener(v -> { dlg[0].dismiss(); if (c instanceof com.macdmr.radio.MainActivity) ((com.macdmr.radio.MainActivity) c).exitApp(false); });
        b.addView(off, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (52 * Theme.dp(c))));
        b.addView(Neo.sheetHint(c, Txt.s(R.string.device_exit_off_hint)));
        TextView back = Neo.button(c, Txt.s(R.string.device_exit_handback), "handback", Neo.AMBER);
        back.setOnClickListener(v -> { dlg[0].dismiss(); if (c instanceof com.macdmr.radio.MainActivity) ((com.macdmr.radio.MainActivity) c).exitApp(true); });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (52 * Theme.dp(c))); bl.topMargin = (int) (10 * Theme.dp(c));
        b.addView(back, bl);
        b.addView(Neo.sheetHint(c, Txt.s(svc != null && svc.ownAtBoot() ? R.string.device_exit_handback_hint_boot : R.string.device_exit_handback_hint)));
        dlg[0] = Neo.showSheet(c, b);
    }

    private void profileDialog() {
        final Context c = getContext();
        final com.macdmr.radio.ProfileRegistry.Selection s = com.macdmr.radio.ProfileRegistry.selection();
        StringBuilder m = new StringBuilder(com.macdmr.radio.ProfileRegistry.describe(s)).append('.');
        long last = com.macdmr.radio.ProfileRegistry.lastCheck();
        m.append(Txt.s(R.string.device_profsheet_last_checked, last == 0 ? Txt.s(R.string.device_profsheet_never) : android.text.format.DateFormat.getDateFormat(c).format(new java.util.Date(last)) + " " + android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(last))));
        if (com.macdmr.radio.ProfileRegistry.useBuiltIn()) m.append(Txt.s(R.string.device_profsheet_builtin));
        for (com.macdmr.radio.ProfileRegistry.Advisory a : s.advisories)
            m.append(Txt.s(a.overridden ? R.string.device_advisory_line_kept : R.string.device_advisory_line, a.title, a.restrictionText(), a.why))
                    .append(a.expires.isEmpty() ? "" : Txt.s(R.string.device_advisory_until, a.expires));
        m.append(Txt.s(R.string.device_profsheet_note));
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_profsheet_title)).setMessage(m)
                .setPositiveButton(Txt.s(R.string.device_profsheet_check), (dd, w) -> new Thread(() -> { final String r = com.macdmr.radio.ProfileRegistry.fetch(); post(() -> { android.widget.Toast.makeText(c, r, android.widget.Toast.LENGTH_LONG).show(); refresh(); }); }).start())
                .setNegativeButton(com.macdmr.radio.ProfileRegistry.useBuiltIn() ? Txt.s(R.string.device_profsheet_allow) : Txt.s(R.string.device_profsheet_use_builtin), (dd, w) -> { com.macdmr.radio.ProfileRegistry.setUseBuiltIn(!com.macdmr.radio.ProfileRegistry.useBuiltIn()); refresh(); });
        if (!s.advisories.isEmpty()) b.setNeutralButton(Txt.s(R.string.device_advisory_list), (dd, w) -> advisoryList(s));
        b.show();
    }

    private void advisoryList(final com.macdmr.radio.ProfileRegistry.Selection s) {
        final Context c = getContext();
        String[] rows = new String[s.advisories.size()];
        for (int i = 0; i < rows.length; i++) { com.macdmr.radio.ProfileRegistry.Advisory a = s.advisories.get(i); rows[i] = Txt.s(a.overridden ? R.string.device_advisory_row_overridden : R.string.device_advisory_row_active, a.title); }
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_advisory_list_title)).setItems(rows, (dd, w) -> advisory(s.advisories.get(w))).setNegativeButton(Txt.s(R.string.common_close), null).show();
    }

    /** Keep or override one advisory: overriding takes two confirmations, restoring takes one. */
    private void advisory(final com.macdmr.radio.ProfileRegistry.Advisory a) {
        final Context c = getContext();
        String body = a.expires.isEmpty() ? Txt.s(R.string.device_advisory_body, a.restrictionText(), a.why, a.issued) : Txt.s(R.string.device_advisory_body_lapses, a.restrictionText(), a.why, a.issued, a.expires);
        if (a.overridden) {
            new android.app.AlertDialog.Builder(c).setTitle(a.title).setMessage(Txt.s(R.string.device_advisory_body_kept, body))
                    .setPositiveButton(Txt.s(R.string.device_advisory_reapply), (dd, w) -> { com.macdmr.radio.ProfileRegistry.setOverride(a.id, false); refresh(); }).setNegativeButton(Txt.s(R.string.common_close), null).show();
            return;
        }
        new android.app.AlertDialog.Builder(c).setTitle(a.title).setMessage(body)
                .setPositiveButton(Txt.s(R.string.device_advisory_keep), null)
                .setNegativeButton(Txt.s(R.string.device_advisory_override), (dd, w) -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_advisory_confirm1))
                        .setMessage(Txt.s(R.string.device_advisory_confirm1_msg, a.restrictionText().toLowerCase(java.util.Locale.ROOT), a.why))
                        .setPositiveButton(Txt.s(R.string.common_continue), (d2, w2) -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_advisory_confirm2))
                                .setMessage(Txt.s(R.string.device_advisory_confirm2_msg))
                                .setPositiveButton(Txt.s(R.string.device_advisory_confirm2_ok), (d3, w3) -> { com.macdmr.radio.ProfileRegistry.setOverride(a.id, true); refresh(); android.widget.Toast.makeText(c, Txt.s(R.string.device_advisory_overridden), android.widget.Toast.LENGTH_LONG).show(); })
                                .setNegativeButton(Txt.s(R.string.common_cancel), null).show())
                        .setNegativeButton(Txt.s(R.string.common_cancel), null).show())
                .show();
    }

    private void locked(String why) { android.widget.Toast.makeText(getContext(), why, android.widget.Toast.LENGTH_LONG).show(); }

    // ---- Custom PTT key: learn a button by pressing it, with the conflict check ----
    private void refreshCustomPtt() {
        if (customPttSw == null) return;
        Context c = getContext(); com.macdmr.radio.CustomPttKey.Key k = com.macdmr.radio.CustomPttKey.saved(c);
        customPttSw.setChecked(com.macdmr.radio.CustomPttKey.enabled(c));
        customPttSub.setText(k == null ? Txt.s(R.string.device_set_custom_ptt_sub) : Txt.s(R.string.device_learn_learned, com.macdmr.radio.CustomPttKey.friendly(k.device, k.code)));
    }

    private void learnKeySheet() {
        final Context c = getContext(); float d = Theme.dp(c);
        if (svc == null) return;
        final com.macdmr.radio.CustomPttKey.Key had = com.macdmr.radio.CustomPttKey.saved(c);
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.device_learn_title), "ptt");
        box.addView(Neo.sheetHint(c, Txt.s(R.string.device_learn_hint)
                + (had == null ? "" : Txt.s(R.string.device_learn_now, com.macdmr.radio.CustomPttKey.friendly(had.device, had.code)))));
        final TextView status = Neo.label(c, "", Neo.AMBER_LIGHT, 16, true); status.setPadding(0, (int) (8 * d), 0, (int) (4 * d)); status.setVisibility(GONE);
        box.addView(status);
        final LinearLayout found = new LinearLayout(c); found.setOrientation(VERTICAL); box.addView(found);
        final TextView start = Neo.button(c, Txt.s(R.string.common_start), "ptt", Neo.AMBER), use = Neo.button(c, Txt.s(R.string.device_learn_use), "check", Neo.GREEN), forget = Neo.button(c, Txt.s(R.string.device_learn_forget), "close", Neo.RED);
        LayoutParams bl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); bl.setMargins(0, (int) (10 * d), 0, 0);
        box.addView(start, bl); box.addView(use, new LayoutParams(bl)); box.addView(forget, new LayoutParams(bl));
        use.setVisibility(GONE); forget.setVisibility(had == null ? GONE : VISIBLE);
        final android.app.Dialog dlg = Neo.showSheet(c, box);
        final com.macdmr.radio.CustomPttKey.Learned[] last = { null };
        forget.setOnClickListener(v -> {
            c.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().remove("pttKeyCode").remove("pttKeyDevice").remove("pttKeyAndroid").putBoolean("pttKeyOn", false).apply();
            if (svc != null) svc.customPttChanged(); refreshCustomPtt(); dlg.dismiss();
        });
        use.setOnClickListener(v -> {
            com.macdmr.radio.CustomPttKey.Learned l = last[0]; if (l == null || l.key == null) return;
            com.macdmr.radio.CustomPttKey.save(c, l.key);
            if (svc != null) svc.customPttChanged();
            refresh(); dlg.dismiss();
            android.widget.Toast.makeText(c, Txt.s(svc != null && !svc.profile().verified ? R.string.device_learn_saved_untested : R.string.device_learn_saved, l.label()), android.widget.Toast.LENGTH_LONG).show();
        });
        start.setOnClickListener(v -> {
            start.setVisibility(GONE); use.setVisibility(GONE); forget.setVisibility(GONE); found.removeAllViews();
            status.setVisibility(VISIBLE); status.setTextColor(Neo.AMBER_LIGHT); status.setText(Txt.s(R.string.device_starting));
            final com.macdmr.radio.RadioService s = svc;
            s.setPttLearning(true);
            new Thread(() -> {
                com.macdmr.radio.CustomPttKey.Learned l;
                try { l = com.macdmr.radio.CustomPttKey.learn(c, s.profile(), 10, t -> post(() -> status.setText(t))); }
                finally { s.setPttLearning(false); }
                final com.macdmr.radio.CustomPttKey.Learned r = l;
                post(() -> {
                    last[0] = r;
                    start.setText(Txt.s(R.string.device_learn_again)); start.setVisibility(VISIBLE); forget.setVisibility(com.macdmr.radio.CustomPttKey.saved(c) == null ? GONE : VISIBLE);
                    if (r.error != null) { status.setTextColor(Neo.AMBER_LIGHT); status.setText(r.error); return; }
                    boolean blocked = com.macdmr.radio.CustomPttKey.blocked(r.findings);
                    status.setTextColor(blocked ? Neo.RED : r.findings.isEmpty() ? Neo.GREEN_LIGHT : Neo.AMBER_LIGHT);
                    status.setText(Txt.s(blocked ? R.string.device_learn_blocked : r.findings.isEmpty() ? R.string.device_learn_clean : R.string.device_learn_note, r.label()));
                    for (com.macdmr.radio.CustomPttKey.Finding f : r.findings) {
                        TextView t = Neo.label(c, (f.block ? "⛔  " : "⚠  ") + f.text, f.block ? 0xFFFFB3BC : Neo.TEXT_SOFT, 13.5f, false);
                        t.setPadding(0, (int) (6 * d), 0, 0); found.addView(t);
                    }
                    if (!blocked) { use.setText(r.findings.isEmpty() ? Txt.s(R.string.device_learn_use) : Txt.s(R.string.device_learn_anyway)); use.setVisibility(VISIBLE); }
                });
            }, "ptt-learn").start();
        });
    }

    /** No audio on DMR: one minute on the current channel, a verdict, and everything that led to it in the report. */
    private void dmrAudioCheckIntro() {
        final Context c = getContext(); if (svc == null) return;
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_audio))
                .setMessage(Txt.s(R.string.device_audio_intro) + (svc.isReady() ? "" : Txt.s(R.string.device_audio_need_radio)))
                .setPositiveButton(Txt.s(R.string.common_start), (dd, w) -> runDmrAudioCheck()).setNegativeButton(Txt.s(R.string.common_later), null).show();
    }
    private void runDmrAudioCheck() {
        final Context c = getContext(); final com.macdmr.radio.RadioService s = svc; if (s == null) return;
        final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_audio)).setMessage(Txt.s(R.string.device_starting)).setCancelable(false).create(); pd.show();
        new Thread(() -> {
            String r;
            try { r = com.macdmr.radio.DmrAudioCheck.run(c, s, s.isReady() ? 60 : 1, t -> post(() -> pd.setMessage(t))); }
            catch (Throwable t) { r = "failed: " + t + "\n"; }   // i18n: report text
            final String result = r;
            com.macdmr.radio.DeviceReport.append("Radio audio check", result);   // i18n: report section name (the report stays English)
            post(() -> {
                pd.dismiss();
                int a = result.indexOf("verdict:\n"), b = result.indexOf("\n\n", Math.max(0, a));
                String v = a >= 0 && b > a ? result.substring(a + 9, b).trim() : result.substring(0, Math.min(600, result.length()));
                new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_audio)).setMessage(Txt.s(R.string.device_audio_result, v))
                        .setPositiveButton(Txt.s(R.string.device_share_report), (d2, w2) -> shareReport()).setNegativeButton(Txt.s(R.string.common_close), null).show();
            });
        }, "dmr-audio-check").start();
    }

    /** The guided second-radio test (SecondRadioTest): instructions, then a step at a time with Ready / Cancel. */
    private void secondRadioIntro() {
        final Context c = getContext(); if (svc == null) return;
        if (!svc.isReady()) { locked(Txt.s(R.string.device_take_over_first)); return; }
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_second))
                .setMessage(Txt.s(R.string.device_second_intro, com.macdmr.radio.DeviceProfile.detect().bandsText()))
                .setPositiveButton(Txt.s(R.string.common_start), (dd, w) -> runSecondRadioTest()).setNegativeButton(Txt.s(R.string.common_later), null).show();
    }
    private void runSecondRadioTest() {
        final Context c = getContext(); final com.macdmr.radio.RadioService s = svc; if (s == null) return;
        final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_second)).setMessage(Txt.s(R.string.device_starting)).setCancelable(false).create(); pd.show();
        final boolean[] cancelled = { false };
        com.macdmr.radio.SecondRadioTest.Ui ui = new com.macdmr.radio.SecondRadioTest.Ui() {
            @Override public boolean ask(String title, String text) throws InterruptedException {
                final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1); final boolean[] go = { false };
                post(() -> new android.app.AlertDialog.Builder(c).setTitle(title).setMessage(text).setCancelable(false)
                        .setPositiveButton(Txt.s(R.string.device_second_ready), (d2, w2) -> { go[0] = true; l.countDown(); }).setNegativeButton(Txt.s(R.string.device_second_cancel), (d2, w2) -> { cancelled[0] = true; l.countDown(); }).show());
                l.await(); return go[0];
            }
            @Override public void status(String text) { post(() -> pd.setMessage(text)); }
        };
        new Thread(() -> {
            String r;
            try { r = com.macdmr.radio.SecondRadioTest.run(s, ui); } catch (Throwable t) { r = "failed: " + t + "\n"; }   // i18n: report text
            final String result = r;
            if (com.macdmr.radio.DeviceReport.newestDir() == null) { try { com.macdmr.radio.DeviceReport.currentOrNew(c); } catch (Throwable ignored) { } }
            com.macdmr.radio.DeviceReport.append("Second radio test", result);   // i18n: report section name (the report stays English)
            if (!cancelled[0]) c.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putLong("supportSecond", System.currentTimeMillis()).apply();
            post(() -> {
                pd.dismiss();
                StringBuilder v = new StringBuilder(); for (String line : result.split("\n")) { String t = line.trim(); if (t.matches("[0-9]\\. .*")) v.append(v.length() > 0 ? "\n" : "").append(t); else if (t.startsWith("verdict:")) v.append(" \u2014 ").append(t.substring(8).trim()); }
                new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_second)).setMessage(Txt.s(R.string.device_second_result, v.length() == 0 ? result : v.toString()))
                        .setPositiveButton(Txt.s(R.string.device_share_report), (d2, w2) -> shareReport()).setNegativeButton(Txt.s(R.string.common_close), null).show();
            });
        }, "second-radio-test").start();
    }

    private void listenCheckIntro() {
        final Context c = getContext(); if (svc == null) return;
        String need = svc.isReady() ? "" : Txt.s(R.string.device_listen_need_radio);
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_listen))
                .setMessage(Txt.s(R.string.device_listen_intro) + need)
                .setPositiveButton(Txt.s(R.string.common_start), (dd, w) -> { if (svc.isReady()) runListenCheck(); else locked(Txt.s(R.string.device_take_over_first)); })
                .setNegativeButton(Txt.s(R.string.common_later), null).show();
    }

    /**
     * Two parts, each with its own question, so a "no" means one thing: 1 static (10 s, MON forces the squelch open: does
     * the speaker play anything at all?), 2 station (50 s, normal squelch on the owner's busy channel: does a real signal
     * open it and sound clean?). DMR has no static, so part 1 runs on analog channels only.
     */
    private void runListenCheck() {
        final Context c = getContext(); final com.macdmr.radio.RadioService s = svc; if (s == null) return;
        final com.macdmr.radio.ChannelStore.Channel ch = s.current();
        if (ch != null && ch.isDigital()) { listenStation(s, "part 1 (static) skipped: DMR channel, no static to hear\n"); return; }   // i18n: report text
        final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_listen_part1)).setMessage(Txt.s(R.string.device_starting)).setCancelable(false).create(); pd.show();
        final boolean wasMon = s.monitorOn();
        new Thread(() -> {
            String r;
            try {
                if (!wasMon) s.setMonitor(true);                       // squelch forced open: receiver noise plays even with nobody on the channel
                r = com.macdmr.radio.FirstRunCheck.listenCheck(s, 10, t -> post(() -> pd.setMessage(Txt.s(R.string.device_listen_part1_msg, t))),
                        new java.io.File(listenDir(c), "listen_static.wav"), 5000, false);         // 5 s of receiver noise: nothing private in it
            } catch (Throwable t) { r = "failed: " + t + "\n"; }   // i18n: report text
            finally { if (!wasMon) s.setMonitor(false); }
            final String part1 = r;
            post(() -> {
                pd.dismiss();
                final int[] answerIds = { R.string.device_listen_ans_static, R.string.device_listen_ans_talking, R.string.device_listen_ans_none, R.string.device_listen_ans_unsure };
                final String[] answers = new String[answerIds.length]; for (int i = 0; i < answers.length; i++) answers[i] = Txt.s(answerIds[i]);
                new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_listen_q1)).setCancelable(false)
                        .setItems(answers, (dd, w) -> listenStation(s, "part 1 (static, MON on, 10 s):\n" + part1 + "owner's answer: " + Txt.en(answerIds[w]) + "\n")).show();   // i18n: report text
            });
        }, "listen-check-static").start();
    }

    private void listenStation(final com.macdmr.radio.RadioService s, final String part1) {
        final Context c = getContext();
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_listen_part2))
                .setMessage(Txt.s(R.string.device_listen_part2_msg))
                .setCancelable(false).setPositiveButton(Txt.s(R.string.device_listen_part2_start), (d0, w0) -> {
                    final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_listen_part2_progress)).setMessage(Txt.s(R.string.device_starting)).setCancelable(false).create(); pd.show();
                    new Thread(() -> {
                        String r;
                        try { r = com.macdmr.radio.FirstRunCheck.listenCheck(s, 50, t -> post(() -> pd.setMessage(t)),
                                new java.io.File(listenDir(c), com.macdmr.radio.DeviceReport.STATION_CLIP), 20000, true); }   // up to 20 s, only while something is heard
                        catch (Throwable t) { r = "failed: " + t + "\n"; }   // i18n: report text
                        final String result = part1 + "\npart 2 (station, normal squelch, 50 s):\n" + r;   // i18n: report text
                        post(() -> {
                            pd.dismiss();
                            final int[] answerIds = { R.string.device_listen_ans_clear, R.string.device_listen_ans_distorted, R.string.device_listen_ans_none, R.string.device_listen_ans_nobody };
                            final String[] answers = new String[answerIds.length]; for (int i = 0; i < answers.length; i++) answers[i] = Txt.s(answerIds[i]);
                            new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_listen_q2)).setCancelable(false)
                                    .setItems(answers, (dd, w) -> new Thread(() -> {
                                        java.io.File rep = com.macdmr.radio.DeviceReport.currentOrNew(c);             // never into an old, unrelated report
                                        if (rep != null) for (java.io.File w2 : new java.io.File[] { new java.io.File(listenDir(c), "listen_static.wav"), new java.io.File(listenDir(c), com.macdmr.radio.DeviceReport.STATION_CLIP) })
                                            if (w2.length() > 44) { com.macdmr.radio.DeviceReport.copyFile(w2, new java.io.File(rep, w2.getName())); w2.delete(); }
                                        com.macdmr.radio.DeviceReport.append("Listen check", result + "owner's answer: " + Txt.en(answerIds[w]) + "\n");   // i18n: report text
                                        c.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putLong("supportListen", System.currentTimeMillis()).apply();
                                        post(() -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_listen_saved))
                                                .setMessage(Txt.s(R.string.device_listen_saved_msg, result, answers[w]))
                                                .setPositiveButton(Txt.s(R.string.device_listen_send), (d2, w2) -> shareReport()).setNegativeButton(Txt.s(R.string.common_later), null).show());
                                    }).start()).show();
                        });
                    }, "listen-check-station").start();
                }).show();
    }

    /** The guided first run: one dialog, one button, one zip to send. */
    public void offerChecks(boolean firstLaunch) { checks(firstLaunch); }

    private void generateReport() {
        final Context c = getContext();
        final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_report)).setMessage(Txt.s(R.string.device_starting)).setCancelable(false).create(); pd.show();
        new Thread(() -> {
            try { com.macdmr.radio.DeviceReport.generate(c, s -> post(() -> pd.setMessage(s + "…"))); post(() -> { pd.dismiss(); new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_report_ready)).setMessage(Txt.s(R.string.device_report_ready_msg)).setPositiveButton(Txt.s(R.string.common_ok), null).show(); }); }
            catch (Throwable t) { post(() -> { pd.dismiss(); android.widget.Toast.makeText(c, Txt.s(R.string.device_report_failed, t.getMessage()), android.widget.Toast.LENGTH_LONG).show(); }); }
        }).start();
    }

    /** Language: the phone's, or one SignalDeck ships (debug builds add the pseudo-locales for the layout check). */
    private LinearLayout languageRow(final Context c) {
        java.util.List<String> tags = new java.util.ArrayList<>(); tags.add("");
        java.util.Collections.addAll(tags, Txt.LANGUAGES);
        if (com.macdmr.radio.BuildConfig.DEBUG) java.util.Collections.addAll(tags, Txt.PSEUDO);
        final String[] names = new String[tags.size()];
        java.util.Locale phone = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
        for (int i = 0; i < names.length; i++) names[i] = tags.get(i).isEmpty() ? Txt.s(R.string.device_language_phone, Txt.nativeName(phone.toLanguageTag())) : Txt.nativeName(tags.get(i));
        int sel = Math.max(0, tags.indexOf(Txt.language()));
        final LinearLayout row = Neo.switchRow(c, "globe", Neo.CYAN, Txt.s(R.string.device_language), names[sel], null);
        final int pick = sel;
        row.setOnClickListener(v -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_language))
                .setSingleChoiceItems(names, pick, (dd, w) -> {
                    dd.dismiss();
                    if (w == pick) return;
                    Txt.setLanguage(tags.get(w));   // appcompat recreates the activity in the new language
                }).setNegativeButton(Txt.s(R.string.common_cancel), null).show());
        return row;
    }

    /** Intercom layout: Automatic (compact below 700 dp of screen height), Regular, or Compact. The page and the tab bar follow it. */
    private LinearLayout layoutRow(final Context c) {
        final String[] keys = { "auto", "regular", "compact" };
        final int hdp = c.getResources().getConfiguration().screenHeightDp;
        final String[] names = { Txt.s(IntercomView.compactFor(hdp, "auto") ? R.string.device_layout_auto_compact : R.string.device_layout_auto_regular, hdp), Txt.s(R.string.device_layout_regular), Txt.s(R.string.device_layout_compact) };
        String cur = IntercomView.layoutPref(c); int sel = 0; for (int i = 0; i < keys.length; i++) if (keys[i].equals(cur)) sel = i;
        final LinearLayout row = Neo.switchRow(c, "intercom", Neo.CYAN, Txt.s(R.string.device_layout), names[sel], null);
        final int pick = sel;
        row.setOnClickListener(v -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_layout))
                .setSingleChoiceItems(names, pick, (dd, w) -> {
                    dd.dismiss();
                    if (keys[w].equals(IntercomView.layoutPref(c))) return;
                    IntercomView.setLayoutPref(c, keys[w]);
                    if (c instanceof android.app.Activity) ((android.app.Activity) c).recreate();   // the tab bar is sized when the screen is built
                }).setNegativeButton(Txt.s(R.string.common_cancel), null).show());
        return row;
    }

    private void checks(boolean firstLaunch) {
        final Context c = getContext(); if (svc == null) return;
        final com.macdmr.radio.DeviceProfile dp = svc.profile();
        final boolean canTrial = dp.supported && !svc.isReady();
        StringBuilder mb = new StringBuilder();
        if (firstLaunch) mb.append(Txt.s(dp.supported ? R.string.device_checks_intro_known : R.string.device_checks_intro_unknown));
        mb.append(Txt.s(R.string.device_checks_steps));
        mb.append(Txt.s(dp.supported ? R.string.device_checks_probe : R.string.device_checks_probe_no_profile));
        if (canTrial) mb.append(Txt.s(dp.verified ? R.string.device_checks_trial : R.string.device_checks_trial_untested, dp.stockApp));
        if (dp.supported && !dp.stockApp.isEmpty()) mb.append(Txt.s(R.string.device_checks_stock_closed, dp.stockApp));
        mb.append(Txt.s(R.string.device_checks_end));
        String msg = mb.toString();
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(c).setTitle(firstLaunch ? Txt.s(R.string.device_checks_first_title) : Txt.s(R.string.device_checks)).setMessage(msg)
                .setPositiveButton(canTrial ? Txt.s(R.string.device_checks_with_trial) : Txt.s(R.string.device_checks_run), (dd, w) -> chooseApp(canTrial))
                .setNegativeButton(firstLaunch ? Txt.s(R.string.common_later) : Txt.s(R.string.common_cancel), null);
        if (canTrial) b.setNeutralButton(Txt.s(R.string.device_checks_only), (dd, w) -> chooseApp(false));
        b.show();
    }

    /**
     * Before the stock app is opened for 15 s the owner confirms which app it is: the profile's app on a known phone, else
     * the apps ranked by evidence (RadioAppFinder), best first, or none at all.
     */
    private void chooseApp(final boolean trial) {
        final Context c = getContext(); if (svc == null) return;
        if (svc.isReady()) { runChecks(trial, null); return; }        // SignalDeck owns the radio: the stock app is not opened
        final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_checks)).setMessage(Txt.s(R.string.device_checks_finding_app)).setCancelable(false).create(); pd.show();
        final com.macdmr.radio.DeviceProfile dp = svc.profile();
        new Thread(() -> {
            final java.util.List<com.macdmr.radio.RadioAppFinder.App> ranked = com.macdmr.radio.RadioAppFinder.rank(c);
            final boolean root = com.macdmr.radio.RootSetup.haveRoot();
            post(() -> {
                pd.dismiss();
                if (!root) { runChecks(trial, null); return; }
                final java.util.List<String> pkgs = new java.util.ArrayList<>(); final java.util.List<String> rows = new java.util.ArrayList<>();
                if (dp.supported && !dp.stockApp.isEmpty()) { pkgs.add(dp.stockApp); rows.add(Txt.s(R.string.device_checks_app_stock, appLabel(c, dp.stockApp), dp.stockApp, dp.name)); }
                for (com.macdmr.radio.RadioAppFinder.App a : ranked) if (pkgs.size() < 5 && a.launcher && !pkgs.contains(a.pkg)) { pkgs.add(a.pkg); rows.add(a.label + "\n" + a.pkg + " · " + a.whyText()); }
                final String OTHER = "\u0000other";
                pkgs.add(OTHER); rows.add(Txt.s(R.string.device_checks_app_other));
                pkgs.add(null); rows.add(Txt.s(R.string.device_checks_app_none));
                final int[] pick = { 0 };
                new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_checks_app_title))
                        .setSingleChoiceItems(rows.toArray(new String[0]), 0, (dd, w) -> pick[0] = w)
                        .setPositiveButton(Txt.s(R.string.common_continue), (dd, w) -> { if (OTHER.equals(pkgs.get(pick[0]))) chooseAnyApp(trial); else runChecks(trial, pkgs.get(pick[0])); })
                        .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
            });
        }, "radio-app-finder").start();
    }

    /** Every app with a launcher icon (system apps first), for a walkie-talkie app the ranking did not find. */
    private void chooseAnyApp(final boolean trial) {
        final Context c = getContext();
        final android.content.pm.PackageManager pm = c.getPackageManager();
        final java.util.List<android.content.pm.ResolveInfo> ris = new java.util.ArrayList<>(pm.queryIntentActivities(new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0));
        final java.util.List<String> pkgs = new java.util.ArrayList<>(); final java.util.List<String> rows = new java.util.ArrayList<>();
        java.util.List<String[]> all = new java.util.ArrayList<>();
        for (android.content.pm.ResolveInfo ri : ris) {
            String p = ri.activityInfo.packageName; if (p.equals(c.getPackageName()) || pkgs.contains(p)) continue;
            boolean sys = (ri.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            all.add(new String[] { (sys ? "0" : "1") + String.valueOf(ri.loadLabel(pm)).toLowerCase(java.util.Locale.ROOT), p, (sys ? Txt.s(R.string.device_checks_app_system, String.valueOf(ri.loadLabel(pm)), p) : String.valueOf(ri.loadLabel(pm)) + "\n" + p) });
            pkgs.add(p);
        }
        java.util.Collections.sort(all, (x, y) -> x[0].compareTo(y[0]));
        pkgs.clear(); for (String[] a : all) { pkgs.add(a[1]); rows.add(a[2]); }
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_checks_any_title))
                .setItems(rows.toArray(new String[0]), (dd, w) -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_checks_open_app, rows.get(w).split("\n")[0]))
                        .setMessage(Txt.s(R.string.device_checks_open_app_msg))
                        .setPositiveButton(Txt.s(R.string.common_continue), (d2, w2) -> runChecks(trial, pkgs.get(w))).setNegativeButton(Txt.s(R.string.common_back), (d2, w2) -> chooseAnyApp(trial)).show())
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private static String appLabel(Context c, String pkg) {
        try { return String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0))); } catch (Throwable t) { return pkg; }
    }

    private void runChecks(final boolean trial, final String watchPkg) {
        final Context c = getContext(); if (svc == null) return;
        final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_checks_running)).setMessage(Txt.s(R.string.device_starting)).setCancelable(false).create(); pd.show();
        final com.macdmr.radio.RadioService s = svc;
        new Thread(() -> {
            try {
                final java.io.File zip = com.macdmr.radio.FirstRunCheck.run(c, s, trial, watchPkg, t -> post(() -> pd.setMessage(t + "…")));
                c.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putLong("supportChecks", System.currentTimeMillis()).apply();
                final java.io.File dir = com.macdmr.radio.DeviceReport.newestDir();
                String rep = dir == null ? "" : com.macdmr.radio.DeviceReport.readReport(dir);
                int k = rep.lastIndexOf("==== Summary ====");
                final String summary = k >= 0 ? rep.substring(k + 17).trim() : "";
                post(() -> {
                    pd.dismiss();
                    new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_checks_done))
                            .setMessage((summary.isEmpty() ? "" : summary + "\n\n") + Txt.s(R.string.device_checks_done_msg, String.valueOf(zip.length() / 1024), dir != null ? dir.getName() : ""))
                            .setPositiveButton(Txt.s(R.string.common_send), (dd, w) -> shareReport()).setNeutralButton(Txt.s(R.string.device_checks_issue), (dd, w) -> com.macdmr.radio.DeviceReport.openIssue(c)).setNegativeButton(Txt.s(R.string.common_close), null).show();
                });
            } catch (Throwable t) { post(() -> { pd.dismiss(); android.widget.Toast.makeText(c, Txt.s(R.string.device_checks_failed, t.getMessage()), android.widget.Toast.LENGTH_LONG).show(); }); }
        }, "first-run-check").start();
    }

    private void shareReport() {
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.device_share_title))
                .setMessage(Txt.s(R.string.device_share_msg))
                .setPositiveButton(Txt.s(R.string.device_share_all), (dd, w) -> askStation(true)).setNeutralButton(Txt.s(R.string.device_share_no_apk), (dd, w) -> askStation(false)).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private static java.io.File listenDir(Context c) { java.io.File d = new java.io.File(c.getCacheDir(), "listen"); d.mkdirs(); return d; }

    /** The listen check's station recording may hold other people's voices: only with the owner's yes (default: leave it out). */
    private void askStation(final boolean withApks) {
        java.io.File dir = com.macdmr.radio.DeviceReport.newestDir();
        if (dir == null || !new java.io.File(dir, com.macdmr.radio.DeviceReport.STATION_CLIP).exists()) { shareZip(withApks, false); return; }
        final boolean[] include = { false };
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.device_share_station_q))
                .setSingleChoiceItems(new String[] { Txt.s(R.string.device_share_station_no), Txt.s(R.string.device_share_station_yes) }, 0, (dd, w) -> include[0] = w == 1)
                .setPositiveButton(Txt.s(R.string.common_share), (dd, w) -> shareZip(withApks, include[0])).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private void captureKeys() {
        final Context c = getContext();
        final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_capture_title)).setMessage(Txt.s(R.string.device_capture_msg)).setCancelable(false).create(); pd.show();
        new Thread(() -> { final String r = com.macdmr.radio.DeviceReport.captureKeys(c, 10); final java.io.File dir = com.macdmr.radio.DeviceReport.append("PTT key capture", r); post(() -> { pd.dismiss(); android.widget.Toast.makeText(c, dir == null ? Txt.s(R.string.device_capture_no_report) : Txt.s(R.string.device_capture_saved), android.widget.Toast.LENGTH_LONG).show(); }); }).start();   // i18n: report section name (the report stays English)
    }

    /** Opt-in: list the phone's radio firmware files (name, size, SHA-256, version strings); copy only the ticked ones. */
    private void firmwareFilesIntro() {
        final Context c = getContext();
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_fwfiles))
                .setMessage(Txt.s(R.string.device_fwfiles_intro))
                .setPositiveButton(Txt.s(R.string.device_fwfiles_look), (dd, w) -> firmwareFilesScan()).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private void firmwareFilesScan() {
        final Context c = getContext();
        final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_fwfiles)).setMessage(Txt.s(R.string.device_starting)).setCancelable(false).create(); pd.show();
        new Thread(() -> {
            final com.macdmr.radio.RadioService rs = svc != null ? svc : com.macdmr.radio.RadioService.running;   // the page may not be attached yet
            final String stockApp = rs != null ? rs.profile().stockApp : "";
            java.util.List<String> apks = com.macdmr.radio.FirmwareFinder.stockApks(c, stockApp);   // each step guarded and logged; root pm path when the stock app is not visible
            final java.util.List<com.macdmr.radio.FirmwareFinder.Found> fs = com.macdmr.radio.FirmwareFinder.scan(c, apks, s -> post(() -> pd.setMessage(s + "…")));
            if (apks.isEmpty()) fs.add(com.macdmr.radio.FirmwareFinder.noStockApk(stockApp));
            final java.io.File dir = com.macdmr.radio.DeviceReport.append("Radio firmware files found (root)", com.macdmr.radio.FirmwareFinder.describe(fs));   // i18n: report section name (the report stays English)
            post(() -> {
                pd.dismiss();
                final java.util.List<com.macdmr.radio.FirmwareFinder.Found> readable = new java.util.ArrayList<>();
                for (com.macdmr.radio.FirmwareFinder.Found f : fs) if (f.size > 0 && !f.sha256.isEmpty() && !f.sha256.startsWith("(")) readable.add(f);
                if (readable.isEmpty()) {
                    new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_fwfiles)).setMessage(Txt.s(dir == null ? R.string.device_fwfiles_none : R.string.device_fwfiles_none_in_report)).setPositiveButton(Txt.s(R.string.common_ok), null).show();
                    com.macdmr.radio.FirmwareFinder.cleanUp(c); return;
                }
                String[] items = new String[readable.size()]; final boolean[] on = new boolean[items.length];
                for (int i = 0; i < items.length; i++) { com.macdmr.radio.FirmwareFinder.Found f = readable.get(i); items[i] = f.name() + "\n" + (f.size / 1024) + " kB" + (f.versions.isEmpty() ? "" : " · " + f.versions) + (f.known.isEmpty() ? "" : Txt.s(R.string.device_fwfiles_known, f.known)) + "\n" + f.source; }
                new android.app.AlertDialog.Builder(c).setTitle(Txt.p(R.plurals.device_fwfiles_found, readable.size(), readable.size())).setMultiChoiceItems(items, on, (d2, w2, ch) -> on[w2] = ch)
                        .setPositiveButton(Txt.s(R.string.device_fwfiles_copy), (d2, w2) -> {
                            java.util.List<com.macdmr.radio.FirmwareFinder.Found> ticked = new java.util.ArrayList<>();
                            for (int i = 0; i < on.length; i++) if (on[i]) ticked.add(readable.get(i));
                            if (ticked.isEmpty()) { com.macdmr.radio.FirmwareFinder.cleanUp(c); return; }
                            new Thread(() -> {
                                String msg;
                                try { java.io.File out = com.macdmr.radio.FirmwareFinder.copy(ticked); msg = Txt.p(R.plurals.device_fwfiles_copied, ticked.size(), ticked.size(), out.getName()); com.macdmr.radio.DeviceReport.append("Radio firmware files copied", Txt.enP(R.plurals.device_fwfiles_copied, ticked.size(), ticked.size(), out.getName()) + "\n"); }   // i18n: report section name; the report gets the English
                                catch (Throwable t) { msg = Txt.s(R.string.device_fwfiles_copy_failed, t.getMessage()); }
                                com.macdmr.radio.FirmwareFinder.cleanUp(c);
                                final String m = msg; post(() -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_fwfiles)).setMessage(m).setPositiveButton(Txt.s(R.string.common_ok), null).show());
                            }).start();
                        })
                        .setNegativeButton(Txt.s(R.string.device_fwfiles_copy_none), (d2, w2) -> com.macdmr.radio.FirmwareFinder.cleanUp(c)).setCancelable(false).show();
            });
        }).start();
    }

    private void probe() {
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.device_probe)).setMessage(Txt.s(R.string.device_probe_msg))
                .setPositiveButton(Txt.s(R.string.device_probe_run), (dd, w) -> runProbe(false)).setNeutralButton(svc != null && svc.profile().supported ? Txt.s(R.string.device_probe_pulse) : null, (dd, w) -> runProbe(true)).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private void shareZip(final boolean withApks, final boolean withStation) {
        final Context c = getContext();
        new Thread(() -> { try { final java.io.File zip = com.macdmr.radio.DeviceReport.zipNewest(c, withApks, withStation); post(() -> com.macdmr.radio.DeviceReport.share(c, zip)); } catch (Throwable t) { post(() -> android.widget.Toast.makeText(c, Txt.s(R.string.device_share_none), android.widget.Toast.LENGTH_LONG).show()); } }).start();
    }

    private void runProbe(boolean pulse) {
        final Context c = getContext();
        final android.app.AlertDialog pd = new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_probe)).setMessage(Txt.s(R.string.device_starting)).setCancelable(false).create(); pd.show();
        new Thread(() -> {
            final String r = com.macdmr.radio.RadioProbe.run(c, s -> post(() -> pd.setMessage(s + "…")), pulse);
            final java.io.File dir = com.macdmr.radio.DeviceReport.append("Radio probe", r);   // i18n: report section name (the report stays English)
            post(() -> { pd.dismiss(); new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_probe)).setMessage(dir == null ? Txt.s(R.string.device_probe_not_saved, r.length() > 3000 ? r.substring(0, 3000) + "…" : r) : (r.length() > 3000 ? r.substring(0, 3000) + "…" : r)).setPositiveButton(Txt.s(R.string.common_ok), null).show(); });
        }).start();
    }

    public void setHost(Host h) { host = h; }
    public void attach(RadioService s) { svc = s; s.addListener(this); refresh(); }

    /** The map can switch coverage logging too: show the current state whenever this page comes back. */
    @Override protected void onVisibilityChanged(android.view.View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE && svc != null && covSw != null) covSw.setChecked(svc.coverage().enabled());
        if (visibility == VISIBLE && svc != null && profileText != null) refresh();      // a profile-list check may have brought an advisory
        if (visibility == VISIBLE && autoWarn != null) checkAutoTrust();   // also after coming back from Android Auto's settings
    }
    public void detach() { if (svc != null) svc.removeListener(this); svc = null; }

    // ---- Android Auto trust: the warning row shows only while Android Auto would hide the app ----
    private LinearLayout autoWarn; private View autoWarnDiv;
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && autoWarn != null && isShown()) checkAutoTrust();
    }
    private void checkAutoTrust() {
        if (!com.macdmr.radio.DeviceProfile.detect().verified) return;     // reads Android Auto's files with root: no Magisk prompt before the radio is set up
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            com.macdmr.radio.car.AutoTrust.State st = com.macdmr.radio.car.AutoTrust.check(app);
            post(() -> {
                boolean hidden = st == com.macdmr.radio.car.AutoTrust.State.OFF || st == com.macdmr.radio.car.AutoTrust.State.LOCKED;
                if (autoWarn != null) { autoWarn.setVisibility(hidden ? VISIBLE : GONE); autoWarnDiv.setVisibility(hidden ? VISIBLE : GONE); }
                if (st == com.macdmr.radio.car.AutoTrust.State.OK) com.macdmr.radio.car.AutoTrust.clearNotice(app);
            });
        }, "autotrust").start();
    }
    private void autoTrustSheet() {
        Context c = getContext(); float d = Theme.dp(c);
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.device_auto_title), "radio");
        box.addView(Neo.sheetHint(c, Txt.s(R.string.device_auto_hint)));
        box.addView(Neo.sheetHint(c, com.macdmr.radio.car.AutoTrust.steps(com.macdmr.radio.car.AutoTrust.last())));
        TextView open = Neo.button(c, Txt.s(R.string.device_auto_open), "intercom", Neo.AMBER);
        TextView again = Neo.button(c, Txt.s(R.string.device_auto_again), "update", Neo.CYAN);
        LayoutParams bl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); bl.setMargins(0, (int) (10 * d), 0, 0);
        box.addView(open, bl); box.addView(again, new LayoutParams(bl));
        final android.app.Dialog dlg = Neo.showSheet(c, box);
        open.setOnClickListener(v -> { try { c.startActivity(com.macdmr.radio.car.AutoTrust.settingsIntent(c)); } catch (Throwable t) { android.widget.Toast.makeText(c, Txt.s(R.string.intercom_lastheard_open_auto_failed, t.getMessage()), android.widget.Toast.LENGTH_LONG).show(); } });
        again.setOnClickListener(v -> { dlg.dismiss(); checkAutoTrust(); });
    }

    /** The Device page re-reads the firmware-variant finding (after the start-up check). */
    public void refreshVariant() { refresh(); }

    /** Ask whether to submit this phone's firmware variant: GitHub's issue form, filled in, in the browser. */
    public static void offerVariant(Context c, com.macdmr.radio.FirmwareVariantCheck.Finding f, Runnable after) {
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_variant))
                .setMessage(Txt.s(R.string.device_variant_msg, f.why) + Txt.s(f.fileToAttach() ? R.string.device_variant_attach : R.string.device_variant_stays) + Txt.s(R.string.device_variant_notice))
                .setPositiveButton(Txt.s(R.string.device_variant_open), (d, w) -> {
                    com.macdmr.radio.FirmwareVariantCheck.markOpened(c, f.key);
                    final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    new Thread(() -> {
                        final String url = com.macdmr.radio.FirmwareVariantCheck.prepareAttachment(c, f);
                        h.post(() -> {
                            if (!url.equals(f.url)) android.widget.Toast.makeText(c, Txt.s(R.string.device_variant_copied), android.widget.Toast.LENGTH_LONG).show();
                            try { c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); }
                            catch (Throwable t) { android.widget.Toast.makeText(c, Txt.s(R.string.device_variant_no_browser), android.widget.Toast.LENGTH_LONG).show(); }
                            if (after != null) after.run();
                        });
                    }, "fwvariant-file").start();
                })
                .setNegativeButton(Txt.s(R.string.common_not_now), null).show();
    }

    /** What the firmware line means; when the module runs other firmware, the stock version can be reset to it. */
    private void firmwareSheet() {
        if (svc == null) return;
        final Context c = getContext(); float d = Theme.dp(c);
        final String stock = svc.stockFirmware(), now = svc.moduleVersion().trim();
        boolean differs = com.macdmr.radio.Firmware.differs(stock, now);
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.device_modfw_title), "radio");
        box.addView(Neo.sheetHint(c, Txt.s(R.string.device_modfw_versions, stock.isEmpty() ? Txt.s(R.string.device_modfw_stock_unknown) : stock, now.isEmpty() ? Txt.s(R.string.device_modfw_no_answer) : now)));
        box.addView(Neo.sheetHint(c, Txt.s(differs ? R.string.device_modfw_hint_differs : R.string.device_modfw_hint_stock)));
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        if (differs) {
            TextView reset = Neo.button(c, Txt.s(R.string.device_modfw_reset), "update", Neo.AMBER);
            LayoutParams bl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); bl.setMargins(0, (int) (10 * d), 0, 0);
            box.addView(reset, bl);
            reset.setOnClickListener(v -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.device_modfw_reset_title, now))
                    .setMessage(Txt.s(R.string.device_modfw_reset_msg, stock))
                    .setPositiveButton(Txt.s(R.string.device_modfw_reset_ok), (dd, w) -> { svc.setStockFirmwareToCurrent(); if (dlg[0] != null) dlg[0].dismiss(); refresh(); })
                    .setNegativeButton(Txt.s(R.string.common_cancel), null).show());
        }
        dlg[0] = Neo.showSheet(c, box);
    }

    /** The Region row's line (after a change on its sheet or the intercom card). */
    public void updateRegion() { if (regionSub != null) regionSub.setText(RegionSheet.summary(getContext(), com.macdmr.radio.region.Region.current(getContext()))); }
    private void refresh() {
        updateRegion();
        if (svc == null) return;
        Context c = getContext(); float d = Theme.dp(c);
        Neo.headerSubtitle(header).setText(svc.profile().name.toUpperCase(java.util.Locale.US));
        profileText.setText(svc.profile().controlPort + " · " + svc.profile().baud + " · " + (svc.profile().audio == com.macdmr.radio.DeviceProfile.Audio.TINY_SERVICE ? Txt.s(R.string.device_status_audio_vendor) : svc.profile().audio == com.macdmr.radio.DeviceProfile.Audio.CAMCORDER ? Txt.s(R.string.device_status_audio_camcorder) : svc.profile().audio == com.macdmr.radio.DeviceProfile.Audio.EXTMODULE ? Txt.s(R.string.device_status_audio_ext) : Txt.s(R.string.device_status_audio_uart)) + ("sa818".equals(svc.profile().protocol) ? " · " + Txt.s(R.string.device_status_analog_at) : ""));
        com.macdmr.radio.ProfileRegistry.Selection ps = com.macdmr.radio.ProfileRegistry.selection();
        if (ps.profile.supported || !ps.advisories.isEmpty()) profileText.append(" · " + ("updated".equals(ps.source) ? Txt.s(R.string.device_status_rev_updated, String.valueOf(ps.profile.revision)) : "r" + ps.profile.revision));
        for (com.macdmr.radio.ProfileRegistry.Advisory a : ps.advisories) if (!a.overridden) { profileText.append(" · " + Txt.s(R.string.device_status_advisory)); break; }
        String l = svc.link();
        if (!svc.profile().verified && svc.profile().supported) profileText.append(" · " + Txt.s(R.string.device_status_untested));
        String fwStock = svc.stockFirmware(), fwNow = svc.moduleVersion().trim();
        firmwareText.setText(fwStock.isEmpty() ? Txt.s(R.string.device_status_module_fw) : com.macdmr.radio.Firmware.line(fwStock, fwNow));
        firmwareText.setVisibility(VISIBLE);
        firmwareText.setTextColor(com.macdmr.radio.Firmware.differs(fwStock, fwNow) ? Neo.AMBER_LIGHT : 0xFF9FB4C8);
        com.macdmr.radio.FirmwareVariantCheck.Finding vf = svc.profile().supported && svc.profile().firmwareCatalogued() ? com.macdmr.radio.FirmwareVariantCheck.finding(c) : null;
        variantText.setVisibility(vf == null ? GONE : VISIBLE);
        if (vf != null) variantText.setText(Txt.s(vf.opened ? R.string.device_status_variant_sent : R.string.device_status_variant_new));
        boolean ready = "ready".equals(l), unsupported = "unsupported".equals(l), bad = "no root".equals(l) || unsupported;   // i18n: link state codes
        int accent = ready ? Neo.GREEN : bad ? Neo.AMBER : "stopped".equals(l) ? Neo.CYAN : Neo.AMBER;
        linkText.setText(ready ? Txt.s(R.string.device_status_ready) : "no root".equals(l) ? Txt.s(R.string.device_status_no_root) : "stopped".equals(l) ? Txt.s(R.string.device_status_stopped) : unsupported ? Txt.s(R.string.device_status_unsupported) : Txt.s(R.string.device_status_starting, l));   // i18n: "no root" and "stopped" are link state codes
        linkText.setTextColor(ready ? Neo.GREEN_LIGHT : accent == Neo.CYAN ? Neo.CYAN_LIGHT : Neo.AMBER_LIGHT);
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable(); dot.setShape(android.graphics.drawable.GradientDrawable.OVAL); dot.setColor(accent);
        linkDot.setBackground(dot);
        hero.setBackground(Neo.tint(c, accent, 18, 1));
        heroIcon.setImageDrawable(new Neo.IconDrawable(unsupported ? "report" : "module", accent));
        android.graphics.drawable.GradientDrawable ib = new android.graphics.drawable.GradientDrawable(); ib.setColor(Neo.alpha(accent, 0x16)); ib.setCornerRadius(16 * d); ib.setStroke((int) d, Neo.alpha(accent, 0x77));
        heroIcon.setBackground(ib);
        takeOver.setVisibility(unsupported ? GONE : VISIBLE); handBack.setVisibility(unsupported ? GONE : VISIBLE); reportNow.setVisibility(unsupported || !svc.profile().verified ? VISIBLE : GONE);
        takeOver.setAlpha(ready ? 0.55f : 1f); handBack.setAlpha(ready ? 1f : 0.55f);
        idText.setText(Txt.s(R.string.device_id_value, String.valueOf(svc.localId())));
        if (rogerValue != null) rogerValue.setText(com.macdmr.radio.RadioService.rogerNames()[svc.rogerStyle()]);
        if (txLimitValue != null) txLimitValue.setText(com.macdmr.radio.TxLimit.name(svc.txLimitSetting()));
        ownAtBoot.setChecked(svc.ownAtBoot()); voiceSw.setChecked(svc.voiceOn()); hwPttSw.setChecked(svc.hwPtt()); scanSqSw.setChecked(svc.scanSoftSq());
        volPttSw.setChecked(c.getSharedPreferences("radio", Context.MODE_PRIVATE).getBoolean("volPtt", false));
        refreshCustomPtt();
        styleSw.setChecked(IntercomView.newLook(c)); animSw.setChecked(IntercomView.animatedPanel(c)); covSw.setChecked(svc.coverage().enabled()); roamSw.setChecked(svc.roaming()); carMicSw.setChecked(svc.carMic()); carCovSw.setChecked(svc.coverageInCar()); wheelSw.setChecked(svc.wheelTalk()); nextTalkValue.setText(com.macdmr.radio.RadioService.nextTalkNames()[svc.nextTalkMode()]);
        wheelMicValue.setText(com.macdmr.radio.RadioService.wheelMicNames()[Math.max(0, Math.min(1, svc.wheelMic()))]); voxValue.setText(com.macdmr.radio.RadioService.voxNames()[Math.max(0, Math.min(3, svc.voxStop()))]); carScreenSw.setChecked(svc.carControlsScreen());
        ((TextView) radioIdTile.findViewWithTag("sub")).setText(svc.radioId().status());
        updateWeb();
    }

    private static final String WEB_ICON = "M12,3 A9,9 0 1,0 12.01,3 Z M3,12 H21 M12,3 C8.5,7 8.5,17 12,21 M12,3 C15.5,7 15.5,17 12,21";
    private Neo.NeonSwitch webSw, webTxSw; private TextView webSub;
    /** The web card: address and PIN while the server runs. */
    private void updateWeb() {
        if (svc == null || svc.web() == null || webSw == null) return;
        com.macdmr.radio.web.WebHost w = svc.web();
        webSw.setChecked(w.enabled()); webTxSw.setChecked(w.allowTx());
        if (!w.enabled()) { webSub.setText(Txt.s(R.string.device_web_access_sub)); return; }
        if (!w.running()) { webSub.setText(w.lastError() != null ? Txt.s(R.string.device_web_not_running_err, w.lastError()) : Txt.s(R.string.device_web_not_running)); return; }
        java.util.List<String> u = w.urls();
        webSub.setText((u.isEmpty() ? Txt.s(R.string.device_web_no_address) : Txt.s(R.string.device_web_open, android.text.TextUtils.join(Txt.s(R.string.device_web_url_or), u))) + Txt.s(R.string.device_web_pin, w.pin())
                + (w.tlsRunning() && !u.isEmpty() ? Txt.s(R.string.device_web_tls, w.secureUrls().get(0), com.macdmr.radio.web.WebTls.fingerprint().substring(0, 11)) : "")
                + (w.clients() > 0 ? Txt.p(R.plurals.device_web_clients, w.clients(), w.clients()) : ""));
    }

    @Override public void onLink(String state) { refresh(); }
    @Override public void onChannel(ChannelStore.Channel ch) { } @Override public void onRx(boolean r) { } @Override public void onTx(boolean t) { } @Override public void onRssi(int dbm) { }
    @Override public void onAudioLevel(int a) { } @Override public void onHistory(String l) { } @Override public void onCaller(String t) { } @Override public void onLog(String s) { }
    @Override public void onLocation(String t) { } @Override public void onSms(int f, int ty, String te) { } @Override public void onSmsResult(boolean ok) { }
    @Override public void onModes(boolean a, int b, boolean c2, boolean d2, boolean e, boolean f2, boolean g, String z) { }
    @Override public void onCaption(boolean en, String live, String sentence) { }
}
