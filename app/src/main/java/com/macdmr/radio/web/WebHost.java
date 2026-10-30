/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.web;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.macdmr.radio.ChannelStore;
import com.macdmr.radio.RadioService;
import com.macdmr.radio.TranscriptionClient;
import com.macdmr.radio.VoskCaptions;
import com.macdmr.radio.pkt.PacketStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * SignalDeck Web on the phone: owns the {@link WebServer}, answers its JSON API from the running {@link RadioService}
 * (writes run on the main thread, like the phone UI's), and turns the service's listener callbacks into live events.
 * Settings live in prefs "web": enabled, pin, tx (transmit from the web), port.
 */
public final class WebHost {
    public static final int DEFAULT_PORT = 8080, TLS_PORT = 8443;
    private static final int[] VFO_STEPS = { 5000, 6250, 10000, 12500, 25000 };     // VfoDialog.STEPS
    private static final String TX_OFF = "Transmit from the web is off (phone: Device → SignalDeck Web)";
    private final RadioService svc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile WebServer server;
    private volatile Thread ticker;
    private volatile String lastError;

    public WebHost(RadioService svc) { this.svc = svc; }

    private volatile PhoneStats phone;
    private PhoneStats phone() { PhoneStats p = phone; if (p == null) phone = p = new PhoneStats(svc); return p; }

    private SharedPreferences prefs() { return svc.getSharedPreferences("web", Context.MODE_PRIVATE); }
    public boolean enabled() { return prefs().getBoolean("enabled", false); }
    public boolean allowTx() { return prefs().getBoolean("tx", false); }
    public void setAllowTx(boolean on) { prefs().edit().putBoolean("tx", on).apply(); }
    public int port() { return prefs().getInt("port", DEFAULT_PORT); }
    public String pin() {
        String p = prefs().getString("pin", null);
        if (p == null) { p = WebServer.newPin(); prefs().edit().putString("pin", p).apply(); }
        return p;
    }
    /** A new PIN logs every browser out. */
    public void newPin() { prefs().edit().putString("pin", WebServer.newPin()).remove("sessions").apply(); if (running()) { stop(); start(); } }
    public boolean running() { WebServer s = server; return s != null && s.running(); }
    public int clients() { WebServer s = server; return s == null ? 0 : s.clients(); }
    public int listeners() { WebServer s = server; return s == null ? 0 : s.audioClients(); }
    /** Received audio for the browsers that are listening (8 kHz mono 16-bit). */
    public void rxAudio(byte[] mono, int n) { WebServer s = server; if (s != null) s.publishAudio(mono, n); }
    public String lastError() { return lastError; }

    /** Turn the web server on or off (and remember it). Returns null or an error. */
    public String setEnabled(boolean on) {
        prefs().edit().putBoolean("enabled", on).apply();
        if (on) return start();
        stop(); return null;
    }

    public synchronized String start() {
        if (running()) return null;
        WebServer s = new WebServer(port(), pin(), this::asset, this::api, new WebServer.SessionStore() {
            @Override public java.util.Map<String, Long> load() {
                java.util.Map<String, Long> m = new java.util.HashMap<>();
                try { JSONObject o = new JSONObject(prefs().getString("sessions", "{}")); for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.getLong(k)); } } catch (Exception ignored) { }
                return m;
            }
            @Override public void save(java.util.Map<String, Long> m) { prefs().edit().putString("sessions", new JSONObject(m).toString()).apply(); }
        });
        try { s.start(); } catch (Exception e) { lastError = "Port " + port() + ": " + e.getMessage(); return lastError; }
        server = s; lastError = null;
        s.webSocket("/api/ws/talk", (req, peer) -> new TalkSession(req, peer));
        try { s.startTls(TLS_PORT, WebTls.serverFactory()); tlsError = null; }                 // HTTPS: the browser microphone needs it
        catch (Exception e) { tlsError = String.valueOf(e.getMessage()); android.util.Log.w("WebHost", "https: " + e); }
        ui.post(() -> svc.addListener(events));
        Thread t = new Thread(this::tick, "web-tick"); t.setDaemon(true); ticker = t; t.start();
        return null;
    }
    public synchronized void stop() {
        WebServer s = server; server = null;
        if (s != null) s.stop();
        Thread t = ticker; ticker = null; if (t != null) t.interrupt();
        ui.post(() -> svc.removeListener(events));
    }

    private volatile String tlsError;
    public boolean tlsRunning() { WebServer s = server; return s != null && s.tlsRunning(); }
    public String tlsError() { return tlsError; }
    /** The secure addresses (for talking with the browser's microphone). */
    public List<String> secureUrls() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String u : urls()) out.add(u.replace("http://", "https://").replaceAll(":\\d+$", "") + ":" + TLS_PORT);
        return out;
    }
    /** The addresses a browser on the same network can use. */
    public List<String> urls() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add("http://" + a.getHostAddress() + (port() == 80 ? "" : ":" + port()));
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---- static files: assets/web/, fonts from assets/fonts/ ----
    private byte[] asset(String path) throws java.io.IOException {
        String p = path.startsWith("fonts/") || path.startsWith("exam/") ? path : "web/" + path;     // the exam pools and figures come straight from the phone's assets
        try (InputStream in = svc.getAssets().open(p)) { return readAll(in); } catch (FileNotFoundException e) { return null; }
    }
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) != -1) if (n > 0) b.write(buf, 0, n);      // a read may return 0 bytes before the end (compressed assets)
        return b.toByteArray();
    }

    // ---- talk: the browser's microphone over a WebSocket ("down" / "up" text, 8 kHz s16le binary audio) ----
    private static volatile TalkSession talker;                           // one talker at a time
    private final class TalkSession implements WebServer.WebSocketSession {
        private final WebServer.Request req; private final WebServer.WebSocketPeer peer;
        private volatile WebTxFeed feed;
        TalkSession(WebServer.Request r, WebServer.WebSocketPeer p) { req = r; peer = p; p.sendText(state("ready", null)); }
        private String state(String st, String err) {
            try { JSONObject o = new JSONObject().put("state", st); if (err != null) o.put("error", err); return o.toString(); } catch (Exception e) { return "{}"; }
        }
        @Override public void text(String t) {
            if ("down".equals(t)) down(); else if ("up".equals(t)) release("released");
        }
        private synchronized void down() {
            if (feed != null) return;
            if (!allowTx()) { peer.sendText(state("rx", TX_OFF)); return; }
            RadioService.SatState sat = svc.satState();
            if (sat != null && sat.listenOnly && !sat.waiting) { peer.sendText(state("rx", "Receive only during this satellite pass")); return; }
            if (svc.txInhibited()) { peer.sendText(state("rx", "This channel is receive-only")); return; }
            if (svc.firmwareLoading()) { peer.sendText(state("rx", "Radio busy, loading firmware")); return; }
            if (!svc.isReady()) { peer.sendText(state("rx", "The radio is not ready")); return; }
            TalkSession other = talker;
            if ((other != null && other != this) || svc.isTransmitting()) { peer.sendText(state("rx", "Someone is already transmitting")); return; }
            talker = this;
            final WebTxFeed f = new WebTxFeed(); feed = f;
            svc.history("warn", "\uD83C\uDF10 Talking from the web with the browser microphone (" + req.remote + ")");
            ui.post(() -> svc.pttFromWeb(true, f));
            peer.sendText(state("tx", null));
            Thread w = new Thread(() -> {                                 // watchdog: no audio for 1.5 s (tab hidden, network gone) unkeys
                while (feed == f) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                    if (feed == f && f.idleNanos() > 1_500_000_000L) { release("no audio from the browser"); return; }
                }
            }, "web-talk-watchdog");
            w.setDaemon(true); w.start();
        }
        private synchronized void release(String why) {
            WebTxFeed f = feed; if (f == null) return;
            feed = null; f.close();
            if (talker == this) talker = null;
            ui.post(() -> svc.ptt(false));
            peer.sendText(state("rx", "released".equals(why) ? null : "Stopped: " + why));
        }
        @Override public void binary(byte[] b, int n) { WebTxFeed f = feed; if (f != null) f.push(b, 0, n); }
        @Override public void closed() { release("the connection closed"); }
    }

    // ---- live events ----
    private final RadioService.Listener events = new RadioService.Listener() {
        private long lastRssi, lastLevel;
        @Override public void onChannel(ChannelStore.Channel ch) { pub("channel", channelJson(ch)); }
        @Override public void onChannels() { pub("channels", "{}"); }
        @Override public void onStockImportOffer() { pub("stockImport", "{}"); }
        @Override public void onNoChannel() { pub("channel", "{}"); }
        @Override public void onRx(boolean r) { pub("rx", "{\"on\":" + r + "}"); }
        @Override public void onTx(boolean t) { pub("tx", "{\"on\":" + t + "}"); }
        @Override public void onRssi(int dbm) { long now = System.currentTimeMillis(); if (now - lastRssi < 400) return; lastRssi = now; pub("rssi", "{\"dbm\":" + dbm + "}"); }
        private long lastSpec;
        @Override public void onScope(int from, int step, float[] dbm) { try { pub("scope", scopeRow(from, step, dbm).toString()); } catch (Exception ignored) { } }
        @Override public void onScopeState(String st) { try { pub("scopeState", new JSONObject().put("state", st).put("running", svc.scope().running()).toString()); } catch (Exception ignored) { } }
        @Override public void onSpectrum(float[] b) {                     // the intercom bars in the browser, about 12 times a second
            long now = System.currentTimeMillis(); boolean zero = true; for (float v : b) if (v > 0) { zero = false; break; }
            if (!zero && now - lastSpec < 80) return; lastSpec = now;
            StringBuilder s = new StringBuilder("{\"b\":["); for (int i = 0; i < b.length; i++) { if (i > 0) s.append(','); s.append(Math.round(b[i] * 100)); } s.append("]}");
            pub("spectrum", s.toString());
        }
        @Override public void onAudioLevel(int amp) { long now = System.currentTimeMillis(); if (now - lastLevel < 100) return; lastLevel = now; pub("level", "{\"amp\":" + amp + "}"); }
        @Override public void onHistory(String line) { pub("history", "{\"line\":" + WebServer.quote(line) + "}"); }
        @Override public void onCaller(String text) { pub("caller", "{\"text\":" + WebServer.quote(text) + "}"); }
        @Override public void onLog(String s) { }
        @Override public void onLink(String state) { pub("link", "{\"state\":" + WebServer.quote(state) + "}"); }
        @Override public void onLocation(String text) { pub("location", "{\"text\":" + WebServer.quote(text) + "}"); }
        @Override public void onSms(int from, int type, String text) { pub("sms", "{\"from\":" + from + ",\"type\":" + type + ",\"text\":" + WebServer.quote(text) + "}"); }
        @Override public void onSmsResult(boolean ok) { pub("smsResult", "{\"ok\":" + ok + "}"); }
        @Override public void onModes(boolean softSq, int level, boolean open, boolean mon, boolean rec, boolean scan, boolean hold, String zone) {
            pub("modes", "{\"softSq\":" + softSq + ",\"softSqLevel\":" + level + ",\"squelchOpen\":" + open + ",\"monitor\":" + mon + ",\"record\":" + rec
                    + ",\"scanning\":" + scan + ",\"holding\":" + hold + ",\"zone\":" + WebServer.quote(zone) + "}");
        }
        @Override public void onCaption(boolean enabled, String live, String sentence) {
            pub("caption", "{\"enabled\":" + enabled + ",\"live\":" + WebServer.quote(live) + ",\"sentence\":" + WebServer.quote(sentence) + "}");
        }
    };
    private void pub(String type, String json) { WebServer s = server; if (s != null) s.publish(type, json); }

    /** Every 2 s while a browser is connected: the S-meter poll, the satellite card, mode state and new packets. */
    private void tick() {
        long aprsSeen = -1; String sstvSeen = null; int n = 0;
        while (Thread.currentThread() == ticker) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            try { phone().sampleIfDue(); } catch (Throwable ignored) { }      // battery history for the rate, browser or not
            if (clients() == 0) continue;
            if (n++ % 2 == 0) try { pub("phone", phone().json().toString()); } catch (Throwable ignored) { }
            try {
                svc.pollRssi();
                pub("sat", satJson().toString());
                pub("mode", modeJson().toString());
                com.macdmr.radio.PacketEngine p = svc.packet();
                if (p != null) {
                    long ch = p.store().changes();
                    if (ch != aprsSeen) { aprsSeen = ch; pub("aprs", "{\"changes\":" + ch + "}"); }
                    JSONObject s = sstvJson(); String st = s.toString();
                    if (!st.equals(sstvSeen) || p.sstvRx()) { sstvSeen = st; pub("sstv", st); }
                }
            } catch (Throwable ignored) { }
        }
    }

    // ---- JSON ----
    private static String channelJson(ChannelStore.Channel ch) { try { return channelObj(ch).toString(); } catch (Exception e) { return "{}"; } }
    private static JSONObject channelObj(ChannelStore.Channel ch) throws Exception {
        JSONObject o = new JSONObject();
        if (ch == null) return o;
        o.put("number", ch.number).put("name", ch.name).put("digital", ch.isDigital()).put("rxFreq", ch.rxFreq).put("txFreq", ch.txFreq)
                .put("power", ch.power).put("band", ch.band).put("cc", ch.cc).put("slot", ch.inSlot).put("txContact", ch.txContact)
                .put("rxType", ch.rxType).put("rxSubCode", ch.rxSubCode).put("txType", ch.txType).put("txSubCode", ch.txSubCode).put("location", ch.location);
        if (ch.lat != 0 || ch.lon != 0) o.put("lat", ch.lat).put("lon", ch.lon);
        o.put("notes", ch.notes == null ? "" : ch.notes);
        o.put("rxOnly", ch.rxOnly).put("receiveOnly", ChannelStore.receiveOnly(ch));
        return o;
    }
    /** Every field the channel editor has (the encrypt key itself stays on the phone: only whether one is set). */
    private static JSONObject channelFull(ChannelStore.Channel ch) throws Exception {
        JSONObject o = channelObj(ch);
        o.put("type", ch.type).put("relay", ch.relay).put("mode", ch.mode).put("contactType", ch.contactType).put("encryptSw", ch.encryptSw)
                .put("hasKey", ch.encryptKey != null && !ch.encryptKey.isEmpty()).put("interrupt", ch.interrupt).put("sq", ch.sq)
                .put("softSq", ch.softSq).put("softSqLevel", ch.softSqLevel).put("m17dest", ch.m17Dest());
        JSONArray g = new JSONArray(); for (int x : ch.groups) g.put(x); o.put("groups", g);
        return o;
    }
    /** The phone editor's checks (ChannelEditorView.apply), applied to a JSON body; returns null or the problem. */
    public static String applyChannel(ChannelStore.Channel ch, JSONObject b) {
        try {
            ch.name = ChannelStore.fitName(b.optString("name", ch.name));
            int asked = b.optInt("type", ch.type);
            ch.type = asked == ChannelStore.Channel.TYPE_M17 ? ChannelStore.Channel.TYPE_M17 : asked == 0 ? 0 : 1;
            double rxm = b.optDouble("rxMHz", Double.NaN), txm = b.optDouble("txMHz", Double.NaN);   // an empty field arrives as null or NaN
            if (Double.isNaN(rxm)) return "Enter the receive frequency (MHz)";
            if (Double.isNaN(txm)) txm = rxm;                                                       // empty send frequency: simplex
            ch.rxFreq = (int) Math.round(rxm * 1e6); ch.txFreq = (int) Math.round(txm * 1e6);
            if (!ChannelStore.inBand(ch.rxFreq) || !ChannelStore.inBand(ch.txFreq)) return "Frequencies must be inside " + com.macdmr.radio.DeviceProfile.detect().bandsText();
            ch.power = b.optInt("power", ch.power) == 1 ? 1 : 0;
            ch.relay = b.optInt("relay", ch.relay) == 1 ? 1 : 2;
            if (b.has("notes")) ch.notes = b.optString("notes", "").trim();
            if (b.has("rxOnly")) ch.rxOnly = b.optBoolean("rxOnly", false);
            String la = b.optString("lat", "").trim(), lo = b.optString("lon", "").trim();
            if (la.isEmpty() && lo.isEmpty()) { ch.lat = 0; ch.lon = 0; }
            else {
                ch.lat = Double.parseDouble(la); ch.lon = Double.parseDouble(lo);
                if (Math.abs(ch.lat) > 90 || Math.abs(ch.lon) > 180) return "Latitude must be within ±90 and longitude within ±180";
            }
            if (ch.isDigital()) {
                ch.cc = Math.max(0, Math.min(15, b.optInt("cc", ch.cc)));
                ch.mode = b.optInt("mode", ch.mode) == 4 ? 4 : 0;
                ch.inSlot = ch.outSlot = b.optInt("slot", ch.inSlot) == 1 ? 1 : 0;
                ch.contactType = Math.max(0, Math.min(2, b.optInt("contactType", ch.contactType)));
                String call = b.optString("txContact", "").trim();
                ch.txContact = call.isEmpty() ? 0 : Integer.parseInt(call);
                if (ch.txContact < 0 || ch.txContact > 0xFFFFFF) return "The call number must be a DMR ID or talkgroup (1 to 16777215)";
                java.util.List<Integer> g = new java.util.ArrayList<>();
                for (String s : b.optString("groups", "").split("[,; ]+")) { s = s.trim(); if (!s.isEmpty()) g.add(Integer.parseInt(s)); }
                // the radio passes a group call only if its talkgroup is in this list (measured on air 2026-10-15): include the call target
                if (ch.contactType == 1 && ch.txContact > 0 && !g.contains(ch.txContact)) g.add(0, ch.txContact);
                if (g.size() > 32) return "At most 32 talkgroups in the RX list (including the call number)";
                ch.groups = new int[g.size()]; for (int i = 0; i < g.size(); i++) ch.groups[i] = g.get(i);
                ch.encryptSw = b.optInt("encryptSw", ch.encryptSw) == 1 ? 1 : 2;
                if (b.has("encryptKey")) ch.encryptKey = b.getString("encryptKey");            // absent: keep the stored key
                if (ch.encryptSw == 1 && (ch.encryptKey == null || ch.encryptKey.length() != 8)) return "Encrypt key must be exactly 8 characters";
                ch.interrupt = b.optInt("interrupt", ch.interrupt) == 1 ? 1 : 2;
            } else if (ch.isM17()) {
                ch.band = 1;
                ch.cc = Math.max(0, Math.min(15, b.optInt("cc", ch.cc)));
                if (b.has("m17dest")) ch.m17dest = ChannelStore.Channel.cleanM17Dest(b.optString("m17dest", ""));
                ch.sq = 0;
                ch.txType = 0; ch.rxType = 0; ch.txSubCode = 0; ch.rxSubCode = 0;
                ch.softSq = false;
            } else {
                ch.band = b.optInt("band", ch.band) == 0 ? 0 : 1;
                ch.softSq = b.optBoolean("softSq", ch.softSq);
                ch.softSqLevel = Math.max(1, Math.min(9, b.optInt("softSqLevel", ch.softSqLevel)));
                ch.sq = Math.max(0, Math.min(9, b.optInt("sq", ch.sq)));
                ch.txType = Math.max(0, Math.min(3, b.optInt("txType", ch.txType)));
                ch.rxType = Math.max(0, Math.min(3, b.optInt("rxType", ch.rxType)));
                int nTx = ch.txType == 1 ? com.macdmr.radio.dsp.ToneTable.CTCSS.length : com.macdmr.radio.dsp.ToneTable.DCS_COUNT, nRx = ch.rxType == 1 ? com.macdmr.radio.dsp.ToneTable.CTCSS.length : com.macdmr.radio.dsp.ToneTable.DCS_COUNT;
                ch.txSubCode = ch.txType == 0 ? 0 : Math.max(0, Math.min(nTx - 1, b.optInt("txSubCode", 0)));
                ch.rxSubCode = ch.rxType == 0 ? 0 : Math.max(0, Math.min(nRx - 1, b.optInt("rxSubCode", 0)));
            }
            if (ch.rxFreq < 300_000_000) ch.band = 1;                                  // VHF must be wide on this radio
            return null;
        } catch (NumberFormatException e) {
            return "Check the numbers: " + e.getMessage();
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }
    /** A coverage square for the browser, coloured as the phone's map draws it (predicted squares fainter, underneath). */
    private static JSONObject cellJson(com.macdmr.radio.coverage.CoverageStore.Cell c, boolean tx, boolean predicted) throws Exception {
        int col; double alpha;
        if (predicted) { col = tx ? 0xFF2BEA7A : com.macdmr.radio.coverage.CoverageMath.signalColor(c.value); alpha = tx ? (0x22 + Math.min(20, c.value) * 4) / 255.0 : 0x4C / 255.0; }
        else { col = tx ? (c.keyed > 0 ? 0xFF2BEA7A : 0xFFFF5468) : com.macdmr.radio.coverage.CoverageMath.signalColor(c.value); alpha = 0xA8 / 255.0; }
        return new JSONObject().put("s", c.s).put("w", c.w).put("n", c.n).put("e", c.e).put("value", c.value).put("count", c.count).put("keyed", c.keyed).put("failed", c.failed)
                .put("color", String.format("#%06X", col & 0xFFFFFF)).put("alpha", Math.round(alpha * 100) / 100.0).put("predicted", predicted)
                .put("sunits", tx ? "" : com.macdmr.radio.coverage.CoverageMath.sUnits(c.value));
    }

    /** One terrain prediction at a time, run in the background (it can download terrain); the browser polls for it. Same settings as the phone's map. */
    private static final class PredJob { String id; volatile String status = "starting\u2026"; volatile com.macdmr.radio.coverage.Prediction.Result res; volatile String err; volatile boolean done; volatile long doneAt; }
    private volatile PredJob predJob;
    private synchronized PredJob prediction(String key, double lat, double lon, double hz) {
        android.content.SharedPreferences cp = svc.getSharedPreferences("coverage", Context.MODE_PRIVATE);
        final double ant = cp.getInt("ant_" + key, 0), erp = cp.getInt("pwr_" + key, 0), rad = cp.getInt("predRangeMi", 0) * 1609.344;
        android.location.Location me = svc.lastKnownLocation();
        final double meLat = me != null ? me.getLatitude() : Double.NaN, meLon = me != null ? me.getLongitude() : Double.NaN;
        String id = key + "|" + ant + "|" + erp + "|" + rad;
        PredJob j = predJob; long age = j == null ? 0 : System.currentTimeMillis() - j.doneAt;
        if (j != null && j.id.equals(id) && (!j.done || (j.err == null && age < 120_000) || (j.err != null && age < 60_000))) return j;   // as on the phone: re-calibrated with new readings at most every 2 minutes; a failure retried after a minute
        final PredJob nj = new PredJob(); nj.id = id; predJob = nj;
        new Thread(() -> {
            try {
                nj.res = com.macdmr.radio.coverage.Prediction.run(svc, svc.coverage().store(), key, lat, lon, hz, ant, erp, rad, meLat, meLon, t -> { nj.status = t; return predJob == nj; });
                nj.status = nj.res == null ? "stopped" : "done";
            } catch (Throwable t) { nj.err = t.getMessage() == null ? t.toString() : t.getMessage(); nj.status = "failed"; }
            nj.doneAt = System.currentTimeMillis(); nj.done = true;
        }, "web-prediction").start();
        return nj;
    }

    private static JSONObject toneLists() throws Exception {
        JSONArray c = new JSONArray(); for (int i = 0; i < com.macdmr.radio.dsp.ToneTable.CTCSS.length; i++) c.put(com.macdmr.radio.dsp.ToneTable.ctcssLabel(i));
        JSONArray d = new JSONArray(); for (int i = 0; i < com.macdmr.radio.dsp.ToneTable.DCS_COUNT; i++) d.put("DCS D" + com.macdmr.radio.dsp.ToneTable.dcsCode(i));   // the index the MCU gets; N/I comes from the type
        return new JSONObject().put("ctcss", c).put("dcs", d);
    }
    /** The region's values the pages use (Device → Region on the phone; it is not changed from the web). */
    static JSONObject regionJson(com.macdmr.radio.region.Region r) throws Exception {
        return new JSONObject().put("id", r.id).put("name", r.name).put("chosen", r.chosen).put("aprsHz", r.aprsHz).put("call2mHz", r.call2mHz).put("call70Hz", r.call70Hz)
                .put("sstvHz", r.sstvHz).put("weatherRadio", r.weatherRadio).put("units", r.km ? "km" : "mi").put("stationIdMinutes", r.stationIdMinutes);
    }
    private JSONObject statusJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("link", svc.link()).put("ready", svc.isReady()).put("channel", channelObj(svc.current())).put("stockImport", svc.stockImportPending());
        com.macdmr.radio.DeviceProfile prof = svc.profile();                  // what this radio can do: the pages hide what it cannot (an analog-only radio has no DMR)
        JSONArray bands = new JSONArray(); for (long hz : prof.bandsHz) bands.put(hz);
        o.put("radio", new JSONObject().put("dmr", prof.dmr()).put("messages", prof.hasMessages()).put("contacts", prof.hasContacts())
                .put("rssi", prof.rssi).put("bands", bands).put("bandsText", prof.bandsText()).put("symbolEngine", prof.symbolEngine())
                .put("aprsTerrestrial", svc.aprsTunable()).put("aprsHz", svc.aprsHz()).put("weather", prof.tunable(162_400_000L)).put("region", regionJson(com.macdmr.radio.region.Region.current(svc))));
        o.put("transmitting", svc.isTransmitting()).put("firmwareLoad", svc.firmwareLoading()).put("hearing", svc.hearing()).put("rssi", svc.lastRssi()).put("caller", svc.currentCaller());
        o.put("softSq", svc.softSqOn()).put("softSqLevel", svc.softSqLevel()).put("monitor", svc.monitorOn()).put("promiscuous", svc.promiscuousOn()).put("record", svc.recordOn())
                .put("scanning", svc.scanning()).put("vfo", svc.vfoActive()).put("zone", svc.zoneName()).put("zoneId", svc.zoneId())
                .put("txInhibited", svc.txInhibited()).put("webTx", allowTx()).put("tls", tlsRunning()).put("tlsPort", TLS_PORT);
        o.put("mode", modeJson()).put("sat", satJson()).put("sstv", sstvJson());
        o.put("version", com.macdmr.radio.BuildConfig.VERSION_NAME).put("captions", svc.captionsOn());
        return o;
    }
    private JSONObject modeJson() throws Exception {
        JSONObject o = new JSONObject();
        com.macdmr.radio.PacketEngine p = svc.packet();
        o.put("active", svc.packetActive()).put("since", svc.modeSince());
        if (p != null) o.put("aprs", p.monitoring()).put("sstv", p.sstvRx()).put("kiss", p.kiss().isActive());
        o.put("parrot", svc.parrot().enabled());
        return o;
    }
    private JSONObject satJson() throws Exception {
        JSONObject o = new JSONObject();
        RadioService.SatState s = svc.satState();
        o.put("tracking", s != null).put("repeat", svc.issRepeat());
        if (s == null) return o;
        o.put("name", s.name).put("mode", s.mode).put("rxHz", s.rxHz).put("txHz", s.txHz).put("nominalHz", s.nominalHz).put("aos", s.aos).put("los", s.los)
                .put("maxEl", s.maxEl).put("waiting", s.waiting).put("listenOnly", s.listenOnly);
        if (s.look != null) o.put("el", s.look.el).put("az", s.look.az).put("rangeKm", s.look.rangeKm);
        return o;
    }
    private JSONObject sstvJson() throws Exception {
        com.macdmr.radio.PacketEngine p = svc.packet();
        JSONObject o = new JSONObject();
        if (p == null) return o;
        android.graphics.Bitmap b = p.sstvLast();
        o.put("on", p.sstvRx()).put("status", p.sstvStatus()).put("picture", b != null).put("stamp", b == null ? "" : System.identityHashCode(b) + "_" + b.getGenerationId());
        File[] fs = sstvDir().listFiles((d, n) -> n.endsWith(".png") || n.endsWith(".jpg"));   // changes when a picture is saved, combined or deleted
        long newest = 0; if (fs != null) for (File f : fs) newest = Math.max(newest, f.lastModified());
        o.put("gallery", (fs == null ? 0 : fs.length) + "_" + newest);
        return o;
    }

    private <T> T onMain(Callable<T> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        FutureTask<T> f = new FutureTask<>(c); ui.post(f); return f.get(5, TimeUnit.SECONDS);
    }
    private static JSONArray arr(float[] v) throws Exception { JSONArray a = new JSONArray(); for (float x : v) a.put(Float.isNaN(x) ? JSONObject.NULL : (Object) (Math.round(x * 2) / 2.0)); return a; }
    private JSONObject scopeRow(int from, int step, float[] dbm) throws Exception { return new JSONObject().put("from", from).put("step", step).put("dbm", arr(dbm)).put("sweepMs", svc.scope().sweepMs()); }
    private String channelNameAt(int hz, int step) { for (ChannelStore.Channel ch : svc.store().all()) if (!ch.isDigital() && Math.abs(ch.rxFreq - hz) <= step / 2) return ch.name; return ""; }
    private static WebServer.Response ok(JSONObject o) { return WebServer.Response.json(200, o.toString()); }
    private static WebServer.Response ok(String msg) { return WebServer.Response.json(200, "{\"ok\":true,\"message\":" + WebServer.quote(msg) + "}"); }
    /** The owner's callsign never reaches a browser: whole-word occurrences become "(you)". */
    static String hideCall(String s, String call) {
        if (s == null || call == null || call.isEmpty()) return s;
        return s.replaceAll("(?<![A-Z0-9/])<?" + java.util.regex.Pattern.quote(call) + "(/[A-Z0-9]+)?>?(?![A-Z0-9])", "(you)");
    }
    private static JSONObject body(WebServer.Request r) { try { return r.body.length == 0 ? new JSONObject() : new JSONObject(r.bodyText()); } catch (Exception e) { return new JSONObject(); } }
    private static final String DEFAULT_QUICK = "QSL\nCopy, I will call you back\nOn my way\nWhere are you?\nSwitch to the other channel\nAll OK here";
    private List<String> quickReplies() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String q : svc.getSharedPreferences("radio", Context.MODE_PRIVATE).getString("quickReplies", DEFAULT_QUICK).split("\n")) if (!q.trim().isEmpty()) out.add(q.trim());
        return out;
    }
    private static double parseD(String s, double def) { try { return s == null || s.isEmpty() ? def : Double.parseDouble(s); } catch (Exception e) { return def; } }
    private static int parse(String s, int def) { try { return s == null ? def : Integer.parseInt(s); } catch (Exception e) { return def; } }
    private static JSONObject checkInJson(com.macdmr.radio.ham.NetStore.CheckIn k) throws Exception {
        return new JSONObject().put("id", k.id).put("ts", k.ts).put("call", k.call).put("name", k.name).put("notes", k.notes).put("traffic", k.traffic).put("late", k.late).put("out", k.out);
    }
    private com.macdmr.radio.repeaters.RepeaterService repeaterService;
    private final java.util.Map<String, com.macdmr.radio.repeaters.RepeaterModels.Repeater> lastRepeaters = new java.util.concurrent.ConcurrentHashMap<>();
    private synchronized com.macdmr.radio.repeaters.RepeaterService repeaters() {
        if (repeaterService == null) repeaterService = new com.macdmr.radio.repeaters.RepeaterService(svc, svc.store());
        return repeaterService;
    }
    /** 8 kHz mono 16-bit samples as a WAV file. */
    static byte[] wav(short[] pcm) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + pcm.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length * 2);
        for (short v : pcm) b.putShort(v);
        return b.array();
    }
    /** A backup folder by name, only inside Download/DMR/DMR_Backups. */
    private static File backupDir(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        File d = new File(com.macdmr.radio.Backup.backupRoot(), name);
        return d.isDirectory() ? d : null;
    }
    private static void deleteTree(File f) { File[] l = f.listFiles(); if (l != null) for (File x : l) deleteTree(x); f.delete(); }
    /** A recording by its id "channel/file.wav", only inside Download/DMR/Audio. */
    private static File recording(String id) {
        if (id == null) return null;
        String[] p = id.split("/");
        if (p.length != 2 || p[0].isEmpty() || p[1].isEmpty() || id.contains("..") || id.contains("\\") || !p[1].endsWith(".wav")) return null;
        File f = new File(new File(com.macdmr.radio.ui.MediaDialogs.audioRoot(), p[0]), p[1]);
        return f.isFile() ? f : null;
    }
    private File sstvDir() { return new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "DMR/SSTV"); }

    // ---- settings: the Device page's switches and choices ----
    private interface Get { Object get(); }
    private interface Set { void set(JSONObject b) throws Exception; }
    private static final class Setting {
        final String key, group, label, sub, kind; final String[] choices; final Get get; final Set set;
        Setting(String key, String group, String label, String sub, String kind, String[] choices, Get get, Set set) { this.key = key; this.group = group; this.label = label; this.sub = sub; this.kind = kind; this.choices = choices; this.get = get; this.set = set; }
    }
    private java.util.List<Setting> settings() {
        java.util.List<Setting> l = new java.util.ArrayList<>();
        android.content.SharedPreferences rp = svc.getSharedPreferences("radio", Context.MODE_PRIVATE);
        if (svc.profile().dmr()) l.add(new Setting("dmrId", "Identity", "Your DMR ID", "What other radios see when you talk on DMR channels", "int", null, svc::localId, b -> {
            int v = b.getInt("value"); if (v < 1 || v > com.macdmr.radio.ChannelStore.MAX_DMR_ID) throw new IllegalArgumentException("A DMR ID is 1 to 16776415"); svc.setLocalId(v); }));
        l.add(new Setting("hwPtt", "Radio", "Side PTT key", "The phone's hardware push-to-talk button transmits", "bool", null, svc::hwPtt, b -> svc.setHwPtt(b.getBoolean("value"))));
        l.add(new Setting("volPtt", "Radio", "Volume-up transmits", "While the Intercom page is open on the phone", "bool", null, () -> rp.getBoolean("volPtt", false), b -> rp.edit().putBoolean("volPtt", b.getBoolean("value")).apply()));
        l.add(new Setting("customPtt", "Radio", "Custom PTT key", "The button learned on the phone (Device → Custom PTT key) transmits; learning needs the phone in hand", "bool", null, () -> com.macdmr.radio.CustomPttKey.enabled(svc), b -> { if (com.macdmr.radio.CustomPttKey.saved(svc) != null) { com.macdmr.radio.CustomPttKey.setEnabled(svc, b.getBoolean("value")); svc.customPttChanged(); } }));
        l.add(new Setting("scanSoftSq", "Radio", "Soft SQ while scanning", "Every analog channel, each at its own level (5 if never set)", "bool", null, svc::scanSoftSq, b -> svc.setScanSoftSq(b.getBoolean("value"))));
        l.add(new Setting("roger", "Radio", "Roger beep", "A short sound at the end of each transmission", "choice", RadioService.ROGER_NAMES, svc::rogerStyle, b -> svc.setRogerStyle(Math.max(0, Math.min(RadioService.ROGER_NAMES.length - 1, b.getInt("value"))))));
        l.add(new Setting("voice", "Radio", "Voice announcements", "Channel changes, caller names, keyword alerts", "bool", null, svc::voiceOn, b -> svc.setVoiceOn(b.getBoolean("value"))));
        l.add(new Setting("keywords", "Radio", "Keyword alerts", "Words in live captions that raise an alert, separated by commas", "text", null, svc::keywords, b -> svc.setKeywords(b.getString("value"))));
        l.add(new Setting("logHeard", "Logbook", "Log stations heard", "DMR callers and APRS stations go into the logbook as Heard", "bool", null, svc::logHeard, b -> svc.setLogHeard(b.getBoolean("value"))));
        l.add(new Setting("lookupOnline", "Logbook", "Look up names online", "RadioID / callsign lookups for callers and stations", "bool", null, svc::lookupOnline, b -> svc.setLookupOnline(b.getBoolean("value"))));
        if (svc.profile().dmr()) l.add(new Setting("roaming", "Radio", "DMR roaming", "While you travel, switch to the nearest repeater in this zone with the same talkgroup (needs repeater channels with a location)", "bool", null, svc::roaming, b -> svc.setRoaming(b.getBoolean("value"))));
        l.add(new Setting("coverage", "Coverage", "Coverage logging", "Records signal and key-ups with GPS for the coverage maps", "bool", null, () -> svc.coverage().enabled(), b -> svc.setCoverageLogging(b.getBoolean("value"))));
        l.add(new Setting("covInCar", "Coverage", "Log coverage in the car", "Coverage logging while Android Auto is connected", "bool", null, svc::coverageInCar, b -> svc.setCoverageInCar(b.getBoolean("value"))));
        l.add(new Setting("carMic", "Car", "Car microphone in Android Auto", "Talk uses the car's microphone through its hands-free link", "bool", null, svc::carMic, b -> svc.setCarMic(b.getBoolean("value"))));
        l.add(new Setting("wheelTalk", "Car", "Steering-wheel play/pause talks", "In Android Auto: play/pause starts and stops transmitting", "bool", null, svc::wheelTalk, b -> svc.setWheelTalk(b.getBoolean("value"))));
        l.add(new Setting("nextTalk", "Car", "Steering-wheel Next talks", "Hold Next to talk, or press it twice", "choice", RadioService.NEXT_TALK_NAMES, svc::nextTalkMode, b -> svc.setNextTalkMode(Math.max(0, Math.min(RadioService.NEXT_TALK_NAMES.length - 1, b.getInt("value"))))));
        l.add(new Setting("wheelMic", "Car", "Steering-wheel microphone", "Car (as a call) or the phone's microphone", "choice", RadioService.WHEEL_MIC_NAMES, svc::wheelMic, b -> svc.setWheelMic(Math.max(0, Math.min(1, b.getInt("value"))))));
        l.add(new Setting("voxStop", "Car", "Stop when I stop talking", "For transmissions started from the wheel", "choice", RadioService.VOX_NAMES, svc::voxStop, b -> svc.setVoxStop(Math.max(0, Math.min(RadioService.VOX_NAMES.length - 1, b.getInt("value"))))));
        l.add(new Setting("carScreen", "Car", "Full-screen Radio controls in the car", "Off: a media app; on: a big Talk screen (reconnect the car after changing)", "bool", null, svc::carControlsScreen, b -> svc.setCarControlsScreen(b.getBoolean("value"))));
        return l;
    }
    private JSONObject settingsJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Setting s : settings()) {
            JSONObject o = new JSONObject().put("key", s.key).put("group", s.group).put("label", s.label).put("sub", s.sub).put("kind", s.kind).put("value", s.get.get());
            if (s.choices != null) o.put("choices", new JSONArray(java.util.Arrays.asList(s.choices)));
            a.put(o);
        }
        return new JSONObject().put("settings", a).put("link", svc.link()).put("ready", svc.isReady()).put("profile", svc.profile() == null ? "" : svc.profile().name)
                .put("firmware", svc.moduleVersion().trim()).put("firmwareStock", svc.stockFirmware())
                .put("version", com.macdmr.radio.BuildConfig.VERSION_NAME).put("webTx", allowTx()).put("browsers", clients()).put("listeners", listeners())
                .put("region", regionJson(com.macdmr.radio.region.Region.current(svc)));
    }

    // ---- the API ----
    private WebServer.Response api(WebServer.Request r) throws Exception {
        String p = r.path; boolean post = "POST".equals(r.method);
        final JSONObject b = post ? body(r) : new JSONObject();
        switch (p) {
            case "/api/status": return ok(statusJson());
            case "/api/phone": return ok(phone().json());                       // battery, cell and Wi-Fi of the phone itself
            case "/api/captions": {                                             // finished caption sentences, newest first (q: only those containing it)
                int lim = Math.max(1, Math.min(5000, parse(r.param("limit"), 200)));
                java.util.Map<Integer, String> names = new java.util.HashMap<>();
                JSONArray a = new JSONArray();
                for (Object[] row : svc.store().captions(lim, r.param("q"))) {
                    int n = (Integer) row[1];
                    String name = names.get(n); if (name == null) { ChannelStore.Channel ch = svc.store().byNumber(n); name = ch != null ? ch.name : "channel " + n; names.put(n, name); }
                    a.put(new JSONObject().put("ts", row[0]).put("channel", n).put("channelName", name).put("text", ((String) row[2]).replaceFirst("^\uD83D\uDCDD\\s*", "")));
                }
                return ok(new JSONObject().put("captions", a).put("on", svc.captionsOn()).put("available", svc.captionsAvailable()).put("engine", svc.captionEngine()));
            }
            case "/api/captions/set": if (!post) break; {                        // TXT on/off, as the phone's TXT key
                boolean on = b.optBoolean("on", !svc.captionsOn());
                if (on && !svc.captionsAvailable()) return WebServer.Response.error(409, "Captions need a Google Cloud key for the Google engine: set one on the phone, or switch to the on-device engine (long-press TXT)");
                onMain(() -> { svc.setCaptions(on); return null; });
                return ok(on ? "Captions on" + ("vosk".equals(svc.captionEngine()) ? " (the on-device model downloads on first use)" : "") : "Captions off");
            }
            case "/api/captions/models": {                                      // engine, the on-device models (size, downloaded, in use), the recognizer's state
                JSONArray a = new JSONArray(); String sel = svc.captionModel(), loaded = svc.captionLoadedModel();
                for (int i = 0; i < VoskCaptions.MODELS.length; i++) {
                    String n = VoskCaptions.MODELS[i]; boolean present = VoskCaptions.modelPresent(svc, n);
                    a.put(new JSONObject().put("name", n).put("label", VoskCaptions.MODEL_LABELS[i]).put("present", present).put("selected", n.equals(sel)).put("loaded", n.equals(loaded))
                            .put("bytes", present ? VoskCaptions.modelBytes(svc, n) : 0));
                }
                return ok(new JSONObject().put("engine", svc.captionEngine()).put("models", a).put("status", svc.captionStatus()).put("busy", svc.captionModelBusy())
                        .put("googleKeySet", !TranscriptionClient.isPlaceholderKey(svc.googleKey())).put("on", svc.captionsOn())
                        .put("freeBytes", svc.getFilesDir().getUsableSpace()));
            }
            case "/api/captions/model": if (!post) break; {                     // use a model: downloaded first if it is not on the phone yet, then loaded
                String n = b.optString("name", ""); if (!java.util.Arrays.asList(VoskCaptions.MODELS).contains(n)) return WebServer.Response.error(400, "Unknown model");
                if (!VoskCaptions.modelPresent(svc, n)) {
                    long need = n.contains("gigaspeech") ? 2_600_000_000L * 2 : n.contains("0.22-lgraph") ? 140_000_000L * 2 : n.contains("small") ? 45_000_000L * 2 : 1_900_000_000L * 2;   // the zip and its unpacked copy
                    if (svc.getFilesDir().getUsableSpace() < need) return WebServer.Response.error(507, "Not enough free space on the phone for this model (needs about " + (need / 1_000_000_000.0 > 1 ? String.format(java.util.Locale.US, "%.1f GB", need / 1e9) : (need / 1_000_000) + " MB") + " while downloading)");
                }
                onMain(() -> { svc.setCaptionModel(n); return null; });
                return ok(VoskCaptions.modelPresent(svc, n) ? "Loading the model" : "Downloading the model on the phone");
            }
            case "/api/captions/model/delete": if (!post) break; {
                String n = b.optString("name", ""); if (!java.util.Arrays.asList(VoskCaptions.MODELS).contains(n)) return WebServer.Response.error(400, "Unknown model");
                if (n.equals(svc.captionModel()) || n.equals(svc.captionLoadedModel())) return WebServer.Response.error(409, "That model is in use: choose another one first");
                if (svc.captionModelBusy()) return WebServer.Response.error(409, "A model is being downloaded or loaded: try again when it is done");
                return VoskCaptions.deleteModel(svc, n) ? ok("Deleted") : WebServer.Response.error(500, "Could not delete it");
            }
            case "/api/captions/engine": if (!post) break; {                    // vosk (on the phone) or google (your key; write-only here)
                String e = b.optString("engine", svc.captionEngine()); if (!"vosk".equals(e) && !"google".equals(e)) return WebServer.Response.error(400, "Unknown engine");
                String k = b.optString("googleKey", "").trim();
                if (!k.isEmpty()) svc.setGoogleKey(k);
                if ("google".equals(e) && TranscriptionClient.isPlaceholderKey(svc.googleKey())) return WebServer.Response.error(409, "Set a Google Cloud Speech API key first");
                svc.setCaptionEngine(e);
                return ok("google".equals(e) ? "Captions use Google Cloud Speech" : "Captions are made on the phone");
            }
            case "/api/mission": return ok(new JSONObject().put("text", com.macdmr.radio.ui.DeviceView.MISSION).put("by", "IIMacGyverII, SignalDeck").put("legal", com.macdmr.radio.About.legal()));   // the same words as the phone's button and the README
            case "/api/history": {
                ChannelStore.Channel ch = svc.current(); int lim = Math.min(500, parse(r.param("limit"), 120));
                JSONArray a = new JSONArray(); if (ch != null) for (String l : svc.store().recentHistory(ch.number, lim)) a.put(l);
                return ok(new JSONObject().put("lines", a));
            }
            case "/api/channels": {
                java.util.Set<Integer> favs = svc.store().favourites();
                JSONArray a = new JSONArray(); for (ChannelStore.Channel ch : "1".equals(r.param("all")) ? svc.store().all() : svc.zoneChannels()) a.put(channelObj(ch).put("fav", favs.contains(ch.number)));   // all=1: every channel (the map)
                JSONArray z = new JSONArray(); z.put(new JSONObject().put("id", 0).put("name", "ALL"));
                for (ChannelStore.Zone zz : svc.store().zones()) z.put(new JSONObject().put("id", zz.id).put("name", zz.name));
                return ok(new JSONObject().put("channels", a).put("zones", z).put("zoneId", svc.zoneId()));
            }
            case "/api/channel/star": if (!post) break; {                    // star / unstar: the ★ Favourites zone
                int n = b.optInt("number", 0); boolean on = b.optBoolean("on", true);
                if (svc.store().byNumber(n) == null) return WebServer.Response.error(404, "No channel " + n);
                onMain(() -> { svc.store().setFavourite(n, on); return null; });
                return ok(on ? "Starred channel " + n : "Channel " + n + " is no longer a favourite");
            }
            case "/api/channel/select": if (!post) break; {
                int n = b.optInt("number", -1); if (n < 0) return WebServer.Response.error(400, "number needed");
                onMain(() -> { svc.selectChannel(n); return null; }); return ok("Channel " + n);
            }
            case "/api/channel/step": if (!post) break; { int d = b.optInt("delta", 1); onMain(() -> { svc.stepChannel(d >= 0 ? 1 : -1); return null; }); return ok("stepped"); }
            case "/api/zone": if (!post) break; { long id = b.optLong("id", 0); onMain(() -> { svc.setZone(id); return null; }); return ok(svc.zoneName()); }
            case "/api/scan": if (!post) break; return ok(onMain(svc::toggleScan));
            case "/api/softsq": if (!post) break;
                onMain(() -> { if (b.has("on")) svc.setSoftSq(b.getBoolean("on")); if (b.has("level")) svc.setSoftSqLevel(b.getInt("level")); return null; });
                return ok(statusJson());
            case "/api/monitor": if (!post) break; onMain(() -> { svc.setMonitor(b.optBoolean("on", !svc.monitorOn())); return null; }); return ok(statusJson());
            case "/api/record": if (!post) break; onMain(() -> { svc.setRecord(b.optBoolean("on", !svc.recordOn())); return null; }); return ok(statusJson());
            case "/api/ptt": if (!post) break; {
                boolean down = b.optBoolean("down", false);
                if (down && !allowTx()) return WebServer.Response.error(403, TX_OFF);
                RadioService.SatState s = svc.satState();
                if (down && s != null && s.listenOnly) return WebServer.Response.error(409, "Receive only during this satellite pass");
                if (down) svc.history("warn", "🌐 Transmitting from the web (" + r.remote + ")");
                onMain(() -> { svc.ptt(down); return null; });
                return ok(down ? "PTT down" : "PTT up");
            }
            case "/api/aprs/log": {
                JSONArray a = new JSONArray();
                if (svc.packet() != null) for (PacketStore.LogEntry e : svc.packet().store().aprsLog(Math.min(500, parse(r.param("limit"), 200))))
                    a.put(new JSONObject().put("ts", e.ts).put("kind", e.kind).put("from", e.from).put("to", e.to).put("text", e.text).put("mine", e.mine));
                return ok(new JSONObject().put("log", a));
            }
            case "/api/aprs/stations": {
                JSONArray a = new JSONArray();
                if (svc.packet() != null) for (PacketStore.Station s : svc.packet().store().stations(Math.min(500, parse(r.param("limit"), 200)))) {
                    JSONObject o = new JSONObject().put("call", s.call).put("lastTs", s.lastTs).put("count", s.count).put("comment", s.comment).put("via", s.via)
                            .put("sym", String.valueOf(s.symTable) + s.symCode);
                    if (s.lat != 0 || s.lon != 0) o.put("lat", s.lat).put("lon", s.lon);
                    if (s.speedKmh >= 0) o.put("speedKmh", s.speedKmh);
                    if (s.courseDeg >= 0) o.put("course", s.courseDeg);
                    if (s.alt != 0) o.put("alt", s.alt);
                    a.put(o);
                }
                return ok(new JSONObject().put("stations", a));
            }
            case "/api/aprs/monitor": if (!post) break; return ok(onMain(() -> svc.aprsMonitor(b.optBoolean("on", true))));
            case "/api/aprs/message": if (!post) break; {
                if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                String to = b.optString("to", "").trim(), text = b.optString("text", "").trim();
                if (to.isEmpty() || text.isEmpty()) return WebServer.Response.error(400, "To and text are needed");
                String e = onMain(() -> svc.packet().sendMessage(to, text));
                return e == null ? ok("Message to " + to + " queued") : WebServer.Response.error(409, e);
            }
            case "/api/aprs/beacon": if (!post) break; {
                if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                String e = onMain(() -> svc.packet().sendBeacon());
                return e == null ? ok("Beacon sent") : WebServer.Response.error(409, e);
            }
            case "/api/sstv": return ok(sstvJson());
            case "/api/sstv/monitor": if (!post) break; return ok(String.valueOf(onMain(() -> svc.sstvMonitor(b.optBoolean("on", true), false))));
            case "/api/sstv/live.png": {
                android.graphics.Bitmap bm = svc.packet() == null ? null : svc.packet().sstvLast();
                if (bm == null) return WebServer.Response.error(404, "No picture yet");
                ByteArrayOutputStream o = new ByteArrayOutputStream(); bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
                WebServer.Response x = WebServer.Response.bytes("image/png", o.toByteArray()); x.headers.put("Cache-Control", "no-store"); return x;
            }
            case "/api/decode/recordings": {                                    // what can be decoded: SignalDeck's recordings, newest first
                JSONArray a = new JSONArray();
                for (File f : com.macdmr.radio.RecordingDecodeJob.recordings(60))
                    a.put(new JSONObject().put("folder", f.getParentFile().getName()).put("name", f.getName()).put("ts", f.lastModified()).put("seconds", Math.max(0, (f.length() - 44) / 16000)).put("pass", f.getName().contains("_pass")));
                return ok(new JSONObject().put("recordings", a));
            }
            case "/api/decode": {                                               // POST {folder, name, mode: sstv|aprs} starts; GET reports the job
                if (post) {
                    File f = com.macdmr.radio.RecordingDecodeJob.recording(b.optString("folder", null), b.optString("name", null));
                    if (f == null) return WebServer.Response.error(404, "Recording not found");
                    String e = com.macdmr.radio.RecordingDecodeJob.start(b.optString("mode", "sstv"), f, svc);
                    return e == null ? ok("Decoding " + f.getName()) : WebServer.Response.error(409, e);
                }
                com.macdmr.radio.RecordingDecodeJob j = com.macdmr.radio.RecordingDecodeJob.current();
                if (j == null) return ok(new JSONObject().put("job", JSONObject.NULL));
                JSONObject o = new JSONObject().put("file", j.file.getName()).put("mode", j.mode).put("percent", j.percent).put("stage", j.stage).put("done", j.done).put("summary", j.summary == null ? "" : j.summary).put("error", j.error == null ? "" : j.error).put("started", j.startedAt);
                JSONArray pics = new JSONArray(); synchronized (j.pictures) { for (String s : j.pictures) pics.put(s); }
                JSONArray pk = new JSONArray(); synchronized (j.packets) { int n = j.packets.size(); for (int i = Math.max(0, n - 300); i < n; i++) pk.put(j.packets.get(i)); o.put("packetCount", n); }
                return ok(new JSONObject().put("job", o.put("pictures", pics).put("packets", pk)));
            }
            case "/api/decode/upload": if (!post) break; {                     // body: the recording's bytes; ?name=file.mp3&mode=sstv|aprs
                if (r.body == null || r.body.length < 100) return WebServer.Response.error(400, "Empty file");
                File f;
                try { f = com.macdmr.radio.RecordingDecodeJob.saveUpload(r.param("name"), new java.io.ByteArrayInputStream(r.body)); }
                catch (java.io.IOException e) { return WebServer.Response.error(400, e.getMessage()); }
                String e = com.macdmr.radio.RecordingDecodeJob.start(r.param("mode") == null ? "sstv" : r.param("mode"), f, svc);
                return e == null ? ok("Uploaded " + f.getName() + "; decoding") : WebServer.Response.error(409, "Uploaded " + f.getName() + ", but: " + e);
            }
            case "/api/decode/stop": if (!post) break; { com.macdmr.radio.RecordingDecodeJob j = com.macdmr.radio.RecordingDecodeJob.current(); if (j != null) j.cancel(); return ok("Stopping"); }
            case "/api/sstv/pictures": {
                File[] fs = sstvDir().listFiles((d, n) -> n.endsWith(".png") || n.endsWith(".jpg"));
                JSONArray a = new JSONArray();
                if (fs != null) {
                    Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                    for (File f : fs) a.put(new JSONObject().put("name", f.getName()).put("ts", f.lastModified()).put("size", f.length()));
                }
                return ok(new JSONObject().put("pictures", a));
            }
            case "/api/sstv/combine": if (!post) break; {                        // {names, dy?, dx?, use?, save?}: line-up + preview, or save it
                JSONArray names = b.optJSONArray("names"); if (names == null || names.length() < 2) return WebServer.Response.error(400, "Select two or more pictures");
                java.util.List<File> fs = new java.util.ArrayList<>();
                for (int i = 0; i < names.length(); i++) {
                    String n = names.optString(i, "");
                    if (n.isEmpty() || n.contains("/") || n.contains("\\") || n.contains("..")) return WebServer.Response.error(400, "Bad name");
                    File f = new File(sstvDir(), n); if (!f.isFile()) return WebServer.Response.error(404, "Not found: " + n); fs.add(f);
                }
                String[] err = { null };
                com.macdmr.radio.SstvCombine.Plan pl = com.macdmr.radio.SstvCombine.plan(fs, err);
                if (pl == null) return WebServer.Response.error(409, err[0]);
                JSONArray jy = b.optJSONArray("dy"), jx = b.optJSONArray("dx"), ju = b.optJSONArray("use");
                if (jy != null && jx != null && ju != null && jy.length() == fs.size() && jx.length() == fs.size() && ju.length() == fs.size())
                    for (int i = 0; i < fs.size(); i++) { pl.dy[i] = Math.max(-pl.h, Math.min(pl.h, jy.optInt(i))); pl.dx[i] = Math.max(-pl.w / 2, Math.min(pl.w / 2, jx.optInt(i))); pl.use[i] = i == pl.ref || ju.optBoolean(i); }
                if (b.optBoolean("save")) {
                    com.macdmr.radio.SstvCombine.Result cr = com.macdmr.radio.SstvCombine.save(pl);
                    if (cr.file == null) return WebServer.Response.error(409, cr.message);
                    return ok(new JSONObject().put("message", cr.message).put("name", cr.file.getName()));
                }
                JSONArray oy = new JSONArray(), ox = new JSONArray(), ou = new JSONArray(), os = new JSONArray();
                for (int i = 0; i < fs.size(); i++) { oy.put(pl.dy[i]); ox.put(pl.dx[i]); ou.put(pl.use[i]); os.put(pl.sure[i]); }
                return ok(new JSONObject().put("ref", pl.ref).put("dy", oy).put("dx", ox).put("use", ou).put("sure", os)
                        .put("png", android.util.Base64.encodeToString(com.macdmr.radio.SstvCombine.png(pl), android.util.Base64.NO_WRAP)));
            }
            case "/api/sstv/delete": if (!post) break; {                         // {names: [...]}: received pictures in Download/DMR/SSTV only
                JSONArray names = b.optJSONArray("names"); if (names == null || names.length() == 0) return WebServer.Response.error(400, "No pictures given");
                int gone = 0;
                for (int i = 0; i < names.length(); i++) {
                    String n = names.optString(i, "");
                    if (n.isEmpty() || n.contains("/") || n.contains("\\") || n.contains("..") || !(n.toLowerCase(java.util.Locale.US).endsWith(".png") || n.toLowerCase(java.util.Locale.US).endsWith(".jpg"))) continue;
                    File f = new File(sstvDir(), n); if (f.isFile() && f.delete()) gone++;
                }
                return ok(new JSONObject().put("deleted", gone));
            }
            case "/api/sstv/picture": {
                String n = r.param("name");
                if (n == null || n.contains("/") || n.contains("\\") || n.contains("..")) return WebServer.Response.error(400, "Bad name");
                File f = new File(sstvDir(), n); if (!f.isFile()) return WebServer.Response.error(404, "Not found");
                byte[] data; try (InputStream in = new java.io.FileInputStream(f)) { data = readAll(in); }
                return WebServer.Response.bytes(n.endsWith(".png") ? "image/png" : "image/jpeg", data);
            }
            case "/api/iss": if (!post) break; {
                String m = b.optString("mode", "");
                if (m.equals("stop")) { onMain(() -> { svc.endSatTracking(); return null; }); return ok("Stopped tracking"); }
                int mode = m.equals("sstv") ? com.macdmr.radio.ham.Satellites.SSTV : m.equals("aprs") ? com.macdmr.radio.ham.Satellites.APRS : -1;
                if (mode < 0) return WebServer.Response.error(400, "mode: aprs, sstv or stop");
                String e = onMain(() -> { String err = svc.startIssMode(mode); if (err == null) svc.setIssRepeat(mode); return err; });
                return e == null ? ok("Tracking the ISS (" + m + ")") : WebServer.Response.error(409, e);
            }
            case "/api/channel": {                                              // one channel with everything the editor needs (number 0: a new one)
                int n = parse(r.param("number"), 0);
                ChannelStore.Channel ch = n > 0 ? svc.store().byNumber(n) : null;
                if (n > 0 && ch == null) return WebServer.Response.error(404, "No channel " + n);
                if (ch == null) {
                    ch = new ChannelStore.Channel(); ChannelStore.Channel cur = svc.current();
                    if (cur != null) { ch.type = cur.type; ch.rxFreq = cur.rxFreq; ch.txFreq = cur.txFreq; }
                    if (!svc.profile().dmr() && ch.type == ChannelStore.Channel.TYPE_DMR) ch.type = ChannelStore.Channel.TYPE_FM;   // an analog-only radio: a new channel is analog
                }
                JSONObject help = new JSONObject(); for (String[] h : com.macdmr.radio.ui.ChannelEditorView.helpForWeb()) help.put(h[0], h[1]);
                return ok(new JSONObject().put("channel", channelFull(ch)).put("isNew", n <= 0).put("help", help).put("tones", toneLists()).put("nameMax", ChannelStore.NAME_MAX));
            }
            case "/api/channel/save": if (!post) break; {
                int n = b.optInt("number", 0);
                ChannelStore.Channel ch = n > 0 ? svc.store().byNumber(n) : new ChannelStore.Channel();
                if (ch == null) return WebServer.Response.error(404, "No channel " + n);
                String err = applyChannel(ch, b);
                if (err != null) return WebServer.Response.error(400, err);
                if (n <= 0 && ch.isDigital() && !svc.profile().dmr()) return WebServer.Response.error(400, "This radio has no DMR: add an analog channel");
                final ChannelStore.Channel c = ch; final boolean isNew = n <= 0;
                onMain(() -> { svc.saveChannel(c, isNew); return null; });
                return WebServer.Response.json(200, new JSONObject().put("ok", true).put("message", "Saved " + (c.name.isEmpty() ? "channel " + c.number : c.name)).put("number", c.number).toString());
            }
            case "/api/vfo": {                                                  // GET: the VFO's settings; POST: tune it (enters VFO mode)
                if (!post) {
                    JSONObject help = new JSONObject(); for (String[] h : com.macdmr.radio.ui.ChannelEditorView.helpForWeb()) help.put(h[0], h[1]);
                    JSONArray steps = new JSONArray(); for (int s : VFO_STEPS) steps.put(s);
                    return ok(new JSONObject().put("channel", channelFull(svc.vfoChannel())).put("active", svc.vfoActive()).put("step", svc.vfoStepHz()).put("steps", steps)
                            .put("help", help).put("tones", toneLists()));
                }
                if (svc.firmwareLoading()) return WebServer.Response.error(409, "Radio busy, loading firmware");
                if (svc.isTransmitting()) return WebServer.Response.error(409, "Not while transmitting");
                ChannelStore.Channel v = svc.vfoChannel();
                if (!b.has("lat")) b.put("lat", ""); if (!b.has("lon")) b.put("lon", "");
                String err = applyChannel(v, b);
                if (err != null) return WebServer.Response.error(400, err);
                if (v.isDigital() && v.contactType != 2 && v.txContact <= 0) return WebServer.Response.error(400, "A call number is needed for a private or group call");
                v.name = ""; v.lat = 0; v.lon = 0;
                int step = b.optInt("step", svc.vfoStepHz()); boolean stepOk = false; for (int s : VFO_STEPS) stepOk |= s == step;
                final boolean setStep = stepOk;
                onMain(() -> { if (setStep) svc.setVfoStepHz(step); svc.applyVfo(v); return null; });
                return ok(String.format(java.util.Locale.US, "VFO %.4f MHz", v.rxFreq / 1e6));
            }
            case "/api/vfo/exit": if (!post) break; onMain(() -> { svc.exitVfo(); return null; }); return ok("Back to the channel");
            case "/api/channels/stockimport": if (!post) break; {              // the empty-list question: {import: true | false}
                final boolean yes = b.optBoolean("import", false);
                String msg = yes ? svc.answerStockImport(true) : onMain(() -> svc.answerStockImport(false));
                return msg == null ? WebServer.Response.error(409, "Already answered") : ok(msg);
            }
            case "/api/channels/bulk": if (!post) break; {                    // the channel list's selection: {action: delete | zone | unzone | star | unstar, numbers[], zoneId, zoneName}
                JSONArray ns = b.optJSONArray("numbers"); java.util.List<Integer> nums = new java.util.ArrayList<>();
                if (ns != null) for (int i = 0; i < ns.length(); i++) nums.add(ns.optInt(i, 0));
                if (nums.isEmpty()) return WebServer.Response.error(400, "Select at least one channel");
                final String action = b.optString("action", ""); final long zid = b.optLong("zoneId", 0); final String zname = b.optString("zoneName", "");
                String msg;
                switch (action) {
                    case "delete": msg = onMain(() -> svc.deleteChannels(nums)); break;
                    case "zone": msg = onMain(() -> svc.addChannelsToZone(zid, zname, nums)); break;
                    case "unzone": msg = onMain(() -> svc.removeChannelsFromZone(zid, nums)); break;
                    case "star": case "unstar": msg = onMain(() -> svc.favouriteChannels(nums, action.equals("star"))); break;
                    default: return WebServer.Response.error(400, "Unknown action " + action);
                }
                return ok(msg);
            }
            case "/api/channel/delete": if (!post) break; {
                ChannelStore.Channel ch = svc.store().byNumber(b.optInt("number", 0));
                if (ch == null) return WebServer.Response.error(404, "No such channel");
                if (svc.firmwareLoading()) return WebServer.Response.error(409, "Radio busy, loading firmware");
                if (svc.isTransmitting()) return WebServer.Response.error(409, "Not while transmitting");
                onMain(() -> { svc.deleteChannel(ch); return null; });
                return ok("Deleted " + (ch.name.isEmpty() ? "channel " + ch.number : ch.name));
            }
            case "/api/sat/passes": {                                           // every satellite's next passes over the phone (no position sent)
                android.location.Location me = svc.lastKnownLocation();
                com.macdmr.radio.ham.Satellites sats = svc.satellites();
                java.util.Map<Integer, com.macdmr.radio.ham.Sgp4> el = sats.elements();
                long now = System.currentTimeMillis();
                JSONArray a = new JSONArray();
                for (com.macdmr.radio.ham.Satellites.Sat s : com.macdmr.radio.ham.Satellites.all(svc)) {
                    if (!com.macdmr.radio.DeviceProfile.inBands(s.downHz)) continue;                 // a downlink this radio cannot tune (the W6: UHF only)
                    JSONObject o = new JSONObject().put("key", s.key).put("name", s.name).put("about", s.about).put("downHz", s.downHz).put("upHz", s.upHz)
                            .put("toneHz", s.toneHz).put("mode", s.mode).put("listenOnly", s.listenOnly());
                    String an = com.macdmr.radio.ham.FmSats.amsatName(s); if (an != null && !(s.catalog == com.macdmr.radio.ham.Satellites.ISS_CATALOG && s.mode == com.macdmr.radio.ham.Satellites.APRS)) o.put("amsat", an);
                    JSONArray ps = new JSONArray(); com.macdmr.radio.ham.Sgp4 e = el.get(s.catalog);
                    if (e == null) o.put("problem", "No orbit for it in the download");
                    else if (me != null) {
                        for (com.macdmr.radio.ham.SatTrack.Pass pa : com.macdmr.radio.ham.SatTrack.passes(e, now - 15 * 60_000L, 24, me.getLatitude(), me.getLongitude(), me.hasAltitude() ? me.getAltitude() : 0, 10)) {
                            if (pa.los < now) continue;
                            ps.put(new JSONObject().put("aos", pa.aos).put("los", pa.los).put("maxEl", pa.maxEl).put("aosAz", pa.aosAz).put("losAz", pa.losAz));
                            if (ps.length() >= 6) break;
                        }
                    }
                    a.put(o.put("passes", ps));
                }
                return ok(new JSONObject().put("satellites", a).put("hasFix", me != null).put("elementsAgeH", sats.elementsAgeMs() / 3_600_000.0));
            }
            case "/api/schedules": {                                            // GET the list; POST one {id?, action, hour, minute, days, date, minutes, channel, record, label, enabled}
                if (post) {
                    com.macdmr.radio.Scheduler.Entry e = new com.macdmr.radio.Scheduler.Entry();
                    e.id = b.optInt("id", 0); e.action = b.optString("action", "listen"); e.hour = Math.max(0, Math.min(23, b.optInt("hour", 19))); e.minute = Math.max(0, Math.min(59, b.optInt("minute", 0)));
                    e.days = b.optInt("days", 0) & 0x7F; e.date = b.optString("date", ""); e.minutes = Math.max(1, Math.min(1440, b.optInt("minutes", 60))); e.channel = b.optInt("channel", -1);
                    e.record = b.optBoolean("record", false); e.label = b.optString("label", "").trim(); e.enabled = b.optBoolean("enabled", true);
                    if (!java.util.Arrays.asList(com.macdmr.radio.Scheduler.ACTIONS).contains(e.action)) return WebServer.Response.error(400, "Unknown action");
                    if (e.days != 0) e.date = ""; else if (!e.date.matches("\\d{4}-\\d{2}-\\d{2}")) return WebServer.Response.error(400, "Choose days or a date");
                    if (e.enabled && com.macdmr.radio.Scheduler.nextStart(e, System.currentTimeMillis(), java.util.TimeZone.getDefault()) < 0) return WebServer.Response.error(400, "That time has already passed");
                    com.macdmr.radio.Scheduler.put(svc, e);
                    return ok("Saved");
                }
                long now = System.currentTimeMillis();
                return ok(new JSONObject().put("schedules", com.macdmr.radio.Scheduler.json(svc)).put("running", com.macdmr.radio.Scheduler.runningEnd(svc) > now ? com.macdmr.radio.Scheduler.runningId(svc) : 0).put("runningEnd", com.macdmr.radio.Scheduler.runningEnd(svc)));
            }
            case "/api/schedules/delete": if (!post) break; { com.macdmr.radio.Scheduler.remove(svc, b.optInt("id")); return ok("Deleted"); }
            case "/api/conditions": {                                           // tropo / aurora / meteors (the phone fetches; the browser gets the result)
                android.location.Location me = svc.lastKnownLocation();
                com.macdmr.radio.ham.Conditions.Report rep = com.macdmr.radio.ham.Conditions.get(me == null ? 0 : me.getLatitude(), me == null ? 0 : me.getLongitude(), me != null);
                return ok(com.macdmr.radio.ham.Conditions.json(rep));
            }
            case "/api/scope": {                                                // GET: presets, state, the last sweep, peak hold and the waterfall (newest last)
                com.macdmr.radio.BandScope s = svc.scope();
                JSONArray pr = new JSONArray(); for (com.macdmr.radio.BandScope.Preset p0 : com.macdmr.radio.BandScope.PRESETS) pr.put(new JSONObject().put("name", p0.name).put("from", p0.from).put("to", p0.to).put("step", p0.step));
                JSONObject o = new JSONObject().put("presets", pr).put("running", s.running()).put("from", s.from()).put("to", s.to()).put("step", s.step()).put("sweepMs", s.sweepMs()).put("settleMs", s.settleMs()).put("estimateMs", s.estimateMs()).put("currentHz", s.currentHz()).put("maxSteps", com.macdmr.radio.BandScope.MAX_STEPS);
                float[] last = s.last(), peak = s.peak(), part = s.partial();
                if (last != null) o.put("last", arr(last)); if (peak != null) o.put("peak", arr(peak)); if (part != null && s.partialIndex() >= 0) o.put("partial", arr(part)).put("partialIndex", s.partialIndex());
                JSONArray h = new JSONArray(); java.util.List<float[]> hist = s.history(); for (int k = Math.max(0, hist.size() - 60); k < hist.size(); k++) h.put(arr(hist.get(k)));
                JSONArray pk = new JSONArray(); for (int[] p0 : com.macdmr.radio.BandScope.peaks(last, s.from(), s.step(), 8)) pk.put(new JSONObject().put("hz", p0[0]).put("dbm", p0[1]).put("name", channelNameAt(p0[0], s.step())));
                return ok(o.put("history", h).put("peaks", pk));
            }
            case "/api/scope/start": if (!post) break; {
                String bad = com.macdmr.radio.BandScope.problem(b.optInt("from"), b.optInt("to"), b.optInt("step", 25_000));
                if (bad != null) return WebServer.Response.error(400, bad);
                String e = svc.scopeStart(b.optInt("from"), b.optInt("to"), b.optInt("step", 25_000), svc.profile().scopeSettleMs);
                return e == null ? ok("Sweeping") : WebServer.Response.error(409, e);
            }
            case "/api/scope/stop": if (!post) break; { new Thread(svc::scopeStop, "scope-stop").start(); return ok("Stopping"); }
            case "/api/scope/listen": if (!post) break; {                       // stop and listen on {hz} with the VFO
                final int hz = b.optInt("hz"); if (!com.macdmr.radio.BandScope.tunable(hz)) return WebServer.Response.error(400, "Not a frequency this radio can tune");
                new Thread(() -> { svc.scopeStop(); ChannelStore.Channel v = new ChannelStore.Channel(); v.type = 1; v.rxFreq = v.txFreq = hz; v.band = 1; v.power = 0; v.sq = 2; v.name = ""; svc.applyVfo(v); }, "scope-listen").start();
                return ok(String.format(java.util.Locale.US, "Listening on %.4f MHz (VFO)", hz / 1e6));
            }
            case "/api/sat/status": {                                           // AMSAT's reports of the last 4 days for one satellite (?name=SO-50_[FM])
                String n = r.param("name"); if (n == null || !n.matches("[A-Za-z0-9 -]+_\\[[A-Z]+\\]")) return WebServer.Response.error(400, "Bad name");
                com.macdmr.radio.ham.FmSats.Status st = com.macdmr.radio.ham.FmSats.status(n, 96);
                return ok(new JSONObject().put("line", com.macdmr.radio.ham.FmSats.describe(st, System.currentTimeMillis())).put("heard", st == null ? -1 : st.heard + st.crew).put("telemetry", st == null ? 0 : st.telemetry));
            }
            case "/api/sat/update": if (!post) break; { String e = svc.satellites().updateAll(); return e == null ? ok("Orbits and the FM satellite list updated") : WebServer.Response.error(502, e); }
            case "/api/sat/track": if (!post) break; {                          // track one pass {key, aos}
                android.location.Location me = svc.lastKnownLocation(); if (me == null) return WebServer.Response.error(409, "No position fix on the phone yet");
                String key = b.optString("key", ""); long aos = b.optLong("aos", 0);
                for (com.macdmr.radio.ham.Satellites.Sat s : com.macdmr.radio.ham.Satellites.all(svc)) {
                    if (!s.key.equals(key)) continue;
                    com.macdmr.radio.ham.Sgp4 e = svc.satellites().elements().get(s.catalog); if (e == null) return WebServer.Response.error(409, "No orbit for " + s.name);
                    for (com.macdmr.radio.ham.SatTrack.Pass pa : com.macdmr.radio.ham.SatTrack.passes(e, System.currentTimeMillis() - 15 * 60_000L, 24, me.getLatitude(), me.getLongitude(), me.hasAltitude() ? me.getAltitude() : 0, 10)) {
                        if (Math.abs(pa.aos - aos) > 120_000) continue;
                        String err = onMain(() -> { svc.setIssRepeat(-1); return svc.trackSat(s, e, pa); });
                        return err == null ? ok("Tracking " + s.name) : WebServer.Response.error(409, err);
                    }
                    return WebServer.Response.error(404, "That pass is no longer ahead");
                }
                return WebServer.Response.error(404, "No such satellite");
            }
            case "/api/recordings": {                                           // REC files: Download/DMR/Audio/<channel>/*.wav
                java.util.List<File> files = new java.util.ArrayList<>();
                File[] chans = com.macdmr.radio.ui.MediaDialogs.audioRoot().listFiles();
                if (chans != null) for (File d : chans) { File[] fs = d.listFiles(); if (fs != null) for (File f : fs) if (f.getName().endsWith(".wav")) files.add(f); }
                files.sort((x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                JSONArray a = new JSONArray();
                for (File f : files) { if (a.length() >= 300) break; a.put(new JSONObject().put("id", f.getParentFile().getName() + "/" + f.getName()).put("channel", f.getParentFile().getName()).put("name", f.getName()).put("ts", f.lastModified()).put("seconds", Math.max(0, (f.length() - 44) / 16000.0))); }
                return ok(new JSONObject().put("recordings", a));
            }
            case "/api/recording": case "/api/recording/delete": {
                File f = recording(post ? b.optString("id", "") : r.param("id"));
                if (f == null) return WebServer.Response.error(404, "No such recording");
                if (post) return f.delete() ? ok("Deleted " + f.getName()) : WebServer.Response.error(500, "Could not delete " + f.getName());
                byte[] data; try (InputStream in = new java.io.FileInputStream(f)) { data = readAll(in); }
                WebServer.Response x = WebServer.Response.bytes("audio/wav", data);
                if ("1".equals(r.param("download"))) x.headers.put("Content-Disposition", "attachment; filename=\"" + f.getName().replace("\"", "") + "\"");
                return x;
            }
            case "/api/logbook": {
                com.macdmr.radio.ham.Logbook lb = svc.logbook();
                if (post) {                                                     // save {id?, call, freqMHz, mode, rstSent, rstRcvd, name, notes, ts?}
                    com.macdmr.radio.ham.Logbook.Entry e = null;
                    long id = b.optLong("id", 0);
                    if (id > 0) for (com.macdmr.radio.ham.Logbook.Entry x : lb.list(5000, false)) if (x.id == id) e = x;
                    if (e == null) { e = new com.macdmr.radio.ham.Logbook.Entry(); e.ts = System.currentTimeMillis(); e.qso = true; e.source = "web"; }
                    String call = b.optString("call", "").trim().toUpperCase(java.util.Locale.US);
                    if (call.isEmpty()) return WebServer.Response.error(400, "A callsign is needed");
                    e.call = call; e.mode = b.optString("mode", e.mode); e.rstSent = b.optString("rstSent", e.rstSent); e.rstRcvd = b.optString("rstRcvd", e.rstRcvd);
                    e.name = b.optString("name", e.name); e.notes = b.optString("notes", e.notes); e.qso = b.optBoolean("qso", e.qso);
                    if (b.has("freqMHz")) e.freqHz = Math.round(b.getDouble("freqMHz") * 1e6);
                    if (b.has("ts")) e.ts = b.getLong("ts");
                    lb.save(e);
                    return ok("Logged " + e.call);
                }
                boolean qsoOnly = "1".equals(r.param("qso"));
                java.util.List<com.macdmr.radio.ham.Logbook.Entry> list = lb.list(Math.min(2000, parse(r.param("limit"), 500)), qsoOnly);
                if ("adif".equals(r.param("format"))) {
                    WebServer.Response x = WebServer.Response.bytes("text/plain; charset=utf-8", com.macdmr.radio.ham.Logbook.adif(list, com.macdmr.radio.BuildConfig.VERSION_NAME).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    x.headers.put("Content-Disposition", "attachment; filename=\"signaldeck_log.adi\""); return x;
                }
                JSONArray a = new JSONArray();
                for (com.macdmr.radio.ham.Logbook.Entry e : list)
                    a.put(new JSONObject().put("id", e.id).put("ts", e.ts).put("call", e.call).put("freqHz", e.freqHz).put("band", com.macdmr.radio.ham.Logbook.band(e.freqHz)).put("mode", e.mode)
                            .put("name", e.name).put("notes", e.notes).put("rstSent", e.rstSent).put("rstRcvd", e.rstRcvd).put("qso", e.qso).put("source", e.source).put("grid", e.grid).put("qth", e.qth));
                return ok(new JSONObject().put("entries", a).put("qsos", lb.count(true)).put("total", lb.count(false)));
            }
            case "/api/pota": {                                                 // GET: your activation + the spots; POST {action: start|end|log|parks, ...}
                com.macdmr.radio.ham.Pota.Activation act = com.macdmr.radio.ham.Pota.active(svc);
                if (post) {
                    String a0 = b.optString("action");
                    if (a0.equals("start")) {
                        String ref = b.optString("ref", "").trim().toUpperCase(java.util.Locale.US);
                        if (com.macdmr.radio.ham.Pota.programOf(ref) == null) return WebServer.Response.error(400, "Not a POTA (US-1234) or SOTA (W0C/FR-001) reference");
                        String name = com.macdmr.radio.ham.Pota.lookup(ref); com.macdmr.radio.ham.Pota.start(svc, ref, name == null ? "" : name);
                        return ok(name == null ? "Activating " + ref + " (not found in the programme's list)" : "Activating " + ref + ": " + name);
                    }
                    if (a0.equals("end")) { com.macdmr.radio.ham.Pota.end(svc); return ok("Activation ended"); }
                    if (a0.equals("parks")) {
                        android.location.Location me = svc.lastKnownLocation(); if (me == null) return WebServer.Response.error(409, "No position fix on the phone");
                        JSONArray pa = new JSONArray(); for (com.macdmr.radio.ham.Pota.Park p0 : com.macdmr.radio.ham.Pota.parksNear(me.getLatitude(), me.getLongitude())) { if (pa.length() >= 25) break; pa.put(new JSONObject().put("ref", p0.ref).put("name", p0.name).put("km", Math.round(p0.km * 10) / 10.0)); }
                        return ok(new JSONObject().put("parks", pa));
                    }
                    if (a0.equals("log")) {
                        String call = b.optString("call", "").trim().toUpperCase(java.util.Locale.US); if (com.macdmr.radio.ham.Logbook.baseCall(call) == null) return WebServer.Response.error(400, "Enter a callsign");
                        ChannelStore.Channel cur = svc.current(); long f = b.optLong("freqHz", cur != null ? cur.rxFreq : 0);
                        if (act != null && !b.optBoolean("force") && svc.logbook().isDupe(call, f, System.currentTimeMillis(), act.ref)) return WebServer.Response.error(409, "DUPE: " + call + " is already logged on this band today under " + act.ref);
                        com.macdmr.radio.ham.Logbook.Entry e = new com.macdmr.radio.ham.Logbook.Entry(); e.ts = System.currentTimeMillis(); e.call = call; e.freqHz = f; e.mode = "FM"; e.qso = true;
                        e.rstSent = b.optString("sent", "59"); e.rstRcvd = b.optString("rcvd", "59"); e.notes = b.optString("notes", ""); e.source = act != null ? act.program + " activation" : "POTA/SOTA hunting";
                        String tr = b.optString("theirRef", "").trim().toUpperCase(java.util.Locale.US); if (com.macdmr.radio.ham.Pota.programOf(tr) != null) { e.sig = com.macdmr.radio.ham.Pota.programOf(tr); e.sigInfo = tr; }
                        if (act != null) { e.mySig = act.program; e.mySigInfo = act.ref; }
                        svc.logbook().save(e);
                        return ok(call + " logged");
                    }
                    return WebServer.Response.error(400, "Unknown action");
                }
                JSONObject o = new JSONObject();
                if (act != null) { int today = svc.logbook().activation(act.ref, com.macdmr.radio.ham.Pota.utcDay(System.currentTimeMillis())).size(); o.put("activation", new JSONObject().put("program", act.program).put("ref", act.ref).put("name", act.name).put("since", act.since).put("today", today).put("needed", com.macdmr.radio.ham.Pota.needed(act.program)).put("total", svc.logbook().activation(act.ref, null).size())); }
                java.util.List<String> probs = new java.util.ArrayList<>(); JSONArray sp = new JSONArray();
                for (com.macdmr.radio.ham.Pota.Spot s0 : com.macdmr.radio.ham.Pota.spots(probs)) { if (sp.length() >= 60) break; sp.put(new JSONObject().put("program", s0.program).put("call", s0.call).put("freqHz", s0.freqHz).put("mode", s0.mode).put("ref", s0.ref).put("name", s0.name).put("where", s0.where).put("time", s0.time).put("workable", s0.workable())); }
                JSONArray pr = new JSONArray(); for (String x : probs) pr.put(x);
                return ok(o.put("spots", sp).put("problems", pr));
            }
            case "/api/pota/adif": {                                            // the activation's ADIF (POTA's file name)
                com.macdmr.radio.ham.Pota.Activation act = com.macdmr.radio.ham.Pota.active(svc); if (act == null) return WebServer.Response.error(409, "No activation");
                String me = svc.packet() == null ? "" : svc.packet().callsign();
                String adif = com.macdmr.radio.ham.Logbook.adif(svc.logbook().activation(act.ref, null), com.macdmr.radio.BuildConfig.VERSION_NAME, me);
                WebServer.Response resp = WebServer.Response.bytes("application/octet-stream", adif.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                resp.headers.put("Content-Disposition", "attachment; filename=\"" + (me.isEmpty() ? "MYCALL" : me) + "@" + act.ref.replace('/', '_') + "-" + com.macdmr.radio.ham.Pota.utcDay(System.currentTimeMillis()) + ".adi\"");
                return resp;
            }
            case "/api/forms": {                                                // GET: the forms log and the field lists; POST {action: send|delete, ...}
                if (post) {
                    String a0 = b.optString("action");
                    if (a0.equals("delete")) { svc.forms().delete(b.optLong("id")); return ok("Deleted"); }
                    com.macdmr.radio.ham.Forms.Form f = new com.macdmr.radio.ham.Forms.Form(); f.kind = com.macdmr.radio.ham.Forms.RADIOGRAM.equals(b.optString("kind")) ? com.macdmr.radio.ham.Forms.RADIOGRAM : com.macdmr.radio.ham.Forms.ICS213;
                    JSONObject fv = b.optJSONObject("fields"); if (fv != null) for (String[] k : com.macdmr.radio.ham.Forms.fields(f.kind)) f.f.put(k[0], fv.optString(k[0], "").trim());
                    if (com.macdmr.radio.ham.Forms.RADIOGRAM.equals(f.kind)) f.f.put("CK", String.valueOf(com.macdmr.radio.ham.Forms.words(f.f.get("MS"))));
                    String via = b.optString("via", "save"); ChannelStore.Contact to = null;
                    if (via.equals("dmr")) { long cid = b.optLong("contact", -1); for (ChannelStore.Contact ct : svc.store().contacts()) if (ct.id == cid) to = ct; }
                    if (via.equals("winlink")) { String e0 = svc.sendForm(f, "winlink", b.optString("to", ""), null); return e0 == null ? ok("In the Winlink outbox") : WebServer.Response.error(409, e0); }
                    final ChannelStore.Contact toF = to; final String via0 = via;
                    String e = onMain(() -> svc.sendForm(f, via0, b.optString("to", ""), toF));
                    return e == null ? ok(via.equals("save") ? "Saved" : "Sending") : WebServer.Response.error(409, e);
                }
                JSONArray rows = new JSONArray();
                for (com.macdmr.radio.ham.FormStore.Row r0 : svc.forms().list(100)) { JSONObject fo = new JSONObject(); for (java.util.Map.Entry<String, String> e : r0.form.f.entrySet()) fo.put(e.getKey(), e.getValue()); rows.put(new JSONObject().put("id", r0.id).put("ts", r0.ts).put("outgoing", r0.outgoing).put("peer", r0.peer).put("via", r0.via).put("status", r0.status).put("kind", r0.form.kind).put("title", com.macdmr.radio.ham.Forms.title(r0.form.kind)).put("fields", fo).put("text", com.macdmr.radio.ham.Forms.text(r0.form))); }
                JSONObject kinds = new JSONObject();
                for (String kd : new String[] { com.macdmr.radio.ham.Forms.ICS213, com.macdmr.radio.ham.Forms.RADIOGRAM }) { JSONArray fl = new JSONArray(); for (String[] k : com.macdmr.radio.ham.Forms.fields(kd)) fl.put(new JSONArray().put(k[0]).put(k[1])); kinds.put(kd, fl); }
                JSONArray cs = new JSONArray(); for (ChannelStore.Contact ct : svc.store().contacts()) cs.put(new JSONObject().put("id", ct.id).put("name", ct.name).put("number", ct.number).put("type", ct.type));
                return ok(new JSONObject().put("forms", rows).put("kinds", kinds).put("contacts", cs));
            }
            case "/api/winlink": {                                              // GET: mailbox, state, log; POST {action: compose|connect|cancel|delete|read|settings}
                com.macdmr.radio.winlink.Winlink w = svc.winlink(); com.macdmr.radio.winlink.WinlinkStore st = w.store();
                if (post) {
                    switch (b.optString("action")) {
                        case "compose": { String e = w.compose(b.optString("to"), b.optString("cc"), b.optString("subject"), b.optString("text")); return e == null ? ok("In the outbox") : WebServer.Response.error(400, e); }
                        case "connect": {
                            String e = "radio".equals(b.optString("how")) ? onMain(() -> w.connectRadio(b.optString("gateway"), b.optString("via"))) : w.connectInternet();
                            return e == null ? ok("Connecting") : WebServer.Response.error(409, e);
                        }
                        case "cancel": w.cancel(); return ok("Stopping");
                        case "delete": st.delete(b.optString("folder"), b.optString("mid")); return ok("Deleted");
                        case "read": st.markRead(b.optString("mid")); return ok("ok");
                        case "gateways": { try { return ok(w.importGateways(b.optString("csv"))); } catch (IllegalArgumentException e) { return WebServer.Response.error(400, e.getMessage()); } }
                        case "cleargateways": w.clearGateways(); return ok("Removed");
                        case "tuneconnect": {
                            String call = b.optString("call"); int hz = b.optInt("hz");
                            if (!com.macdmr.radio.DeviceProfile.inBands(hz)) return WebServer.Response.error(400, "Bad frequency");
                            onMain(() -> { ChannelStore.Channel ch = new ChannelStore.Channel(); ch.type = 1; ch.rxFreq = ch.txFreq = hz; ch.band = 1; ch.power = 1; ch.sq = 2; ch.name = call; svc.applyVfo(ch, true); w.setGateway(call, ""); return null; });
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> w.connectRadio(call, ""), 4500);
                            return ok("Tuning " + String.format(java.util.Locale.US, "%.4f MHz", hz / 1e6) + ", connecting in a few seconds");
                        }
                        case "settings": {
                            String g = b.optString("grid", "").trim();
                            if (!g.isEmpty() && !g.matches("(?i)[A-R]{2}[0-9]{2}([A-X]{2})?")) return WebServer.Response.error(400, "Grid square like JO01 or JO01ab");
                            if (b.has("password") && !b.optString("password").isEmpty()) w.setPassword(b.optString("password"));   // write-only
                            if (b.has("diag")) w.setDiag(b.optBoolean("diag")); if (b.has("diagContents")) w.setDiagContents(b.optBoolean("diagContents"));
                            w.setGrid(g); return ok("Saved");
                        }
                        case "diagasked": w.setDiagAsked(); if (b.has("diag")) w.setDiag(b.optBoolean("diag")); return ok("ok");
                        default: return WebServer.Response.error(400, "Unknown action");
                    }
                }
                JSONObject fo = new JSONObject();
                for (String folder : new String[] { com.macdmr.radio.winlink.WinlinkStore.INBOX, com.macdmr.radio.winlink.WinlinkStore.OUTBOX, com.macdmr.radio.winlink.WinlinkStore.SENT }) {
                    JSONArray arr = new JSONArray();
                    for (com.macdmr.radio.winlink.WinlinkStore.Item it : st.list(folder)) {
                        com.macdmr.radio.winlink.B2Message m = it.m; JSONArray to = new JSONArray(), cc = new JSONArray(), files = new JSONArray();
                        for (String a : m.to) to.put(com.macdmr.radio.winlink.B2Message.shown(a)); for (String a : m.cc) cc.put(com.macdmr.radio.winlink.B2Message.shown(a));
                        for (com.macdmr.radio.winlink.B2Message.Attachment at : m.files) files.put(new JSONObject().put("name", at.name).put("size", at.data.length));
                        arr.put(new JSONObject().put("mid", m.mid).put("subject", m.subject).put("from", com.macdmr.radio.winlink.B2Message.shown(m.from)).put("to", to).put("cc", cc)
                                .put("time", m.time()).put("unread", folder.equals(com.macdmr.radio.winlink.WinlinkStore.INBOX) && !st.isRead(m.mid)).put("text", m.bodyText().replace("\r\n", "\n")).put("files", files));
                    }
                    fo.put(folder, arr);
                }
                JSONArray gws = new JSONArray(); int gn = 0;
                for (com.macdmr.radio.winlink.RmsList.Gateway g : w.gateways()) { if (++gn > 40) break; gws.put(g.json().put("km", g.km < 0 ? JSONObject.NULL : (Object) Math.round(g.km)).put("dir", g.km < 0 ? "" : com.macdmr.radio.ham.Maidenhead.compass(g.bearing))); }
                return ok(new JSONObject().put("diag", w.diagEnabled()).put("diagContents", w.diagContents()).put("diagAsked", w.diagAsked()).put("lastWasDiag", w.lastWasDiag() && w.lastSession() > 0)
                        .put("gateways", gws).put("gatewayCount", w.gateways().size()).put("state", w.state()).put("busy", w.busy()).put("progress", w.progress()).put("passwordSet", w.passwordSet()).put("grid", w.grid())
                        .put("gateway", w.gateway()).put("via", w.via()).put("log", w.logText()).put("folders", fo));
            }
            case "/api/finder": {                                               // GET: state, settings, stations; POST {action: start|stop|clear|settings}
                com.macdmr.radio.PacketFinder pf = svc.finder();
                if (post) {
                    switch (b.optString("action")) {
                        case "start": { String e = onMain(() -> pf.start(0)); return e == null ? ok("Listening") : WebServer.Response.error(409, e); }
                        case "stop": pf.stop(); return ok("Stopping");
                        case "clear": pf.clear(); return ok("Cleared");
                        case "settings": { String e = pf.setSettings(b.optString("freqs", ""), b.optInt("dwell", 3), b.optInt("hours", 2)); return e == null ? ok("Saved") : WebServer.Response.error(400, e); }
                        default: return WebServer.Response.error(400, "Unknown action");
                    }
                }
                return ok(pf.json());
            }
            case "/api/winlink/log": {                                           // GET: the session log as a text file
                WebServer.Response x = WebServer.Response.bytes("text/plain; charset=utf-8", svc.winlink().logText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                x.headers.put("Content-Disposition", "attachment; filename=\"signaldeck_winlink_log.txt\""); x.headers.put("Cache-Control", "no-store"); return x;
            }
            case "/api/winlink/file": {                                         // GET ?folder=&mid=&i=: an attachment
                com.macdmr.radio.winlink.WinlinkStore st = svc.winlink().store(); String folder = r.param("folder"), mid = r.param("mid");
                for (com.macdmr.radio.winlink.WinlinkStore.Item it : st.list(folder == null ? "" : folder)) if (it.m.mid.equals(mid)) {
                    int i = parse(r.param("i"), -1); if (i < 0 || i >= it.m.files.size()) break;
                    com.macdmr.radio.winlink.B2Message.Attachment at = it.m.files.get(i);
                    String ext = at.name.contains(".") ? at.name.substring(at.name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.US) : "";
                    String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext); if (type == null) type = "application/octet-stream";
                    WebServer.Response x = WebServer.Response.bytes(type, at.data);
                    x.headers.put("Content-Disposition", ("1".equals(r.param("download")) ? "attachment" : "inline") + "; filename=\"" + at.name.replaceAll("[\"\\\\\r\n]", "_") + "\"");
                    x.headers.put("Cache-Control", "no-store"); return x;
                }
                return WebServer.Response.error(404, "No such attachment");
            }
            case "/api/logbook/delete": if (!post) break; { svc.logbook().delete(b.optLong("id", 0)); return ok("Deleted"); }
            case "/api/settings": {
                if (post) {
                    String key = b.optString("key", "");
                    for (Setting s : settings()) if (s.key.equals(key)) {
                        try { onMain(() -> { s.set.set(b); return null; }); }
                        catch (java.util.concurrent.ExecutionException e) { return WebServer.Response.error(400, e.getCause() == null ? String.valueOf(e) : String.valueOf(e.getCause().getMessage())); }
                        return ok(settingsJson().put("message", s.label + " saved"));
                    }
                    return WebServer.Response.error(404, "No such setting");
                }
                return ok(settingsJson());
            }
            case "/api/radio": if (!post) break; {                              // take over / hand back the radio
                String a = b.optString("action", "");
                if (svc.firmwareLoading()) return WebServer.Response.error(409, "Radio busy, loading firmware");
                if (svc.isTransmitting()) return WebServer.Response.error(409, "Not while transmitting");
                if (a.equals("takeover")) { svc.bringUp(); return ok("Taking over the radio"); }
                if (a.equals("handback")) { svc.shutDown(true); return ok("Handing the radio back to the stock app"); }
                return WebServer.Response.error(400, "action: takeover or handback");
            }
            case "/api/packet": {                                               // packet settings + tool states + DTMF log + weather alerts
                com.macdmr.radio.PacketEngine pe = svc.packet(); if (pe == null) return WebServer.Response.error(409, "Packet engine not ready");
                android.content.SharedPreferences pp = svc.getSharedPreferences("packet", Context.MODE_PRIVATE);
                JSONObject st = new JSONObject()
                        .put("callsignSet", pe.callsignSet()).put("ssid", pe.ssid()).put("comment", pe.comment()).put("path", pp.getString("path", "WIDE1-1,WIDE2-1"))
                        .put("symbol", pp.getString("symTable", "/") + pp.getString("symCode", "[")).put("autoBeacon", pe.autoBeacon()).put("beaconMin", pe.beaconMin())
                        .put("txPower", pe.dataTxPower()).put("leadKind", pe.packetLeadKind()).put("leadMs", pe.packetLeadMs()).put("passive", pe.passiveMode())
                        .put("autoAck", pp.getBoolean("autoAck", true)).put("digipeat", pp.getBoolean("digipeat", false)).put("dtmf", pp.getBoolean("dtmf", true))
                        .put("wx", pp.getBoolean("wx", true)).put("cw", pp.getBoolean("cw", true)).put("cwId", pp.getBoolean("cwId", false))
                        .put("telemetry", pp.getBoolean("telemetry", false)).put("weather", pp.getBoolean("weather", false)).put("txIgate", pp.getBoolean("txIgate", false))
                        .put("groups", pp.getString("groups", "")).put("kissPort", pp.getInt("kissPort", 8001))
                        .put("isServer", pe.isServer()).put("isPort", pe.isPort()).put("isPasscodeSet", !"-1".equals(pp.getString("isPasscode", "-1")))
                        .put("isBeaconMin", pe.isBeaconMin()).put("isComment", pp.getString("isComment", pe.comment()));
                JSONArray lk = new JSONArray(); for (String k : com.macdmr.radio.PacketEngine.LEAD_KINDS) lk.put(k);
                JSONArray lc = new JSONArray(); for (int ms : com.macdmr.radio.PacketEngine.LEAD_CHOICES) lc.put(ms);
                JSONArray dt = new JSONArray();
                for (PacketStore.Dtmf d : pe.store().dtmfLog(50)) dt.put(new JSONObject().put("ts", d.ts).put("digits", d.digits).put("channel", d.channel).put("channelName", d.channelName));
                JSONArray al = new JSONArray();
                for (PacketStore.Alert x : pe.store().alerts(50)) al.put(new JSONObject().put("ts", x.ts).put("event", x.event).put("summary", x.summary).put("eom", x.eom));
                JSONObject tools = new JSONObject().put("is", pe.isEnabled()).put("isStatus", pe.isStatus()).put("kiss", pe.kiss().isActive()).put("kissClient", pe.kiss().clientConnected())
                        .put("parrot", svc.parrot().enabled()).put("fox", svc.foxTxOn()).put("webTx", allowTx());
                return ok(new JSONObject().put("settings", st).put("leadKinds", lk).put("leadChoices", lc).put("tools", tools).put("dtmf", dt).put("alerts", al)
                        .put("dtmfSaved", new JSONArray(pp.getString("dtmfSaved", "[]"))));
            }
            case "/api/packet/settings": if (!post) break; {                   // the APRS settings sheet's checks (PktDialog)
                android.content.SharedPreferences pp = svc.getSharedPreferences("packet", Context.MODE_PRIVATE);
                android.content.SharedPreferences.Editor ed = pp.edit();
                try {
                    if (b.has("callsign") && !b.getString("callsign").trim().isEmpty()) {         // write-only: absent or empty keeps the stored one
                        String cs = b.getString("callsign").trim().toUpperCase(java.util.Locale.US);
                        int dash = cs.indexOf('-'); if (dash > 0) { b.put("ssid", Integer.parseInt(cs.substring(dash + 1).trim())); cs = cs.substring(0, dash).trim(); }
                        if (!cs.matches("[A-Z0-9]{1,6}")) return WebServer.Response.error(400, "Callsign: 1 to 6 letters and digits; the SSID goes in its own box");
                        ed.putString("callsign", cs);
                    }
                    if (b.has("ssid")) { int v = b.getInt("ssid"); if (v < 0 || v > 15) return WebServer.Response.error(400, "SSID is 0 to 15"); ed.putInt("ssid", v); }
                    if (b.has("symbol")) { String s = b.getString("symbol").trim(); ed.putString("symTable", s.length() >= 1 ? s.substring(0, 1) : "/").putString("symCode", s.length() >= 2 ? s.substring(1, 2) : "["); }
                    if (b.has("comment")) ed.putString("comment", b.getString("comment").trim());
                    if (b.has("path")) ed.putString("path", b.getString("path").trim().toUpperCase(java.util.Locale.US));
                    if (b.has("groups")) ed.putString("groups", b.getString("groups").trim().toUpperCase(java.util.Locale.US));
                    if (b.has("autoBeacon")) ed.putInt("autoBeacon", Math.max(0, Math.min(2, b.getInt("autoBeacon"))));
                    if (b.has("beaconMin")) ed.putInt("beaconMin", Math.max(1, b.getInt("beaconMin")));
                    if (b.has("txPower")) ed.putInt("txPower", b.getInt("txPower") == 0 ? 0 : 1);
                    if (b.has("leadKind")) ed.putInt("txLeadKind", Math.max(0, Math.min(com.macdmr.radio.PacketEngine.LEAD_KINDS.length - 1, b.getInt("leadKind"))));
                    if (b.has("leadMs")) { int ms = b.getInt("leadMs"); boolean okMs = false; for (int c : com.macdmr.radio.PacketEngine.LEAD_CHOICES) okMs |= c == ms; if (!okMs) return WebServer.Response.error(400, "Lead-in length must be one of the listed choices"); ed.putInt("txLeadMs", ms); }
                    if (b.has("passive")) ed.putInt("passive", Math.max(0, Math.min(2, b.getInt("passive"))));
                    for (String k : new String[] { "autoAck", "digipeat", "dtmf", "wx", "cw", "cwId", "telemetry", "weather", "txIgate" }) if (b.has(k)) ed.putBoolean(k, b.getBoolean(k));
                    if (b.has("kissPort")) { int v = b.getInt("kissPort"); if (v < 1024 || v > 65535) return WebServer.Response.error(400, "KISS port is 1024 to 65535"); ed.putInt("kissPort", v); }
                    if (b.has("isServer")) ed.putString("isServer", b.getString("isServer").trim());
                    if (b.has("isPort")) ed.putInt("isPort", b.getInt("isPort"));
                    if (b.has("isPasscode") && !b.getString("isPasscode").trim().isEmpty()) ed.putString("isPasscode", b.getString("isPasscode").trim());   // write-only
                    if (b.has("isBeaconMin")) ed.putInt("isBeaconMin", Math.max(0, b.getInt("isBeaconMin")));
                    if (b.has("isComment")) ed.putString("isComment", b.getString("isComment").trim());
                } catch (Exception e) { return WebServer.Response.error(400, "Check the numbers"); }
                ed.apply();
                onMain(() -> { svc.packet().applySettings(); return null; });
                return ok("APRS settings saved");
            }
            case "/api/packet/tool": if (!post) break; {                       // {tool: is|kiss|parrot, on}
                com.macdmr.radio.PacketEngine pe = svc.packet(); String tool = b.optString("tool", ""); boolean on = b.optBoolean("on", false);
                if (on && (tool.equals("parrot") || tool.equals("fox")) && !allowTx()) return WebServer.Response.error(403, TX_OFF);     // both transmit
                String msg = onMain(() -> {
                    switch (tool) {
                        case "is": if (on) { String e = pe.isStart(); return e == null ? "APRS-IS starting" : e; } pe.isStop(); return "APRS-IS off";
                        case "kiss": if (on) { String e = pe.kissStart(); return e == null ? "KISS TNC on" : e; } pe.kissStop(); return "KISS TNC off";
                        case "parrot": return svc.setParrot(on);
                        case "fox": return svc.setFoxTx(on);
                        default: return null;
                    }
                });
                return msg == null ? WebServer.Response.error(400, "tool: is, kiss, parrot or fox") : ok(msg);
            }
            case "/api/dtmf": if (!post) break; {
                if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                String seq = b.optString("seq", "").trim().toUpperCase(java.util.Locale.US);
                if (!seq.matches("[0-9A-D*#]{1,32}")) return WebServer.Response.error(400, "DTMF: 1 to 32 of 0-9, A-D, * and #");
                String e = onMain(() -> svc.packet().sendDtmf(seq));
                return e == null ? ok("Sending DTMF " + seq) : WebServer.Response.error(409, e);
            }
            case "/api/cwid": if (!post) break; {
                if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                String e = onMain(() -> svc.packet().sendCwId());
                return e == null ? ok("Sending the Morse ID") : WebServer.Response.error(409, e);
            }
            case "/api/sstv/send": if (!post) break; {                          // body: the picture's bytes (PNG/JPEG); ?mode=0..2
                if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                int[] modes = com.macdmr.radio.pkt.SSTVEncoder.MODES;                 // the page's select lists them in this order
                int m = parse(r.param("mode"), 2); if (m < 0 || m >= modes.length) return WebServer.Response.error(400, "mode: 0 Robot 8 BW, 1 Robot 12 BW, 2 Robot 24 Color, 3 Robot 36, 4 Martin M2, 5 Scottie S2, 6 PD 90, 7 Scottie S1, 8 Martin M1");
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(r.body, 0, r.body.length);
                if (bmp == null) return WebServer.Response.error(400, "That is not a picture this phone can read (use PNG or JPEG)");
                String e = onMain(() -> svc.packet().sendSstv(bmp, modes[m]));
                return e == null ? ok("Sending the picture as " + com.macdmr.radio.pkt.SSTVEncoder.name(modes[m])) : WebServer.Response.error(409, e);
            }
            case "/api/trainer": {                                              // the Morse trainer's lesson and speeds (shared with the phone's trainer)
                android.content.SharedPreferences tp = svc.getSharedPreferences("trainer", Context.MODE_PRIVATE);
                if (post) {
                    android.content.SharedPreferences.Editor ed = tp.edit();
                    if (b.has("lesson")) ed.putInt("lesson", Math.max(1, Math.min(com.macdmr.radio.ham.MorseTrainer.LESSONS, b.getInt("lesson"))));
                    if (b.has("wpm")) ed.putInt("wpm", Math.max(5, Math.min(40, b.getInt("wpm"))));
                    if (b.has("fw")) ed.putInt("fw", Math.max(5, Math.min(40, b.getInt("fw"))));
                    ed.apply();
                }
                return ok(new JSONObject().put("lesson", tp.getInt("lesson", 1)).put("wpm", tp.getInt("wpm", 20)).put("fw", tp.getInt("fw", 10))
                        .put("koch", com.macdmr.radio.ham.MorseTrainer.KOCH).put("lessons", com.macdmr.radio.ham.MorseTrainer.LESSONS));
            }
            case "/api/exam": {                                                 // the pools and this phone's progress (shared with the phone's Exam practice)
                android.content.SharedPreferences ep = svc.getSharedPreferences("exam", Context.MODE_PRIVATE);
                JSONArray a = new JSONArray();
                for (com.macdmr.radio.ham.ExamPool.Element e : com.macdmr.radio.ham.ExamPool.ELEMENTS)
                    a.put(new JSONObject().put("key", e.key).put("name", e.name).put("asset", e.asset).put("questions", e.questions).put("pass", e.pass)
                            .put("best", ep.getInt("best_" + e.key, -1)).put("missed", new JSONArray(ep.getStringSet("missed_" + e.key, new java.util.HashSet<>()))));
                return ok(new JSONObject().put("elements", a));
            }
            case "/api/exam/answer": if (!post) break; {                       // {element, id, right}: keeps the missed list like the phone
                String key = b.optString("element", ""), id = b.optString("id", "");
                if (!key.matches("tech|general|extra") || !id.matches("[A-Z][0-9][A-Z][0-9]{2}")) return WebServer.Response.error(400, "element and id");
                android.content.SharedPreferences ep = svc.getSharedPreferences("exam", Context.MODE_PRIVATE);
                java.util.Set<String> miss = new java.util.HashSet<>(ep.getStringSet("missed_" + key, new java.util.HashSet<>()));
                if (b.optBoolean("right", false)) miss.remove(id); else miss.add(id);
                ep.edit().putStringSet("missed_" + key, miss).apply();
                return ok(new JSONObject().put("missed", miss.size()));
            }
            case "/api/exam/result": if (!post) break; {                       // {element, right}: a full practice exam's score
                String key = b.optString("element", ""); int right = b.optInt("right", -1);
                if (!key.matches("tech|general|extra") || right < 0) return WebServer.Response.error(400, "element and right");
                android.content.SharedPreferences ep = svc.getSharedPreferences("exam", Context.MODE_PRIVATE);
                boolean best = right > ep.getInt("best_" + key, -1); if (best) ep.edit().putInt("best_" + key, right).apply();
                return ok(new JSONObject().put("best", best));
            }
            case "/api/nets": {                                                 // the running net (check-ins, stations heard lately) and past nets
                com.macdmr.radio.ham.NetStore ns = svc.nets();
                JSONObject o = new JSONObject();
                com.macdmr.radio.ham.NetStore.Net n = ns.running();
                if (n != null) {
                    JSONArray ins = new JSONArray(); java.util.Set<String> in = new java.util.HashSet<>();
                    for (com.macdmr.radio.ham.NetStore.CheckIn k : ns.checkIns(n.id)) { in.add(k.call); ins.put(checkInJson(k)); }
                    JSONArray heard = new JSONArray(); java.util.Set<String> seen = new java.util.HashSet<>(); long since = System.currentTimeMillis() - 30 * 60_000L;
                    for (com.macdmr.radio.ham.Logbook.Entry e : svc.logbook().list(80, false))
                        if (e.ts >= since && !in.contains(e.call) && seen.add(e.call)) heard.put(new JSONObject().put("call", e.call).put("name", e.name == null ? "" : e.name));
                    o.put("running", new JSONObject().put("id", n.id).put("name", n.name).put("channel", n.channel).put("freqHz", n.freqHz).put("start", n.start).put("checkIns", ins).put("heard", heard));
                }
                JSONArray past = new JSONArray();
                for (com.macdmr.radio.ham.NetStore.Net p0 : ns.past(30)) past.put(new JSONObject().put("id", p0.id).put("name", p0.name).put("channel", p0.channel).put("start", p0.start).put("end", p0.end).put("count", ns.checkIns(p0.id).size()));
                return ok(o.put("past", past).put("lastName", svc.getSharedPreferences("nets", Context.MODE_PRIVATE).getString("last", "")));
            }
            case "/api/nets/start": if (!post) break; {
                if (svc.nets().running() != null) return WebServer.Response.error(409, "A net is already running");
                String nm = b.optString("name", "").trim(); if (nm.isEmpty()) nm = "Net";
                svc.getSharedPreferences("nets", Context.MODE_PRIVATE).edit().putString("last", nm).apply();
                ChannelStore.Channel cur = svc.current();
                svc.nets().start(nm, cur == null ? "" : cur.name, cur == null ? 0 : cur.rxFreq);
                final String name = nm; onMain(() -> { svc.history("pkt", "📋 Net started: " + name); return null; });
                return ok("Net started: " + nm);
            }
            case "/api/nets/end": if (!post) break; {
                com.macdmr.radio.ham.NetStore.Net n = svc.nets().running(); if (n == null) return WebServer.Response.error(409, "No net is running");
                svc.nets().end(n); int c = svc.nets().checkIns(n.id).size();
                onMain(() -> { svc.history("pkt", "📋 Net ended: " + n.name + " (" + c + " check-ins)"); return null; });
                return ok("Net ended: " + c + " check-ins");
            }
            case "/api/nets/checkin": if (!post) break; {
                com.macdmr.radio.ham.NetStore.Net n = svc.nets().running(); if (n == null) return WebServer.Response.error(409, "No net is running");
                String cs = b.optString("call", "").trim().toUpperCase(java.util.Locale.US), nm = b.optString("name", "").trim();
                if (!cs.matches("[A-Z0-9/]{3,12}")) return WebServer.Response.error(400, "That does not look like a callsign");
                com.macdmr.radio.ham.NetStore.CheckIn k = svc.nets().checkIn(n, cs, nm);
                if (nm.isEmpty()) svc.enrichCall(cs, i -> { if (k.name == null || k.name.isEmpty()) { k.name = i.name; svc.nets().save(k); pub("net", "{}"); } });   // the name from the callsign lookup
                return ok("Checked in " + cs);
            }
            case "/api/nets/update": if (!post) break; {                        // {id, traffic?, late?, out?, notes?, remove?}
                com.macdmr.radio.ham.NetStore.Net n = svc.nets().running(); if (n == null) return WebServer.Response.error(409, "No net is running");
                long id = b.optLong("id", 0);
                for (com.macdmr.radio.ham.NetStore.CheckIn k : svc.nets().checkIns(n.id)) if (k.id == id) {
                    if (b.optBoolean("remove", false)) { svc.nets().remove(k); return ok("Removed " + k.call); }
                    if (b.has("traffic")) k.traffic = b.getBoolean("traffic"); if (b.has("late")) k.late = b.getBoolean("late"); if (b.has("out")) k.out = b.getBoolean("out");
                    if (b.has("notes")) k.notes = b.getString("notes"); if (b.has("name")) k.name = b.getString("name");
                    svc.nets().save(k); return ok(k.call + " updated");
                }
                return WebServer.Response.error(404, "No such check-in");
            }
            case "/api/nets/report": {                                          // the net's report text (the phone's NetStore.report)
                long id = r.param("id") == null ? -1 : Long.parseLong(r.param("id"));
                com.macdmr.radio.ham.NetStore.Net n = null;
                com.macdmr.radio.ham.NetStore.Net run = svc.nets().running(); if (run != null && (id < 0 || run.id == id)) n = run;
                if (n == null) for (com.macdmr.radio.ham.NetStore.Net p0 : svc.nets().past(200)) if (p0.id == id) n = p0;
                if (n == null) return WebServer.Response.error(404, "No such net");
                String text = com.macdmr.radio.ham.NetStore.report(n, svc.nets().checkIns(n.id), svc.packet() != null && svc.packet().callsignSet() ? svc.packet().callsign() : "");
                WebServer.Response x = WebServer.Response.bytes("text/plain; charset=utf-8", text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if ("1".equals(r.param("download"))) x.headers.put("Content-Disposition", "attachment; filename=\"net_report.txt\"");
                return x;
            }
            case "/api/nets/delete": if (!post) break; {
                long id = b.optLong("id", 0); com.macdmr.radio.ham.NetStore.Net run = svc.nets().running();
                if (run != null && run.id == id) return WebServer.Response.error(409, "End the net first");
                svc.nets().deleteNet(id); return ok("Net deleted");
            }
            case "/api/keyer": {                                                // the voice keyer's three messages and the station-ID timer
                com.macdmr.radio.ham.VoiceKeyer k = svc.keyer();
                if (post) {
                    int slot = b.optInt("slot", -1); if (slot < 0 || slot >= com.macdmr.radio.ham.VoiceKeyer.SLOTS) return WebServer.Response.error(400, "slot 0 to 2");
                    if (b.has("name")) k.setName(slot, b.getString("name").trim());
                    if (b.has("addId")) k.setAddId(slot, b.getBoolean("addId"));
                    if (b.optBoolean("delete", false)) k.delete(slot);
                    if (b.optBoolean("send", false)) {
                        if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                        String e = onMain(() -> svc.sendKeyer(slot)); if (e != null) return WebServer.Response.error(409, e);
                    }
                }
                JSONArray a = new JSONArray();
                for (int i = 0; i < com.macdmr.radio.ham.VoiceKeyer.SLOTS; i++) a.put(new JSONObject().put("name", k.name(i)).put("has", k.has(i)).put("ms", k.lengthMs(i)).put("addId", k.addId(i)));
                long due = svc.idDueInMs();
                return ok(new JSONObject().put("slots", a).put("idDueMs", due == Long.MAX_VALUE ? null : due).put("webTx", allowTx()));
            }
            case "/api/keyer/audio": {                                          // GET ?slot= : the message as WAV; POST ?slot= : raw 8 kHz mono s16le body
                int slot = parse(r.param("slot"), -1); if (slot < 0 || slot >= com.macdmr.radio.ham.VoiceKeyer.SLOTS) return WebServer.Response.error(400, "slot 0 to 2");
                if (post) {
                    if (r.body.length < 1600) return WebServer.Response.error(400, "That recording is too short");
                    short[] pcm = new short[r.body.length / 2]; java.nio.ByteBuffer.wrap(r.body).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                    svc.keyer().save(slot, pcm);
                    return ok("Saved " + svc.keyer().name(slot) + String.format(java.util.Locale.US, " (%.1f s)", Math.min(pcm.length, 200_000) / 8000.0));
                }
                short[] p0 = svc.keyer().pcm(slot); if (p0 == null || p0.length == 0) return WebServer.Response.error(404, "Nothing recorded");
                return WebServer.Response.bytes("audio/wav", wav(p0));
            }
            case "/api/ft8": {                                                  // FT8 over FM: GET state + decodes; POST {on} | {cq} | {reply:{slot,text}} | {halt} | {send} | {parity} | {txHz} | {grid} | {clear}
                final com.macdmr.radio.ham.Ft8Station f8 = svc.ft8();
                if (post) {
                    boolean tx = b.optBoolean("cq", false) || b.has("reply") || b.has("send");
                    if (tx && !allowTx()) return WebServer.Response.error(403, TX_OFF);
                    String e = onMain(() -> {
                        if (b.has("on")) svc.setFt8(b.getBoolean("on"));
                        if (b.has("parity")) f8.setTxParity(b.getInt("parity"));
                        if (b.has("txHz")) f8.setTxHz(b.getDouble("txHz"));
                        if (b.has("grid")) {
                            String g = b.getString("grid").trim().toUpperCase(java.util.Locale.US);
                            if (!g.isEmpty() && !g.equals("-") && !g.matches("[A-R]{2}[0-9]{2}([A-X]{2})?")) return "A grid is two letters A-R and two digits, like FN42";
                            svc.setFt8Grid(g);
                        }
                        if (b.optBoolean("clear", false)) f8.clear();
                        if (b.optBoolean("halt", false)) f8.haltTx();
                        if (b.optBoolean("cq", false)) return f8.callCq();
                        if (b.has("send")) return f8.sendText(b.getString("send"));
                        if (b.has("reply")) {
                            JSONObject rp = b.getJSONObject("reply");
                            com.macdmr.radio.ham.Ft8Station.Heard h = f8.find(rp.getLong("slot"), rp.getString("text"));
                            return h == null ? "That decode is no longer in the list" : f8.reply(h);
                        }
                        return null;
                    });
                    if (e != null) return WebServer.Response.error(409, e);
                }
                String my = svc.packet() != null && svc.packet().callsignSet() ? svc.packet().callsign() : null;
                JSONArray heard = new JSONArray();
                java.util.List<com.macdmr.radio.ham.Ft8Station.Heard> hl = f8.heard();
                for (int i = Math.max(0, hl.size() - 80); i < hl.size(); i++) {
                    com.macdmr.radio.ham.Ft8Station.Heard h = hl.get(i);
                    heard.put(new JSONObject().put("slot", h.slot).put("time", h.time()).put("snr", h.d.snrDb).put("dt", Math.round(h.d.dtS * 10) / 10.0).put("hz", Math.round(h.d.freqHz))
                        .put("text", h.d.text).put("shown", hideCall(h.d.text, my)).put("cq", h.cq).put("toMe", h.toMe).put("from", h.from == null ? JSONObject.NULL : h.from));
                }
                JSONArray sent = new JSONArray(); for (String s0 : f8.sent()) sent.put(hideCall(s0, my));
                String next = f8.nextMessage();
                return ok(new JSONObject().put("running", f8.running()).put("status", hideCall(f8.status(), my)).put("tx", f8.txEnabled()).put("next", next == null ? JSONObject.NULL : hideCall(next, my))
                    .put("partner", f8.partner() == null ? JSONObject.NULL : f8.partner()).put("parity", f8.txParity()).put("txHz", Math.round(f8.txHz())).put("grid", svc.ft8Grid()).put("gridSetting", svc.ft8GridSetting())
                    .put("now", System.currentTimeMillis()).put("heard", heard).put("sent", sent).put("analog", svc.current() != null && !svc.current().isDigital())
                    .put("callsignSet", my != null).put("webTx", allowTx()));
            }
            case "/api/m17": {                                                  // M17 receive: GET state + what was heard; POST {on}
                final com.macdmr.radio.M17Station m7 = svc.m17();
                if (post && b.has("on")) { String e = onMain(() -> svc.setM17(b.getBoolean("on"))); if (e != null) return WebServer.Response.error(409, e); }
                JSONArray hl = new JSONArray(); java.util.List<com.macdmr.radio.M17Station.Heard> hs = m7.heard();
                for (int i = Math.max(0, hs.size() - 50); i < hs.size(); i++) {
                    com.macdmr.radio.M17Station.Heard h = hs.get(i);
                    hl.put(new JSONObject().put("start", h.start).put("end", h.end).put("src", h.src).put("dst", h.dst).put("meta", h.meta == null ? JSONObject.NULL : h.meta)
                        .put("frames", h.frames).put("good", h.good).put("encrypted", h.encrypted).put("data", h.data));
                }
                com.macdmr.radio.M17Station.Heard cur = m7.current();
                String mine = svc.packet() != null && svc.packet().callsignSet() ? svc.packet().callsign() : null;
                JSONArray texts = new JSONArray();
                for (com.macdmr.radio.M17Station.Text t : m7.texts())
                    texts.put(new JSONObject().put("ts", t.ts).put("sent", t.sent).put("src", hideCall(t.src, mine)).put("dst", hideCall(t.dst, mine)).put("body", hideCall(t.body, mine)));
                return ok(new JSONObject().put("running", m7.running()).put("receiving", cur != null).put("quality", Math.round(m7.quality() * 100)).put("texts", texts).put("webTx", allowTx()).put("canSend", svc.m17CanSend())
                    .put("current", cur == null ? JSONObject.NULL : new JSONObject().put("src", cur.src).put("dst", cur.dst).put("meta", cur.meta == null ? JSONObject.NULL : cur.meta).put("frames", cur.frames).put("seconds", (cur.end - cur.start) / 1000.0).put("encrypted", cur.encrypted))
                    .put("heard", hl).put("calibration", m7.calibration()).put("analog", svc.current() != null && !svc.current().isDigital()));
            }
            case "/api/ft8/spectrum": {                                         // the FT8 waterfall's next line: 200 levels in dB, 200-2800 Hz
                com.macdmr.radio.ham.Ft8Station f8 = svc.ft8();
                JSONArray a = new JSONArray();
                if (f8.running()) for (float v : f8.spectrum(200, 200, 2800)) a.put(Math.round(v * 10) / 10.0);
                return ok(new JSONObject().put("db", a));
            }
            case "/api/textmodes": {                                            // RTTY / PSK31: GET state + text; POST {mode?, txMode?, clear?, send?}
                com.macdmr.radio.PacketEngine pe = svc.packet();
                if (post) {
                    if (b.has("send")) {
                        if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                        String e = onMain(() -> pe.textSend(b.getString("send")));
                        if (e != null) return WebServer.Response.error(409, e);
                    }
                    onMain(() -> {
                        if (b.has("mode")) pe.setTextMode(Math.max(0, Math.min(2, b.getInt("mode"))));
                        if (b.has("txMode")) pe.setTextTxMode(b.getInt("txMode") == 1 ? 1 : 2);
                        if (b.optBoolean("clear", false)) pe.textClear();
                        return null;
                    });
                }
                JSONArray modes = new JSONArray(); for (String m : com.macdmr.radio.PacketEngine.TEXT_MODES) modes.put(m);
                return ok(new JSONObject().put("mode", pe.textMode()).put("txMode", pe.textTxMode()).put("status", pe.textStatus()).put("text", pe.textReceived()).put("modes", modes).put("webTx", allowTx()));
            }
            case "/api/terminal": {                                             // AX.25 connected-mode terminal: GET transcript; POST {connect, via} | {send} | {disconnect} | {accept}
                com.macdmr.radio.PacketEngine pe = svc.packet();
                if (post) {
                    boolean tx = b.has("connect") || b.has("send") || (b.has("accept") && b.getBoolean("accept"));
                    if (tx && !allowTx()) return WebServer.Response.error(403, TX_OFF);
                    String e = onMain(() -> {
                        if (b.has("connect")) return pe.terminalConnect(b.getString("connect"), b.optString("via", ""));
                        if (b.has("send")) return pe.terminalSend(b.getString("send"));
                        if (b.optBoolean("disconnect", false)) { pe.terminalDisconnect(); return null; }
                        if (b.has("accept")) { pe.setAcceptConnections(b.getBoolean("accept")); return null; }
                        return null;
                    });
                    if (e != null) return WebServer.Response.error(409, e);
                }
                return ok(new JSONObject().put("state", pe.terminalState()).put("transcript", pe.transcriptText()).put("accept", pe.acceptConnections()).put("callsignSet", pe.callsignSet()).put("webTx", allowTx()));
            }
            case "/api/coverage": {                                             // coverage for all repeaters or one: measured squares (rx median signal / tx keyed or not), the repeaters with data, and the terrain prediction
                boolean tx = "tx".equals(r.param("layer"));
                com.macdmr.radio.coverage.CoverageStore st = svc.coverage().store();
                String key = r.param("key"); if (key != null && key.isEmpty()) key = null;
                double fLat = parseD(r.param("lat"), Double.NaN), fLon = parseD(r.param("lon"), Double.NaN), fHz = parseD(r.param("hz"), 0);
                String fName = r.param("name");
                if (key == null && fHz > 0 && !Double.isNaN(fLat)) key = com.macdmr.radio.coverage.CoverageMath.repeaterKey((int) fHz, fLat, fLon);   // a repeater from the map, measured or not
                JSONArray reps = new JSONArray();
                for (com.macdmr.radio.coverage.CoverageStore.Repeater x : st.repeaters()) {
                    reps.put(new JSONObject().put("key", x.key).put("name", x.name).put("rxHz", x.rxHz).put("lat", x.lat).put("lon", x.lon).put("rx", x.rxCount).put("tx", x.txCount));
                    if (x.key.equals(key)) { if (Double.isNaN(fLat)) { fLat = x.lat; fLon = x.lon; } if (fHz <= 0) fHz = x.rxHz; if (fName == null || fName.isEmpty()) fName = x.name; }
                }
                JSONArray a = new JSONArray();
                for (com.macdmr.radio.coverage.CoverageStore.Cell c : tx ? st.txCells(key) : st.rxCells(key)) a.put(cellJson(c, tx, false));
                JSONObject out = new JSONObject().put("cells", a).put("layer", tx ? "tx" : "rx").put("logging", svc.coverage().enabled()).put("repeaters", reps).put("key", key == null ? "" : key);
                if (key != null && "1".equals(r.param("predict")) && !Double.isNaN(fLat) && fHz > 0) {
                    PredJob j = prediction(key, fLat, fLon, fHz);
                    out.put("predictStatus", j.status).put("predictDone", j.done);
                    if (j.err != null) out.put("predictError", j.err);
                    if (j.res != null) {
                        JSONArray pa = new JSONArray(); for (com.macdmr.radio.coverage.CoverageStore.Cell c : tx ? j.res.tx : j.res.rx) pa.put(cellJson(c, tx, true));
                        out.put("predicted", pa).put("predictSummary", j.res.summary(!tx));
                    }
                }
                if (key != null) out.put("name", fName == null ? "" : fName).put("lat", fLat).put("lon", fLon);
                return ok(out);
            }
            case "/api/repeaters": {                                            // nearby repeaters (RadioID / BrandMeister / Hearham, cached by the phone)
                double cLat = parseD(r.param("lat"), Double.NaN), cLon = parseD(r.param("lon"), Double.NaN);       // lat/lon: around that point (the map's centre); otherwise around the phone
                boolean atPoint = !Double.isNaN(cLat) && !Double.isNaN(cLon) && Math.abs(cLat) <= 90 && Math.abs(cLon) <= 180;
                android.location.Location me = svc.lastKnownLocation(); if (!atPoint && me == null) return WebServer.Response.error(409, "No position fix on the phone yet");
                double mi = Math.max(5, Math.min(150, parse(r.param("radiusMi"), svc.getSharedPreferences("repeaters", Context.MODE_PRIVATE).getInt("radiusMi", 30))));
                StringBuilder st = new StringBuilder();
                java.util.List<com.macdmr.radio.repeaters.RepeaterModels.Repeater> found = repeaters().nearby(atPoint ? cLat : me.getLatitude(), atPoint ? cLon : me.getLongitude(), mi * 1.609344, "1".equals(r.param("force")), st);
                JSONArray a = new JSONArray(); lastRepeaters.clear();
                for (com.macdmr.radio.repeaters.RepeaterModels.Repeater x : found) {
                    lastRepeaters.put(x.key, x);
                    JSONObject o = new JSONObject().put("key", x.key).put("callsign", x.callsign).put("rxHz", x.rxHz).put("txHz", x.txHz).put("fm", x.fm).put("dmr", x.dmr).put("cc", x.cc)
                            .put("network", x.network).put("city", x.city).put("region", x.region).put("toneUp", x.toneUp).put("toneDown", x.toneDown).put("status", x.status)
                            .put("distanceKm", Double.isNaN(x.distanceMeters) ? null : x.distanceMeters / 1000.0).put("approx", x.approx);
                    if (x.hasCoords) o.put("lat", x.lat).put("lon", x.lon);
                    a.put(o);
                }
                return ok(new JSONObject().put("repeaters", a).put("status", st.toString()).put("radiusMi", mi).put("around", atPoint ? "map" : "phone"));
            }
            case "/api/repeaters/talkgroups": {
                com.macdmr.radio.repeaters.RepeaterModels.Repeater x = lastRepeaters.get(r.param("key")); if (x == null) return WebServer.Response.error(404, "Search again: that repeater is not in the last list");
                JSONArray a = new JSONArray();
                for (com.macdmr.radio.repeaters.RepeaterModels.Talkgroup t : repeaters().talkgroups(x)) a.put(new JSONObject().put("id", t.id).put("slot", t.slot).put("name", t.name).put("kind", t.kind));
                return ok(new JSONObject().put("talkgroups", a));
            }
            case "/api/repeaters/add": if (!post) break; {                      // {key, talk: [tg…], confirm}: without confirm only the preview
                com.macdmr.radio.repeaters.RepeaterModels.Repeater x = lastRepeaters.get(b.optString("key", "")); if (x == null) return WebServer.Response.error(404, "Search again: that repeater is not in the last list");
                java.util.Set<Integer> talk = new java.util.HashSet<>(); JSONArray ta = b.optJSONArray("talk"); if (ta != null) for (int i = 0; i < ta.length(); i++) talk.add(ta.getInt(i));
                com.macdmr.radio.repeaters.RepeaterPlanner.Plan plan = repeaters().plan(x, talk);
                if (plan.channels.isEmpty()) return WebServer.Response.error(409, plan.warnings.isEmpty() ? "Nothing to program" : plan.warnings.get(0));
                if (!b.optBoolean("confirm", false)) return ok(new JSONObject().put("preview", com.macdmr.radio.repeaters.RepeaterService.preview(plan)));
                String res = repeaters().program(x, plan);
                return ok(res);
            }
            case "/api/zones": {
                if (post) {                                                     // save {id?, name, numbers[]}
                    ChannelStore.Zone z = new ChannelStore.Zone(); z.id = b.optLong("id", 0); z.name = b.optString("name", "").trim();
                    if (z.name.isEmpty()) return WebServer.Response.error(400, "A zone needs a name");
                    JSONArray ns = b.optJSONArray("numbers"); java.util.List<Integer> keep = new java.util.ArrayList<>();
                    if (ns != null) for (int i = 0; i < ns.length(); i++) { int n = ns.getInt(i); if (svc.store().byNumber(n) != null && !keep.contains(n)) keep.add(n); }
                    if (keep.isEmpty()) return WebServer.Response.error(400, "Pick at least one channel for the zone");
                    java.util.Collections.sort(keep); z.numbers = new int[keep.size()]; for (int i = 0; i < keep.size(); i++) z.numbers[i] = keep.get(i);
                    long id = svc.store().saveZone(z);
                    if (svc.zoneId() == id) onMain(() -> { svc.setZone(id); return null; });   // the active zone changed: scan and arrows follow it
                    return WebServer.Response.json(200, new JSONObject().put("ok", true).put("message", "Saved zone " + z.name).put("id", id).toString());
                }
                JSONArray zs = new JSONArray();
                for (ChannelStore.Zone z : svc.store().zones()) { JSONArray ns = new JSONArray(); for (int n : z.numbers) ns.put(n); zs.put(new JSONObject().put("id", z.id).put("name", z.name).put("numbers", ns)); }
                JSONArray all = new JSONArray(); for (ChannelStore.Channel ch : svc.store().all()) all.put(new JSONObject().put("number", ch.number).put("name", ch.name).put("rxFreq", ch.rxFreq).put("digital", ch.isDigital()));
                return ok(new JSONObject().put("zones", zs).put("channels", all).put("active", svc.zoneId()));
            }
            case "/api/zones/delete": if (!post) break; {
                long id = b.optLong("id", 0); ChannelStore.Zone z = svc.store().zone(id); if (z == null) return WebServer.Response.error(404, "No such zone");
                onMain(() -> { if (svc.zoneId() == id) svc.setZone(0); svc.store().deleteZone(id); return null; });
                return ok("Deleted zone " + z.name);
            }
            case "/api/backups": {                                              // the backups in Download/DMR/DMR_Backups
                JSONArray a = new JSONArray();
                for (File d : com.macdmr.radio.Backup.backups()) {
                    JSONArray fs = new JSONArray(); File[] l = d.listFiles(); if (l != null) for (File f : l) if (f.getName().endsWith(".csv")) fs.put(f.getName());
                    a.put(new JSONObject().put("name", d.getName()).put("ts", d.lastModified()).put("files", fs));
                }
                return ok(new JSONObject().put("backups", a));
            }
            case "/api/backup": if (!post) break; {
                File dir = com.macdmr.radio.Backup.export(svc.store(), svc.packet() != null ? svc.packet().dtmfSaved() : null);
                return WebServer.Response.json(200, new JSONObject().put("ok", true).put("message", "Backed up to Download/DMR/DMR_Backups/" + dir.getName()).put("name", dir.getName()).toString());
            }
            case "/api/backup/zip": {                                           // one backup as a .zip download
                File dir = backupDir(r.param("name")); if (dir == null) return WebServer.Response.error(404, "No such backup");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bo)) {
                    File[] l = dir.listFiles(); if (l != null) for (File f : l) if (f.isFile() && f.getName().endsWith(".csv")) {
                        z.putNextEntry(new java.util.zip.ZipEntry(dir.getName() + "/" + f.getName()));
                        try (InputStream in = new java.io.FileInputStream(f)) { z.write(readAll(in)); }
                        z.closeEntry();
                    }
                }
                WebServer.Response x = WebServer.Response.bytes("application/zip", bo.toByteArray());
                x.headers.put("Content-Disposition", "attachment; filename=\"" + dir.getName() + ".zip\""); return x;
            }
            case "/api/restore": if (!post) break; {                            // {name} an existing backup, or {files: {"Channels.csv": text, …}} uploaded from the computer
                if (svc.firmwareLoading()) return WebServer.Response.error(409, "Radio busy, loading firmware");
                if (svc.isTransmitting()) return WebServer.Response.error(409, "Not while transmitting");
                File dir;
                if (b.has("files")) {
                    JSONObject fs = b.getJSONObject("files");
                    java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList("Contacts.csv", "TG_Lists.csv", "Channels.csv", "Zones.csv", "DTMF.csv"));
                    if (!fs.has("Channels.csv")) return WebServer.Response.error(400, "Channels.csv is needed (with Contacts.csv, TG_Lists.csv and Zones.csv from the same backup)");
                    dir = new File(svc.getCacheDir(), "web_restore"); deleteTree(dir); dir.mkdirs();
                    for (java.util.Iterator<String> it = fs.keys(); it.hasNext(); ) {
                        String n = it.next(); if (!allowed.contains(n)) continue;
                        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, n))) { o.write(fs.getString(n).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                    }
                } else {
                    dir = backupDir(b.optString("name", "")); if (dir == null) return WebServer.Response.error(404, "No such backup");
                }
                final File from = dir;
                String res;
                try {
                    res = com.macdmr.radio.Backup.restore(svc.store(), from);
                    if (svc.packet() != null) for (java.util.Map.Entry<String, String> e : com.macdmr.radio.Backup.readDtmf(from).entrySet()) svc.packet().dtmfSave(e.getKey(), e.getValue());
                } catch (Exception e) { return WebServer.Response.error(400, "Restore failed: " + e.getMessage()); }
                onMain(() -> { svc.selectChannel(svc.store().activeNumber()); return null; });
                return ok(res);
            }
            case "/api/log": {                                                  // the Diagnostics page's log (last 300 lines)
                JSONArray a = new JSONArray(); for (String l : svc.recentLog()) a.put(l);
                return ok(new JSONObject().put("lines", a));
            }
            case "/api/heard": {                                                // Last heard, grouped like the phone's sheet, plus alerts and events
                java.util.LinkedHashMap<String, JSONObject> rows = new java.util.LinkedHashMap<>();
                for (Object[] h : svc.store().lastHeard(400)) {
                    long ts = (Long) h[0]; int ch = (Integer) h[1]; String kind = (String) h[2], text = (String) h[3]; int id = (Integer) h[4];
                    if (text != null && (text.startsWith("⛔") || text.startsWith("⚠"))) continue;
                    String key = ch + ":" + (kind.equals("dmr") || kind.equals("sms") ? id : kind);
                    JSONObject e = rows.get(key);
                    if (e != null) { e.put("count", e.getInt("count") + 1); continue; }
                    ChannelStore.Channel c = svc.store().byNumber(ch);
                    rows.put(key, new JSONObject().put("ts", ts).put("channel", ch).put("channelName", c != null ? c.name : ch == 0 ? "VFO" : "channel " + ch)
                            .put("kind", kind).put("text", text).put("dmrId", id).put("name", id > 0 ? svc.callerName(id) : "").put("count", 1));
                }
                JSONArray a = new JSONArray(); for (JSONObject o : rows.values()) a.put(o);
                JSONArray ev = new JSONArray();
                for (Object[] h : svc.store().events(System.currentTimeMillis() - 7L * 24 * 3600 * 1000, 100))
                    ev.put(new JSONObject().put("ts", h[0]).put("channel", h[1]).put("kind", h[2]).put("text", h[3]));
                return ok(new JSONObject().put("heard", a).put("events", ev));
            }
            case "/api/help": {
                JSONArray a = new JSONArray();
                for (String[] t : com.macdmr.radio.ui.HelpView.topicsForWeb()) {
                    JSONArray secs = new JSONArray(); for (int i = 3; i + 1 < t.length; i += 2) secs.put(new JSONObject().put("head", t[i]).put("text", t[i + 1]));
                    a.put(new JSONObject().put("group", t[0]).put("title", t[1]).put("summary", t[2]).put("sections", secs));
                }
                return ok(new JSONObject().put("topics", a));
            }
            case "/api/contacts": {
                if (post) {                                                     // create or update {id?, name, number, type}
                    ChannelStore.Contact ct = b.optLong("id", 0) > 0 ? svc.store().contactById(b.optLong("id", 0)) : new ChannelStore.Contact();
                    if (ct == null) return WebServer.Response.error(404, "No such contact");
                    ct.name = b.optString("name", "").trim(); ct.number = b.optInt("number", 0); ct.type = b.optInt("type", 0);
                    String cs = b.optString("callsign", ct.callsign).trim().toUpperCase(java.util.Locale.US);
                    if (!cs.isEmpty() && !cs.matches("[A-Z0-9]{1,9}(-[0-9]{1,2})?")) return WebServer.Response.error(400, "Callsign: letters and digits, an optional -SSID");
                    if (!cs.equals(ct.callsign)) ct.callsignAuto = 2;
                    ct.callsign = cs; ct.rics = ChannelStore.ricText(ChannelStore.parseRics(b.optString("rics", ct.rics))); ct.notes = b.optString("notes", ct.notes).trim(); ct.homeLabel = b.optString("homeLabel", ct.homeLabel).trim();
                    if (b.has("home")) { String h = b.optString("home", "").trim(); double[] hp = h.isEmpty() ? null : com.macdmr.radio.ui.ContactEditor.parseLocation(h); if (!h.isEmpty() && hp == null) return WebServer.Response.error(400, "Home: a grid square or latitude, longitude"); ct.homeLat = hp == null ? Double.NaN : hp[0]; ct.homeLon = hp == null ? Double.NaN : hp[1]; }
                    if (ct.number < 0 || ct.number > 0xFFFFFF) return WebServer.Response.error(400, "The DMR ID must be 1 to 16777215");
                    if (ct.number == 0 && ct.callsign.isEmpty() && ct.rics.isEmpty()) return WebServer.Response.error(400, "A contact needs a DMR ID, a callsign or a pager RIC");
                    if (ct.type < 0 || ct.type > 2) return WebServer.Response.error(400, "type: 0 private, 1 group, 2 all");
                    if (ct.name.isEmpty()) ct.name = !ct.callsign.isEmpty() ? ct.callsign : ct.number > 0 ? "ID " + ct.number : "RIC " + ct.rics;
                    if (ct.id > 0) svc.store().updateContact(ct); else svc.store().insertContact(ct);
                    return ok("Saved " + (ct.name.isEmpty() ? String.valueOf(ct.number) : ct.name));
                }
                JSONArray a = new JSONArray();
                for (ChannelStore.Contact ct : svc.store().contacts()) {
                    JSONObject o = new JSONObject().put("id", ct.id).put("name", ct.name).put("number", ct.number).put("type", ct.type)
                            .put("callsign", ct.callsign).put("rics", ct.rics).put("notes", ct.notes).put("homeLabel", ct.homeLabel).put("lastTs", ct.lastTs).put("lastSource", ct.lastSource);
                    if (ct.hasHome()) o.put("homeLat", ct.homeLat).put("homeLon", ct.homeLon);
                    if (ct.hasLast()) o.put("lastLat", ct.lastLat).put("lastLon", ct.lastLon);
                    a.put(o);
                }
                return ok(new JSONObject().put("contacts", a));
            }
            case "/api/contacts/history": {                                 // ?id=N: the contact's heard/sent history, newest first
                long id = b.optLong("id", 0); if (id <= 0) id = parse(r.param("id"), 0);
                JSONArray a = new JSONArray();
                for (ChannelStore.Heard h : svc.store().contactHeard(id, 200)) { JSONObject o = new JSONObject().put("ts", h.ts).put("source", h.source).put("detail", h.detail).put("sent", h.sent); if (h.hasPosition()) o.put("lat", h.lat).put("lon", h.lon); a.put(o); }
                return ok(new JSONObject().put("history", a));
            }
            case "/api/contacts/delete": if (!post) break; {
                long id = b.optLong("id", 0);
                for (ChannelStore.Contact ct : svc.store().contacts()) if (ct.id == id) { svc.store().deleteContact(ct); return ok("Deleted " + (ct.name.isEmpty() ? String.valueOf(ct.number) : ct.name)); }
                return WebServer.Response.error(404, "No such contact");
            }
            case "/api/conversations": {
                JSONArray a = new JSONArray();
                for (ChannelStore.Conversation c : svc.conversations())
                    a.put(new JSONObject().put("peer", c.peer).put("type", c.type).put("name", c.name).put("lastText", c.lastText).put("lastTs", c.lastTs).put("outgoing", c.outgoing)
                            .put("transport", c.transport == null || c.transport.isEmpty() ? "dmr" : c.transport)
                            .put("threadKey", c.threadKey == null ? "" : c.threadKey).put("address", c.address == null ? "" : c.address).put("unread", c.unread));
                return ok(new JSONObject().put("conversations", a).put("quickReplies", new JSONArray(quickReplies())));
            }
            case "/api/messages": {
                if (post) {                                                     // DMR {peer, type, text}; a page or M17 thread sends on that transport
                    if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                    String transport = b.optString("transport", "dmr");
                    String text = b.optString("text", "").trim();
                    if ("page".equals(transport)) return sendWebPage(b, text);
                    if ("m17".equals(transport)) return sendWebM17(b, text);
                    WebServer.Response denied = gate("/api/messages"); if (denied != null) return denied;
                    if (text.isEmpty() || b.optInt("peer", 0) <= 0) return WebServer.Response.error(400, "A recipient and a text are needed");
                    if (text.length() > 140) return WebServer.Response.error(400, "Texts are at most 140 characters");
                    if (!svc.isReady()) return WebServer.Response.error(409, "The radio is not ready");
                    int peer = b.optInt("peer", 0), type = b.optInt("type", 0);
                    ChannelStore.Contact to = new ChannelStore.Contact(); to.number = peer; to.type = type;
                    String nm = svc.store().contactName(peer, type); to.name = nm == null ? "" : nm;
                    onMain(() -> { svc.sendSms(to, text); return null; });
                    return ok("Sending to " + (to.name.isEmpty() ? String.valueOf(peer) : to.name));
                }
                String thread = r.param("thread");
                if (thread != null && !thread.isEmpty()) {
                    svc.watchingThread(thread);
                    String mine = svc.packet() != null && svc.packet().callsignSet() ? svc.packet().callsign() : null;
                    JSONArray a = new JSONArray();
                    for (ChannelStore.PageRow row : svc.store().pagesIn(thread))
                        a.put(new JSONObject().put("id", row.id).put("outgoing", row.outgoing).put("ts", row.ts).put("text", hideCall(row.text, mine)).put("status", 1)
                                .put("transport", row.transport).put("baud", row.baud).put("hz", row.hz).put("ric", row.ric));
                    return ok(new JSONObject().put("messages", a));
                }
                int peer = parse(r.param("peer"), 0), type = parse(r.param("type"), 0);
                JSONArray a = new JSONArray();
                for (ChannelStore.Message m : svc.store().messages(peer, type))
                    a.put(new JSONObject().put("id", m.id).put("outgoing", m.outgoing).put("ts", m.ts).put("text", m.text).put("status", m.status)
                            .put("sender", m.sender).put("senderName", m.sender > 0 ? svc.callerName(m.sender) : ""));
                return ok(new JSONObject().put("messages", a));
            }
            case "/api/pager/pages": {
                int ricFilter = parse(r.param("ric"), -1);
                String mine = svc.packet() != null && svc.packet().callsignSet() ? svc.packet().callsign() : null;
                JSONArray a = new JSONArray();
                for (ChannelStore.PageRow row : svc.store().recentPages(200)) {
                    if (!"page".equals(row.transport)) continue;
                    if (ricFilter >= 0 && row.ric != ricFilter) continue;
                    a.put(new JSONObject().put("id", row.id).put("ts", row.ts).put("hz", row.hz).put("ric", row.ric).put("func", row.func).put("baud", row.baud)
                            .put("text", hideCall(row.text, mine)).put("corrected", row.corrected).put("outgoing", row.outgoing).put("threadKey", row.threadKey));
                }
                com.macdmr.radio.PagerStation pg = svc.pager();
                ChannelStore.Channel cur = svc.current();
                return ok(new JSONObject().put("pages", a).put("listening", svc.pagerRunning()).put("monitor", pg != null && pg.monitoring())
                        .put("hz", cur == null ? 467625000 : cur.rxFreq).put("webTx", allowTx()).put("scan", pagerScanText()));
            }
            case "/api/pager/monitor": if (!post) break; {
                WebServer.Response denied = gate("/api/pager/monitor"); if (denied != null) return denied;
                boolean on = b.optBoolean("on", false);
                String err = onMain(() -> on ? svc.setPagerMonitor(true, pagerScanHz(), b.optInt("baud", 1200)) : stopPagerMonitor());
                if (err != null) return WebServer.Response.error(409, err);
                return ok(on ? "Pager monitor on" : "Pager monitor off");
            }
            case "/api/pager/send": if (!post) break; {
                WebServer.Response denied = gate("/api/pager/send"); if (denied != null) return denied;
                if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                return sendWebPage(b, b.optString("text", "").trim());
            }
            case "/api/m17/sms": if (!post) break; {
                WebServer.Response denied = gate("/api/m17/sms"); if (denied != null) return denied;
                if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
                return sendWebM17(b, b.optString("text", "").trim());
            }
            case "/api/device": return ok(deviceJson());
            case "/api/device/restart": if (!post) break; {
                WebServer.Response denied = gateAdmin("/api/device/restart"); if (denied != null) return denied;
                if (!b.optBoolean("confirm", false)) return WebServer.Response.error(400, "Confirm on the page first");
                onMain(() -> { svc.diagRestartModule(); return null; });
                return ok("Restarting the radio module");
            }
            case "/api/device/update": if (!post) break; {
                WebServer.Response denied = gateAdmin("/api/device/update"); if (denied != null) return denied;
                if (!b.optBoolean("confirm", false)) return WebServer.Response.error(400, "Confirm on the page first");
                return deviceUpdate(b.optBoolean("install", false));
            }
            default: break;
        }
        return null;
    }

    /** One modeAllowed or adminAllowed call, from the part-B route tables. */
    static final String[][] ROUTE_MODES = WebRoutes.ROUTE_MODES;
    static final String[][] ROUTE_ADMIN = WebRoutes.ROUTE_ADMIN;
    private static final String RESTRICTED = "That is turned off in Restricted mode";
    private WebServer.Response gate(String path) {
        String id = null;
        for (String[] m : ROUTE_MODES) if (path.equals(m[0])) id = m[1];
        if (id != null && !svc.modeAllowed(id)) return WebServer.Response.error(403, RESTRICTED);
        return null;
    }
    private WebServer.Response gateAdmin(String path) {
        String id = null;
        for (String[] a : ROUTE_ADMIN) if (path.equals(a[0])) id = a[1];
        if (id != null && !svc.adminAllowed(id)) return WebServer.Response.error(403, RESTRICTED);
        return null;
    }
    private int[] ricsOf(String token) {
        String key = PagerTargets.key(token);
        for (ChannelStore.Contact ct : svc.store().contacts()) {
            String name = ct.name == null ? "" : ct.name.toUpperCase(java.util.Locale.US);
            String call = ct.baseCall() == null ? "" : ct.baseCall().toUpperCase(java.util.Locale.US);
            if (key.equals(name) || key.equals(call)) return ct.ricList();
        }
        return null;
    }
    private int[] pagerScanHz() {
        String raw = svc.getSharedPreferences("pager", Context.MODE_PRIVATE).getString("scan", "467625000,467650000");
        java.util.List<Integer> hz = new java.util.ArrayList<>();
        for (String s : raw.split("[,\\s]+")) { try { int n = Integer.parseInt(s.trim()); if (ChannelStore.inBand(n)) hz.add(n); } catch (Exception ignored) { } }
        int[] a = new int[hz.size()]; for (int i = 0; i < a.length; i++) a[i] = hz.get(i); return a;
    }
    private String pagerScanText() {
        StringBuilder sb = new StringBuilder();
        for (int hz : pagerScanHz()) { if (sb.length() > 0) sb.append(", "); sb.append(String.format(java.util.Locale.US, "%.4f", hz / 1e6)); }
        return sb.toString();
    }
    private String stopPagerMonitor() {
        com.macdmr.radio.PagerStation p = svc.pager();
        if (p != null) p.monitor(false);
        return null;
    }
    private WebServer.Response sendWebPage(JSONObject b, String text) throws Exception {
        WebServer.Response denied = gate("/api/pager/send"); if (denied != null) return denied;
        if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
        if (text == null || text.trim().isEmpty()) return WebServer.Response.error(400, "Write the message");
        int hz = b.optInt("hz", 0);
        if (hz <= 0 && svc.current() != null) hz = svc.current().rxFreq;
        int[] rics;
        if (b.has("ric") && !b.has("to")) {
            int ric = b.optInt("ric", -1);
            if (ric < 0 || ric > 0x1FFFFF) return WebServer.Response.error(400, "Add a RIC");
            rics = new int[] { ric };
        } else {
            try { rics = PagerTargets.resolve(b.optString("to", ""), this::ricsOf); }
            catch (IllegalArgumentException e) { return WebServer.Response.error(400, e.getMessage()); }
        }
        final int fhz = hz, baud = b.optInt("baud", 1200), func = b.optInt("func", 3);
        final int[] fr = rics; final boolean sign = b.optBoolean("sign", true); final String body = text.trim();
        String err = onMain(() -> svc.sendPage(baud, fhz, fr, func, body, sign));
        if (err != null) return WebServer.Response.error(409, err);
        return ok("Sending to " + rics.length + (rics.length == 1 ? " RIC" : " RICs"));
    }
    private WebServer.Response sendWebM17(JSONObject b, String text) throws Exception {
        WebServer.Response denied = gate("/api/m17/sms"); if (denied != null) return denied;
        if (!allowTx()) return WebServer.Response.error(403, TX_OFF);
        final String dest = b.optString("dest", ""); final String body = text == null ? "" : text.trim();
        String err = onMain(() -> svc.sendM17Sms(dest, body));
        if (err != null) return WebServer.Response.error(409, err);
        return ok("Sending");
    }
    private JSONObject deviceJson() throws Exception {
        String tag = svc.moduleVersion() == null ? "" : svc.moduleVersion().trim();
        String stock = svc.stockFirmware() == null ? "" : svc.stockFirmware().trim();
        boolean factory = !tag.isEmpty() && (stock.isEmpty() ? tag.contains("UV4T") && !tag.contains(".FW") : tag.equals(stock));
        com.macdmr.radio.DeviceProfile p = svc.profile();
        double ap = svc.thermal.readC();
        double bat = com.macdmr.radio.ThermalGuard.batteryC(svc);
        String prop = com.macdmr.radio.RootSetup.run("getprop persist.adb.tcp.port");
        String port = prop == null ? "" : prop.trim();
        int nl = port.indexOf('\n'); if (nl >= 0) port = port.substring(0, nl).trim();
        JSONObject o = new JSONObject().put("tag", tag).put("stock", stock).put("factory", factory).put("ready", svc.isReady())
                .put("profile", p == null ? "" : p.name).put("profileId", p == null ? "" : p.id).put("profileRevision", p == null ? 0 : p.revision)
                .put("version", com.macdmr.radio.BuildConfig.VERSION_NAME).put("ownAtBoot", svc.ownAtBoot())
                .put("wifiAdb", "5555".equals(port)).put("wifiAdbPort", port);
        if (!Double.isNaN(bat)) o.put("batteryC", Math.round(bat * 10) / 10.0);
        if (!Double.isNaN(ap)) o.put("apC", Math.round(ap * 10) / 10.0);
        return o;
    }
    private WebServer.Response deviceUpdate(boolean install) {
        try {
            com.macdmr.radio.Updater.Release rel = com.macdmr.radio.Updater.fetchLatest();
            String have = com.macdmr.radio.BuildConfig.VERSION_NAME;
            if (rel == null) return ok(new JSONObject().put("ok", true).put("message", "No SignalDeck release is published yet").put("installed", have).put("newer", false));
            boolean newer = com.macdmr.radio.Updater.compareVersions(rel.version, have) > 0;
            if (!newer || !install) {
                String msg = newer ? ("Update " + rel.version + " is available") : ("You are on the latest version (" + have + ")");
                return ok(new JSONObject().put("ok", true).put("message", msg).put("installed", have).put("latest", rel.version == null ? "" : rel.version).put("newer", newer).put("notes", rel.notes == null ? "" : rel.notes));
            }
            String name = rel.apkName == null ? "SignalDeck-update.apk" : rel.apkName.replaceAll("[^A-Za-z0-9._-]", "_");
            if (!name.endsWith(".apk")) name = name + ".apk";
            java.io.File dest = new java.io.File(svc.getCacheDir(), name);
            downloadTo(rel.apkUrl, dest);
            String result = com.macdmr.radio.Updater.rootInstall(dest.getAbsolutePath());
            return ok(new JSONObject().put("ok", true).put("message", "Installing " + rel.version).put("installed", have).put("latest", rel.version).put("newer", true).put("install", result == null ? "" : result));
        } catch (Exception e) {
            String m = e.getMessage() == null ? "Update check failed" : e.getMessage();
            return WebServer.Response.error(502, m);
        }
    }
    private static void downloadTo(String url, java.io.File dest) throws Exception {
        if (url == null || !url.startsWith("https://")) throw new IllegalArgumentException("The update has no download address");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setInstanceFollowRedirects(true); c.setConnectTimeout(15000); c.setReadTimeout(120000);
        try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[65536]; int n; long total = 0;
            while ((n = in.read(buf)) > 0) { total += n; if (total > 80L * 1024 * 1024) throw new IllegalStateException("The update is larger than 80 MB"); out.write(buf, 0, n); }
        } finally { c.disconnect(); }
    }
}
