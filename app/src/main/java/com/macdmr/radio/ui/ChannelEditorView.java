/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Channel editor with the OEM's field set (name, type, frequencies in MHz, position, power, band, relay; colour
 * code, mode, slot, contact type, call number, RX group list, encryption, interrupt; squelch and tones), grouped
 * into sections in the new look. Every field has an info button with what it does on this radio. Enum rows cycle
 * on tap; text rows edit in place.
 */
public class ChannelEditorView extends LinearLayout {
    public interface Done { void onDone(ChannelStore.Channel ch, boolean saved); }

    private final ChannelStore.Channel ch;
    private final boolean isNew;
    private final Done done;
    private final LinearLayout form;
    private EditText name, tx, rx, call, key, groups, notes;
    private EditText lat, lon;
    private TextView txCodeV, rxCodeV, callPickV;
    private View callTypedRow, callPickRow;
    private boolean callFromContacts;
    private EditText m17Dest;
    private LinearLayout digitalGroup, analogGroup, m17Group;

    private static String[] toneTypes() { return new String[] { Txt.s(R.string.channel_editor_tone_none), "CTCSS", "DCS", Txt.s(R.string.channel_editor_tone_dcs_inverted) }; }
    /** The field help texts, for SignalDeck Web's channel editor: {key, text}. */
    public static String[][] helpForWeb() {
        return new String[][] { { "name", H_NAME() }, { "notes", H_NOTES() }, { "type", H_TYPE() }, { "tx", H_TX() }, { "rx", H_RX() }, { "pos", H_POS() }, { "power", H_POWER() }, { "rxOnly", H_RXONLY() }, { "band", H_BAND() },
                { "relay", H_RELAY() }, { "cc", H_CC() }, { "mode", H_MODE() }, { "slot", H_SLOT() }, { "contactType", H_CTYPE() }, { "call", H_CALL() }, { "groups", H_GROUPS() },
                { "encrypt", H_ENC() }, { "key", H_KEY() }, { "interrupt", H_INT() }, { "sq", H_SQ() }, { "softSq", H_SOFTSQ() }, { "softSqLevel", H_SOFTSQ_LVL() },
                { "txType", H_TXT() }, { "txTone", H_TXC() }, { "rxType", H_RXT() }, { "rxTone", H_RXC() }, { "m17dest", H_M17() }, { "can", H_CAN() } };
    }

    // ---- field help (what each setting does on this radio) ----
    private static String H_NOTES() { return Txt.s(R.string.channel_editor_h_notes); }
    private static String H_NAME() { return Txt.s(R.string.channel_editor_h_name, ChannelStore.NAME_MAX); }
    private static String H_TYPE() { return Txt.s(R.string.channel_editor_h_type); }
    private static String H_M17() { return Txt.s(R.string.channel_editor_h_m17); }
    private static String H_CAN() { return Txt.s(R.string.channel_editor_h_can); }
    private static String H_TX() { return Txt.s(R.string.channel_editor_h_tx, com.macdmr.radio.DeviceProfile.detect().bandsText()); }
    private static String H_RX() { return Txt.s(R.string.channel_editor_h_rx, com.macdmr.radio.DeviceProfile.detect().bandsText()); }
    private static String H_POS() { return Txt.s(R.string.channel_editor_h_pos); }
    private static String H_RXONLY() { return Txt.s(R.string.channel_editor_h_rxonly); }
    private static String H_POWER() { return Txt.s(R.string.channel_editor_h_power); }
    private static String H_BAND() { return Txt.s(R.string.channel_editor_h_band); }
    private static String H_RELAY() { return Txt.s(R.string.channel_editor_h_relay); }
    private static String H_CC() { return Txt.s(R.string.channel_editor_h_cc); }
    private static String H_MODE() { return Txt.s(R.string.channel_editor_h_mode); }
    private static String H_NOSLOT() { return Txt.s(R.string.channel_editor_h_noslot); }
    private static String H_SLOT() { return Txt.s(R.string.channel_editor_h_slot); }
    private static String H_CTYPE() { return Txt.s(R.string.channel_editor_h_ctype); }
    private static String H_CALLFROM() { return Txt.s(R.string.channel_editor_h_callfrom); }
    private static String H_CALLPICK() { return Txt.s(R.string.channel_editor_h_callpick); }
    private static String H_CALL() { return Txt.s(R.string.channel_editor_h_call); }
    private static String H_GROUPS() { return Txt.s(R.string.channel_editor_h_groups); }
    private static String H_ENC() { return Txt.s(R.string.channel_editor_h_enc); }
    private static String H_KEY() { return Txt.s(R.string.channel_editor_h_key); }
    private static String H_INT() { return Txt.s(R.string.channel_editor_h_int); }
    private static String H_SQ() { return Txt.s(R.string.channel_editor_h_sq); }
    private static String H_SOFTSQ() { return Txt.s(R.string.channel_editor_h_softsq); }
    private static String H_SOFTSQ_LVL() { return Txt.s(R.string.channel_editor_h_softsq_lvl); }
    private static String H_MAPS() { return Txt.s(R.string.channel_editor_h_maps); }
    private static String H_TXT() { return Txt.s(R.string.channel_editor_h_txt); }
    private static String H_TXC() { return Txt.s(R.string.channel_editor_h_txc); }
    private static String H_RXT() { return Txt.s(R.string.channel_editor_h_rxt); }
    private static String H_RXC() { return Txt.s(R.string.channel_editor_h_rxc); }

    public ChannelEditorView(Context c, ChannelStore.Channel channel, boolean isNew, Done done) {
        super(c);
        this.ch = channel; this.isNew = isNew; this.done = done;
        if (isNew && !com.macdmr.radio.DeviceProfile.detect().dmr()) ch.type = ChannelStore.Channel.TYPE_FM;   // an analog-only radio: a new channel is analog (the type row is hidden)
        setOrientation(VERTICAL); setBackground(Neo.page(c));   // overlay: covers the channel list
        setClickable(true);
        float d = Theme.dp(c);

        LinearLayout bar = Neo.header(c, Txt.s(isNew ? R.string.channel_editor_title_new : R.string.channel_editor_title_edit), isNew ? Txt.s(R.string.channel_editor_subtitle_new) : Txt.s(R.string.channel_editor_subtitle_number, ch.number));
        ImageView back = Neo.iconButton(c, "left", Neo.CYAN, Txt.s(R.string.channel_editor_cd_back)); back.setOnClickListener(v -> done.onDone(ch, false));
        LayoutParams bl = new LayoutParams((int) (44 * d), (int) (44 * d)); bl.rightMargin = (int) (12 * d);
        bar.addView(back, 0, bl);
        TextView save = Neo.button(c, Txt.s(R.string.common_save), "check", Neo.GREEN); save.setMinHeight((int) (44 * d));
        save.setOnClickListener(v -> { if (apply()) done.onDone(ch, true); });
        LayoutParams sl = new LayoutParams(LayoutParams.WRAP_CONTENT, (int) (44 * d)); sl.leftMargin = (int) (8 * d);
        ((LinearLayout) bar.findViewWithTag("actions")).addView(save, sl);
        addView(bar);

        ScrollView sv = new ScrollView(c); sv.setVerticalScrollBarEnabled(false);
        form = new LinearLayout(c); form.setOrientation(VERTICAL); form.setPadding((int) (16 * d), (int) (4 * d), (int) (16 * d), (int) (28 * d));
        sv.addView(form);
        addView(sv, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        build();
    }

    private void build() {
        Context c = getContext(); float d = Theme.dp(c);
        section(form, Txt.s(R.string.channel_editor_sec_channel));
        name = textInto(form, Txt.s(R.string.channel_editor_l_name), H_NAME(), ch.name, InputType.TYPE_CLASS_TEXT);
        nameCounter(name);
        if (com.macdmr.radio.DeviceProfile.detect().dmr() || ch.type != com.macdmr.radio.ChannelStore.Channel.TYPE_FM)   // an analog-only radio: every channel is analog (an old DMR channel can still be changed)
            choiceInto(form, Txt.s(R.string.channel_editor_l_type), H_TYPE(), new String[] { Txt.s(R.string.channel_editor_o_digital), Txt.s(R.string.channel_editor_o_analog), "M17" }, ch.type, v -> { ch.type = v; refreshGroups(); });
        // receive first: repeater lists give the output (what you hear) and the offset
        rx = textInto(form, Txt.s(R.string.channel_editor_l_rx), H_RX(), ch.rxFreq > 0 ? String.format(Locale.US, "%.5f", ch.rxFreq / 1e6) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        tx = textInto(form, Txt.s(R.string.channel_editor_l_tx), H_TX(), ch.txFreq > 0 ? String.format(Locale.US, "%.5f", ch.txFreq / 1e6) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(VfoDialog.bandPlanLine(getContext(), rx, tx));

        section(form, Txt.s(R.string.channel_editor_sec_radio));
        choiceInto(form, Txt.s(R.string.channel_editor_l_power), H_POWER(), new String[] { Txt.s(R.string.channel_editor_o_low_power), Txt.s(R.string.channel_editor_o_high_power) }, ch.power, v -> ch.power = v);
        choiceInto(form, Txt.s(R.string.channel_editor_l_transmit), H_RXONLY(), new String[] { Txt.s(R.string.channel_editor_o_allowed), Txt.s(R.string.channel_editor_o_rx_only) }, ch.rxOnly ? 1 : 0, v -> ch.rxOnly = v == 1);
        if (com.macdmr.radio.DeviceProfile.detect().dmr()) choiceInto(form, Txt.s(R.string.channel_editor_l_relay), H_RELAY(), new String[] { Txt.s(R.string.channel_editor_o_disconnect), Txt.s(R.string.channel_editor_o_normal) }, ch.relay == 1 ? 0 : 1, v -> ch.relay = v == 0 ? 1 : 2);

        digitalGroup = new LinearLayout(c); digitalGroup.setOrientation(VERTICAL); form.addView(digitalGroup);
        section(digitalGroup, "DMR");   // i18n: mode name
        choiceInto(digitalGroup, Txt.s(R.string.channel_editor_l_cc), H_CC(), names(0, 15), ch.cc, v -> ch.cc = v);
        if (com.macdmr.radio.DeviceProfile.detect().timeSlots) {
            choiceInto(digitalGroup, Txt.s(R.string.channel_editor_l_mode), H_MODE(), new String[] { Txt.s(R.string.channel_editor_o_direct), Txt.s(R.string.channel_editor_o_double_slot) }, ch.mode == 4 ? 1 : 0, v -> ch.mode = v == 1 ? 4 : 0);
            choiceInto(digitalGroup, Txt.s(R.string.channel_editor_l_slot), H_SLOT(), new String[] { "TS1", "TS2" }, ch.inSlot, v -> { ch.inSlot = v; ch.outSlot = v; });
        } else {                                                     // the Armor 3WT: its module's channel has no slot field
            TextView noSlot = Neo.text(c, H_NOSLOT(), Neo.DIM, 13, Neo.uiMedium(c)); noSlot.setPadding((int) (4 * d), (int) (6 * d), (int) (4 * d), (int) (6 * d));
            digitalGroup.addView(noSlot);
        }
        choiceInto(digitalGroup, Txt.s(R.string.channel_editor_l_ctype), H_CTYPE(), new String[] { Txt.s(R.string.channel_editor_o_private), Txt.s(R.string.channel_editor_o_group), Txt.s(R.string.channel_editor_o_all) }, ch.contactType, v -> { ch.contactType = v; showCallSource(); });
        callFromContacts = contactFor(ch.txContact, ch.contactType) != null;
        choiceInto(digitalGroup, Txt.s(R.string.channel_editor_l_callfrom), H_CALLFROM(), new String[] { Txt.s(R.string.channel_editor_o_typed), Txt.s(R.string.channel_editor_o_contacts) }, callFromContacts ? 1 : 0, v -> { callFromContacts = v == 1; showCallSource(); });
        call = textInto(digitalGroup, Txt.s(R.string.channel_editor_l_call), H_CALL(), ch.txContact > 0 ? String.valueOf(ch.txContact) : "", InputType.TYPE_CLASS_NUMBER);
        callTypedRow = (View) call.getParent().getParent();
        callPickV = choiceInto(digitalGroup, Txt.s(R.string.channel_editor_l_callpick), H_CALLPICK(), null, 0, null);
        callPickRow = (View) callPickV.getParent();
        callPickV.setOnClickListener(v -> pickCallContact()); callPickRow.setOnClickListener(v -> pickCallContact());
        showCallSource();
        groups = textInto(digitalGroup, Txt.s(R.string.channel_editor_l_groups), H_GROUPS(), join(ch.groups), InputType.TYPE_CLASS_TEXT);
        TextView lists = Neo.button(c, Txt.s(R.string.channel_editor_tg_lists_button), "channels", Neo.VIOLET); lists.setTextSize(14);
        LayoutParams tll = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); tll.bottomMargin = (int) (6 * Theme.dp(c));
        digitalGroup.addView(lists, tll);
        lists.setOnClickListener(v -> tgListsMenu());
        choiceInto(digitalGroup, Txt.s(R.string.channel_editor_l_enc), H_ENC(), new String[] { Txt.s(R.string.channel_editor_o_disabled), Txt.s(R.string.channel_editor_o_enabled) }, ch.encryptSw == 1 ? 1 : 0, v -> ch.encryptSw = v == 1 ? 1 : 2);
        key = textInto(digitalGroup, Txt.s(R.string.channel_editor_l_key), H_KEY(), ch.encryptKey == null ? "" : ch.encryptKey, InputType.TYPE_CLASS_TEXT);
        choiceInto(digitalGroup, Txt.s(R.string.channel_editor_l_int), H_INT(), new String[] { Txt.s(R.string.channel_editor_o_int_off), Txt.s(R.string.channel_editor_o_int_on) }, ch.interrupt == 1 ? 1 : 0, v -> ch.interrupt = v == 1 ? 1 : 2);

        analogGroup = new LinearLayout(c); analogGroup.setOrientation(VERTICAL); form.addView(analogGroup);
        section(analogGroup, Txt.s(R.string.channel_editor_sec_analog));
        choiceInto(analogGroup, Txt.s(R.string.channel_editor_l_band), H_BAND(), new String[] { Txt.s(R.string.channel_editor_o_narrow), Txt.s(R.string.channel_editor_o_wide) }, ch.band == 0 ? 0 : 1, v -> ch.band = v);
        choiceInto(analogGroup, Txt.s(R.string.channel_editor_l_softsq), H_SOFTSQ(), new String[] { Txt.s(R.string.common_off), Txt.s(R.string.common_on) }, ch.softSq ? 1 : 0, v -> ch.softSq = v == 1);
        choiceInto(analogGroup, Txt.s(R.string.channel_editor_l_softsq_level), H_SOFTSQ_LVL(), names(1, 9), Math.max(1, Math.min(9, ch.softSqLevel)) - 1, v -> ch.softSqLevel = v + 1);
        choiceInto(analogGroup, Txt.s(R.string.channel_editor_l_sq), H_SQ(), names(0, 9), ch.sq, v -> ch.sq = v);
        choiceInto(analogGroup, Txt.s(R.string.channel_editor_l_tx_tone_type), H_TXT(), toneTypes(), ch.txType, v -> { ch.txType = v; ch.txSubCode = 0; txCodeV.setText(toneName(ch.txType, ch.txSubCode)); });
        txCodeV = choiceInto(analogGroup, Txt.s(R.string.channel_editor_l_tx_tone), H_TXC(), null, 0, null);
        txCodeV.setOnClickListener(v -> pickTone(true)); ((View) txCodeV.getParent()).setOnClickListener(v -> pickTone(true));
        choiceInto(analogGroup, Txt.s(R.string.channel_editor_l_rx_tone_type), H_RXT(), toneTypes(), ch.rxType, v -> { ch.rxType = v; ch.rxSubCode = 0; rxCodeV.setText(toneName(ch.rxType, ch.rxSubCode)); });
        rxCodeV = choiceInto(analogGroup, Txt.s(R.string.channel_editor_l_rx_tone), H_RXC(), null, 0, null);
        rxCodeV.setOnClickListener(v -> pickTone(false)); ((View) rxCodeV.getParent()).setOnClickListener(v -> pickTone(false));
        txCodeV.setText(toneName(ch.txType, ch.txSubCode)); rxCodeV.setText(toneName(ch.rxType, ch.rxSubCode));

        m17Group = new LinearLayout(c); m17Group.setOrientation(VERTICAL); form.addView(m17Group);
        section(m17Group, "M17");   // i18n: mode name
        m17Dest = textInto(m17Group, Txt.s(R.string.channel_editor_l_m17dest), H_M17(), ch.m17Dest(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        TextView pick = Neo.button(c, Txt.s(R.string.channel_editor_pick_contacts), "contacts", Neo.VIOLET); pick.setTextSize(14);
        LayoutParams mpl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); mpl.bottomMargin = (int) (6 * Theme.dp(c));
        m17Group.addView(pick, mpl);
        pick.setOnClickListener(v -> pickM17Dest());
        choiceInto(m17Group, Txt.s(R.string.channel_editor_l_can), H_CAN(), names(0, 15), ch.can(), v -> ch.cc = v);

        section(form, Txt.s(R.string.channel_editor_sec_location));
        lat = textInto(form, Txt.s(R.string.channel_editor_l_lat), H_POS() + "\n\n" + H_MAPS(), ch.lat != 0 || ch.lon != 0 ? String.format(Locale.US, "%.5f", ch.lat) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        lon = textInto(form, Txt.s(R.string.channel_editor_l_lon), H_POS(), ch.lat != 0 || ch.lon != 0 ? String.format(Locale.US, "%.5f", ch.lon) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        LinearLayout locRow = new LinearLayout(c); locRow.setBaselineAligned(false);
        TextView here = Neo.button(c, Txt.s(R.string.channel_editor_my_position), "radio", Neo.CYAN), maps = Neo.button(c, Txt.s(R.string.channel_editor_open_maps), "globe", Neo.GREEN), paste = Neo.button(c, Txt.s(R.string.channel_editor_paste), "import", Neo.AMBER);
        for (TextView b : new TextView[] { here, maps, paste }) { b.setTextSize(13.5f); b.setPadding((int) (6 * d), (int) (8 * d), (int) (6 * d), (int) (8 * d)); b.setCompoundDrawablePadding((int) (4 * d)); b.setSingleLine(true); }
        LayoutParams b1 = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1.15f); b1.rightMargin = (int) (3 * d);
        LayoutParams b2 = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1.15f); b2.leftMargin = (int) (3 * d); b2.rightMargin = (int) (3 * d);
        LayoutParams b3 = new LayoutParams(0, LayoutParams.MATCH_PARENT, 0.9f); b3.leftMargin = (int) (3 * d);
        locRow.addView(here, b1); locRow.addView(maps, b2); locRow.addView(paste, b3);
        maps.setOnClickListener(v -> openMaps());
        paste.setOnClickListener(v -> pasteLocation());
        here.setOnClickListener(v -> {
            android.location.Location me = null;
            try { android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE); for (String p : new String[] { "gps", "network", "passive" }) { try { android.location.Location l = lm.getLastKnownLocation(p); if (l != null && (me == null || l.getTime() > me.getTime())) me = l; } catch (SecurityException ignored) { } } } catch (Throwable ignored) { }
            if (me == null) { android.widget.Toast.makeText(c, Txt.s(R.string.channel_editor_no_fix), android.widget.Toast.LENGTH_SHORT).show(); return; }
            lat.setText(String.format(Locale.US, "%.5f", me.getLatitude())); lon.setText(String.format(Locale.US, "%.5f", me.getLongitude()));
        });
        LayoutParams hl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); hl.topMargin = (int) (2 * d); form.addView(locRow, hl);
        TextView how = Neo.text(c, Txt.s(R.string.channel_editor_location_how), Neo.DIM, 13, Neo.uiMedium(c));
        how.setLineSpacing(0, 1.1f); how.setPadding((int) (4 * d), (int) (8 * d), (int) (4 * d), 0); form.addView(how);

        section(form, Txt.s(R.string.channel_editor_sec_notes));
        notes = textInto(form, Txt.s(R.string.channel_editor_l_notes), H_NOTES(), ch.notes == null ? "" : ch.notes, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setSingleLine(false); notes.setMinLines(2); notes.setMaxLines(8);

        section(form, Txt.s(R.string.channel_editor_sec_nets));
        netsBox = new LinearLayout(c); netsBox.setOrientation(VERTICAL); form.addView(netsBox);
        refreshNets();
        refreshGroups();
        section(form, Txt.s(R.string.channel_editor_sec_history));
        historyBox = new LinearLayout(c); historyBox.setOrientation(VERTICAL); form.addView(historyBox);
        refreshHistory();
    }

    /** What was heard and sent on this channel (the same list as the card's History), newest first, grouped by day. */
    private LinearLayout historyBox; private com.macdmr.radio.RadioService svc;
    public void attach(com.macdmr.radio.RadioService s) { svc = s; if (historyBox != null) refreshHistory(); }
    private void refreshHistory() {
        final Context c = getContext(); float d = Theme.dp(c);
        historyBox.removeAllViews();
        if (isNew || svc == null) { TextView t = Neo.text(c, Txt.s(isNew ? R.string.channel_editor_history_new : R.string.channel_editor_history_no_service), Neo.DIM, 13, Neo.uiMedium(c)); t.setPadding((int) (4 * d), (int) (4 * d), (int) (4 * d), 0); historyBox.addView(t); return; }
        try { ChannelHistoryDialog.fill(c, svc, ch, historyBox, 100, this::refreshHistory); } catch (Throwable t) { historyBox.addView(Neo.text(c, Txt.s(R.string.channel_editor_history_failed, String.valueOf(t.getMessage())), Neo.DIM, 13, Neo.uiMedium(c))); }
    }

    /** Nets the owner added to this channel: tap one to edit or delete it, Add a net for another. */
    private LinearLayout netsBox;
    private void refreshNets() {
        final Context c = getContext(); float d = Theme.dp(c);
        netsBox.removeAllViews();
        if (isNew) { TextView t = Neo.text(c, Txt.s(R.string.channel_editor_nets_new), Neo.DIM, 13, Neo.uiMedium(c)); t.setPadding((int) (4 * d), (int) (4 * d), (int) (4 * d), 0); netsBox.addView(t); return; }
        for (final com.macdmr.radio.ham.NetFinder.Manual m : com.macdmr.radio.NetWatch.manualOn(c, ch.id)) {
            LinearLayout r = new LinearLayout(c); r.setOrientation(VERTICAL); r.setBackground(Neo.rowCard(c)); r.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
            r.addView(Neo.text(c, m.name, Neo.AMBER_LIGHT, 15.5f, Neo.ui(c)));
            r.addView(Neo.text(c, m.notes.isEmpty() ? Txt.s(R.string.channel_editor_net_line, com.macdmr.radio.ham.NetFinder.when(m), m.durationMin)
                    : Txt.s(R.string.channel_editor_net_line_notes, com.macdmr.radio.ham.NetFinder.when(m), m.durationMin, m.notes), Neo.MUTED, 12.5f, Neo.uiMedium(c)));
            r.setOnClickListener(v -> new android.app.AlertDialog.Builder(c).setTitle(m.name).setItems(new String[] { Txt.s(R.string.common_edit), Txt.s(R.string.common_delete) }, (dd, w) -> {
                if (w == 0) NetsDialog.editManual(c, null, m, this::refreshNets);
                else { com.macdmr.radio.NetWatch.deleteManual(c, m.id); refreshNets(); }
            }).show());
            LayoutParams rl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); rl.topMargin = (int) (6 * d);
            netsBox.addView(r, rl);
        }
        TextView add = Neo.button(c, Txt.s(R.string.channel_editor_add_net), "plus", Neo.AMBER);
        LayoutParams al = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); al.topMargin = (int) (8 * d);
        netsBox.addView(add, al);
        add.setOnClickListener(v -> { com.macdmr.radio.ham.NetFinder.Manual m = new com.macdmr.radio.ham.NetFinder.Manual(); m.channel = ch.number; m.channelId = ch.id; NetsDialog.editManual(c, null, m, this::refreshNets); });
        TextView how = Neo.text(c, Txt.s(R.string.channel_editor_nets_how), Neo.DIM, 13, Neo.uiMedium(c));
        how.setPadding((int) (4 * d), (int) (8 * d), (int) (4 * d), 0); netsBox.addView(how);
    }

    /** A position chosen elsewhere (shared from a maps app): fill the fields; Save keeps it. */
    public void setLocation(double la, double lo) {
        lat.setText(String.format(Locale.US, "%.5f", la)); lon.setText(String.format(Locale.US, "%.5f", lo));
        android.widget.Toast.makeText(getContext(), Txt.s(R.string.channel_editor_location_filled), android.widget.Toast.LENGTH_LONG).show();
    }

    private void openMaps() {
        Context c = getContext();
        double la = 0, lo = 0;
        try { la = Double.parseDouble(lat.getText().toString().trim()); lo = Double.parseDouble(lon.getText().toString().trim()); } catch (Throwable ignored) { }
        String label = android.net.Uri.encode(ch.name == null || ch.name.isEmpty() ? Txt.s(R.string.channel_editor_maps_label) : ch.name);
        android.net.Uri u = la != 0 || lo != 0 ? android.net.Uri.parse("geo:" + la + "," + lo + "?q=" + la + "," + lo + "(" + label + ")") : android.net.Uri.parse("geo:0,0?q=");
        try { c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, u)); android.widget.Toast.makeText(c, Txt.s(R.string.channel_editor_maps_how), android.widget.Toast.LENGTH_LONG).show(); }
        catch (Throwable t) { android.widget.Toast.makeText(c, Txt.s(R.string.channel_editor_no_maps), android.widget.Toast.LENGTH_LONG).show(); }
    }

    private void pasteLocation() {
        final Context c = getContext();
        android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence clip = null;
        try { if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) clip = cm.getPrimaryClip().getItemAt(0).coerceToText(c); } catch (Throwable ignored) { }
        if (clip == null || clip.length() == 0) { android.widget.Toast.makeText(c, Txt.s(R.string.channel_editor_clipboard_empty), android.widget.Toast.LENGTH_LONG).show(); return; }
        final String text = clip.toString();
        new Thread(() -> {
            final double[] p = com.macdmr.radio.GeoLink.resolve(text);
            post(() -> { if (p == null) android.widget.Toast.makeText(c, Txt.s(R.string.channel_editor_clipboard_no_coords), android.widget.Toast.LENGTH_LONG).show(); else setLocation(p[0], p[1]); });
        }).start();
    }

    private List<Integer> groupsInField() {
        List<Integer> g = new ArrayList<>();
        for (String s : groups.getText().toString().split("[,; ]+")) { s = s.trim(); if (!s.isEmpty()) try { int v = Integer.parseInt(s); if (v > 0 && !g.contains(v)) g.add(v); } catch (NumberFormatException ignored) { } }
        return g;
    }

    /** Named talkgroup lists: insert one into the RX group list, or save the current list under a name. */
    private void tgListsMenu() {
        final Context c = getContext();
        if (!(c instanceof com.macdmr.radio.MainActivity) || ((com.macdmr.radio.MainActivity) c).service() == null) return;
        final com.macdmr.radio.ChannelStore store = ((com.macdmr.radio.MainActivity) c).service().store();
        final List<com.macdmr.radio.ChannelStore.TgList> all = store.tgLists();
        String[] items = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) items[i] = all.get(i).name + "  (" + all.get(i).tgs.length + ")";
        items[all.size()] = Txt.s(R.string.channel_editor_tg_save_item);
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.channel_editor_tg_lists_title)).setItems(items, (d, w) -> {
            if (w < all.size()) {
                List<Integer> g = groupsInField(); int added = 0;
                for (int t : all.get(w).tgs) if (!g.contains(t) && g.size() < 32) { g.add(t); added++; }
                StringBuilder b = new StringBuilder(); for (int t : g) { if (b.length() > 0) b.append(", "); b.append(t); }
                groups.setText(b.toString());
                android.widget.Toast.makeText(c, Txt.p(g.size() >= 32 ? R.plurals.channel_editor_tg_added_full : R.plurals.channel_editor_tg_added, added, added), android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            final EditText nm = new EditText(c); nm.setHint(Txt.s(R.string.channel_editor_tg_list_name));
            new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.channel_editor_tg_save_title)).setView(nm).setPositiveButton(Txt.s(R.string.common_save), (d2, w2) -> {
                List<Integer> g = groupsInField(); if (g.isEmpty() || nm.getText().toString().trim().isEmpty()) return;
                com.macdmr.radio.ChannelStore.TgList t = new com.macdmr.radio.ChannelStore.TgList(); t.name = nm.getText().toString().trim();
                t.tgs = new int[g.size()]; for (int i = 0; i < g.size(); i++) t.tgs[i] = g.get(i);
                store.saveTgList(t);
                android.widget.Toast.makeText(c, Txt.s(R.string.channel_editor_tg_saved, t.name), android.widget.Toast.LENGTH_SHORT).show();
            }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        }).show();
    }

    /** The contact of this type (private for a private call, group otherwise) with this number, or null. */
    private com.macdmr.radio.ChannelStore.Contact contactFor(int number, int contactType) {
        if (number <= 0) return null;
        for (com.macdmr.radio.ChannelStore.Contact ct : callContacts(contactType)) if (ct.number == number) return ct;
        return null;
    }
    private java.util.List<com.macdmr.radio.ChannelStore.Contact> callContacts(int contactType) {
        java.util.List<com.macdmr.radio.ChannelStore.Contact> out = new java.util.ArrayList<>();
        Context c = getContext();
        if (!(c instanceof com.macdmr.radio.MainActivity) || ((com.macdmr.radio.MainActivity) c).service() == null) return out;
        int want = contactType == 0 ? 0 : 1;
        for (com.macdmr.radio.ChannelStore.Contact ct : ((com.macdmr.radio.MainActivity) c).service().store().contacts()) if (ct.type == want && ct.number > 0) out.add(ct);
        return out;
    }
    private int typedCall() { try { return Integer.parseInt(call.getText().toString().trim()); } catch (Exception e) { return 0; } }
    /** Show the number field or the contact row, and what the contact row holds. */
    private void showCallSource() {
        if (callTypedRow == null || callPickRow == null) return;
        callTypedRow.setVisibility(callFromContacts ? GONE : VISIBLE);
        callPickRow.setVisibility(callFromContacts ? VISIBLE : GONE);
        com.macdmr.radio.ChannelStore.Contact ct = contactFor(typedCall(), ch.contactType);
        int n = typedCall();
        callPickV.setText(ct != null ? ct.name + " · " + ct.number : n > 0 ? Txt.s(R.string.channel_editor_call_not_contact, n) : Txt.s(R.string.channel_editor_choose));
    }
    private void pickCallContact() {
        final java.util.List<com.macdmr.radio.ChannelStore.Contact> all = callContacts(ch.contactType);
        boolean priv = ch.contactType == 0;
        if (all.isEmpty()) {
            new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(priv ? R.string.channel_editor_no_private : R.string.channel_editor_no_group))
                    .setMessage(Txt.s(priv ? R.string.channel_editor_no_private_msg : R.string.channel_editor_no_group_msg))
                    .setPositiveButton(Txt.s(R.string.common_ok), null).show();
            return;
        }
        String[] items = new String[all.size()];
        for (int i = 0; i < items.length; i++) items[i] = all.get(i).name + "  ·  " + all.get(i).number;
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(ch.contactType == 0 ? R.string.channel_editor_private_contacts : R.string.channel_editor_group_contacts))
                .setItems(items, (d, w) -> { call.setText(String.valueOf(all.get(w).number)); showCallSource(); }).show();
    }

    private void pickM17Dest() {
        final Context c = getContext();
        if (!(c instanceof com.macdmr.radio.MainActivity) || ((com.macdmr.radio.MainActivity) c).service() == null) return;
        final java.util.List<com.macdmr.radio.ChannelStore.Contact> all = ((com.macdmr.radio.MainActivity) c).service().store().contacts();
        final java.util.List<String> calls = new java.util.ArrayList<>();
        calls.add("@ALL");   // i18n: M17 protocol word
        for (com.macdmr.radio.ChannelStore.Contact ct : all) {
            String call = ct.baseCall();
            if (!call.isEmpty() && !calls.contains(call)) calls.add(call);
        }
        String[] items = calls.toArray(new String[0]);
        new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.channel_editor_m17_dest_title)).setItems(items, (d, w) -> m17Dest.setText(items[w])).show();
    }

    private void refreshGroups() {
        digitalGroup.setVisibility(ch.isDigital() ? VISIBLE : GONE);
        analogGroup.setVisibility(ch.type == com.macdmr.radio.ChannelStore.Channel.TYPE_FM ? VISIBLE : GONE);
        if (m17Group != null) m17Group.setVisibility(ch.isM17() ? VISIBLE : GONE);
    }

    private static String[] names(int from, int to) { String[] s = new String[to - from + 1]; for (int i = from; i <= to; i++) s[i - from] = String.valueOf(i); return s; }
    private static String join(int[] a) { StringBuilder b = new StringBuilder(); for (int x : a) { if (b.length() > 0) b.append(", "); b.append(x); } return b.toString(); }
    private static String toneName(int type, int code) {
        if (type == 1) return com.macdmr.radio.dsp.ToneTable.ctcssHz(code) > 0 ? com.macdmr.radio.dsp.ToneTable.ctcssLabel(code) : "CTCSS #" + code;   // i18n: protocol word
        if (type == 2 || type == 3) { String l = com.macdmr.radio.dsp.ToneTable.toneLabel(type, code); return l.equals("—") ? "DCS #" + code : "DCS " + l; }   // i18n: protocol word
        return "—";
    }

    private void pickTone(final boolean txSide) {
        int type = txSide ? ch.txType : ch.rxType;
        if (type == 0) { android.widget.Toast.makeText(getContext(), Txt.s(R.string.channel_editor_tone_type_first), android.widget.Toast.LENGTH_SHORT).show(); return; }
        final String[] items; final int n = type == 1 ? com.macdmr.radio.dsp.ToneTable.CTCSS.length : com.macdmr.radio.dsp.ToneTable.DCS_COUNT;
        items = new String[n]; for (int i = 0; i < n; i++) items[i] = toneName(type, i);
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(txSide ? R.string.channel_editor_l_tx_tone : R.string.channel_editor_l_rx_tone)).setItems(items, (d, w) -> {
            if (txSide) { ch.txSubCode = w; txCodeV.setText(items[w]); } else { ch.rxSubCode = w; rxCodeV.setText(items[w]); }
        }).show();
    }

    private interface IntSetter { void set(int v); }

    private void section(LinearLayout target, String title) {
        TextView t = Neo.caps(getContext(), title); float d = Theme.dp(getContext());
        t.setPadding((int) (2 * d), (int) (18 * d), 0, (int) (8 * d));
        target.addView(t);
    }

    /** Round "i" button that explains the field in a small card. */
    private ImageView infoButton(final String title, final String help) {
        Context c = getContext(); float d = Theme.dp(c);
        ImageView i = new ImageView(c); i.setImageDrawable(new Neo.IconDrawable("info", Neo.MUTED)); i.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = (int) (11 * d); i.setPadding(p, p, p, p); i.setContentDescription(Txt.s(R.string.channel_editor_cd_about, title)); i.setClickable(true); i.setFocusable(true);
        i.setOnClickListener(v -> showInfo(title, help));
        return i;
    }

    private void showInfo(String title, String help) {
        Context c = getContext(); float d = Theme.dp(c);
        final android.app.Dialog dlg = new android.app.Dialog(c);
        LinearLayout box = new LinearLayout(c); box.setOrientation(VERTICAL);
        box.setBackground(Neo.sheet(c, Neo.alpha(Neo.CYAN, 0x99), 18)); box.setPadding((int) (18 * d), (int) (16 * d), (int) (18 * d), (int) (14 * d));
        LinearLayout top = new LinearLayout(c); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Neo.iconTile(c, "info", Neo.CYAN, 36), new LayoutParams((int) (36 * d), (int) (36 * d)));
        TextView t = Neo.text(c, title.replaceAll("\\s*\\(.*\\)$", ""), Neo.TEXT, 18, Neo.ui(c)); t.setPadding((int) (12 * d), 0, 0, 0);
        top.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        box.addView(top);
        TextView b = Neo.text(c, help, Neo.TEXT_SOFT, 15, Neo.uiMedium(c)); b.setLineSpacing(0, 1.18f); b.setPadding(0, (int) (12 * d), 0, 0);
        box.addView(b);
        TextView ok = Neo.button(c, Txt.s(R.string.channel_editor_got_it), null, Neo.CYAN);
        LayoutParams ol = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ol.topMargin = (int) (16 * d);
        box.addView(ok, ol);
        ok.setOnClickListener(v -> dlg.dismiss());
        dlg.setContentView(box);
        if (dlg.getWindow() != null) { dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); dlg.getWindow().setLayout((int) (Math.min(c.getResources().getDisplayMetrics().widthPixels - 32 * d, 420 * d)), LayoutParams.WRAP_CONTENT); }
        dlg.show();
    }

    /** The name field stops at {@link ChannelStore#NAME_MAX} characters and shows how many are used. */
    private void nameCounter(EditText e) {
        Context c = getContext();
        e.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(ChannelStore.NAME_MAX) });
        if (e.length() > ChannelStore.NAME_MAX) e.setText(ChannelStore.fitName(e.getText().toString()));   // an imported longer name
        TextView count = Neo.text(c, "", Neo.MUTED, 12, Neo.uiSemi(c));
        count.setGravity(Gravity.END);
        Runnable show = () -> { int n = e.length(); count.setText(n + " / " + ChannelStore.NAME_MAX); count.setTextColor(n >= ChannelStore.NAME_MAX ? Neo.AMBER : Neo.MUTED); };
        show.run();
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable s) { show.run(); }
        });
        ((LinearLayout) e.getParent()).addView(count, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private EditText textInto(LinearLayout target, String label, String help, String value, int inputType) {
        Context c = getContext(); float d = Theme.dp(c);
        LinearLayout row = rowShell(); row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(c); col.setOrientation(VERTICAL);
        col.addView(Neo.text(c, label, Neo.MUTED, 13, Neo.uiSemi(c)));
        EditText e = new EditText(c); e.setText(value); e.setInputType(inputType); e.setTextSize(17); e.setTextColor(Neo.TEXT); e.setSingleLine(true);
        e.setTypeface((inputType & InputType.TYPE_CLASS_NUMBER) != 0 ? Neo.mono(c) : Neo.uiSemi(c));
        e.setBackground(null); e.setPadding(0, (int) (4 * d), 0, (int) (2 * d));
        col.addView(e);
        row.addView(col, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(infoButton(label, help), new LayoutParams((int) (40 * d), (int) (40 * d)));
        target.addView(row);
        return e;
    }

    private TextView choiceInto(LinearLayout target, String label, String help, final String[] options, int index, final IntSetter setter) {
        Context c = getContext(); float d = Theme.dp(c);
        LinearLayout row = rowShell(); row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Neo.text(c, label, Neo.TEXT, 15.5f, Neo.uiSemi(c)), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = Neo.text(c, options != null && index >= 0 && index < options.length ? options[index] : "", Neo.CYAN_LIGHT, 15, Neo.ui(c));
        val.setBackground(Neo.outline(c, Neo.CYAN, 0x16, 12, 1)); val.setPadding((int) (12 * d), (int) (7 * d), (int) (12 * d), (int) (7 * d)); val.setLetterSpacing(0.03f);
        row.addView(val);
        row.addView(infoButton(label, help), new LayoutParams((int) (40 * d), (int) (40 * d)));
        if (options != null) {
            final int[] cur = { index };
            row.setClickable(true);
            View.OnClickListener cl = v -> { cur[0] = (cur[0] + 1) % options.length; val.setText(options[cur[0]]); if (setter != null) setter.set(cur[0]); };
            row.setOnClickListener(cl); val.setOnClickListener(cl);
        }
        target.addView(row);
        return val;
    }

    private LinearLayout rowShell() {
        Context c = getContext(); float d = Theme.dp(c);
        LinearLayout row = new LinearLayout(c); row.setOrientation(VERTICAL);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(0xBF0B1622); g.setCornerRadius(12 * d); g.setStroke((int) d, 0xFF142536);
        row.setBackground(g); row.setPadding((int) (14 * d), (int) (8 * d), (int) (4 * d), (int) (8 * d)); row.setMinimumHeight((int) (56 * d));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.bottomMargin = (int) (6 * d);
        row.setLayoutParams(lp);
        return row;
    }

    /** Validate + copy the text fields into the channel. */
    /** A typed number, or a plain message naming the field (Java's own says only "empty String" or "For input string"). */
    static double number(String text, String what) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw new IllegalArgumentException(Txt.s(R.string.channel_editor_err_enter, what));
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { throw new IllegalArgumentException(Txt.s(R.string.channel_editor_err_not_number, t, what)); }
    }
    private boolean apply() {
        try {
            ch.name = ChannelStore.fitName(name.getText().toString());
            ch.notes = notes.getText().toString().trim();
            ch.rxFreq = (int) Math.round(number(rx.getText().toString(), Txt.s(R.string.channel_editor_what_rx)) * 1e6);
            String txs = tx.getText().toString().trim();
            ch.txFreq = txs.isEmpty() ? ch.rxFreq : (int) Math.round(number(txs, Txt.s(R.string.channel_editor_what_tx)) * 1e6);   // empty: simplex
            if (!ChannelStore.inBand(ch.rxFreq) || !ChannelStore.inBand(ch.txFreq)) throw new IllegalArgumentException(Txt.s(R.string.channel_editor_err_band, com.macdmr.radio.DeviceProfile.detect().bandsText()));
            if (ch.rxFreq < 300_000_000) ch.band = 1;                                  // VHF must be wide on this radio
            String la = lat.getText().toString().trim(), lo = lon.getText().toString().trim();
            if (la.isEmpty() && lo.isEmpty()) { ch.lat = 0; ch.lon = 0; }
            else if (la.isEmpty() || lo.isEmpty()) throw new IllegalArgumentException(Txt.s(R.string.channel_editor_err_both));
            else { ch.lat = number(la, Txt.s(R.string.channel_editor_what_lat)); ch.lon = number(lo, Txt.s(R.string.channel_editor_what_lon)); if (Math.abs(ch.lat) > 90 || Math.abs(ch.lon) > 180) throw new IllegalArgumentException(Txt.s(R.string.channel_editor_err_latlon)); }
            if (ch.isM17()) {
                ch.m17dest = com.macdmr.radio.ChannelStore.Channel.cleanM17Dest(m17Dest.getText().toString());
                ch.band = 1;
                ch.sq = 0;
                ch.cc = ch.can();
            } else if (ch.isDigital()) {
                ch.txContact = call.getText().toString().trim().isEmpty() ? 0 : (int) number(call.getText().toString(), Txt.s(R.string.channel_editor_what_call));
                List<Integer> g = new ArrayList<>();
                for (String s : groups.getText().toString().split("[,; ]+")) { s = s.trim(); if (!s.isEmpty()) g.add((int) number(s, Txt.s(R.string.channel_editor_what_group))); }
                // the radio passes a group call only if its talkgroup is in this list (measured on air 2026-10-15): include the call target
                if (ch.contactType == 1 && ch.txContact > 0 && !g.contains(ch.txContact)) g.add(0, ch.txContact);
                if (g.size() > 32) throw new IllegalArgumentException(Txt.s(R.string.channel_editor_err_groups));
                ch.groups = new int[g.size()]; for (int i = 0; i < g.size(); i++) ch.groups[i] = g.get(i);
                String k = key.getText().toString();
                if (ch.encryptSw == 1 && k.length() != 8) throw new IllegalArgumentException(Txt.s(R.string.channel_editor_err_key));
                ch.encryptKey = k;
            }
            return true;
        } catch (Exception e) {
            new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.channel_editor_check_title)).setMessage(e.getMessage() == null ? e.toString() : e.getMessage()).setPositiveButton(Txt.s(R.string.common_ok), null).show();


            return false;
        }
    }
}
