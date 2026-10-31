/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import com.macdmr.radio.i18n.Msg;
import com.macdmr.radio.i18n.Txt;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Packet finder: listens (never transmits) on the usual 1200-baud packet frequencies one after another and lists
 * the packet stations it hears that are not plain APRS: Winlink gateways, BBSs, nodes (NET/ROM broadcasts, ID
 * beacons), and stations in connected-mode traffic. Nodes beacon every 10 to 30 minutes, so a real search takes
 * hours (the scheduler can run it at night). The radio goes back to its channel (or VFO) afterwards.
 */
public final class PacketFinder {
    /** The common 2 m packet frequencies (kHz). */
    public static final int[] DEFAULT_KHZ = { 144910, 144930, 144950, 144970, 144990, 145010, 145030, 145050, 145070, 145090 };
    /** Best first when a station shows several kinds. */
    public static final String GATEWAY = "Winlink gateway", BBS = "BBS", NODE = "Node", ID = "Station ID", BEACON = "Beacon", STATION = "Packet station";   // i18n: kind keys (stored with the stations, SignalDeck Web); kindName() shows them
    /** A kind's name in the app's language. */
    public static String kindName(String kind) { int id = kindId(kind); return id == 0 ? kind : Txt.s(id); }
    /** A kind as a message argument (a history line). */
    static Object kindArg(String kind) { int id = kindId(kind); return id == 0 ? kind : Msg.of(id); }
    private static int kindId(String kind) {
        if (GATEWAY.equals(kind)) return R.string.packet_finder_kind_gateway; if (BBS.equals(kind)) return R.string.packet_finder_kind_bbs; if (NODE.equals(kind)) return R.string.packet_finder_kind_node;
        if (ID.equals(kind)) return R.string.packet_finder_kind_id; if (BEACON.equals(kind)) return R.string.packet_finder_kind_beacon; if (STATION.equals(kind)) return R.string.packet_finder_kind_station;
        return 0;
    }
    private static final String[] RANK = { GATEWAY, BBS, NODE, ID, BEACON, STATION };

    public static final class Hit { public final String call, kind, note; Hit(String call, String kind, String note) { this.call = call; this.kind = kind; this.note = note; } }

    public static final class Station {
        public String call = "", kind = STATION, note = ""; public int hz, bestDbm = -999, count; public long first, last;
        JSONObject json() throws Exception { return new JSONObject().put("call", call).put("kind", kind).put("note", note).put("hz", hz).put("dbm", bestDbm).put("count", count).put("first", first).put("last", last); }
        static Station of(JSONObject o) { Station s = new Station(); s.call = o.optString("call"); s.kind = o.optString("kind", STATION); s.note = o.optString("note"); s.hz = o.optInt("hz"); s.bestDbm = o.optInt("dbm", -999); s.count = o.optInt("count"); s.first = o.optLong("first"); s.last = o.optLong("last"); return s; }
    }

    // ---- classification (pure, host-tested) -------------------------------------------------------------

    /** What a frame (no FCS) says about who is on the air; empty for APRS and noise. */
    public static List<Hit> classify(byte[] f) {
        List<Hit> out = new ArrayList<>();
        if (f == null || f.length < 15 || !com.macdmr.radio.pkt.Ax25.plausibleAddresses(f)) return out;
        String dst = call(f, 0), src = call(f, 7);
        int off = 14; boolean last = (f[13] & 1) != 0;
        while (!last && off + 7 <= f.length) { last = (f[off + 6] & 1) != 0; off += 7; }
        if (off >= f.length) return out;
        int ctl = f[off] & 0xFF; String dstBase = dst.replaceAll("-.*", "");
        boolean ui = (ctl & ~0x10) == 0x03, iFrame = (ctl & 1) == 0;
        byte[] info = new byte[0]; int pid = -1;
        if ((ui || iFrame) && off + 1 < f.length) { pid = f[off + 1] & 0xFF; info = java.util.Arrays.copyOfRange(f, off + 2, f.length); }
        String text = printable(info);
        if (ui) {
            if (dstBase.equals("NODES") && pid == 0xCF && info.length >= 7 && (info[0] & 0xFF) == 0xFF) {
                String alias = new String(info, 1, 6, StandardCharsets.US_ASCII).trim();
                int n = Math.max(0, (info.length - 7) / 21);
                out.add(new Hit(src, NODE, "NET/ROM node" + (alias.isEmpty() ? "" : " " + alias) + (n > 0 ? ", " + n + " route" + (n == 1 ? "" : "s") : "")));   // i18n: note stored with the station
                return out;
            }
            if (aprs(dstBase, info)) return out;
            String kind = kindOf(text);
            if (kind == null) kind = dstBase.equals("ID") ? ID : dstBase.equals("MAIL") ? BBS : BEACON;
            out.add(new Hit(src, kind, (dstBase.equals("ID") || dstBase.equals("BEACON") ? "" : "to " + dst + ": ") + text));   // i18n: note stored with the station
            return out;
        }
        String kind = iFrame ? kindOf(text) : null;
        out.add(new Hit(src, kind == null ? STATION : kind, "in a connection with " + dst + (iFrame && !text.isEmpty() ? ": " + text : "")));   // i18n: note stored with the station
        return out;
    }

    /** APRS (left to the APRS page): an AP* tocall, or an APRS data type at the start of the info. */
    static boolean aprs(String dstBase, byte[] info) {
        if (dstBase.startsWith("AP") || dstBase.equals("GPS") || dstBase.startsWith("GPS") || dstBase.equals("WIDE")) return true;
        if (dstBase.equals("ID") || dstBase.equals("BEACON") || dstBase.equals("MAIL") || dstBase.equals("CQ") || dstBase.equals("QST")) return false;
        if (info.length == 0) return false;
        return "!=/@;)`'_:>T$<{}".indexOf((char) (info[0] & 0xFF)) >= 0;
    }

    /** A kind from what the station says about itself, or null. */
    static String kindOf(String t) {
        String u = t.toUpperCase(Locale.US);
        if (u.contains("WINLINK") || u.contains("[WL2K") || u.matches(".*\\bRMS\\b.*")) return GATEWAY;
        if (u.contains("BBS") || u.contains("[FBB") || u.contains("MAIL FOR") || u.contains("[JNOS") && u.contains("$]")) return BBS;
        if (u.contains("NODE") || u.contains("BPQ") || u.contains("JNOS") || u.contains("NET/ROM") || u.contains("NETROM") || u.contains("XNET") || u.contains("TNOS") || u.contains("CONNECT TO")) return NODE;
        return null;
    }

    static int rank(String kind) { for (int i = 0; i < RANK.length; i++) if (RANK[i].equals(kind)) return i; return RANK.length; }

    static String call(byte[] f, int off) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) { char ch = (char) ((f[off + i] & 0xFF) >> 1); if (ch != ' ') sb.append(ch); }
        int ssid = (f[off + 6] >> 1) & 15;
        return ssid == 0 ? sb.toString() : sb + "-" + ssid;
    }
    static String printable(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) { int c = x & 0xFF; sb.append(c == '\r' || c == '\n' ? ' ' : c >= 32 && c < 127 ? (char) c : '.'); }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.length() > 100 ? s.substring(0, 97) + "..." : s;
    }

    /** Merge a hit into the station list (pure). */
    static void merge(Map<String, Station> map, Hit h, int hz, int dbm, long now) {
        Station s = map.get(h.call);
        if (s == null) { s = new Station(); s.call = h.call; s.first = now; s.kind = h.kind; s.note = h.note; s.hz = hz; map.put(h.call, s); }
        if (rank(h.kind) < rank(s.kind)) { s.kind = h.kind; s.note = h.note; }
        else if (rank(h.kind) == rank(s.kind) && !h.note.isEmpty()) s.note = h.note;
        if (dbm > s.bestDbm) { s.bestDbm = dbm; s.hz = hz; }
        s.count++; s.last = now;
    }

    // ---- running -------------------------------------------------------------------------------------------

    private final RadioService svc; private final SharedPreferences prefs;
    private final Map<String, Station> stations = new LinkedHashMap<>();
    private volatile boolean running; private volatile Thread thread; private volatile int currentHz; private volatile long endsAt;
    private volatile String status = Txt.s(R.string.packet_finder_status_idle); private volatile int frames;

    PacketFinder(RadioService svc) {
        this.svc = svc; prefs = svc.getSharedPreferences("finder", Context.MODE_PRIVATE);
        try { JSONArray a = new JSONArray(prefs.getString("stations", "[]")); for (int i = 0; i < a.length(); i++) { Station s = Station.of(a.getJSONObject(i)); stations.put(s.call, s); } } catch (Exception ignored) { }
    }

    public boolean running() { return running; }
    public String status() { return status; }
    public int currentHz() { return currentHz; }
    public long endsAt() { return endsAt; }
    public synchronized List<Station> stations() { List<Station> l = new ArrayList<>(stations.values()); l.sort((a, b) -> rank(a.kind) != rank(b.kind) ? rank(a.kind) - rank(b.kind) : Long.compare(b.last, a.last)); return l; }
    public synchronized void clear() { stations.clear(); save(); }

    public List<Integer> freqs() {
        List<Integer> l = new ArrayList<>();
        for (String p : prefs.getString("freqs", "").split("[,;\\s]+")) { try { double mhz = Double.parseDouble(p.trim()); int hz = (int) Math.round(mhz * 1e6); if (ok(hz)) l.add(hz); } catch (NumberFormatException ignored) { } }
        if (l.isEmpty()) for (int k : DEFAULT_KHZ) if (ok(k * 1000)) l.add(k * 1000);   // the 2 m defaults: none on a UHF-only radio (the W6)
        return l;
    }
    /** Your own analog channels this radio tunes, as a MHz list (each frequency once, at most 20): a start where the 2 m defaults do not apply (the W6). */
    public String myChannelsText() {
        java.util.LinkedHashSet<Integer> hz = new java.util.LinkedHashSet<>();
        try { for (com.macdmr.radio.ChannelStore.Channel ch : svc.store().all()) if (!ch.isDigital() && !ch.isM17() && ok(ch.rxFreq) && hz.size() < 20) hz.add(ch.rxFreq); } catch (Throwable ignored) { }
        StringBuilder b = new StringBuilder(); for (int f : hz) b.append(b.length() > 0 ? ", " : "").append(new java.math.BigDecimal(f).movePointLeft(6).stripTrailingZeros().toPlainString());
        return b.toString();
    }
    public String freqText() { StringBuilder b = new StringBuilder(); for (int hz : freqs()) b.append(b.length() > 0 ? ", " : "").append(String.format(Locale.US, "%.3f", hz / 1e6)); return b.toString(); }
    public int dwellMin() { return prefs.getInt("dwell", 3); }
    public int hours() { return prefs.getInt("hours", 2); }
    static boolean ok(int hz) { return com.macdmr.radio.DeviceProfile.inBands(hz); }

    /** Save the frequency list (MHz, commas), minutes per frequency and hours; returns an error text or null. */
    public String setSettings(String freqsMhz, int dwell, int hrs) {
        List<String> bad = new ArrayList<>(); StringBuilder good = new StringBuilder();
        for (String p : (freqsMhz == null ? "" : freqsMhz).split("[,;\\s]+")) {
            if (p.trim().isEmpty()) continue;
            try { int hz = (int) Math.round(Double.parseDouble(p.trim()) * 1e6); if (ok(hz)) good.append(good.length() > 0 ? "," : "").append(p.trim()); else bad.add(p.trim()); } catch (NumberFormatException e) { bad.add(p.trim()); }
        }
        if (!bad.isEmpty()) return Txt.s(R.string.packet_finder_err_bad_freq, com.macdmr.radio.DeviceProfile.detect().bandsText(), String.join(", ", bad));
        prefs.edit().putString("freqs", good.toString()).putInt("dwell", Math.max(1, Math.min(30, dwell))).putInt("hours", Math.max(1, Math.min(24, hrs))).apply();
        return null;
    }

    /** Start searching for {@code minutes} (0 = the saved number of hours). Returns an error text or null. */
    public synchronized String start(int minutes) {
        if (running) return Txt.s(R.string.packet_finder_err_running);
        if (!svc.isReady()) return Txt.s(R.string.packet_finder_err_take_over);
        if (freqs().isEmpty()) return Txt.s(R.string.packet_finder_err_no_freqs, svc.profile().bandsText());
        if (svc.isTransmitting()) return Txt.s(R.string.packet_finder_err_tx);
        RadioService.SatState st = svc.satState(); if (st != null && !st.waiting) return Txt.s(R.string.packet_finder_err_sat);
        if (svc.winlink().busy()) return Txt.s(R.string.packet_finder_err_winlink);
        PacketEngine pe = svc.packet(); if (pe == null) return Txt.s(R.string.packet_finder_err_no_engine);
        if (pe.terminal() != null && pe.terminal().state() != com.macdmr.radio.pkt.Ax25Session.State.DISCONNECTED) return Txt.s(R.string.packet_finder_err_terminal);
        if (pe.monitoring()) return Txt.s(R.string.packet_finder_err_monitoring);
        final List<Integer> list = freqs(); final long dwell = dwellMin() * 60_000L;
        final long until = System.currentTimeMillis() + (minutes > 0 ? minutes : hours() * 60) * 60_000L;
        final boolean hadVfo = svc.vfoActive(); final ChannelStore.Channel before = hadVfo ? svc.vfoCopy(svc.vfoChannel()) : null;
        running = true; endsAt = until; frames = 0;
        pe.setFinderTap(this::onFrame);
        svc.history("pkt", Msg.of(R.string.packet_finder_event_started, list.size(), dwellMin(), new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(until))));
        Thread t = new Thread(() -> {
            Msg why = Msg.of(R.string.packet_finder_why_finished);
            try {
                outer:
                while (running && System.currentTimeMillis() < until) {
                    for (int hz : list) {
                        if (!running || System.currentTimeMillis() >= until) break outer;
                        ChannelStore.Channel c = new ChannelStore.Channel(); c.type = 1; c.rxFreq = c.txFreq = hz; c.band = 1; c.power = 0; c.sq = 0; c.softSq = false; c.name = Txt.s(R.string.packet_pkt_finder);
                        currentHz = hz; svc.applyVfo(c, false);
                        status = Txt.s(R.string.packet_finder_status_listening, Txt.freqMhz(hz, 3));
                        long stop = Math.min(until, System.currentTimeMillis() + dwell);
                        Thread.sleep(RadioService.RETUNE_SETTLE_MS);
                        while (running && System.currentTimeMillis() < stop) {
                            Thread.sleep(1000);
                            if (svc.isTransmitting()) { why = Msg.of(R.string.packet_finder_why_tx); break outer; }
                            RadioService.SatState s2 = svc.satState(); if (s2 != null && !s2.waiting) { why = Msg.of(R.string.packet_finder_why_satellite); break outer; }
                            ChannelStore.Channel v = svc.vfoChannel();
                            if (!svc.vfoActive() || v == null || v.rxFreq != hz) { why = Msg.of(R.string.packet_finder_why_channel); break outer; }
                        }
                    }
                }
                if (!running) why = Msg.of(R.string.packet_finder_why_stopped);
            } catch (InterruptedException e) { why = Msg.of(R.string.packet_finder_why_stopped); }
            catch (Throwable e) { why = Msg.of(R.string.packet_finder_why_error, String.valueOf(e)); }
            PacketEngine p2 = svc.packet(); if (p2 != null) p2.setFinderTap(null);
            boolean moved = why.key().equals("packet_finder_why_channel") || why.key().equals("packet_finder_why_satellite");
            if (!moved) { if (hadVfo && before != null) svc.applyVfo(before, false); else svc.exitVfo(); }
            running = false; currentHz = 0; endsAt = 0;
            int n; synchronized (this) { n = stations.size(); }
            status = Txt.s(R.string.packet_finder_status_last, why.text(), frames);
            svc.history("pkt", Msg.of(R.string.packet_finder_event_ended, why, Msg.plural(R.plurals.packet_finder_stations, n, n)));
        }, "packet-finder");
        thread = t; t.start();
        return null;
    }

    public void stop() { running = false; Thread t = thread; if (t != null) t.interrupt(); }

    /** From the packet engine: every decoded frame while the finder runs. */
    void onFrame(byte[] f) {
        int hz = currentHz; if (hz == 0) return;
        List<Hit> hits = classify(f); if (hits.isEmpty()) return;
        frames++;
        int dbm = svc.rssiNow();
        boolean fresh = false;
        synchronized (this) {
            for (Hit h : hits) { fresh |= !stations.containsKey(h.call); merge(stations, h, hz, dbm, System.currentTimeMillis()); }
            save();
        }
        if (fresh) for (Hit h : hits) svc.history("pkt", Msg.of(R.string.packet_finder_event_found, h.call, kindArg(h.kind), String.format(Locale.US, "%.3f", hz / 1e6)));
    }

    private void save() {
        try { JSONArray a = new JSONArray(); int k = 0; for (Station s : stations.values()) { if (++k > 300) break; a.put(s.json()); } prefs.edit().putString("stations", a.toString()).apply(); } catch (Exception ignored) { }
    }

    public JSONObject json() throws Exception {
        JSONArray a = new JSONArray(); for (Station s : stations()) a.put(s.json());
        return new JSONObject().put("running", running).put("status", status).put("currentHz", currentHz).put("endsAt", endsAt).put("freqs", freqText()).put("dwell", dwellMin()).put("hours", hours()).put("stations", a);
    }
}
