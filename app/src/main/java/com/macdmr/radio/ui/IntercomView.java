/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.CircuitBoardView;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.i18n.Txt;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The intercom page exactly as the module renders it on the OEM screen: OEM geometry (60 dp arrows,
 * 34×58 dp digit bitmaps, 15 sp info text, 176 dp PTT) plus the module's injected rows with their
 * original sizes, margins, colours and artwork.
 */
public class IntercomView extends LinearLayout implements RadioService.Listener {
    private static final int[] DIGITS = { R.drawable.interphone_talkback_num_0, R.drawable.interphone_talkback_num_1, R.drawable.interphone_talkback_num_2,
            R.drawable.interphone_talkback_num_3, R.drawable.interphone_talkback_num_4, R.drawable.interphone_talkback_num_5, R.drawable.interphone_talkback_num_6,
            R.drawable.interphone_talkback_num_7, R.drawable.interphone_talkback_num_8, R.drawable.interphone_talkback_num_9 };

    private TextView title, power, squelch, send, receive, location, signal, scanBtn, zoneBtn, sqValue;
    private final TextView headline, historyHeader, historyText, transcriptionText;
    private SeekBar sqSeek;
    private boolean sqFromUser;
    private ImageView digitOne, digitTwo;
    private TextView vfoLabel;
    // new look only
    private final boolean neo;
    private LinearLayout neoHeader, chipRow;
    private TextView bigNum, smeterText;
    private TextView[] readLabel;
    private SMeterView smeter;
    private String zoneName = "ALL";
    private LinearLayout scanZoneRow;
    private LinearLayout sqRow;                       // new look's Soft SQ slider row: shown only while Soft SQ is on an FM channel
    private boolean sqShown = true, chanFm = true;
    private String lastZoneText = "";

    /**
     * Every size on the new-look page, in dp and sp. Both layouts are built by the same code from one of these sets, so
     * whatever is added to or changed on the page reaches small screens too: give a new part its size here (the field's
     * starting value is the regular size, {@link #compact()} sets the small one), never as a number in the build code.
     * The regular sizes are the page as it was before compact screens (Armor 26 Ultra, about 800 dp tall).
     */
    static final class Size {
        boolean compact;
        int side = 16, headerMin = 60, logo = 28, logoGap = 12, menu = 44; float titleSp = 15, subSp = 11.5f;
        int arrow = 50, arrowPad = 13, arrowSide = 18, stripTop = 6, numPadH = 22, numPadV = 2, chipTop = 4, chipGap = 6, chipPadH = 6, chipPadV = 2;
        float numSp = 50, chipSp = 11; boolean chipsBeside;
        int cardTop = 8, cardPadL = 14, cardPadT = 9, cardPadR = 12, cardPadB = 4, valueH = 22, valuePadTop = 4, divTop = 8, divBottom = 6, meterTop = 4;
        float labelSp = 11.5f, valueSp = 15.5f, meterSp = 17, meterQuietSp = 12.5f;
        int scanMinH = 44, scanTop = 6; float scanSp = 15.5f;
        int sqTop = 14, sqH = 32, sqLabelW = 70, sqValueW = 24; float sqLabelSp = 12, sqValueSp = 20;
        int panelTop = 8, infoPadH = 10, infoPadV = 6, capMax = 96, capBottom = 22; float headSp = 17, histSp = 12.5f;
        int bottomH = 182, bottomTop = 4, ptt = 0, satW = 76, satH = 54, satSide = 12, satTop0 = 4, satStep = 59;

        /** Phones under 700 dp tall (the W6: 320 x 569 dp): the same page, smaller, with the channel strip inside the readout card. */
        Size compact() {
            compact = true;
            side = 10; headerMin = 40; logo = 20; logoGap = 9; menu = 34; titleSp = 13; subSp = 9.5f;
            arrow = 34; arrowPad = 8; arrowSide = 0; stripTop = 0; numPadH = 10; numPadV = 0; chipTop = 0; chipGap = 3; chipPadH = 4; chipPadV = 1;
            numSp = 34; chipSp = 8.5f; chipsBeside = true;
            cardTop = 6; cardPadL = 10; cardPadT = 6; cardPadR = 10; cardPadB = 2; valueH = 16; valuePadTop = 2; divTop = 5; divBottom = 4; meterTop = 2;
            labelSp = 8.5f; valueSp = 12.5f; meterSp = 13; meterQuietSp = 10.5f;
            scanMinH = 30; scanTop = 6; scanSp = 12;
            sqTop = 6; sqH = 24; sqLabelW = 56; sqValueW = 20; sqLabelSp = 10; sqValueSp = 15;
            panelTop = 6; infoPadH = 8; infoPadV = 4; capMax = 56; capBottom = 6; headSp = 13; histSp = 11;
            bottomH = 150; bottomTop = 2; ptt = 132; satW = 66; satH = 44; satSide = 8; satTop0 = 4; satStep = 49;
            return this;
        }
    }
    static final Size REGULAR = new Size(), COMPACT = new Size().compact();
    private final Size k;

    /** Screens under this many dp tall get the compact sizes (the W6 is 569, the Armor 26 Ultra about 800). */
    static final int COMPACT_BELOW_DP = 700;
    /** The layout choice: "auto" (by screen height), "regular" or "compact" (Device → Radio settings → Intercom layout). */
    public static String layoutPref(Context c) { return c.getSharedPreferences("radio", Context.MODE_PRIVATE).getString("intercomLayout", "auto"); }
    public static void setLayoutPref(Context c, String v) { c.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putString("intercomLayout", v).apply(); }
    /** Compact or not, from the screen height in dp and the setting. */
    static boolean compactFor(int screenHeightDp, String pref) {
        if ("compact".equals(pref)) return true;
        if ("regular".equals(pref)) return false;
        return screenHeightDp > 0 && screenHeightDp < COMPACT_BELOW_DP;
    }
    /** This phone's choice: the intercom page and the tab bar follow it. */
    public static boolean compactScreen(Context c) { return compactFor(c.getResources().getConfiguration().screenHeightDp, layoutPref(c)); }

    /**
     * Share the SCAN / ZONE row by what each label needs: SCAN keeps room for its longest state ("SCANNING", so it does
     * not jump when a scan starts), ZONE gets the rest; if both cannot fit, both shrink their text (auto-size).
     */
    private void rebalanceScanZone() {
        if (scanZoneRow == null) return;
        if (scanZoneRow.getWidth() <= 0) { scanZoneRow.post(this::rebalanceScanZone); return; }
        android.text.TextPaint p = new android.text.TextPaint(scanBtn.getPaint());
        p.setTextSize(k.scanSp * getResources().getDisplayMetrics().scaledDensity);
        float d = Theme.dp(getContext()), extra = (18 + 8 + 24 + 6) * d;   // icon, icon gap, side padding, slack
        float scanNeed = Math.max(p.measureText("SCANNING"), Math.max(p.measureText("SCAN"), p.measureText("HOLD"))) + extra;   // i18n: key legend
        float zoneNeed = p.measureText(zoneBtn.getText().toString()) + extra;
        ((LayoutParams) scanBtn.getLayoutParams()).weight = scanNeed;
        ((LayoutParams) zoneBtn.getLayoutParams()).weight = zoneNeed;
        scanZoneRow.requestLayout();
    }

    /** Animate the panel's drops on a quiet channel (Device setting, on by default; it always animates while receiving). */
    public static boolean animatedPanel(Context c) { return c.getSharedPreferences("radio", Context.MODE_PRIVATE).getBoolean("animPanel", true); }
    public void setAnimatedPanel(boolean on) { getContext().getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putBoolean("animPanel", on).apply(); board.animateIdle = on; }

    /** Which intercom page to build: the new look (default since 2026-10-23) or the classic module page. */
    public static boolean newLook(Context c) { return c.getSharedPreferences("radio", Context.MODE_PRIVATE).getBoolean("newIntercom", true); }
    public static void setNewLook(Context c, boolean on) { c.getSharedPreferences("radio", Context.MODE_PRIVATE).edit().putBoolean("newIntercom", on).apply(); }
    private final LinearLayout transcriptionBox;
    private final TextView captionLive;
    private final android.widget.ScrollView captionScroll;
    private final java.util.ArrayDeque<String> captionLines = new java.util.ArrayDeque<>();
    private final CircuitBoardView board;
    private final PttButton ptt;
    private final NeonToggle softSq, txt, vfo, rec, mon, pkt;
    private final Deque<String> history = new ArrayDeque<>();
    private RadioService svc;
    private boolean rx, tx;
    private LinearLayout infoPanel;
    private boolean panelClosed;

    /** The activity box's cross: empty its list and hide it; it comes back on the next reception or transmission (Last heard keeps everything). */
    private ImageView restoreBox;
    private void closePanel() { panelClosed = true; history.clear(); renderHistory(); infoPanel.setVisibility(GONE); restoreBox.setVisibility(VISIBLE); board.barCeilingPx = 0; }
    /** The small button left behind: bring the box back now, with this channel's recent events. */
    private void restorePanel() {
        if (!panelClosed) return;
        panelClosed = false; infoPanel.setVisibility(VISIBLE); restoreBox.setVisibility(GONE);
        ChannelStore.Channel ch = svc != null ? svc.current() : null;
        history.clear(); if (svc != null && ch != null) for (String l : svc.store().recentHistory(ch.number, 3)) history.addLast(l);
        renderHistory();
    }
    private void reopenPanel(String why) { if (!panelClosed) return; android.util.Log.i("IntercomView", "activity box reopened: " + why); restoreBox.setVisibility(GONE); panelClosed = false; infoPanel.setVisibility(VISIBLE); }

    public IntercomView(Context c) {
        super(c);
        setOrientation(VERTICAL); setBackgroundColor(Theme.BG);
        final float d = Theme.dp(c);
        final int dp10 = (int) (10 * d), dp8 = (int) (8 * d), dp4 = (int) (4 * d);
        neo = newLook(c);
        k = neo && compactScreen(c) ? COMPACT : REGULAR;
        if (neo) { setBackground(null); buildNeoTop(c, d); } else {

        // ---- title bar (OEM: 24 sp title, hamburger) ----
        LinearLayout bar = new LinearLayout(c); bar.setBackgroundColor(Theme.TITLE_BG); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (20 * d), (int) (4 * d), (int) (16 * d), (int) (4 * d));
        title = Theme.label(c, "", Theme.WHITE, 24, false);
        bar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(Theme.label(c, "☰", Theme.WHITE, 26, false));
        bar.setOnLongClickListener(v -> { offerLook(true); return true; });
        addView(bar);

        // ---- channel row: sub (60 dp) · two digit bitmaps (34×58 dp) · add (60 dp) ----
        FrameLayout chRow = new FrameLayout(c);
        LayoutParams chLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); chLp.topMargin = (int) (5 * d);
        ImageView sub = new ImageView(c); sub.setImageResource(R.drawable.interphone_talkback_sub); sub.setClickable(true);
        ImageView add = new ImageView(c); add.setImageResource(R.drawable.interphone_talkback_add); add.setClickable(true);
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams((int) (60 * d), (int) (60 * d), Gravity.START | Gravity.CENTER_VERTICAL); subLp.leftMargin = (int) (26 * d);
        FrameLayout.LayoutParams addLp = new FrameLayout.LayoutParams((int) (60 * d), (int) (60 * d), Gravity.END | Gravity.CENTER_VERTICAL); addLp.rightMargin = (int) (26 * d);
        chRow.addView(sub, subLp); chRow.addView(add, addLp);
        LinearLayout digits = new LinearLayout(c); digits.setGravity(Gravity.CENTER);
        digitOne = new ImageView(c); digitTwo = new ImageView(c);
        LayoutParams dl = new LayoutParams((int) (34 * d), (int) (58 * d)); dl.rightMargin = (int) (6 * d);
        digits.addView(digitOne, dl); digits.addView(digitTwo, new LayoutParams((int) (34 * d), (int) (58 * d)));
        vfoLabel = Theme.label(c, "VFO", Theme.VFO[3], 30, true); vfoLabel.setVisibility(GONE); vfoLabel.setShadowLayer(14 * d, 0, 0, Theme.VFO[2]);   // i18n: key legend
        digits.addView(vfoLabel);
        chRow.addView(digits, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        addView(chRow, chLp);
        sub.setOnClickListener(v -> { if (svc != null) new Thread(() -> svc.stepChannel(-1)).start(); });
        add.setOnClickListener(v -> { if (svc != null) new Thread(() -> svc.stepChannel(1)).start(); });

        // ---- info block: OEM 15 sp cyan labels left, location right ----
        LinearLayout info = new LinearLayout(c); info.setPadding(0, (int) (6 * d), 0, (int) (4 * d));
        LinearLayout leftCol = new LinearLayout(c); leftCol.setOrientation(VERTICAL);
        // module: OEM's 15 dp labels are restyled to 11 sp with no padding (MainHook 1762-1774)
        power = Theme.label(c, Txt.s(R.string.intercom_main_classic_power), Theme.CYAN, 11, false); squelch = Theme.label(c, Txt.s(R.string.intercom_main_classic_squelch), Theme.CYAN, 11, false);
        send = Theme.label(c, Txt.s(R.string.intercom_main_classic_send), Theme.CYAN, 11, false); receive = Theme.label(c, Txt.s(R.string.intercom_main_classic_receive), Theme.CYAN, 11, false);
        for (TextView t : new TextView[] { power, squelch, send, receive }) { t.setPadding(dp10, (int) (1 * d), 0, (int) (1 * d)); leftCol.addView(t); }   // the page's 10 dp margin
        info.addView(leftCol, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        location = Theme.label(c, "", Theme.WHITE, 12, false); location.setGravity(Gravity.END);
        LayoutParams locLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); locLp.rightMargin = dp10;
        info.addView(location, locLp);
        addView(info);

        // ---- Signal (0.4) · SCAN (0.3) · ZONE (0.3) ----
        LinearLayout sr = new LinearLayout(c); sr.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams srLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); srLp.leftMargin = srLp.rightMargin = dp10; srLp.topMargin = dp4;
        signal = Theme.label(c, Txt.s(R.string.intercom_main_classic_signal_none_yet), Theme.YELLOW, 16, true); signal.setGravity(Gravity.CENTER);
        signal.setSingleLine(true); signal.setMaxLines(1);   // "Signal: -104 dBm" must never wrap and push the page down
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(signal, 10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        signal.setBackground(Theme.rounded(c, 0x15FFFF00, 0xAAFFFF00, 2, 8)); signal.setPadding(dp4, (int) (12 * d), dp4, (int) (12 * d));
        LayoutParams sl = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.4f); sl.rightMargin = dp4;
        sr.addView(signal, sl);
        TextView scan = pill(c, "🔍 SCAN"), zone = pill(c, "ZONE: ALL"); scanBtn = scan; zoneBtn = zone;   // i18n: key legend
        LayoutParams bl = new LayoutParams(0, LayoutParams.MATCH_PARENT, 0.3f); bl.leftMargin = dp4;
        sr.addView(scan, bl); sr.addView(zone, new LayoutParams(bl));
        addView(sr, srLp);
        scan.setOnClickListener(v -> { if (svc != null) android.widget.Toast.makeText(c, svc.toggleScan(), android.widget.Toast.LENGTH_SHORT).show(); });
        scan.setOnLongClickListener(v -> { if (svc != null) android.widget.Toast.makeText(c, svc.skipScanChannel(), android.widget.Toast.LENGTH_SHORT).show(); return true; });
        zone.setOnClickListener(v -> pickZone());

        // ---- SQ slider row (module: SQ: 14 sp grey bold, value 18 sp cyan, cyan seek bar) ----
        LinearLayout sq = new LinearLayout(c); sq.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams sqLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); sqLp.leftMargin = (int) (16 * d); sqLp.topMargin = dp4; sqLp.bottomMargin = (int) (-6 * d);
        TextView sqLabel = Theme.label(c, "SQ:", Theme.GREY, 14, true); LayoutParams ll = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); ll.rightMargin = (int) (2 * d);   // i18n: key legend
        sq.addView(sqLabel, ll);
        sqValue = Theme.label(c, "3", Theme.CYAN, 18, true); sq.addView(sqValue);
        SeekBar seek = new SeekBar(c); seek.setMax(9); seek.setProgress(5); sqSeek = seek;
        android.content.res.ColorStateList cyan = android.content.res.ColorStateList.valueOf(Theme.CYAN);
        seek.setProgressTintList(cyan); seek.setProgressBackgroundTintList(cyan); seek.setThumbTintList(cyan);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { sqValue.setText(String.valueOf(Math.max(1, p))); }
            @Override public void onStartTrackingTouch(SeekBar s) { sqFromUser = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { sqFromUser = false; if (svc != null) svc.setSoftSqLevel(Math.max(1, s.getProgress())); }
        });
        LayoutParams skl = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.42f); skl.leftMargin = dp10; skl.rightMargin = dp10; skl.topMargin = dp4;
        sq.addView(seek, skl);
        sq.addView(new TextView(c), new LayoutParams(0, 1, 0.58f));
        addView(sq, sqLp);
        }

        // ---- circuit-board panel (250 dp, 10 dp side margins, 8 dp top, 2 dp 35 % cyan border, radius 12) ----
        FrameLayout panel = new FrameLayout(c);
        board = new CircuitBoardView(c); board.animateIdle = animatedPanel(c);   // draws the panel frame itself (its own surface: the window cannot paint over it)
        board.setFrame(0xFF0B1823, 0x5900E5FF, 2, 12, Theme.BG);   // fill = classic page navy with the panel's faint cyan tint
        panel.addView(board, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        final LinearLayout infoPanel = new LinearLayout(c); infoPanel.setOrientation(VERTICAL); this.infoPanel = infoPanel;
        infoPanel.setBackground(Theme.rounded(c, 0x18000000, 0x5500BFFF, 1, 8));
        int ip = (int) (5 * d); infoPanel.setPadding(ip, ip, ip, ip);
        headline = Theme.label(c, Txt.s(R.string.intercom_main_classic_ready), Theme.GREEN, 16, true); headline.setMaxLines(1);
        LayoutParams hl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); hl.bottomMargin = (int) (2 * d);
        // headline row with a clear-and-close button: hides the box (and empties its list) until the next reception or transmission
        LinearLayout headRow = new LinearLayout(c); headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.addView(headline, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        ImageView closeBox = new ImageView(c); closeBox.setImageDrawable(new Neo.IconDrawable("close", Neo.MUTED)); closeBox.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int cp = (int) (8 * d); closeBox.setPadding(cp, cp, cp, cp); closeBox.setContentDescription(Txt.s(R.string.intercom_main_panel_close)); closeBox.setClickable(true);
        closeBox.setOnClickListener(v -> closePanel());
        headRow.addView(closeBox, new LayoutParams((int) (34 * d), (int) (34 * d)));
        infoPanel.addView(headRow, hl);
        historyHeader = Theme.label(c, Txt.s(R.string.intercom_main_classic_history_analog), Theme.CYAN2, 13, true);
        LayoutParams hh = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); hh.bottomMargin = dp4;
        infoPanel.addView(historyHeader, hh);
        historyText = Theme.label(c, "", Theme.CYAN2, 11, false); infoPanel.addView(historyText);
        if (neo) {
            headline.setTypeface(Neo.ui(c)); headline.setTextSize(k.headSp); headline.setLetterSpacing(0.04f);
            historyHeader.setVisibility(GONE);   // the headline already says what the panel shows
            historyText.setTypeface(Neo.uiSemi(c)); historyText.setTextColor(Neo.TEXT_SOFT); historyText.setTextSize(k.histSp); historyText.setMaxLines(3); historyText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            infoPanel.setBackground(Neo.card(c, Neo.alpha(Neo.CYAN, 0x30), 12)); infoPanel.setPadding((int) (k.infoPadH * d), (int) (k.infoPadV * d), (int) (k.infoPadH * d), (int) (k.infoPadV * d));
        }
        infoPanel.setClickable(true); infoPanel.setOnClickListener(v -> {
            if (svc == null) return;
            final String tone = svc.pendingToneLabel();
            if (tone != null && svc.current() != null) {
                new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.intercom_main_tone_heard_title, tone))
                        .setMessage(Txt.s(R.string.intercom_main_tone_heard_message, svc.current().name))
                        .setPositiveButton(Txt.s(R.string.intercom_main_tone_heard_use), (d2, w) -> android.widget.Toast.makeText(c, svc.applyPendingTone(), android.widget.Toast.LENGTH_LONG).show())
                        .setNeutralButton(Txt.s(R.string.intercom_main_last_heard), (d2, w) -> LastHeardDialog.show(c, svc))
                        .setNegativeButton(Txt.s(R.string.common_not_now), (d2, w) -> svc.dismissPendingTone()).show();
            } else LastHeardDialog.show(c, svc);
        });
        FrameLayout.LayoutParams ipl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        ipl.topMargin = ipl.leftMargin = ipl.rightMargin = dp8;
        panel.addView(infoPanel, ipl);
        infoPanel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> board.barCeilingPx = v.getVisibility() == VISIBLE ? b : 0);   // the spectrum bars stay below the activity box
        restoreBox = Neo.iconButton(c, "txt", Neo.CYAN, Txt.s(R.string.intercom_main_panel_restore)); int rp = (int) (9 * d); restoreBox.setPadding(rp, rp, rp, rp);
        restoreBox.setOnClickListener(v -> restorePanel()); restoreBox.setVisibility(GONE);
        FrameLayout.LayoutParams rbl = new FrameLayout.LayoutParams((int) (38 * d), (int) (38 * d), Gravity.TOP | Gravity.END); rbl.topMargin = rbl.rightMargin = dp8;
        panel.addView(restoreBox, rbl);
        transcriptionBox = new LinearLayout(c); transcriptionBox.setOrientation(VERTICAL);
        transcriptionBox.setBackground(Theme.rounded(c, 0xF0071626, 0xAA00BFFF, 2, 8)); transcriptionBox.setPadding(dp8, dp8, dp8, dp8);
        TextView th = Theme.label(c, Txt.s(R.string.intercom_main_transcription), Theme.CYAN2, 10, true);
        LayoutParams thl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); thl.bottomMargin = dp4;
        transcriptionBox.addView(th, thl);
        // capped like the module (inner scroll, newest at the bottom) so the box never grows over the history
        final int capPx = (int) (k.capMax * d);
        captionScroll = new android.widget.ScrollView(c) {
            @Override protected void onMeasure(int w, int h) { super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(capPx, android.view.View.MeasureSpec.AT_MOST)); }
        };
        captionScroll.setVerticalScrollBarEnabled(false);
        LinearLayout capCol = new LinearLayout(c); capCol.setOrientation(VERTICAL);
        transcriptionText = Theme.label(c, "", Theme.WHITE, 13, false); capCol.addView(transcriptionText);
        captionLive = Theme.label(c, "", Theme.CYAN, 13, false); captionLive.setTypeface(null, android.graphics.Typeface.ITALIC); capCol.addView(captionLive);
        captionScroll.addView(capCol); transcriptionBox.addView(captionScroll);
        FrameLayout.LayoutParams tbl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        tbl.leftMargin = tbl.rightMargin = dp8; tbl.bottomMargin = (int) (k.capBottom * d);
        panel.addView(transcriptionBox, tbl); transcriptionBox.setVisibility(GONE);
        LayoutParams pl = neo ? new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f) : new LayoutParams(LayoutParams.MATCH_PARENT, (int) (250 * d)); pl.leftMargin = pl.rightMargin = neo ? (int) (k.side * d) : dp10; pl.topMargin = neo ? (int) (k.panelTop * d) : dp8;   // classic: the module's fixed 250 dp (its circuit board is a surface: no resizing)
        if (neo) board.setFrame(0xFF07121D, Neo.LINE, 1, 14, Neo.GROUND);   // the card fill as it looks over the page
        addView(panel, pl);

        // ---- PTT (176 dp, centred) with the six satellites at the module's exact offsets ----
        final FrameLayout bottom = new FrameLayout(c);
        ptt = new PttButton(c);
        int pttPx = neo && k.ptt > 0 ? (int) (k.ptt * d) : FrameLayout.LayoutParams.WRAP_CONTENT;
        bottom.addView(ptt, new FrameLayout.LayoutParams(pttPx, pttPx, Gravity.CENTER));
        if (neo) {
            softSq = new NeoToggle(c, "softsq", "SOFT SQ", Neo.CYAN); txt = new NeoToggle(c, "txt", "TXT", Neo.VIOLET); vfo = new NeoToggle(c, "vfo", "VFO", Neo.AMBER);   // i18n: key legend
            rec = new NeoToggle(c, "rec", "REC", Neo.RED); mon = new NeoToggle(c, "mon", "MON", Neo.AMBER); pkt = new NeoToggle(c, "pkt", "PKT", Neo.GREEN);   // i18n: key legend
            NeonToggle[] l = { softSq, txt, vfo }, r = { rec, mon, pkt };
            if (k.compact) for (NeonToggle t : new NeonToggle[] { softSq, txt, vfo, rec, mon, pkt }) ((NeoToggle) t).compact();
            for (int i = 0; i < 3; i++) { bottom.addView(l[i], neoSat(d, k, Gravity.START, k.satTop0 + i * k.satStep)); bottom.addView(r[i], neoSat(d, k, Gravity.END, k.satTop0 + i * k.satStep)); }
        } else {
        softSq = new NeonToggle(c, "🎚️", "SOFT SQ", Theme.SOFTSQ); txt = new NeonToggle(c, "✍️", "TXT", Theme.TXT); vfo = new NeonToggle(c, "🎛️", "VFO", Theme.VFO);   // i18n: key legend
        rec = new NeonToggle(c, "🔴", "REC", Theme.REC); mon = new NeonToggle(c, "👁️", "MON", Theme.MON); pkt = new NeonToggle(c, "📡", "PKT", Theme.PKT);   // i18n: key legend
        bottom.addView(softSq, sat(d, 80, Gravity.START, 8, 4));
        bottom.addView(txt, sat(d, 70, Gravity.START, 8, 58));
        bottom.addView(vfo, sat(d, 70, Gravity.START, 8, 112));
        bottom.addView(rec, sat(d, 70, Gravity.END, 16, 4));
        bottom.addView(mon, sat(d, 70, Gravity.END, 16, 58));
        bottom.addView(pkt, sat(d, 76, Gravity.END, 16, 112));
        }
        LayoutParams btl = new LayoutParams(LayoutParams.MATCH_PARENT, (int) (k.bottomH * d)); btl.topMargin = (int) (k.bottomTop * d);   // PTT + satellites need 182 dp (classic offsets 4/58/112 dp + 70 dp); compact 150
        if (neo) addView(bottom, btl);
        else {
            // classic: the PTT area keeps its 182 dp design and is scaled down to the room left (a mode bar takes ~55 dp), so
            // all six satellites stay on the page and tappable (touches follow the scaling)
            final int design = (int) (182 * d);
            FrameLayout holder = new FrameLayout(c) {
                @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
                    super.onSizeChanged(w, h, ow, oh);
                    float sc = Math.min(1f, h / (float) design);
                    bottom.setPivotX(w / 2f); bottom.setPivotY(0); bottom.setScaleX(sc); bottom.setScaleY(sc);
                }
            };
            holder.addView(bottom, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, design, Gravity.TOP));
            LayoutParams hold = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f); hold.topMargin = dp4;
            addView(holder, hold);
        }
        // Mode satellites. Those not brought over yet say so instead of pretending to toggle.
        softSq.setOnClickListener(v -> { if (svc != null) svc.setSoftSq(!softSq.isOn()); });
        rec.setOnClickListener(v -> { if (svc != null) svc.setRecord(!rec.isOn()); });
        mon.setOnClickListener(v -> { if (svc != null) svc.setMonitor(!mon.isOn()); });
        txt.setOnClickListener(v -> {
            if (svc == null) return;
            if (!svc.captionsAvailable()) { android.widget.Toast.makeText(c, "google".equals(svc.captionEngine()) ? Txt.s(R.string.intercom_main_captions_need_key) : Txt.s(R.string.intercom_main_captions_unavailable), android.widget.Toast.LENGTH_LONG).show(); return; }
            svc.setCaptions(!txt.isOn());
        });
        txt.setOnLongClickListener(v -> {
            final boolean google = "google".equals(svc.captionEngine());
            new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.intercom_main_captions_menu_title)).setItems(new String[] { google ? Txt.s(R.string.intercom_main_captions_engine_google) : Txt.s(R.string.intercom_main_captions_engine_vosk), Txt.s(R.string.intercom_main_captions_menu_model), Txt.s(R.string.intercom_main_captions_menu_key), Txt.s(R.string.intercom_main_captions_menu_keywords) }, (d2, w) -> {
                if (w == 0) { svc.setCaptionEngine(google ? "vosk" : "google"); android.widget.Toast.makeText(c, google ? Txt.s(R.string.intercom_main_captions_now_vosk) : Txt.s(R.string.intercom_main_captions_now_google), android.widget.Toast.LENGTH_SHORT).show(); }
                else if (w == 1) pickCaptionModel();
                else if (w == 2) editGoogleKey();
                else editKeywords();
            }).show();
            return true;
        });
        vfo.setOnClickListener(v -> { if (svc != null) VfoDialog.show(c, svc); });
        vfo.setOnLongClickListener(v -> { if (svc != null && svc.vfoActive()) { svc.exitVfo(); android.widget.Toast.makeText(c, Txt.s(R.string.intercom_main_vfo_off_toast), android.widget.Toast.LENGTH_SHORT).show(); } return true; });
        pkt.setOnClickListener(v -> { if (svc != null) PktDialog.show(c, svc); });   // the packet tools; running modes are one tap away at the top
        ptt.setListener(down -> { if (svc != null) svc.ptt(down); });
    }

    private static FrameLayout.LayoutParams neoSat(float d, Size k, int side, int topDp) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams((int) (k.satW * d), (int) (k.satH * d), side | Gravity.TOP);
        if (side == Gravity.START) p.leftMargin = (int) (k.satSide * d); else p.rightMargin = (int) (k.satSide * d);
        p.topMargin = (int) (topDp * d);
        return p;
    }

    /** Offer to switch looks (long-press the classic title, or the new look's menu). */
    private void offerLook(boolean toNew) {
        final Context c = getContext();
        new android.app.AlertDialog.Builder(c).setTitle(toNew ? Txt.s(R.string.intercom_main_look_try_new) : Txt.s(R.string.intercom_main_look_back_classic))
                .setMessage(Txt.s(R.string.intercom_main_look_message))
                .setPositiveButton(toNew ? Txt.s(R.string.intercom_main_look_new) : Txt.s(R.string.intercom_main_look_classic), (dd, w) -> { setNewLook(c, toNew); if (c instanceof com.macdmr.radio.MainActivity) ((com.macdmr.radio.MainActivity) c).rebuildIntercom(); })
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    /** Soft halo behind the channel number: a radial gradient drawn once (a text shadow is re-blurred on every change and cost up to 38 ms of GPU per channel step). */
    private android.graphics.drawable.Drawable glow(int color) {
        GradientDrawable g = new GradientDrawable(); g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] { Neo.alpha(color, 0x3C), Neo.alpha(color, 0x14), 0x00000000 }); g.setGradientRadius(Theme.dp(getContext(), 70));
        return g;
    }

    private ImageView roundArrow(Context c, String icon, String desc) {
        ImageView v = new ImageView(c); v.setImageDrawable(new Neo.IconDrawable(icon, Neo.CYAN)); v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = Theme.dp(c, k.arrowPad); v.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xFF12304A, 0xFF08121D }); g.setShape(GradientDrawable.OVAL); g.setStroke(Theme.dp(c, 1.5f), Neo.CYAN);
        v.setBackground(g);
        v.setContentDescription(desc); v.setClickable(true); v.setFocusable(true);
        return v;
    }

    /**
     * The new look's top half: header, channel strip, readout, S-meter, SCAN/ZONE, Soft SQ slider. One build for every
     * screen, sized by {@link #k}; on a compact screen the channel strip sits inside the readout card instead of above it.
     */
    private void buildNeoTop(final Context c, final float d) {
        final int m = (int) (k.side * d);
        neoHeader = Neo.header(c, "", Txt.s(R.string.intercom_main_zone_subtitle, "ALL").toUpperCase(java.util.Locale.US));
        neoHeader.setMinimumHeight((int) (k.headerMin * d));
        title = Neo.headerTitle(neoHeader); title.setTextSize(k.titleSp); Neo.headerSubtitle(neoHeader).setTextSize(k.subSp);
        ImageView logo = new ImageView(c); logo.setImageDrawable(new Neo.IconDrawable("radio", Neo.CYAN));
        LayoutParams lg = new LayoutParams((int) (k.logo * d), (int) (k.logo * d)); lg.rightMargin = (int) (k.logoGap * d);
        neoHeader.addView(logo, 0, lg);
        ImageView menu = Neo.iconButton(c, "menu", Neo.CYAN, Txt.s(R.string.intercom_main_menu));
        menu.setOnClickListener(v -> new android.app.AlertDialog.Builder(c).setItems(new String[] { Txt.s(R.string.intercom_main_menu_zone), Txt.s(R.string.intercom_main_last_heard), Txt.s(R.string.intercom_main_menu_nets), Txt.s(R.string.intercom_main_menu_map), Txt.s(R.string.intercom_main_menu_fox), Txt.s(R.string.intercom_main_menu_emergency), Txt.s(R.string.intercom_main_menu_classic) }, (dd, w) -> {
            if (w == 0) pickZone(); else if (w == 1) { if (svc != null) LastHeardDialog.show(c, svc); } else if (w == 2) NetsDialog.show(c, svc); else if (w == 3) MapDialog.show(c, svc); else if (w == 4) FoxHuntDialog.show(c, svc); else if (w == 5) EmergencyDialog.show(c, svc); else offerLook(false);
        }).show());
        Neo.addAction(neoHeader, menu);
        menu.getLayoutParams().width = menu.getLayoutParams().height = (int) (k.menu * d);
        addView(neoHeader);

        // channel strip: arrows, big number, mode chips
        FrameLayout strip = new FrameLayout(c);
        ImageView prev = roundArrow(c, "left", Txt.s(R.string.intercom_main_prev_channel)), next = roundArrow(c, "right", Txt.s(R.string.intercom_main_next_channel));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams((int) (k.arrow * d), (int) (k.arrow * d), Gravity.START | Gravity.CENTER_VERTICAL); pl.leftMargin = (int) (k.arrowSide * d);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams((int) (k.arrow * d), (int) (k.arrow * d), Gravity.END | Gravity.CENTER_VERTICAL); nl.rightMargin = (int) (k.arrowSide * d);
        strip.addView(prev, pl); strip.addView(next, nl);
        LinearLayout mid = new LinearLayout(c); mid.setOrientation(k.chipsBeside ? HORIZONTAL : VERTICAL); mid.setGravity(k.chipsBeside ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        bigNum = Neo.text(c, "--", Neo.CYAN, k.numSp, Neo.display(c)); bigNum.setLetterSpacing(0.04f); bigNum.setPadding((int) (k.numPadH * d), (int) (k.numPadV * d), (int) (k.numPadH * d), (int) (k.numPadV * d)); bigNum.setBackground(glow(Neo.CYAN));
        mid.addView(bigNum);
        chipRow = new LinearLayout(c); chipRow.setOrientation(k.chipsBeside ? VERTICAL : HORIZONTAL); chipRow.setGravity(k.chipsBeside ? Gravity.START : Gravity.CENTER); chipRow.setPadding(0, (int) (k.chipTop * d), 0, 0);
        mid.addView(chipRow);
        strip.addView(mid, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        LayoutParams sl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); sl.topMargin = (int) (k.stripTop * d);
        if (!k.compact) addView(strip, sl);   // compact: the strip goes into the readout card below
        prev.setOnClickListener(v -> { if (svc != null) new Thread(() -> svc.stepChannel(-1)).start(); });
        next.setOnClickListener(v -> { if (svc != null) new Thread(() -> svc.stepChannel(1)).start(); });

        // readout: four cells
        LinearLayout rcard = new LinearLayout(c); rcard.setOrientation(VERTICAL); rcard.setBackground(Neo.card(c, Neo.LINE, 14));
        rcard.setPadding((int) (k.cardPadL * d), (int) (k.cardPadT * d), (int) (k.cardPadR * d), (int) (k.cardPadB * d));
        if (k.compact) { LayoutParams in = new LayoutParams(sl); in.bottomMargin = (int) (4 * d); rcard.addView(strip, in); }
        LinearLayout ro = new LinearLayout(c); ro.setBaselineAligned(false);
        readLabel = new TextView[4]; TextView[] val = new TextView[4];
        float[] wts = { 1.15f, 1.15f, 0.85f, 1f };
        for (int i = 0; i < 4; i++) {
            LinearLayout cell = new LinearLayout(c); cell.setOrientation(VERTICAL);
            readLabel[i] = Neo.caps(c, ""); readLabel[i].setTextSize(k.labelSp); cell.addView(readLabel[i]);
            val[i] = Neo.text(c, "", Neo.TEXT, k.valueSp, Neo.mono(c)); val[i].setSingleLine(true); val[i].setPadding(0, (int) (k.valuePadTop * d), 0, 0);
            cell.addView(val[i], new LayoutParams(LayoutParams.MATCH_PARENT, (int) (k.valueH * d)));
            ro.addView(cell, new LayoutParams(0, LayoutParams.WRAP_CONTENT, wts[i]));
            if (i == 3) cell.setOnLongClickListener(v -> { pickTalkgroup(); return true; });   // long-press only: no accidental change
        }
        send = val[0]; receive = val[1]; squelch = val[2]; power = val[3];
        rcard.addView(ro);
        android.view.View rdiv = new android.view.View(c); rdiv.setBackgroundColor(Neo.DIVIDER); LayoutParams rdl = new LayoutParams(LayoutParams.MATCH_PARENT, 1); rdl.setMargins(0, (int) (k.divTop * d), 0, (int) (k.divBottom * d));
        rcard.addView(rdiv, rdl);
        LayoutParams rl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); rl.setMargins(m, (int) (k.cardTop * d), m, 0);
        addView(rcard, rl);
        location = Neo.text(c, "", Neo.MUTED, 12.5f, Neo.uiSemi(c)); location.setSingleLine(true); location.setEllipsize(android.text.TextUtils.TruncateAt.END);
                location.setVisibility(GONE);

        // S-meter
        LinearLayout sm = rcard;
        LinearLayout smTop = new LinearLayout(c); smTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView sigLabel = Neo.caps(c, Txt.s(R.string.intercom_main_meter_label)); sigLabel.setTextSize(k.labelSp);
        smTop.addView(sigLabel, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        smeterText = Neo.text(c, Txt.s(R.string.intercom_main_meter_none), Neo.DIM, k.meterSp, Neo.mono(c)); smTop.addView(smeterText);
        sm.addView(smTop);
        smeter = new SMeterView(c); smeter.setCompact(k.compact); LayoutParams mlp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); mlp.topMargin = (int) (k.meterTop * d);
        sm.addView(smeter, mlp);
        signal = smeterText;
        // where the channel is (stored position): shown under the card only when there is one
        LayoutParams ll2 = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ll2.setMargins(m + (int) (4 * d), (int) (4 * d), m, 0);
        addView(location, ll2);

        // SCAN / ZONE
        LinearLayout br = new LinearLayout(c); br.setBaselineAligned(false);
        scanBtn = Neo.button(c, "SCAN", "scan", Neo.CYAN); zoneBtn = Neo.button(c, "ZONE: ALL", "zone", Neo.CYAN);   // i18n: key legend
        for (TextView t : new TextView[] { scanBtn, zoneBtn }) {
            t.setLetterSpacing(0.12f); t.setTextSize(k.scanSp); t.setMinHeight((int) (k.scanMinH * d)); t.setSingleLine(true); t.setMaxLines(1);
            if (k.compact) t.setPadding((int) (10 * d), (int) (3 * d), (int) (10 * d), (int) (3 * d));
            // long zone names: the text shrinks to fit (down to 10 sp) instead of being cut off
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(t, (int) (10 * getResources().getDisplayMetrics().scaledDensity), (int) (k.scanSp * getResources().getDisplayMetrics().scaledDensity), 1, android.util.TypedValue.COMPLEX_UNIT_PX);
        }
        LayoutParams b1 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); b1.rightMargin = (int) (4 * d);
        LayoutParams b2 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); b2.leftMargin = (int) (4 * d);
        br.addView(scanBtn, b1); br.addView(zoneBtn, b2); scanZoneRow = br;
        LayoutParams brl = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); brl.setMargins(m, (int) (k.scanTop * d), m, 0);
        addView(br, brl);
        scanBtn.setOnClickListener(v -> { if (svc != null) android.widget.Toast.makeText(c, svc.toggleScan(), android.widget.Toast.LENGTH_SHORT).show(); });
        scanBtn.setOnLongClickListener(v -> { if (svc != null) android.widget.Toast.makeText(c, svc.skipScanChannel(), android.widget.Toast.LENGTH_SHORT).show(); return true; });
        zoneBtn.setOnClickListener(v -> pickZone());

        // Soft SQ slider
        LinearLayout sq = new LinearLayout(c); sq.setGravity(Gravity.CENTER_VERTICAL);
        TextView sqLabel = Neo.caps(c, "Soft SQ"); sqLabel.setTextSize(k.sqLabelSp);   // i18n: key legend
        sq.addView(sqLabel, new LayoutParams((int) (k.sqLabelW * d), LayoutParams.WRAP_CONTENT));
        SeekBar seek = new SeekBar(c); seek.setMax(9); seek.setProgress(5); sqSeek = seek;
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Neo.CYAN)); seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Neo.TRACK)); seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Neo.CYAN));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s2, int p, boolean u) { sqValue.setText(String.valueOf(Math.max(1, p))); }
            @Override public void onStartTrackingTouch(SeekBar s2) { sqFromUser = true; }
            @Override public void onStopTrackingTouch(SeekBar s2) { sqFromUser = false; if (svc != null) svc.setSoftSqLevel(Math.max(1, s2.getProgress())); }
        });
        sq.addView(seek, new LayoutParams(0, (int) (k.sqH * d), 1f));
        sqValue = Neo.text(c, "5", Neo.CYAN, k.sqValueSp, Neo.mono(c)); sqValue.setGravity(Gravity.END);
        sq.addView(sqValue, new LayoutParams((int) (k.sqValueW * d), LayoutParams.WRAP_CONTENT));
        LayoutParams sql = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); sql.setMargins(m + (int) (2 * d), (int) (k.sqTop * d), m, 0);   // 14 dp under SCAN / ZONE: room for a thumb (was 2 dp)
        addView(sq, sql); sqRow = sq;
    }

    private static FrameLayout.LayoutParams sat(float d, int wDp, int side, int sideMarginDp, int topDp) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams((int) (wDp * d), (int) (48 * d), side | Gravity.TOP);
        if (side == Gravity.START) p.leftMargin = (int) (sideMarginDp * d); else p.rightMargin = (int) (sideMarginDp * d);
        p.topMargin = (int) (topDp * d);
        return p;
    }

    private static TextView pill(Context c, String text) {
        TextView t = Theme.label(c, text, Theme.CYAN, 12, true); t.setGravity(Gravity.CENTER);
        t.setBackground(Theme.pill(c)); t.setClickable(true);
        int p = Theme.dp(c, 6); t.setPadding(p, p, p, p);
        return t;
    }

    public void attach(RadioService s) { svc = s; s.addListener(this); updateMeter(); }

    private void editGoogleKey() {
        if (svc == null) return;
        final android.widget.EditText et = new android.widget.EditText(getContext()); et.setHint(Txt.s(R.string.intercom_main_key_hint)); et.setText(svc.googleKey());
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.intercom_main_key_title)).setMessage(Txt.s(R.string.intercom_main_key_message)).setView(et)
                .setPositiveButton(Txt.s(R.string.common_save), (d2, w) -> svc.setGoogleKey(et.getText().toString())).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private void editKeywords() {
        if (svc == null) return;
        final android.widget.EditText et = new android.widget.EditText(getContext());
        et.setHint(Txt.s(R.string.intercom_main_keywords_hint)); et.setText(svc.keywords()); et.setMinLines(3);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.intercom_main_keywords_title)).setMessage(Txt.s(R.string.intercom_main_keywords_message)).setView(et)
                .setPositiveButton(Txt.s(R.string.common_save), (d2, w) -> svc.setKeywords(et.getText().toString())).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private void pickCaptionModel() {
        if (svc == null) return;
        int cur = 1; for (int i = 0; i < com.macdmr.radio.VoskCaptions.MODELS.length; i++) if (com.macdmr.radio.VoskCaptions.MODELS[i].equals(svc.captionModel())) cur = i;
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.intercom_main_model_title)).setSingleChoiceItems(com.macdmr.radio.VoskCaptions.MODEL_LABELS, cur, null)
                .setPositiveButton(Txt.s(R.string.intercom_main_model_use), (d, w) -> {
                    int i = ((android.app.AlertDialog) d).getListView().getCheckedItemPosition();
                    if (i >= 0) { svc.setCaptionModel(com.macdmr.radio.VoskCaptions.MODELS[i]); android.widget.Toast.makeText(getContext(), Txt.s(R.string.intercom_main_model_switching), android.widget.Toast.LENGTH_LONG).show(); }
                }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private void pickZone() { if (svc != null) ZonesDialog.show(getContext(), svc, null); }
    public void detach() { if (svc != null) svc.removeListener(this); svc = null; }

    private void refreshHeadline() {
        if (neo) { refreshNeoHeadline(); return; }
        if (svc != null && svc.firmwareLoading()) { headline.setText(Txt.s(R.string.intercom_main_classic_loading)); headline.setTextColor(Theme.AMBER); }
        else if (tx) { headline.setText(Txt.s(R.string.intercom_main_classic_transmitting)); headline.setTextColor(Theme.RED); }
        else if (receivingNow()) { headline.setText(Txt.s(R.string.intercom_main_classic_receiving)); headline.setTextColor(Theme.GREEN); }
        else if (svc != null && "no root".equals(svc.link())) { headline.setText(Txt.s(R.string.intercom_main_classic_root_needed)); headline.setTextColor(Theme.AMBER); }   // i18n: svc.link() state key
        else if (svc != null && "unsupported".equals(svc.link())) { headline.setText(Txt.s(R.string.intercom_main_classic_unsupported)); headline.setTextColor(Theme.AMBER); }
        else { headline.setText(svc != null && svc.isReady() ? Txt.s(R.string.intercom_main_classic_ready) : "⏳ " + (svc != null ? svc.link() : "")); headline.setTextColor(Theme.GREEN); }
        ptt.setState(tx ? PttButton.State.TX : receivingNow() ? PttButton.State.RX : (svc != null && svc.isReady() && svc.txInhibited() ? PttButton.State.BUSY : PttButton.State.IDLE));
        board.isReceiving = rx;
    }

    private void refreshNeoHeadline() {
        String t; int col;
        if (svc != null && svc.firmwareLoading()) { t = Txt.s(R.string.intercom_main_neo_loading); col = Neo.AMBER; }
        else if (tx) { t = Txt.s(R.string.intercom_main_neo_transmitting); col = Neo.RED; }
        else if (receivingNow()) { t = Txt.s(R.string.intercom_main_neo_receiving); col = Neo.GREEN; }
        else if (svc != null && "no root".equals(svc.link())) { t = Txt.s(R.string.intercom_main_neo_root_needed); col = Neo.AMBER; }   // i18n: svc.link() state key
        else if (svc != null && "unsupported".equals(svc.link())) { t = Txt.s(R.string.intercom_main_neo_unsupported); col = Neo.AMBER; }
        else if (svc != null && svc.isReady()) { t = Txt.s(R.string.intercom_main_neo_ready); col = Neo.GREEN; }
        else { t = svc != null ? svc.link().toUpperCase(java.util.Locale.US) : ""; col = Neo.AMBER; }
        headline.setText(Txt.s(R.string.intercom_main_neo_headline, t)); headline.setTextColor(col);
        ptt.setState(tx ? PttButton.State.TX : receivingNow() ? PttButton.State.RX : (svc != null && svc.isReady() && svc.txInhibited() ? PttButton.State.BUSY : PttButton.State.IDLE));
        board.isReceiving = rx;
    }

    /** New look: number, chips and the four readout cells for the channel. */
    private void neoChannel(ChannelStore.Channel ch, boolean isVfo) {
        Context c = getContext(); float d = Theme.dp(c);
        title.setText((isVfo ? "VFO" : ch.name).toUpperCase(java.util.Locale.US));   // i18n: key legend
        bigNum.setText(isVfo ? "VFO" : String.format(java.util.Locale.US, "%02d", ch.number % 100));   // i18n: key legend (readout abbreviation)
        int numCol = isVfo ? Neo.AMBER : Neo.CYAN; bigNum.setTextColor(numCol); bigNum.setBackground(glow(numCol));
        chipRow.removeAllViews();
        TextView[] chips = { Neo.chip(c, ch.isM17() ? "M17" : ch.isDigital() ? "DMR" : "FM", ch.isM17() || ch.isDigital() ? Neo.VIOLET : Neo.CYAN), Neo.chip(c, ch.rxFreq < 300_000_000 ? "VHF" : "UHF", Neo.CYAN), Neo.chip(c, ch.power == 1 ? Txt.s(R.string.intercom_main_chip_high_power) : Txt.s(R.string.intercom_main_chip_low_power), Neo.AMBER) };
        for (int i = 0; i < chips.length; i++) {
            chips[i].setTextSize(k.chipSp); chips[i].setPadding((int) (k.chipPadH * d), (int) (k.chipPadV * d), (int) (k.chipPadH * d), (int) (k.chipPadV * d));
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            if (i > 0) { if (k.chipsBeside) lp.topMargin = (int) (k.chipGap * d); else lp.leftMargin = (int) (k.chipGap * d); }
            chipRow.addView(chips[i], lp);
        }
        readLabel[0].setText("TX"); readLabel[1].setText("RX");   // i18n: key legend (readout abbreviation)
        send.setText(ChannelsView.mhz(ch.txFreq)); receive.setText(ChannelsView.mhz(ch.rxFreq));
        if (ch.isM17()) {
            readLabel[2].setText("CAN"); squelch.setText(String.valueOf(ch.can())); power.setTextColor(Neo.TEXT);   // i18n: key legend (readout abbreviation)
            readLabel[3].setText(Txt.s(R.string.intercom_main_read_dest)); power.setText(ch.m17Dest());
        } else if (ch.isDigital()) {
            if (com.macdmr.radio.DeviceProfile.detect().timeSlots) { readLabel[2].setText("CC · TS"); squelch.setText(ch.cc + " · " + (ch.inSlot + 1)); }   // i18n: key legend (readout abbreviation)
            else { readLabel[2].setText("CC"); squelch.setText(String.valueOf(ch.cc)); }   // i18n: key legend (readout abbreviation); the Armor 3WT: no slot setting
            int picked = svc != null && !isVfo ? svc.talkgroupOverride() : 0;          // a talkgroup picked here (long-press) shows in amber
            readLabel[3].setText(picked > 0 ? Txt.s(R.string.intercom_main_read_call_picked) : Txt.s(R.string.intercom_main_read_call));
            power.setText(picked > 0 ? "TG " + picked : (ch.contactType == 1 ? "TG " : ch.contactType == 2 ? "ALL " : "ID ") + ch.txContact);   // i18n: DMR call-type abbreviation
            power.setTextColor(picked > 0 ? Neo.AMBER : Neo.TEXT);
        } else {
            readLabel[2].setText("SQ"); squelch.setText(String.valueOf(ch.sq)); power.setTextColor(Neo.TEXT);   // i18n: key legend (readout abbreviation)
            readLabel[3].setText(Txt.s(R.string.intercom_main_read_tone));
            String tone = ch.rxType == 1 && com.macdmr.radio.dsp.ToneTable.ctcssHz(ch.rxSubCode) > 0 ? String.format(java.util.Locale.US, "%.1f", com.macdmr.radio.dsp.ToneTable.ctcssHz(ch.rxSubCode)) : ch.rxType == 2 || ch.rxType == 3 ? com.macdmr.radio.dsp.ToneTable.toneLabel(ch.rxType, ch.rxSubCode) : Txt.s(R.string.intercom_main_read_tone_none);
            power.setText(tone);
        }
    }

    /** Long-press on CALL (DMR): transmit to another talkgroup without editing the channel, until the channel changes. */
    private void pickTalkgroup() {
        final ChannelStore.Channel ch = svc != null ? svc.current() : null;
        if (ch == null || !ch.isDigital()) return;
        final java.util.List<com.macdmr.radio.TalkgroupOverride.Option> opts = svc.talkgroupOptions();
        int picked = svc.talkgroupOverride();
        String[] items = new String[opts.size()];
        for (int i = 0; i < items.length; i++) {
            com.macdmr.radio.TalkgroupOverride.Option o = opts.get(i);
            boolean on = picked > 0 ? !o.programmed && o.number == picked : o.programmed;
            items[i] = (on ? "✓  " : "     ") + o.label;
        }
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.intercom_main_tg_title, ch.name))
                .setItems(items, (d, w) -> {
                    com.macdmr.radio.TalkgroupOverride.Option o = opts.get(w);
                    String err = svc.pickTalkgroup(o.programmed ? 0 : o.number);
                    if (err != null) android.widget.Toast.makeText(getContext(), err, android.widget.Toast.LENGTH_SHORT).show();
                });
        if (opts.size() == 1) {                                                 // a message would hide the list: say what to do instead
            new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.intercom_main_tg_title, ch.name))
                    .setMessage(Txt.s(R.string.intercom_main_tg_none))
                    .setPositiveButton(Txt.s(R.string.common_ok), null).show();
            return;
        }
        b.show();
    }

    // RadioService.Listener
    /** Every channel was deleted: a fresh page shows the empty state ("--") until a channel is selected again. */
    @Override public void onNoChannel() { if (getContext() instanceof com.macdmr.radio.MainActivity) post(() -> ((com.macdmr.radio.MainActivity) getContext()).rebuildIntercom()); }
    @Override public void onChannel(ChannelStore.Channel ch) {
        boolean isVfo = ch.id < 0;
        if (neo) {
            neoChannel(ch, isVfo); vfo.setOn(isVfo);
            historyHeader.setText(ch.isM17() ? "M17" : ch.isDigital() ? Txt.s(R.string.intercom_main_neo_history_dmr) : Txt.s(R.string.intercom_main_neo_history_analog));
            softSq.setVisibility(ch.isDigital() || ch.isM17() ? INVISIBLE : VISIBLE);
            chanFm = !ch.isDigital() && !ch.isM17(); showSqRow(softSq.isOn());
            history.clear();
            if (svc != null && !panelClosed) for (String l : svc.store().recentHistory(ch.number, 3)) history.addLast(l);
            renderHistory(); refreshHeadline();
            return;
        }
        title.setText(ch.name + (ch.rxFreq < 300_000_000 ? "(VHF)" : "(UHF)"));   // i18n: band name
        int shown = ch.number % 100;
        digitOne.setImageResource(DIGITS[shown / 10]); digitTwo.setImageResource(DIGITS[shown % 10]);
        digitOne.setVisibility(isVfo ? GONE : VISIBLE); digitTwo.setVisibility(isVfo ? GONE : VISIBLE); vfoLabel.setVisibility(isVfo ? VISIBLE : GONE);
        vfo.setOn(isVfo);
        power.setText(ch.power == 1 ? Txt.s(R.string.intercom_main_classic_power_high) : Txt.s(R.string.intercom_main_classic_power_low));
        squelch.setText(ch.isM17() ? Txt.s(R.string.intercom_main_classic_can, String.valueOf(ch.can()), ch.m17Dest()) : ch.isDigital() ? Txt.s(R.string.intercom_main_classic_color_code, String.valueOf(ch.cc)) : Txt.s(R.string.intercom_main_classic_squelch_level, String.valueOf(ch.sq)));
        send.setText(Txt.s(R.string.intercom_main_classic_send_value, ch.freqText(ch.txFreq))); receive.setText(Txt.s(R.string.intercom_main_classic_receive_value, ch.freqText(ch.rxFreq)));
        historyHeader.setText(ch.isM17() ? "M17" : ch.isDigital() ? Txt.s(R.string.intercom_main_classic_history_dmr) : Txt.s(R.string.intercom_main_classic_history_analog));
        softSq.setVisibility(ch.isDigital() || ch.isM17() ? INVISIBLE : VISIBLE);
        history.clear();
        if (svc != null && !panelClosed) for (String l : svc.store().recentHistory(ch.number, 3)) history.addLast(l);
        renderHistory();
        refreshHeadline();
    }
    @Override public void onRx(boolean receiving) { boolean was = rx; rx = receiving; if (receiving && !was && (svc == null || svc.rxReportsReception())) reopenPanel("rx"); refreshHeadline(); updateMeter(); }
    @Override public void onTx(boolean transmitting) { tx = transmitting; if (transmitting) reopenPanel("tx"); refreshHeadline(); updateMeter(); }
    private int lastDbm = -999;
    /** Is something being received right now? The Soft SQ gate when Soft SQ decides, else the radio's own report (sq 0 analog: always). */
    private boolean receivingNow() {
        if (tx || svc == null) return false;
        if (svc.softSqActive()) return gateWasOpen;
        return svc.rxReportsReception() ? rx : true;
    }
    /** New look: the meter shows a reading only while something is received; otherwise it is dark and says NO SIGNAL, with the noise floor beside it. */
    private void updateMeter() {
        if (svc != null && !svc.profile().rssi) {                            // no signal readings (the W6): say whether something is received, no bars
            boolean r = receivingNow();
            if (!neo) { signal.setText(r ? Txt.s(R.string.intercom_main_classic_signal_receiving) : Txt.s(R.string.intercom_main_classic_signal_none)); return; }
            smeter.setVisibility(GONE);
            smeterText.setText(r ? Txt.s(R.string.intercom_main_meter_receiving) : Txt.s(R.string.intercom_main_meter_quiet)); smeterText.setTextColor(r ? Neo.GREEN_LIGHT : Neo.DIM); smeterText.setTextSize(r ? 17 : 12.5f);
            return;
        }
        if (!neo) { boolean on2 = receivingNow() && lastDbm != -999; signal.setText(on2 ? Txt.s(R.string.intercom_main_classic_signal_dbm, lastDbm) : Txt.s(R.string.intercom_main_classic_signal_none)); return; }   // classic: the yellow box reads only while something is heard
        boolean on = receivingNow() && lastDbm != -999;
        smeter.setDbm(on ? lastDbm : -999);
        if (on) { smeterText.setText(Txt.s(R.string.intercom_main_meter_dbm, SMeterView.sUnits(lastDbm), lastDbm)); smeterText.setTextColor(lastDbm > -93 ? Neo.AMBER_LIGHT : Neo.TEXT); smeterText.setTextSize(k.meterSp); }
        else { smeterText.setText(lastDbm == -999 ? Txt.s(R.string.intercom_main_meter_none) : Txt.s(R.string.intercom_main_meter_floor, lastDbm)); smeterText.setTextColor(Neo.DIM); smeterText.setTextSize(k.meterQuietSp); }
    }
    @Override public void onRssi(int dbm) {
        lastDbm = dbm; updateMeter();

    }
    @Override public void onAudioLevel(int amplitude) { board.audioAmplitude = amplitude; }
    @Override public void onSpectrum(float[] bands) { board.spectrum = bands; }
    @Override public void onHistory(String line) { reopenPanel("history: " + line); if (!history.isEmpty() && history.peekLast().equals(line)) return; history.addLast(line); while (history.size() > 3) history.removeFirst(); renderHistory(); }   // i18n: log reason (reopenPanel logs it)
    @Override public void onCaller(String text) { reopenPanel("caller: " + text); headline.setText(neo ? Txt.s(R.string.intercom_main_neo_headline, text) : Txt.s(R.string.intercom_main_classic_caller, text)); }   // i18n: log reason (reopenPanel logs it)
    @Override public void onLog(String s) { }
    @Override public void onLink(String state) { refreshHeadline(); }
    @Override public void onSms(int from, int type, String text) { }
    @Override public void onSmsResult(boolean ok) { }
    @Override public void onLocation(String text) { location.setText(text); if (neo) location.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE); }
    @Override public void onCaption(boolean enabled, String live, String sentence) {
        txt.setOn(enabled);
        transcriptionBox.setVisibility(enabled ? VISIBLE : GONE);
        board.barsCompact = enabled;
        if (sentence != null) { captionLines.addLast(new java.text.SimpleDateFormat("[HH:mm:ss] ", java.util.Locale.US).format(new java.util.Date()) + sentence); while (captionLines.size() > 4) captionLines.removeFirst(); }
        StringBuilder sb = new StringBuilder(); for (String l : captionLines) { if (sb.length() > 0) sb.append('\n'); sb.append(l); }
        transcriptionText.setText(sb.toString()); transcriptionText.setVisibility(sb.length() == 0 ? GONE : VISIBLE);
        captionLive.setText(live == null ? "" : live); captionLive.setVisibility(live == null || live.isEmpty() ? GONE : VISIBLE);
        captionScroll.post(() -> captionScroll.fullScroll(android.view.View.FOCUS_DOWN));
    }
    /** The slider only while Soft SQ is on and the channel is FM; the activity panel takes the room otherwise (the talk key does not move). */
    private void showSqRow(boolean sqOn) {
        if (sqRow == null) return;
        boolean show = sqOn && chanFm;
        if (show == sqShown) return;
        sqShown = show; sqRow.setVisibility(show ? VISIBLE : GONE);
    }
    private boolean gateWasOpen;
    private int lastScanState = -1;
    @Override public void onModes(boolean sq, int level, boolean open, boolean monitor, boolean recording, boolean scanning, boolean holding, String zoneName) {
        if (sq && open && !gateWasOpen) reopenPanel("soft squelch opened");   // i18n: log reason (reopenPanel logs it)
        boolean was = gateWasOpen; gateWasOpen = sq && open;   // Soft SQ channels: the gate opening is the reception
        if (was != gateWasOpen) { updateMeter(); refreshHeadline(); }   // headline and talk key follow what is actually heard
        softSq.setOn(sq); mon.setOn(monitor); rec.setOn(recording); pkt.setOn(svc != null && svc.packetActive());
        showSqRow(sq);
        if (!sqFromUser) { sqSeek.setProgress(level); sqValue.setText(String.valueOf(level)); }
        sqValue.setTextColor(sq ? (open ? Theme.GREEN : Theme.AMBER) : Theme.CYAN);
        zoneBtn.setText("ZONE: " + zoneName.toUpperCase(java.util.Locale.US));   // i18n: key legend
        if (neo) {
            if (!zoneBtn.getText().toString().equals(lastZoneText)) { lastZoneText = zoneBtn.getText().toString(); rebalanceScanZone(); }
            this.zoneName = zoneName; Neo.headerSubtitle(neoHeader).setText(Txt.s(R.string.intercom_main_zone_subtitle, zoneName).toUpperCase(java.util.Locale.US));
            sqValue.setTextColor(sq ? (open ? Neo.GREEN : Neo.AMBER) : Neo.CYAN);
            int scanState = scanning ? (holding ? 2 : 1) : 0;
            if (scanState == lastScanState) return;   // pushModes runs on every Soft SQ gate change: rebuild the SCAN key only when it changes
            lastScanState = scanState;
            Context c = getContext(); int col = scanning ? Neo.AMBER : Neo.CYAN;
            scanBtn.setText(scanning ? (holding ? "HOLD" : "SCANNING") : "SCAN");   // i18n: key legend scanBtn.setTextColor(scanning ? Neo.AMBER_LIGHT : Neo.CYAN_LIGHT);
            Neo.IconDrawable ic = new Neo.IconDrawable(scanning ? "stop" : "scan", scanBtn.getCurrentTextColor()); int sz = Theme.dp(c, 18); ic.setBounds(0, 0, sz, sz); scanBtn.setCompoundDrawables(ic, null, null, null);
            android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
            st.addState(new int[] { android.R.attr.state_pressed }, Neo.outline(c, col, 0x40, 12, 1.5f)); st.addState(new int[] { }, Neo.outline(c, col, scanning ? 0x30 : 0x18, 12, 1.5f));
            scanBtn.setBackground(st);
            return;
        }
        scanBtn.setText(scanning ? (holding ? "\u23F9 HOLD" : "\u23F9 SCANNING") : "\uD83D\uDD0D SCAN");   // i18n: key legend
        scanBtn.setTextColor(scanning ? Theme.AMBER : Theme.CYAN);
        scanBtn.setBackground(scanning ? Theme.gradient(getContext(), 0xFF4A3A0D, 0xFF80651A, Theme.AMBER, 2, 8) : Theme.pill(getContext()));
    }

    private void renderHistory() {
        StringBuilder sb = new StringBuilder();
        for (String l : history) { if (sb.length() > 0) sb.append('\n'); sb.append(l); }
        historyText.setText(sb.toString());
    }
}
