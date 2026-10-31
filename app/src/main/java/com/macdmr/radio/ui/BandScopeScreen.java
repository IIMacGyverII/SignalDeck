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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.macdmr.radio.BandScope;
import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.RadioService;

import java.util.List;
import java.util.Locale;

/** PKT → Band scope: the live sweep graph with peak hold, the waterfall, peaks, tap to read or listen. */
public final class BandScopeScreen extends ModeScreen {
    private final ScopeView view;
    private final TextView info, peaks;
    private TextView startBtn;
    private int presetIdx;

    public BandScopeScreen(Context c, RadioService svc, Runnable onClose) {
        super(c, svc, Txt.s(R.string.modes_scope_title), "signal", Neo.GREEN, onClose);
        BandScope s = svc.scope();
        presetIdx = c.getSharedPreferences("scope", Context.MODE_PRIVATE).getInt("preset", 0);
        LinearLayout col = new LinearLayout(c); col.setOrientation(VERTICAL); col.setPadding(dp(14), dp(4), dp(14), dp(4));
        view = new ScopeView(c);
        col.addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        info = Neo.text(c, "", Neo.CYAN_LIGHT, 15, Neo.mono(c)); info.setPadding(0, dp(8), 0, dp(2)); col.addView(info);
        peaks = Neo.text(c, "", Neo.MUTED, 13.5f, Neo.uiMedium(c)); peaks.setLineSpacing(0, 1.1f); col.addView(peaks);
        content.addView(col);
        if (presetIdx < 0 || presetIdx > BandScope.PRESETS.length || (presetIdx < BandScope.PRESETS.length && !BandScope.fits(BandScope.PRESETS[presetIdx]))) presetIdx = BandScope.firstFitting();   // never start on a band this radio cannot tune (2 m on a UHF-only radio)
        buildButtons();
        setSubtitle(Txt.s(R.string.modes_scope_subtitle));
        refresh();
    }

    @Override protected boolean stillRunning() { return true; }          // the page stays when the sweep stops (Back closes it)
    @Override protected void refresh() {
        BandScope s = svc.scope();
        boolean on = s.running();
        String l = Txt.s(on ? R.string.common_stop : R.string.common_start); if (!l.contentEquals(startBtn.getText())) { startBtn.setText(l); Neo.fit(startBtn); }
        int nSteps = BandScope.steps(s.from(), s.to(), s.step());
        if (on) {
            String f0 = String.format(Locale.US, "%.3f", s.from() / 1e6), f1 = String.format(Locale.US, "%.3f", s.to() / 1e6), about = s.sweepMs() > 0 ? "" : "≈", sweep = BandScope.duration(s.estimateMs());
            setSubtitle(s.partialIndex() >= 0 ? Txt.s(R.string.modes_scope_running_step, f0, f1, nSteps, khz(s.step()), about, sweep, s.partialIndex() + 1) : Txt.s(R.string.modes_scope_running, f0, f1, nSteps, khz(s.step()), about, sweep));
        }
        else setSubtitle(Txt.s(R.string.modes_scope_subtitle));
        showLive();
        float[] last = s.last();
        if (last != null) {
            StringBuilder b = new StringBuilder();
            for (int[] p : firstPeaks(BandScope.peaks(last, s.from(), s.step(), 8), 5)) {
                ChannelStore.Channel ch = channelAt(p[0]);
                b.append(b.length() > 0 ? "\n" : "").append(String.format(Locale.US, "%.4f MHz  %d dBm", p[0] / 1e6, p[1])).append(ch == null ? "" : "  · " + ch.name);   // i18n: frequency and level, units only
            }
            set(peaks, b.length() == 0 ? Txt.s(R.string.modes_scope_no_peaks) : Txt.s(R.string.modes_scope_peaks, b.toString()));
        } else set(peaks, on ? Txt.s(R.string.modes_scope_first_sweep) : Txt.s(R.string.modes_scope_intro));
        view.invalidate();
    }
    private static List<int[]> firstPeaks(List<int[]> p, int n) { p.sort((a, b) -> b[1] - a[1]); return p.size() > n ? p.subList(0, n) : p; }
    private ChannelStore.Channel channelAt(int hz) { for (ChannelStore.Channel ch : svc.store().all()) if (!ch.isDigital() && Math.abs(ch.rxFreq - hz) <= svc.scope().step() / 2) return ch; return null; }

    private android.content.SharedPreferences prefs() { return c.getSharedPreferences("scope", Context.MODE_PRIVATE); }
    private boolean custom() { return presetIdx == BandScope.PRESETS.length; }
    /** The range to sweep: a preset, or the custom one (from, to, step in Hz). */
    private int[] range() {
        if (!custom()) { BandScope.Preset p = BandScope.PRESETS[presetIdx]; return new int[] { p.from, p.to, p.step }; }
        return new int[] { prefs().getInt("customFrom", 446_000_000), prefs().getInt("customTo", 446_200_000), prefs().getInt("customStep", 12_500) };
    }
    private String rangeLabel() {
        if (!custom()) return BandScope.PRESETS[presetIdx].label();
        int[] r = range(); return String.format(Locale.US, "%.3f–%.3f", r[0] / 1e6, r[1] / 1e6);
    }
    private void buildButtons() {
        footer.removeAllViews();
        LinearLayout r1 = buttonRow();
        button(r1, rangeLabel(), "scan", Neo.CYAN, v -> choosePreset());
        startBtn = button(r1, Txt.s(svc.scope().running() ? R.string.common_stop : R.string.common_start), "signal", Neo.GREEN, v -> toggle());
        LinearLayout r2 = buttonRow();
        button(r2, Txt.s(R.string.modes_scope_listen_here), "radio", Neo.AMBER, v -> listenHere());
        button(r2, Txt.s(R.string.modes_scope_clear_peaks), "restore", Neo.VIOLET, v -> { svc.scope().clearPeak(); view.invalidate(); });
    }

    private void toggle() {
        BandScope s = svc.scope();
        if (s.running()) { new Thread(svc::scopeStop, "scope-stop").start(); return; }
        int[] r = range();
        toast(svc.scopeStart(r[0], r[1], r[2], prefs().getInt("settle", svc.profile().scopeSettleMs)));
        refresh();
    }
    /** Use a new range: restart the sweep if one is running. */
    private void picked(int idx) {
        presetIdx = idx; prefs().edit().putInt("preset", idx).apply();
        buildButtons();
        if (svc.scope().running()) new Thread(() -> { svc.scopeStop(); post(this::toggle); }, "scope-restart").start();
        refresh();
    }
    private void choosePreset() { choosePreset(false); }
    /** The region's bands (no NOAA weather band where there is no weather radio), Custom, and Show all when some are left out. */
    private void choosePreset(boolean all) {
        java.util.List<Integer> idx = BandScope.offered(com.macdmr.radio.region.Region.current(c), all);
        idx.removeIf(i -> !BandScope.fits(BandScope.PRESETS[i]));        // only bands this radio tunes
        int inBand = 0; for (BandScope.Preset p : BandScope.PRESETS) if (BandScope.fits(p)) inBand++;
        final boolean more = idx.size() < inBand;
        int k = idx.size(); String[] n = new String[k + (more ? 2 : 1)];
        for (int j = 0; j < k; j++) { BandScope.Preset p = BandScope.PRESETS[idx.get(j)]; int st = BandScope.steps(p.from, p.to, p.step); n[j] = Txt.p(R.plurals.modes_scope_preset_item, st, p.label(), String.format(Locale.US, "%.3f", p.from / 1e6), String.format(Locale.US, "%.3f", p.to / 1e6), khz(p.step), st, BandScope.duration((long) st * (BandScope.DEFAULT_SETTLE_MS + 300))); }
        n[k] = Txt.s(R.string.modes_scope_custom_item); if (more) n[k + 1] = Txt.s(R.string.modes_scope_show_all);
        new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_scope_which_band)).setItems(n, (dd, w) -> { if (w == k) customRange(); else if (w == k + 1) choosePreset(true); else picked(idx.get(w)); }).show();
    }
    /** From, to and step of your own; checked against what the radio can tune before it is used. */
    private void customRange() {
        LinearLayout b = Neo.sheetBody(c, Txt.s(R.string.modes_scope_custom_title), "radio");
        b.addView(Neo.sheetHint(c, Txt.s(R.string.modes_scope_custom_hint, com.macdmr.radio.DeviceProfile.detect().bandsText(), BandScope.MAX_STEPS)));
        int[] r = range();
        final android.widget.EditText from = field(b, Txt.s(R.string.modes_scope_from), String.format(Locale.US, "%.4f", (custom() ? r[0] : prefs().getInt("customFrom", 446_000_000)) / 1e6));
        final android.widget.EditText to = field(b, Txt.s(R.string.modes_scope_to), String.format(Locale.US, "%.4f", (custom() ? r[1] : prefs().getInt("customTo", 446_200_000)) / 1e6));
        final android.widget.EditText step = field(b, Txt.s(R.string.modes_scope_step), khz(custom() ? r[2] : prefs().getInt("customStep", 12_500)));
        final TextView est = Neo.sheetHint(c, ""); b.addView(est);
        final Runnable check = () -> set(est, describe(parse(from), parse(to), parseKhz(step)));
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a1, int a2, int a3) { }
            @Override public void onTextChanged(CharSequence x, int a1, int a2, int a3) { }
            @Override public void afterTextChanged(android.text.Editable e) { check.run(); }
        };
        from.addTextChangedListener(tw); to.addTextChangedListener(tw); step.addTextChangedListener(tw); check.run();
        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        TextView use = Neo.button(c, Txt.s(R.string.modes_scope_use), "check", Neo.GREEN);
        use.setOnClickListener(v -> {
            int f = parse(from), t = parse(to), st = parseKhz(step);
            String e = BandScope.problem(f, t, st); if (e != null) { toast(e); return; }
            prefs().edit().putInt("customFrom", f).putInt("customTo", t).putInt("customStep", st).apply();
            dlg[0].dismiss(); picked(BandScope.PRESETS.length);
        });
        LinearLayout row = new LinearLayout(c); row.addView(use, new LinearLayout.LayoutParams(0, dp(48), 1f)); b.addView(row);
        dlg[0] = Neo.showSheet(c, b);
    }
    private android.widget.EditText field(LinearLayout b, String label, String value) {
        b.addView(Neo.sheetSection(c, label));
        android.widget.EditText e = new android.widget.EditText(c); e.setTextColor(Neo.TEXT); e.setTypeface(Neo.mono(c)); e.setSingleLine(true); e.setText(value);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); b.addView(e); return e;
    }
    private static int parse(android.widget.EditText e) { try { return (int) Math.round(Double.parseDouble(e.getText().toString().trim().replace(',', '.')) * 1e6); } catch (Exception x) { return 0; } }
    private static int parseKhz(android.widget.EditText e) { try { return (int) Math.round(Double.parseDouble(e.getText().toString().trim().replace(',', '.')) * 1e3); } catch (Exception x) { return 0; } }
    private String describe(int from, int to, int step) {
        String e = BandScope.problem(from, to, step); if (e != null) return e;
        int n = BandScope.steps(from, to, step);
        return Txt.p(R.plurals.modes_scope_estimate, n, n, BandScope.duration((long) n * (svc.profile().scopeSettleMs + svc.profile().scopeReadMs)));
    }

    private void listenHere() {
        if (view.selHz <= 0) { toast(Txt.s(R.string.modes_scope_tap_first)); return; }
        final int hz = view.selHz;
        new Thread(() -> {
            svc.scopeStop();
            post(() -> {
                ChannelStore.Channel v = new ChannelStore.Channel(); v.type = 1; v.rxFreq = v.txFreq = hz; v.band = 1; v.power = 0; v.sq = 2; v.name = "";
                svc.applyVfo(v); toast(Txt.s(R.string.modes_scope_listening, String.format(Locale.US, "%.4f", hz / 1e6))); close();
            });
        }, "scope-listen").start();
    }

    @Override public void onScope(int from, int step, float[] dbm) { refresh(); }
    @Override protected long liveHz() { return svc.scope().currentHz(); }
    static String khz(int hz) { return hz % 1000 == 0 ? String.valueOf(hz / 1000) : String.format(Locale.US, "%.2f", hz / 1000.0).replaceAll("0+$", ""); }
    @Override public void onScopeState(String state) { refresh(); }

    /** Graph (top 55 %) and waterfall (bottom). */
    private final class ScopeView extends View {
        final Paint grid = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), hold = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(), cursor = new Paint();
        static final float TOP = -40, BOTTOM = -125;
        int selHz = -1; float selX = -1;
        ScopeView(Context c) {
            super(c);
            grid.setColor(0x33FFFFFF); grid.setStrokeWidth(1);
            line.setColor(Neo.CYAN); line.setStrokeWidth(dp(2)); line.setStyle(Paint.Style.STROKE);
            hold.setColor(Neo.AMBER); hold.setStrokeWidth(dp(1)); hold.setStyle(Paint.Style.STROKE); hold.setAlpha(170);
            text.setColor(Neo.DIM); text.setTextSize(dp(10)); text.setTypeface(Neo.mono(c));
            cursor.setColor(Neo.GREEN); cursor.setStrokeWidth(dp(1.5f));
            setContentDescription(Txt.s(R.string.modes_scope_graph_description));
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            BandScope s = svc.scope(); int n = BandScope.steps(s.from(), s.to(), s.step()); if (n < 2) return true;
            float x = Math.max(0, Math.min(getWidth(), e.getX()));
            int i = Math.round(x / getWidth() * (n - 1)); selHz = s.from() + i * s.step(); selX = (float) i / (n - 1) * getWidth();
            float[] last = s.last(); float v = last != null && i < last.length ? last[i] : Float.NaN;
            ChannelStore.Channel ch = channelAt(selHz);
            info.setText(String.format(Locale.US, "%.4f MHz  %s", selHz / 1e6, Float.isNaN(v) ? "" : Math.round(v) + " dBm") + (ch == null ? "" : "  · " + ch.name));   // i18n: frequency and level, units only
            invalidate(); return true;
        }
        float y(float dbm, float h) { float t = (Math.max(BOTTOM, Math.min(TOP, dbm)) - BOTTOM) / (TOP - BOTTOM); return h * (1 - t); }
        int heat(float dbm) {                                                 // waterfall colour: dark → cyan → green → amber → red
            if (Float.isNaN(dbm)) return 0xFF0A1420;
            float t = Math.max(0, Math.min(1, (dbm + 118) / 50f));
            int[] st = { 0xFF0A1420, 0xFF0E4E6A, 0xFF19D3FF, 0xFF2BEA7A, 0xFFFFB020, 0xFFFF5468 };
            float p = t * (st.length - 1); int k = Math.min(st.length - 2, (int) p); float f = p - k;
            int a = st[k], b = st[k + 1];
            return 0xFF000000 | ((int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f) << 16) | ((int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f) << 8) | (int) ((a & 255) * (1 - f) + (b & 255) * f);
        }
        @Override protected void onDraw(Canvas cv) {
            BandScope s = svc.scope();
            int w = getWidth(), h = getHeight(); float gh = h * 0.55f, wt = gh + dp(6), wh = h - wt;
            for (float db = -120; db <= -40; db += 20) { float yy = y(db, gh); cv.drawLine(0, yy, w, yy, grid); cv.drawText((int) db + "", dp(2), yy - dp(2), text); }
            int n = BandScope.steps(s.from(), s.to(), s.step());
            if (n >= 2) {
                for (int k = 0; k <= 4; k++) { float xx = w * k / 4f; cv.drawLine(xx, 0, xx, gh, grid); String l = String.format(Locale.US, "%.3f", (s.from() + (s.to() - s.from()) * k / 4.0) / 1e6); float tw = text.measureText(l); cv.drawText(l, Math.max(0, Math.min(w - tw, xx - tw / 2)), gh - dp(2), text); }
                float[] pk = s.peak(); if (pk != null) cv.drawPath(path(pk, w, gh), hold);
                float[] last = s.last(), part = s.partial(); int pi = s.partialIndex();
                if (last != null) { line.setAlpha(part != null && pi >= 0 ? 90 : 255); cv.drawPath(path(last, w, gh), line); }
                if (part != null && pi >= 0) {                                 // the sweep in progress, step by step
                    line.setAlpha(255); cv.drawPath(path(part, w, gh), line);
                    float px = n == 1 ? 0 : (float) pi / (n - 1) * w; cursor.setAlpha(120); cv.drawLine(px, 0, px, gh, cursor); cursor.setAlpha(255);
                }
                List<float[]> hist = s.history();
                if (!hist.isEmpty()) {
                    float rowH = Math.max(1, wh / BandScope.HISTORY), cw = (float) w / n;
                    for (int r = 0; r < hist.size(); r++) {
                        float[] row = hist.get(hist.size() - 1 - r); float top = wt + r * rowH; if (top > h) break;
                        for (int i = 0; i < row.length; i++) { fill.setColor(heat(row[i])); cv.drawRect(i * cw, top, (i + 1) * cw + 1, top + rowH + 1, fill); }
                    }
                }
                if (selX >= 0) cv.drawLine(selX, 0, selX, h, cursor);
            }
        }
        Path path(float[] v, int w, float gh) {
            Path p = new Path(); boolean pen = false;
            for (int i = 0; i < v.length; i++) { if (Float.isNaN(v[i])) { pen = false; continue; } float x = v.length == 1 ? 0 : (float) i / (v.length - 1) * w, yy = y(v[i], gh); if (!pen) { p.moveTo(x, yy); pen = true; } else p.lineTo(x, yy); }
            return p;
        }
    }
}
