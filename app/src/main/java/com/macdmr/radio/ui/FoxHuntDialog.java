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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.ham.FoxHunt;
import com.macdmr.radio.ham.FoxPredictor;
import com.macdmr.radio.ham.FoxSignalGate;
import com.macdmr.radio.ham.SpinBearing;

import java.util.List;
import java.util.Locale;

/**
 * Fox hunt: live signal strength (big number, S-meter, peak hold, an optional rising beep), the compass bearing the
 * phone points at, Mark to save a reading, a map of the hunt with the prediction layers (bearing lines, the signal
 * heat map with its 50 % and 90 % regions, the combined estimate; the compact plot when no map tiles load), the
 * off-frequency trick for when the signal is too strong, and Be the fox (a beacon every so often on this channel).
 */
public final class FoxHuntDialog {
    private FoxHuntDialog() { }

    public static void show(Context c, RadioService svc) {
        if (svc == null) return;
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_fox_title), "radio");
        ChannelStore.Channel ch = svc.current();
        body.addView(Neo.sheetHint(c, ch == null ? Txt.s(R.string.modes_fox_hint_no_channel) : Txt.s(R.string.modes_fox_hint, ch.name, Txt.freqMhz(ch.rxFreq, 4))));

        final TextView big = Neo.text(c, "--", Neo.CYAN, 56, Neo.display(c)); big.setGravity(Gravity.CENTER);
        body.addView(big);
        final TextView sub = Neo.text(c, "", Neo.MUTED, 14, Neo.uiMedium(c)); sub.setGravity(Gravity.CENTER); body.addView(sub);
        final SMeterView meter = new SMeterView(c); body.addView(meter);
        final TextView info = Neo.label(c, "", 0xFFBFC8D0, 13, false); info.setPadding(0, (int) (6 * d), 0, (int) (4 * d)); body.addView(info);

        // ---- prediction: which layers, the map (or the plot when no tiles load) ----
        final android.content.SharedPreferences fp = c.getSharedPreferences("fox", Context.MODE_PRIVATE);
        final boolean[] layerOn = { fp.getBoolean("predBearings", true), fp.getBoolean("predHeat", true), fp.getBoolean("predTerrain", false) };
        final String[] layerKey = { "predBearings", "predHeat", "predTerrain" }, layerName = { Txt.s(R.string.modes_fox_pill_bearings), Txt.s(R.string.modes_fox_pill_heat), Txt.s(R.string.modes_fox_pill_terrain) };
        final int[] layerAccent = { Neo.CYAN, Neo.AMBER, Neo.GREEN };
        body.addView(Neo.sheetSection(c, Txt.s(R.string.modes_fox_prediction)));
        LinearLayout pills = new LinearLayout(c);
        final TextView[] pill = new TextView[layerOn.length];
        for (int i = 0; i < pill.length; i++) {
            pill[i] = Neo.label(c, layerName[i], layerAccent[i], 14, true); pill[i].setGravity(Gravity.CENTER);
            pill[i].setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d)); Neo.pillStyle(pill[i], layerOn[i], layerAccent[i]);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); pl.setMargins((int) (3 * d), 0, (int) (3 * d), 0);
            pills.addView(pill[i], pl);
        }
        body.addView(pills);
        // terrain: the download (once per area) and how high the fox's antenna is
        final double[] aglChoices = { FoxPredictor.FOX_AGL, 10, 30 }; final String[] aglNames = { Txt.s(R.string.modes_fox_agl_ground), Txt.s(R.string.modes_fox_agl_roof), Txt.s(R.string.modes_fox_agl_tower) };
        final int[] agl = { Math.max(0, Math.min(2, fp.getInt("foxAgl", 0))) };
        final LinearLayout terrRow = new LinearLayout(c); terrRow.setBaselineAligned(false);
        final TextView aglBtn = Neo.button(c, aglNames[agl[0]], null, Neo.GREEN), dlBtn = Neo.button(c, Txt.s(R.string.modes_fox_download_terrain), "globe", Neo.GREEN);
        final String[] dlMb = { "" };                                      // the download size in the button, for the dialog
        for (TextView b : new TextView[] { aglBtn, dlBtn }) { b.setTextSize(13.5f); Neo.fit(b); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins((int) (3 * d), (int) (8 * d), (int) (3 * d), 0); terrRow.addView(b, lp); }
        dlBtn.setVisibility(View.GONE);
        terrRow.setVisibility(layerOn[2] ? View.VISIBLE : View.GONE);
        body.addView(terrRow);
        final TextView predNote = Neo.label(c, "", Neo.DIM, 12.5f, false); predNote.setPadding(0, (int) (6 * d), 0, (int) (6 * d)); body.addView(predNote);

        final android.widget.FrameLayout view = new android.widget.FrameLayout(c);
        final MapView mapView = new MapView(c);
        final PlotView plot = new PlotView(c, svc.fox());
        final HuntLayer layer = new HuntLayer(c);
        mapView.setOverlay(layer);
        view.addView(mapView); view.addView(plot); plot.setVisibility(View.GONE);
        final TextView swap = Neo.chip(c, Txt.s(R.string.modes_fox_chip_plot), Neo.CYAN);
        android.widget.FrameLayout.LayoutParams sl0 = new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        sl0.setMargins((int) (8 * d), (int) (8 * d), 0, 0); view.addView(swap, sl0);
        body.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (300 * d)));
        swap.setOnClickListener(v -> { boolean toPlot = plot.getVisibility() != View.VISIBLE; plot.setVisibility(toPlot ? View.VISIBLE : View.GONE); mapView.setVisibility(toPlot ? View.GONE : View.VISIBLE); swap.setText(Txt.s(toPlot ? R.string.modes_fox_chip_map_caps : R.string.modes_fox_chip_plot_caps)); });
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_fox_rough_hint)));

        LinearLayout r1 = new LinearLayout(c); r1.setBaselineAligned(false);
        TextView mark = Neo.button(c, Txt.s(R.string.modes_fox_mark), "locate", Neo.GREEN), beep = Neo.button(c, Txt.s(R.string.modes_fox_beeper_off), "volume", Neo.CYAN), off = Neo.button(c, Txt.s(R.string.modes_fox_off_freq), "vfo", Neo.AMBER);
        for (TextView b : new TextView[] { mark, beep, off }) { b.setTextSize(14); Neo.fit(b); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins((int) (3 * d), (int) (8 * d), (int) (3 * d), 0); r1.addView(b, lp); }
        body.addView(r1);
        LinearLayout r2 = new LinearLayout(c); r2.setBaselineAligned(false);
        TextView spin = Neo.button(c, Txt.s(R.string.modes_fox_spin), "spin", Neo.VIOLET), map = Neo.button(c, Txt.s(R.string.modes_fox_open_maps), "map", Neo.CYAN), clear = Neo.button(c, Txt.s(R.string.modes_fox_clear_marks), "close", Neo.RED);
        for (TextView b : new TextView[] { spin, map, clear }) { b.setTextSize(14); Neo.fit(b); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins((int) (3 * d), (int) (6 * d), (int) (3 * d), 0); r2.addView(b, lp); }
        body.addView(r2);
        // ---- which readings count: any signal, or only the fox's own transmissions (its tone, or its DMR ID and colour code) ----
        final FoxSignalGate gate = new FoxSignalGate();
        final boolean[] foxOnly = { fp.getBoolean("foxOnly", false) };
        final double[] foxTone = { fp.getFloat("foxTone", -1) };            // -1 = the channel's tone squelch
        final int[] foxId = { fp.getInt("foxId", 0) }, foxCc = { fp.getInt("foxCc", -1) };
        final int[] autoM = { fp.getInt("autoMarkM", 0) };                   // 0 = auto-marks off
        body.addView(Neo.sheetSection(c, Txt.s(R.string.modes_fox_signal_section)));
        LinearLayout fsRow = new LinearLayout(c);
        final TextView anyPill = Neo.label(c, Txt.s(R.string.modes_fox_any_signal), Neo.CYAN, 14, true), onlyPill = Neo.label(c, Txt.s(R.string.modes_fox_only_fox), Neo.VIOLET, 14, true);
        for (TextView t : new TextView[] { anyPill, onlyPill }) { t.setGravity(Gravity.CENTER); t.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d)); LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); pl.setMargins((int) (3 * d), 0, (int) (3 * d), 0); fsRow.addView(t, pl); }
        body.addView(fsRow);
        LinearLayout fsRow2 = new LinearLayout(c); fsRow2.setBaselineAligned(false);
        final TextView setFox = Neo.button(c, Txt.s(R.string.modes_fox_set_fox), "radio", Neo.VIOLET), autoBtn = Neo.button(c, Txt.s(R.string.modes_fox_auto_off), "locate", Neo.GREEN);
        for (TextView b : new TextView[] { setFox, autoBtn }) { b.setTextSize(13.5f); Neo.fit(b); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins((int) (3 * d), (int) (8 * d), (int) (3 * d), 0); fsRow2.addView(b, lp); }
        body.addView(fsRow2);
        final TextView foxState = Neo.label(c, "", Neo.MUTED, 12.5f, false); foxState.setPadding(0, (int) (6 * d), 0, 0); body.addView(foxState);
        final Runnable applyFox = () -> {
            FoxSignalGate.Fox f = new FoxSignalGate.Fox(); f.only = foxOnly[0];
            ChannelStore.Channel cur = svc.current();
            if (cur != null && cur.isDigital()) { f.digital = true; f.id = foxId[0]; f.cc = foxCc[0]; }
            else if (foxTone[0] > 0) f.toneHz = foxTone[0];
            else if (cur != null && cur.rxType == 1) f.toneHz = com.macdmr.radio.dsp.ToneTable.ctcssHz(cur.rxSubCode);
            else if (cur != null && (cur.rxType == 2 || cur.rxType == 3)) f.dcs = true;
            gate.setFox(f);
            Neo.pillStyle(anyPill, !foxOnly[0], Neo.CYAN); Neo.pillStyle(onlyPill, foxOnly[0], Neo.VIOLET);
            setFox.setVisibility(foxOnly[0] ? View.VISIBLE : View.INVISIBLE);
            setFox.setText(Txt.s(R.string.modes_fox_fox_is, f.describe()));
            autoBtn.setText(autoM[0] == 0 ? Txt.s(R.string.modes_fox_auto_off) : Txt.s(R.string.modes_fox_auto_every, autoM[0]));
        };
        applyFox.run();
        anyPill.setOnClickListener(v -> { foxOnly[0] = false; fp.edit().putBoolean("foxOnly", false).apply(); applyFox.run(); });
        onlyPill.setOnClickListener(v -> { foxOnly[0] = true; fp.edit().putBoolean("foxOnly", true).apply(); applyFox.run(); });
        autoBtn.setOnClickListener(v -> { int[] steps = { 0, 50, 100, 200, 25 }; int k = 0; for (int i = 0; i < steps.length; i++) if (steps[i] == autoM[0]) k = i; autoM[0] = steps[(k + 1) % steps.length]; fp.edit().putInt("autoMarkM", autoM[0]).apply(); applyFox.run(); });
        setFox.setOnClickListener(v -> {
            ChannelStore.Channel cur = svc.current();
            if (cur != null && cur.isDigital()) {
                LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL); col.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
                final android.widget.EditText idE = new android.widget.EditText(c), ccE = new android.widget.EditText(c);
                idE.setHint(Txt.s(R.string.modes_fox_dmr_id_hint)); ccE.setHint(Txt.s(R.string.modes_fox_dmr_cc_hint));
                for (android.widget.EditText e : new android.widget.EditText[] { idE, ccE }) { e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); e.setSingleLine(true); e.setTextColor(Neo.TEXT); e.setTypeface(Neo.uiSemi(c)); col.addView(e); }
                if (foxId[0] > 0) idE.setText(String.valueOf(foxId[0])); if (foxCc[0] >= 0) ccE.setText(String.valueOf(foxCc[0]));
                final int last = svc.lastCallerId();
                AlertDialog.Builder b = new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_fox_dmr_title)).setView(col)
                        .setPositiveButton(Txt.s(R.string.common_save), (x, y) -> {
                            int id = 0, cc = -1;
                            try { id = Integer.parseInt(idE.getText().toString().trim()); } catch (RuntimeException ignored) { }
                            try { cc = Integer.parseInt(ccE.getText().toString().trim()); } catch (RuntimeException ignored) { }
                            foxId[0] = Math.max(0, Math.min(16_777_215, id)); foxCc[0] = cc < 0 || cc > 15 ? -1 : cc;
                            fp.edit().putInt("foxId", foxId[0]).putInt("foxCc", foxCc[0]).apply(); applyFox.run();
                        }).setNegativeButton(Txt.s(R.string.common_cancel), null);
                if (last > 0) b.setNeutralButton(Txt.s(R.string.modes_fox_last_caller, String.valueOf(last)), (x, y) -> { foxId[0] = last; fp.edit().putInt("foxId", last).apply(); applyFox.run(); });
                b.show();
                return;
            }
            final java.util.List<String> items = new java.util.ArrayList<>(); final java.util.List<Double> tones = new java.util.ArrayList<>();
            String own = cur == null ? null : com.macdmr.radio.dsp.ToneTable.toneLabel(cur.rxType, cur.rxSubCode);
            items.add(own == null || own.equals("—") ? Txt.s(R.string.modes_fox_tone_channel_none) : Txt.s(R.string.modes_fox_tone_channel, own)); tones.add(-1.0);
            double heard = svc.heardToneHz();
            if (heard > 0) { items.add(Txt.s(R.string.modes_fox_tone_heard, Txt.decimal(heard, 1))); tones.add(heard); }
            items.add(Txt.s(R.string.modes_fox_tone_pick)); tones.add(0.0);
            new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_fox_tone_title)).setItems(items.toArray(new String[0]), (x, w) -> {
                double t = tones.get(w);
                if (t == 0) {
                    int n = com.macdmr.radio.dsp.ToneConverter.ctcssCount(); String[] ls = new String[n];
                    for (int i = 0; i < n; i++) ls[i] = com.macdmr.radio.dsp.ToneConverter.ctcssLabelAt(i) + " Hz";
                    new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_fox_ctcss_title)).setItems(ls, (x2, k) -> { foxTone[0] = com.macdmr.radio.dsp.ToneConverter.ctcssHz(k); fp.edit().putFloat("foxTone", (float) foxTone[0]).apply(); applyFox.run(); }).show();
                    return;
                }
                foxTone[0] = t; fp.edit().putFloat("foxTone", (float) t).apply(); applyFox.run();
            }).show();
        });


        if (!svc.profile().rssi) {                                          // no signal readings (the W6): only the fox side works
            for (int i = body.getChildCount() - 1; i >= 1; i--) body.removeViewAt(i);   // keep the title, drop the hunt
            body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_fox_no_rssi)));
        }
        body.addView(Neo.sheetSection(c, Txt.s(R.string.modes_fox_be_fox)));
        final TextView foxBtn = Neo.button(c, Txt.s(svc.foxTxOn() ? R.string.modes_fox_beacon_stop : R.string.modes_fox_beacon_start), "bolt", Neo.VIOLET);
        body.addView(foxBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_fox_beacon_hint)));

        // ---- live state ----
        final int[] peak = { -999 }; final boolean[] beeping = { false }; final int[] offset = { 0 };
        final double[] heading = { Double.NaN }; final Location[] here = { svc.lastKnownLocation() };
        final Handler h = new Handler(Looper.getMainLooper());
        final android.media.ToneGenerator[] tg = { null };
        final long[] nextBeep = { 0 };
        final int[] chSeen = { Integer.MIN_VALUE };
        final SpinBearing[] spinNow = { null }; final long[] spinLastAt = { 0 };
        final double[] hring = new double[6]; java.util.Arrays.fill(hring, Double.NaN); final int[] hpos = { 0 };   // the last 1.5 s of headings: is the phone held steady?
        final FoxPredictor.Result[] pred = { null }; final int[] gen = { 0 }, seen = { -1 };
        final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        final com.macdmr.radio.coverage.Terrain terrain = new com.macdmr.radio.coverage.Terrain(c);   // used on the worker only
        final FoxPredictor.TerrainCache tcache = new FoxPredictor.TerrainCache();
        final double[][] tbox = { null };                                       // the area the terrain must cover {s, w, n, e}
        final Runnable recompute = () -> {
            final int g = ++gen[0]; final List<FoxHunt.Mark> ms = svc.fox().marks(); seen[0] = ms.size();
            final FoxPredictor.Options o = new FoxPredictor.Options(); o.bearings = layerOn[0]; o.heat = layerOn[1]; o.terrain = layerOn[2];
            SensorManager smc = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE); o.noCompass = smc == null || smc.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null;
            o.foxAgl = aglChoices[agl[0]]; o.cache = tcache;
            ChannelStore.Channel cur = svc.current(); if (cur != null && cur.rxFreq > 0) o.hz = cur.rxFreq;
            dlBtn.setVisibility(View.GONE);
            if (!o.bearings && !o.heat) { pred[0] = null; layer.set(null, ms); plot.setEstimate(null); mapView.invalidate(); predNote.setText(Txt.s(R.string.modes_fox_pred_off)); return; }
            final double[] box; if (ms.isEmpty()) box = null; else {
                double s0 = 90, w0 = 180, n0 = -90, e0 = -180;
                for (FoxHunt.Mark m : ms) { s0 = Math.min(s0, m.lat); n0 = Math.max(n0, m.lat); w0 = Math.min(w0, m.lon); e0 = Math.max(e0, m.lon); }
                double padLat = 3000 / 111_320.0, padLon = 3000 / (111_320.0 * Math.cos(Math.toRadians((s0 + n0) / 2)));   // the grid reaches ~1.5 km past the marks; room to grow
                box = new double[] { s0 - padLat, w0 - padLon, n0 + padLat, e0 + padLon };
            }
            tbox[0] = box;
            final boolean useTerrain = o.terrain && o.heat && box != null;
            if (useTerrain) predNote.setText(Txt.s(R.string.modes_fox_working_terrain));
            worker.execute(() -> {
                int missing = 0;
                if (useTerrain) {
                    int[] t = terrain.tiles(box[0], box[1], box[2], box[3]); missing = t[1];
                    if (missing == 0 && terrain.load(box[0], box[1], box[2], box[3], null) == null) o.elevation = terrain;   // from the phone, no network
                }
                FoxPredictor.Result r = null; try { r = FoxPredictor.predict(ms, o); } catch (Throwable ignored) { }
                final FoxPredictor.Result res = r; final int miss = missing;
                h.post(() -> {
                    if (g != gen[0]) return;                            // a newer mark or toggle arrived meanwhile
                    if (miss > 0) { dlMb[0] = Txt.decimal(Math.max(0.1, miss * com.macdmr.radio.coverage.Terrain.TILE_BYTES / 1e6), 1); dlBtn.setText(Txt.s(R.string.modes_fox_download_terrain_size, dlMb[0])); dlBtn.setVisibility(View.VISIBLE); }
                    pred[0] = res; layer.set(res, ms); plot.setEstimate(res); mapView.invalidate();
                    predNote.setText(res == null ? Txt.s(R.string.modes_fox_mark_to_start) : res.note == null ? (res.hasMap() ? Txt.s(R.string.modes_fox_shaded) : "") : Txt.s(res.hasMap() ? R.string.modes_fox_not_used_shaded : R.string.modes_fox_not_used, res.note));
                    if (!ms.isEmpty()) {
                        if (res != null && res.hasMap() && (res.layers & FoxPredictor.HEAT) != 0) mapView.fitBox(res.south, res.west, res.north, res.east);
                        else {
                            double s0 = 90, w0 = 180, n0 = -90, e0 = -180;
                            for (FoxHunt.Mark m : ms) { s0 = Math.min(s0, m.lat); n0 = Math.max(n0, m.lat); w0 = Math.min(w0, m.lon); e0 = Math.max(e0, m.lon); }
                            if (res != null) { s0 = Math.min(s0, res.lat); n0 = Math.max(n0, res.lat); w0 = Math.min(w0, res.lon); e0 = Math.max(e0, res.lon); }
                            double pad = 0.002; mapView.fitBox(s0 - pad, w0 - pad, n0 + pad, e0 + pad);
                        }
                    }
                });
            });
        };
        for (int i = 0; i < pill.length; i++) {
            final int k = i;
            pill[i].setOnClickListener(v -> { layerOn[k] = !layerOn[k]; Neo.pillStyle(pill[k], layerOn[k], layerAccent[k]); fp.edit().putBoolean(layerKey[k], layerOn[k]).apply(); terrRow.setVisibility(layerOn[2] ? View.VISIBLE : View.GONE); recompute.run(); });
        }
        aglBtn.setOnClickListener(v -> { agl[0] = (agl[0] + 1) % aglChoices.length; aglBtn.setText(aglNames[agl[0]]); fp.edit().putInt("foxAgl", agl[0]).apply(); recompute.run(); });
        dlBtn.setOnClickListener(v -> {
            final double[] box = tbox[0]; if (box == null) return;
            new AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_fox_download_terrain)).setMessage(Txt.s(R.string.modes_fox_download_message, dlMb[0]))
                    .setPositiveButton(Txt.s(R.string.modes_fox_download), (x, y) -> {
                        dlBtn.setVisibility(View.GONE); predNote.setText(Txt.s(R.string.modes_fox_downloading));
                        worker.execute(() -> {
                            String err = terrain.load(box[0], box[1], box[2], box[3], (done, total) -> h.post(() -> predNote.setText(Txt.s(R.string.modes_fox_downloading_n, done, total))));
                            h.post(() -> { if (err != null) Toast.makeText(c, err, Toast.LENGTH_LONG).show(); recompute.run(); });
                        });
                    }).setNegativeButton(Txt.s(R.string.common_not_now), null).show();
        });
        { final Location l0 = here[0]; if (l0 != null) { mapView.setMe(l0.getLatitude(), l0.getLongitude(), l0.getAccuracy()); mapView.post(() -> mapView.center(l0.getLatitude(), l0.getLongitude(), 16)); } }
        final long opened = System.currentTimeMillis(); final boolean[] fellBack = { false };
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor rot = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        final SensorEventListener sl = new SensorEventListener() {
            final float[] rm = new float[9], or = new float[3];
            @Override public void onSensorChanged(SensorEvent e) {
                SensorManager.getRotationMatrixFromVector(rm, e.values); SensorManager.getOrientation(rm, or);
                double az = Math.toDegrees(or[0]);
                Location l = here[0];
                if (l != null) az += new android.hardware.GeomagneticField((float) l.getLatitude(), (float) l.getLongitude(), (float) l.getAltitude(), System.currentTimeMillis()).getDeclination();   // magnetic to true north
                heading[0] = (az % 360 + 360) % 360;
            }
            @Override public void onAccuracyChanged(Sensor s, int a) { }
        };
        if (rot != null) sm.registerListener(sl, rot, SensorManager.SENSOR_DELAY_UI);
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        final LocationListener ll = l -> here[0] = l;
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, ll, Looper.getMainLooper()); } catch (Throwable ignored) { }

        final Runnable tick = new Runnable() {
            @Override public void run() {
                svc.pollRssiMeasure();
                int dbm = svc.lastRssi();
                if (dbm != -999 && dbm > peak[0]) peak[0] = dbm;
                big.setText(dbm == -999 ? "--" : String.valueOf(dbm));
                big.setTextColor(dbm >= -70 ? Neo.RED : dbm >= -93 ? Neo.AMBER : Neo.CYAN);
                List<String> facts = new java.util.ArrayList<>();
                if (dbm == -999) facts.add(Txt.s(R.string.modes_fox_no_reading)); else { facts.add("dBm"); facts.add(SMeterView.sUnits(dbm)); }   // i18n: unit
                if (peak[0] != -999) facts.add(Txt.s(R.string.modes_fox_peak, peak[0]));
                if (offset[0] != 0) facts.add(Txt.s(R.string.modes_fox_tuned_off, offset[0] / 1000));
                sub.setText(String.join(Txt.s(R.string.modes_dot_separator), facts));
                meter.setDbm(dbm);
                // the fox-signal gate: which readings count, the waiting mark, auto-marks
                long now = System.currentTimeMillis(); ChannelStore.Channel cc0 = svc.current();
                FoxSignalGate.Now fn = new FoxSignalGate.Now(); fn.t = now;
                fn.rx = svc.rxActive(); fn.mcuRx = svc.mcuReceiving(); fn.softSq = svc.softSqOn(); fn.tx = svc.isTransmitting();
                fn.toneHz = svc.heardToneHz(); fn.caller = svc.currentCallerId(); fn.cc = svc.currentCallerCc(); fn.channelCc = cc0 != null && cc0.isDigital() ? cc0.cc : -1;
                hring[hpos[0]++ % hring.length] = heading[0];
                boolean foxOn = gate.update(fn);
                gate.reading(svc.lastRssiAt(), dbm, heading[0], svc.lastTunedAt());
                List<FoxSignalGate.Reading> fresh = gate.drain(); SpinBearing sp = spinNow[0];
                if (sp != null) {                                               // a spin: every heading, and the readings the fox-signal setting lets through
                    sp.heading(now, heading[0]);
                    if (gate.fox().only) for (FoxSignalGate.Reading r : fresh) sp.add(r.at, r.heading, r.dbm);
                    else { long at = svc.lastRssiAt(); if (at > spinLastAt[0] && at >= svc.lastTunedAt() + FoxSignalGate.SETTLE_MS && !fn.tx) { spinLastAt[0] = at; sp.add(at, heading[0], dbm); } }
                }
                Location me0 = here[0];
                if (me0 != null) {
                    int lv = gate.takeIfReady();
                    if (lv != -999) addMark(c, svc, me0, lv, heading[0], Double.NaN, Txt.s(R.string.modes_fox_how_fox));
                    else if (autoM[0] > 0) {
                        List<FoxHunt.Mark> ms0 = svc.fox().marks(); FoxHunt.Mark last = ms0.isEmpty() ? null : ms0.get(ms0.size() - 1);
                        int al = gate.autoTake(true, last == null ? Double.NaN : FoxPredictor.metres(last.lat, last.lon, me0.getLatitude(), me0.getLongitude()), autoM[0]);
                        if (al != -999) addMark(c, svc, me0, al, FoxSignalGate.steadyHeading(hring, 12), Double.NaN, Txt.s(R.string.modes_fox_how_auto));
                    }
                }
                { String mt = Txt.s(gate.waiting() ? R.string.modes_fox_mark_waiting : R.string.modes_fox_mark); if (!mt.contentEquals(mark.getText())) mark.setText(mt); }
                FoxSignalGate.Fox ff = gate.fox();
                List<String> st = new java.util.ArrayList<>();
                if (!ff.only) st.add(autoM[0] > 0 ? Txt.s(R.string.modes_fox_state_auto, autoM[0]) : Txt.s(R.string.modes_fox_state_any));
                else {
                    if (!ff.digital && ff.dcs && fn.softSq) st.add(Txt.s(R.string.modes_fox_state_softsq));
                    st.add(Txt.s(gate.waiting() ? R.string.modes_fox_state_waiting : foxOn ? R.string.modes_fox_state_on : R.string.modes_fox_state_off));
                    if (autoM[0] > 0) st.add(Txt.s(R.string.modes_fox_state_auto_every, autoM[0]));
                }
                foxState.setText(String.join(Txt.s(R.string.modes_sentence_separator), st));
                StringBuilder sb = new StringBuilder();
                sb.append(Double.isNaN(heading[0]) ? Txt.s(R.string.modes_fox_no_compass) : Txt.s(R.string.modes_fox_pointing, String.format(Locale.US, "%03.0f", heading[0]), compass(heading[0])));
                if (svc.fox().marks().size() != seen[0]) recompute.run();
                ChannelStore.Channel chNow = svc.current(); int chKey = chNow == null ? 0 : chNow.number * 4 + (chNow.isDigital() ? 1 : 0) + chNow.rxType * 8 + chNow.rxSubCode * 64;
                if (chKey != chSeen[0]) { chSeen[0] = chKey; applyFox.run(); }   // the channel (or its tone) changed: the fox's tone or DMR setting follows   // a mark added (or cleared)
                FoxHunt.Estimate est = svc.fox().estimate(); Location me = here[0]; FoxPredictor.Result pr = pred[0];
                if (est != null && me != null) {
                    double eLat = pr != null ? pr.lat : est.lat, eLon = pr != null ? pr.lon : est.lon;
                    float[] res = new float[2]; Location.distanceBetween(me.getLatitude(), me.getLongitude(), eLat, eLon, res);
                    String what = pr != null ? (pr.layers == 0 ? Txt.s(R.string.modes_fox_what_strongest) : pr.confidence() != null ? Txt.s(R.string.modes_fox_what_estimate_conf, pr.layerNames(), pr.confidenceText()) : Txt.s(R.string.modes_fox_what_estimate, pr.layerNames()))
                            : est.fromBearings ? Txt.p(R.plurals.modes_fox_what_bearings, est.used, est.used) : Txt.s(R.string.modes_fox_what_strongest);
                    if (res[0] < 10) sb.append("\n").append(Txt.s(R.string.modes_fox_here, what));   // closer than GPS can tell: a direction would be noise
                    else sb.append(String.format(Locale.US, "\n%s: %s %s (%03.0f°)", what, com.macdmr.radio.region.Region.km() ? com.macdmr.radio.repeaters.GeoUtil.dist(res[0], true) : Txt.decimal(res[0] / 1609.344, 2) + " mi", compass(res[1]), (res[1] + 360) % 360));   // i18n: distance and bearing
                } else if (svc.fox().marks().isEmpty()) sb.append("\n").append(Txt.s(R.string.modes_fox_no_marks));
                info.setText(sb.toString());
                plot.update(me, heading[0]);
                if (me != null) mapView.setMe(me.getLatitude(), me.getLongitude(), me.getAccuracy());
                if (!fellBack[0] && !mapView.anyTile() && System.currentTimeMillis() - opened > 8000) { fellBack[0] = true; if (plot.getVisibility() != View.VISIBLE) swap.performClick(); }   // no map tiles (offline): the plot
                if (beeping[0] && dbm != -999 && System.currentTimeMillis() >= nextBeep[0]) {      // faster and higher as the signal rises
                    double s = Math.max(0, Math.min(1, (dbm + 125) / 70.0));
                    try { if (tg[0] == null) tg[0] = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60); tg[0].startTone(s > 0.66 ? android.media.ToneGenerator.TONE_DTMF_D : s > 0.33 ? android.media.ToneGenerator.TONE_DTMF_9 : android.media.ToneGenerator.TONE_DTMF_1, 40); } catch (Throwable ignored) { }
                    nextBeep[0] = System.currentTimeMillis() + (long) (900 - 800 * s);
                }
                h.postDelayed(this, 250);
            }
        };
        h.post(tick);

        mark.setOnClickListener(v -> {
            if (svc.isTransmitting()) { Toast.makeText(c, Txt.s(R.string.modes_fox_not_while_tx), Toast.LENGTH_SHORT).show(); return; }
            Location me = here[0]; int dbm = svc.lastRssi();
            if (me == null) { Toast.makeText(c, Txt.s(R.string.modes_fox_no_position), Toast.LENGTH_LONG).show(); return; }
            if (gate.fox().only) {                                          // only the fox's: from its transmission, now or at the next one
                if (gate.waiting()) { gate.cancel(); Toast.makeText(c, Txt.s(R.string.modes_fox_mark_cancelled), Toast.LENGTH_SHORT).show(); return; }
                gate.requestMark(); int lv = gate.takeIfReady();
                if (lv != -999) addMark(c, svc, me, lv, heading[0], Double.NaN, Txt.s(R.string.modes_fox_how_fox));
                else { Toast.makeText(c, Txt.s(R.string.modes_fox_mark_wait_toast), Toast.LENGTH_LONG).show(); }
                return;
            }
            if (dbm == -999) { Toast.makeText(c, Txt.s(R.string.modes_fox_no_signal), Toast.LENGTH_SHORT).show(); return; }
            addMark(c, svc, me, dbm, heading[0], Double.NaN, Txt.s(R.string.modes_fox_how_marked));
        });
        spin.setOnClickListener(v -> {
            if (svc.isTransmitting()) { Toast.makeText(c, Txt.s(R.string.modes_fox_not_while_tx), Toast.LENGTH_SHORT).show(); return; }
            if (Double.isNaN(heading[0])) { Toast.makeText(c, Txt.s(R.string.modes_fox_spin_compass), Toast.LENGTH_LONG).show(); return; }
            if (here[0] == null) { Toast.makeText(c, Txt.s(R.string.modes_fox_no_position), Toast.LENGTH_LONG).show(); return; }
            spinLastAt[0] = svc.lastRssiAt(); showSpin(c, svc, spinNow, here, gate);
        });
        beep.setOnClickListener(v -> { beeping[0] = !beeping[0]; beep.setText(Txt.s(beeping[0] ? R.string.modes_fox_beeper_on : R.string.modes_fox_beeper_off)); });
        off.setOnClickListener(v -> {
            ChannelStore.Channel cur = svc.current(); if (cur == null) return;
            int[] steps = { 0, 10_000, 20_000, 30_000 };
            int k = 0; for (int i = 0; i < steps.length; i++) if (steps[i] == offset[0]) k = i;
            int next = steps[(k + 1) % steps.length];
            String e = svc.foxOffset(next);
            if (e != null) { Toast.makeText(c, e, Toast.LENGTH_LONG).show(); if (offset[0] != 0) { svc.foxOffset(0); offset[0] = 0; off.setText(Txt.s(R.string.modes_fox_off_freq)); } return; }   // past the top of the band: the next tap starts again from the channel
            offset[0] = next; peak[0] = -999;
            off.setText(next == 0 ? Txt.s(R.string.modes_fox_off_freq) : Txt.s(R.string.modes_fox_offset, next / 1000));
        });
        map.setOnClickListener(v -> {
            FoxHunt.Estimate est = svc.fox().estimate(); FoxPredictor.Result pr = pred[0];
            if (est == null) { Toast.makeText(c, Txt.s(R.string.modes_fox_mark_first), Toast.LENGTH_SHORT).show(); return; }
            double eLat = pr != null ? pr.lat : est.lat, eLon = pr != null ? pr.lon : est.lon;
            try { c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(Fox)", eLat, eLon, eLat, eLon)))); } catch (Throwable t) { Toast.makeText(c, Txt.s(R.string.modes_fox_no_map_app), Toast.LENGTH_SHORT).show(); }   // i18n: geo URI
        });
        clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.modes_fox_clear_confirm)).setMessage(Txt.s(R.string.modes_fox_clear_message))
                .setPositiveButton(Txt.s(R.string.common_clear), (dd, w) -> { svc.fox().clear(); peak[0] = -999; recompute.run(); }).setNegativeButton(Txt.s(R.string.common_cancel), null).show());
        foxBtn.setOnClickListener(v -> { String r = svc.setFoxTx(!svc.foxTxOn()); Toast.makeText(c, r, Toast.LENGTH_LONG).show(); foxBtn.setText(Txt.s(svc.foxTxOn() ? R.string.modes_fox_beacon_stop : R.string.modes_fox_beacon_start)); });

        AlertDialog a = Neo.showSheet(c, body);
        a.setOnDismissListener(x -> {
            h.removeCallbacks(tick);
            worker.shutdownNow(); mapView.shutdown();
            if (sm != null) sm.unregisterListener(sl);
            try { lm.removeUpdates(ll); } catch (Throwable ignored) { }
            if (tg[0] != null) tg[0].release();
            if (offset[0] != 0) svc.foxOffset(0);       // back on the channel
        });
    }

    /** The spin sheet: guidance while the hunter turns, then the fitted bearing to save as a mark (or a retry). The fox hunt's tick feeds {@code slot[0]}. */
    static void showSpin(Context c, RadioService svc, SpinBearing[] slot, Location[] here, FoxSignalGate gate) {
        float d = Theme.dp(c);
        LinearLayout body = Neo.sheetBody(c, Txt.s(R.string.modes_fox_spin_title), "radio");
        body.addView(Neo.sheetHint(c, Txt.s(R.string.modes_fox_spin_hint)));
        final SpinView ring = new SpinView(c);
        body.addView(ring, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * d)));
        final TextView guide = Neo.text(c, Txt.s(R.string.modes_fox_spin_start), Neo.CYAN_LIGHT, 24, Neo.ui(c)); guide.setGravity(Gravity.CENTER); body.addView(guide);
        final TextView detail = Neo.label(c, "", Neo.MUTED, 13, false); detail.setGravity(Gravity.CENTER); detail.setPadding(0, (int) (4 * d), 0, (int) (4 * d)); body.addView(detail);
        LinearLayout row = new LinearLayout(c); row.setBaselineAligned(false);
        final TextView left = Neo.button(c, Txt.s(R.string.modes_fox_spin_fit), "check", Neo.AMBER), save = Neo.button(c, Txt.s(R.string.modes_fox_spin_save), "locate", Neo.GREEN);
        for (TextView b : new TextView[] { left, save }) { b.setTextSize(14); Neo.fit(b); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f); lp.setMargins((int) (3 * d), (int) (8 * d), (int) (3 * d), 0); row.addView(b, lp); }
        save.setVisibility(View.GONE); body.addView(row);
        final SpinBearing.Result[] res = { null };
        final Handler h = new Handler(Looper.getMainLooper());
        slot[0] = new SpinBearing();
        final Runnable done = () -> {
            SpinBearing sp = slot[0]; if (sp == null) return;
            SpinBearing.Result r = sp.fit(); res[0] = r; ring.set(sp, r);
            guide.setText(r.ok ? Txt.s(R.string.modes_fox_spin_toward, String.format(Locale.US, "%03.0f", r.bearing), compass(r.bearing)) : Txt.s(R.string.modes_fox_spin_retry));
            guide.setTextColor(r.ok ? Neo.GREEN_LIGHT : Neo.AMBER_LIGHT);
            detail.setText(r.ok ? Txt.p(r.fromNull ? R.plurals.modes_fox_spin_within_null : R.plurals.modes_fox_spin_within_peak, r.samples, String.format(Locale.US, "%.0f", r.sigma), String.format(Locale.US, "%.0f", r.depth), r.samples) : r.retry);
            left.setText(Txt.s(R.string.modes_fox_spin_again)); save.setVisibility(r.ok ? View.VISIBLE : View.GONE);
        };
        final Runnable upd = new Runnable() {
            @Override public void run() {
                SpinBearing sp = slot[0]; if (sp == null) return;
                if (res[0] == null) {
                    ring.set(sp, null);
                    boolean wait = gate.fox().only && !gate.active(); String g = sp.guide();
                    guide.setText(wait ? Txt.s(R.string.modes_fox_spin_waiting) : g); guide.setTextColor(wait ? Neo.MUTED : sp.tooFast() ? Neo.AMBER_LIGHT : Neo.CYAN_LIGHT);
                    String pct = String.format(Locale.US, "%.0f", sp.covered() * 100), turned = String.format(Locale.US, "%.0f", sp.turned());
                    detail.setText(Txt.p(wait ? R.plurals.modes_fox_spin_progress_wait : R.plurals.modes_fox_spin_progress, sp.samples(), pct, sp.samples(), turned));
                    if (sp.complete()) done.run();
                }
                h.postDelayed(this, 250);
            }
        };
        left.setOnClickListener(v -> {
            if (res[0] == null) { done.run(); return; }
            res[0] = null; slot[0] = new SpinBearing(); left.setText(Txt.s(R.string.modes_fox_spin_fit)); save.setVisibility(View.GONE); guide.setText(Txt.s(R.string.modes_fox_spin_start));
        });
        final AlertDialog a = Neo.showSheet(c, body);
        save.setOnClickListener(v -> {
            SpinBearing.Result r = res[0]; Location me = here[0];
            if (r == null || !r.ok) return;
            if (me == null) { Toast.makeText(c, Txt.s(R.string.modes_fox_no_position), Toast.LENGTH_LONG).show(); return; }
            addMark(c, svc, me, (int) Math.round(r.peakDb), r.bearing, r.sigma, Txt.s(R.string.modes_fox_how_spin));
            a.dismiss();
        });
        a.setOnDismissListener(x -> { h.removeCallbacks(upd); slot[0] = null; });
        h.post(upd);
    }

    /**
     * The spin on screen, north up: 36 sectors around a ring, filled as readings arrive (cyan weak to red strong), where
     * the phone points now, and after the fit the smoothed curve as a polar line with the bearing as a green arrow.
     */
    static final class SpinView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private double[] means = new double[0]; private double heading = Double.NaN, turned; private SpinBearing.Result r;
        SpinView(Context c) { super(c); }
        void set(SpinBearing sp, SpinBearing.Result res) { means = sp.sectorMeans(); heading = sp.lastHeading(); turned = sp.turned(); r = res; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float d = Theme.dp(getContext()), cx = getWidth() / 2f, cy = getHeight() / 2f, rad = Math.min(cx, cy) - 16 * d, sw = 16 * d;
            double lo = 0, hi = 0; boolean any = false;
            for (double m : means) if (!Double.isNaN(m)) { if (!any) { lo = m; hi = m; any = true; } lo = Math.min(lo, m); hi = Math.max(hi, m); }
            android.graphics.RectF box = new android.graphics.RectF(cx - rad, cy - rad, cx + rad, cy + rad);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw); p.setStrokeCap(Paint.Cap.BUTT);
            for (int k = 0; k < means.length; k++) {
                double m = means[k]; float t = any && hi > lo ? (float) ((m - lo) / (hi - lo)) : 0.5f;
                p.setColor(Double.isNaN(m) ? 0xFF16263A : HuntLayer.ramp(t));
                cv.drawArc(box, -90 + k * 10 + 0.8f, 10 - 1.6f, false, p);   // canvas angles start east; ours north
            }
            p.setStrokeWidth(1.2f * d); p.setColor(0xFF1A2C3E); cv.drawCircle(cx, cy, rad * 0.62f, p); cv.drawCircle(cx, cy, rad * 0.31f, p);
            p.setStyle(Paint.Style.FILL); p.setColor(Neo.DIM); p.setTextSize(12 * d); p.setTextAlign(Paint.Align.CENTER);
            cv.drawText("N", cx, cy - rad + sw / 2 + 18 * d, p);
            if (r != null && r.curve != null) {                             // the fitted curve: weakest near the middle, strongest out toward the ring
                android.graphics.Path path = new android.graphics.Path(); double span = Math.max(1, r.peakDb - r.nullDb);
                for (int i = 0; i <= r.curve.length; i++) {
                    double v = r.curve[i % r.curve.length], th = Math.toRadians(i * 360.0 / r.curve.length); float rr = (float) (rad * (0.25 + 0.6 * (v - r.nullDb) / span));
                    float x = cx + (float) Math.sin(th) * rr, y = cy - (float) Math.cos(th) * rr; if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * d); p.setColor(Neo.alpha(Neo.CYAN, 0xC0)); cv.drawPath(path, p);
                if (r.ok) { double th = Math.toRadians(r.bearing); p.setStrokeWidth(3 * d); p.setColor(Neo.GREEN); cv.drawLine(cx, cy, cx + (float) Math.sin(th) * rad, cy - (float) Math.cos(th) * rad, p); }
            } else {
                p.setStyle(Paint.Style.FILL); p.setColor(Neo.TEXT); p.setTextSize(30 * d); p.setTypeface(Neo.display(getContext()));
                cv.drawText(String.format(Locale.US, "%.0f\u00B0", Math.min(360, turned)), cx, cy + 10 * d, p); p.setTypeface(null);
            }
            if (!Double.isNaN(heading)) {                                   // where the phone points now
                double th = Math.toRadians(heading); float r0 = rad - sw, r1 = rad + sw / 2;
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3 * d); p.setColor(0xFFFFFFFF);
                cv.drawLine(cx + (float) Math.sin(th) * r0, cy - (float) Math.cos(th) * r0, cx + (float) Math.sin(th) * r1, cy - (float) Math.cos(th) * r1, p);
            }
        }
    }

    /** Save a mark here and say so. */
    static void addMark(Context c, RadioService svc, Location me, int dbm, double bearing, double sigma, String how) {
        svc.fox().add(new FoxHunt.Mark(me.getLatitude(), me.getLongitude(), dbm, bearing, System.currentTimeMillis(), sigma));
        Toast.makeText(c, Double.isNaN(bearing) ? Txt.s(R.string.modes_fox_marked, how, dbm) : Double.isNaN(sigma) ? Txt.s(R.string.modes_fox_marked_toward, how, dbm, String.format(Locale.US, "%03.0f", bearing))
                : Txt.s(R.string.modes_fox_marked_toward_sigma, how, dbm, String.format(Locale.US, "%03.0f", bearing), String.format(Locale.US, "%.0f", sigma)), Toast.LENGTH_SHORT).show();
    }

    static String compass(double deg) { String[] n = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" }; return n[(int) Math.round(((deg % 360) + 360) % 360 / 45.0) % 8]; }

    /** The marks around you: dots coloured by strength with their bearing, you in the middle, the estimate as a ring. */
    static final class PlotView extends View {
        private final FoxHunt fox; private Location me; private double heading = Double.NaN;
        private FoxPredictor.Result pred;                                    // the prediction's estimate, when its layers are on
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        PlotView(Context c, FoxHunt f) { super(c); fox = f; }
        void update(Location l, double hd) { me = l; heading = hd; invalidate(); }
        void setEstimate(FoxPredictor.Result r) { pred = r; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float d = Theme.dp(getContext()), w = getWidth(), hgt = getHeight(), cx = w / 2, cy = hgt / 2;
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF0A1622); cv.drawRoundRect(0, 0, w, hgt, 14 * d, 14 * d, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d); p.setColor(0xFF1A2C3E);
            for (int i = 1; i <= 3; i++) cv.drawCircle(cx, cy, Math.min(cx, cy) * i / 3.2f, p);
            List<FoxHunt.Mark> ms = fox.marks(); FoxHunt.Estimate est = fox.estimate();
            if (est != null && pred != null) est = new FoxHunt.Estimate(pred.lat, pred.lon, true, 0);
            if (me == null && ms.isEmpty()) { p.setStyle(Paint.Style.FILL); p.setColor(Neo.DIM); p.setTextSize(13 * d); p.setTextAlign(Paint.Align.CENTER); cv.drawText(Txt.s(R.string.modes_fox_plot_empty), cx, cy, p); return; }
            double lat0 = me != null ? me.getLatitude() : ms.get(0).lat, lon0 = me != null ? me.getLongitude() : ms.get(0).lon, cos = Math.cos(Math.toRadians(lat0));
            double maxM = 50;
            for (FoxHunt.Mark m : ms) maxM = Math.max(maxM, dist(m.lat, m.lon, lat0, lon0, cos));
            if (est != null) maxM = Math.max(maxM, dist(est.lat, est.lon, lat0, lon0, cos));
            float scale = (float) (Math.min(cx, cy) * 0.9 / maxM);
            int max = -999; for (FoxHunt.Mark m : ms) max = Math.max(max, m.dbm);
            for (FoxHunt.Mark m : ms) {
                float x = cx + (float) (Math.toRadians(m.lon - lon0) * 6371000 * cos) * scale, y = cy - (float) (Math.toRadians(m.lat - lat0) * 6371000) * scale;
                int col = m.dbm >= -70 ? Neo.RED : m.dbm >= -93 ? Neo.AMBER : Neo.CYAN;
                if (m.hasBearing()) {
                    float len = Math.min(cx, cy) * 0.8f; double th = Math.toRadians(m.bearing);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * d); p.setColor(Neo.alpha(col, m.dbm == max ? 0xCC : 0x66));
                    cv.drawLine(x, y, x + (float) Math.sin(th) * len, y - (float) Math.cos(th) * len, p);
                }
                p.setStyle(Paint.Style.FILL); p.setColor(col); cv.drawCircle(x, y, (m.dbm == max ? 6 : 4) * d, p);
            }
            if (est != null) {
                float x = cx + (float) (Math.toRadians(est.lon - lon0) * 6371000 * cos) * scale, y = cy - (float) (Math.toRadians(est.lat - lat0) * 6371000) * scale;
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f * d); p.setColor(Neo.GREEN); cv.drawCircle(x, y, 11 * d, p); cv.drawCircle(x, y, 3 * d, p);
            }
            if (me != null) {                                   // you: a white arrow along the compass heading
                p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF);
                double th = Double.isNaN(heading) ? 0 : Math.toRadians(heading);
                android.graphics.Path ar = new android.graphics.Path();
                ar.moveTo(cx + (float) Math.sin(th) * 12 * d, cy - (float) Math.cos(th) * 12 * d);
                ar.lineTo(cx + (float) Math.sin(th + 2.5) * 8 * d, cy - (float) Math.cos(th + 2.5) * 8 * d);
                ar.lineTo(cx + (float) Math.sin(th - 2.5) * 8 * d, cy - (float) Math.cos(th - 2.5) * 8 * d);
                ar.close(); cv.drawPath(ar, p);
            }
            p.setStyle(Paint.Style.FILL); p.setColor(Neo.DIM); p.setTextSize(11 * d); p.setTextAlign(Paint.Align.LEFT);
            double ring = maxM / (0.9 * 3.2);                 // metres between rings
            cv.drawText(com.macdmr.radio.region.Region.km() ? (ring >= 1000 ? Txt.s(R.string.modes_fox_rings_km, Txt.decimal(ring / 1000, 1)) : Txt.s(R.string.modes_fox_rings_m, String.format(Locale.US, "%.0f", ring)))
                    : ring >= 1609 ? Txt.s(R.string.modes_fox_rings_mi, Txt.decimal(ring / 1609.344, 1)) : Txt.s(R.string.modes_fox_rings_m, String.format(Locale.US, "%.0f", ring)), 10 * d, hgt - 10 * d, p);
            cv.drawText("N ↑", w - 34 * d, 18 * d, p);
        }
        private static double dist(double la, double lo, double la0, double lo0, double cos) { double x = Math.toRadians(lo - lo0) * 6371000 * cos, y = Math.toRadians(la - la0) * 6371000; return Math.sqrt(x * x + y * y); }
    }

    /**
     * The hunt on the map: the prediction's heat (translucent, cyan to red as it gets likelier) with the 50 % (bright) and
     * 90 % (faint) outlines, the bearing lines, the marks (coloured by strength; a ring = at the radio's floor) and the
     * estimate as a green ring.
     */
    static final class HuntLayer implements MapView.Overlay {
        private final float d; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private FoxPredictor.Result r; private List<FoxHunt.Mark> marks = new java.util.ArrayList<>();
        private android.graphics.Bitmap heat; private float[] edge50 = new float[0], edge90 = new float[0];
        HuntLayer(Context c) { d = Theme.dp(c); }

        void set(FoxPredictor.Result res, List<FoxHunt.Mark> ms) {
            r = res; marks = ms; heat = null; edge50 = edge90 = new float[0];
            if (r == null || !r.hasMap()) return;
            int nx = r.nx, ny = r.ny; float max = 0; for (float v : r.p) max = Math.max(max, v);
            int[] px = new int[nx * ny];
            for (int iy = 0; iy < ny; iy++)
                for (int ix = 0; ix < nx; ix++) {
                    float v = r.p[iy * nx + ix], t = max > 0 ? v / max : 0;
                    int a = v >= r.thr90 ? (int) (0x38 + 0x70 * Math.sqrt(t)) : (int) (0x30 * Math.sqrt(t));
                    px[(ny - 1 - iy) * nx + ix] = Neo.alpha(ramp(t), Math.min(0xB0, a));   // bitmap rows run north to south
                }
            heat = android.graphics.Bitmap.createBitmap(px, nx, ny, android.graphics.Bitmap.Config.ARGB_8888);
            edge50 = edges(r.thr50); edge90 = edges(r.thr90);
        }

        /** Cyan, green, amber, red: the app's palette from unlikely to likely. */
        static int ramp(float t) {
            int[] stops = { Neo.CYAN, Neo.GREEN, Neo.AMBER, Neo.RED };
            float x = Math.max(0, Math.min(0.999f, t)) * 3; int k = (int) x; float f = x - k;
            int a = stops[k], b = stops[k + 1];
            int rr = (int) (((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f), gg = (int) (((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f), bb = (int) ((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
            return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }

        /** The region's outline as line segments in grid units (x east, y south from the north-west corner). */
        private float[] edges(float thr) {
            int nx = r.nx, ny = r.ny; java.util.ArrayList<Float> out = new java.util.ArrayList<>();
            for (int iy = 0; iy < ny; iy++)
                for (int ix = 0; ix < nx; ix++) {
                    if (r.p[iy * nx + ix] < thr) continue;
                    float x = ix, y = ny - 1 - iy;
                    if (iy == ny - 1 || r.p[(iy + 1) * nx + ix] < thr) { out.add(x); out.add(y); out.add(x + 1); out.add(y); }          // north side
                    if (iy == 0 || r.p[(iy - 1) * nx + ix] < thr) { out.add(x); out.add(y + 1); out.add(x + 1); out.add(y + 1); }       // south
                    if (ix == 0 || r.p[iy * nx + ix - 1] < thr) { out.add(x); out.add(y); out.add(x); out.add(y + 1); }                  // west
                    if (ix == nx - 1 || r.p[iy * nx + ix + 1] < thr) { out.add(x + 1); out.add(y); out.add(x + 1); out.add(y + 1); }     // east
                }
            float[] a = new float[out.size()]; for (int i = 0; i < a.length; i++) a[i] = out.get(i); return a;
        }

        @Override public void draw(Canvas cv, MapView m) {
            if (r != null && heat != null) {
                float l = m.screenX(r.west), rt = m.screenX(r.east), t = m.screenY(r.north), b = m.screenY(r.south);
                cv.drawBitmap(heat, null, new android.graphics.RectF(l, t, rt, b), bmp);
                float sx = (rt - l) / r.nx, sy = (b - t) / r.ny;
                p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
                for (int pass = 0; pass < 2; pass++) {
                    float[] e = pass == 0 ? edge90 : edge50; if (e.length == 0) continue;
                    float[] q = new float[e.length]; for (int i = 0; i < e.length; i += 2) { q[i] = l + e[i] * sx; q[i + 1] = t + e[i + 1] * sy; }
                    p.setStrokeWidth((pass == 0 ? 1.2f : 2f) * d); p.setColor(pass == 0 ? Neo.alpha(Neo.AMBER_LIGHT, 0x80) : 0xFFFFFFFF);
                    cv.drawLines(q, p);
                }
            }
            int max = -999; for (FoxHunt.Mark k : marks) max = Math.max(max, k.dbm);
            boolean bearOn = r == null || (r.layers & FoxPredictor.BEARINGS) != 0;
            for (FoxHunt.Mark k : marks) {                              // bearing lines, 3 km long (they leave the screen when zoomed in)
                if (!k.hasBearing()) continue;
                double th = Math.toRadians(k.bearing), len = 3000, cos = Math.cos(Math.toRadians(k.lat));
                double la = k.lat + Math.toDegrees(Math.cos(th) * len / 6371000), lo = k.lon + Math.toDegrees(Math.sin(th) * len / (6371000 * cos));
                int col = k.dbm >= -70 ? Neo.RED : k.dbm >= -93 ? Neo.AMBER : Neo.CYAN;
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * d); p.setColor(Neo.alpha(col, bearOn ? (k.dbm == max ? 0xD0 : 0x80) : 0x40));
                cv.drawLine(m.screenX(k.lon), m.screenY(k.lat), m.screenX(lo), m.screenY(la), p);
            }
            for (FoxHunt.Mark k : marks) {
                float x = m.screenX(k.lon), y = m.screenY(k.lat);
                int col = k.dbm >= -70 ? Neo.RED : k.dbm >= -93 ? Neo.AMBER : Neo.CYAN;
                p.setColor(0xFF07101A); p.setStyle(Paint.Style.FILL); cv.drawCircle(x, y, (k.dbm == max ? 7 : 5.5f) * d, p);
                p.setColor(col);
                if (k.dbm <= FoxPredictor.FLOOR_DBM) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * d); cv.drawCircle(x, y, 4 * d, p); }   // heard nothing here
                else cv.drawCircle(x, y, (k.dbm == max ? 5.5f : 4) * d, p);
            }
            if (r != null && r.layers != 0) {
                float x = m.screenX(r.lon), y = m.screenY(r.lat);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f * d); p.setColor(Neo.GREEN); cv.drawCircle(x, y, 12 * d, p); cv.drawCircle(x, y, 3 * d, p);
            } else if (!marks.isEmpty()) {                               // no prediction: today's estimate
                FoxHunt.Estimate e = FoxHunt.estimate(marks);
                if (e != null && e.fromBearings) { float x = m.screenX(e.lon), y = m.screenY(e.lat); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f * d); p.setColor(Neo.GREEN); cv.drawCircle(x, y, 12 * d, p); cv.drawCircle(x, y, 3 * d, p); }
            }
        }
    }
}
