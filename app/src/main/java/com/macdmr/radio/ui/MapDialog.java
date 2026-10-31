/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.R;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.pkt.PacketStore;
import com.macdmr.radio.repeaters.GeoUtil;
import com.macdmr.radio.repeaters.RepeaterModels.Repeater;
import com.macdmr.radio.repeaters.RepeaterService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Map: APRS stations heard (with their last position), nearby repeaters (within the Nearby repeaters range) and
 * channels that have a location, around your own position. Filter pills switch each kind on and off; tapping a
 * marker opens a card with what is known and an action (use the channel, repeater details, open in Maps).
 */
public final class MapDialog {
    private final Context c; private final RadioService svc; private final float d;
    private final MapView map; private Dialog dlg;
    private final LinearLayout card; private final TextView cardTitle, cardSub, cardBody; private final LinearLayout cardActions;
    private final TextView status;
    private final TextView[] pills = new TextView[5];
    private int layer = MapView.LAYER_NONE; private String focusKey; private String focusName;
    private double focusLat, focusLon, focusHz;
    private com.macdmr.radio.coverage.Prediction.Result pred; private volatile int predRun; private String predStatus = "";
    private android.content.SharedPreferences cprefs() { return c.getSharedPreferences("coverage", Context.MODE_PRIVATE); }
    private boolean predictOn() { return cprefs().getBoolean("predict", true); }
    /** The repeater antenna height chosen for the focused repeater; 0 = Auto (fitted from the measurements, 30 m without enough of them). */
    private int antennaM() { return focusKey == null ? 0 : cprefs().getInt("ant_" + focusKey, 0); }
    /** Prediction range in miles; 0 = Auto (as far as the transmitter can reach, up to 50 miles). */
    private int rangeMi() { return cprefs().getInt("predRangeMi", 0); }
    /** Transmitter power for the focused repeater (W); 0 = not known (50 W assumed, the measurements correct the level). */
    private int powerW() { return focusKey == null ? 0 : cprefs().getInt("pwr_" + focusKey, 0); }
    private void focus(String key, String name, double lat, double lon, double hz) { focusKey = key; focusName = name; focusLat = lat; focusLon = lon; focusHz = hz; pred = null; }
    private LinearLayout legend;
    private final int[] counts = new int[4];
    private final List<MapView.Marker> all = new ArrayList<>();
    private Location me;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static void show(Context c, RadioService svc) { if (svc != null) new MapDialog(c, svc).open(); }

    private MapDialog(Context c, RadioService svc) {
        this.c = c; this.svc = svc; d = Theme.dp(c);
        map = new MapView(c);
        card = new LinearLayout(c); cardTitle = Neo.label(c, "", Neo.TEXT, 18, true); cardSub = Neo.label(c, "", Neo.CYAN, 14, true);
        cardBody = Neo.label(c, "", Neo.TEXT_SOFT, 14, false); cardActions = new LinearLayout(c);
        status = Neo.label(c, Txt.s(R.string.packet_map_loading), Neo.MUTED, 12.5f, false);
    }

    private void open() {
        LinearLayout root = new LinearLayout(c); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Neo.GROUND);

        LinearLayout head = new LinearLayout(c); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding((int) (16 * d), (int) (12 * d), (int) (10 * d), (int) (8 * d));
        head.addView(Neo.iconTile(c, "map", Neo.CYAN, 40), new LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d)));
        LinearLayout tcol = new LinearLayout(c); tcol.setOrientation(LinearLayout.VERTICAL); tcol.setPadding((int) (12 * d), 0, 0, 0);
        tcol.addView(Neo.label(c, Txt.s(R.string.packet_map_title), Neo.TEXT, 20, true)); tcol.addView(status);
        head.addView(tcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView close = Neo.iconButton(c, "close", Neo.CYAN, Txt.s(R.string.packet_map_cd_close));
        head.addView(close);
        root.addView(head);

        LinearLayout pillRow = new LinearLayout(c); pillRow.setPadding((int) (12 * d), 0, (int) (12 * d), (int) (8 * d));
        String[] names = { Txt.s(R.string.packet_map_pill_coverage), "APRS", Txt.s(R.string.packet_map_pill_repeaters), Txt.s(R.string.packet_map_pill_channels), Txt.s(R.string.packet_map_pill_help) };
        int[] accents = { Neo.VIOLET, Neo.GREEN, Neo.CYAN, Neo.AMBER, Neo.MUTED };
        final android.content.SharedPreferences mp = c.getSharedPreferences("map", Context.MODE_PRIVATE);   // which layers were on last time
        final String[] showKey = { null, "showAprs", "showRepeaters", "showChannels", null };
        for (int i = 1; i <= 3; i++) if (!mp.getBoolean(showKey[i], true)) { if (i == 2) { map.setVisible(MapView.REPEATER_FM, false); map.setVisible(MapView.REPEATER_DMR, false); } else map.setVisible(i == 1 ? MapView.STATION : MapView.CHANNEL, false); }
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            TextView p = Neo.label(c, names[i], accents[i], 14, true); p.setGravity(Gravity.CENTER);
            p.setPadding((int) (14 * d), (int) (8 * d), (int) (14 * d), (int) (8 * d));
            Neo.pillStyle(p, i >= 1 && i <= 3 && mp.getBoolean(showKey[i], true), accents[i]);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); pl.rightMargin = (int) (8 * d);
            pillRow.addView(p, pl); pills[i] = p;
            p.setOnClickListener(v -> {
                if (idx == 4) { help(); return; }
                if (idx == 0) { coverageSheet(); return; }
                boolean on;
                if (idx == 2) { on = !map.isVisible(MapView.REPEATER_FM); map.setVisible(MapView.REPEATER_FM, on); map.setVisible(MapView.REPEATER_DMR, on); }
                else { int kind = idx == 1 ? MapView.STATION : MapView.CHANNEL; on = !map.isVisible(kind); map.setVisible(kind, on); }
                Neo.pillStyle(p, on, accents[idx]);
                mp.edit().putBoolean(showKey[idx], on).apply();
            });
        }
        HorizontalScrollView hs = new HorizontalScrollView(c); hs.setHorizontalScrollBarEnabled(false); hs.addView(pillRow);
        root.addView(hs);

        FrameLayout frame = new FrameLayout(c);
        frame.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout tools = new LinearLayout(c); tools.setOrientation(LinearLayout.VERTICAL);
        ImageView plus = floating("plus", Txt.s(R.string.packet_map_cd_zoom_in)), minus = floating("minus", Txt.s(R.string.packet_map_cd_zoom_out)), locate = floating("locate", Txt.s(R.string.packet_map_cd_me)), fit = floating("expand", Txt.s(R.string.packet_map_cd_fit)), here = floating("repeaters", Txt.s(R.string.packet_map_cd_here));
        for (ImageView b : new ImageView[] { plus, minus, locate, fit, here }) { LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams((int) (46 * d), (int) (46 * d)); bl.bottomMargin = (int) (8 * d); tools.addView(b, bl); }
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        tl.setMargins(0, (int) (12 * d), (int) (12 * d), 0);
        frame.addView(tools, tl);
        legend = new LinearLayout(c); legend.setOrientation(LinearLayout.VERTICAL); legend.setVisibility(View.GONE);
        legend.setBackground(Neo.sheet(c, Neo.alpha(Neo.VIOLET, 0x55), 14)); legend.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (10 * d));
        legend.setOnClickListener(v -> coverageSheet());
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams((int) (230 * d), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP);
        gl.setMargins((int) (12 * d), (int) (12 * d), 0, 0);
        frame.addView(legend, gl);

        card.setOrientation(LinearLayout.VERTICAL); card.setBackground(Neo.sheet(c, Neo.alpha(Neo.CYAN, 0x66), 18));
        card.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (12 * d));
        card.addView(cardTitle); card.addView(cardSub); cardBody.setPadding(0, (int) (6 * d), 0, (int) (4 * d)); card.addView(cardBody);
        cardActions.setBaselineAligned(false); card.addView(cardActions);
        card.setVisibility(View.GONE);
        card.setOnClickListener(v -> { });
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cl.setMargins((int) (12 * d), 0, (int) (12 * d), (int) (30 * d));
        frame.addView(card, cl);
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dlg = new Dialog(c, android.R.style.Theme_Black_NoTitleBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) { w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Neo.GROUND)); w.setStatusBarColor(Neo.GROUND); w.setNavigationBarColor(Neo.BAR); }
        dlg.setOnDismissListener(x -> { stopFollowing(); svc.coverage().setOnData(null); ui.removeCallbacks(liveRefresh); map.shutdown(); });
        dlg.show();

        close.setOnClickListener(v -> dlg.dismiss());
        plus.setOnClickListener(v -> map.zoomBy(1));
        minus.setOnClickListener(v -> map.zoomBy(-1));
        locate.setOnClickListener(v -> { if (me != null) map.center(me.getLatitude(), me.getLongitude(), Math.max(map.zoom(), 11)); else Toast.makeText(c, Txt.s(R.string.packet_map_err_no_fix), Toast.LENGTH_SHORT).show(); });
        fit.setOnClickListener(v -> fitVisible());
        here.setOnClickListener(v -> repeatersHere());
        map.setOnSelect(this::showCard);
        map.setOnCell(this::showCellCard);

        me = svc.lastKnownLocation();
        if (me != null) { map.center(me.getLatitude(), me.getLongitude(), 10); map.setMe(me.getLatitude(), me.getLongitude(), me.hasAccuracy() ? me.getAccuracy() : 0); }
        startFollowing();
        svc.coverage().setOnData(() -> { ui.removeCallbacks(liveRefresh); ui.postDelayed(liveRefresh, 1500); });
        load();
        android.content.SharedPreferences cp = c.getSharedPreferences("coverage", Context.MODE_PRIVATE);
        if (!cp.getBoolean("introShown", false)) { cp.edit().putBoolean("introShown", true).apply(); ui.postDelayed(this::coverageIntro, 600); }
        else if (svc.coverage().store().rxCount() + svc.coverage().store().txCount() > 0) setLayer(MapView.LAYER_RX);
    }

    // ---- your position, live while the map is open ----
    private android.location.LocationListener follow;
    private void startFollowing() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            follow = l -> {
                Location cur = me;   // a network fix only replaces a GPS one when GPS has been quiet for 10 s
                if (cur != null && !android.location.LocationManager.GPS_PROVIDER.equals(l.getProvider()) && android.location.LocationManager.GPS_PROVIDER.equals(cur.getProvider())
                        && l.getElapsedRealtimeNanos() - cur.getElapsedRealtimeNanos() < 10_000_000_000L) return;
                me = l; map.setMe(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 0);
            };
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, follow, Looper.getMainLooper());
            try { lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 3000, 0, follow, Looper.getMainLooper()); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }   // no permission: the dot stays where the last fix put it
    }
    private void stopFollowing() {
        try { if (follow != null) ((android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE)).removeUpdates(follow); } catch (Throwable ignored) { }
        follow = null;
    }

    private ImageView floating(String icon, String desc) {
        ImageView b = Neo.iconButton(c, icon, Neo.CYAN, desc);
        b.setBackground(Neo.sheet(c, Neo.alpha(Neo.CYAN, 0x55), 23));
        int p = (int) (11 * d); b.setPadding(p, p, p, p);
        return b;
    }

    private void fitVisible() {
        List<MapView.Marker> v = new ArrayList<>();
        for (MapView.Marker m : all) if (map.isVisible(m.kind)) v.add(m);
        if (v.isEmpty()) { Toast.makeText(c, Txt.s(R.string.packet_map_err_nothing), Toast.LENGTH_SHORT).show(); return; }
        map.fit(v);
    }

    /** Stations and channels at once; repeaters from the Nearby repeaters cache (or the network) in the background. */
    private void load() {
        new Thread(() -> {
            final List<MapView.Marker> ms = new ArrayList<>();
            int st = 0, ch = 0, rp = 0;
            try {
                if (svc.packet() != null) for (PacketStore.Station s : svc.packet().store().stations(500)) {
                    if (s.lat == 0 && s.lon == 0) continue;
                    ms.add(new MapView.Marker(s.lat, s.lon, MapView.STATION, s.call, s)); st++;
                }
            } catch (Throwable ignored) { }
            try {
                for (ChannelStore.Channel x : svc.store().all()) {
                    if (x.lat == 0 && x.lon == 0) continue;
                    ms.add(new MapView.Marker(x.lat, x.lon, MapView.CHANNEL, x.name, x)); ch++;
                }
            } catch (Throwable ignored) { }
            final int fst = st, fch = ch;
            ui.post(() -> { publish(ms, fst, fch, -1); if (me == null) fitVisible(); });
            if (me == null) return;
            try {
                int mi = c.getSharedPreferences("repeaters", Context.MODE_PRIVATE).getInt("radiusMi", 30);
                List<Repeater> reps = new RepeaterService(c, svc.store()).nearby(me.getLatitude(), me.getLongitude(), GeoUtil.radiusKm(mi), false, new StringBuilder());
                for (Repeater r : reps) {
                    if (!r.hasCoords) continue;
                    ms.add(new MapView.Marker(r.lat, r.lon, r.dmr ? MapView.REPEATER_DMR : MapView.REPEATER_FM, (r.callsign == null ? "?" : r.callsign) + " " + String.format(Locale.US, "%.3f", r.rxHz / 1e6), r)); rp++;
                }
            } catch (Throwable ignored) { }
            final int frp = rp;
            ui.post(() -> { if (!repeatersMoved) publish(ms, fst, fch, frp); });   // unless Repeaters here was used meanwhile
        }, "map-load").start();
    }

    /** The repeaters around the middle of the map (wherever it was moved), in place of those around the phone. */
    private volatile boolean repeatersMoved;
    private void repeatersHere() {
        repeatersMoved = true;
        final double lat = map.centerLat(), lon = map.centerLon();
        final int mi = c.getSharedPreferences("repeaters", Context.MODE_PRIVATE).getInt("radiusMi", 30);
        status.setText(Txt.s(R.string.packet_map_st_finding_here));
        new Thread(() -> {
            final List<MapView.Marker> reps = new ArrayList<>();
            try {
                for (Repeater r : new RepeaterService(c, svc.store()).nearby(lat, lon, GeoUtil.radiusKm(mi), false, new StringBuilder())) {
                    if (!r.hasCoords) continue;
                    reps.add(new MapView.Marker(r.lat, r.lon, r.dmr ? MapView.REPEATER_DMR : MapView.REPEATER_FM, (r.callsign == null ? "?" : r.callsign) + " " + String.format(Locale.US, "%.3f", r.rxHz / 1e6), r));
                }
            } catch (Throwable ignored) { }
            ui.post(() -> {
                List<MapView.Marker> ms = new ArrayList<>(); for (MapView.Marker m : all) if (m.kind != MapView.REPEATER_FM && m.kind != MapView.REPEATER_DMR) ms.add(m);
                ms.addAll(reps);
                publish(ms, counts[0], counts[2], reps.size());
                if (!map.isVisible(MapView.REPEATER_FM)) {
                    map.setVisible(MapView.REPEATER_FM, true); map.setVisible(MapView.REPEATER_DMR, true); Neo.pillStyle(pills[2], true, Neo.CYAN);
                    c.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putBoolean("showRepeaters", true).apply();
                }
                status.setText(reps.isEmpty() ? Txt.s(R.string.packet_map_st_none_here, GeoUtil.radiusText(mi))
                        : Txt.p(R.plurals.packet_map_st_n_here, reps.size(), reps.size(), GeoUtil.radiusText(mi)));
            });
        }, "map-repeaters-here").start();
    }

    private void publish(List<MapView.Marker> ms, int st, int ch, int rp) {
        all.clear(); all.addAll(ms); map.setMarkers(new ArrayList<>(ms));
        counts[0] = st; counts[1] = rp; counts[2] = ch;
        pills[1].setText("APRS " + st); pills[2].setText(rp < 0 ? Txt.s(R.string.packet_map_pill_repeaters_wait) : Txt.s(R.string.packet_map_pill_repeaters_n, rp)); pills[3].setText(Txt.s(R.string.packet_map_pill_channels_n, ch));   // i18n: mode name (APRS)
        status.setText(Txt.s(me == null ? R.string.packet_map_st_no_fix : rp < 0 ? R.string.packet_map_st_finding : R.string.packet_map_st_around));
    }

    private String dist(double lat, double lon) {
        if (me == null) return "";
        double m = GeoUtil.haversineMeters(me.getLatitude(), me.getLongitude(), lat, lon), mi = com.macdmr.radio.region.Region.km() ? m / 1000 : m * 0.000621371;
        String u = com.macdmr.radio.region.Region.km() ? " km" : " mi";   // i18n: unit symbols
        double brg = GeoUtil.bearingDegrees(me.getLatitude(), me.getLongitude(), lat, lon);
        String[] dirs = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
        return (mi < 10 ? String.format(Locale.US, "%.1f", mi) : String.format(Locale.US, "%.0f", mi)) + u + " " + dirs[(int) Math.round(brg / 45) % 8];
    }

    private static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 90) return Txt.s(R.string.packet_map_ago_s, s); if (s < 5400) return Txt.s(R.string.packet_map_ago_min, s / 60); if (s < 172800) return Txt.s(R.string.packet_map_ago_h, s / 3600); return Txt.s(R.string.packet_map_ago_days, s / 86400);
    }

    private void showCard(final MapView.Marker m) {
        if (m == null) { card.setVisibility(View.GONE); return; }
        cardActions.removeAllViews();
        final String geo = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", m.lat, m.lon, m.lat, m.lon);   // i18n: geo URI
        if (m.data instanceof PacketStore.Station) {
            PacketStore.Station s = (PacketStore.Station) m.data;
            cardTitle.setText(s.call); cardTitle.setTextColor(Neo.GREEN);
            cardSub.setText(Txt.s(R.string.packet_map_station_sub, dist(s.lat, s.lon))); cardSub.setTextColor(Neo.GREEN);
            boolean via = s.via != null && !s.via.isEmpty();
            StringBuilder b = new StringBuilder(s.count > 1 ? (via ? Txt.s(R.string.packet_map_heard_times_via, ago(s.lastTs), s.count, s.via) : Txt.s(R.string.packet_map_heard_times, ago(s.lastTs), s.count))
                                                            : (via ? Txt.s(R.string.packet_map_heard_via, ago(s.lastTs), s.via) : Txt.s(R.string.packet_map_heard, ago(s.lastTs))));
            if (s.speedKmh >= 0) { String mph = String.format(Locale.US, "%.0f", s.speedKmh * 0.621371); b.append('\n').append(s.courseDeg >= 0 ? Txt.s(R.string.packet_map_moving_toward, mph, s.courseDeg) : Txt.s(R.string.packet_map_moving, mph)); }
            if (s.alt != 0) b.append('\n').append(Txt.s(R.string.packet_map_altitude, Math.round(s.alt * 3.28084)));
            if (s.comment != null && !s.comment.trim().isEmpty()) b.append("\n").append(s.comment.trim());
            cardBody.setText(b);
        } else if (m.data instanceof Repeater) {
            final Repeater r = (Repeater) m.data;
            int col = r.dmr ? Neo.VIOLET : Neo.CYAN;
            cardTitle.setText(r.callsign == null ? Txt.s(R.string.packet_map_repeater) : r.callsign); cardTitle.setTextColor(col);
            cardSub.setText(Txt.s(R.string.packet_map_repeater_sub, r.dmr ? "DMR" + (r.cc >= 0 ? " CC" + r.cc : "") : "FM" + (hasTone(r.toneUp) ? " " + r.toneUp : ""), dist(r.lat, r.lon))); cardSub.setTextColor(col);
            cardBody.setText(Txt.s(R.string.packet_map_repeater_freqs, String.format(Locale.US, "%.4f", r.rxHz / 1e6), String.format(Locale.US, "%.4f", r.txHz / 1e6)) + (r.city != null ? "\n" + r.city + (r.region != null ? ", " + r.region : "") : "") + (r.approx ? "\n" + Txt.s(R.string.packet_map_repeater_approx) : ""));
            action(Txt.s(R.string.packet_map_details), "repeaters", col, v -> RepeatersDialog.showDetails(c, svc, r, me != null ? me.getLatitude() : r.lat, me != null ? me.getLongitude() : r.lon));
            action(Txt.s(R.string.packet_map_pill_coverage), "signal", Neo.VIOLET, v -> { focus(com.macdmr.radio.coverage.CoverageMath.repeaterKey((int) r.rxHz, r.lat, r.lon), r.callsign == null ? Txt.s(R.string.packet_map_repeater) : r.callsign, r.lat, r.lon, r.rxHz); setLayer(layer == MapView.LAYER_NONE ? MapView.LAYER_RX : layer); card.setVisibility(View.GONE); });
        } else if (m.data instanceof ChannelStore.Channel) {
            final ChannelStore.Channel x = (ChannelStore.Channel) m.data;
            cardTitle.setText(x.name); cardTitle.setTextColor(Neo.AMBER);
            cardSub.setText(Txt.s(R.string.packet_map_channel_sub, x.number, x.isDigital() ? "DMR" : Txt.s(R.string.packet_map_analog), dist(x.lat, x.lon))); cardSub.setTextColor(Neo.AMBER);
            cardBody.setText(Txt.s(R.string.packet_map_channel_freqs, String.format(Locale.US, "%.4f", x.rxFreq / 1e6), String.format(Locale.US, "%.4f", x.txFreq / 1e6)));
            action(Txt.s(R.string.packet_map_use_channel), "channels", Neo.AMBER, v -> { String e = svc.trySelectChannel(x.number); Toast.makeText(c, e != null ? e : Txt.s(R.string.packet_map_channel_toast, x.number, x.name), e != null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show(); });
            action(Txt.s(R.string.packet_map_pill_coverage), "signal", Neo.VIOLET, v -> { focus(com.macdmr.radio.coverage.CoverageMath.repeaterKey(x.rxFreq, x.lat, x.lon), x.name, x.lat, x.lon, x.rxFreq); setLayer(layer == MapView.LAYER_NONE ? MapView.LAYER_RX : layer); card.setVisibility(View.GONE); });
        }
        if (!(m.data instanceof ChannelStore.Channel) && !(m.data instanceof Repeater)) action(Txt.s(R.string.packet_map_open_maps), "map", Neo.CYAN, v -> { try { c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(geo))); } catch (Throwable t) { Toast.makeText(c, Txt.s(R.string.packet_map_no_maps), Toast.LENGTH_SHORT).show(); } });
        card.setVisibility(View.VISIBLE);
    }

    private static boolean hasTone(String t) {
        if (t == null || t.trim().isEmpty()) return false;
        try { return Double.parseDouble(t.trim()) > 0; } catch (NumberFormatException e) { return true; }   // DCS codes such as D023N
    }

    private void action(String label, String icon, int accent, View.OnClickListener l) {
        TextView b = Neo.button(c, label, icon, accent); b.setTextSize(14);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bl.setMargins(cardActions.getChildCount() == 0 ? 0 : (int) (4 * d), (int) (8 * d), cardActions.getChildCount() == 0 ? (int) (4 * d) : 0, 0);
        b.setOnClickListener(l); cardActions.addView(b, bl);
    }

    // ---- coverage ---------------------------------------------------------------------------------------

    private void setLayer(int l) {
        if (l != layer) card.setVisibility(View.GONE);
        layer = l;
        Neo.pillStyle(pills[0], l != MapView.LAYER_NONE, Neo.VIOLET);
        if (l == MapView.LAYER_NONE) { map.setCells(null, l); map.setPredicted(null); legend.setVisibility(View.GONE); return; }
        final String key = focusKey;
        updatePrediction();
        new Thread(() -> {
            com.macdmr.radio.coverage.CoverageStore st = svc.coverage().store();
            final List<com.macdmr.radio.coverage.CoverageStore.Cell> cells = l == MapView.LAYER_RX ? st.rxCells(key) : st.txCells(key);
            ui.post(() -> { if (layer == l) { map.setCells(cells, l); measuredSquares = cells.size(); showLegend(cells.size()); } });
        }, "coverage-cells").start();
    }

    private int measuredSquares;
    private long predAt;

    /** New readings while the map is open: redraw the measured squares now; re-calibrate the prediction at most every 2 minutes. */
    private final Runnable liveRefresh = this::refreshLive;
    private void refreshLive() {
        if (layer == MapView.LAYER_NONE) return;
        final int l = layer; final String key = focusKey;
        new Thread(() -> {
            com.macdmr.radio.coverage.CoverageStore st = svc.coverage().store();
            final List<com.macdmr.radio.coverage.CoverageStore.Cell> cells = l == MapView.LAYER_RX ? st.rxCells(key) : st.txCells(key);
            ui.post(() -> {
                if (layer != l || (key == null ? focusKey != null : !key.equals(focusKey))) return;
                map.setCells(cells, l); measuredSquares = cells.size(); showLegend(cells.size());
                if (pred != null && System.currentTimeMillis() - predAt > 120_000) { pred = null; updatePrediction(); }
            });
        }, "coverage-live").start();
    }

    /** Run (or reuse) the terrain prediction for the focused repeater and show it under the measured squares. */
    private void updatePrediction() {
        if (!predictOn() || focusKey == null || layer == MapView.LAYER_NONE) { map.setPredicted(null); predStatus = ""; return; }
        if (pred != null) { map.setPredicted(layer == MapView.LAYER_RX ? pred.rx : pred.tx); predStatus = pred.summary(layer == MapView.LAYER_RX); return; }
        final int run = ++predRun;
        final String key = focusKey; final double lat = focusLat, lon = focusLon, hz = focusHz, ant = antennaM(), erp = powerW(), rad = rangeMi() * 1609.344;
        final android.location.Location here = svc.lastKnownLocation();
        final double meLat = here != null ? here.getLatitude() : Double.NaN, meLon = here != null ? here.getLongitude() : Double.NaN;
        predStatus = Txt.s(R.string.packet_map_pred_starting);
        new Thread(() -> {
            com.macdmr.radio.coverage.Prediction.Result r; String err = null;
            try {
                r = com.macdmr.radio.coverage.Prediction.run(c, svc.coverage().store(), key, lat, lon, hz, ant, erp, rad, meLat, meLon, text -> {
                    if (run != predRun) return false;
                    ui.post(() -> { if (run == predRun) { predStatus = Txt.s(R.string.packet_map_pred_progress, text); showLegend(measuredSquares); } });
                    return true;
                });
            } catch (Throwable t) { r = null; err = t.getMessage(); }
            final com.macdmr.radio.coverage.Prediction.Result fr = r; final String fe = err;
            ui.post(() -> {
                if (run != predRun) return;
                if (fr == null) { predStatus = fe == null ? "" : Txt.s(R.string.packet_map_pred_failed, fe); showLegend(measuredSquares); return; }
                pred = fr; predAt = System.currentTimeMillis(); map.setPredicted(layer == MapView.LAYER_RX ? fr.rx : fr.tx);
                predStatus = Txt.s(R.string.packet_map_pred_done, fr.summary(layer == MapView.LAYER_RX));
                showLegend(measuredSquares);
            });
        }, "coverage-predict").start();
    }

    private void showLegend(int squares) {
        legend.removeAllViews();
        String who = focusKey == null ? Txt.s(R.string.packet_map_all_repeaters) : focusName;
        legend.addView(Neo.label(c, Txt.s(layer == MapView.LAYER_RX ? R.string.packet_map_legend_heard : R.string.packet_map_legend_keyups, who), Neo.TEXT, 13.5f, true));
        if (layer == MapView.LAYER_RX) {
            View bar = new View(c) {
                final android.graphics.Paint p = new android.graphics.Paint();
                @Override protected void onDraw(android.graphics.Canvas cv) {
                    int n = 40; float w = getWidth() / (float) n;
                    for (int i = 0; i < n; i++) {
                        int dbm = com.macdmr.radio.coverage.CoverageMath.SCALE_LOW + (com.macdmr.radio.coverage.CoverageMath.SCALE_HIGH - com.macdmr.radio.coverage.CoverageMath.SCALE_LOW) * i / (n - 1);
                        p.setColor(com.macdmr.radio.coverage.CoverageMath.signalColor(dbm)); cv.drawRect(i * w, 0, (i + 1) * w + 1, getHeight(), p);
                    }
                }
            };
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (8 * d)); bl.topMargin = (int) (6 * d);
            legend.addView(bar, bl);
            LinearLayout ends = new LinearLayout(c);
            TextView lo = Neo.label(c, Txt.s(R.string.packet_map_legend_weak, com.macdmr.radio.coverage.CoverageMath.sUnits(com.macdmr.radio.coverage.CoverageMath.SCALE_LOW)), Neo.MUTED, 11.5f, false),
                    hi = Neo.label(c, Txt.s(R.string.packet_map_legend_strong, com.macdmr.radio.coverage.CoverageMath.sUnits(com.macdmr.radio.coverage.CoverageMath.SCALE_HIGH)), Neo.MUTED, 11.5f, false); hi.setGravity(Gravity.END);
            ends.addView(lo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); ends.addView(hi, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            legend.addView(ends);
        } else {
            LinearLayout keys = new LinearLayout(c); keys.setPadding(0, (int) (4 * d), 0, 0);
            keys.addView(Neo.label(c, Txt.s(R.string.packet_map_key_keyed), Neo.GREEN, 13, true));
            TextView no = Neo.label(c, Txt.s(R.string.packet_map_key_no_reply), Neo.RED, 13, true); no.setPadding((int) (14 * d), 0, 0, 0); keys.addView(no);
            legend.addView(keys);
        }
        legend.addView(Neo.label(c, squares == 0 ? Txt.s(pred != null ? R.string.packet_map_legend_none_rep : R.string.packet_map_legend_none) : Txt.p(R.plurals.packet_map_legend_squares, squares, squares), Neo.MUTED, 11.5f, false));
        if (!predStatus.isEmpty()) { TextView ps = Neo.label(c, predStatus, Neo.VIOLET_LIGHT, 11.5f, false); ps.setPadding(0, (int) (3 * d), 0, 0); legend.addView(ps); }
        legend.setVisibility(View.VISIBLE);
    }

    private void showCellCard(com.macdmr.radio.coverage.CoverageStore.Cell cell) {
        cardActions.removeAllViews();
        if (cell.predicted) {
            String where = dist((cell.s + cell.n) / 2, (cell.w + cell.e) / 2);
            if (layer == MapView.LAYER_RX) {
                cardTitle.setText(Txt.s(R.string.packet_map_cell_about, cell.value, com.macdmr.radio.coverage.CoverageMath.sUnits(cell.value))); cardTitle.setTextColor(com.macdmr.radio.coverage.CoverageMath.signalColor(cell.value));
                cardSub.setText(Txt.s(R.string.packet_map_cell_predicted, focusName, where));
                cardBody.setText(pred == null ? Txt.s(R.string.packet_map_cell_pred_rx_none, 30) : Txt.s(pred.antennaFitted ? R.string.packet_map_cell_pred_rx_fitted : R.string.packet_map_cell_pred_rx, Math.round(pred.antennaUsed), pred.summary(true)));
            } else {
                cardTitle.setText(Txt.s(R.string.packet_map_cell_should_key, cell.value)); cardTitle.setTextColor(Neo.GREEN);
                cardSub.setText(Txt.s(R.string.packet_map_cell_predicted, focusName, where));
                cardBody.setText(pred == null ? Txt.s(R.string.packet_map_cell_pred_tx_none) : Txt.s(R.string.packet_map_cell_pred_tx, pred.summary(false)));
            }
            cardSub.setTextColor(Neo.VIOLET);
            card.setVisibility(View.VISIBLE);
            return;
        }
        if (layer == MapView.LAYER_RX) {
            cardTitle.setText(Txt.s(R.string.packet_map_cell_dbm, cell.value, com.macdmr.radio.coverage.CoverageMath.sUnits(cell.value))); cardTitle.setTextColor(com.macdmr.radio.coverage.CoverageMath.signalColor(cell.value));
            cardSub.setText(Txt.s(R.string.packet_map_cell_heard, focusKey == null ? Txt.s(R.string.packet_map_cell_best) : focusName, dist((cell.s + cell.n) / 2, (cell.w + cell.e) / 2))); cardSub.setTextColor(Neo.VIOLET);
            cardBody.setText(Txt.p(R.plurals.packet_map_cell_typical, cell.count, cell.count));
        } else {
            boolean ok = cell.keyed > 0;
            cardTitle.setText(ok ? Txt.s(R.string.packet_map_cell_keyed, cell.keyed, cell.count) : Txt.p(R.plurals.packet_map_cell_no_reply, cell.failed, cell.failed)); cardTitle.setTextColor(ok ? Neo.GREEN : Neo.RED);
            cardSub.setText((focusKey == null ? Txt.s(R.string.packet_map_cell_any) : focusName) + " \u00b7 " + dist((cell.s + cell.n) / 2, (cell.w + cell.e) / 2)); cardSub.setTextColor(Neo.VIOLET);
            cardBody.setText(Txt.s(ok ? R.string.packet_map_cell_keyed_body : R.string.packet_map_cell_no_reply_body));
        }
        card.setVisibility(View.VISIBLE);
    }

    private void coverageIntro() {
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.packet_cov_title), "signal");
        box.addView(Neo.label(c, Txt.s(R.string.packet_cov_new), Neo.VIOLET, 12, true));
        box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_intro_1)));
        box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_intro_2)));
        box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_intro_3)));
        TextView go = Neo.button(c, Txt.s(R.string.packet_cov_turn_on), "signal", Neo.VIOLET);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); gl.topMargin = (int) (12 * d);
        box.addView(go, gl);
        final Dialog sh = Neo.showSheet(c, box);
        go.setOnClickListener(v -> { svc.setCoverageLogging(true); if (sh != null) sh.dismiss(); setLayer(MapView.LAYER_RX); Toast.makeText(c, Txt.s(R.string.packet_cov_on), Toast.LENGTH_SHORT).show(); });
    }

    private void coverageSheet() {
        final com.macdmr.radio.coverage.CoverageLogger lg = svc.coverage();
        final com.macdmr.radio.coverage.CoverageStore st = lg.store();
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.packet_cov_title), "signal");
        box.addView(Neo.sheetHint(c, Txt.s(svc.profile().rssi ? R.string.packet_cov_hint : R.string.packet_cov_hint_no_rssi)));

        Neo.NeonSwitch sw = new Neo.NeonSwitch(c); sw.setChecked(lg.enabled());
        final int rxN = st.rxCount(), txN = st.txCount();
        LinearLayout row = Neo.switchRow(c, "signal", Neo.VIOLET, Txt.s(R.string.packet_cov_log), Txt.s(R.string.packet_cov_log_sub, rxN, txN), sw);
        row.setBackground(Neo.rowCard(c));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); rl.topMargin = (int) (10 * d);
        box.addView(row, rl);
        sw.setOnChange(on -> { svc.setCoverageLogging(on); Toast.makeText(c, Txt.s(on ? R.string.packet_cov_on : R.string.packet_cov_off), Toast.LENGTH_SHORT).show(); });
        TextView test = Neo.button(c, Txt.s(R.string.packet_cov_test), "signal", Neo.VIOLET); test.setTextSize(14);
        LinearLayout.LayoutParams tl2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); tl2.topMargin = (int) (8 * d);
        box.addView(test, tl2);
        box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_test_hint)));
        test.setOnClickListener(v -> {
            ChannelStore.Channel cur = svc.current();
            new android.app.AlertDialog.Builder(c).setTitle(Txt.s(R.string.packet_cov_test_title))
                    .setMessage(Txt.s(R.string.packet_cov_test_msg, cur == null ? Txt.s(R.string.packet_cov_test_this_channel) : cur.name))
                    .setPositiveButton(Txt.s(R.string.packet_pkt_dtmf_transmit), (dd, w) -> { String e = svc.keyupTest(); Toast.makeText(c, e == null ? Txt.s(R.string.packet_cov_test_sending) : e, Toast.LENGTH_LONG).show(); })
                    .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
        });

        box.addView(Neo.sheetSection(c, Txt.s(R.string.packet_cov_show_section)));
        LinearLayout lay = new LinearLayout(c);
        String[] ln = { Txt.s(R.string.common_off), Txt.s(R.string.packet_cov_layer_heard), Txt.s(R.string.packet_cov_layer_keyups) }; final TextView[] lp = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int li = i;
            TextView t = Neo.label(c, ln[i], Neo.VIOLET, 14, true); t.setGravity(Gravity.CENTER); t.setPadding(0, (int) (9 * d), 0, (int) (9 * d));
            Neo.pillStyle(t, layer == i, Neo.VIOLET); lp[i] = t;
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); pl.setMargins(i == 0 ? 0 : (int) (4 * d), 0, i == 2 ? 0 : (int) (4 * d), 0);
            lay.addView(t, pl);
            t.setOnClickListener(v -> { setLayer(li); for (int k = 0; k < 3; k++) Neo.pillStyle(lp[k], k == li, Neo.VIOLET); });
        }
        box.addView(lay);

        box.addView(Neo.sheetSection(c, Txt.s(R.string.packet_map_repeater)));
        final List<com.macdmr.radio.coverage.CoverageStore.Repeater> reps = st.repeaters();
        final List<TextView> rows = new ArrayList<>();
        TextView allRow = repRow(Txt.s(R.string.packet_map_all_repeaters), Txt.s(R.string.packet_cov_all_sub), focusKey == null);
        box.addView(allRow); rows.add(allRow);
        allRow.setOnClickListener(v -> { focus(null, null, 0, 0, 0); for (TextView r : rows) Neo.pillStyle(r, r == v, Neo.VIOLET); if (layer == MapView.LAYER_NONE) setLayer(MapView.LAYER_RX); else setLayer(layer); });
        boolean listed = focusKey == null; for (com.macdmr.radio.coverage.CoverageStore.Repeater r : reps) if (r.key.equals(focusKey)) listed = true;
        if (!listed) {                                       // focused from the map, nothing measured yet: still show it as the selection
            com.macdmr.radio.coverage.CoverageStore.Repeater f = new com.macdmr.radio.coverage.CoverageStore.Repeater();
            f.key = focusKey; f.name = focusName; f.rxHz = (int) focusHz; f.lat = focusLat; f.lon = focusLon; reps.add(0, f);
        }
        for (final com.macdmr.radio.coverage.CoverageStore.Repeater r : reps) {
            TextView t = repRow(r.name + String.format(Locale.US, "  %.4f", r.rxHz / 1e6), r.txCount > 0 ? Txt.s(R.string.packet_cov_rep_sub_keyed, r.rxCount, r.txCount, r.keyed) : Txt.s(R.string.packet_cov_rep_sub, r.rxCount, r.txCount), r.key.equals(focusKey));
            box.addView(t); rows.add(t);
            t.setOnClickListener(v -> {
                focus(r.key, r.name, r.lat, r.lon, r.rxHz); for (TextView x : rows) Neo.pillStyle(x, x == v, Neo.VIOLET);
                setLayer(layer == MapView.LAYER_NONE ? MapView.LAYER_RX : layer);
                map.center(r.lat, r.lon, Math.min(map.zoom(), 11));
            });
        }
        box.addView(Neo.sheetSection(c, Txt.s(R.string.packet_cov_pred_section)));
        if (focusKey == null) box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_pred_pick)));
        else {
            Neo.NeonSwitch ps = new Neo.NeonSwitch(c); ps.setChecked(predictOn());
            LinearLayout prow = Neo.switchRow(c, "map", Neo.VIOLET, Txt.s(R.string.packet_cov_pred_show), Txt.s(R.string.packet_cov_pred_show_sub, focusName), ps);
            prow.setBackground(Neo.rowCard(c)); box.addView(prow);
            ps.setOnChange(on -> { cprefs().edit().putBoolean("predict", on).apply(); updatePrediction(); showLegend(measuredSquares); });
            box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_ant_label)));
            box.addView(choiceRow(new int[] { 0, 15, 30, 60, 100 }, new String[] { Txt.s(R.string.packet_cov_auto), "15 m", "30 m", "60 m", "100 m" }, antennaM(), " m", Txt.s(R.string.packet_cov_ant_ask), 3, 600,
                    v -> { cprefs().edit().putInt("ant_" + focusKey, v).apply(); pred = null; updatePrediction(); }));
            box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_ant_hint)));
            box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_pwr_hint)));
            box.addView(choiceRow(new int[] { 0, 25, 100, 300, 1000 }, new String[] { Txt.s(R.string.packet_cov_unknown), "25 W", "100 W", "300 W", "1000 W" }, powerW(), " W", Txt.s(R.string.packet_cov_pwr_ask), 1, 100000,
                    v -> { cprefs().edit().putInt("pwr_" + focusKey, v).apply(); pred = null; updatePrediction(); }));
            box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_range_hint)));
            box.addView(choiceRow(new int[] { 0, 10, 20, 30, 50 }, new String[] { Txt.s(R.string.packet_cov_auto), "10 mi", "20 mi", "30 mi", "50 mi" }, rangeMi(), null, null, 0, 0, v -> { cprefs().edit().putInt("predRangeMi", v).apply(); pred = null; updatePrediction(); }));
            box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_cov_tiles_hint)));
        }
        int located = 0; for (ChannelStore.Channel x : svc.store().all()) if (x.lat != 0 || x.lon != 0) located++;
        box.addView(Neo.sheetHint(c, Txt.s(reps.isEmpty() ? R.string.packet_cov_located_none : R.string.packet_cov_located, located)));

        LinearLayout btns = new LinearLayout(c); btns.setBaselineAligned(false);
        TextView exp = Neo.button(c, Txt.s(R.string.packet_cov_export), "backup", Neo.GREEN), clr = Neo.button(c, Txt.s(R.string.common_clear), "close", Neo.RED);
        exp.setTextSize(14); clr.setTextSize(14);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); b1.setMargins(0, (int) (14 * d), (int) (4 * d), 0);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); b2.setMargins((int) (4 * d), (int) (14 * d), 0, 0);
        btns.addView(exp, b1); btns.addView(clr, b2);
        box.addView(btns);
        final Dialog sh = Neo.showSheet(c, box);
        exp.setOnClickListener(v -> new Thread(() -> {
            String msg;
            try { java.io.File dir = com.macdmr.radio.coverage.CoverageExport.export(st); msg = Txt.s(R.string.packet_cov_exported, dir.getName()); }
            catch (Throwable t) { msg = Txt.s(R.string.packet_pkt_export_failed, t.getMessage()); }
            final String m2 = msg; ui.post(() -> Toast.makeText(c, m2, Toast.LENGTH_LONG).show());
        }, "coverage-export").start());
        clr.setOnClickListener(v -> new android.app.AlertDialog.Builder(c).setTitle(focusKey == null ? Txt.s(R.string.packet_cov_clear_all) : Txt.s(R.string.packet_cov_clear_one, focusName))
                .setMessage(Txt.s(R.string.packet_cov_clear_msg))
                .setPositiveButton(Txt.s(R.string.common_clear), (dd, w) -> { st.clear(focusKey); if (sh != null) sh.dismiss(); focus(null, null, 0, 0, 0); setLayer(layer); })
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show());
    }

    private interface IntPick { void pick(int v); }
    /**
     * A row of choice pills. With {@code unit} set, a last "Set" pill takes any whole number from {@code min} to
     * {@code max} (it shows the value when the current one is not among the pills).
     */
    private LinearLayout choiceRow(int[] values, String[] labels, int current, String unit, String askTitle, int min, int max, IntPick pick) {
        LinearLayout row = new LinearLayout(c); row.setPadding(0, (int) (4 * d), 0, (int) (4 * d));
        final List<TextView> all = new ArrayList<>();
        boolean listed = false; for (int v : values) if (v == current) listed = true;
        for (int i = 0; i < values.length; i++) {
            final int v = values[i];
            TextView t = Neo.label(c, labels[i], Neo.VIOLET, 14, true); t.setGravity(Gravity.CENTER); t.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            Neo.pillStyle(t, v == current, Neo.VIOLET); all.add(t);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); l.setMargins(i == 0 ? 0 : (int) (3 * d), 0, i == values.length - 1 ? 0 : (int) (3 * d), 0);
            row.addView(t, l);
            t.setOnClickListener(x -> { for (TextView o : all) Neo.pillStyle(o, o == x, Neo.VIOLET); pick.pick(v); });
        }
        if (unit != null) {
            final TextView set = Neo.label(c, listed ? Txt.s(R.string.packet_cov_set) : current + unit, Neo.VIOLET, 14, true); set.setGravity(Gravity.CENTER); set.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            Neo.pillStyle(set, !listed, Neo.VIOLET); all.add(set);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f); l.setMargins((int) (3 * d), 0, 0, 0);
            row.addView(set, l);
            set.setOnClickListener(x -> {
                final android.widget.EditText in = new android.widget.EditText(c); in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); in.setHint(Txt.s(R.string.packet_cov_set_range, min, max));
                new android.app.AlertDialog.Builder(c).setTitle(askTitle).setView(in).setPositiveButton(Txt.s(R.string.packet_cov_set), (dd, w) -> {
                    int v; try { v = Integer.parseInt(in.getText().toString().trim()); } catch (NumberFormatException e) { return; }
                    if (v < min || v > max) { Toast.makeText(c, Txt.s(R.string.packet_cov_set_err, min, max), Toast.LENGTH_SHORT).show(); return; }
                    for (TextView o : all) Neo.pillStyle(o, o == set, Neo.VIOLET); set.setText(v + unit); pick.pick(v);
                }).setNegativeButton(Txt.s(R.string.common_cancel), null).show();
            });
        }
        return row;
    }

    private TextView repRow(String title, String sub, boolean on) {
        TextView t = Neo.label(c, title + "\n" + sub, Neo.TEXT, 14.5f, true);
        t.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
        Neo.pillStyle(t, on, Neo.VIOLET);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); l.bottomMargin = (int) (6 * d);
        t.setLayoutParams(l);
        return t;
    }

    private void help() {
        LinearLayout box = Neo.sheetBody(c, Txt.s(R.string.packet_map_title), "radio");
        box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_map_help_1)));
        box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_map_help_2)));
        box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_map_help_3)));
        box.addView(Neo.sheetHint(c, Txt.s(R.string.packet_map_help_4)));
        Neo.showSheet(c, box);
    }
}
