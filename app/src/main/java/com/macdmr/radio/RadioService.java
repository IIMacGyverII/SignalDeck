/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns the radio while the app runs: engine (UART), audio backend, channel store, current state.
 * Activities bind and register a {@link Listener}; everything the UI shows comes through it.
 */
public class RadioService extends Service implements RadioEngine.Events {
    private static final String TAG = "RadioService";

    public interface Listener {
        void onChannel(ChannelStore.Channel ch);
        void onRx(boolean receiving);
        void onTx(boolean transmitting);
        void onRssi(int dbm);
        void onAudioLevel(int amplitude);
        /** The received audio's spectrum (Spectrum.BANDS values 0…1), every 32 ms while something is heard; all zero when it stops. */
        default void onSpectrum(float[] bands) { }
        /** A band scope sweep finished: dBm per step from {@code from} in {@code step} Hz (NaN = no reading). */
        default void onScope(int from, int step, float[] dbm) { }
        default void onScopeState(String state) { }
        void onHistory(String line);
        void onCaller(String text);
        void onLog(String s);
        void onLink(String state);          // "no root", "policy", "uart closed", "ready"…
        void onLocation(String text);
        void onSms(int from, int type, String text);
        void onSmsResult(boolean ok);
        void onModes(boolean softSq, int softSqLevel, boolean squelchOpen, boolean monitor, boolean recording, boolean scanning, boolean holding, String zoneName);
        void onCaption(boolean enabled, String live, String sentence);
        /** The channel list is empty at take-over: ask whether to import the stock app's channels ({@link #answerStockImport}). */
        default void onStockImportOffer() { }
        /** The last channel was deleted: no channel is selected any more (pages show their empty state). */
        default void onNoChannel() { }
        /** The channel list or the zones changed (here, on the web or by an import): lists showing them reload. */
        default void onChannels() { }   // live = current partial ("" to clear); sentence = a finished line (null if none)       // the intercom's location block ("City, State\n(↓S 25.5mi (41.0km))\n959ft (292m) 📍")
        /** A page or an M17 text was filed under {@code threadKey}. */
        default void onPage(String threadKey, String line) { }
    }

    public class LocalBinder extends Binder { public RadioService get() { return RadioService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** The running service, for the widget and notification buttons (set in onCreate, cleared in onDestroy). */
    public static volatile RadioService running;

    private DeviceProfile profile;
    private ModuleEngine engine;
    private AudioBackend audio;
    private ChannelStore store;
    private ChannelStore.Channel current;
    private volatile boolean receiving, transmitting, rxAudioOn;
    private volatile int rssi = -999;
    private volatile long rxStart;
    private volatile boolean rxCounts;   // decided at RECEIVE_START: the matching STOP can arrive after a retune, when "current" is already the next channel
    private volatile int lastCaller, lastCallType, lastTarget, lastCallerCc = -1;
    private volatile long tunedAt, rssiAt;   // the last channel/VFO write to the module and the last signal reading (fox hunt: settle and fresh readings)
    private String link = "stopped";
    private final com.macdmr.radio.dsp.SoftSquelch softSq = new com.macdmr.radio.dsp.SoftSquelch();
    private volatile boolean monitorOn, recordOn, squelchOpen;
    private WavRecorder recorder;
    private volatile boolean captionsOn;
    private volatile long captionLastEnd;
    private VoskCaptions captions;
    private long zoneId;                       // 0 = all channels
    private final Scanner scanner = new Scanner(new Scanner.Host() {
        @Override public java.util.List<ChannelStore.Channel> candidates() { return zoneChannels(); }
        @Override public void tune(ChannelStore.Channel ch) { selectChannel(ch.number); }
        @Override public boolean receiving() { return softSq.isEnabled() ? squelchOpen : receiving; }   // with Soft SQ on the module's squelch is open and it reports a reception all the time: the soft gate is the activity signal
        @Override public boolean softGated() { return softSq.isEnabled(); }
        @Override public long dwellMs() { return profile.scanDwellMs; }
        @Override public ChannelStore.Channel priority() { int n = scanPriority(); return n > 0 ? store.byNumber(n) : null; }
        @Override public void status(boolean s, boolean h, ChannelStore.Channel on) {
            if (voiceOn()) {
                if (s && h && on != null) sayChannel("Holding " + channelWords(on));                       // the scan stopped on activity
                else if (s && !h) { ui.removeCallbacks(channelSay); pendingChannelSay = null; if (speakingChannel && tts != null) try { tts.stop(); } catch (Throwable ignored) { } }   // moving on: drop it
                else if (!s && current != null) sayChannel(channelWords(current));                          // scan off: where you are
            }
            if (!s) { final ChannelStore.Channel ch = current; if (ch != null && !ch.isDigital() && !ch.softSq && scanSoftSq()) worker.execute(() -> refreshSquelch(ch)); } pushModes(); }   // scan over: a channel that only had Soft SQ for the scan goes back to its own squelch
    });
    /** Every MCU/audio action runs here, in order — a PTT release can never overtake its press. */
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "radio-worker"));
    private volatile boolean pttDown;
    private final Runnable txWatchdog = () -> { if (pttDown) { log("TX watchdog: releasing PTT after 28 s"); ptt(false); } };
    private PacketEngine packet;

    // ---- simplex repeater (parrot): repeats each reception on the channel it was enabled on ----
    private final com.macdmr.radio.ham.Parrot parrot = new com.macdmr.radio.ham.Parrot(new com.macdmr.radio.ham.Parrot.Host() {
        @Override public boolean transmit(short[] pcm, String label, Runnable done) {
            ChannelStore.Channel ch = current;
            if (!isReady() || ch == null || ch.isDigital() || txInhibited() || pttDown || scanner.isScanning()) return false;
            transmitPcm(pcm, label, done); return true;
        }
        @Override public String idCallsign() { ChannelStore.Channel ch = current; return ch != null && amateurBand(ch.txFreq) && packet != null && packet.callsignSet() ? packet.callsign() : null; }
        @Override public void event(String line) { history("pkt", line); }
    });
    private volatile int parrotChannel = -1;
    private volatile long txStartedAt;
    /** True while SignalDeck itself unkeys: a SEND_STOP then is ours, not the radio's time-out (the W6+ reports it before ptt() returns). */
    private volatile boolean unkeying;
    private void unkey() throws IOException { unkeying = true; try { engine.ptt(false); } finally { unkeying = false; } }
    /** The label of the transmission SignalDeck plays itself (APRS, FT8, SSTV …), for its history line; null for voice. */
    private volatile String pcmTxLabel;

    // ---- voice keyer and the ten-minute identification timer ----
    private final com.macdmr.radio.ham.VoiceKeyer keyer = new com.macdmr.radio.ham.VoiceKeyer(this);
    public com.macdmr.radio.ham.VoiceKeyer keyer() { return keyer; }
    /** Transmit a recorded keyer message (with the Morse ID after it when set and on an amateur channel). Error or null. */
    public String sendKeyer(int slot) {
        short[] p = keyer.pcm(slot);
        if (p == null || p.length == 0) return "Nothing recorded in " + keyer.name(slot);
        ChannelStore.Channel c = current;
        if (c == null || c.isDigital()) return "The voice keyer needs an analog FM channel";
        if (txInhibited()) return "This channel is receive-only";
        boolean id = keyer.addId(slot) && amateurBand(c.txFreq) && packet != null && packet.callsignSet();
        short[] m = id ? com.macdmr.radio.coverage.Morse.pcm(packet.callsign(), 20, 800) : new short[0];
        int lead = 12000, gap = id ? 2400 : 0;                                   // 1.5 s: the radio passes audio only ~1.5 s after keying
        int voiceN = Math.min(p.length, 8000 * 28 - lead - gap - m.length);
        short[] out = new short[lead + voiceN + gap + m.length];
        System.arraycopy(p, 0, out, lead, voiceN); System.arraycopy(m, 0, out, lead + voiceN + gap, m.length);
        final String nm = keyer.name(slot); final boolean sentId = id;
        transmitPcm(out, "voice keyer: " + nm, () -> { history("pkt", "\uD83C\uDF99 Voice keyer sent: " + nm + (sentId ? " + Morse ID" : "")); if (sentId) markIdentified(); });
        return null;
    }
    /** The station-ID interval: the region's (Device → Region), else 10 minutes. */
    public static long idEveryMs() { return com.macdmr.radio.region.Region.cached().idEveryMs(); }
    private volatile long idClockFrom, lastIdReminder;                       // first transmission since the last identification
    public void markIdentified() { idClockFrom = 0; }
    /** Milliseconds until the next identification is due (negative: overdue), or Long.MAX_VALUE when the timer is not running. */
    public long idDueInMs() { long f = idClockFrom; return f == 0 ? Long.MAX_VALUE : f + idEveryMs() - System.currentTimeMillis(); }
    private void idTimerOnKey() {
        ChannelStore.Channel c = current;
        if (c == null || !amateurBand(c.txFreq) || packet == null || !packet.callsignSet()) return;
        long now = System.currentTimeMillis();
        if (idClockFrom == 0) { idClockFrom = now; return; }
        if (now - idClockFrom >= idEveryMs() && !packet.cwIdEnabled() && now - lastIdReminder > 120_000) {
            lastIdReminder = now;
            try { android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.vibrate(android.os.VibrationEffect.createWaveform(new long[] { 0, 120, 80, 120 }, -1)); } catch (Throwable ignored) { }
            final long min = idEveryMs() / 60_000L;
            ui.post(() -> android.widget.Toast.makeText(this, "Give your callsign: it has been " + min + " minutes since you identified", android.widget.Toast.LENGTH_LONG).show());
            history("pkt", "\u23F0 Time to identify: " + min + " minutes since your last ID");
        }
    }

    // ---- callsign lookup (HamDB) ----
    private com.macdmr.radio.ham.CallLookup lookup;
    public com.macdmr.radio.ham.CallLookup callLookup() { if (lookup == null) lookup = new com.macdmr.radio.ham.CallLookup(this); return lookup; }
    public boolean lookupOnline() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("lookupOnline", true); }
    public void setLookupOnline(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("lookupOnline", on).apply(); }
    private final java.util.concurrent.ExecutorService lookups = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "call-lookup"); t.setDaemon(true); return t; });
    /** Look a callsign up in the background and fill in the logbook (and {@code then}, with the result, on the worker). */
    public void enrichCall(final String call, final java.util.function.Consumer<com.macdmr.radio.ham.CallLookup.Info> then) {
        if (!lookupOnline() || com.macdmr.radio.ham.Logbook.baseCall(call) == null) return;
        lookups.execute(() -> {
            com.macdmr.radio.ham.CallLookup.Info i = callLookup().lookup(call);
            if (i == null || !i.found) return;
            try { logbook().enrich(call, i.name, i.grid, i.place()); } catch (Throwable t) { log("lookup: " + t); }
            if (then != null) then.accept(i);
        });
    }

    // ---- satellite tracking: Doppler-corrected VFO during a pass ----
    private com.macdmr.radio.ham.Satellites sats;
    public com.macdmr.radio.ham.Satellites satellites() { if (sats == null) sats = new com.macdmr.radio.ham.Satellites(this); return sats; }
    public static final class SatState { public String name = ""; public com.macdmr.radio.ham.SatTrack.Look look; public long rxHz, txHz, aos, los, nominalHz; public double maxEl; public boolean waiting, listenOnly; public int mode; }
    private volatile Thread satThread; private volatile SatState satState;
    public SatState satState() { return satState; }
    /** The tracked pass's mode (Satellites.APRS / SSTV), or -1 when nothing is tracked. */
    public int satMode() { SatState s = satState; return s == null ? -1 : s.mode; }
    public void stopSatTrack() { Thread t = satThread; satThread = null; if (t != null) t.interrupt(); }
    /** Stop tracking and following passes (the Stop buttons). */
    public void endSatTracking() { setIssRepeat(-1); stopSatTrack(); }
    /** Bench (DIAG vfo): listen on a frequency through the VFO, wide, low power. */
    void diagVfo(int hz, int power) { diagVfo(hz, power, 0); }
    /** {@code ctcssHz} > 0: the bench VFO transmits that tone (a fox for the fox hunt tests); power 0 = low, 1 = high. */
    void diagVfo(int hz, int power, double ctcssHz) {
        ChannelStore.Channel a = new ChannelStore.Channel(); a.type = 1; a.rxFreq = a.txFreq = hz; a.band = 1; a.power = power; a.name = "Bench";
        int t = ctcssHz > 0 ? ChannelPresets.ctcssIndex(ctcssHz) : -1; if (t >= 0) { a.txType = 1; a.txSubCode = t; }
        applyVfo(a, false);
    }
    void diagVfoOff() { if (vfo != null && "Bench".equals(vfo.name)) exitVfo(); }
    /** Record whole passes (AOS to LOS, below the squelch too) as WAV in Download/DMR/Audio/<satellite>/…_pass.wav. */
    public boolean satRecord() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("satRecord", true); }
    public void setSatRecord(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("satRecord", on).apply(); }
    private volatile WavRecorder passRec;
    private void finishPassRecording() {
        WavRecorder r = passRec; passRec = null; if (r == null) return;
        try { java.io.File f = r.stop(); if (f != null) history("rec", "\uD83C\uDFA7 Pass recorded: " + f.getName()); } catch (Throwable t) { log("pass record stop: " + t); }
    }
    /**
     * Fresh orbits for a pass: elements older than a day are downloaded again (call off the main thread). Returns the
     * new elements and the same pass computed from them, or null when nothing changed (fresh enough, offline, not found).
     */
    private Object[] refreshForPass(com.macdmr.radio.ham.Satellites.Sat sat, com.macdmr.radio.ham.SatTrack.Pass pass, android.location.Location me) {
        try {
            if (satellites().elementsAgeMs() < 24 * 3_600_000L) return null;
            String e = satellites().update(); if (e != null) { log("orbits before the pass: " + e); return null; }
            com.macdmr.radio.ham.Sgp4 n = satellites().elements().get(sat.catalog); if (n == null) return null;
            for (com.macdmr.radio.ham.SatTrack.Pass p : com.macdmr.radio.ham.SatTrack.passes(n, pass.aos - 30 * 60_000L, 1.5, me.getLatitude(), me.getLongitude(), me.hasAltitude() ? me.getAltitude() : 0, 0))
                if (Math.abs(p.aos - pass.aos) < 10 * 60_000L) {
                    log(String.format(Locale.US, "orbits refreshed before the pass: AOS moved %+d s, LOS %+d s", (p.aos - pass.aos) / 1000, (p.los - pass.los) / 1000));
                    return new Object[] { n, p };
                }
        } catch (Throwable t) { log("orbits before the pass: " + t); }
        return null;
    }
    /** Track a pass: at AOS minus a minute the VFO takes the satellite's channel, corrected for Doppler every 2 s, until LOS. */
    public String trackSat(final com.macdmr.radio.ham.Satellites.Sat sat, final com.macdmr.radio.ham.Sgp4 el, final com.macdmr.radio.ham.SatTrack.Pass pass) {
        final android.location.Location me = lastKnownLocation();
        if (me == null) return "No position fix: turn on location";
        if (!profile.tunable(sat.downHz)) return "This radio tunes " + profile.bandsText() + ": " + sat.name + " sends on " + String.format(java.util.Locale.US, "%.3f", sat.downHz / 1e6) + " MHz";
        stopSatTrack();
        final SatState st = new SatState(); st.name = sat.name; st.aos = pass.aos; st.los = pass.los; st.maxEl = pass.maxEl; st.waiting = true;
        final boolean listenOnly = sat.listenOnly() || !profile.tunable(sat.upHz);   // a 2 m uplink on a UHF-only radio (the W6): hear it, never key
        st.mode = sat.mode; st.listenOnly = listenOnly; st.nominalHz = sat.downHz; satState = st; updateModeWake();
        if (sat.mode != com.macdmr.radio.ham.Satellites.VOICE) modeSince = System.currentTimeMillis();
        final Thread t = new Thread(() -> {
            boolean tuned = false, started = false, natural = false;
            com.macdmr.radio.ham.Sgp4 E = el; com.macdmr.radio.ham.SatTrack.Pass P = pass; boolean refreshed = false;
            try {
                while (satThread == Thread.currentThread()) {
                    if (!refreshed && System.currentTimeMillis() > P.aos - 15 * 60_000L) {   // orbits a day old or more: fetch fresh ones 15 min ahead (or now, when closer)
                        refreshed = true; Object[] f = refreshForPass(sat, P, me);
                        if (f != null) { E = (com.macdmr.radio.ham.Sgp4) f[0]; P = (com.macdmr.radio.ham.SatTrack.Pass) f[1]; st.aos = P.aos; st.los = P.los; st.maxEl = P.maxEl; }
                    }
                    if (System.currentTimeMillis() >= P.aos - 60_000) break;
                    Thread.sleep(1000);
                }
                if (scope.running()) { scope.stop(); history("pkt", "\u2702 Band scope stopped for the satellite pass"); }
                history("pkt", "\uD83D\uDEF0 Tracking " + sat.name + ": tuning for the pass");
                ChannelStore.Channel v = new ChannelStore.Channel(); v.type = 1; v.band = 1; v.power = 1; v.name = sat.name; dataModeSquelch(v);   // satellites arrive far below the radio's squelch
                int tone = sat.toneHz > 0 ? com.macdmr.radio.ChannelPresets.ctcssIndex(sat.toneHz) : -1;
                if (tone >= 0) { v.txType = 1; v.txSubCode = tone; }
                long lastRx = 0;
                while (satThread == Thread.currentThread() && System.currentTimeMillis() < P.los + 30_000) {
                    long now = System.currentTimeMillis();
                    com.macdmr.radio.ham.SatTrack.Look l = com.macdmr.radio.ham.SatTrack.look(E, now, me.getLatitude(), me.getLongitude(), me.hasAltitude() ? me.getAltitude() : 0);
                    long rx = com.macdmr.radio.ham.SatTrack.downlink(sat.downHz, l.rangeRateKmS), tx = com.macdmr.radio.ham.SatTrack.uplink(sat.upHz, l.rangeRateKmS);
                    st.look = l; st.rxHz = rx; st.txHz = tx; st.waiting = false;
                    // 2 kHz is well inside an FM channel; fewer retunes, fewer gaps. While an SSTV picture is coming in, up to
                    // 5 kHz is tolerated so the retune gap does not tear the picture.
                    boolean picture = sat.mode == com.macdmr.radio.ham.Satellites.SSTV && packet != null && packet.sstvStatus() != null && packet.sstvStatus().startsWith("receiving");
                    if (!pttDown && (!tuned || Math.abs(rx - lastRx) >= (picture ? 5000 : 2000))) {
                        v.rxFreq = (int) (Math.round(rx / 500.0) * 500); v.txFreq = listenOnly ? v.rxFreq : (int) (Math.round(tx / 500.0) * 500);
                        applyVfo(v, false); lastRx = rx; tuned = true; lastSatRetune = System.currentTimeMillis();
                    }
                    if (tuned && !started && packet != null) {         // the pass's job: decode APRS or SSTV on the moving frequency
                        started = true;
                        if (sat.mode == com.macdmr.radio.ham.Satellites.APRS) { aprsMonitorHz = (int) sat.downHz; packet.setMonitoring(true); pushModes(); }
                        else if (sat.mode == com.macdmr.radio.ham.Satellites.SSTV) { packet.setSstvForceContinuous(true); packet.setSstvRx(true); pushModes(); }   // ISS events are Robot 36: no VIS needed, partial pictures kept
                        if (sat.mode != com.macdmr.radio.ham.Satellites.VOICE && satRecord()) {      // the whole pass, so a missed picture can be decoded again later
                            try { WavRecorder r = new WavRecorder(sat.name); r.start("pass"); passRec = r; history("pkt", "\uD83D\uDEF0 Recording the pass (" + r.file().getName() + ")"); }
                            catch (Throwable x) { log("pass record: " + x); }
                        }
                    }
                    Thread.sleep(2000);
                }
                natural = satThread == Thread.currentThread();      // the pass ended (not stopped)
            } catch (InterruptedException ignored) {
            } catch (Throwable x) { log("sat track: " + x); }
            finally {
                finishPassRecording();
                if (started && packet != null) {
                    if (sat.mode == com.macdmr.radio.ham.Satellites.APRS) packet.setMonitoring(false);
                    else if (sat.mode == com.macdmr.radio.ham.Satellites.SSTV) { packet.setSstvRx(false); packet.setSstvForceContinuous(false); }
                }
                if (tuned && vfo != null && sat.name.equals(vfo.name)) exitVfo();
                if (satState == st) satState = null;
                if (natural && sat.catalog == com.macdmr.radio.ham.Satellites.ISS_CATALOG && issRepeatMode == sat.mode)   // following every pass: the next one
                    ui.postDelayed(() -> { if (issRepeatMode == sat.mode && satState == null) { String e = startIssMode(sat.mode); if (e != null) { history("pkt", "\uD83D\uDEF0 " + e); setIssRepeat(-1); } } }, 5000);
                pushModes();
                history("pkt", "\uD83D\uDEF0 " + sat.name + " pass over");
            }
        }, "sat-track");
        satThread = t; t.setDaemon(true); t.start();
        return null;
    }

    // ---- nets (net control) ----
    private com.macdmr.radio.ham.NetStore nets;
    public com.macdmr.radio.ham.NetStore nets() { if (nets == null) nets = new com.macdmr.radio.ham.NetStore(this); return nets; }
    private NetWatch netWatch;
    /** Nets near you (NetLogger + what the radio heard) and their reminders. */
    public NetWatch netWatch() { if (netWatch == null) netWatch = new NetWatch(this); return netWatch; }

    // ---- logbook ----
    private com.macdmr.radio.ham.Logbook logbook;
    public com.macdmr.radio.ham.Logbook logbook() { if (logbook == null) logbook = new com.macdmr.radio.ham.Logbook(this); return logbook; }
    public boolean logHeard() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("logHeard", true); }
    public void setLogHeard(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("logHeard", on).apply(); }
    void logHeardStation(String call, String mode, String note) {
        if (!logHeard()) return;
        ChannelStore.Channel c = current;
        try { logbook().heard(call, c != null ? c.rxFreq : 0, mode, null, note); enrichCall(call, null); } catch (Throwable t) { log("logbook: " + t); }
    }

    // ---- fox hunt: the hunter's marks, the fox beacon and the off-frequency trick ----
    private final com.macdmr.radio.ham.FoxHunt fox = new com.macdmr.radio.ham.FoxHunt();
    public com.macdmr.radio.ham.FoxHunt fox() { return fox; }
    private volatile Thread foxThread; private volatile int foxChannel = -1;
    public boolean foxTxOn() { return foxThread != null; }
    /** Start or stop the fox beacon (5 s of beeps every minute on the current channel). Returns a message. */
    public String setFoxTx(boolean on) {
        Thread t = foxThread; foxThread = null; if (t != null) t.interrupt();
        if (!on) { if (t != null) history("pkt", "\uD83E\uDD8A Fox beacon off"); pushModes(); return "Fox beacon off"; }
        ChannelStore.Channel ch = current;
        if (ch == null || ch.isDigital()) return "The fox beacon needs an analog FM channel";
        if (txInhibited()) return "This channel is receive-only";
        foxChannel = ch.number;
        final Thread nt = new Thread(() -> {
            while (foxThread == Thread.currentThread()) {
                ChannelStore.Channel c = current;
                if (c == null || c.number != foxChannel) { foxThread = null; history("pkt", "\uD83E\uDD8A Fox beacon stopped: the channel changed"); pushModes(); break; }
                if (isReady() && !pttDown && !scanner.isScanning() && !txInhibited()) {
                    String id = amateurBand(c.txFreq) && packet != null && packet.callsignSet() ? packet.callsign() : null;
                    transmitPcm(com.macdmr.radio.ham.FoxHunt.beacon(5, id), "fox beacon", null);
                }
                try { Thread.sleep(60_000); } catch (InterruptedException e) { break; }
            }
        }, "fox-beacon");
        foxThread = nt; nt.setDaemon(true); nt.start();
        history("pkt", "\uD83E\uDD8A Fox beacon on " + ch.name + " (every minute)");
        pushModes();
        return "Fox beacon on " + ch.name + ": 5 s of beeps every minute";
    }
    private ChannelStore.Channel foxHome;
    /** Listen {@code hz} above the channel (0 = back on it): close to the fox, an off-frequency signal is weaker and easier to judge. Error or null. */
    public String foxOffset(int hz) {
        if (hz == 0) { if (foxHome != null) { foxHome = null; exitVfo(); } return null; }
        ChannelStore.Channel base = foxHome != null ? foxHome : current;
        if (base == null || base.isDigital()) return "Needs an analog channel";
        if (foxHome == null) foxHome = base;
        ChannelStore.Channel v = vfoCopy(base); v.rxFreq = base.rxFreq + hz; v.txFreq = base.txFreq; v.name = base.name + " +" + (hz / 1000) + " kHz";
        applyVfo(v, false);
        return null;
    }
    public com.macdmr.radio.ham.Parrot parrot() { return parrot; }
    /** Turn the parrot on for the current channel (or off). Returns what happened, for a toast. */
    public String setParrot(boolean on) {
        if (!on) { if (parrot.enabled()) history("pkt", "\uD83E\uDD9C Parrot off (" + parrot.played() + " repeated)"); parrot.setEnabled(false); parrotChannel = -1; pushModes(); return "Parrot off"; }
        ChannelStore.Channel ch = current;
        if (ch == null || ch.isDigital()) return "The parrot needs an analog FM channel";
        if (txInhibited()) return "This channel is receive-only";
        if (!softSq.isEnabled() && hardwareSq(ch) == 0) return "Turn the channel's squelch up (or Soft SQ on) first: with squelch 0 the parrot would repeat static";
        parrotChannel = ch.number; modeSince = System.currentTimeMillis(); parrot.setEnabled(true);
        history("pkt", "\uD83E\uDD9C Parrot on: repeating what is heard on " + ch.name);
        pushModes();
        return "Parrot on " + ch.name + ": transmissions are repeated a second after they end";
    }
    /** Inside the region's amateur bands (Device → Region; before one is chosen, the US 2 m, 1.25 m and 70 cm bands). */
    public static boolean amateurBand(int hz) { return com.macdmr.radio.region.Region.cached().amateur(hz); }
    public PacketEngine packet() { return packet; }
    private final DiagReceiver diag = new DiagReceiver();
    private RadioIdStore radioId;
    public RadioIdStore radioId() { return radioId; }

    /** A talkgroup's contact name, or "TG 91". */
    public String talkgroupName(int tg) { String n = store.contactName(tg, 1); return n != null && !n.isEmpty() ? n : "TG " + tg; }
    /** Conversations with a name for everyone: contacts, then RadioID for radios, "TG n" for talkgroups. */
    public java.util.List<ChannelStore.Conversation> conversations() {
        java.util.List<ChannelStore.Conversation> l = store.conversations();
        for (ChannelStore.Conversation cv : l) if (cv.name == null || cv.name.isEmpty()) cv.name = cv.type == 1 ? talkgroupName(cv.peer) : cv.type == 0 ? callerName(cv.peer) : "";
        for (ChannelStore.Conversation cv : store.pageConversations()) {
            if (cv.threadKey != null && cv.threadKey.startsWith("c:")) {
                try { ChannelStore.Contact ct = store.contactById(Long.parseLong(cv.threadKey.substring(2))); if (ct != null && ct.name != null && !ct.name.isEmpty()) cv.name = ct.name; } catch (Exception ignored) { }
            } else if ("everyone".equals(cv.threadKey)) cv.name = "M17 everyone";
            else if (cv.threadKey != null && cv.threadKey.startsWith("call:")) cv.name = cv.threadKey.substring(5);
            l.add(cv);
        }
        l.sort((a, b) -> Long.compare(b.lastTs, a.lastTs));
        return l;
    }

    /** Contacts first, then the RadioID register: "Name" or "W1AW Hiram"; empty when unknown. */
    public String callerName(int id) {
        if (id <= 0) return "";
        String n = store.contactName(id, 0);
        if (n != null && !n.isEmpty()) return n;
        RadioIdStore.Record r = radioId != null ? radioId.lookup(id) : null;
        return r != null ? r.display() : "";
    }

    // ---- voice announcements + keyword alerts ----
    private android.speech.tts.TextToSpeech tts; private volatile boolean ttsReady;
    public boolean voiceOn() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("voice", false); }
    public void setVoiceOn(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("voice", on).apply(); if (on) say("Voice announcements on"); }
    /** True while the speech queue holds only a channel announcement, which a newer one may cut off. */
    private volatile boolean speakingChannel;
    private String pendingChannelSay;
    private final Runnable channelSay = () -> {
        String t = pendingChannelSay; pendingChannelSay = null;
        if (t == null || !voiceOn()) return;
        try {
            if (tts == null || !ttsReady) { say(t); speakingChannel = true; return; }
            boolean cut = speakingChannel || !tts.isSpeaking();      // never cut off a caller name or a keyword alert
            log("voice: " + t + (cut ? "" : " (queued behind other speech)"));
            tts.speak(t, cut ? android.speech.tts.TextToSpeech.QUEUE_FLUSH : android.speech.tts.TextToSpeech.QUEUE_ADD, null, "channel");
            speakingChannel = true;
        } catch (Throwable e) { log("tts: " + e); }
    };
    /**
     * Channel announcement: waits 350 ms so quick channel changes announce only where you land, and replaces a
     * channel announcement that is still playing instead of queueing behind it.
     */
    public void sayChannel(final String text) {
        pendingChannelSay = text;
        ui.removeCallbacks(channelSay); ui.postDelayed(channelSay, 350);
    }

    private static String channelWords(ChannelStore.Channel ch) {
        return "Channel " + ch.number + ", " + ch.name.replaceAll("[_]+", " ").replaceAll("\\((UHF|VHF)\\)", "").trim() + (ch.isM17() ? ", M 17" : ch.isDigital() ? ", digital" : "");
    }

    public void say(final String text) {
        speakingChannel = false;
        try {
            if (tts == null) { tts = new android.speech.tts.TextToSpeech(this, st -> { ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS; if (ttsReady) { tts.setLanguage(Locale.US); tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "say"); } }); return; }
            if (ttsReady) tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "say");
        } catch (Throwable t) { log("tts: " + t); }
    }
    public String keywords() { return getSharedPreferences("radio", MODE_PRIVATE).getString("keywords", ""); }
    public void setKeywords(String k) { getSharedPreferences("radio", MODE_PRIVATE).edit().putString("keywords", k == null ? "" : k.trim()).apply(); }
    private final java.util.HashMap<String, Long> keywordLastHit = new java.util.HashMap<>();
    private void checkKeywords(String sentence) {
        try {
            String raw = keywords(); if (raw.isEmpty() || sentence == null) return;
            String low = " " + sentence.toLowerCase(Locale.US).replaceAll("[^a-z0-9' ]+", " ") + " ";
            long now = System.currentTimeMillis();
            for (String w : raw.toLowerCase(Locale.US).split("[,;\n]+")) {
                w = w.trim(); if (w.isEmpty() || !low.contains(" " + w + " ")) continue;
                synchronized (keywordLastHit) { Long last = keywordLastHit.get(w); if (last != null && now - last < 15000) continue; keywordLastHit.put(w, now); }
                final String word = w, chan = current != null ? current.name : "the radio";
                try { android.os.Vibrator vib = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE); if (vib != null) vib.vibrate(android.os.VibrationEffect.createWaveform(new long[] { 0, 250, 120, 250, 120, 400 }, -1)); } catch (Throwable ignored) { }
                history("alert", "\uD83D\uDD14 Keyword \"" + word + "\" heard");
                notifyUser("Keyword \"" + word + "\" heard on " + chan, sentence, true);
                if (voiceOn()) say("Keyword " + word + " on " + chan);
                return;
            }
        } catch (Throwable t) { log("keywords: " + t); }
    }
    private volatile long modeSince;
    /** When the mode that has the radio (APRS monitor, KISS, SSTV, parrot) started; 0 if none. */
    public long modeSince() { return packetActive() ? modeSince : 0; }
    public void markModeStart() { modeSince = System.currentTimeMillis(); }
    // Keep the CPU awake while a mode runs or a satellite pass is awaited: the beacon / telemetry timer, the pass
    // wait and the decoders are plain threads and timers, which stop in deep sleep (screen off in the car). Until
    // 2026-10-20 the phone only stayed awake by accident, through the audio mixer's wake lock while RX audio played.
    private android.os.PowerManager.WakeLock modeWake;
    // On the Atom XL the CPU is kept awake while SignalDeck owns the radio: with the reception line stuck, receptions come
    // from timed signal readings, which stop in deep sleep (2026-10-29: a 10 s timer ran 5 minutes late with the screen
    // off, and 6 of 10 test transmissions were never heard).
    void updateModeWake() {
        boolean owned = agoldBridge() && isReady();
        boolean want = packetActive() || satState != null || owned;
        try {
            if (modeWake == null) { android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE); modeWake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "signaldeck:mode"); modeWake.setReferenceCounted(false); }
            if (want && !modeWake.isHeld()) { modeWake.acquire(); log(owned ? "wake lock on (the radio is owned)" : "wake lock on (a mode is running)"); exemptFromDoze(); }
            else if (!want && modeWake.isHeld()) { modeWake.release(); log("wake lock off"); }
        } catch (Throwable t) { log("wake lock: " + t); }
    }
    /** Doze ignores the wake locks of apps that are not exempt (phone unplugged, still and dark, e.g. on the nightstand
     *  for a 4 a.m. ISS pass): put the app on the battery-optimisation allowlist with root, once. */
    private volatile boolean dozeChecked;
    private void exemptFromDoze() {
        if (dozeChecked) return; dozeChecked = true;
        worker.execute(() -> {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
                RootSetup.run("dumpsys deviceidle whitelist +" + getPackageName());
                log(pm.isIgnoringBatteryOptimizations(getPackageName()) ? "battery optimisation: exempt (Doze no longer pauses modes)" : "battery optimisation: could not exempt the app");
            } catch (Throwable t) { log("doze exemption: " + t); }
        });
    }
    public boolean packetActive() { return parrot.enabled() || foxTxOn() || (packet != null && (packet.monitoring() || packet.kiss().isActive() || packet.sstvRx())); }

    /** SSTV receive: on the SSTV calling frequency (through the VFO) or on the current channel. */
    public String sstvMonitor(boolean on, boolean park) {
        if (packet == null) return "not ready";
        if (on) {
            if (park && !profile.tunable(PacketEngine.SSTV_FREQ_HZ)) return "This radio tunes " + profile.bandsText() + ", not 144.500 MHz: use Receive here";
            if (park) {
                ChannelStore.Channel a = new ChannelStore.Channel();
                a.type = 1; a.rxFreq = a.txFreq = PacketEngine.SSTV_FREQ_HZ; a.band = 1; a.power = 1; a.name = "SSTV 144.500"; dataModeSquelch(a);
                applyVfo(a, false);
            }
            if (!packet.sstvRx()) modeSince = System.currentTimeMillis();
            packet.setSstvRx(true); pushModes();
            return park ? "SSTV receive on 144.500 MHz" : "SSTV receive on this channel";
        }
        packet.setSstvRx(false);
        if (vfo != null && vfo.rxFreq == PacketEngine.SSTV_FREQ_HZ) exitVfo();
        pushModes();
        return "SSTV receive off";
    }

    private final PacketEngine.Host packetHost = new PacketEngine.Host() {
        @Override public android.content.Context context() { return RadioService.this; }
        @Override public boolean analogRx() { ChannelStore.Channel c = current; return c != null && !c.isDigital(); }
        @Override public int rxFreqHz() { ChannelStore.Channel c = current; return c == null ? 0 : c.rxFreq; }
        @Override public int aprsHz() { return RadioService.this.aprsHz(); }
        @Override public int channelNumber() { ChannelStore.Channel c = current; return c == null ? 0 : c.number; }
        @Override public String channelName() { ChannelStore.Channel c = current; return c == null ? "" : c.name; }
        @Override public boolean txBusy() { return pttDown; }
        @Override public void transmitPcm(short[] pcm, String label, Runnable onDone) { RadioService.this.transmitPcm(pcm, label, onDone); }
        @Override public void transmitPcmLong(short[] pcm, String label, int maxMs, Runnable onDone) { RadioService.this.transmitPcm(pcm, label, onDone, maxMs); }
        @Override public boolean tonePath() { return RadioService.this.tonePath(); }
        @Override public String transmitTones(java.util.List<com.macdmr.radio.ham.ToneQueue.Pair> pairs, int amplitude, String label, Runnable done) { return RadioService.this.transmitTones(pairs, amplitude, label, done); }
        @Override public String longTxWait(int ms) { return thermal.gate(ms); }
        @Override public android.location.Location location() { return lastKnownLocation(); }
        @Override public void stationHeard(String call, double lat, double lon, long ts, String detail) { ChannelStore.Contact pc = store.contactByCallsign(call); if (pc != null) store.contactHeard(pc.id, ts, "APRS", detail, lat, lon); }
        @Override public void sentTo(String call, String source, String detail) { ChannelStore.Contact pc = store.contactByCallsign(call); if (pc != null) store.contactSent(pc.id, System.currentTimeMillis(), source, detail); }
        @Override public void event(String line) { history("pkt", line); }
        @Override public void notifyUser(String title, String text, boolean alert) { RadioService.this.notifyUser(title, text, alert); }
        @Override public int rssiDbm() { return rssi; }
        @Override public void wantLocation(boolean on) { ui.post(() -> aprsLocation(on)); }
        @Override public void logHeard(String call, String mode, String note) { RadioService.this.logHeardStation(call, mode, note); }
        @Override public void identified() { markIdentified(); }
        @Override public void dtmfSequence(String digits) { if (packet != null) remote.handle(digits, packet.remotePin(), packet.remoteOn(), System.currentTimeMillis()); }
    };

    // ---- DTMF remote control: PIN + command digit, answered on the channel ----
    private final com.macdmr.radio.ham.VoiceReply voice = new com.macdmr.radio.ham.VoiceReply(this);
    private final com.macdmr.radio.ham.DtmfRemote remote = new com.macdmr.radio.ham.DtmfRemote(new com.macdmr.radio.ham.DtmfRemote.Actions() {
        @Override public void speakPosition() {
            android.location.Location l = lastKnownLocation();
            if (l == null) { voiceTx("No position fix."); return; }
            String place = "";
            try { java.util.List<android.location.Address> a = new android.location.Geocoder(RadioService.this, Locale.US).getFromLocation(l.getLatitude(), l.getLongitude(), 1); if (a != null && !a.isEmpty() && a.get(0).getLocality() != null) place = "Near " + a.get(0).getLocality() + (a.get(0).getAdminArea() != null ? ", " + a.get(0).getAdminArea() : "") + ". "; } catch (Throwable ignored) { }
            voiceTx("Position. " + place + String.format(Locale.US, "%.3f %s, %.3f %s.", Math.abs(l.getLatitude()), l.getLatitude() >= 0 ? "north" : "south", Math.abs(l.getLongitude()), l.getLongitude() >= 0 ? "east" : "west"));
        }
        @Override public void speakStatus() {
            android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int level = b == null ? -1 : b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) * 100 / Math.max(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
            ChannelStore.Channel c = current;
            voiceTx("Status. " + (level >= 0 ? "Battery " + level + " percent. " : "") + (c != null ? "Channel " + c.name + ". " : "") + (rssi != -999 ? "Signal minus " + Math.abs(rssi) + " dBm." : ""));
        }
        @Override public void beacon() { if (packet != null) { String e = packet.sendBeacon(); if (e != null) history("pkt", "\u26D4 Remote beacon not sent: " + e); } }
        @Override public void parrot(boolean on) { ui.post(() -> history("pkt", "\uD83D\uDCDF " + setParrot(on))); }
        @Override public void morseId() { if (packet != null) { String e = packet.sendCwId(); if (e != null) history("pkt", "\u26D4 " + e); } }
        @Override public void event(String line) { history("pkt", line); }
    });
    /** Speak {@code text} on the channel 1.5 s after the caller unkeys; on amateur channels the Morse ID follows. */
    private void voiceTx(String text) {
        voice.say(text, pcm -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            ChannelStore.Channel c = current;
            short[] id = c != null && amateurBand(c.txFreq) && packet != null && packet.callsignSet() ? com.macdmr.radio.coverage.Morse.pcm(packet.callsign(), 20, 800) : new short[0];
            int lead = 12000, max = 8000 * 28;                     // 1.5 s: the radio passes audio only ~1.5 s after keying
            int voiceN = Math.min(pcm.length, max - lead - id.length);
            short[] out = new short[lead + voiceN + id.length];
            System.arraycopy(pcm, 0, out, lead, voiceN); System.arraycopy(id, 0, out, lead + voiceN, id.length);
            transmitPcm(out, "remote reply", () -> history("pkt", "\uD83D\uDDE3 Remote reply sent: " + text));
        });
    }
    private android.location.LocationListener aprsGps;
    private void aprsLocation(boolean on) {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        if (on && aprsGps == null) {
            aprsGps = l -> { if (packet != null) packet.onLocation(l); };
            try { lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 5000, 10, aprsGps, android.os.Looper.getMainLooper()); log("SmartBeaconing: GPS on"); } catch (Throwable t) { log("SmartBeaconing GPS: " + t); aprsGps = null; }
        } else if (!on && aprsGps != null) { try { lm.removeUpdates(aprsGps); } catch (Throwable ignored) { } aprsGps = null; }
    }

    private volatile long lastSatRetune;
    /** Wait after a retune before keying: the MCU's channel change completes about 3 s later and would cut the transmission. */
    static final long RETUNE_SETTLE_MS = 4000;
    /** Beacon through the space station's digipeater (path ARISS) on the ISS APRS frequency, corrected for Doppler.
     *  Only while the ISS is above the horizon: during a tracked ISS APRS pass it uses that tuning, otherwise it tunes
     *  for the moment, beacons and comes back. */
    public String issBeacon() {
        if (packet == null) return "not ready";
        if (!packet.callsignSet()) return "Set your callsign first (PKT \u2192 Settings)";
        final android.location.Location me = lastKnownLocation();
        if (me == null) return "No position fix yet";
        SatState st = satState;
        if (st != null && st.mode == com.macdmr.radio.ham.Satellites.APRS && !st.waiting && st.look != null) {
            if (st.look.el < 0) return "The ISS is below the horizon";
            new Thread(() -> {
                try { long wait = RETUNE_SETTLE_MS - (System.currentTimeMillis() - lastSatRetune); if (wait > 0) Thread.sleep(wait); } catch (InterruptedException ignored) { }
                String e = packet.sendBeaconVia(new String[] { "ARISS" });
                if (e != null) history("pkt", "\u26D4 ISS beacon not sent: " + e);
            }, "iss-beacon").start();
            return "Beaconing via the ISS (ARISS)";
        }
        com.macdmr.radio.ham.Sgp4 el = satellites().elements().get(com.macdmr.radio.ham.Satellites.ISS_CATALOG);
        if (el == null) return "No ISS orbit yet: open Satellite passes once to download it";
        long now = System.currentTimeMillis();
        com.macdmr.radio.ham.SatTrack.Look l = com.macdmr.radio.ham.SatTrack.look(el, now, me.getLatitude(), me.getLongitude(), me.hasAltitude() ? me.getAltitude() : 0);
        if (l.el < 5) {
            String next = nextIssPassText(el, me);
            return "The ISS is not in view" + (next != null ? ": next pass " + next : "");
        }
        long hz = com.macdmr.radio.ham.Satellites.issHz(this, "issAprs", com.macdmr.radio.ham.Satellites.ISS_APRS_HZ);
        ChannelStore.Channel a = new ChannelStore.Channel(); a.type = 1; a.band = 1; a.power = 1; a.name = "ISS APRS"; dataModeSquelch(a);
        // the beacon goes out about 5 s from now: aim for the Doppler then
        com.macdmr.radio.ham.SatTrack.Look l5 = com.macdmr.radio.ham.SatTrack.look(el, now + 5000, me.getLatitude(), me.getLongitude(), me.hasAltitude() ? me.getAltitude() : 0);
        a.rxFreq = (int) (Math.round(com.macdmr.radio.ham.SatTrack.downlink(hz, l5.rangeRateKmS) / 500.0) * 500);
        a.txFreq = (int) (Math.round(com.macdmr.radio.ham.SatTrack.uplink(hz, l5.rangeRateKmS) / 500.0) * 500);
        applyVfo(a, false);
        final int rxHz = a.rxFreq;
        new Thread(() -> {
            try { Thread.sleep(RETUNE_SETTLE_MS); } catch (InterruptedException ignored) { }       // the retune settles before keying
            String e = packet.sendBeaconVia(new String[] { "ARISS" });
            if (e != null) history("pkt", "\u26D4 ISS beacon not sent: " + e);
            try { Thread.sleep(12000); } catch (InterruptedException ignored) { }
            if (vfo != null && vfo.rxFreq == rxHz && satState == null) exitVfo();
        }, "iss-beacon").start();
        return String.format(Locale.US, "ISS at %.0f\u00B0: beaconing on %.4f MHz (Doppler-corrected) via ARISS", l.el, a.txFreq / 1e6);
    }

    /** The next ISS pass above 10\u00B0 as "Tue 15:42 (highest 48\u00B0)", or null. */
    public String nextIssPassText(com.macdmr.radio.ham.Sgp4 el, android.location.Location me) {
        java.util.List<com.macdmr.radio.ham.SatTrack.Pass> ps = com.macdmr.radio.ham.SatTrack.passes(el, System.currentTimeMillis(), 24, me.getLatitude(), me.getLongitude(), me.hasAltitude() ? me.getAltitude() : 0, 10);
        for (com.macdmr.radio.ham.SatTrack.Pass p : ps) if (p.aos > System.currentTimeMillis())
            return new java.text.SimpleDateFormat("EEE HH:mm", Locale.US).format(new java.util.Date(p.aos)) + String.format(Locale.US, " (highest %.0f\u00B0)", p.maxEl);
        return null;
    }

    /** ISS APRS or ISS SSTV: track the pass that is up now, or the next one above 10\u00B0 in the next day; the pass page
     *  shows the countdown and the decoding. Returns an error, or null when tracking starts. */
    public String startIssMode(int mode) {
        android.location.Location me = lastKnownLocation();
        if (me == null) return "No position fix: turn on location";
        com.macdmr.radio.ham.Sgp4 el = satellites().elements().get(com.macdmr.radio.ham.Satellites.ISS_CATALOG);
        if (el == null) return "No ISS orbit yet: open Satellite passes once to download it";
        com.macdmr.radio.ham.Satellites.Sat sat = com.macdmr.radio.ham.Satellites.byKey(this, mode == com.macdmr.radio.ham.Satellites.SSTV ? "iss-sstv" : "iss-aprs");
        long now = System.currentTimeMillis();
        for (com.macdmr.radio.ham.SatTrack.Pass p : com.macdmr.radio.ham.SatTrack.passes(el, now - 15 * 60_000L, 24, me.getLatitude(), me.getLongitude(), me.hasAltitude() ? me.getAltitude() : 0, 10))
            if (p.los > now + 60_000) return trackSat(sat, el, p);
        return "No ISS pass above 10\u00B0 in the next 24 hours";
    }
    /** Keep tracking the ISS pass after pass (for an SSTV event or a day of APRS): restarted at each LOS. */
    private volatile int issRepeatMode = -1;
    public void setIssRepeat(int mode) { issRepeatMode = mode; getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("issRepeat", mode).apply(); }   // kept across restarts (an install during an SSTV event must not end it)
    /** After a restart: carry on following the ISS pass after pass if it was on. */
    private void resumeIssRepeat() {
        final int m = getSharedPreferences("radio", MODE_PRIVATE).getInt("issRepeat", -1);
        if (m < 0 || satState != null) return;
        ui.postDelayed(() -> {
            if (satState != null) return;
            String e = startIssMode(m);
            if (e == null) { issRepeatMode = m; history("pkt", "\uD83D\uDEF0 Following the ISS again after a restart (" + (m == com.macdmr.radio.ham.Satellites.SSTV ? "SSTV" : "APRS") + ")"); }
            else { history("pkt", "\uD83D\uDEF0 Could not resume ISS tracking: " + e); }
        }, 4000);
    }
    public int issRepeat() { return issRepeatMode; }

    /** Hands-free one-shot transmission: key, play the PCM through the TX path, release, resume RX. */
    public void transmitPcm(final short[] pcm, final String label, final Runnable onDone) { transmitPcm(pcm, label, onDone, false); }
    /** liftLimit is the one bench tone that may run to 40 s. It uses engine.ptt, so the 28 s voice watchdog is not in this path. */
    private void transmitPcm(final short[] pcm, final String label, final Runnable onDone, final boolean liftLimit) { transmitPcm(pcm, label, onDone, liftLimit ? 40000 : 28500); }

    /** The cool-down guard for transmissions longer than 30 s (the bench's heat table, 2026-10-25). */
    public final ThermalGuard thermal = new ThermalGuard();
    /** The longest one transmission may run: the module's own 120 s time-out less a margin (FW19 ceiling 120.3 s, stock firmware 120 s start-up value, `setTot(120)` since 2026-10-25). */
    public static final int TX_MAX_MS = 118_000;

    /**
     * Key, play 8 kHz PCM, release. {@code maxMs} is the caller's limit: 28.5 s for everything that has not been
     * measured longer (the voice watchdog stays 28 s), up to {@link #TX_MAX_MS} for the long SSTV modes, which also
     * pass the cool-down guard first.
     */
    private void transmitPcm(final short[] pcm, final String label, final Runnable onDone, final int maxMs) {
        worker.execute(() -> {
            try {
                if (!isReady()) { log("TX " + label + " skipped: radio not ready"); return; }
                if (txInhibited()) { log("TX " + label + " blocked: receive-only channel"); history("warn", "\u26D4 TX blocked on a receive-only channel"); return; }
                if (pttDown) { log("TX " + label + " skipped: PTT busy"); return; }
                int ms = pcm.length / 8;
                int cap = Math.min(maxMs, TX_MAX_MS);
                if (ms > cap) { log("TX " + label + " refused: " + ms + " ms over " + cap); history("warn", "\u26D4 Too long to send (" + label + ", " + Math.round(ms / 1000.0) + " s; the radio allows " + (cap / 1000) + " s)"); return; }
                Thread heatWatch = null;
                if (ms > ThermalGuard.LONG_MS) {
                    String wait = thermal.gate(ms, ThermalGuard.batteryC(this));
                    if (wait != null) { log("TX " + label + " held: " + wait); history("warn", "\u23F3 " + wait); return; }
                    thermal.beforeLong();
                    heatWatch = heatWatchdog(label);
                }
                pttDown = true; txStartedAt = System.currentTimeMillis(); pcmTxLabel = label;   // the history line and the time-out check are about this transmission
                if (audio != null) audio.stopRx();
                engine.ptt(true);
                log("TX " + label + " (" + ms + " ms)");
                try { Thread.sleep(200); if (audio != null) log(audio.playPcm(pcm)); }
                finally { if (heatWatch != null) heatWatch.interrupt(); unkey(); pttDown = false; thermal.after(ms); if (rxAudioOn && audio != null && (!agoldBridge() || receiving)) audio.startRx(true); holdAfterTx(); }
                if (ms > ThermalGuard.LONG_MS) history("tx", "\uD83D\uDCFB Long transmission " + Math.round(ms / 1000.0) + " s (" + label + "); " + thermal.state());
                if (onDone != null) onDone.run();
            } catch (Throwable t) { log("TX " + label + ": " + t); try { engine.ptt(false); } catch (Throwable ignored) { } pttDown = false; }
        });
    }

    public android.location.Location lastKnownLocation() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            android.location.Location me = null;
            for (String p : new String[] { android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER }) {
                try { android.location.Location l = lm.getLastKnownLocation(p); if (l != null && (me == null || l.getTime() > me.getTime())) me = l; } catch (SecurityException ignored) { }
            }
            return me;
        } catch (Throwable t) { return null; }
    }

    private int notifyId = 100;
    public void notifyUser(String title, String text, boolean alert) {
        // messages and weather alerts as conversations, so Android Auto reads them aloud (and offers a reply)
        if (title != null && title.startsWith("APRS-IS message from ")) { CarMessages.aprs(this, title.substring("APRS-IS message from ".length()), text, true); return; }
        if (title != null && title.startsWith("APRS message from ")) { CarMessages.aprs(this, title.substring("APRS message from ".length()), text, false); return; }
        if (alert && title != null && title.startsWith("\u26C8")) { CarMessages.alert(this, title.replaceFirst("^\\S+\\s*", ""), text); return; }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = alert ? "alerts" : "packet";
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(ch, alert ? "Weather alerts" : "APRS messages", alert ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
            b.setSmallIcon(alert ? android.R.drawable.ic_dialog_alert : android.R.drawable.ic_dialog_email).setContentTitle(title).setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
                    .setContentIntent(android.app.PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(notifyId++, b.build());
        } catch (Throwable t) { log("notify: " + t); }
    }

    /** APRS monitoring: park on the region's APRS frequency (through the VFO, so the channel comes back) and decode. */
    public String aprsMonitor(boolean on) { int hz = aprsHz(); return aprsMonitorAt(on, hz, "APRS " + com.macdmr.radio.region.Region.mhz(hz)); }
    /** The terrestrial APRS frequency: the region's (Device → Region) or the user's own; 144.390 before a region is chosen. */
    public int aprsHz() { return com.macdmr.radio.region.Region.current(this).aprsHz; }
    /** This radio tunes the region's APRS frequency (else APRS monitoring is hidden, as on a UHF-only radio). */
    public boolean aprsTunable() { return profile != null && profile.tunable(aprsHz()); }
    private volatile int aprsMonitorHz = PacketEngine.APRS_FREQ_HZ;
    public int aprsMonitorHz() { return aprsMonitorHz; }
    /** APRS monitoring on another frequency (the ISS digipeater on 145.825). */
    public String aprsMonitorAt(boolean on, int hz, String name) {
        if (packet == null) return "not ready";
        if (on && !profile.tunable(hz)) return "This radio tunes " + profile.bandsText() + ", not " + String.format(java.util.Locale.US, "%.3f", hz / 1e6) + " MHz";
        if (on) {
            aprsMonitorHz = hz;
            ChannelStore.Channel a = new ChannelStore.Channel();
            a.type = 1; a.rxFreq = a.txFreq = hz; a.band = 1; a.power = 1; a.name = name; dataModeSquelch(a);
            if (!packet.monitoring()) modeSince = System.currentTimeMillis();
            packet.setMonitoring(true);
            applyVfo(a, false);
            pushModes();
            return "APRS monitoring on " + String.format(Locale.US, "%.3f", hz / 1e6) + " MHz";
        }
        packet.setMonitoring(false);
        if (vfo != null && vfo.rxFreq == aprsMonitorHz) exitVfo();
        pushModes();
        return "APRS monitoring off";
    }

    @Override public void onCreate() {
        super.onCreate();
        startForegroundTyped(false);
        running = this;
        watchCar();
        watchUsbDuringTx();
        watchPlugChanges();
        ProfileRegistry.init(this);
        com.macdmr.radio.region.Region.ensureDefault(this); com.macdmr.radio.region.Region.current(this);   // the region's defaults before anything reads them
        profile = DeviceProfile.detect();
        softSq.toneDetect = profile.subaudible; softSq.setNoiseTable(profile.softSqThresholds, profile.softSqCloseCap);   // the W6: noise only, on its own scale
        ProfileRegistry.maybeFetch();                               // a newer revision or an advisory, at most every 12 h
        store = new ChannelStore(this);
        store.onChange = () -> { ui.removeCallbacks(channelsChanged); ui.postDelayed(channelsChanged, 150); };   // a bulk action is one event
        zoneId = getSharedPreferences("radio", MODE_PRIVATE).getLong("zone", 0);
        if (coverage().enabled()) { coverage.resume(); startForegroundTyped(true); }
        engine = "sa818".equals(profile.protocol) ? new AtModuleEngine(profile, this) : new RadioEngine(profile, this);   // the W6's AT module, else the DMR module
        if (profile.muteLines != null && !profile.appPlayback) {                                 // the module drives the speaker itself: follow Android's media volume, like the stock app
            followMediaVolume();
            try { registerReceiver(mediaVolume, new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")); } catch (Throwable t) { log("volume receiver: " + t); }
        }
        packet = new PacketEngine(packetHost);
        packet.applySettings();
        com.macdmr.radio.car.RadioCallService.register(this);
        // developer bench tools: adb only (the sender must hold DUMP, which the shell has and apps cannot get)
        try {
            android.content.IntentFilter df = new android.content.IntentFilter(DiagReceiver.ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(diag, df, "android.permission.DUMP", null, RECEIVER_EXPORTED); else registerReceiver(diag, df, "android.permission.DUMP", null);
        } catch (Throwable t) { log("diag receiver: " + t); }
        radioId = new RadioIdStore(this);
        if (!getSharedPreferences("radio", MODE_PRIVATE).getBoolean("smsStatusMigrated", false)) {     // texts marked failed before 2026-10-17 were only unacknowledged
            try { store.getWritableDatabase().execSQL("UPDATE messages SET status=3 WHERE status=2 AND outgoing=1"); } catch (Throwable ignored) { }
            getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("smsStatusMigrated", true).apply();
        }
        registerHardwarePtt();
        customKey = new CustomPttKey.Listener(this, down -> { if (pttLearning) return; if (down) { if (isReady()) ptt(true); } else ptt(false); }, this::log);
        if (profile.supported) customKey.start();
        try { store.migrateNets(this); } catch (Throwable t) { log("nets migrate: " + t); }
        netWatch().start();
        web = new com.macdmr.radio.web.WebHost(this);
        if (web.enabled()) { String e = web.start(); if (e != null) log("web: " + e); }
    }

    /** SignalDeck Web: the built-in web server (Device → SignalDeck Web). */
    private com.macdmr.radio.web.WebHost web;
    public com.macdmr.radio.web.WebHost web() { return web; }

    @Override public IBinder onBind(Intent i) { return binder; }
    @Override public int onStartCommand(Intent i, int f, int id) {
        if (i != null && i.getBooleanExtra("bringUp", false) && !"ready".equals(link)) bringUp();   // started by BootReceiver
        return START_STICKY;
    }

    public boolean ownAtBoot() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("ownAtBoot", false); }
    public void setOwnAtBoot(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("ownAtBoot", on).apply(); }

    // ---- the phone's hardware PTT key: the framework broadcasts the profile's down/up actions ----
    private android.content.BroadcastReceiver pttKey;
    private void registerHardwarePtt() {
        if (!profile.supported || profile.pttActions == null || profile.pttActions.length < 2) return;
        pttKey = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                String a = i.getAction(); if (a == null) return;
                if (!getSharedPreferences("radio", MODE_PRIVATE).getBoolean("hwPtt", true) || pttLearning) return;
                for (int k = 0; k < profile.pttActions.length; k++) if (a.equals(profile.pttActions[k])) { if (k % 2 == 0) { if (isReady()) ptt(true); } else ptt(false); return; }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        for (String a : profile.pttActions) f.addAction(a);
        try { if (Build.VERSION.SDK_INT >= 33) registerReceiver(pttKey, f, RECEIVER_EXPORTED); else registerReceiver(pttKey, f); } catch (Throwable t) { log("ptt key: " + t); }
    }
    // ---- a PTT key the owner taught it (CustomPttKey): any hardware or Bluetooth button, read with root ----
    private CustomPttKey.Listener customKey;
    private volatile boolean pttLearning;
    /** While a key is being learned no key transmits (the owner is pressing buttons on purpose). */
    public void setPttLearning(boolean on) { pttLearning = on; if (on && pttDown) ptt(false); }
    /** After the learned key or its switch changed. */
    public void customPttChanged() { if (customKey == null) return; if (profile.supported && CustomPttKey.enabled(this)) customKey.start(); else customKey.stop(); }
    /** The activity swallows the learned key's events while the listener does the keying (no volume change in the app). */
    public boolean customPttConsumes(int keyCode) {
        if (keyCode == 0 || customKey == null || !customKey.isRunning()) return false;
        CustomPttKey.Key k = CustomPttKey.saved(this); return k != null && k.androidKey == keyCode;
    }
    public boolean hwPtt() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("hwPtt", true); }
    public void setHwPtt(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("hwPtt", on).apply(); if (!on && pttDown) ptt(false); }

    @Override public void onDestroy() {
        running = null; RadioWidget.refresh(this, null);
        ui.removeCallbacks(moduleWatch);
        try { if (web != null) web.stop(); } catch (Throwable ignored) { }
        try { if (coverage != null) coverage.stop(); } catch (Throwable ignored) { }
        stopBluetoothMic();
        try { if (pttKey != null) unregisterReceiver(pttKey); } catch (Throwable ignored) { }
        try { if (customKey != null) customKey.stop(); } catch (Throwable ignored) { }
        try { if (netWatch != null) netWatch.stop(); } catch (Throwable ignored) { }
        try { unregisterReceiver(diag); } catch (Throwable ignored) { }
        if (profile != null && profile.muteLines != null) try { unregisterReceiver(mediaVolume); } catch (Throwable ignored) { }
        try { if (powerOff != null) unregisterReceiver(powerOff); } catch (Throwable ignored) { }
        try { if (plugSettle != null) unregisterReceiver(plugSettle); } catch (Throwable ignored) { }
        try { if (pttDown) { if (audio != null) audio.stopTx(); engine.ptt(false); } } catch (Throwable ignored) { }
        try { if (audio != null) { audio.stopRx(); } } catch (Throwable ignored) { }
        engine.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---- lifecycle of the link --------------------------------------------------------------------

    private final java.util.concurrent.atomic.AtomicBoolean bringUpRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long bringUpAt;
    static final long BRING_UP_STALE_MS = 90_000;
    /** Bring the radio up: policy (root), stock app off, UART, power, channel, RX audio. Runs on a worker. */
    public void bringUp() {
        if (!profile.supported) { setLink("unsupported"); log("This phone has no device profile yet. Device tab → Generate device report, then share it on the project's GitHub issues."); return; }
        final boolean agold = "agold".equals(profile.protocol);                          // the Atom XL: the radio behind a USB bridge
        if (agold && "ready".equals(link)) { log("bring-up skipped: the radio is already up (a second one would power-cycle the USB bridge)"); return; }
        if (!bringUpRunning.compareAndSet(false, true)) {
            long age = System.currentTimeMillis() - bringUpAt;
            if (age < BRING_UP_STALE_MS) { log("bring-up skipped: one is already running"); return; }
            log("an earlier bring-up has hung for " + age / 1000 + " s: starting a new one");   // one stuck bring-up must not refuse every later Take over
        }
        bringUpAt = System.currentTimeMillis();
        exiting = false;                                                // a bring-up wants the radio: an earlier Exit no longer blocks bridge recovery
        new Thread(() -> {
            boolean pausedStock = false;
            try {
                setLink("root");
                boolean root = RootSetup.haveRoot();
                if (!root) {
                    boolean stockRunning = !RootSetup.run("pidof " + profile.stockApp + " || true").trim().replaceAll("[^0-9]", "").isEmpty();
                    if (stockRunning || !new java.io.File(profile.controlPort).canRead()) {
                        setLink("no root");
                        log("Root was not granted. Open the Magisk app → Superuser → allow \"SignalDeck\", then tap Bring radio up. (Without root the stock app keeps the radio and the UART stays locked.)");
                        return;
                    }
                    log("No root granted, but the UART is reachable and the stock app is not running — continuing");
                } else {
                    log("sepolicy: " + RootSetup.applySepolicy(getFilesDir(), profile));
                    if (agold) {
                        log("tty: set at the bridge's power-up (the port exists only then)");
                        android.content.SharedPreferences rp = getSharedPreferences("radio", MODE_PRIVATE);
                        String prev = AgoldUsb.usbRoutingOff();                  // kept across a crash: the next release restores it
                        if (!rp.contains("agoldUsbRouting")) rp.edit().putString("agoldUsbRouting", prev).apply();
                        log("USB audio routing off while SignalDeck has the radio (was " + prev + ")");
                    }
                    else if (!VendorSerial.load(this, profile.serialLib)) log("tty (stty fallback): " + RootSetup.ensureTty(profile));
                    if (RootSetup.stockAppEnabled(profile)) { log("stock app paused (its icon disappears while this app owns the radio; Device → Release brings it back): " + RootSetup.stockApp(profile, false)); pausedStock = true; Thread.sleep(1500); }
                }
                final boolean sa818 = "sa818".equals(profile.protocol);                      // the W6: no DMR, its own channel table
                final boolean quickChat = "com.chamsion.quickchat".equals(profile.stockApp);      // Xplore 1: a Room database, no module side tables
                final boolean prizeStock = !sa818 && !quickChat && !agold && !flyscaleDmr();                        // the Armors' stock app: DMR ID and TG lists to read
                if (store.count() == 0 && !getSharedPreferences("radio", MODE_PRIVATE).getBoolean("stockImportDeclined", false)) {   // no channels: ask before importing the stock app's (owner, 2026-10-28)
                    stockImportPending = true; log("no channels: asking whether to import the stock app's");
                    ui.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onStockImportOffer(); });
                }
                if (agold && !getSharedPreferences("radio", MODE_PRIVATE).contains("dmrId")) {         // the Atom XL's stock app keeps its DMR ID as device_id (unset = 1)
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("name=\"device_id\" value=\"([0-9]+)\"").matcher(RootSetup.run("cat /data/data/" + profile.stockApp + "/shared_prefs/intercom_prefs.xml 2>/dev/null"));
                    if (m.find() && Integer.parseInt(m.group(1)) > 1) { setLocalIdQuiet(Integer.parseInt(m.group(1))); log("DMR ID from the stock app: " + m.group(1)); }
                }
                if (prizeStock && !getSharedPreferences("radio", MODE_PRIVATE).contains("dmrId")) {     // seed our DMR ID from the stock app's setting
                    String xml = RootSetup.run("cat /data/data/" + profile.stockApp + "/shared_prefs/com.pri.prizeinterphone.data.person.xml");
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("name=\"pref_person_device_id\" value=\"([0-9]+)\"").matcher(xml);
                    if (m.find()) { setLocalIdQuiet(Integer.parseInt(m.group(1))); log("DMR ID from the stock app: " + m.group(1)); }
                }
                if (!getSharedPreferences("radio", MODE_PRIVATE).getBoolean("garbledPktPurged", false)) {   // noise frames logged before the AX.25 plausibility check
                    int k = store.purgeGarbledPacketHistory(); log("removed " + k + " garbled packet lines from the history");
                    getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("garbledPktPurged", true).apply();
                }
                if (prizeStock && !getSharedPreferences("radio", MODE_PRIVATE).getBoolean("tgListsImported", false)) {   // installs imported before TG lists were read
                    StringBuilder err = new StringBuilder();
                    int k = store.repairModuleTgLists(this, profile.stockApp, "default_uhf", err);
                    log(k >= 0 ? "module TG lists imported; " + k + " channels got their list" : "TG list import failed: " + err);
                    if (k >= 0) getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("tgListsImported", true).apply();
                }
                setLink("uart");
                engine.open();
                if (engine.powerOn()) {                       // the stock app pulls the module's power when it is disabled
                    setLink("booting");
                    if (!engine.waitBooted(12000)) log("no boot banner within 12 s — continuing anyway");
                    Thread.sleep(300);
                }
                moduleHandshake();
                selectChannel(store.activeNumber());
                startRxAudio();
                setLink("ready");
                if (agold) {                                  // Agold's App block would stop SignalDeck a few minutes after the screen goes off
                    String blocked = AgoldUsb.appBlockProblem(getPackageName());
                    if (blocked != null) {
                        history("warn", "⚠ Agold App block will stop SignalDeck with the screen off (" + blocked + " on): switch them off in Settings → App block → SignalDeck");
                        if (!getSharedPreferences("radio", MODE_PRIVATE).getBoolean("appBlockOpened", false)) {
                            getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("appBlockOpened", true).apply();
                            log("App block page: " + AgoldUsb.openAppBlock(getPackageName()));
                        }
                    }
                }
                if (agold) { lineFallback = false; synthRx = false; lineMissed = 0; lineRestarts = 0; monRx = false; ui.removeCallbacks(lineCheck); lineCheckTries = 0; ui.postDelayed(lineCheck, LINE_CHECK_MS); ui.removeCallbacks(rxWatchdog); ui.postDelayed(rxWatchdog, 3000); ui.removeCallbacks(windowCheck); ui.postDelayed(windowCheck, AgoldUsb.POWER_UP_EDGES_MS + 300); }
                ui.removeCallbacks(moduleWatch); ui.postDelayed(moduleWatch, 2000);
                resumeIssRepeat();
                Scheduler.arm(this);                                          // scheduled operations (also ends one that ran out while SignalDeck was down)
                if (roaming()) ui.post(() -> startRoaming());
            } catch (Throwable t) {
                log("bring-up failed: " + t);
                if (agold) {                                  // the bridge: never leave it powered, and check it before the stock app runs
                    try { engine.close(); } catch (Throwable ignored) { }
                    restoreUsbRouting();
                    log("hand-back after the failed bring-up: " + AgoldUsb.giveBack(profile, getFilesDir()).replace('\n', ' '));
                } else if (pausedStock) {                     // never leave the phone with no working radio app
                    try { engine.close(); } catch (Throwable ignored) { }
                    log("stock app on again: " + RootSetup.stockApp(profile, true));
                }
                setLink("error: " + t.getMessage());
            } finally { bringUpRunning.set(false); }
        }, "radio-bringup").start();
    }

    /** Exiting: no new start of the service by the app until it is opened again. */
    public static volatile boolean exiting;
    public interface ExitDone { void done(String report); }
    /**
     * Exit SignalDeck: end any transmission and every running mode, stop the audio, then either switch the radio module's
     * power off (restoreStock false) or give the radio back to the stock app (true), close the serial port and stop the
     * service. {@code done} gets what happened (on the main thread).
     */
    public void exit(final boolean restoreStock, final ExitDone done) {
        exiting = true;
        bringUpRunning.set(false);                                       // a Take over after this Exit starts afresh
        new Thread(() -> {
            StringBuilder r = new StringBuilder();
            try { if (pttDown) { if (audio != null) audio.stopTx(); engine.ptt(false); pttDown = false; r.append("transmission ended; "); } } catch (Throwable t) { r.append("unkey: ").append(t.getMessage()).append("; "); }
            try { if (scanner != null && scanner.isScanning()) scanner.stop(); } catch (Throwable ignored) { }
            try { if (scope.running()) scope.stop(); } catch (Throwable ignored) { }
            try { if (ft8Running()) setFt8(false); } catch (Throwable ignored) { }
            try { if (m17Running()) setM17(false); } catch (Throwable ignored) { }
            try { if (parrot.enabled()) parrot.setEnabled(false); } catch (Throwable ignored) { }
            try { if (foxTxOn()) setFoxTx(false); } catch (Throwable ignored) { }
            try { stopSatTrack(); } catch (Throwable ignored) { }
            try { if (audio != null) { audio.stopRx(); rxAudioOn = false; if (restoreStock) audio.releaseForStockApp(); } } catch (Throwable ignored) { }
            if ("agold".equals(profile.protocol)) {      // both buttons give the USB port back (owner, 2026-10-28); Hand back checks the bridge first
                try { engine.close(); } catch (Throwable ignored) { }
                restoreUsbRouting();
                if (restoreStock) {
                    String g = AgoldUsb.giveBack(profile, getFilesDir()); r.append(g.replace('\n', ' ')).append("; ");
                    if (!AgoldUsb.bridgeAnswered(g)) history("warn", "\u26A0 The radio's USB bridge did not answer: restart the phone before opening the stock radio app");
                    if (g.contains(AgoldUsb.STOCK_FIRMWARE_WARNING)) history("warn", "\u26A0 " + AgoldUsb.STOCK_FIRMWARE_DIR + " holds radio firmware files: the stock app loads them into the module when it starts. Remove them unless you mean to.");
                } else r.append(AgoldUsb.bridgeDown()).append("; ");
            } else if (restoreStock) r.append("stock app: ").append(RootSetup.stockApp(profile, true)).append("; ");
            else {
                try { engine.powerOff(); r.append("radio module power off (").append(engine.powerState()).append("); "); }
                catch (Throwable t) { r.append("power off failed: ").append(t.getMessage()).append("; "); }
            }
            try { engine.close(); } catch (Throwable ignored) { }
            setLink("stopped");
            log("exit: " + r);
            final String report = r.toString();
            ui.post(() -> {
                try { stopForeground(true); } catch (Throwable ignored) { }
                stopSelf();
                if (done != null) done.done(report);
            });
        }, "radio-exit").start();
    }

    public void shutDown(final boolean restoreStock) {
        new Thread(() -> {
            final boolean agold = "agold".equals(profile.protocol);
            if (restoreStock && !agold) log("stock app on: " + RootSetup.stockApp(profile, true));
            try { if (audio != null) { audio.stopRx(); audio.releaseForStockApp(); } } catch (Throwable ignored) { }
            engine.close();
            if (agold) restoreUsbRouting();
            if (agold) log(restoreStock ? "hand-back: " + AgoldUsb.giveBack(profile, getFilesDir()).replace('\n', ' ') : AgoldUsb.bridgeDown());
            setLink("stopped");
        }, "radio-shutdown").start();
    }

    /** Why receive audio could not start (null when it did): a custom ROM without the vendor audio class shows here. */
    private volatile String audioError, audioStart;
    public String audioError() { return audioError; }
    public String audioStartResult() { return audioStart; }
    public String audioBackendName() { AudioBackend a = audio; return a == null ? "none" : a.getClass().getSimpleName(); }
    public boolean rxAudioOn() { return rxAudioOn; }

    // ---- DMR call events, for the DMR audio check: reception start/stop and caller reports, with the audio counter ----
    public static final class DmrEvent { public long ts; public String kind; public int type, caller, target; public int cc = -1, slot = -1; public long pcmBytes; public boolean digital; }
    private final java.util.ArrayDeque<DmrEvent> dmrEvents = new java.util.ArrayDeque<>();
    private void dmrEvent(String kind, int type, int caller, int target) { dmrEvent(kind, type, caller, target, -1, -1); }
    private void dmrEvent(String kind, int type, int caller, int target, int cc, int slot) {
        DmrEvent e = new DmrEvent(); e.ts = System.currentTimeMillis(); e.kind = kind; e.type = type; e.caller = caller; e.target = target; e.cc = cc; e.slot = slot; e.pcmBytes = rxAudioBytes();
        ChannelStore.Channel c = current; e.digital = c != null && c.isDigital();
        synchronized (dmrEvents) { dmrEvents.addLast(e); while (dmrEvents.size() > 200) dmrEvents.removeFirst(); }
    }
    public java.util.List<DmrEvent> dmrEventsSince(long ts) { java.util.List<DmrEvent> out = new java.util.ArrayList<>(); synchronized (dmrEvents) { for (DmrEvent e : dmrEvents) if (e.ts >= ts) out.add(e); } return out; }

    private void startRxAudio() {
        try {
            if (audio == null) {
                audio = profile.audio == DeviceProfile.Audio.UART_PCM ? new UartPcmAudio(profile.pcmPort, profile.pcmBaud)
                        : profile.audio == DeviceProfile.Audio.CAMCORDER ? new CamcorderAudio(profile.appPlayback, profile.camcorderGain, profile.gatewayLinesOn, profile.gatewayLinesOff, profile.txAudioScale, (android.media.AudioManager) getSystemService(AUDIO_SERVICE), profile.gatewayVolume) : vendorOrDirectAudio();
                audio.setSink((b, len) -> { if (!injecting) onRxPcm(b, len); });   // a bench injection replaces the live audio while it plays
                audio.setGate(() -> squelchOpen && !muted && !rawHeld() && !(profile.appPlayback && pttDown) && !dataQuiet());   // M17 in play, a data mode without MON, or app playback while keyed: the raw FM stays off the speaker; decoded M17 voice has its own track
            }
            if (agoldBridge() && audio instanceof AlsaDirectAudio)        // the bridge's stream stops after 20-70 s of capture: open it per reception, as the stock app does
                ((AlsaDirectAudio) audio).setOnCaptureEnded(this::captureEnded);
            String r = agoldBridge() ? "USB capture opens at each reception" : audio.startRx(true);
            audioStart = r; log(r); rxAudioOn = true; audioError = r != null && r.startsWith("startRx failed") ? r : null;
        } catch (Throwable t) { audioError = String.valueOf(t) + (t.getCause() != null ? " / " + t.getCause() : ""); log("audio: " + t); }
    }

    /**
     * A vendor-service phone: PrizeTinyService when the ROM has it, else (a custom ROM or GSI) the radio audio straight
     * from the sound card when the profile knows how. Prefs radio/audioPath "direct" or "vendor" (DIAG audiopath) force one.
     */
    private AudioBackend vendorOrDirectAudio() throws Exception {
        boolean cls; try { Class.forName("android.os.PrizeTinyService"); cls = true; } catch (Throwable t) { cls = false; }
        String path = AlsaDirectAudio.choose(getSharedPreferences("radio", MODE_PRIVATE).getString("audioPath", "auto"), cls, profile.directAudio != null);
        if (!"direct".equals(path)) return new TinyServiceAudio();
        log(cls ? "audio: the sound card directly (forced by audioPath)" : "audio: this ROM has no PrizeTinyService: the sound card directly");
        return new AlsaDirectAudio(this, profile.directAudio);
    }
    /** True while the radio audio comes straight from the sound card (a ROM without PrizeTinyService). */
    public boolean directAudio() { return audio instanceof AlsaDirectAudio; }
    /** Bench: choose the audio path (auto / vendor / direct) and restart receive audio on it; never while transmitting. */
    public void diagAudioPath(String path) {
        worker.execute(() -> {
            if (isTransmitting()) { log("audiopath: transmitting, not changed"); return; }
            getSharedPreferences("radio", MODE_PRIVATE).edit().putString("audioPath", path).apply();
            boolean wasOn = rxAudioOn;
            try { if (audio != null) log(audio.stopRx()); } catch (Throwable t) { log("audiopath stop: " + t); }
            audio = null;
            if (wasOn) startRxAudio();
            log("audiopath " + path + ": " + audioBackendName());
        });
    }

    /** Every received 8 kHz stereo (L == R) block: take the left channel, run the squelch, record, meter. */
    private final byte[] mono = new byte[4096];
    private void onRxPcm(byte[] b, int len) {
        rxPcmBytes += len;
        if (scope.running()) return;                                          // sweeping: noise and fragments, not for the speaker or the decoders
        int n = 0;
        for (int i = 0; i + 3 < len && n + 1 < mono.length; i += 4) { mono[n++] = b[i]; mono[n++] = b[i + 1]; }
        boolean open = injecting || current == null || current.isDigital() ? true : softSq.process(mono, n);
        if (BuildConfig.DEBUG && softSq.isEnabled() && System.currentTimeMillis() - lastSqDiag > 2000) { lastSqDiag = System.currentTimeMillis(); log("soft sq: " + softSq.describe() + " gate=" + open); }
        if (open != squelchOpen && softSq.isEnabled()) log("soft squelch " + (open ? "open" : "closed") + (current != null && current.rxType == 1 ? " (CTCSS " + com.macdmr.radio.dsp.ToneTable.ctcssLabel(current.rxSubCode) + ")" : ""));
        if (open != squelchOpen && softSq.isEnabled()) { softGateHistory(open); squelchOpen = open; pushModes(); }   // pages see the gate (activity box, Soft SQ number colour)
        squelchOpen = open;
        boolean held = rawHeld();                  // M17 in play: the raw FM goes nowhere
        if (web != null && open && !muted && !pttDown && !held && !dataQuiet()) web.rxAudio(mono, n);      // M17 sends decoded voice, and a data mode without MON sends nothing
        ClipRecorder clip = this.clip;                                            // listen check: what arrived, before the speaker
        if (clip != null && (!clipOnlyHeard || rxActive()) && !clip.write(mono, n)) this.clip = null;
        int peak = 0;
        if (open) for (int i = 0; i + 1 < n; i += 2) { int v = Math.abs((short) ((mono[i] & 0xFF) | (mono[i + 1] << 8))); if (v > peak) peak = v; }
        if (captionsOn && open) {
            if ("google".equals(captionEngine())) googleFeed(mono, n, peak);
            else if (captions != null && captions.isActive()) captions.feed8kMono(mono, n);
            else if (peak > 400 && System.currentTimeMillis() - captionLastEnd > 1500 && captions != null) { Log.i(TAG, "captions: starting utterance (peak " + peak + ")"); captions.start(); }
        }
        if (packet != null) packet.feed(mono, n, open, packet.monitoring() || packet.sstvRx());   // APRS / SSTV decode below any squelch (noise fails the CRC / VIS); Morse and DTMF wait for a signal
        M17Station m7 = m17;
        if (m7 != null && m7.running() && current != null && !current.isDigital()) m7.feed(mono, n);   // finds the preamble itself, squelch or not
        PagerStation pg = pager;
        if (pg != null && pg.running() && current != null && !current.isDigital()) pg.feed(mono, n);   // pages, below the squelch
        com.macdmr.radio.ham.Ft8Station f8 = ft8;
        if (f8 != null && f8.running() && current != null && !current.isDigital()) f8.feed(mono, n);   // every slot, squelch or not: noise fails FT8's CRC
        if (parrot.enabled()) {
            ChannelStore.Channel pc = current;
            if (pc == null || pc.number != parrotChannel) { ui.post(() -> { if (parrot.enabled()) { setParrot(false); history("pkt", "\uD83E\uDD9C Parrot stopped: the channel changed"); } }); }
            else parrot.feed(mono, n, open && hearing(), System.currentTimeMillis());
        }
        if (open && current != null && !current.isDigital() && !(current.rxFreq >= 162_400_000 && current.rxFreq <= 162_550_000)) {
            toneBank.setEnergyMin(profile.toneEnergyMin);
            toneBank.processMono(mono, n);
            int ti = toneBank.detectedIndex();
            if (ti >= 0 && ti != toneAnnounced) {
                toneAnnounced = ti;
                if (!(current.rxType == 1 && current.rxSubCode == ti)) {          // not already programmed on this channel
                    pendingTone = ti; pendingToneMs = System.currentTimeMillis();
                    history("tone", "\uD83C\uDFB5 CTCSS " + toneBank.detectedLabel() + " Hz heard \u2014 tap this box to use it");
                }
            }
        }
        WavRecorder pr = passRec;                                                 // satellite pass: everything, below the squelch too
        if (pr != null) { try { pr.write(mono, n); } catch (Throwable t) { passRec = null; log("pass record: " + t); } }
        if (recordOn && receiving && open) {
            try {
                if (recorder == null) { recorder = new WavRecorder(current != null ? current.name : null); recorder.start(lastCaller > 0 ? String.valueOf(lastCaller) : null); }
                recorder.write(mono, n);
            } catch (Throwable t) { log("record: " + t); recorder = null; }
        }
        final int p = held ? 0 : peak;
        ui.post(() -> { for (Listener l : listeners) l.onAudioLevel(p); });
        // the intercom bars (phone and web): the spectrum of what the speaker plays; zeros once, when it stops
        float[] sb = !held && open && !muted ? spectrum.feed(mono, n) : null;
        if (sb != null) { final float[] copy = sb.clone(); spectrumZero = false; ui.post(() -> { for (Listener l : listeners) l.onSpectrum(copy); }); }
        else if ((held || !(open && !muted)) && !spectrumZero) { spectrumZero = true; spectrum.reset(); final float[] z = new float[com.macdmr.radio.dsp.Spectrum.BANDS]; ui.post(() -> { for (Listener l : listeners) l.onSpectrum(z); }); }
    }

    private final com.macdmr.radio.dsp.Spectrum spectrum = new com.macdmr.radio.dsp.Spectrum();
    private volatile boolean spectrumZero = true;

    // ---- CTCSS heard on an analog channel: offer it as the channel's tone ----
    private final com.macdmr.radio.dsp.CtcssDecoderBank toneBank = new com.macdmr.radio.dsp.CtcssDecoderBank(8000);
    private volatile int toneAnnounced = -1, pendingTone = -1; private volatile long pendingToneMs;
    public String pendingToneLabel() { return pendingTone >= 0 && System.currentTimeMillis() - pendingToneMs < 180000 ? com.macdmr.radio.dsp.ToneConverter.ctcssLabelAt(pendingTone) : null; }
    public void dismissPendingTone() { pendingTone = -1; }
    /** Program the heard tone as RX and TX CTCSS on the current channel (VFO included). */
    public String applyPendingTone() {
        int t = pendingTone; if (t < 0 || current == null) return "Nothing to apply";
        ChannelStore.Channel ch = current;
        ch.rxType = 1; ch.rxSubCode = t; ch.txType = 1; ch.txSubCode = t;
        pendingTone = -1;
        if (ch.id < 0) applyVfo(ch, true); else { store.update(ch); selectChannel(ch.number); }
        return "CTCSS " + com.macdmr.radio.dsp.ToneConverter.ctcssLabelAt(t) + " Hz set on " + ch.name;
    }

    private void finishRecording() {
        WavRecorder r = recorder; recorder = null;
        if (r == null) return;
        try { java.io.File f = r.stop(); if (f != null) { history("rec", "\uD83C\uDFA7 Recorded " + f.getName()); } } catch (Throwable t) { log("record stop: " + t); }
    }

    // ---- modes ----------------------------------------------------------------------------------------

    public boolean softSqOn() { ChannelStore.Channel c = current; return profile.softSqInModule ? c != null && softSqFor(c) : softSq.isEnabled(); }   // the W6: on in the module, not as an app gate
    public int softSqLevel() { ChannelStore.Channel c = current; return profile.softSqInModule && c != null ? Math.max(1, Math.min(9, c.softSqLevel)) : softSq.level(); }
    public boolean monitorOn() { return monitorOn; }
    public boolean recordOn() { return recordOn; }
    public boolean scanning() { return scanner.isScanning(); }
    /** Soft SQ decides what is heard on the current channel (the module then reports a reception all the time). */
    public boolean softSqActive() { return softSq.isEnabled(); }
    private boolean moved(ChannelStore.Channel ch) { ChannelStore.Channel was = current; return was == null || was.number != ch.number || was.rxFreq != ch.rxFreq || was.type != ch.type; }
    /** The module reports a reception (RECEIVE_START … STOP), whatever Soft SQ decides. */
    public boolean mcuReceiving() { return receiving; }
    /**
     * Radio audio check: the squelch stayed open after MON off. Step 1 re-sends the channel the way QuickChat does
     * (channel, monitor off, squelch); step 2 sends monitor 0. Receive only. Returns what was sent.
     */
    public String squelchCloseAttempt(int step) {
        final ChannelStore.Channel ch = current; if (ch == null || ch.isDigital()) return "no analog channel";
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1); final String[] what = { "" };
        worker.execute(() -> {
            try {
                if (step == 1) {
                    int band = ch.rxFreq < 300_000_000 ? 1 : ch.band;
                    synchronized (tunes) { tunes.addLast(new Object[] { ch, false }); }
                    tunedAt = System.currentTimeMillis(); engine.setAnalog(ch.rxFreq, ch.txFreq, band, ch.power, hardwareSq(ch), ch.rxType, ch.rxSubCode, ch.txType, ch.txSubCode, 8, 2);
                    engine.setMonitor(false); engine.setSquelch(hardwareSq(ch));
                    what[0] = "channel (0x23), monitor 2 (0x2F), squelch " + hardwareSq(ch) + " (0x30)";
                } else { engine.setMonitorRaw(0); what[0] = "monitor 0 (0x2F)"; }
                if (monitorOn && promiscuousCapable()) sendPromiscuousNow(false);
                monitorOn = false;
            } catch (Throwable t) { what[0] = "failed: " + t; }
            done.countDown();
        });
        try { done.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        log("audio check, squelch close step " + step + ": " + what[0]);
        return what[0];
    }

    /** The radio's RECEIVE_START means a real reception: a DMR channel, or an analog one whose squelch is not held fully open (sq 0 reports "receiving" all the time). */
    public boolean rxReportsReception() { ChannelStore.Channel ch = current; return ch == null || ch.isDigital() || (!softSq.isEnabled() && hardwareSq(ch) > 0); }
    public boolean scanHolding() { return scanner.isHolding(); }

    private void pushModes() {
        followDataMode();
        updateModeWake();
        refreshControls();
        final String zn = zoneName();
        ui.post(() -> { for (Listener l : listeners) l.onModes(softSqOn(), softSqLevel(), squelchOpen, monitorOn, recordOn, scanner.isScanning(), scanner.isHolding(), zn); });
    }

    /** Soft SQ on/off for the current analog channel (persisted per channel): on = the module's squelch is held open and the app gates the audio; off = the module's own squelch level. */
    public void setSoftSq(boolean on) {
        ChannelStore.Channel ch = current; if (ch == null || ch.isDigital()) return;
        ch.softSq = on; store.update(ch);
        worker.execute(() -> refreshSquelch(ch));
    }

    public void setSoftSqLevel(int level) {
        level = Math.max(1, Math.min(9, level));                             // 1-9 everywhere (the web slider once sent 0-100)
        ChannelStore.Channel ch = current; if (ch == null) return;
        ch.softSqLevel = level; store.update(ch);
        softSq.configure(softGate(ch), level, ch.rxType, ch.rxSubCode);   // the scan's Soft SQ counts too, or moving the slider would switch its gate off
        if (profile.softSqInModule && softSqFor(ch)) { ui.removeCallbacks(moduleSqWrite); ui.postDelayed(moduleSqWrite, 300); }   // the module's squelch follows the slider (once it rests)
        pushModes();
    }

    private volatile int monRetries; private volatile long monRefusedAt;
    private volatile Runnable pendingPostWrite; private volatile long postWriteToken;   // monitor + squelch after a channel write (globalSquelch profiles)
    /** When the radio last refused MON after the retries (0 = never): the radio audio check reports it. */
    public long monRefusedAt() { return monRefusedAt; }
    public void setMonitor(final boolean on) {
        monitorOn = on; monRetries = 0;
        sendMonitor(on);
        monitorReception(on);
        if (promiscuousCapable()) sendPromiscuous(on);
        pushModes();
    }

    /**
     * Atom XL, analog channels: MON holds the squelch open, so there is always something to hear, but the bridge's reception line reports it
     * only when the line works (in some sessions it stays in its power-up state and reports nothing, 2026-10-28/29). So MON
     * opens the reception itself; an edge that arrives meanwhile is merged ({@link #status}), the signal watchdog leaves it
     * open, and MON off ends it when the line never reported one.
     */
    private volatile boolean monRx;
    private void monitorReception(boolean on) {
        if (!agoldBridge()) return;                                      // on a DMR channel MON admits calls; it opens no audio
        if (on && !receiving && !isTransmitting() && isReady() && current != null && !current.isDigital()) { monRx = true; synthRx = true; log("MON: reception opened without waiting for the reception line"); status(1, RadioEngine.statusName(1)); }
        else if (!on && monRx) {
            monRx = false;
            if (receiving && synthRx) {                                  // MON opened it and the line never reported it: a miss too
                final boolean restart = lineMissedOne(); status(2, RadioEngine.statusName(2));
                if (restart && current != null) worker.execute(() -> { try { powerCycleOrGiveUp(); applyChannelNow(current, current.id > 0); log("bridge restarted and channel rewritten: the reception line looked stuck"); } catch (Throwable t) { log("bridge restart: " + t); } });
            }
        }
    }

    /** The first bench image whose receive handler admits every call while the flag is on (promiscuous receive, phonedmrapp docs/promiscuous-rx-patch-spec-2026-10-24.md). */
    static final int PROMISCUOUS_FW = 26;
    /** True when MON on this channel also means "every call": a digital channel and a module running FW26 or later. */
    public boolean promiscuousCapable() {
        ChannelStore.Channel ch = current;
        return ch != null && ch.isDigital() && Firmware.stubTag(moduleVersion) && Firmware.tagNumber(moduleVersion) >= PROMISCUOUS_FW;
    }
    /** MON is on and the module is admitting every call (talkgroup, ID and colour code ignored), so the caller line carries CC and slot. */
    public boolean promiscuousOn() { return monitorOn && promiscuousCapable(); }
    /** Command 0x40 op 15: body {15, 1} on / {15, 0} off; the reply body echoes the flag. Stock firmware has no 0x40, so this is sent only behind promiscuousCapable(). */
    private void sendPromiscuous(final boolean on) { worker.execute(() -> sendPromiscuousNow(on)); }
    private void sendPromiscuousNow(boolean on) {
        try {
            Frame f = engine.diag40(new byte[] { 15, (byte) (on ? 1 : 0) }, 1000);
            log("promiscuous " + (on ? "on" : "off") + (f == null ? ": no answer" : ": sr=" + f.sr + (f.body != null && f.body.length > 0 ? " flag=" + (f.body[0] & 0xFF) : "")));
            if (f == null || f.sr != 0) history("warn", "WARN The module did not take the promiscuous flag (MON is the radio's own monitor only)".replace("WARN", String.valueOf(WARN_CHAR)));
        } catch (java.io.IOException e) { log("promiscuous: " + e); }
    }
    private static final char WARN_CHAR = (char) 0x26A0;
    /** When the radio last acknowledged MON (0x2F), and when it was last sent: the Xplore 1 sometimes does not answer at all. */
    private volatile long monAckAt, monSentAt;
    public long monAckAt() { return monAckAt; }
    /** Send MON and check that the radio answers within 1.5 s; no answer: send again (3 times), then warn. A refusal is retried in ack(). */
    private void sendMonitor(final boolean want) {
        worker.execute(() -> { try { monSentAt = System.currentTimeMillis(); engine.setMonitor(want); } catch (IOException e) { log("monitor: " + e); } });
        final long asked = System.currentTimeMillis();
        ui.postDelayed(() -> {
            if (monitorOn != want || monAckAt >= asked) return;            // answered (or MON changed meanwhile)
            if (monRetries < 3) { monRetries++; log("MON not answered, sending again (" + monRetries + ")"); sendMonitor(want); }
            else { monRefusedAt = System.currentTimeMillis(); history("warn", "\u26A0 The radio did not answer MON: switch it again in a few seconds"); }
        }, 1500);
    }

    public void setRecord(boolean on) { recordOn = on; if (!on) finishRecording(); pushModes(); }

    // ---- live captions ----------------------------------------------------------------------------

    public boolean captionsOn() { return captionsOn; }
    public boolean captionsAvailable() { return "google".equals(captionEngine()) ? !TranscriptionClient.isPlaceholderKey(googleKey()) : true; }
    public String captionEngine() { return getSharedPreferences("radio", MODE_PRIVATE).getString("captionEngine", "vosk"); }
    public void setCaptionEngine(String e) { getSharedPreferences("radio", MODE_PRIVATE).edit().putString("captionEngine", e).apply(); }
    public String googleKey() { return getSharedPreferences("radio", MODE_PRIVATE).getString("googleKey", ""); }
    public void setGoogleKey(String k) { getSharedPreferences("radio", MODE_PRIVATE).edit().putString("googleKey", k == null ? "" : k.trim()).apply(); }   // never logged

    // Google Cloud Speech: collect one utterance (16 kHz, upsampled from the 8 kHz stream), send it when the audio pauses or the reception ends
    private final java.io.ByteArrayOutputStream googleBuf = new java.io.ByteArrayOutputStream();
    private volatile long googleLastVoiceMs; private volatile boolean googleBusy;
    private void googleFeed(byte[] mono, int n, int peak) {
        synchronized (googleBuf) {
            if (peak > 400) googleLastVoiceMs = System.currentTimeMillis();
            if (googleBuf.size() == 0 && peak <= 400) return;
            int prev = googleBuf.size() == 0 ? 0 : (short) ((mono[0] & 0xFF) | (mono[1] << 8));
            for (int i = 0; i + 1 < n; i += 2) { int cur = (short) ((mono[i] & 0xFF) | (mono[i + 1] << 8)); int mid = (prev + cur) >> 1; googleBuf.write(mid); googleBuf.write(mid >> 8); googleBuf.write(cur); googleBuf.write(cur >> 8); prev = cur; }
            if (googleBuf.size() > 16000 * 2 * 55 || (System.currentTimeMillis() - googleLastVoiceMs > 1500 && googleBuf.size() > 16000 * 2)) googleFlush();
        }
    }
    private void googleFlush() {
        final byte[] pcm; synchronized (googleBuf) { if (googleBuf.size() < 16000) { googleBuf.reset(); return; } pcm = googleBuf.toByteArray(); googleBuf.reset(); }
        if (googleBusy) return; googleBusy = true;
        ui.post(() -> { for (Listener l : listeners) l.onCaption(captionsOn, "\u2601 transcribing\u2026", null); });
        new Thread(() -> {
            try {
                String text = TranscriptionClient.transcribe(pcm, 16000, googleKey());
                if (text != null && !text.trim().isEmpty()) {
                    String line = captionPrefix() + text.trim();
                    store.addHistory(current != null ? current.number : 0, "txt", "\uD83D\uDCDD " + line, -999, lastCaller);
                    checkKeywords(text);
                    ui.post(() -> { for (Listener l : listeners) l.onCaption(captionsOn, "", line); });
                } else ui.post(() -> { for (Listener l : listeners) l.onCaption(captionsOn, "", null); });
            } catch (Throwable t) { log("google stt: " + t.getClass().getSimpleName()); ui.post(() -> { for (Listener l : listeners) l.onCaption(captionsOn, "\u26A0 cloud transcription failed", null); }); }
            finally { googleBusy = false; }
        }, "google-stt").start();
    }
    public String captionModel() { return getSharedPreferences("radio", MODE_PRIVATE).getString("captionModel", VoskCaptions.DEFAULT_MODEL); }
    /** The on-device recognizer's state: idle, downloading … %, unpacking, loading, ready, or an error. */
    public String captionStatus() { VoskCaptions v = captions; return v == null ? "idle" : v.status(); }
    public String captionLoadedModel() { VoskCaptions v = captions; return v == null ? null : v.loadedName(); }
    public boolean captionModelBusy() { VoskCaptions v = captions; return v != null && v.isLoading(); }

    public void setCaptionModel(String m) {
        getSharedPreferences("radio", MODE_PRIVATE).edit().putString("captionModel", m).apply();
        if (captions == null) captions = newVoskCaptions();
        captions.setModel(m);
    }

    private VoskCaptions newVoskCaptions() {
        return new VoskCaptions(this, new VoskCaptions.Listener() {
            @Override public void status(String s) { ui.post(() -> { for (Listener l : listeners) l.onCaption(captionsOn, "\u23F3 " + s, null); }); }
            @Override public void partial(String text) { ui.post(() -> { for (Listener l : listeners) l.onCaption(captionsOn, text + " \u2026", null); }); }
            @Override public void sentence(String text) {
                String line = captionPrefix() + text;
                store.addHistory(current != null ? current.number : 0, "txt", "\uD83D\uDCDD " + line, -999, lastCaller);
                checkKeywords(text);
                ui.post(() -> { for (Listener l : listeners) l.onCaption(captionsOn, "\u2026", line); });
            }
            @Override public void finished(String full) {
                captionLastEnd = System.currentTimeMillis();
                ui.post(() -> { for (Listener l : listeners) l.onCaption(captionsOn, "", null); });
            }
        });
    }

    private String captionPrefix() {
        if (current != null && current.isDigital() && lastCaller > 0) { String n = callerName(lastCaller); return (n.isEmpty() ? "ID " + lastCaller : n) + ": "; }
        return "";
    }

    public void setCaptions(boolean on) {
        captionsOn = on;
        if (on) { if (captions == null) captions = newVoskCaptions(); captions.setModel(captionModel()); }
        else if (captions != null) captions.stop();
        ui.post(() -> { for (Listener l : listeners) l.onCaption(on, "", null); });
    }

    public String toggleScan() {
        if (scanner.isScanning()) { scanner.stop(); return "Scan stopped"; }
        if (vfo != null) return "Turn VFO off to scan channels";
        String err = scanner.start(); pushModes();      // the scanner tunes its first channel at once, which applies the scan-time Soft SQ
        return err == null ? "Scanning " + zoneName() : err;
    }
    public String skipScanChannel() { return scanner.skipCurrent(); }

    public long zoneId() { return zoneId; }
    public String zoneName() { if (zoneId <= 0) return "ALL"; ChannelStore.Zone z = store.zone(zoneId); return z == null ? "ALL" : z.name; }
    public void setZone(long id) {
        zoneId = id; getSharedPreferences("radio", MODE_PRIVATE).edit().putLong("zone", id).apply(); pushModes();
        // tune the channel last used in this zone (or stay, when the current channel is in it; else its first channel)
        if (pttDown || scanner.isScanning() || vfo != null) return;              // not while talking, scanning (it carries on in the new zone) or on the VFO
        java.util.List<ChannelStore.Channel> in = zoneChannels(); if (in.isEmpty()) return;
        int last = getSharedPreferences("radio", MODE_PRIVATE).getInt("zoneLast_" + id, -1);
        ChannelStore.Channel cur = current; int target = -1; boolean curIn = false;
        for (ChannelStore.Channel c : in) { if (c.number == last) target = last; if (cur != null && c.number == cur.number) curIn = true; }
        if (target < 0) { if (curIn) return; target = in.get(0).number; }
        if (cur == null || cur.number != target) selectChannel(target);
    }
    /** Remember the channel as the last one used in the current zone (not for channels the scanner steps through). */
    private void rememberZoneChannel(int number) {
        if (scanner.isScanning()) return;
        for (ChannelStore.Channel c : zoneChannels()) if (c.number == number) { getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("zoneLast_" + zoneId, number).apply(); return; }
    }
    /** Channels in the selected zone, in channel order (all channels when no zone is selected). */
    public java.util.List<ChannelStore.Channel> zoneChannels() {
        java.util.List<ChannelStore.Channel> all = store.all();
        if (zoneId <= 0) return all;
        ChannelStore.Zone z = store.zone(zoneId); if (z == null) return all;
        java.util.Set<Integer> in = new java.util.HashSet<>(); for (int n : z.numbers) in.add(n);
        java.util.List<ChannelStore.Channel> out = new java.util.ArrayList<>(); for (ChannelStore.Channel ch : all) if (in.contains(ch.number)) out.add(ch);
        return out;
    }

    // ---- channel ------------------------------------------------------------------------------------

    public ChannelStore store() { return store; }
    public ChannelStore.Channel current() { return current; }

    // ---- talkgroup picked on the intercom (TalkgroupOverride) ----
    private final TalkgroupOverride tgOverride = new TalkgroupOverride();
    /** The talkgroup picked for the current channel, 0 when it uses its own call number. */
    public int talkgroupOverride() { return tgOverride.talkgroup(current); }
    /** The current channel's programmed call, then the group contacts. */
    public java.util.List<TalkgroupOverride.Option> talkgroupOptions() { ChannelStore.Channel ch = current; return ch == null || !ch.isDigital() ? java.util.Collections.<TalkgroupOverride.Option>emptyList() : TalkgroupOverride.options(ch, store.contacts()); }
    /** Transmit to (and hear) {@code tg} on the current DMR channel until another channel is tuned; 0 or the channel's own talkgroup goes back to it. Null when done, else why not. */
    public String pickTalkgroup(final int tg) {
        final ChannelStore.Channel ch = current;
        if (ch == null || !ch.isDigital() || ch.id <= 0 || vfo != null) return "Pick a talkgroup on a saved DMR channel";
        if (pttDown) return "Not while transmitting";
        tgOverride.set(ch, tg);
        worker.execute(() -> {
            if (current != ch) return;
            if (applyChannelNow(ch, true)) { int t = tgOverride.talkgroup(ch); history("tg", t > 0 ? "Talkgroup " + t + " on " + ch.name + " until you change channel (the channel keeps " + ch.txContact + ")" : "Back to " + ch.name + "'s own call " + ch.txContact); }
        });
        return null;
    }
    /** Something is being heard right now (the Soft SQ gate when it is on, else the module's own receive state). */
    public boolean rxActive() { return softSq.isEnabled() ? squelchOpen : receiving; }
    public DeviceProfile profile() { return profile; }
    public String link() { return link; }
    public boolean isReady() { return "ready".equals(link); }

    public void stepChannel(int delta) {
        if (current == null || pttDown) return;
        if (vfo != null) { vfoStep(delta); return; }
        if (scanner.isScanning()) scanner.stop();
        java.util.List<ChannelStore.Channel> list = zoneChannels(); if (list.isEmpty()) return;
        int pos = -1; for (int i = 0; i < list.size(); i++) if (list.get(i).number == current.number) pos = i;
        int next = ((pos + delta) % list.size() + list.size()) % list.size();
        selectChannel(list.get(next).number);
    }

    private final java.util.concurrent.atomic.AtomicInteger selectSeq = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastChannelWrite;
    public void selectChannel(final int number) {
        final int my = selectSeq.incrementAndGet();
        worker.execute(() -> {
            long gap = profile.minWriteGapMs;
            if (gap > 0) {                                                     // this module wants room between channel writes: wait it out, keep only the newest request
                long wait = lastChannelWrite + gap - System.currentTimeMillis();
                if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { return; } }
                if (selectSeq.get() != my) return;
            }
            selectChannelNow(number);
        });
    }

    private synchronized void selectChannelNow(int number) {
        ChannelStore.Channel ch = store.byNumber(number);
        if (ch == null) { log("no channel " + number); return; }
        vfo = null;
        applyChannelNow(ch, true);
        rememberZoneChannel(number);
    }

    /** Squelch byte for the module: with Soft SQ on, or on an M17 channel, the radio's squelch is held open (0). An M17 channel needs the audio below the module's one threshold; the speaker gate keeps that raw FM out and only the decoded voice is played. */
    /** A data mode runs (APRS monitoring, SSTV, FT8, M17): its decoders get the audio below any squelch. */
    public boolean dataModeActive() {
        PacketEngine p = packet; com.macdmr.radio.ham.Ft8Station f = ft8; M17Station m = m17;
        return (p != null && (p.monitoring() || p.sstvRx())) || (f != null && f.running()) || (m != null && m.running());
    }
    /** Data modes keep the received audio off the speaker and the web (owner, 2026-10-28); MON brings it back. */
    private boolean dataQuiet() { return !monitorOn && dataModeActive(); }
    private volatile boolean lastDataMode;
    /** A data mode started or stopped: on a radio with {@link DeviceProfile#dataSquelch} the module squelch follows. */
    private void followDataMode() {
        boolean d = dataModeActive(); if (d == lastDataMode) return; lastDataMode = d;
        ChannelStore.Channel c = current;
        if (profile.dataSquelch > 0 && c != null && !c.isDigital() && !c.isM17()) worker.execute(() -> rewriteAnalogSquelch(c));
    }
    private int hardwareSq(ChannelStore.Channel ch) {
        if (ch != null && !ch.isDigital() && !ch.isM17() && profile.dataSquelch > 0 && dataModeActive()) return Math.max(profile.dataSquelch, ch.sq);   // the W6: never its monitor mode for data
        if (ch != null && !ch.isM17() && profile.softSqInModule && softSqFor(ch)) return Math.max(1, Math.min(8, ch.softSqLevel));   // Soft SQ is the module's squelch (1-9 to its 1-8)
        if (ch != null && !ch.isM17() && !profile.subaudible && softSqFor(ch) && ch.rxType != 0) return 1;   // no tones in the audio (the W6): the module keeps checking the channel's tone, Soft SQ gates the noise
        return ch != null && (ch.isM17() || softSqFor(ch)) ? 0 : ch == null ? 0 : ch.sq;
    }
    /** Soft SQ gates the audio in the app (every radio but those whose module squelch does it). */
    private boolean softGate(ChannelStore.Channel ch) { return softSqFor(ch) && !profile.softSqInModule; }
    private final Runnable moduleSqWrite = () -> { ChannelStore.Channel c = current; if (c != null) worker.execute(() -> rewriteAnalogSquelch(c)); };   // no tones in the audio (the W6): the module keeps its squelch and tone
    /** APRS, SSTV and satellite channels: the radio's squelch wide open (its single threshold sits near -90 dBm, far
     *  above a satellite or a distant digipeater), so the decoders get every signal; Soft SQ keeps the speaker quiet
     *  until something is really there. */
    /** First-run trial: listen on an analog frequency with the radio's squelch open (noise keeps the audio path busy); receive only. */
    public void listenTest(int hz, String name) {
        ChannelStore.Channel a = new ChannelStore.Channel();
        a.type = 1; a.rxFreq = a.txFreq = hz; a.band = 1; a.power = 1; a.name = name;
        a.sq = 0; a.softSq = true; a.softSqLevel = 5;                 // the radio passes everything; Soft SQ keeps the speaker quiet
        applyVfo(a, false);
    }

    private void dataModeSquelch(ChannelStore.Channel c) {
        c.sq = 0; c.softSq = true; c.softSqLevel = 5;
        c.power = packet != null ? packet.dataTxPower() : 1;      // APRS settings: high (default) or low on these frequencies
    }

    /** Soft SQ applies to an analog channel when it is switched on for that channel, or on every analog channel while scanning (setting, default on). */
    private boolean softSqFor(ChannelStore.Channel ch) { return !ch.isM17() && softSqApplies(ch.isDigital(), ch.softSq, scanner.isScanning(), scanSoftSq()); }
    static boolean softSqApplies(boolean digital, boolean channelSoftSq, boolean scanning, boolean scanSoftSq) { return !digital && (channelSoftSq || (scanning && scanSoftSq)); }
    /** The scan priority channel number (0 = none). */
    public int scanPriority() { return getSharedPreferences("radio", MODE_PRIVATE).getInt("scanPriority", 0); }
    public void setScanPriority(int number) { getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("scanPriority", number).apply(); }
    public boolean scanSoftSq() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("scanSoftSq", true); }
    public void setScanSoftSq(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("scanSoftSq", on).apply(); }

    /** Re-evaluate the squelch for the current channel (Soft SQ key, scan started/stopped): detector config, blackout and the module's squelch byte. */
    private synchronized void refreshSquelch(ChannelStore.Channel ch) {
        if (ch == null || ch.isDigital() || ch.isM17() || current != ch) return;
        boolean soft = softGate(ch);
        softSq.configure(soft, ch.softSqLevel, ch.rxType, ch.rxSubCode);
        if (soft) { softSq.onRetune(); squelchOpen = false; } else squelchOpen = true;
        rewriteAnalogSquelch(ch);
        pushModes();
    }

    /** Soft SQ was switched: re-send the analog channel so the module's squelch follows (single direct write, same path as a channel change). */
    private synchronized void rewriteAnalogSquelch(ChannelStore.Channel ch) {
        if (ch == null || ch.isDigital() || ch.isM17() || current != ch) return;
        try {
            int band = ch.rxFreq < 300_000_000 ? 1 : ch.band;
            synchronized (tunes) { tunes.addLast(new Object[] { ch, false }); }   // answered like any channel write
            tunedAt = System.currentTimeMillis(); engine.setAnalog(ch.rxFreq, ch.txFreq, band, ch.power, hardwareSq(ch), ch.rxType, ch.rxSubCode, ch.txType, ch.txSubCode, 8, 2);
            if (profile.globalSquelch) engine.setSquelch(hardwareSq(ch));
            if (dataReceive) scheduleDataHold(true);
        } catch (IOException e) { log("squelch rewrite: " + e); }
    }

    /** Program the radio with a channel; {@code fromStore} = a real channel (remembered as active), else the VFO. */
    private synchronized boolean applyChannelNow(ChannelStore.Channel ch, boolean fromStore) {
        { ChannelStore.Channel was = current; if (was == null || was.rxFreq != ch.rxFreq) idleFloor = Double.NaN; }   // another frequency: its own idle floor
        if (!ChannelStore.inBand(ch.rxFreq) || !ChannelStore.inBand(ch.txFreq)) {         // the module refuses these and stays where it is: never show them as tuned
            ChannelStore.Channel on = current;
            history("warn", "⚠ " + ch.name + " (" + ch.freqText(ch.rxFreq) + (ch.txFreq != ch.rxFreq ? " / " + ch.freqText(ch.txFreq) : "") + " MHz) is outside what this radio can tune (" + com.macdmr.radio.DeviceProfile.detect().bandsText() + ")"
                    + (on != null ? "; still on " + on.name + ", " + on.freqText(on.rxFreq) + " MHz" : ""));
            if (!fromStore && vfo == ch) vfo = on != null && on.number == 0 ? on : null;
            return false;
        }
        try {
            synchronized (tunes) { if (tunes.size() > 8) tunes.clear(); tunes.addLast(new Object[] { ch, fromStore }); }
            tgOverride.onApply(ch);                                          // a talkgroup picked on the intercom lasts until another channel is tuned
            if (ch.isDigital()) {
                int id = !fromStore && ch.mode > 0 ? ch.mode : localId();          // VFO may transmit under another DMR ID
                int[] call = tgOverride.call(ch);
                tunedAt = System.currentTimeMillis(); engine.setDigital(ch.rxFreq, ch.txFreq, id, tgOverride.groups(ch), call[0], call[1], ch.power, ch.cc, ch.inSlot, ch.outSlot, 8, 4);
                engine.setInterrupt(ch.interrupt == 1);
            } else if (ch.isM17()) {
                tunedAt = System.currentTimeMillis(); engine.setAnalog(ch.rxFreq, ch.txFreq, 1, ch.power, hardwareSq(ch), 0, 0, 0, 0, 8, 2);   // wide FM; M17 is layered on top
                lastChannelWrite = System.currentTimeMillis();
                if (profile.globalSquelch) {
                    final boolean mon = monitorOn && !moved(ch); final int sq = hardwareSq(ch); final long token = ++postWriteToken;
                    pendingPostWrite = () -> { if (postWriteToken != token) return; postWriteToken++; worker.execute(() -> { try { engine.setMonitor(mon); engine.setSquelch(sq); } catch (IOException e) { log("monitor/squelch: " + e); } }); };
                    ui.postDelayed(() -> { Runnable r = pendingPostWrite; if (r != null && postWriteToken == token) { log("no channel acknowledgement in 1.5 s: monitor and squelch sent anyway"); r.run(); } }, 1500);
                }
            } else {
                int band = ch.rxFreq < 300_000_000 ? 1 : ch.band;      // VHF must be band=1 (band=0 deafens the RX)
                tunedAt = System.currentTimeMillis(); engine.setAnalog(ch.rxFreq, ch.txFreq, band, ch.power, hardwareSq(ch), ch.rxType, ch.rxSubCode, ch.txType, ch.txSubCode, 8, 2);
                lastChannelWrite = System.currentTimeMillis();
                if (profile.globalSquelch) {                              // this module's squelch follows 0x30; QuickChat sends monitor and squelch 200 ms after the channel's acknowledgement
                    final boolean mon = monitorOn && !moved(ch); final int sq = hardwareSq(ch); final long token = ++postWriteToken;
                    pendingPostWrite = () -> { if (postWriteToken != token) return; postWriteToken++; worker.execute(() -> { try { engine.setMonitor(mon); engine.setSquelch(sq); } catch (IOException e) { log("monitor/squelch: " + e); } }); };
                    ui.postDelayed(() -> { Runnable r = pendingPostWrite; if (r != null && postWriteToken == token) { log("no channel acknowledgement in 1.5 s: monitor and squelch sent anyway"); r.run(); } }, 1500);
                }
            }
            finishRecording();
            ChannelStore.Channel was = current;
            boolean moved = was == null || was.number != ch.number || was.rxFreq != ch.rxFreq || was.type != ch.type;
            if (moved && monitorOn) {                                  // the module leaves monitor mode on a channel change: so does the button
                boolean promisc = promiscuousCapable();            // judged on the channel being left
                monitorOn = false;
                try { engine.setMonitor(false); } catch (IOException e) { log("monitor: " + e); }
                if (promisc) sendPromiscuousNow(false);
                log("MON off: the channel changed");
            }
            current = ch; if (fromStore) store.setActive(ch.number);
            softSq.configure(softGate(ch), ch.softSqLevel, ch.rxType, ch.rxSubCode);
            softSq.onRetune();
            squelchOpen = !softSq.isEnabled();                     // with Soft SQ on, stay muted until the detector opens (no burst of static after a retune)
            ui.post(() -> { for (Listener l : listeners) l.onChannel(ch); });
            if (fromStore && voiceOn() && !scanner.isScanning() && !quietRewrite) sayChannel(channelWords(ch));   // while scanning only a stop is announced (see the scanner status)
            pushModes();
            describeLocation(ch);
            updateNotification();
            if (dataReceive) scheduleDataHold(true);
            followM17Channel(ch);
            return true;
        } catch (IOException e) { log("select: " + e); return false; }
    }

    /** Reverse-geocode the channel's stored position and add distance/bearing from the phone, like the module. */
    private void describeLocation(final ChannelStore.Channel ch) {
        if (ch.lat == 0 && ch.lon == 0) { ui.post(() -> { for (Listener l : listeners) l.onLocation(""); }); return; }
        new Thread(() -> {
            String place = String.format(Locale.US, "%.4f, %.4f", ch.lat, ch.lon);
            try {
                java.util.List<android.location.Address> a = new android.location.Geocoder(this, Locale.US).getFromLocation(ch.lat, ch.lon, 1);
                if (a != null && !a.isEmpty()) {
                    android.location.Address ad = a.get(0);
                    String city = ad.getLocality() != null ? ad.getLocality() : ad.getSubAdminArea();
                    String state = ad.getAdminArea();
                    if (city != null && state != null) place = city + ", " + state; else if (city != null) place = city; else if (state != null) place = state;
                }
            } catch (Throwable ignored) { }
            String dist = "";
            try {
                android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
                android.location.Location me = null;
                for (String p : new String[] { android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER }) {
                    try { android.location.Location l = lm.getLastKnownLocation(p); if (l != null && (me == null || l.getTime() > me.getTime())) me = l; } catch (SecurityException ignored) { }
                }
                if (me != null) {
                    float[] r = new float[2];
                    android.location.Location.distanceBetween(me.getLatitude(), me.getLongitude(), ch.lat, ch.lon, r);
                    double km = r[0] / 1000.0, mi = km * 0.621371; float b = (r[1] + 360) % 360;
                    String[] dirs = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
                    String arrow = b < 22.5 || b >= 337.5 ? "\u2191" : b < 67.5 ? "\u2197" : b < 112.5 ? "\u2192" : b < 157.5 ? "\u2198" : b < 202.5 ? "\u2193" : b < 247.5 ? "\u2199" : b < 292.5 ? "\u2190" : "\u2196";
                    dist = com.macdmr.radio.region.Region.km() ? String.format(Locale.US, "\n(%s%s %.1fkm)", arrow, dirs[(int) Math.round(b / 45.0) % 8], km) : String.format(Locale.US, "\n(%s%s %.1fmi (%.1fkm))", arrow, dirs[(int) Math.round(b / 45.0) % 8], mi, km);
                }
            } catch (Throwable ignored) { }
            double el = ch.elev;
            if (el <= 0) {                                   // the module looks this up online (open-elevation) and so do we, cached
                String k = String.format(Locale.US, "elev_%.4f_%.4f", ch.lat, ch.lon);
                android.content.SharedPreferences ep = getSharedPreferences("elevation", MODE_PRIVATE);
                if (ep.contains(k)) el = ep.getFloat(k, 0);
                else {
                    try {
                        java.net.HttpURLConnection cn = (java.net.HttpURLConnection) new java.net.URL(String.format(Locale.US, "https://api.open-elevation.com/api/v1/lookup?locations=%.6f,%.6f", ch.lat, ch.lon)).openConnection();
                        cn.setConnectTimeout(5000); cn.setReadTimeout(8000);
                        if (cn.getResponseCode() == 200) {
                            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(cn.getInputStream()));
                            StringBuilder sb = new StringBuilder(); String ln; while ((ln = br.readLine()) != null) sb.append(ln);
                            el = new org.json.JSONObject(sb.toString()).getJSONArray("results").getJSONObject(0).getDouble("elevation");
                            ep.edit().putFloat(k, (float) el).apply();
                        }
                    } catch (Throwable ignored) { }
                }
            }
            String elev = el > 0 ? String.format(Locale.US, "\n%dft (%.0fm) ", (int) (el * 3.28084), el) : "\n";
            final String text = place + dist + elev + "\uD83D\uDCCD";
            ui.post(() -> { for (Listener l : listeners) l.onLocation(text); });
        }, "geocode").start();
    }

    public void deleteChannel(ChannelStore.Channel ch) {
        store.delete(ch);
        if (current != null && current.id == ch.id) { current = null; int n = store.count(); if (n > 0) selectChannel(Math.min(ch.number, n)); else noChannel(); }
    }

    // ---- several channels at once (the channel list's selection, on the phone and on the web) ----

    /** Delete several channels (highest number first: the renumbering never moves one still to go). Refused while transmitting or loading firmware. */
    public String deleteChannels(java.util.Collection<Integer> numbers) {
        if (firmwareLoading()) return "Radio busy, loading firmware";
        if (isTransmitting()) return "Not while transmitting";
        ChannelStore.Channel cur = current; long curId = cur != null ? cur.id : -1; int curNum = cur != null ? cur.number : -1;
        boolean curGone = false; int n = 0;
        for (int num : ChannelStore.deleteOrder(numbers)) {
            ChannelStore.Channel ch = store.byNumber(num); if (ch == null) continue;
            if (ch.id == curId) curGone = true;
            store.delete(ch); n++;
        }
        if (curGone) { current = null; int left = store.count(); if (left > 0) selectChannel(Math.min(curNum, left)); else noChannel(); }
        else if (cur != null) for (ChannelStore.Channel c : store.all()) if (c.id == curId) { cur.number = c.number; break; }   // its number may have moved down: same channel, nothing re-tuned
        return n == 0 ? "No channels deleted" : "Deleted " + n + (n == 1 ? " channel" : " channels");
    }

    /** Add several channels to a zone: an existing one ({@code zoneId} > 0) or a new one named {@code newName}. */
    public String addChannelsToZone(long zoneId, String newName, java.util.Collection<Integer> numbers) {
        ChannelStore.Zone z;
        if (zoneId > 0) { z = store.zone(zoneId); if (z == null) return "No such zone"; }
        else { if (newName == null || newName.trim().isEmpty()) return "A new zone needs a name"; z = new ChannelStore.Zone(); z.name = newName.trim(); }
        java.util.List<Integer> ok = new java.util.ArrayList<>(); for (Integer n : numbers) if (n != null && store.byNumber(n) != null) ok.add(n);
        if (ok.isEmpty()) return "No channels selected";
        int before = z.numbers.length; z.numbers = ChannelStore.withNumbers(z.numbers, ok);
        long id = store.saveZone(z);
        if (zoneId() == id) setZone(id);                                     // the active zone changed: scan and arrows follow it
        int added = z.numbers.length - before;
        return (zoneId > 0 ? "Added " + added + (added == 1 ? " channel" : " channels") + " to zone " : "New zone ") + z.name + (zoneId > 0 ? "" : " with " + z.numbers.length + (z.numbers.length == 1 ? " channel" : " channels"));
    }

    /** Take several channels out of a zone (the channels themselves stay). A zone is never left empty: delete it instead. */
    public String removeChannelsFromZone(long zoneId, java.util.Collection<Integer> numbers) {
        ChannelStore.Zone z = store.zone(zoneId); if (z == null) return "No such zone";
        int[] left = ChannelStore.withoutNumbers(z.numbers, numbers);
        if (left.length == 0) return "That would leave zone " + z.name + " empty: delete the zone instead (Zones)";
        int removed = z.numbers.length - left.length; z.numbers = left; store.saveZone(z);
        if (zoneId() == zoneId) setZone(zoneId);
        return "Took " + removed + (removed == 1 ? " channel" : " channels") + " out of zone " + z.name;
    }

    /** Star or unstar several channels (the ★ Favourites zone). */
    public String favouriteChannels(java.util.Collection<Integer> numbers, boolean on) {
        int n = 0; for (Integer num : numbers) if (num != null && store.byNumber(num) != null) { store.setFavourite(num, on); n++; }
        return (on ? "Starred " : "Unstarred ") + n + (n == 1 ? " channel" : " channels");
    }

    /** Save an edited or new channel; if it is the active one, re-program the radio. */
    public void saveChannel(ChannelStore.Channel ch, boolean isNew) {
        if (isNew) store.insert(ch); else store.update(ch);
        if (!isNew && current != null && current.id == ch.id) selectChannel(ch.number);
    }

    private void setLocalIdQuiet(int id) { if (id >= 1 && id <= ChannelStore.MAX_DMR_ID) getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("dmrId", id).apply(); }

    public void setLocalId(int id) { getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("dmrId", id).apply(); if (current != null && current.isDigital()) selectChannel(current.number); }

    // ---- VFO ---------------------------------------------------------------------------------------

    private ChannelStore.Channel vfo;          // the channel the radio is on while VFO is active (id -1, number 0)
    private int vfoReturn = -1;                // channel number to return to

    public boolean vfoActive() { return vfo != null; }
    public int vfoStepHz() { return getSharedPreferences("radio", MODE_PRIVATE).getInt("vfoStep", 12500); }
    public void setVfoStepHz(int hz) { getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("vfoStep", hz).apply(); }

    /** A copy of the active VFO, else the last one tuned (prefs), else 146.520 FM simplex. */
    public ChannelStore.Channel vfoChannel() {
        if (vfo != null) return vfoCopy(vfo);
        ChannelStore.Channel v = new ChannelStore.Channel();
        v.type = 1; v.rxFreq = v.txFreq = 146_520_000; v.power = 1; v.band = 1; v.cc = 1; v.contactType = 1; v.txContact = 9; v.groups = new int[] { 9 };
        try {
            String js = getSharedPreferences("radio", MODE_PRIVATE).getString("vfo", null);
            if (js != null) {
                org.json.JSONObject o = new org.json.JSONObject(js);
                v.type = o.optInt("type", 1); v.rxFreq = o.optInt("rx", v.rxFreq); v.txFreq = o.optInt("tx", v.txFreq); v.power = o.optInt("power", 1); v.band = o.optInt("band", 1);
                v.rxType = o.optInt("rxType"); v.rxSubCode = o.optInt("rxCode"); v.txType = o.optInt("txType"); v.txSubCode = o.optInt("txCode");
                v.cc = o.optInt("cc", 1); v.inSlot = v.outSlot = o.optInt("slot"); v.contactType = o.optInt("callType", 1); v.txContact = o.optInt("call", 9); v.mode = o.optInt("idOverride");
                org.json.JSONArray g = o.optJSONArray("groups"); if (g != null) { v.groups = new int[g.length()]; for (int i = 0; i < g.length(); i++) v.groups[i] = g.optInt(i); }
            }
        } catch (Throwable ignored) { }
        if (!profile.dmr()) v.type = 1;                                  // an analog-only radio (the W6)
        if (!profile.tunable(v.rxFreq)) {                                // 146.520 on a UHF-only radio: start where it can listen
            ChannelStore.Channel ch = current;
            long f = ch != null && profile.tunable(ch.rxFreq) ? ch.rxFreq : profile.tunable(446_000_000L) ? 446_000_000L : profile.bandsHz[0];
            v.rxFreq = v.txFreq = (int) f;
        }
        return v;
    }

    public ChannelStore.Channel vfoCopy(ChannelStore.Channel s) {
        ChannelStore.Channel v = new ChannelStore.Channel();
        v.id = s.id; v.number = s.number; v.name = s.name; v.type = s.type; v.rxFreq = s.rxFreq; v.txFreq = s.txFreq; v.power = s.power; v.band = s.band; v.sq = s.sq;
        v.rxType = s.rxType; v.rxSubCode = s.rxSubCode; v.txType = s.txType; v.txSubCode = s.txSubCode; v.relay = s.relay; v.mode = s.mode;
        v.cc = s.cc; v.inSlot = s.inSlot; v.outSlot = s.outSlot; v.contactType = s.contactType; v.txContact = s.txContact; v.encryptSw = s.encryptSw; v.interrupt = s.interrupt;
        v.encryptKey = s.encryptKey; v.groups = s.groups.clone(); v.softSq = s.softSq; v.softSqLevel = s.softSqLevel;
        return v;
    }

    private void saveVfoPrefs(ChannelStore.Channel v) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("type", v.type); o.put("rx", v.rxFreq); o.put("tx", v.txFreq); o.put("power", v.power); o.put("band", v.band);
            o.put("rxType", v.rxType); o.put("rxCode", v.rxSubCode); o.put("txType", v.txType); o.put("txCode", v.txSubCode);
            o.put("cc", v.cc); o.put("slot", v.inSlot); o.put("callType", v.contactType); o.put("call", v.txContact); o.put("idOverride", v.mode);
            org.json.JSONArray g = new org.json.JSONArray(); for (int x : v.groups) g.put(x); o.put("groups", g);
            getSharedPreferences("radio", MODE_PRIVATE).edit().putString("vfo", o.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Tune the VFO (enters VFO mode if not in it); the previous real channel is remembered for {@link #exitVfo}. */
    public void applyVfo(final ChannelStore.Channel in) { applyVfo(in, true); }
    public void applyVfo(final ChannelStore.Channel in, boolean remember) {
        final ChannelStore.Channel v = vfoCopy(in);
        v.id = -1; v.number = 0;
        if (v.name == null || v.name.isEmpty()) v.name = "VFO " + String.format(Locale.US, "%.4f", v.rxFreq / 1e6) + (v.isDigital() ? " DMR" : " FM");
        if (remember) saveVfoPrefs(v);
        worker.execute(() -> {
            if (pttDown) return;
            if (scanner.isScanning()) scanner.stop();
            if (vfo == null && current != null) vfoReturn = current.number;
            vfo = v;
            if (applyChannelNow(v, false)) log("VFO " + v.freqText(v.rxFreq) + "/" + v.freqText(v.txFreq) + (v.isDigital() ? " DMR cc" + v.cc + " TG " + v.txContact : " FM"));
        });
    }

    public void exitVfo() {
        if (packet != null && packet.monitoring()) packet.setMonitoring(false);
        if (packet != null && packet.sstvRx() && vfo != null && vfo.rxFreq == PacketEngine.SSTV_FREQ_HZ) packet.setSstvRx(false);
        worker.execute(() -> {
            if (vfo == null) return;
            vfo = null;
            int back = vfoReturn > 0 && store.byNumber(vfoReturn) != null ? vfoReturn : store.activeNumber();
            selectChannelNow(back);
        });
    }

    /** Intercom arrows while VFO is on: step both frequencies (keeps any repeater offset). */
    private void vfoStep(int delta) {
        ChannelStore.Channel v = vfoCopy(vfo);
        int hz = delta * vfoStepHz();
        v.rxFreq += hz; v.txFreq += hz;
        long next = profile.nextTunable(v.rxFreq, delta);                                                                                  // jump a gap the radio cannot tune
        if (next > 0 && next != v.rxFreq) { v.rxFreq = (int) next; v.txFreq = v.rxFreq; }
        if (!ChannelStore.inBand(v.rxFreq) || !ChannelStore.inBand(v.txFreq)) return;                                                     // at an end, or a repeater offset would leave the band
        v.name = "";
        applyVfo(v);
    }

    /** Stock "areas" (one channel database each): keys like default_uhf, usa, eu … */
    public java.util.List<String> stockAreas() {
        if ("agold".equals(profile.protocol)) {                         // the Atom XL: areas in the stock app's intercom.db ("1 Default (16 channels)")
            java.util.List<String> a = ChannelStore.agoldAreas(this, profile.stockApp);
            if (a.isEmpty()) a.add("1 Default");
            return a;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String l : RootSetup.run("ls /data/data/" + profile.stockApp + "/databases/ 2>/dev/null").split("\\s+")) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("database_channel_area_(\\w+)\\.db").matcher(l); if (m.matches()) out.add(m.group(1)); }
        if (out.isEmpty()) out.add("default_uhf");
        return out;
    }
    public String reimportFromStock() { return reimportFromStock("default_uhf"); }
    /** The stock app keeps its channels in areas (the DMR phones' app); the W6's and the Xplore 1's have one list. */
    public boolean stockHasAreas() { return !"sa818".equals(profile.protocol) && !"com.chamsion.quickchat".equals(profile.stockApp) && !flyscaleDmr(); }
    /** The W6Plus DMR's stock app: one channel database (128 slots), the DMR ID in its in-use slot. */
    private boolean flyscaleDmr() { return "com.flyscale.dmr".equals(profile.stockApp); }
    /** The Atom XL's stock app has channels only (no contacts, history or messages SignalDeck imports). */
    public boolean stockImportsChannelsOnly() { return "agold".equals(profile.protocol); }
    /** The area the Atom XL's stock app is on (its curr_area setting, 1 when unset). */
    int agoldCurrentArea() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("name=\"curr_area\" value=\"([0-9]+)\"").matcher(RootSetup.run("cat /data/data/" + profile.stockApp + "/shared_prefs/intercom_prefs.xml 2>/dev/null"));
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }
    /** Replace the channel list with the stock app's: the W6's table, QuickChat's database, or one area of the DMR phones' app. */
    public String reimportFromStock(String areaKey) {
        StringBuilder err = new StringBuilder(); int n;
        if ("sa818".equals(profile.protocol)) n = store.importFromFlyscale(this, profile.stockApp, err);
        else if ("com.chamsion.quickchat".equals(profile.stockApp)) n = store.importFromQuickChat(this, profile.stockApp, err);
        else if (flyscaleDmr()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("name=\"channel_index\" value=\"([0-9]+)\"").matcher(RootSetup.run("cat /data/data/" + profile.stockApp + "/shared_prefs/config.xml 2>/dev/null"));
            int[] id = new int[1];
            n = store.importFromFlyscaleDmr(this, profile.stockApp, m.find() ? Integer.parseInt(m.group(1)) : 0, id, err);
            if (n >= 0 && id[0] > 0 && !getSharedPreferences("radio", MODE_PRIVATE).contains("dmrId")) { setLocalIdQuiet(id[0]); log("DMR ID from the stock app: " + id[0]); }
        }
        else if ("agold".equals(profile.protocol)) {                     // areaKey "3 USA FRS (14 channels)", or null for the stock app's current area
            java.util.regex.Matcher m = areaKey == null ? null : java.util.regex.Pattern.compile("^(\\d+)").matcher(areaKey.trim());
            n = store.importFromAgold(this, profile.stockApp, m != null && m.find() ? Integer.parseInt(m.group(1)) : agoldCurrentArea(), err);
        }
        else n = store.importFromStock(this, profile.stockApp, areaKey == null ? "default_uhf" : areaKey, err);
        if (n >= 0) {
            getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("tgListsImported", true).putBoolean("stockImportDeclined", false).apply();   // imported: an empty list asks again later
            stockImportPending = false; int keep = store.activeNumber(); selectChannel(keep);
        }
        log(n >= 0 ? "imported " + n + " channels from the stock app" + (err.length() > 0 ? " (" + err + ")" : "") : "stock import failed: " + err);
        String tones = n > 0 ? toneNote(store.all()) : null;
        if (tones != null) history("warn", "🎵 " + tones);
        return n >= 0 ? "Imported " + n + " channels" + (err.length() > 0 ? " (" + err + ")" : "") + (tones != null ? ". " + tones : "") : "Import failed: " + err;
    }

    /**
     * The imported analog channels that only open for a sub-audible tone, said once: the W6Plus DMR's stock app sets
     * CTCSS 62.5 Hz on every factory channel without showing it, so a radio sending no tone is never heard there
     * (owner, 2026-10-29). Nothing is changed; null when no analog channel has a receive tone.
     */
    static String toneNote(List<ChannelStore.Channel> chans) {
        int n = 0, analog = 0; java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (ChannelStore.Channel c : chans) {
            if (c.isDigital() || c.isM17()) continue;
            analog++;
            if (c.rxType != 0) { n++; kinds.merge(com.macdmr.radio.dsp.ToneTable.toneLabel(c.rxType, c.rxSubCode), 1, Integer::sum); }
        }
        if (n == 0) return null;
        String which = kinds.size() == 1 ? " (" + kinds.keySet().iterator().next() + ")" : "";
        return (n == analog ? "All " + n : n + " of " + analog) + " imported analog channels open only for a receive tone" + which
                + ", as the stock app had them: a radio that sends no tone is not heard there. Set RX tone type to None in the channel editor if you need to";
    }

    private volatile boolean stockImportPending;
    /** True while the empty-list question is open (the phone and the web both show it until one answers). */
    public boolean stockImportPending() { return stockImportPending; }
    /**
     * The answer to the empty-list question: import the stock app's channels, or start empty (remembered, so it is not
     * asked again; Device → Re-import still imports). Null when it was already answered elsewhere.
     */
    public String answerStockImport(boolean importThem) {
        synchronized (this) { if (!stockImportPending) return null; stockImportPending = false; }
        if (!importThem) {
            getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("stockImportDeclined", true).apply();
            log("stock import declined: starting with no channels"); ui.post(channelsChanged);
            return "Starting with no channels";
        }
        return reimportFromStock(null);
    }

    /** This radio's DMR ID; 1 (the stock default) when none is set or the stored one is out of range, which the module refuses and then stops answering (W6Plus DMR, 2026-10-29). */
    public int localId() { int id = getSharedPreferences("radio", MODE_PRIVATE).getInt("dmrId", 1); return id >= 1 && id <= ChannelStore.MAX_DMR_ID ? id : 1; }

    // ---- PTT ----------------------------------------------------------------------------------------

    public boolean txInhibited() {
        if (!profile.verified || ProfileRegistry.transmitBlocked()) return true;   // an untested profile never transmits; an advisory can switch it off (the Xplore 1 keys through a GPIO line)
        ChannelStore.Channel ch = current;
        if (ch == null) return true;
        if (ch.isM17() && !m17CanSend()) return true;             // stock firmware: an M17 channel is receive-only
        SatState ss = satState;
        if (ss != null && ss.listenOnly && vfo != null && ss.name.equals(vfo.name)) return true;     // a satellite downlink (ISS SSTV)
        return ChannelStore.receiveOnly(ch);             // NOAA weather, marine and railroad channels, and channels set receive only
    }

    /** The browser's microphone for the next key-down (SignalDeck Web talk); read once by {@link #ptt}. */
    private volatile AudioBackend.TxFeed webFeedNext;
    /** Key or unkey with a browser's microphone as the audio (SignalDeck Web). */
    public void pttFromWeb(boolean down, AudioBackend.TxFeed feed) { if (down) webFeedNext = feed; ptt(down); }

    public void ptt(final boolean down) {
        worker.execute(() -> {
            try {
                if (down) {
                    if (current != null && current.isM17()) {
                        if (firmwareBusy) { log("PTT blocked: loading firmware"); history("warn", "Radio busy, loading firmware"); return; }
                        if (!isReady()) { log("PTT ignored: radio not ready"); return; }
                        if (!m17CanSend()) { log("PTT blocked: M17 receive only"); history("warn", "⚠️ M17 is receive-only until the module firmware has the symbol engine"); return; }
                        if (txInhibited()) { log("PTT blocked: receive-only channel"); history("warn", "⛔ TX blocked on a receive-only channel"); return; }
                        if (pttDown) return;
                        wheelPtt = false; callPtt = false; webFeedNext = null;
                        pttDown = true; refreshControls();
                        m17Voice(true);
                        return;
                    }
                    if (scope.running()) { scope.stop(); log("band scope stopped for Talk"); }   // back on the channel before keying
                    final boolean fromWheel = wheelPtt, fromCall = callPtt; wheelPtt = false; callPtt = false;      // read once, whatever happens next
                    final AudioBackend.TxFeed webFeed = webFeedNext; webFeedNext = null;
                    if (firmwareBusy) { log("PTT blocked: loading firmware"); history("warn", "Radio busy, loading firmware"); return; }
                    if (!isReady()) { log("PTT ignored: radio not ready"); return; }
                    if (!profile.verified) { log("PTT blocked: untested profile"); history("warn", "⛔ Transmit is off on this phone until its profile has been tested"); return; }
                    if (txInhibited()) { log("PTT blocked: receive-only channel"); history("warn", "⛔ TX blocked on a receive-only channel"); return; }
                    if (pttDown) return;
                    pttDown = true; refreshControls();
                    txChannel = current; txInCar = inCar; txCharging = charging(); txDropNoted = false; txStartedAt = System.currentTimeMillis(); pcmTxLabel = null;
                    try { ChannelStore.Channel tc = current; int[] tcall = tc != null ? tgOverride.call(tc) : null; if (tc != null && tc.isDigital() && tcall[0] > 0) { ChannelStore.Contact pc = store.contactByNumber(tcall[0], tcall[1] == 0 ? 0 : 1); if (pc != null) store.contactSent(pc.id, txStartedAt, "DMR voice", "on " + tc.name); } } catch (Throwable e) { }
                    idTimerOnKey();
                    if (audio != null) audio.stopRx();              // never play the RX stream while the mic is live (feedback)
                    boolean carMic = webFeed == null && (fromCall || (inCar && carMic() && !fromWheel && startBluetoothMic()));   // a talk call: Telecom has opened the car's link   // not for the wheel's Next: the hands-free link turns the wheel into call controls and the stop press never arrives   // in the car: its microphone, through the hands-free link
                    if (audio != null) audio.setTxSource(carMic ? android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION : android.media.MediaRecorder.AudioSource.MIC);
                    if (audio != null) audio.setTxFeed(webFeed);                     // SignalDeck Web: the browser's microphone instead of the phone's
                    int vi = voxStop();
                    if ((fromWheel || fromCall) && vi > 0 && vi < VOX_MS.length && audio != null) {          // stop when the talking stops
                        final com.macdmr.radio.ham.VoxStop v = new com.macdmr.radio.ham.VoxStop(VOX_MS[vi], System.currentTimeMillis()); vox = v;
                        audio.setTxLevel(pk -> { if (vox == v && v.level(pk, System.currentTimeMillis())) { vox = null; log("stopped: quiet for " + VOX_NAMES[vi]); ptt(false); } });
                    } else if (audio != null) { vox = null; audio.setTxLevel(null); }
                    if (carMic) log("TX with the car microphone"); else if (fromWheel && inCar && carMic()) log("TX from the wheel: phone microphone (keeps the wheel's buttons working)");
                    engine.ptt(true);
                    if (audio != null) log(audio.startTx());
                    ui.removeCallbacks(txWatchdog); ui.postDelayed(txWatchdog, 28000);
                } else {
                    if (current != null && current.isM17()) {
                        m17Voice(false);
                        if (pttDown) { pttDown = false; refreshControls(); }
                        return;
                    }
                    if (!pttDown) return;
                    pttDown = false; refreshControls();
                    wheel.stopped(); vox = null; if (audio != null) audio.setTxLevel(null);
                    if (com.macdmr.radio.car.RadioCallService.active()) ui.post(com.macdmr.radio.car.RadioCallService::end);   // the talk call ends with the transmission
                    txEndedAt = System.currentTimeMillis();
                    ui.removeCallbacks(txWatchdog);
                    try {
                        if (audio != null) audio.stopTx();
                        stopBluetoothMic();
                        short[] roger = rogerPcm(rogerStyle());
                        if (roger != null && audio != null) audio.playPcm(roger);    // still keyed: the tone goes out before the carrier drops
                        ChannelStore.Channel tc = current;
                        short[] id = tc != null && !tc.isDigital() && amateurBand(tc.txFreq) && packet != null && System.currentTimeMillis() - txStartedAt < 25_000 ? packet.cwIdIfDue(System.currentTimeMillis()) : null;
                        if (id != null && audio != null) { log("Morse ID after the transmission"); audio.playPcm(id); history("pkt", "\uD83D\uDD24 Morse ID " + packet.callsign()); }   // the station identification every ten minutes
                    } finally { unkey(); }
                    if (rxAudioOn && audio != null && (!agoldBridge() || receiving)) audio.startRx(true);
                    if (dataReceive) scheduleDataHold(true);   // the unkey path rewrites the voice filter; put the hold back
                }
            } catch (Throwable t) { log("ptt: " + t); try { engine.ptt(false); } catch (Throwable ignored) { } pttDown = false; }
        });
    }

    // ---- Device tools ----
    public void diagVersion() { worker.execute(() -> { try { engine.queryVersion(); } catch (IOException e) { log("version: " + e); } }); }
    /** Bench: send one command with a single body byte 1. Used for an unused slot such as 0x40. Does not key. */
    public void diagCmd(int cmd) { diagCmd(cmd, 1); }
    /** Bench: one command and one body byte. Does not key. */
    public void diagCmd(int cmd, int data) { worker.execute(() -> { try { engine.diagCommand(cmd, data); log(String.format(java.util.Locale.US, "cmd 0x%02X body 0x%02X", cmd & 0xFF, data & 0xFF)); } catch (IOException e) { log("cmd: " + e); } }); }
    /** Bench: store a sine on command 0x40. Does not key. */
    public void diagSine(int n, int amp, int hz) { worker.execute(() -> { try { engine.diagSine(n, amp, hz); log(String.format(java.util.Locale.US, "sine n=%d amp=%d hz=%d", n, amp, hz)); } catch (IOException e) { log("sine: " + e); } }); }
    /** Bench: store one piece of a little-endian sample file on command 0x40. Does not key. */
    public void diagPcm(java.io.File f, int off, int n) { worker.execute(() -> { try {
        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        int samples = all.length / 2;
        int count = Math.max(0, Math.min(Math.min(n, 398), samples - off));
        if (count <= 0) { log("pcm: empty"); return; }
        byte[] chunk = java.util.Arrays.copyOfRange(all, off * 2, (off + count) * 2);
        engine.diagPcm(off, chunk);
        log(String.format(java.util.Locale.US, "pcm off=%d n=%d", off, count));
    } catch (Throwable t) { log("pcm: " + t); } }); }
    private volatile Thread symThread;
    private volatile boolean m17Down, m17Tx;
    private volatile long status4At;
    private volatile short[] m17Script;
    /** Bench: stream a packed-symbol file on command 0x40. Paces on the returned fill.
     *  eot or forceKey keys once eight chunks are in the ring (no 28 s watchdog, no microphone).
     *  eot sends the last chunk as op 7 so the module drops the carrier. Unless nounkey, eot then sends the unkey after status byte 4. */
    public void diagSym(java.io.File f) { diagSym(f, false, false, false); }
    public void diagSym(java.io.File f, boolean eot) { diagSym(f, eot, false, false); }
    public void diagSym(java.io.File f, boolean eot, boolean forceKey, boolean nounkey) {
        Thread old = symThread;
        if (old != null && old.isAlive()) { log("sym busy"); return; }
        Thread t = new Thread(() -> streamSym(f, eot, forceKey, nounkey), "m17-sym");
        symThread = t;
        t.start();
    }

    /**
     * One POCSAG transmission: every RIC gets the same text, in one key-up, through the symbol engine
     * (op 8 hold mode, then the bit stream). Null when it has started. Stock firmware cannot send pages.
     * A frequency that is not the current FM channel is tuned on the VFO at low power, then the key waits
     * out the retune.
     */
    public String sendPage(int baud, int hz, int[] rics, int func, String text, boolean sign) {
        if (rics == null || rics.length == 0) return "Add at least one RIC";
        if (text == null || text.trim().isEmpty()) return "Write the message";
        if (!ChannelStore.inBand(hz)) return "Frequency must be inside " + com.macdmr.radio.DeviceProfile.detect().bandsText();
        if (firmwareBusy) return "Radio busy, loading firmware";
        if (!isReady()) return "Radio not ready";
        if (txInhibited()) return "Transmit is off on this channel";
        if (!(com.macdmr.radio.Firmware.stubTag(moduleVersion) && com.macdmr.radio.Firmware.tagNumber(moduleVersion) >= 21))
            return "Pages send only when the module firmware has the symbol engine (FW21 or later)";
        Thread old = symThread;
        if (old != null && old.isAlive()) return "The symbol engine is busy";
        String body = sign ? com.macdmr.radio.ham.Pocsag.sign(com.macdmr.radio.Station.callsign(this), text.trim()) : text.trim();
        java.util.List<com.macdmr.radio.ham.Pocsag.Page> pages = new java.util.ArrayList<>();
        for (int ric : rics) pages.add(new com.macdmr.radio.ham.Pocsag.Page(ric, func, body));
        int[] bits;
        try { bits = com.macdmr.radio.ham.Pocsag.bits(pages); }
        catch (IllegalArgumentException e) { return e.getMessage(); }
        int rate = baud == 512 || baud == 2400 ? baud : 1200;
        if (com.macdmr.radio.ham.Pocsag.seconds(bits, rate) > 25) return "That page is longer than 25 seconds";
        final int[] packedBits = bits;
        Thread t = new Thread(() -> streamPage(rate, hz, packedBits, rics.length), "pocsag");
        symThread = t;
        t.start();
        for (int ric : rics) rememberSentPage(ric, func, rate, hz, body);
        return null;
    }

    private void streamPage(int baud, int hz, int[] bits, int nRic) {
        boolean keyed = false;
        boolean hopped = false;
        try {
            ChannelStore.Channel cur = current;
            int home = cur != null ? cur.number : 0;
            boolean same = cur != null && !cur.isDigital() && cur.power == 0 && cur.band == 1
                    && Math.abs(cur.rxFreq - hz) < 500 && Math.abs(cur.txFreq - hz) < 500;
            if (!same) {
                ChannelStore.Channel v = new ChannelStore.Channel();
                v.type = 1; v.rxFreq = v.txFreq = hz; v.band = 1; v.power = 0; v.sq = 2; v.name = "Pager";
                applyVfo(v, false);
                hopped = true;
                Thread.sleep(3500);
            }
            Frame rst = sym40(new byte[] { 3 });
            if (rst == null || rst.sr != 0) { log("page: no symbol engine"); history("warn", "⚠️ This firmware did not accept the pager waveform"); return; }
            Frame setup = null;
            for (int t = 0; t < 8 && (setup == null || setup.sr != 0); t++) {
                if (t > 0) Thread.sleep(40);
                setup = sym40(com.macdmr.radio.ham.Pocsag.waveform(baud));
            }
            if (setup == null || setup.sr != 0) { log("page: waveform not accepted"); history("warn", "⚠️ This firmware did not accept the pager waveform"); return; }
            byte[] packed = com.macdmr.radio.ham.Pocsag.pack(bits);
            int sent = 0;
            while (sent < packed.length) {
                int n = Math.min(48, packed.length - sent);
                boolean last = sent + n >= packed.length;
                int fill = sendSymChunk(packed, sent, n, last);
                if (fill < 0) return;
                sent += n;
                if (!keyed && (sent >= 48 * 8 || last)) { engine.ptt(true); keyed = true; log("page key"); }
                paceSym(fill, keyed);
            }
            history(home, "pkt", "📟 Page sent to " + nRic + (nRic == 1 ? " RIC" : " RICs") + " at " + baud + " baud");
            if (keyed) { unkeyAfterStop(); rxAfterSym(); }
        } catch (Throwable t) { log("page: " + t); history("warn", "⚠️ Page failed: " + t.getMessage()); }
        finally {
            if (keyed) { try { engine.ptt(false); } catch (Throwable ignored) { } holdAfterTx(); }
            if (hopped) exitVfo();
        }
    }
    private volatile Thread fwThread;
    /** Bench: load a module image with our own YModem sender. {@code abort50} closes the port halfway. */
    public void diagFwLoad(java.io.File f, boolean abort50) { diagFwLoad(f, abort50, -1); }

    /** {@code pauseMs} &lt; 0 keeps the measured pause before each data block, which is 0. */
    public void diagFwLoad(java.io.File f, boolean abort50, int pauseMs) {
        Thread old = fwThread;
        if (old != null && old.isAlive()) { log("fwload busy"); return; }
        if (f == null || profile == null || !profile.firmwareLoader()) { log("fwload refused"); return; }
        String path = f.getAbsolutePath();
        boolean allowed = path.startsWith("/sdcard/Download/DMR/Firmware/")
                || path.startsWith("/storage/emulated/0/Download/DMR/Firmware/");
        if (!allowed) { log("fwload refused " + path); return; }
        setFirmwareBusy(true);
        Thread job = new Thread(() -> {
            boolean diverted = false;
            try {
                byte[] image = imageBytes(f);
                if (image.length < 1024 || image.length > 2_000_000) { log("fwload bad length " + image.length); return; }
                if (!engine.isOpen()) engine.open();
                engine.startDivert();
                diverted = true;
                final boolean[] still = { true };
                com.macdmr.radio.fw.YModemSender sender = new com.macdmr.radio.fw.YModemSender(null, s -> log("fwload " + s));
                if (pauseMs >= 0) sender.blockPause(pauseMs);
                com.macdmr.radio.fw.YModemSender.Result r = sender.send(
                        image, f.getName(), engine.powerNode(), engine.pttNode(),
                        new com.macdmr.radio.fw.YModemSender.Wire() {
                            public void write(byte[] b) throws java.io.IOException { engine.writeRaw(b); }
                            public int read(int ms) throws java.io.IOException { return engine.readRaw(ms); }
                            public void close() { engine.close(); }
                        },
                        (node, high) -> SysfsGpio.write(node, high ? "1" : "0"),
                        abort50,
                        () -> {
                            engine.stopDivert();
                            still[0] = false;
                            return waitForTag();
                        });
                diverted = still[0];
                log("fwload " + r);
            } catch (Throwable t) {
                log("fwload io-error " + t.getMessage());
            } finally {
                if (diverted) engine.stopDivert();
                try { if (!engine.isOpen()) engine.open(); } catch (java.io.IOException e) { log("fwload reopen " + e.getMessage()); }
                setFirmwareBusy(false);
            }
        }, "fwload");
        fwThread = job;
        job.start();
    }

    private final java.util.concurrent.atomic.AtomicBoolean fwHalt = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean firmwareBusy;
    /** True while a firmware load owns the radio. Talk is refused and every screen says so. */
    public boolean firmwareLoading() { return firmwareBusy; }
    private void setFirmwareBusy(boolean on) {
        firmwareBusy = on;
        ui.post(() -> { for (Listener l : listeners) l.onLink(link); });
        updateNotification();
    }

    /** The firmware page: load one known image. {@code treatTagAsWrong} forces the factory restore after a good transfer. */
    public void loadFirmware(com.macdmr.radio.fw.ImageTable.Row row, byte[] image, com.macdmr.radio.fw.FirmwareSession.Ui ui, boolean treatTagAsWrong) {
        Thread old = fwThread;
        if (old != null && old.isAlive()) { ui.finished("A load is already running"); return; }
        if (profile == null || !profile.firmwareLoader()) { ui.finished("This phone's radio module cannot be loaded from SignalDeck"); return; }
        fwHalt.set(false);
        setFirmwareBusy(true);
        Thread job = new Thread(() -> {
            try { com.macdmr.radio.fw.FirmwareSession.run(this, row, image, ui, fwHalt, treatTagAsWrong); }
            finally { setFirmwareBusy(false); }
        }, "fwpage");
        fwThread = job;
        job.start();
    }

    /** Close the port at the next block. The page's Stop button. */
    public void haltFirmware() { fwHalt.set(true); }

    private byte[] imageBytes(java.io.File f) throws java.io.IOException {
        try { return java.nio.file.Files.readAllBytes(f.toPath()); }
        catch (java.io.IOException e) {
            java.io.File dst = new java.io.File(getCacheDir(), "fwload.bin");
            String r = RootSetup.run("cp '" + f.getAbsolutePath() + "' '" + dst.getAbsolutePath()
                    + "' && chown " + android.os.Process.myUid() + ":" + android.os.Process.myUid()
                    + " '" + dst.getAbsolutePath() + "' && echo ok");
            if (!r.endsWith("ok")) throw new java.io.IOException(r);
            return java.nio.file.Files.readAllBytes(dst.toPath());
        }
    }

    /** The module's version, after asking. Used by the firmware page once the packet reader is running again. */
    public String readModuleTag() throws java.io.IOException { return waitForTag(); }

    private String waitForTag() throws java.io.IOException {
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("interrupted"); }
        for (int pass = 0; pass < 3; pass++) {
            if (!engine.isOpen()) engine.open();
            long asked = System.currentTimeMillis();
            try { engine.queryVersion(); }
            catch (java.io.IOException e) { engine.close(); continue; }
            long end = asked + 8000;
            while (System.currentTimeMillis() < end) {
                if (versionAt >= asked && moduleVersion != null && moduleVersion.contains("DMR")) return moduleVersion.trim();
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("interrupted"); }
            }
            engine.close();
        }
        return moduleVersion == null ? "" : moduleVersion.trim();
    }

    /** Bench: one command 0x40 whose body is the file. Does not key. */
    public void diagRaw40(java.io.File f) { worker.execute(() -> {
        try {
            byte[] body = java.nio.file.Files.readAllBytes(f.toPath());
            if (body.length == 0 || body.length > 400) { log("raw40 bad " + body.length); return; }
            Frame pic = engine.diag40(body, 800);
            if (pic == null || pic.body == null) { log("raw40 none"); return; }
            java.nio.file.Files.write(new java.io.File(getCacheDir(), "raw40.bin").toPath(), pic.body);
            log("raw40 sr=" + pic.sr + " len=" + pic.body.length);
        } catch (Throwable t) { log("raw40: " + t); }
    }); }
    /** Bench: one status read of the symbol ring. Does not key. */
    public void diagSymStatus() { worker.execute(() -> logSymStatus(sym40(new byte[] { 2 }))); }
    /** Bench: reset, append one symbol chunk, render N samples. Does not key. Writes cache/render.bin. */
    public void diagSymRender(java.io.File f, int n) { worker.execute(() -> {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            if (data.length == 0 || data.length > 48 || n < 1 || n > 256) { log("render bad n=" + n + " bytes=" + data.length); return; }
            Frame rst = sym40(new byte[] { 3 });
            if (rst == null || rst.sr != 0) { log("render reset failed"); return; }
            byte[] body = new byte[1 + data.length];
            body[0] = 1;
            System.arraycopy(data, 0, body, 1, data.length);
            Frame ack = sym40(body);
            if (ack == null || ack.sr != 0) { log("render append failed"); return; }
            Frame pic = sym40(new byte[] { 4, (byte) n, (byte) (n >> 8) });
            if (pic == null || pic.body == null) { log("render missing"); return; }
            java.io.File out = new java.io.File(getCacheDir(), "render.bin");
            java.nio.file.Files.write(out.toPath(), pic.body);
            log("render bytes=" + pic.body.length + " sr=" + pic.sr);
        } catch (Throwable t) { log("render: " + t); }
    }); }
    /** Bench: op 6 applies variant 3 or 4 and leaves it, or restores it. action 1 applies, 0 restores. Does not key. */
    public void diagHold(int mode, int action) { worker.execute(() -> {
        try {
            Frame pic = sym40(new byte[] { 6, (byte) mode, (byte) action }, 1000);
            log("hold mode=" + mode + " action=" + action + (pic == null ? " missing" : " sr=" + pic.sr));
        } catch (Throwable t) { log("hold: " + t); }
    }); }
    /** Bench: op 5, n FM receive halves (4 or 6). mode 0, 2, 3 or 4. Does not key. Writes cache/rxram.bin. */
    public void diagRxRam(int mode) { diagRxRam(mode, 4); }
    public void diagRxRam(int mode, int n) { worker.execute(() -> {
        try {
            if (mode != 0 && mode != 2 && mode != 3 && mode != 4) { log("rxram bad mode"); return; }
            if (n != 4 && n != 6) { log("rxram bad n"); return; }
            byte[] body = n == 4 ? new byte[] { 5, (byte) mode } : new byte[] { 5, (byte) mode, (byte) n };
            java.util.List<Frame> got = engine.diag40n(body, n, 4000);
            if (got == null || got.isEmpty()) { log("rxram missing"); return; }
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            StringBuilder b = new StringBuilder();
            for (Frame pic : got) {
                byte[] part = pic.body == null ? new byte[0] : pic.body;
                o.write(part);
                if (b.length() > 0) b.append('+');
                b.append(part.length).append(" sr=").append(pic.sr);
            }
            java.nio.file.Files.write(new java.io.File(getCacheDir(), "rxram.bin").toPath(), o.toByteArray());
            log("rxram bytes=" + b + " mode=" + mode);
        } catch (Throwable t) { log("rxram: " + t); }
    }); }
    private Frame sym40(byte[] body) { return sym40(body, 500); }
    private Frame sym40(byte[] body, int waitMs) {
        try { return engine.diag40(body, waitMs); } catch (java.io.IOException e) { log("sym: " + e); return null; }
    }
    private void logSymStatus(Frame f) {
        if (f == null || f.body == null || f.body.length < 6) { log("sym status missing"); return; }
        byte[] s = f.body;
        int fill = (s[0] & 0xFF) | ((s[1] & 0xFF) << 8);
        int und = (s[2] & 0xFF) | ((s[3] & 0xFF) << 8);
        int err = (s[4] & 0xFF) | ((s[5] & 0xFF) << 8);
        log("sym status fill=" + fill + " underrun=" + und + " err=" + err + " sr=" + f.sr);
    }
    private void streamSym(java.io.File f, boolean eot, boolean forceKey, boolean nounkey) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            Frame rst = sym40(new byte[] { 3 });
            if (rst == null || rst.sr != 0) { log("sym reset failed"); return; }
            int off = 0, nsend = 0;
            boolean keyed = false;
            boolean arm = eot || forceKey;
            while (off < data.length) {
                int n = Math.min(48, data.length - off);
                byte[] body = new byte[1 + n];
                boolean last = eot && off + n >= data.length;
                body[0] = (byte) (last ? 7 : 1);
                System.arraycopy(data, off, body, 1, n);
                Frame ack = sym40(body);
                int wait = 0;
                while ((ack == null || ack.sr != 0) && wait < 8) {
                    Thread.sleep(40);
                    ack = sym40(body);
                    wait++;
                }
                if (ack == null) { log("sym no ack at " + off); return; }
                if (ack.sr != 0) { log("sym refused at " + off); return; }
                int fill = ack.body != null && ack.body.length > 1 ? ((ack.body[0] & 0xFF) | ((ack.body[1] & 0xFF) << 8)) : 0;
                off += n;
                nsend++;
                if (last) log("sym last op 7 fill=" + fill);
                if (nsend == 8) {
                    log("sym ready fill=" + fill);
                    if (arm && !keyed) {
                        engine.ptt(true);
                        keyed = true;
                        log("sym key");
                    }
                }
                if (nsend < 8) { Thread.sleep(5); continue; }
                int spins = 0;
                int spinCap = arm ? 400 : 150;
                while (fill > 1500 && spins < spinCap) {
                    Thread.sleep(20);
                    Frame s = sym40(new byte[] { 2 });
                    if (s != null && s.body != null && s.body.length > 1) fill = (s.body[0] & 0xFF) | ((s.body[1] & 0xFF) << 8);
                    spins++;
                }
                Thread.sleep(15);
            }
            if (arm && !keyed) { engine.ptt(true); keyed = true; log("sym key"); }
            log("sym done bytes=" + data.length);
            if (eot && keyed && !nounkey) { unkeyAfterStop(); rxAfterSym(); }
            else if (keyed && nounkey) log("sym nounkey");
        } catch (Throwable t) { log("sym: " + t); }
    }

    /** This firmware image has the symbol engine (command 0x40). Stock UV4T does not. */
    public boolean m17CanSend() { return Firmware.stubTag(moduleVersion); }

    /** Hold to talk on the M17 screen. The voice limit stays 28 s. Does nothing on stock firmware. */
    public void m17Voice(boolean down) {
        if (!down) { m17Down = false; return; }
        if (!m17CanSend()) { log("m17 voice: stock firmware"); history("warn", "⚠️ M17 transmit needs the module firmware with the symbol engine"); return; }
        if (current != null && current.isDigital()) { log("m17 voice: digital channel"); history("warn", "⚠️ M17 transmit needs an analog channel"); return; }
        if (symThread != null && symThread.isAlive()) { log("m17 voice: busy"); return; }
        m17Down = true;
        Thread t = new Thread(this::m17VoiceRun, "m17-voice");
        symThread = t;
        t.start();
    }

    /**
     * Send an M17 text through the symbol engine, the same path voice uses. Null when it has started.
     * Stock firmware and a digital channel get the same refusal voice does. The text is protocol 0x05.
     */
    public String sendM17Sms(String dest, String text) {
        if (!m17CanSend()) return "M17 transmit needs the module firmware with the symbol engine";
        if (current != null && current.isDigital()) return "M17 transmit needs an analog channel";
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) return "Write the message";
        if (symThread != null && symThread.isAlive()) return "M17 is already sending";
        String src = com.macdmr.radio.ham.M17.voiceCall(m17Call());
        ChannelStore.Channel mc = current;
        String raw = dest != null && dest.trim().length() > 0 ? dest.trim() : (mc != null && mc.isM17() ? mc.m17Dest() : "@ALL");
        String dst = raw.equalsIgnoreCase("@ALL") ? "@ALL" : com.macdmr.radio.ham.M17.voiceCall(raw);
        int can = mc != null && mc.isM17() ? mc.can() : 0;
        int[] syms = com.macdmr.radio.ham.M17.packetTransmission(src, dst, can, com.macdmr.radio.ham.M17.smsPayload(body));
        try {
            java.io.File f = new java.io.File(getCacheDir(), "m17-sms.sym");
            java.nio.file.Files.write(f.toPath(), com.macdmr.radio.ham.M17.packSymbols(syms));
            diagSym(f, true);
            M17Station st = m17; if (st != null) st.noteSent(src, dst, body);
            history("m17", "✉ " + src + " → " + dst + ": " + body);
            return null;
        } catch (java.io.IOException e) { return "Could not send the text"; }
    }

    /** Bench: one short Codec 2 tone through the symbol engine. Not a long key. */
    public void m17BenchTone(int ms) {
        ms = Math.max(800, Math.min(4000, ms));
        short[] pcm = new short[8000 * ms / 1000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (8000 * Math.sin(2 * Math.PI * 1000 * i / 8000.0));
        m17Script = pcm;
        m17Voice(true);
    }

    private void m17VoiceRun() {
        android.media.AudioRecord rec = null;
        com.macdmr.radio.ham.Codec2 c2 = null;
        boolean keyed = false;
        m17Tx = true;
        try {
            Frame rst = sym40(new byte[] { 3 });
            if (rst == null || rst.sr != 0) { log("m17 voice: symbol engine did not answer"); history("warn", "⚠️ This firmware did not accept an M17 symbol reset"); return; }
            String src = com.macdmr.radio.ham.M17.voiceCall(m17Call());
            ChannelStore.Channel mc = current;
            String dst = mc != null && mc.isM17() ? mc.m17Dest() : "@ALL";
            int lsfType = mc != null && mc.isM17() ? com.macdmr.radio.ham.M17.streamVoice(mc.can()) : 0x0005;
            byte[] lsf = com.macdmr.radio.ham.M17.lsf(src, dst, lsfType, null);
            int[] head = new int[192 + 192];
            System.arraycopy(com.macdmr.radio.ham.M17.preamble(), 0, head, 0, 192);
            System.arraycopy(com.macdmr.radio.ham.M17.lsfSymbols(lsf), 0, head, 192, 192);
            java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
            pending.write(com.macdmr.radio.ham.M17.packSymbols(head));
            c2 = new com.macdmr.radio.ham.Codec2(com.macdmr.radio.ham.Codec2.MODE_3200);
            int spf = c2.samples;
            short[] script = m17Script;
            if (script == null) {
                rec = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, 8000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(spf * 8, android.media.AudioRecord.getMinBufferSize(8000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)));
                if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) { log("m17 voice: no microphone"); return; }
                rec.startRecording();
            }
            byte[] primed = pending.toByteArray();
            int sent = 0;
            while (sent + 48 <= primed.length) {
                int fill = sendSymChunk(primed, sent, 48, false);
                if (fill < 0) return;
                sent += 48;
                if (sent / 48 == 8 && !keyed) { engine.ptt(true); keyed = true; log("m17 voice key " + src); }
                paceSym(fill, keyed);
            }
            short[] buf = new short[spf];
            byte[] half = null;
            int fn = 0, scriptAt = 0;
            long t0 = System.currentTimeMillis();
            boolean closed = false;
            while (!closed && fn < 700) {
                boolean release = !m17Down || System.currentTimeMillis() - t0 > 27500;
                if (script != null) {
                    int n = Math.min(spf, script.length - scriptAt);
                    if (n <= 0) release = true;
                    else { System.arraycopy(script, scriptAt, buf, 0, n); scriptAt += n; if (n < spf) java.util.Arrays.fill(buf, n, spf, (short) 0); }
                    if (scriptAt >= script.length) release = true;
                } else {
                    int n = rec.read(buf, 0, spf);
                    if (n < spf) java.util.Arrays.fill(buf, Math.max(0, n), spf, (short) 0);
                }
                byte[] enc = c2.encode(buf);
                if (half == null) {
                    half = enc;
                    if (!release) continue;
                    enc = c2.encode(new short[spf]);
                }
                byte[] pay = new byte[16];
                System.arraycopy(half, 0, pay, 0, Math.min(8, half.length));
                System.arraycopy(enc, 0, pay, 8, Math.min(8, enc.length));
                half = null;
                int frameNo = fn | (release ? 0x8000 : 0);
                if (script != null) {
                    StringBuilder hx = new StringBuilder(32);
                    for (byte b : pay) hx.append(String.format(java.util.Locale.US, "%02X", b & 0xFF));
                    log("m17 pay " + frameNo + " " + hx);
                }
                pending.write(com.macdmr.radio.ham.M17.packSymbols(com.macdmr.radio.ham.M17.streamSymbols(lsf, frameNo, pay, fn % 6)));
                fn++;
                if (release) pending.write(com.macdmr.radio.ham.M17.packSymbols(com.macdmr.radio.ham.M17.eotTail()));
                byte[] data = pending.toByteArray();
                while (sent + 48 <= data.length) {
                    boolean last = release && sent + 48 >= data.length;
                    int fill = sendSymChunk(data, sent, 48, last);
                    if (fill < 0) return;
                    sent += 48;
                    if (sent / 48 == 8 && !keyed) { engine.ptt(true); keyed = true; log("m17 voice key " + src); }
                    paceSym(fill, keyed);
                }
                if (release) closed = true;
            }
            byte[] data = pending.toByteArray();
            if (sent < data.length) {
                int fill = sendSymChunk(data, sent, data.length - sent, true);
                if (fill < 0) return;
                if (!keyed) { engine.ptt(true); keyed = true; log("m17 voice key " + src); }
                paceSym(fill, keyed);
            }
            log("m17 voice frames " + fn);
            if (keyed) { unkeyAfterStop(); rxAfterSym(); }
        } catch (Throwable t) { log("m17 voice: " + t); }
        finally {
            if (keyed) { try { engine.ptt(false); } catch (Throwable ignored) { } holdAfterTx(); }
            m17Down = false; m17Tx = false; m17Script = null;
            if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } rec.release(); }
            if (c2 != null) c2.close();
        }
    }

    /** The module's tone queue is usable: a bench image at or after FW22 (ops 10 and 11, and the tone-only key). */
    public boolean tonePath() { return Firmware.stubTag(moduleVersion) && Firmware.tagNumber(moduleVersion) >= com.macdmr.radio.ham.ToneQueue.FIRST_IMAGE; }

    /**
     * Plays a pair list through the module's tone queue instead of the audio path: op 3, bodies of op 10 with the
     * last as op 11, the key after the first body, top-ups while the queue drains, then the module's own end
     * (FW20) and the host unkey. Returns the reason it cannot start, or null; {@code done} runs after the unkey.
     */
    public String transmitTones(final java.util.List<com.macdmr.radio.ham.ToneQueue.Pair> pairs, final int amplitude, final String label, final Runnable done) {
        if (!tonePath()) return "The module's tone generator needs the bench firmware (FW22 or later)";
        if (!isReady()) return "Radio not ready";
        if (txInhibited()) return "Receive-only channel";
        if (pttDown || m17Tx) return "Already transmitting";
        if (pairs == null || pairs.isEmpty()) return "Nothing to send";
        final int ms = com.macdmr.radio.ham.ToneQueue.durationMs(pairs);
        if (ms > TX_MAX_MS) return "Too long for this radio (" + Math.round(ms / 1000.0) + " s; it allows " + (TX_MAX_MS / 1000) + " s)";
        if (ms > ThermalGuard.LONG_MS) { String wait = thermal.gate(ms); if (wait != null) return wait; }
        worker.execute(() -> toneRun(pairs, amplitude, label, done, ms));
        return null;
    }

    private void toneRun(java.util.List<com.macdmr.radio.ham.ToneQueue.Pair> pairs, int amplitude, String label, Runnable done, int ms) {
        boolean keyed = false;
        try {
            if (pttDown) { log("tones " + label + " skipped: PTT busy"); return; }
            Frame rst = sym40(new byte[] { 3 });
            if (rst == null || rst.sr != 0) { log("tones: no symbol engine"); history("warn", "\u26A0\uFE0F This firmware did not accept a tone-queue reset"); return; }
            if (ms > ThermalGuard.LONG_MS) thermal.beforeLong();
            pttDown = true; txStartedAt = System.currentTimeMillis(); pcmTxLabel = label; refreshControls();
            if (audio != null) audio.stopRx();
            java.util.List<byte[]> bodies = com.macdmr.radio.ham.ToneQueue.bodies(amplitude, pairs);
            for (int i = 0; i < bodies.size(); i++) {
                byte[] b = bodies.get(i);
                Frame ack = sym40(b); int tries = 0;
                while ((ack == null || ack.sr != 0) && tries < 8) { Thread.sleep(40); ack = sym40(b); tries++; }
                if (ack == null || ack.sr != 0) { log("tones refused at body " + i + " of " + bodies.size()); history("warn", "\u26A0\uFE0F The module refused the tone queue (" + label + ")"); return; }
                if (!keyed) { engine.ptt(true); keyed = true; log("tone key " + label + " (" + ms + " ms, " + bodies.size() + " bodies)"); }
                if (i + 1 < bodies.size()) {                                 // top up when the queue is below the mark; the queue only drains while keyed
                    int spins = 0;
                    while (spins++ < 2400) { Thread.sleep(50); if (queued() <= com.macdmr.radio.ham.ToneQueue.TOP_UP_AT) break; }
                }
            }
            long deadline = System.currentTimeMillis() + ms + 3000;        // the last pair plays out, then the module drops the carrier itself
            while (System.currentTimeMillis() < deadline) { Thread.sleep(100); if (queued() == 0) break; }
            unkeyAfterStop();
            history("tx", "\uD83D\uDD09 " + label + " through the module's tone generator (" + Math.round(ms / 1000.0) + " s)");
            if (done != null) done.run();
        } catch (Throwable t) { log("tones " + label + ": " + t); }
        finally {
            if (keyed) { try { engine.ptt(false); } catch (Throwable ignored) { } holdAfterTx(); }
            pttDown = false; thermal.after(ms); refreshControls();
            if (rxAudioOn && audio != null && (!agoldBridge() || receiving)) audio.startRx(true);
        }
    }

    /**
     * The in-key heat watchdog for a long transmission: every 5 s the AP zone and the battery; at a stop
     * ({@link ThermalGuard#stopNow}) the playback is ended at its next block and the history says why. Interrupted
     * by the sender when the transmission ends.
     */
    private Thread heatWatchdog(final String label) {
        Thread t = new Thread(() -> {
            double pz = Double.NaN, pb = Double.NaN; int n = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(5000);
                    double z = thermal.readC(), b = ThermalGuard.batteryC(RadioService.this);
                    String stop = ThermalGuard.stopNow(z, b, pz, pb);
                    if (stop != null) {
                        log("heat stop during " + label + ": " + stop);
                        history("warn", "\u26D4 " + label + " ended early: " + stop + ". Let the phone cool before the next long transmission.");
                        if (audio != null) audio.abortPlay();
                        return;
                    }
                    if (++n % 2 == 0) { pz = z; pb = b; }                      // the 10 s comparison
                }
            } catch (InterruptedException ignored) { }
        }, "heat-watch");
        t.setDaemon(true); t.start();
        return t;
    }

    /** Tone pairs still queued in the module (op 2 with a following byte of 1); 0 when it does not answer. */
    private int queued() {
        Frame s = sym40(new byte[] { 2, 1 });
        return s != null && s.body != null && s.body.length > 1 ? ((s.body[0] & 0xFF) | ((s.body[1] & 0xFF) << 8)) : 0;
    }

    private String m17Call() {
        try { if (packet != null && packet.callsignSet()) return packet.callsign(); } catch (Throwable ignored) { }
        return getSharedPreferences("packet", MODE_PRIVATE).getString("callsign", "");
    }

    /** Send one symbol chunk. The last chunk of a transmission is op 7 so the module drops the carrier.
     *  Returns the ring fill, or -1 if the module refused. Does not wait for the ring: it only drains while the transmitter is keyed, so the caller keys on the eighth chunk and then paces. */
    private int sendSymChunk(byte[] data, int off, int n, boolean last) throws InterruptedException {
        byte[] body = new byte[1 + n];
        body[0] = (byte) (last ? 7 : 1);
        System.arraycopy(data, off, body, 1, n);
        Frame ack = sym40(body);
        int wait = 0;
        while ((ack == null || ack.sr != 0) && wait < 8) { Thread.sleep(40); ack = sym40(body); wait++; }
        if (ack == null || ack.sr != 0) { log("m17 voice refused at " + off); return -1; }
        if (last) log("m17 voice last op 7");
        return ack.body != null && ack.body.length > 1 ? ((ack.body[0] & 0xFF) | ((ack.body[1] & 0xFF) << 8)) : 0;
    }

    /** Before the key, a short pause. After it, wait while the ring is over 1500 symbols, the same pace as a symbol-file stream. */
    private void paceSym(int fill, boolean keyed) throws InterruptedException {
        if (!keyed) { Thread.sleep(5); return; }
        int spins = 0;
        while (fill > 1500 && spins < 400) {
            Thread.sleep(20);
            Frame s = sym40(new byte[] { 2 });
            if (s != null && s.body != null && s.body.length > 1) fill = (s.body[0] & 0xFF) | ((s.body[1] & 0xFF) << 8);
            spins++;
        }
        Thread.sleep(15);
    }

    /** Op 7 drops the carrier and leaves the module timer running. The unkey stops that timer. */
    private void unkeyAfterStop() throws InterruptedException, java.io.IOException {
        long seen = status4At;
        long t0 = System.currentTimeMillis();
        while (status4At == seen && System.currentTimeMillis() - t0 < 2500) Thread.sleep(20);
        if (status4At != seen && !rewriteAfterSym(moduleVersion)) { log("sym unkey: the module ended the key itself (FW31+), no 0x26 off"); holdAfterTx(); return; }   // a second close would mute the receiver again (bench 2026-10-28)
        engine.ptt(false);
        log("sym unkey");
        holdAfterTx();
    }
    public void diagPowerCycle() { worker.execute(() -> { try { if (audio != null) audio.stopRx(); log("power-cycling the module"); powerCycleOrGiveUp(); if (!engine.waitBooted(12000)) log("no banner after power-cycle"); engine.queryVersion(); if (current != null) applyChannelNow(current, current.id > 0); if (rxAudioOn && audio != null && (!agoldBridge() || receiving)) audio.startRx(true); } catch (Throwable t) { log("power-cycle: " + t); } }); }
    /** Bench (DiagReceiver): transmit an 8 kHz mono 16-bit WAV from the app's cache on the current channel/VFO (at most 28 s). */
    public String diagTxWav(java.io.File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath()); int n = (b.length - 44) / 2;
            if (n <= 0 || n > 8000 * 28) return "bad length";
            short[] pcm = new short[n]; for (int i = 0; i < n; i++) pcm[i] = (short) ((b[44 + 2 * i] & 0xFF) | (b[45 + 2 * i] << 8));
            transmitPcm(pcm, "bench wav", null); return "transmitting " + n / 8 + " ms";
        } catch (Exception e) { return "txwav: " + e; }
    }
    /** Bench: the gateway route's sample factor until the next restart (the profile's txAudioScale otherwise). */
    public String diagTxScale(float v) { return audio instanceof CamcorderAudio ? ((CamcorderAudio) audio).setTxScale(v) : "no gateway route on this phone"; }
    public void diagTestTone(int ms) { diagTestTone(ms, false); }
    /** lift is one bench run, at most 40 s, past the 28.5 s refusal. The voice watchdog stays 28 s for every other transmission. */
    public void diagTestTone(int ms, boolean lift) { diagTestTone(ms, lift, 1000, 12000); }
    /** Bench: a test tone of {@code hz} at {@code amp} (of 32767). */
    public void diagTestTone(int ms, boolean lift, int hz, int amp) {
        if (ms < 200) ms = 200;
        if (lift) ms = Math.min(ms, 40000); else ms = Math.min(ms, 5000);
        short[] pcm = new short[8000 * ms / 1000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (amp * Math.sin(2 * Math.PI * hz * i / 8000.0));
        transmitPcm(pcm, hz + " Hz test tone " + ms + " ms", null, lift);
    }
    /** Bench: the transmit path's frequency response in one key, 400 ms per tone at {@code amp}, a 100 ms gap between them. */
    static final int[] SWEEP_HZ = { 100, 150, 200, 250, 300, 400, 500, 700, 1000, 1500, 2000, 2500, 3000, 3500 };
    public void diagToneSweep(int amp) {
        int per = 3200, gap = 800;
        short[] pcm = new short[SWEEP_HZ.length * (per + gap)];
        for (int k = 0; k < SWEEP_HZ.length; k++)
            for (int i = 0; i < per; i++) pcm[k * (per + gap) + i] = (short) (amp * Math.sin(2 * Math.PI * SWEEP_HZ[k] * i / 8000.0));
        transmitPcm(pcm, "tone sweep 100-3500 Hz", null, true);
    }

    /** Ask the module for the signal strength (on the radio worker: never a serial write on the main thread). */
    /** A signal reading for a display (the S-meter, SignalDeck Web); held on the Atom XL during a reception ({@link AgoldUsb#holdsRxRead}). */
    public void pollRssi() { pollRssi(false); }
    /** A signal reading for a measurement (fox hunt, coverage, checks): taken during a reception too, at the cost of a possible capture drop on the Atom XL. */
    public void pollRssiMeasure() { pollRssi(true); }
    private void pollRssi(boolean measure) { if (scope.running() || engine.rawSession()) return;
        if (!measure && AgoldUsb.holdsRxRead(agoldBridge(), receiving, synthRx)) return;
        worker.execute(() -> { try { if (engine.isOpen() && !engine.rawSession()) engine.queryRssi(); } catch (IOException ignored) { } }); }

    // ---- listeners ----------------------------------------------------------------------------------

    public void addListener(Listener l) { listeners.add(l); l.onLink(link); if (current != null) l.onChannel(current); l.onModes(softSqOn(), softSqLevel(), squelchOpen, monitorOn, recordOn, scanner.isScanning(), scanner.isHolding(), zoneName()); l.onCaption(captionsOn, "", null); l.onRx(receiving); l.onTx(transmitting); if (rssi != -999) l.onRssi(rssi); if (stockImportPending) l.onStockImportOffer(); }   // a page built later (intercom look switched) starts from the live receive/transmit state, not "idle"
    public void removeListener(Listener l) { listeners.remove(l); }
    private void noChannel() { ui.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onNoChannel(); }); }
    private final Runnable channelsChanged = () -> { for (Listener l : new ArrayList<>(listeners)) l.onChannels(); };

    private void setLink(String s) { link = s; ui.post(() -> { for (Listener l : listeners) l.onLink(s); updateModeWake(); }); updateNotification(); }

    private volatile long softOpenAt;
    private long lastSqDiag;
    /** Soft SQ gate transitions: the module reports a continuous reception while its squelch is held open, so the "Received N s" line comes from the gate instead. */
    private void softGateHistory(boolean open) {
        long now = System.currentTimeMillis();
        if (open) { softOpenAt = now; return; }
        long len = softOpenAt > 0 ? now - softOpenAt : 0; softOpenAt = 0;
        if (current != null && !current.isDigital() && len >= 700) history("rx", "📻 Received " + Math.round(len / 1000.0) + " s");
    }

    public void history(String kind, String text) { history(current != null ? current.number : 0, kind, text); }

    /** {@code chNum} is the channel the line belongs to. A page sent from a temporary VFO still records on the channel the radio left. */
    public void history(int chNum, String kind, String text) {
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "\u00B7");   // no control characters on screen (they also break screen readers)
        store.addHistory(chNum, kind, text, rssi, lastCaller);
        String line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()) + " " + text + (rssi != -999 ? " " + rssi + " dBm" : "");
        ui.post(() -> { for (Listener l : listeners) l.onHistory(line); });
    }

    // ---- RadioEngine.Events -------------------------------------------------------------------------

    /** Last 300 log lines for the Diagnostics page. Lines reach the pages only while that page is open: posting every
     *  serial frame (ten a second while receiving) to the main thread slowed the intercom down over a session. */
    private final java.util.ArrayDeque<String> logRing = new java.util.ArrayDeque<>();
    private volatile boolean uiLog;
    private final java.text.SimpleDateFormat logTf = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    public void setUiLog(boolean on) { uiLog = on; }
    public java.util.List<String> recentLog() { synchronized (logRing) { return new java.util.ArrayList<>(logRing); } }

    /** First-run check: bytes the receive audio path has delivered, and a raw copy of the next few seconds (UART PCM phones). */
    private volatile long rxPcmBytes;
    private volatile ClipRecorder clip; private volatile boolean clipOnlyHeard;
    /** Record the received audio into a WAV clip (null stops); {@code onlyHeard}: only while something is being heard. */
    public void recordClip(ClipRecorder c, boolean onlyHeard) { ClipRecorder old = clip; clipOnlyHeard = onlyHeard; clip = c; if (old != null && old != c) old.finish(); }

    public String audioStats() { AudioBackend a = audio; return a instanceof UartPcmAudio ? ((UartPcmAudio) a).frameStats() : ""; }
    public byte[] rxAudioHead() { AudioBackend a = audio; return a instanceof UartPcmAudio ? ((UartPcmAudio) a).head() : null; }
    public long rxAudioBytes() { AudioBackend a = audio; return a instanceof UartPcmAudio ? ((UartPcmAudio) a).rxBytes() : rxPcmBytes; }
    public String captureRxAudio(java.io.File f, int ms) {
        AudioBackend a = audio;
        if (a instanceof UartPcmAudio) return ((UartPcmAudio) a).capture(f, ms);
        long before = rxPcmBytes;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
        return (rxPcmBytes - before) + " bytes in " + ms + " ms from the vendor audio service (not saved)";
    }

    @Override public void log(String s) {
        Log.i(TAG, s);
        synchronized (logRing) { logRing.addLast(logTf.format(new java.util.Date()) + " " + s); while (logRing.size() > 300) logRing.removeFirst(); }
        if (uiLog) ui.post(() -> { for (Listener l : listeners) l.onLog(s); });
    }

    /** The transmit test session's view of the module's status replies (Device report → Transmit test session); null otherwise. */
    public volatile java.util.function.BiConsumer<Integer, String> statusTap;

    @Override public void status(int code, String name) {
        java.util.function.BiConsumer<Integer, String> tap = statusTap; if (tap != null) try { tap.accept(code, name); } catch (Throwable ignored) { }
        switch (code) {
            case 1: if (agoldBridge() && receiving) { if (synthRx) { synthRx = false; lineReported(); log("reception line caught up with the signal"); if (!captureRunning()) bridgeCapture(true); } break; }   // a capture closed for silence reopens   // already open: an edge after a signal-started reception
                if (agoldBridge() && !synthRx) lineReported();           // an edge from the line itself
                receiving = true; dmrEvent("start", 0, 0, 0);
                if (current != null && current.isDigital()) {           // a DMR call: forget the last talker, ask who this is now and again a second later
                    lastCaller = 0; lastCallerCc = -1; callLogged = false;
                    if (!agoldBridge()) for (long ms : new long[] { 300, 1200 }) ui.postDelayed(() -> { if (receiving) worker.execute(() -> { try { engine.queryCallerInfo(); } catch (IOException ignored) { } }); }, ms);
                }
                bridgeReception(true);
                refreshControls(); rxStart = System.currentTimeMillis(); rxCounts = rxReportsReception(); softSq.rxStarted(); ui.post(() -> { for (Listener l : listeners) l.onRx(true); }); pollRssi(); break;
            case 2: {
                synthRx = false; monRx = false;
                dmrEvent("stop", 0, 0, 0);
                receiving = false; bridgeReception(false); refreshControls(); long len = rxStart > 0 ? System.currentTimeMillis() - rxStart : 0; rxStart = 0;
                if (softSq.isEnabled() && squelchOpen) { softSq.closeNow(); softGateHistory(false); squelchOpen = false; log("soft squelch closed (carrier gone)"); }
                softSq.awaitRxStart();                                   // with the module squelch held open a RECEIVE_STOP only happens while the receiver re-settles
                if (current != null && current.isDigital() && !agoldBridge()) { try { engine.queryCallerInfo(); } catch (IOException ignored) { } }   // who was that (0x2B); the Atom XL asks after its capture closes
                if (current != null && !current.isDigital() && !softSq.isEnabled() && rxCounts && len >= 700) history("rx", "📻 Received " + Math.round(len / 1000.0) + " s");   // with Soft SQ on the soft gate writes this line
                finishRecording();
                toneBank.reset(); toneAnnounced = -1;
                if (captions != null) captions.stop();
                if (captionsOn && "google".equals(captionEngine())) googleFlush();
                ui.post(() -> { for (Listener l : listeners) l.onRx(false); });
                break;
            }
            case 3: transmitting = true; refreshControls(); if (coverage != null) coverage.onTxStart(); ui.post(() -> { for (Listener l : listeners) l.onTx(true); }); break;
            case 4: status4At = System.currentTimeMillis(); transmitting = false; refreshControls(); if (coverage != null) coverage.onTxEnd(); String pl = pcmTxLabel; pcmTxLabel = null; history(m17Tx ? "m17" : "tx", m17Tx ? "📻 M17 TX" : pl != null ? "📻 TX: " + pl : "📻 Voice TX");
                if (pttDown && !m17Tx && !unkeying && System.currentTimeMillis() - txStartedAt > 100_000) history("warn", "\u23F1 The radio ended the transmission itself: its 120 s time-out"); ui.post(() -> { for (Listener l : listeners) l.onTx(false); }); break;
            case 5: try { engine.fetchSms(); } catch (IOException ignored) { } break;             // SMS_RECEIVED -> ask for it
            case 8: smsResult(true); break;
            case 9: smsResult(false); break;
            case 7: history("busy", "⚠ Channel busy"); break;
            default: log("status " + name);
        }
    }

    /**
     * Atom XL: the capture follows each reception, as the stock app's openPcmIn / closePcmIn do, and the caller is asked
     * around it. A capture held open from take-over stopped with an I/O error after 3-17 s on the bench (2026-10-28, cause
     * still open; by hand the same capture runs for minutes). Other phones: nothing.
     */
    private void bridgeCapture(boolean on) {
        if (!agoldBridge()) return;
        final AudioBackend a = rxAudioOn && audio instanceof AlsaDirectAudio ? audio : null;
        final boolean dmr = current != null && current.isDigital();
        if (on && synthRx && !monRx) {                                       // found from the signal only: no capture yet (AgoldUsb.captureOpenDelay)
            long wait = AgoldUsb.captureOpenDelay(true, false, System.currentTimeMillis() - synthStartAt);
            if (wait > 0) {
                if (a != null && ((AlsaDirectAudio) a).capturing()) worker.execute(() -> { try { a.stopRx(); } catch (Throwable ignored) { } });   // the last reception's tail: not this one's, and it would run into the bridge's I/O error
                log("reception found from the signal: the capture waits " + wait + " ms for the line or for the reception to last");
                ui.postDelayed(() -> { if (receiving && synthRx && !captureRunning()) bridgeCapture(true); }, wait);
                return;
            }
        }
        worker.execute(() -> {
            try {
                // while the bridge streams audio it cuts every frame on the port after 12 bytes (measured 2026-10-28), so
                // the caller (a 20-byte 0x2B reply) is asked before the capture opens and after it closes, as the stock app does
                if (on) {
                    // analog, still open from the last reception's tail: keep it running (a quick reopen can come back empty)
                    if (!dmr && a != null && ((AlsaDirectAudio) a).capturing()) return;
                    if (a != null) a.stopRx(); if (dmr) { engine.queryCallerInfo(); Thread.sleep(150); }
                    if (a != null) {
                        final long bytes0 = rxAudioBytes();
                        String r = a.startRx(true); log(r);
                        if (synthRx) ui.postDelayed(() -> {
                            if (!receiving || !AgoldUsb.closeSilentSignalOnlyCapture(synthRx, monRx, rxAudioBytes() - bytes0) || !captureRunning()) return;
                            log("no audio " + AgoldUsb.SIGNAL_ONLY_AUDIO_WAIT_MS + " ms into a reception found from the signal (another transmitter close by, most likely): capture closed before the bridge times out");
                            worker.execute(() -> { try { a.stopRx(); } catch (Throwable t) { log("capture close: " + t); } });
                        }, AgoldUsb.SIGNAL_ONLY_AUDIO_WAIT_MS);
                        if (r != null && r.contains("no audio block yet") && !(synthRx && !monRx)) recoverBridgeAudio("the USB capture delivered nothing");   // a signal-only one is closed for silence above instead
                    } }
                else { if (a != null) a.stopRx(); if (packet != null && !dmr) packet.streamEnded(); if (dmr) { Thread.sleep(100); engine.queryCallerInfo(); } }
            } catch (Throwable t) { log("bridge capture: " + t); }
        });
    }

    /**
     * The Atom XL's capture holds up to half a second of audio behind the radio (16 periods of 32 ms), and an APRS packet
     * sits at the end of its transmission: closing at the module's reception end dropped the packet (2026-10-28). So the
     * close waits this long, and a reception that starts meanwhile cancels it.
     */
    static final long BRIDGE_TAIL_MS = 700;
    private final java.util.concurrent.atomic.AtomicInteger captureGen = new java.util.concurrent.atomic.AtomicInteger();
    private void bridgeReception(boolean on) {
        if (!agoldBridge()) return;
        final int g = captureGen.incrementAndGet();
        if (on) { bridgeCapture(true); return; }
        ui.postDelayed(() -> { if (captureGen.get() == g) bridgeCapture(false); }, BRIDGE_TAIL_MS);
    }

    @Override public void bridgeFault(String why) { recoverBridgeAudio(why); }

    // ---- Atom XL: a reception line that never reports (measured 2026-10-28, the stock app misses every call then too) ----
    static final long LINE_CHECK_MS = 5000;
    private volatile boolean lineFallback; private int lineCheckTries;
    /**
     * After a bring-up the bridge's /dev/stmcu delivers its power-up toggle within a second or two. When nothing at all has
     * arrived after 5 s the line is stuck in its power-up state and will report no reception: power-cycle the bridge once;
     * if it stays silent, receptions come from the signal reading ({@link #rxWatchdog}) until it reports again.
     */
    private final Runnable lineCheck = new Runnable() { @Override public void run() {
        if (!agoldBridge() || exiting || !isReady() || engine.receptionLineBytes() != 0) return;
        if (lineCheckTries++ == 0) {
            log("reception line silent " + (LINE_CHECK_MS / 1000) + " s after bring-up: power-cycling the bridge once");
            worker.execute(() -> { try { powerCycleOrGiveUp(); if (current != null) applyChannelNow(current, current.id > 0); } catch (Throwable t) { log("reception line: " + t); } });
            ui.postDelayed(this, LINE_CHECK_MS + 6000);
            return;
        }
        log("reception line still silent: receptions come from the signal reading (RSSI) until it reports again");
        history("warn", "\u26A0 The radio's reception signal is not working: SignalDeck detects calls from the signal strength (a little later), and the sound may drop out. Restart the phone to fix it");
        lineFallback = true;
    } };

    /**
     * Atom XL reception watchdog, always on: the bridge's line also misses receptions now and then after a long one (and the
     * stock app then misses them too). Idle, the signal is read every second; a signal above the start threshold twice with
     * no reception reported starts one ({@link #synthRx}), two readings below the end threshold end it and rewrite the
     * channel, which brought the line back on the bench (2026-10-28). The thresholds follow the channel's idle floor
     * ({@link AgoldUsb#rssiStartDbm}). An edge that arrives meanwhile is merged.
     */
    private volatile boolean synthRx; private volatile int strongCount, lowCount;
    private volatile long synthStartAt;
    private volatile double idleFloor = Double.NaN;                     // dBm, while nothing is received; reset on a channel write
    private final Runnable rxWatchdog = new Runnable() { @Override public void run() {
        if (!agoldBridge() || exiting) return;
        if (isReady() && !isTransmitting() && (!receiving || synthRx)) pollRssi();
        ui.postDelayed(this, synthRx || lineFallback ? 500 : 1000);
    } };
    private void agoldRssi(double dbm) {
        if (!agoldBridge() || isTransmitting()) return;
        if (!receiving) {
            idleFloor = AgoldUsb.nextFloor(idleFloor, dbm);
            if (dbm > AgoldUsb.rssiStartDbm(idleFloor)) { if (++strongCount >= (lineFallback ? 1 : 2)) { strongCount = 0; lowCount = 0; synthRx = true; synthStartAt = System.currentTimeMillis(); log("signal " + Math.round(dbm) + " dBm with no reception reported: treated as a reception"); status(1, RadioEngine.statusName(1)); } }
            else strongCount = 0;
        } else if (synthRx && !monRx) {                                  // a MON reception ends with MON
            if (dbm < AgoldUsb.rssiEndDbm(idleFloor)) { if (++lowCount >= 2) { lowCount = 0;
                long held = System.currentTimeMillis() - synthStartAt;
                final boolean counts = AgoldUsb.countsAsLineMiss(held);       // a short off-channel blip (source in the cage not identified) read -87 dBm here: not a reception the line missed
                if (!counts) log("signal reception of " + held + " ms with no line edge: too short to count against the reception line");
                final boolean restart = counts && lineMissedOne(); synthRx = false; status(2, RadioEngine.statusName(2));
                if (current != null) worker.execute(() -> { try { boolean cycled = restart && autoCycle("stuck reception line"); if (restart && !cycled) lineRestarts--; /* held back: the restart is still owed */ if (cycled) powerCycleOrGiveUp(); applyChannelNow(current, current.id > 0); log(cycled ? "bridge restarted and channel rewritten: the reception line looked stuck" : "channel rewritten after a reception the line did not report"); } catch (Throwable t) { log("channel rewrite: " + t); } }); } }
            else lowCount = 0;
        }
    }

    /** Receptions in a row the line did not report, and bridge restarts for it, since the bring-up (Device report). */
    private volatile int lineMissed, lineRestarts;
    private void lineReported() {
        lineMissed = 0;
        if (lineFallback) { lineFallback = false; log("reception line reports again"); }
    }
    /** A reception the line never reported has ended. True when the bridge should be restarted for it now. */
    private boolean lineMissedOne() {
        int a = AgoldUsb.stuckLineAction(++lineMissed, lineRestarts, lineFallback);
        if (a == 1) { lineRestarts++; lineMissed = 0; log("reception line: " + AgoldUsb.STUCK_LINE_MISSES + " receptions in a row it did not report: restarting the bridge"); return true; }
        if (a == 2) {
            lineFallback = true;
            log("reception line still misses receptions after a bridge restart: receptions come from the signal reading (RSSI)");
            history("warn", "⚠ The radio's reception signal is stuck: SignalDeck detects calls from the signal strength (a little later). Restart the phone to fix it");
        }
        return false;
    }
    /** For the Device report: how the Atom XL's reception line is doing; null on other phones. */
    public String receptionLineState() {
        if (!agoldBridge()) return null;
        return (lineFallback ? "stuck (receptions from the signal reading)" : "reporting") + " · " + engine.receptionLineBytes() + " bytes since the bridge came up · "
                + lineMissed + " unreported in a row · " + lineRestarts + " bridge restart" + (lineRestarts == 1 ? "" : "s") + " for it";
    }

    /** Atom XL: put "Disable USB audio routing" back as take-over found it. */
    private void restoreUsbRouting() {
        android.content.SharedPreferences rp = getSharedPreferences("radio", MODE_PRIVATE);
        String prev = rp.getString("agoldUsbRouting", null);
        if (prev == null) return;
        AgoldUsb.usbRoutingRestore(prev); rp.edit().remove("agoldUsbRouting").apply();
        log("USB audio routing restored (" + prev + ")");
    }

    private volatile long lastBridgeRecoverAt;
    /**
     * Atom XL: the bridge stops streaming capture audio after 20-70 s of one capture (measured 2026-10-28, also with no
     * SignalDeck involved) and reports "pcm error"; its stock app then resets it. Power-cycle the bridge, put the channel
     * back and reopen the capture if a reception is still on. At most once every 20 s, never while transmitting.
     */
    /**
     * A module restart while SignalDeck owns the radio. On the Atom XL a failed one leaves the bridge off and the port gone:
     * then the radio is let go the way a failed bring-up does it (USB routing back, the bridge checked before the stock
     * app returns) and the link shows the error, instead of "ready" with the radio off.
     */
    /**
     * Our own bridge power-cycles end the capture too: that end is not a fault and must not queue a recovery of its own
     * (2026-10-29 17:06: a stuck-line restart killed the capture that had just opened, its "File descriptor in bad state"
     * queued a second power-cycle, and the two back to back left the module silent for a third).
     */
    private volatile long ownCycleUntil;
    // one minimum gap between automatic bridge power-cycles (stuck line, capture recovery, module watchdog): back to back
    // they left the module silent (2026-10-29 17:07); the user's own Exit / Take over does not go through these paths
    private volatile long lastAutoCycleAt; private volatile boolean moduleWatchDeferred;
    private long autoCycleWait() { return agoldBridge() ? Math.max(0, AgoldUsb.AUTO_CYCLE_GAP_MS - (System.currentTimeMillis() - lastAutoCycleAt)) : 0; }
    /** True (and the gap starts) when an automatic power-cycle may run now; logs and returns false inside the gap. */
    private boolean autoCycle(String why) {
        long w = autoCycleWait();
        if (w > 0) { log("bridge restart for the " + why + " held back: another automatic restart ran " + (AgoldUsb.AUTO_CYCLE_GAP_MS - w) / 1000 + " s ago"); return false; }
        lastAutoCycleAt = System.currentTimeMillis(); return true;
    }
    private void captureEnded(String err) {
        if (agoldBridge() && System.currentTimeMillis() < ownCycleUntil) { log("the capture ended with our own bridge restart: no recovery (" + err + ")"); return; }
        recoverBridgeAudio("the USB capture ended by itself (" + err + ")");
    }
    private void powerCycleOrGiveUp() throws IOException {
        if (agoldBridge()) {
            ownCycleUntil = System.currentTimeMillis() + 30_000;            // until the cycle is done (cleared to a short grace below)
            try { if (audio != null) audio.stopRx(); } catch (Throwable ignored) { }
        }
        try { powerCycleOrGiveUp0(); }
        finally { if (agoldBridge()) ownCycleUntil = System.currentTimeMillis() + 1500; }
    }
    private void powerCycleOrGiveUp0() throws IOException {
        long settle = agoldBridge() ? AgoldUsb.plugSettleWait(System.currentTimeMillis(), lastPlugChangeAt) : 0;
        if (settle > 0) {                                                    // the stock app leaves the bridge alone for 4 s after a charger change
            log("bridge power-up held " + settle + " ms after a charger or USB change");
            try { Thread.sleep(settle); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        try { engine.powerCycle(); }
        catch (IOException e) { if (agoldBridge()) bridgeLost(e); throw e; }
        if (agoldBridge()) { ui.removeCallbacks(windowCheck); ui.postDelayed(windowCheck, AgoldUsb.POWER_UP_EDGES_MS + 300); }
    }
    /**
     * After a bridge power-up the line's edges are ignored for {@link AgoldUsb#POWER_UP_EDGES_MS} (the bridge toggles it),
     * so a reception that began or ended meanwhile was missed: a restart in the middle of a carrier swallowed its end and
     * SignalDeck stayed "receiving" until the next carrier stopped (2026-10-29). When the window ends, the driver's own flag
     * settles it; a start also needs the signal reading to agree (the flag can be stale on a stuck line).
     */
    private final Runnable windowCheck = () -> new Thread(() -> {             // its own thread: the worker is still busy with the restart that powered the bridge up, and the link is not "ready" yet
        if (!agoldBridge() || exiting || isTransmitting() || !engine.isOpen()) return;
        String v = RootSetup.run("cat " + AgoldUsb.CALL_IN).trim();
        int act = AgoldUsb.windowAction(receiving, v, rssi, AgoldUsb.rssiStartDbm(idleFloor));
        if (act == 2) ui.post(() -> { if (receiving && !monRx) { /* MON holds its own reception open without the line */ log("reception ended during the bridge power-up (call_in " + v + ")"); synthRx = false; status(2, RadioEngine.statusName(2)); } });
        else if (act == 1) ui.post(() -> { if (!receiving) { log("reception in progress after the bridge power-up (call_in " + v + ", " + rssi + " dBm)"); synthRx = true; status(1, RadioEngine.statusName(1)); } });
        // a bridge recovery leaves the capture closed until this check has settled whether a reception is still on (a
        // capture it reopened at once met a reception whose end the power-up had hidden, got nothing and ended with EIO
        // 2 s later, which started the next recovery: 2026-10-29 14:47); a reception that starts here opens its own
        final boolean flagKnown = AgoldUsb.callInKnown(v);
        if (!flagKnown) log("reception line: call_in unreadable after the power-up (" + v + "): the signal reading decides whether the capture reopens");
        final boolean signal = rssi > AgoldUsb.rssiStartDbm(idleFloor);
        if (act != 1) ui.post(() -> {
            if (!receiving || !rxAudioOn || audio == null || captureRunning()) return;
            if (!flagKnown && !signal && !monitorOn) { log("no signal after the power-up and no flag: the capture stays closed"); return; }
            worker.execute(() -> { try { log(audio.startRx(true)); } catch (Throwable t) { log("capture after the power-up: " + t); } });
        });
    }, "agold-window-check").start();
    private void bridgeLost(Throwable t) {
        log("the USB bridge did not come back: " + t);
        ui.removeCallbacks(rxWatchdog); ui.removeCallbacks(lineCheck); ui.removeCallbacks(moduleWatch);
        try { if (audio != null) audio.stopRx(); } catch (Throwable ignored) { }
        try { engine.close(); } catch (Throwable ignored) { }
        restoreUsbRouting();
        log("hand-back after the lost bridge: " + AgoldUsb.giveBack(profile, getFilesDir()).replace('\n', ' '));
        history("warn", "⚠ The radio's USB bridge did not come back after a restart: the radio is off and back with the stock app. Take over again, or restart the phone if it keeps happening");
        setLink("error: the USB bridge did not come back (" + t.getMessage() + ")");
    }

    // A bridge power-up right at a charger change left the Atom XL's bridge taking no serial data (2026-10-29 10:11,
    // the cable pulled during a recovery); the stock app restarts the bridge 4 s after every charger change.
    private volatile long lastPlugChangeAt;
    private android.content.BroadcastReceiver plugSettle;
    private void watchPlugChanges() {
        plugSettle = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) { lastPlugChangeAt = System.currentTimeMillis(); }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED); f.addAction(android.content.Intent.ACTION_POWER_DISCONNECTED);
        try { registerReceiver(plugSettle, f); } catch (Throwable t) { log("plug watch: " + t); }
    }

    private volatile boolean bridgeRecoverDeferred;
    private boolean captureRunning() { return audio instanceof AlsaDirectAudio && ((AlsaDirectAudio) audio).capturing(); }
    private volatile long captureDeadAt; private volatile boolean recoverHeld; private volatile String heldWhy;
    private final Runnable heldRecovery = () -> {                       // re-checked every 0.5 s
        if (!recoverHeld || exiting) return;
        if (captureRunning()) { recoverHeld = false; captureDeadAt = 0; log("the capture is running again: no bridge restart"); return; }   // healed by itself: no restart, and no stale start time for the next one
        recoverBridgeAudio(heldWhy);
    };
    void recoverBridgeAudio(String why) {
        long wait = Math.max(20_000 - (System.currentTimeMillis() - lastBridgeRecoverAt), autoCycleWait());
        if (agoldBridge() && !exiting && !isTransmitting() && wait > 0 && receiving && !bridgeRecoverDeferred) {
            // inside the 20 s window: try once more when it ends if the reception is still on, instead of leaving the capture dead (MON on a stuck line, 2026-10-29)
            bridgeRecoverDeferred = true;
            log("bridge recovery in " + (wait / 1000 + 1) + " s: " + why);
            ui.postDelayed(() -> { bridgeRecoverDeferred = false; if (receiving && !exiting && !captureRunning()) recoverBridgeAudio(why); }, wait + 100);   // not if the capture came back meanwhile
            return;
        }
        if (!agoldBridge() || exiting || isTransmitting() || wait > 0) { log("bridge recovery skipped: " + why); return; }
        // a power-cycle in the middle of a reception cuts it off and can hide its edges (2026-10-29), and since the capture
        // settings of PR 92 a dead capture is rare: wait for the reception to end, at most CAPTURE_DEAD_WAIT_MS
        long now = System.currentTimeMillis();
        if (captureDeadAt == 0) captureDeadAt = now;
        long hold = AgoldUsb.CAPTURE_DEAD_WAIT_MS - (now - captureDeadAt);
        if (receiving && hold > 0) {
            if (!recoverHeld) { recoverHeld = true; log("bridge recovery after this reception (at most " + (hold / 1000 + 1) + " s): " + why); }
            ui.removeCallbacks(heldRecovery); heldWhy = why; ui.postDelayed(heldRecovery, Math.min(hold, 500));
            return;
        }
        recoverHeld = false; captureDeadAt = 0; ui.removeCallbacks(heldRecovery);
        lastBridgeRecoverAt = System.currentTimeMillis(); lastAutoCycleAt = lastBridgeRecoverAt;
        worker.execute(() -> {
            try {
                log("bridge recovery: " + why);
                if (audio != null) audio.stopRx();
                powerCycleOrGiveUp();
                if (current != null) applyChannelNow(current, current.id > 0);
                if (monitorOn) engine.setMonitor(true);                  // the power-cycle left the module's monitor off: the squelch closed, the reopened capture stayed empty
                // the capture is reopened by windowCheck once the power-up window has settled whether a reception is still on
            } catch (Throwable t) { log("bridge recovery: " + t); }
        });
    }

    /** Run all checks' trial: open (or close) the Atom XL's USB capture outside a reception, waiting for it. Other phones: nothing. */
    public void benchCapture(boolean on) {
        if (!agoldBridge() || !rxAudioOn || !(audio instanceof AlsaDirectAudio)) return;
        try { audio.stopRx(); if (on) log("bench capture: " + audio.startRx(true)); } catch (Throwable t) { log("bench capture: " + t); }
    }

    /** The Atom XL's USB bridge (protocol agold). */
    private boolean agoldBridge() { return "agold".equals(profile.protocol); }

    @Override public void rssi(int raw, double dbm) { if (scope.running()) { scope.onRssi(raw); return; }   // a sweep step's reading: not the channel's signal, not for the coverage log
        rssi0(raw, dbm); }
    private void rssi0(int raw, double dbm) { agoldRssi(dbm); rssi = (int) Math.round(dbm); rssiAt = System.currentTimeMillis(); if (coverage != null && AgoldUsb.coverageReadingUsable(agoldBridge(), System.currentTimeMillis(), ownCycleUntil)) coverage.onRssi(rssi); if (hearing()) refreshControls(); /* the car screen's signal meter */ ui.post(() -> { for (Listener l : listeners) l.onRssi(rssi); }); }
    private volatile String moduleVersion = "";
    private volatile long versionAt;
    @Override public void version(int v, String text) {
        log("module " + text); moduleVersion = text; versionAt = System.currentTimeMillis();
        android.content.SharedPreferences p = getSharedPreferences("radio", MODE_PRIVATE);
        String stored = p.getString("fwStock", ""), stock = Firmware.stockAfter(stored, text);
        if (!stock.equals(stored)) p.edit().putString("fwStock", stock).apply();          // the first version this phone's module ever reported
    }
    /** The module firmware this phone came with (the first version SignalDeck saw), "" before the first answer. */
    public String stockFirmware() { return getSharedPreferences("radio", MODE_PRIVATE).getString("fwStock", ""); }
    /** Take the version the module reports now as the stock one (for a phone whose first report was already other firmware). */
    public void setStockFirmwareToCurrent() { if (!moduleVersion.trim().isEmpty()) getSharedPreferences("radio", MODE_PRIVATE).edit().putString("fwStock", moduleVersion.trim()).apply(); }
    /** Ask the module for its version and wait up to {@code ms} for the answer: does it take commands at all? */
    private boolean versionHandshake(long ms) throws Exception {
        long asked = System.currentTimeMillis(); engine.queryVersion();
        while (versionAt < asked && System.currentTimeMillis() - asked < ms) Thread.sleep(50);
        return versionAt >= asked;
    }
    /**
     * Start-up check (what the Xplore 1's stock app does with a 5 s watchdog): the module must answer the version query.
     * If not, it is restarted for real, twice at most. Xplore 1 tester 2026-10-23: after an update the module was already on,
     * powering it "on" did not restart it, and it ignored every command for four minutes.
     */
    private void moduleHandshake() throws Exception {
        if (versionHandshake(3000)) return;
        for (int attempt = 1; attempt <= 2; attempt++) {
            log("the module did not answer the version query: restarting it (" + attempt + " of 2)");
            setLink("booting");
            engine.powerCycle();
            if (!engine.waitBooted(12000)) log("no boot banner after the restart");
            Thread.sleep(300);
            if (versionHandshake(3000)) { history("warn", "\u26A0 The radio module did not answer at start-up and was restarted: it works now"); return; }
        }
        history("warn", "\u26A0 The radio module does not answer commands (channel, MON, squelch). Restarting the phone usually fixes this; please send a device report");
    }

    /**
     * While running: a channel, MON, squelch or version command unanswered for 6 s while the module still answers signal
     * readings means it has stopped taking commands. Confirm with a version query, then restart it and put the channel,
     * MON and audio back. At most once every two minutes, never while transmitting.
     */
    private volatile long lastRecoverAt; private volatile boolean recovering;
    private final Runnable moduleWatch = new Runnable() { @Override public void run() {
        ui.postDelayed(this, 2000);
        boolean stalled = agoldBridge() && engine.writeStalled();          // the bridge stopped taking data: writes fail after their deadline
        if (exiting || engine.rawSession() || !isReady() || recovering || isTransmitting() || (!stalled && engine.unansweredForMs() < 6000) || System.currentTimeMillis() - lastRecoverAt < 120_000) return;
        if (agoldBridge() && autoCycleWait() > 0) { if (!moduleWatchDeferred) { moduleWatchDeferred = true; log("module restart held " + autoCycleWait() + " ms: another automatic bridge restart ran just before"); } return; }   // re-checked every 2 s
        moduleWatchDeferred = false;
        recovering = true;
        worker.execute(() -> {
            try {
                if (stalled) { restartModule("the radio's USB bridge stopped taking commands (a write did not finish in " + AgoldUsb.WRITE_DEADLINE_MS / 1000 + " s)"); return; }
                if (versionHandshake(2500)) { log("the module was slow but answered the version query"); return; }
                restartModule("the module stopped answering commands for " + engine.unansweredForMs() / 1000 + " s");
            } catch (Throwable t) { log("module restart: " + t); setLink("ready"); }
            finally { recovering = false; }
        });
    } };
    /** Restart the module for real and put the channel, MON and receive audio back (on the worker thread). */
    void restartModule(String why) {
        try {
                lastRecoverAt = System.currentTimeMillis(); lastAutoCycleAt = lastRecoverAt;
                log(why + ": restarting it");
                history("warn", "\u26A0 The radio module stopped answering commands: restarting it (a few seconds without reception)");
                setLink("booting");
                if (audio != null) audio.stopRx();
                powerCycleOrGiveUp();
                if (!engine.waitBooted(12000)) log("no boot banner after the restart");
                Thread.sleep(300);
                boolean ok = versionHandshake(3000);
                if (current != null) applyChannelNow(current, current.id > 0);
                if (monitorOn) { sendMonitor(true); if (promiscuousCapable()) sendPromiscuous(true); }
                if (rxAudioOn && audio != null && (!agoldBridge() || receiving)) audio.startRx(true);   // the Atom XL opens its capture per reception
                setLink("ready");
                history("warn", ok ? "\u2714 The radio module was restarted and answers again" : "\u26A0 The radio module still does not answer after a restart. Restarting the phone usually fixes this; please send a device report");
        } catch (Throwable t) { log("module restart: " + t); setLink("ready"); }
    }
    /** Bench: the watchdog's restart, by hand (DIAG restartmodule). */
    public void diagRestartModule() { worker.execute(() -> { recovering = true; try { restartModule("DIAG restartmodule"); } finally { recovering = false; } }); }
    public String moduleVersion() { return moduleVersion; }
    /** The last signal reading (dBm, -999 none) and whether the module reports a reception (for the bench tests). */
    public int rssiNow() { return rssi; }
    public boolean receivingNow() { return receiving; }
    public ModuleEngine engine() { return engine; }
    /** One history line, voice announcement and caller report per call (the caller is asked at the start, a second in, and at the end). */
    private volatile boolean callLogged; private volatile int loggedCaller, loggedTarget;
    @Override public void callerInfo(int type, int caller, int target, int cc, int slot) {
        if (caller <= 0) return;
        dmrEvent("caller", type, caller, target, cc, slot);
        final String ccts = cc >= 0 ? "  CC " + cc + " TS " + (slot + 1) : "";   // appended by the promiscuous firmware while MON is on
        lastCaller = caller; lastCallType = type; lastTarget = target; if (cc >= 0) lastCallerCc = cc;
        refreshControls();   // the car screens show who is talking
        if (callLogged && caller == loggedCaller && target == loggedTarget) return;
        callLogged = true; loggedCaller = caller; loggedTarget = target;
        String t = (type == 1 ? "TG " + target : type == 2 ? "All call" : "Private") ;
        String who = callerName(caller);
        history("dmr", "📻 Voice RX " + t + " from " + (who.isEmpty() ? String.valueOf(caller) : who + " (" + caller + ")") + ccts);
        try { ChannelStore.Contact pc = store.contactByNumber(caller, 0); if (pc != null) store.contactHeard(pc.id, System.currentTimeMillis(), "DMR voice", t + (current != null ? " on " + current.name : ""), Double.NaN, Double.NaN); } catch (Throwable e) { }
        if (logHeard()) { RadioIdStore.Record rr = radioId != null ? radioId.lookup(caller) : null; ChannelStore.Channel lc = current; if (rr != null && rr.callsign != null && !rr.callsign.isEmpty()) try { logbook().heard(rr.callsign, lc != null ? lc.rxFreq : 0, "DMR", rr.first, t + ", DMR ID " + caller); enrichCall(rr.callsign, null); } catch (Throwable x) { log("logbook: " + x); } }
        ui.post(() -> { for (Listener l : listeners) l.onCaller((who.isEmpty() ? "ID " + caller : who + "  " + caller) + "  " + t + ccts); });
        if (voiceOn() && !who.isEmpty()) say(who.split(" ")[0]);
    }
    @Override public void ack(int cmd, boolean ok) {
        if (!ok) log(String.format(Locale.US, "MCU rejected 0x%02X", cmd));
        if (cmd == RadioEngine.CMD_MONITOR && ok) monAckAt = System.currentTimeMillis();
        if (cmd == RadioEngine.CMD_MONITOR && !ok) {                          // seen on the Xplore 1 right after a burst of channel changes: try again
            monAckAt = System.currentTimeMillis();                            // an answer (a refusal): the no-answer check stands down
            if (monRetries < 3) { monRetries++; final boolean want = monitorOn; ui.postDelayed(() -> { if (monitorOn == want) sendMonitor(want); }, 1500); log("MON refused, retry " + monRetries); }
            else { monRefusedAt = System.currentTimeMillis(); history("warn", "\u26A0 The radio refused MON (busy after channel changes?): wait a few seconds and switch it again"); }
        }
        if (cmd == RadioEngine.CMD_SET_ANALOG && ok && pendingPostWrite != null) { final Runnable r = pendingPostWrite; ui.postDelayed(r, 200); }
        if (cmd != RadioEngine.CMD_SET_ANALOG && cmd != RadioEngine.CMD_SET_DIGITAL) return;
        Object[] t; synchronized (tunes) { t = tunes.pollFirst(); }
        if (t == null) return;
        final ChannelStore.Channel ch = (ChannelStore.Channel) t[0];
        if (ok) { if (ch.isDigital() == (cmd == RadioEngine.CMD_SET_DIGITAL)) confirmed = ch; return; }
        // The radio refused the channel and kept its previous settings: show the truth, not the channel that was asked for.
        final ChannelStore.Channel was = confirmed;
        worker.execute(() -> {
            if (current != ch) return;                                      // something else was tuned since
            String why = ch.isDigital() && ch.txContact <= 0 ? " (a DMR channel needs a call number)" : "";
            history("warn", "⚠ The radio refused " + ch.name + " (" + ch.freqText(ch.rxFreq) + " MHz)" + why
                    + (was != null && was != ch ? "; it is still on " + was.name + ", " + was.freqText(was.rxFreq) + " MHz" : ""));
            if (was == null || was == ch) return;
            current = was;
            vfo = was.number == 0 ? was : null;
            if (was.number > 0) store.setActive(was.number);
            ui.post(() -> { for (Listener l : listeners) l.onChannel(was); });
            pushModes(); updateNotification();
        });
    }
    /** Channel writes waiting for the module's answer (oldest first) and the last one it accepted. */
    private final java.util.ArrayDeque<Object[]> tunes = new java.util.ArrayDeque<>();
    private volatile ChannelStore.Channel confirmed;
    @Override public void booted(String banner) { log("module booted: " + banner); }

    private volatile long pendingSmsId = -1;

    @Override public void sms(int type, int fromId, String text) {
        // 0x2D type: 1 = a talkgroup text, anything else private (the stock app's own reading, FetchSmsMessage/DmrManager.onSmsReceived).
        // A talkgroup text does not say which talkgroup: like the stock app, it belongs to the current channel's talkgroup.
        boolean group = type == 1;
        ChannelStore.Channel cur = current;
        int peer = fromId, sender = 0, t = 0;
        if (group && cur != null && cur.isDigital() && cur.txContact > 0) { peer = cur.txContact; sender = fromId; t = 1; }
        else if (group) t = 1;                                                // no talkgroup to file it under: the sender, marked as a group text
        log("text received: type " + type + (group ? " (talkgroup " + peer + ")" : " (private)") + " from " + fromId);
        store.addMessage(peer, t, false, text, 1, sender);
        try { ChannelStore.Contact pc = store.contactByNumber(fromId, 0); if (pc != null) store.contactHeard(pc.id, System.currentTimeMillis(), "DMR text", text, Double.NaN, Double.NaN); } catch (Throwable e) { }
        String who = callerName(fromId); if (who == null || who.isEmpty()) who = String.valueOf(fromId);
        String nm = t == 1 ? talkgroupName(peer) + (sender > 0 ? " \u00B7 " + who : "") : who;
        CarMessages.dmr(this, peer, t, nm, text);   // the phone and Android Auto (read aloud, voice reply to the thread)
        history("sms", "✉ Text from " + who + (sender > 0 ? " on " + talkgroupName(peer) : "") + ": " + (text.length() > 40 ? text.substring(0, 40) + "…" : text));
        onFormText(nm == null || nm.isEmpty() ? String.valueOf(fromId) : nm, text, "DMR");
        final int threadPeer = peer, threadType = t;                         // the open thread (the talkgroup for a group text) refreshes
        ui.post(() -> { for (Listener l : listeners) l.onSms(threadPeer, threadType, text); });
    }

    /** Status 8 = the other radio acknowledged; 9 = no acknowledgement came back (the text usually still arrived — a BTECH 6X2 shows it and never acks, 2026-10-17). */
    private void smsResult(boolean ok) {
        long id = pendingSmsId; pendingSmsId = -1;
        if (id >= 0) store.setMessageStatus(id, ok ? 1 : 3);
        if (!ok) log("text message sent, no acknowledgement");
        ui.post(() -> { for (Listener l : listeners) l.onSmsResult(ok); });
    }

    /** Send a DMR text to a contact (private / group / all) over the current digital channel. */
    public void sendSms(final ChannelStore.Contact to, final String text) {
        if (txInhibited()) { history("warn", "⛔ Text not sent: transmitting is off on this phone or channel"); ui.post(() -> { for (Listener l : listeners) l.onSmsResult(false); }); return; }   // an untested profile never transmits, texts included
        final long id = store.addMessage(to.number, to.type, true, text, 0);
        try { ChannelStore.Contact pc = to.id > 0 ? to : store.contactByNumber(to.number, to.type); if (pc != null) store.contactSent(pc.id, System.currentTimeMillis(), "DMR text", text); } catch (Throwable e) { }
        worker.execute(() -> {
            try {
                int msgType = to.type == 1 ? 3 : to.type == 2 ? 2 : 1;
                pendingSmsId = id;
                engine.sendSms(msgType, to.number, 0, text);     // group texts: id = the talkgroup, second int 0 exactly as the stock app sends them
            } catch (Throwable t) { store.setMessageStatus(id, 2); log("sms: " + t); ui.post(() -> { for (Listener l : listeners) l.onSmsResult(false); }); }
        });
    }

    // ---- notification -------------------------------------------------------------------------------

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) nm.createNotificationChannel(new NotificationChannel("radio", "Radio", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "radio") : new Notification.Builder(this);
        ChannelStore.Channel ch = current;
        b.setContentTitle(ch != null ? "Ch " + ch.number + " · " + ch.name : "SignalDeck").setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_radio).setColor(0xFF19D3FF).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setContentIntent(RadioWidget.openApp(this));
        if (isReady()) {
            boolean scanning = scanner != null && scanner.isScanning();
            b.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_w_left), "Ch −", RadioWidget.command(this, RadioWidget.CMD_PREV, 1)).build());
            b.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, scanning ? R.drawable.ic_w_stop : R.drawable.ic_w_scan), scanning ? "Stop scan" : "Scan", RadioWidget.command(this, RadioWidget.CMD_SCAN, 3)).build());
            b.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_w_right), "Ch +", RadioWidget.command(this, RadioWidget.CMD_NEXT, 2)).build());
            b.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_w_stop), "Exit", RadioWidget.command(this, RadioWidget.CMD_EXIT, 4)).build());   // expanded view: radio off and exit
            Notification.MediaStyle ms = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
            try { ms.setMediaSession((android.media.session.MediaSession.Token) com.macdmr.radio.media.RadioMedia.get(this).session().getSessionToken().getToken()); } catch (Throwable ignored) { }
            b.setStyle(ms);
        }
        return b.build();
    }

    private void updateNotification() { refreshControls(); }

    private final Runnable controlsRefresh = () -> {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String t = isReady() ? RadioWidget.status(this) : "Radio " + link;
            if (nm != null) nm.notify(1, notification(t));
        } catch (Throwable ignored) { }
        RadioWidget.refresh(this, this);
        updateAudioFocus();
        try {
            // in the car "playing" means the radio is the car's media source (media focus, the car's media channel); the
            // ducking focus it holds otherwise is played on the car's voice-prompt channel, so show Play there instead
            com.macdmr.radio.media.RadioMedia.get(this).update(this, inCar() ? inMediaMode() && !isMuted() : !isMuted(), carStatus(), zoneName(), isScanning());
        } catch (Throwable t) { log("media session: " + t); }
    };

    // ---- audio focus --------------------------------------------------------------------------------
    // While something is heard the app holds transient, may-duck audio focus: music and podcasts dip under the
    // radio, and Android Auto (which only sends the focus holder's media to the car) plays it through the car.
    // Released 2.5 s after reception ends, so the music does not bounce back between phrases.
    private android.media.AudioFocusRequest focusRequest; private boolean focusHeld;
    /**
     * Projecting to a car (Android Auto). Measured with the head unit emulator: any transient focus (may duck or not)
     * is streamed on the car's voice-prompt channel (16 kHz, above the radio's 8 kHz; the car dips its media under it),
     * only permanent focus on the media channel; so the request is the same in the car, and this is for the car UI.
     */
    private volatile boolean inCar;
    public boolean inCar() { return inCar; }

    // ---- in the car: coverage logging for the drive, steering-wheel Talk -----------------------------
    public boolean coverageInCar() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("covInCar", true); }
    public void setCoverageInCar(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("covInCar", on).apply(); if (inCar) carCoverage(true); }
    public boolean wheelTalk() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("wheelTalk", false); }
    public void setWheelTalk(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("wheelTalk", on).apply(); }
    private boolean coverageForDrive;   // switched on by the car connecting (not by you): switched off again when it disconnects
    private void carCoverage(boolean car) {
        try {
            com.macdmr.radio.coverage.CoverageLogger cl = coverage();
            if (car && coverageInCar() && !cl.running()) { cl.start(); coverageForDrive = true; startForegroundTyped(true); log("coverage logging on for the drive"); history("cov", "\uD83D\uDCF6 Coverage logging on while Android Auto is connected"); }
            else if (!car && coverageForDrive) { coverageForDrive = false; if (!cl.enabled()) { cl.stop(); startForegroundTyped(false); log("coverage logging off (car disconnected)"); } }
            refreshControls();
        } catch (Throwable t) { log("car coverage: " + t); }
    }
    // ---- the wheel's Next as a talk key (hold, or double-press) ----
    public static final String[] NEXT_TALK_NAMES = { "Off", "Hold", "Double-press" };
    public int nextTalkMode() {
        android.content.SharedPreferences p = getSharedPreferences("radio", MODE_PRIVATE);
        return p.contains("nextTalk") ? p.getInt("nextTalk", 0) : !p.contains("holdNextTalk") ? com.macdmr.radio.ham.WheelNext.DOUBLE      // default: double-press (only presses, works with every car)
                : p.getBoolean("holdNextTalk", false) ? com.macdmr.radio.ham.WheelNext.HOLD : 0;   // the first version was a hold on/off switch
    }
    public void setNextTalkMode(int m) { getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("nextTalk", m).apply(); wheel.setMode(m); }
    public boolean holdNextTalk() { return nextTalkMode() != 0; }
    private volatile long lastWheelKeyAt; private int wheelKeysLogged;
    /** The transmission in progress was started by the wheel's Next (it then uses the phone microphone). */
    private volatile boolean wheelPtt;
    /** The transmission rides a talk "call" (car microphone through Telecom; the car's hang-up stops it). */
    private volatile boolean callPtt, talkCallPending;

    // wheel microphone: 0 phone, 1 car through a talk call; stop on silence: 0 off, else the quiet gap in ms
    public static final String[] WHEEL_MIC_NAMES = { "Phone", "Car (as a call)" };
    public static final int[] VOX_MS = { 0, 1500, 2500, 4000 };
    public static final String[] VOX_NAMES = { "Off", "1.5 s", "2.5 s", "4 s" };
    public int wheelMic() { return getSharedPreferences("radio", MODE_PRIVATE).getInt("wheelMic", 1); }
    public void setWheelMic(int m) { getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("wheelMic", m).apply(); }
    public int voxStop() { return getSharedPreferences("radio", MODE_PRIVATE).getInt("voxStop", 1); }   // default 1.5 s
    public void setVoxStop(int i) { getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("voxStop", i).apply(); }
    private final Runnable talkCallTimeout = () -> { if (talkCallPending) { talkCallPending = false; log("talk call: no car audio route in 4 s, using the phone microphone"); history("car", "\u26A0\uFE0F The car did not open its microphone for the talk call: this transmission uses the phone microphone"); com.macdmr.radio.car.RadioCallService.end(); wheelPtt = true; ptt(true); } };
    /** A steering-wheel start: with the car microphone as a call when set, else the phone microphone. */
    private void wheelTalkStart() {
        if (carMic() && wheelMic() == 1) {
            String e = com.macdmr.radio.car.RadioCallService.start(this);
            if (e == null) { talkCallPending = true; ui.removeCallbacks(talkCallTimeout); ui.postDelayed(talkCallTimeout, 4000); log("talk call placed"); return; }
            log("talk call: " + e + "; phone microphone");
        }
        wheelPtt = true; ptt(true);
    }
    /** Telecom opened the car's audio route for the talk call: key the radio with the car's microphone. */
    public void talkCallReady(boolean bluetooth) {
        if (!talkCallPending) return;
        talkCallPending = false; ui.removeCallbacks(talkCallTimeout);
        log("talk call active, route " + (bluetooth ? "car (Bluetooth)" : "not Bluetooth"));
        if (bluetooth) callPtt = true; else wheelPtt = true;
        ptt(true);
    }
    public void talkCallFailed() { if (talkCallPending) { ui.removeCallbacks(talkCallTimeout); talkCallTimeout.run(); } }
    /** Hang-up or End in the car: stop transmitting. */
    public void talkCallHungUp() { talkCallPending = false; history("car", "\uD83D\uDCDE Hung up in the car: transmission stopped"); if (pttDown) ptt(false); }
    private volatile com.macdmr.radio.ham.VoxStop vox;
    private final com.macdmr.radio.ham.WheelNext wheel = new com.macdmr.radio.ham.WheelNext(new com.macdmr.radio.ham.WheelNext.Out() {
        @Override public void talk(boolean on) {
            if (on) { if (inCar && !pttDown) wheelTalkStart(); }
            else { if (talkCallPending) { talkCallPending = false; com.macdmr.radio.car.RadioCallService.end(); } if (pttDown) ptt(false); }
        }
        @Override public void step() { stepChannel(1); }
        @Override public void schedule(Runnable r, long ms) { ui.postDelayed(r, ms); }
        @Override public void cancel(Runnable r) { ui.removeCallbacks(r); }
        @Override public void note(String what) { wheelLog("-> " + what); }
    });
    private boolean wheelModeSet;
    /** A KEYCODE_MEDIA_NEXT event from the car, for the talk-key modes. */
    public void wheelNext(android.view.KeyEvent ev) {
        if (!wheelModeSet) { wheelModeSet = true; wheel.setMode(nextTalkMode()); }
        long now = android.os.SystemClock.uptimeMillis();
        if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) wheel.down(ev.getRepeatCount(), now); else if (ev.getAction() == android.view.KeyEvent.ACTION_UP) wheel.up(now);
    }
    /** Every wheel event and decision, with milliseconds, in files/wheel.log (last 400 lines): how this car behaves. */
    private synchronized void wheelLog(String line) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "wheel.log");
            String stamp = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new java.util.Date());
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) { w.write(stamp + "  " + line + "\n"); }
            if (f.length() > 40_000) {
                java.util.List<String> l = java.nio.file.Files.readAllLines(f.toPath());
                java.nio.file.Files.write(f.toPath(), l.subList(Math.max(0, l.size() - 400), l.size()));
            }
        } catch (Throwable ignored) { }
    }
    /** The first few raw wheel events of each drive go to the history, so what this car sends can be seen afterwards. */
    public void wheelKeySeen(android.view.KeyEvent ev) {
        lastWheelKeyAt = System.currentTimeMillis();
        String k = android.view.KeyEvent.keyCodeToString(ev.getKeyCode()).replace("KEYCODE_", "") + (ev.getAction() == android.view.KeyEvent.ACTION_DOWN ? " down" : " up") + " repeat " + ev.getRepeatCount() + " (event time " + ev.getEventTime() + ", now " + android.os.SystemClock.uptimeMillis() + ")";
        log("wheel key " + k); wheelLog(k);
        if (holdNextTalk() && wheelKeysLogged < 6) {
            wheelKeysLogged++;
            carEvent("\uD83C\uDF9B Wheel: " + android.view.KeyEvent.keyCodeToString(ev.getKeyCode()).replace("KEYCODE_", "") + (ev.getAction() == android.view.KeyEvent.ACTION_DOWN ? " pressed" + (ev.getRepeatCount() > 0 ? " (repeat " + ev.getRepeatCount() + ")" : "") : " released"));
        }
    }
    /** Android Auto sent Next as a command (no button events): holding cannot be told from tapping in this car. */
    public void wheelSkipCommand() {
        if (holdNextTalk() && inCar && System.currentTimeMillis() - lastWheelKeyAt > 2000)
            carEvent("\u26A0\uFE0F Android Auto sends the wheel's Next as a command, not a button press: hold Next to talk cannot work in this car");
    }

    /** Steering-wheel play/pause (a media button) while in the car with the setting on: start or stop talking. */
    public boolean wheelButton() {
        if (!inCar || !wheelTalk()) return false;
        if (pttHeld() || talkCallPending) { if (talkCallPending) { talkCallPending = false; com.macdmr.radio.car.RadioCallService.end(); } ptt(false); } else wheelTalkStart();
        return true;
    }
    // ---- car presence: media app (default) or the full-screen Radio controls template app ---------------
    public boolean carControlsScreen() {
        try {
            int st = getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(this, com.macdmr.radio.car.RadioCarAppService.class));
            return st == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Throwable t) { return false; }
    }
    public void setCarControlsScreen(boolean on) {
        getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this, com.macdmr.radio.car.RadioCarAppService.class),
                on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP);
        log("car screen: " + (on ? "Radio controls" : "media app"));
    }
    /** Car milestones go into the radio history (kept across reboots), so a drive can be looked at afterwards. */
    private String lastCarEvent; private long lastCarEventAt;
    public void carEvent(String line) {
        long now = System.currentTimeMillis();
        if (line.equals(lastCarEvent) && now - lastCarEventAt < 60_000) return;   // once a minute at most per kind
        lastCarEvent = line; lastCarEventAt = now;
        log(line); history("car", line);
    }

    // ---- the transmission knocking out the USB cable ---------------------------------------------------
    // Measured 2026-10-19 in the owner's car: on one 2 m channel (TX 144.770 MHz, high power) every transmission reset
    // the phone's USB port about 1.5 s after key-up (USB port change, charging stopped), so a wired Android Auto dropped
    // until the carrier ended; 146.370 MHz on the same band did not. The cable picks up the RF (the antenna is at the
    // far end of the phone), and only some frequencies couple. Software cannot stop it; the user is told what helps.
    private volatile ChannelStore.Channel txChannel; private volatile boolean txInCar, txCharging, txDropNoted; private volatile long txEndedAt;
    private android.content.BroadcastReceiver powerOff;
    private boolean txJustNow() { return pttDown || System.currentTimeMillis() - txEndedAt < 3000; }
    private boolean charging() {
        try { android.content.Intent b = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)); return b != null && b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0; }
        catch (Throwable t) { return false; }
    }
    private void watchUsbDuringTx() {
        powerOff = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) { if (txCharging && txJustNow()) usbDropDuringTx(false); }
        };
        try { registerReceiver(powerOff, new android.content.IntentFilter(android.content.Intent.ACTION_POWER_DISCONNECTED)); } catch (Throwable t) { log("usb watch: " + t); }
    }
    /** Android Auto dropped (car) or charging stopped (power) during or right after a transmission: once per transmission. */
    private void usbDropDuringTx(boolean car) {
        if (txDropNoted) return;
        ChannelStore.Channel ch = txChannel;
        if (ch == null || (car && !txInCar)) return;
        txDropNoted = true;
        String freq = String.format(java.util.Locale.US, "%.3f MHz", ch.txFreq / 1e6);
        String what = car || txInCar ? "Android Auto dropped" : "The USB cable dropped (charging stopped)";
        carEvent("⚠️ " + what + " while transmitting on " + ch.name + " (" + freq + "): the transmission is disturbing the USB cable");
        com.macdmr.radio.car.AutoTrust.notifyUsbDrop(this, ch.name, freq, car || txInCar);
    }

    // ---- Android Auto connected but never loaded the radio ----------------------------------------------
    // Measured 2026-10-19: on every working connection Android Auto binds the media service within ~7 s of connecting
    // (12 of 12). Once, with Unknown sources on, it never did: the radio was missing from the car's app list and its card
    // opened the phone app, until the cable was unplugged and plugged in again. Nothing in the app can make Android Auto
    // load it, so the user is told how to recover.
    private volatile long carMediaBoundAt;
    public void carMediaBound() { carMediaBoundAt = System.currentTimeMillis(); try { androidx.core.app.NotificationManagerCompat.from(this).cancel("notloaded", 10); } catch (Throwable ignored) { } }
    private final Runnable carBindCheck = () -> {
        if (!inCar || System.currentTimeMillis() - carMediaBoundAt < 60_000) return;
        com.macdmr.radio.car.AutoTrust.State st = com.macdmr.radio.car.AutoTrust.last();
        if (st == com.macdmr.radio.car.AutoTrust.State.OFF || st == com.macdmr.radio.car.AutoTrust.State.LOCKED) return;   // that alert already explains it
        carEvent("⚠️ Android Auto did not load SignalDeck on this connection: unplug the cable and plug it in again");
        com.macdmr.radio.car.AutoTrust.notifyNotLoaded(this);
    };

    private void watchCar() {
        try {
            new androidx.car.app.connection.CarConnection(this).getType().observeForever(t -> {
                boolean car = t != null && t == androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION;
                if (car == inCar) return;
                inCar = car; log(car ? "Android Auto connected" : "Android Auto disconnected"); if (car) wheelKeysLogged = 0;
                if (!car && txJustNow()) usbDropDuringTx(true);
                carEvent(car ? "🚗 Android Auto connected (" + (carControlsScreen() ? "Radio controls screen" : "media app") + ")" : "🚗 Android Auto disconnected");
                carCoverage(car);
                ui.removeCallbacks(carBindCheck); if (car) ui.postDelayed(carBindCheck, 30_000);
                if (car) new Thread(() -> {   // a sideloaded app stays off the car's app list unless Unknown sources is on
                    com.macdmr.radio.car.AutoTrust.State st = com.macdmr.radio.car.AutoTrust.check(this);
                    if (st == com.macdmr.radio.car.AutoTrust.State.OFF || st == com.macdmr.radio.car.AutoTrust.State.LOCKED) {
                        ui.post(() -> carEvent("⚠️ Android Auto is hiding SignalDeck: Unknown sources is off (Device page shows how to fix it)"));
                        com.macdmr.radio.car.AutoTrust.notifyHidden(this, st);
                    } else if (st == com.macdmr.radio.car.AutoTrust.State.OK) com.macdmr.radio.car.AutoTrust.clearNotice(this);
                }, "autotrust").start();
            });
        } catch (Throwable t) { log("car connection: " + t); }
    }
    private final Runnable focusRelease = () -> {
        if (!focusHeld) return;
        try { ((android.media.AudioManager) getSystemService(AUDIO_SERVICE)).abandonAudioFocusRequest(focusRequest); } catch (Throwable ignored) { }
        focusHeld = false; log("audio focus released");
    };
    /**
     * Focus taken away. For good (Android Auto connecting grabs it, another player starts): ask again in a second
     * while the radio is still heard. For a while (a phone call): Android gives it back by itself.
     */
    private void onFocusChange(int change) {
        log("audio focus change " + change);
        // 3 s: Android Auto takes the focus when it starts projecting; ask again once it has settled
        if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) { focusHeld = false; ui.removeCallbacks(refocus); ui.postDelayed(refocus, 3000); }
    }
    private final Runnable refocus = this::updateAudioFocus;

    // ---- media (Android Auto, lock screen, headset buttons) --------------------------------------------
    /** Paused from the media controls: nothing is played, but reception, decoding and logging carry on. */
    private volatile boolean muted;
    /** Played from the media controls (Android Auto's media screen, play button): the radio is the media source and
     *  holds permanent focus, which the car plays on its media channel. Ends with pause or when another player takes over. */
    private volatile boolean mediaMode;
    public boolean isMuted() { return muted; }
    public boolean inMediaMode() { return mediaMode; }
    public void mediaPlay(boolean play) {
        ui.post(() -> {
            boolean wasMuted = muted;
            muted = !play; mediaMode = play;
            if (wasMuted != muted) history("media", muted ? "\uD83D\uDD07 Radio sound paused (media controls); still receiving" : "\uD83D\uDD0A Radio sound playing");
            if (focusHeld) { ui.removeCallbacks(focusRelease); focusRelease.run(); }   // re-take the right kind of focus
            updateAudioFocus(); refreshControls();
        });
    }
    private android.media.AudioFocusRequest mediaFocusRequest; private boolean mediaFocusHeld;
    private void updateMediaFocus() {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (mediaMode && !muted) {
            if (mediaFocusHeld) return;
            if (mediaFocusRequest == null) mediaFocusRequest = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(ch -> {
                        log("media focus change " + ch);
                        if (ch == android.media.AudioManager.AUDIOFOCUS_LOSS) {   // another player took over: pause, as media apps do
                            mediaFocusHeld = false; mediaPlay(false);
                            if (inCar) carEvent("🚗 Android Auto took the sound away (another source played)");
                        }
                    }, ui).build();
            mediaFocusHeld = am.requestAudioFocus(mediaFocusRequest) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            log("media focus " + (mediaFocusHeld ? "held" : "refused"));
        } else if (mediaFocusHeld) {
            try { am.abandonAudioFocusRequest(mediaFocusRequest); } catch (Throwable ignored) { }
            mediaFocusHeld = false; log("media focus released");
        }
    }

    private void updateAudioFocus() {
        updateMediaFocus();
        boolean want = rxAudioOn && !pttDown && !muted && !mediaMode && hearing();
        if (want) {
            ui.removeCallbacks(focusRelease);
            if (focusHeld) return;
            try {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (focusRequest == null) focusRequest = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(this::onFocusChange, ui).build();
                int r = am.requestAudioFocus(focusRequest);
                focusHeld = r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                log("audio focus " + (focusHeld ? "held" + (inCar ? " (in the car)" : "") : "refused (" + r + ")"));
            } catch (Throwable t) { log("audio focus: " + t); }
        } else if (focusHeld) { ui.removeCallbacks(focusRelease); ui.postDelayed(focusRelease, 2500); }
    }
    /** Notification + widget, coalesced: state can flip several times in a few ms (gate, RX start, TX). */
    private void refreshControls() { ui.removeCallbacks(controlsRefresh); ui.postDelayed(controlsRefresh, 150); }

    public boolean isTransmitting() { return transmitting || pttDown; }
    public int lastRssi() { return rssi; }
    /** When the last signal reading arrived (ms), and when the module was last retuned (a channel or VFO write): readings need ~0.7 s after it. */
    public long lastRssiAt() { return rssiAt; }
    public long lastTunedAt() { return tunedAt; }
    /** The last DMR caller (kept after the call ends, 0 = none yet) and the colour code the promiscuous firmware reported for it (-1 = not reported: the module passed only the channel's). */
    public int lastCallerId() { return lastCaller; }
    public int currentCallerCc() { return dmrCallNow() ? lastCallerCc : -1; }
    /** The CTCSS tone decoded from the received audio right now (Hz), or 0. */
    public double heardToneHz() { double hz = toneBank.detectedHz(); return hz > 0 ? hz : 0; }
    /** Who is talking on a DMR channel right now (name from the contacts or RadioID, or the ID), else "". */
    public String currentCaller() {
        if (!dmrCallNow()) return "";
        String n = callerName(lastCaller); return n.isEmpty() ? "ID " + lastCaller : n;
    }
    private boolean dmrCallNow() { return current != null && current.isDigital() && hearing() && lastCaller > 0; }
    /** The talker's DMR ID during a call, else 0. */
    public int currentCallerId() { return dmrCallNow() ? lastCaller : 0; }
    /** The talker's callsign when the name starts with one ("N0CALL Demo ..." gives "N0CALL"), else the name or "ID n". */
    public String currentCallsign() {
        String c = currentCaller(); if (c.isEmpty()) return "";
        String first = c.split("\\s+")[0];
        return first.matches("[A-Z0-9]{1,3}[0-9][A-Z0-9]{1,4}") ? first : c;
    }
    /** What the call goes to: "TG 11904 Ontario" (the talkgroup's contact name when known), "Private call", "All call", or "". */
    public String currentTarget() {
        if (!dmrCallNow()) return "";
        if (lastCallType == 1) { String n = store.contactName(lastTarget, 1); return "TG " + lastTarget + (n == null || n.isEmpty() || n.equals(String.valueOf(lastTarget)) ? "" : " " + n); }
        if (lastCallType == 2) return "All call";
        return "Private call";
    }
    /**
     * The zone to show with a channel: the selected zone when it holds the channel; otherwise the first other zone
     * that does (Favourites last); "" when none does.
     */
    public String zoneLabelFor(ChannelStore.Channel ch) {
        if (ch == null) return "";
        ChannelStore.Zone sel = zoneId > 0 ? store.zone(zoneId) : null;
        if (zoneId <= 0) return "ALL";
        if (sel != null) for (int n : sel.numbers) if (n == ch.number) return sel.name;
        String fav = null;
        for (ChannelStore.Zone z : store.zones()) for (int n : z.numbers) if (n == ch.number) { if (com.macdmr.radio.ui.ChannelsView.FAV_ZONE.equals(z.name)) fav = z.name; else return z.name; }
        return fav != null ? fav : "";
    }
    /** The car's subtitle: during a DMR call who is talking and to what; otherwise frequency and state. */
    public String carStatus() {
        String who = currentCaller();
        if (!who.isEmpty()) { int id = currentCallerId(); String t = currentTarget(); return who + (who.startsWith("ID ") ? "" : " \u00b7 " + id) + (t.isEmpty() ? "" : " \u00b7 " + t); }
        return isReady() ? RadioWidget.status(this).replace("Mapping coverage \u00b7 ", "") : "Radio " + link;
    }

    // ---- coverage mapping ---------------------------------------------------------------------------
    private com.macdmr.radio.coverage.CoverageLogger coverage;
    public com.macdmr.radio.coverage.CoverageLogger coverage() {
        if (coverage == null) coverage = new com.macdmr.radio.coverage.CoverageLogger(this, new com.macdmr.radio.coverage.CoverageLogger.Host() {
            @Override public ChannelStore.Channel current() { return current; }
            @Override public boolean ready() { return isReady(); }
            @Override public boolean hearing() { return RadioService.this.hearing(); }
            @Override public boolean busyTx() { return pttDown || transmitting; }
            @Override public boolean toneMatches(ChannelStore.Channel ch) {
                if (ch.isDigital() || ch.rxType != 1) return true;            // DMR: the colour code already matched; no tone or DCS: the carrier is all we have
                double want = com.macdmr.radio.dsp.ToneTable.ctcssHz(ch.rxSubCode), got = toneBank.detectedHz();
                return want > 0 && got > 0 && Math.abs(want - got) < 1.5;
            }
            @Override public void pollRssi() { RadioService.this.pollRssiMeasure(); }
            @Override public void event(String line) { history("cov", line); }
        });
        return coverage;
    }
    /** Coverage logging on/off; on also asks for location in the foreground service so it keeps working with the screen off. */
    public void setCoverageLogging(boolean on) { coverage().setEnabled(on); startForegroundTyped(on); refreshControls(); }
    // ---- car microphone (Bluetooth hands-free) -------------------------------------------------------
    public boolean carMic() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("carMic", true); }
    public void setCarMic(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("carMic", on).apply(); }
    private volatile boolean btMicOn;
    /**
     * Route the microphone to a connected Bluetooth hands-free device (the car) and wait for the link (up to 2 s),
     * so the first words are not lost. False when there is none; the phone microphone is used then.
     */
    private boolean startBluetoothMic() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
            boolean asked = false;
            if (Build.VERSION.SDK_INT >= 31) {
                for (android.media.AudioDeviceInfo d : am.getAvailableCommunicationDevices())
                    if (d.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { asked = am.setCommunicationDevice(d); break; }
            } else if (am.isBluetoothScoAvailableOffCall()) { am.startBluetoothSco(); am.setBluetoothScoOn(true); asked = true; }
            if (!asked) { am.setMode(android.media.AudioManager.MODE_NORMAL); return false; }
            btMicOn = true;
            for (int i = 0; i < 40; i++) {
                boolean up = Build.VERSION.SDK_INT >= 31
                        ? am.getCommunicationDevice() != null && am.getCommunicationDevice().getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        : am.isBluetoothScoOn();
                if (up) { Thread.sleep(150); return true; }       // the audio path needs a moment after the link reports up
                Thread.sleep(50);
            }
            log("car microphone: the hands-free link did not come up; using the phone microphone");
            stopBluetoothMic();
            return false;
        } catch (Throwable t) { log("car microphone: " + t); stopBluetoothMic(); return false; }
    }
    private void stopBluetoothMic() {
        if (!btMicOn) return;
        btMicOn = false;
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
            am.setMode(android.media.AudioManager.MODE_NORMAL);
        } catch (Throwable ignored) { }
    }

    public boolean coverageLogging() { return coverage != null && coverage.running(); }

    /**
     * Key-up test on an FM repeater channel: transmit your callsign in Morse (20 wpm, 800 Hz, about 3 s; a
     * legal identification) and let the coverage logger judge whether the repeater answered. Returns null when
     * sent, or why not.
     */
    public String keyupTest() {
        ChannelStore.Channel ch = current;
        if (!isReady() || ch == null) return "The radio is not on";
        if (!coverageLogging()) return "Turn on coverage logging first: the result is stored with your position";
        if (ch.isDigital()) return "On DMR use the Parrot instead: a private call to 9990 on BrandMeister plays your call back";
        if (ch.txFreq == ch.rxFreq || (ch.lat == 0 && ch.lon == 0)) return "Choose an FM repeater channel that has a location";
        if (txInhibited()) return "This channel is receive-only";
        if (packet == null || !packet.callsignSet()) return "Set your callsign first (PKT \u2192 APRS settings): the test sends it as your identification";
        if (pttDown) return "Already transmitting";
        String id = packet.callsign();
        transmitPcm(com.macdmr.radio.coverage.Morse.pcm(id, 20, 800), "key-up test (Morse ID)", null);
        return null;
    }

    /** The foreground service: microphone always, plus location while coverage logging is on (allowed only while the app is in front). */
    private void startForegroundTyped(boolean location) {
        Notification n = notification(isReady() ? RadioWidget.status(this) : "Radio idle");
        if (Build.VERSION.SDK_INT >= 30) {
            int t = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (location) {
                try { startForeground(1, n, t | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); return; }
                catch (Throwable e) { log("coverage: location in the background not allowed now (" + e.getClass().getSimpleName() + "); logging while the app is open"); }
            }
            try { startForeground(1, n, t); return; } catch (Throwable ignored) { }
        }
        startForeground(1, n);
    }

    // ---- roger beep ---------------------------------------------------------------------------------
    public static final String[] ROGER_NAMES = { "Off", "Beep", "Two-tone", "Morse K" };
    public int rogerStyle() { int s = getSharedPreferences("radio", MODE_PRIVATE).getInt("roger", 0); return s >= 0 && s < ROGER_NAMES.length ? s : 0; }
    public void setRogerStyle(int s) { getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("roger", s).apply(); }
    /** The end-of-transmission tone at 8 kHz (null = off): 40 ms lead, soft 6 ms edges, about a third of full scale. */
    public static short[] rogerPcm(int style) {
        int[][] seg;   // {hz (0 = silence), ms}
        switch (style) {
            case 1: seg = new int[][] { { 0, 40 }, { 1000, 180 } }; break;
            case 2: seg = new int[][] { { 0, 40 }, { 1200, 110 }, { 0, 30 }, { 1800, 130 } }; break;
            case 3: seg = new int[][] { { 0, 40 }, { 800, 180 }, { 0, 60 }, { 800, 60 }, { 0, 60 }, { 800, 180 } }; break;
            default: return null;
        }
        int n = 0; for (int[] s : seg) n += s[1] * 8;
        short[] out = new short[n]; int pos = 0;
        for (int[] s : seg) {
            int len = s[1] * 8, edge = 48;
            for (int i = 0; i < len; i++) {
                double env = Math.min(1.0, Math.min(i, len - 1 - i) / (double) edge);
                out[pos + i] = s[0] == 0 ? 0 : (short) (11000 * env * Math.sin(2 * Math.PI * s[0] * i / 8000.0));
            }
            pos += len;
        }
        return out;
    }
    /** Play a roger tone on the phone speaker (Device page preview). */
    public static void previewRoger(int style) {
        final short[] pcm = rogerPcm(style); if (pcm == null) return;
        new Thread(() -> {
            try {
                android.media.AudioTrack t = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, 8000, android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(pcm.length * 2, 4096), android.media.AudioTrack.MODE_STATIC);
                t.write(pcm, 0, pcm.length); t.play(); Thread.sleep(pcm.length / 8 + 150); t.release();
            } catch (Throwable ignored) { }
        }, "roger-preview").start();
    }

    // ---- emergency ----------------------------------------------------------------------------------
    /** Send an emergency on the current channel: DMR text with coordinates, or an APRS emergency position. Returns what happened. */
    /** Media volume changes (the W6: the module drives the speaker, so Android's volume reaches it only through here). */
    private final android.content.BroadcastReceiver mediaVolume = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == android.media.AudioManager.STREAM_MUSIC) followMediaVolume();
        }
    };
    private void followMediaVolume() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            int lv = AtModuleEngine.speakerLevelFor(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
            ModuleEngine e = engine; if (e != null) e.setSpeakerLevel(lv);
        } catch (Throwable t) { log("media volume: " + t); }
    }

    public String emergency(String text, int toNumber, int toType, String toName) {
        ChannelStore.Channel ch = current;
        if (!isReady() || ch == null) return "Not sent: the radio is not on";
        if (txInhibited()) return "Not sent: this channel is receive-only";
        String m = text == null || text.trim().isEmpty() ? "Need help" : text.trim();
        if (ch.isDigital()) {
            if (toNumber <= 0) return "Not sent: choose who receives the emergency text";
            android.location.Location l = lastKnownLocation();
            String pos = l == null ? "position unknown" : String.format(Locale.US, "%.5f,%.5f", l.getLatitude(), l.getLongitude()) + (l.hasAccuracy() ? " +-" + Math.round(l.getAccuracy()) + "m" : "");
            String body = "EMERGENCY " + m + " @ " + pos;
            if (body.length() > 120) body = body.substring(0, 120);
            ChannelStore.Contact to = new ChannelStore.Contact(); to.number = toNumber; to.type = toType; to.name = toName == null ? "" : toName;
            sendSms(to, body);
            String who = to.name.isEmpty() ? String.valueOf(toNumber) : to.name;
            history("tx", "🆘 Emergency text to " + who + ": " + body);
            return "Emergency text sent to " + who + (l == null ? " (no position fix)" : "");
        }
        if (packet == null) return "Not sent: packet radio is not available";
        String e = packet.sendEmergency(m);
        if (e != null) return "Not sent: " + e;
        return "Emergency APRS " + (lastKnownLocation() != null ? "position" : "status (no position fix)") + " sent on " + ch.name;
    }
    public boolean pttHeld() { return pttDown; }
    public boolean isScanning() { return scanner != null && scanner.isScanning(); }
    public boolean isScanHolding() { return scanner != null && scanner.isHolding(); }
    /** Something is actually heard: the Soft SQ gate on Soft SQ channels, the radio's own report otherwise. */
    public boolean hearing() { return injecting || (softSq != null && softSq.isEnabled() ? squelchOpen : receiving && rxCounts); }

    // ---- message forms (ICS-213, radiogram) over APRS messages or DMR texts ----
    private com.macdmr.radio.ham.FormStore forms;
    // ---- M17 receive ----
    private volatile M17Station m17;
    /** Hold the radio's own receive audio back (speaker, SignalDeck Web, spectrum) because M17 is in play: the channel is
     *  an M17 channel, or PKT → M17 receive is decoding a transmission on an ordinary FM channel (its raw audio would be a
     *  4FSK buzz under the decoded voice). Works wherever {@link AudioBackend#appPlaysRadio} is true. */
    boolean rawHeld() { M17Station m = m17; return rawHeldFor(current != null && current.isM17(), m != null && m.receiving()); }
    static boolean rawHeldFor(boolean m17Channel, boolean m17Decoding) { return m17Channel || m17Decoding; }
    /** Whether SignalDeck can hold the radio's audio back on this phone ({@link #rawHeld}); false where the radio plays
     *  through the speaker by itself. */
    public boolean canHoldRawAudio() { AudioBackend a = audio; return a == null || a.appPlaysRadio(); }
    private volatile PagerStation pager;
    private volatile boolean pagerHold;
    private String openThread = "";
    /** An M17 (or later raw-discriminator) decoder wants the module's voice filter held open. */
    private volatile boolean dataReceive;
    private final java.util.concurrent.atomic.AtomicInteger dataHoldGen = new java.util.concurrent.atomic.AtomicInteger();
    public synchronized M17Station m17() {
        if (m17 == null) m17 = new M17Station(this, profile != null ? profile.id : "unknown", new M17Station.Host() {
            @Override public void event(String line) { history("pkt", line); }
            @Override public void text(String line) { history("m17", line); }
            @Override public void sms(String src, String dst, String body, boolean sent) { fileM17(src, dst, body, sent); }
            @Override public void heard(String call) {
                if (call == null || call.isEmpty() || call.startsWith("@") || call.startsWith("#")) return;
                logHeardStation(call, "M17", "M17");
                try { ChannelStore.Contact pc = store.contactByCallsign(call); if (pc != null) store.contactHeard(pc.id, System.currentTimeMillis(), "M17", current != null ? "on " + current.name : "", Double.NaN, Double.NaN); } catch (Throwable e) { }
            }
            @Override public void changed() { ui.post(() -> { pushModes(); publishM17Line(); }); }
            @Override public int meter() { return rssi; }
            @Override public void voice(short[] pcm) {                       // SignalDeck Web listeners hear the decoded voice (the radio's own audio is held back)
                com.macdmr.radio.web.WebHost w = web; if (w == null || muted) return;
                byte[] b = new byte[pcm.length * 2]; for (int i = 0; i < pcm.length; i++) { b[2 * i] = (byte) pcm[i]; b[2 * i + 1] = (byte) (pcm[i] >> 8); }
                w.rxAudio(b, b.length);
            }
        });
        return m17;
    }
    public boolean m17Running() { M17Station s = m17; return s != null && s.running(); }

    public PagerStation pager() {
        if (pager == null) pager = new PagerStation(new PagerStation.Host() {
            @Override public void page(ChannelStore.PageRow row) { filePage(row); }
            @Override public void tune(int hz) {
                log("pager tune " + hz);
                ChannelStore.Channel v = new ChannelStore.Channel();
                v.type = 1; v.rxFreq = v.txFreq = hz; v.band = 1; v.power = 0; v.sq = 2; v.name = "Pager";
                applyVfo(v, false);
            }
            @Override public int currentHz() { ChannelStore.Channel c = current; return c == null ? 0 : c.rxFreq; }
            @Override public double[] taps() { M17Station s = m17; return s == null ? null : s.taps(); }
            @Override public boolean held() { return dataReceive; }
            @Override public double tau() { return profile == null ? 0 : profile.pocsagTauSec; }
            @Override public com.macdmr.radio.ham.MessageThreads.Contacts contacts() { return threadContacts(); }
        });
        return pager;
    }
    public boolean pagerRunning() { PagerStation p = pager; return p != null && p.running(); }
    /**
     * Restricted mode (PR 26) hides a mode or an admin action. That branch is not merged here, so
     * every id is allowed. Each new web route calls one of these once; the merge keeps the call.
     */
    public boolean modeAllowed(String id) { return true; }
    public boolean adminAllowed(String id) { return true; }
    /** Hear pages on the current analog channel. A stub-tagged module holds the voice filter open. */
    public synchronized String setPager(boolean on) { return setPager(on, true); }
    public synchronized String setPager(boolean on, boolean hold) {
        PagerStation p = pager();
        if (!on) { p.stop(); if (pagerHold) { pagerHold = false; setM17(false); } return null; }
        if (current == null || current.isDigital()) return "Pages need an analog channel";
        boolean stub = com.macdmr.radio.Firmware.stubTag(moduleVersion);
        if (!stub) return "Page receive needs the bench firmware";
        if (hold && stub && !m17Running()) { String e = setM17(true, true); if (e != null) return e; pagerHold = true; }
        else if (hold && stub) m17().setHeldPath(true);
        p.start(hold && stub);
        return null;
    }
    public String setPagerMonitor(boolean on, int[] hz, int baud) {
        String e = setPager(true, true); if (e != null) return e;
        pager().setScan(hz, baud);
        return pager().monitor(on);
    }
    public void watchingThread(String key) { openThread = key == null ? "" : key; if (key != null && !key.isEmpty()) store.markThreadRead(key); }
    private com.macdmr.radio.ham.MessageThreads.Contacts threadContacts() {
        return new com.macdmr.radio.ham.MessageThreads.Contacts() {
            public long byCallsign(String call) { ChannelStore.Contact c = store.contactByCallsign(call); return c == null ? 0 : c.id; }
            public long byRic(int ric) { ChannelStore.Contact c = store.contactByRic(ric); return c == null ? 0 : c.id; }
            public String name(long id) { ChannelStore.Contact c = store.contactById(id); return c == null ? "" : c.name; }
        };
    }
    private void fileM17(String src, String dst, String body, boolean sent) {
        com.macdmr.radio.ham.MessageThreads.Key key = com.macdmr.radio.ham.MessageThreads.m17(src, dst, threadContacts());
        ChannelStore.PageRow row = new ChannelStore.PageRow();
        row.ts = System.currentTimeMillis(); row.text = body == null ? "" : body; row.signer = src == null ? "" : src;
        row.transport = "m17"; row.outgoing = sent; row.threadKey = PagerStation.threadKey(key); row.hz = current == null ? 0 : current.rxFreq;
        filePage(row);
    }
    private void rememberSentPage(int ric, int func, int baud, int hz, String text) {
        com.macdmr.radio.ham.MessageThreads.Key key = com.macdmr.radio.ham.MessageThreads.page(ric, text, threadContacts());
        ChannelStore.PageRow row = new ChannelStore.PageRow();
        row.ts = System.currentTimeMillis(); row.ric = ric; row.func = func; row.baud = baud; row.hz = hz; row.text = text;
        row.signer = com.macdmr.radio.ham.Pocsag.sender(text) == null ? "" : com.macdmr.radio.ham.Pocsag.sender(text);
        row.transport = "page"; row.outgoing = true; row.threadKey = PagerStation.threadKey(key);
        filePage(row);
    }
    private void filePage(ChannelStore.PageRow row) {
        if (row.threadKey == null || row.threadKey.isEmpty()) return;
        store.addPage(row);
        String who = row.ric < 0 ? "RIC unreadable" : (row.signer == null || row.signer.isEmpty() ? ("RIC " + row.ric) : row.signer);
        String line = ("m17".equals(row.transport) ? "✉ " : "📟 ") + who + ": " + row.text;
        if ("page".equals(row.transport)) { history("page", line); log("page ric " + (row.ric < 0 ? "unreadable" : row.ric) + " thread " + row.threadKey + " baud " + row.baud + " corrected " + row.corrected + " " + row.text); }
        if ("page".equals(row.transport) && !row.outgoing && com.macdmr.radio.Station.isMyRic(this, row.ric) && !mutedRic(row.ric))
            notifyUser("Page for you", row.text, false);
        final String key = row.threadKey, shown = line;
        ui.post(() -> { for (Listener l : listeners) l.onPage(key, shown); });
    }
    private boolean mutedRic(int ric) {
        for (String s : getSharedPreferences("pager", MODE_PRIVATE).getString("muted", "").split(",")) if (s.trim().equals(String.valueOf(ric))) return true;
        return false;
    }
    public void muteRic(int ric, boolean on) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String s : getSharedPreferences("pager", MODE_PRIVATE).getString("muted", "").split(",")) if (!s.trim().isEmpty()) set.add(s.trim());
        if (on) set.add(String.valueOf(ric)); else set.remove(String.valueOf(ric));
        getSharedPreferences("pager", MODE_PRIVATE).edit().putString("muted", String.join(",", set)).apply();
    }
    private boolean m17FromChannel;
    /** An M17 channel keeps the live receiver on, and the voice-filter hold with it. Leaving the channel stops only a receiver this started. */
    private void followM17Channel(ChannelStore.Channel ch) {
        if (ch != null && ch.isM17()) {
            m17FromChannel = true;
            String e = setM17(true, true);
            if (e != null) log("m17 channel: " + e);
            publishM17Line();
        } else if (m17FromChannel) {
            m17FromChannel = false;
            setM17(false);
        }
    }
    private void publishM17Line() {
        if (current == null || !current.isM17()) return;
        M17Station s = m17;
        M17Station.Heard h = s == null ? null : s.current();
        String line = h != null
                ? h.src + " → " + h.dst + "  " + h.frames + " frames"
                : (m17CanSend() ? "M17 " : "M17 receive only ") + current.m17Dest() + "  CAN " + current.can();
        for (Listener l : listeners) l.onCaller(line);
    }
    /** M17 receive on or off (null = done, else why not). A stub-tagged module holds the voice filter open. */
    public synchronized String setM17(boolean on) { return setM17(on, true); }

    /**
     * @param hold when starting, ask a stub-tagged module to hold the voice filter open and use the fixed taps.
     *             False keeps the calibrated equalizer and releases a hold. The M17 screen's button uses true.
     */
    public synchronized String setM17(boolean on, boolean hold) {
        M17Station s = m17();
        if (!on) { s.stop(); dataReceive = false; scheduleDataHold(false); pushModes(); return null; }
        ChannelStore.Channel c = current;
        if (c == null || c.isDigital()) return "M17 needs an analog (FM) channel or the VFO";
        String e = s.start();
        if (e != null) return e;
        dataReceive = hold;
        scheduleDataHold(hold);
        pushModes();
        return null;
    }

    /** After any unkey: the module's unkey path rewrites the voice filter, so a held M17 receive goes deaf until the hold is
     *  sent again (seen 2026-10-27 on the hotspot bench: after an M17 key the signal bar moved but no M17 was decoded until a
     *  channel change). Every transmit path calls this once its carrier is down. */
    private void holdAfterTx() { if (dataReceive) scheduleDataHold(true); }

    /** After a symbol-engine transmission (M17 voice and texts, pages, symbol streams) the module leaves its receive audio
     *  muted: measured 2026-10-27 on FW26, the receive RMS fell from about 5600 to 1.3 after the unkey and stayed there while
     *  the signal meter still moved, and reopening the phone's audio stream did not help; only a channel write brought it back
     *  (the owner's workaround was changing channel and back). So the current channel is written again, which also puts the
     *  M17 voice-filter hold back. */
    /** The first bench image that clears the module's own receive mute after a symbol key (FW31, phonedmrapp
     *  radio_firmware/reviews.md 2026-10-28): there a channel write would put the mute back, so only the hold is re-sent. */
    static final int SYM_UNMUTE_FW = 31;
    static boolean rewriteAfterSym(String moduleVersion) { return !(Firmware.stubTag(moduleVersion) && Firmware.tagNumber(moduleVersion) >= SYM_UNMUTE_FW); }
    private void rxAfterSym() {
        ChannelStore.Channel c = current;
        if (c == null) return;
        if (!rewriteAfterSym(moduleVersion)) {
            // FW31 unmutes itself, but a symbol key still leaves the receive path unable to lock until two channel writes
            // (bench 2026-10-28: key then one write = silent, two writes = 75 frames listed, 3 of 3). Stopgap until the
            // firmware restores that state itself.
            quietRewrite = true;
            try {
                boolean a = applyChannelNow(c, c != vfo); Thread.sleep(700); boolean b = applyChannelNow(c, c != vfo);
                log("rx after sym: FW31, channel " + c.number + " rewritten twice " + a + "/" + b);
            } catch (Throwable t) { log("rx after sym: " + t); }
            finally { quietRewrite = false; }
            return;
        }
        quietRewrite = true;                                   // the same channel again: no spoken announcement
        try { log("rx after sym: channel " + c.number + " rewritten " + applyChannelNow(c, c != vfo)); } catch (Throwable t) { log("rx after sym: " + t); }
        finally { quietRewrite = false; }
    }
    private volatile boolean quietRewrite;

    /** Re-send the voice-filter hold after a channel, VFO or tone write. Those writes put the filter back. */
    private void scheduleDataHold(boolean apply) {
        final int g = dataHoldGen.incrementAndGet();
        final boolean want = apply;
        worker.execute(() -> { if (g == dataHoldGen.get()) sendDataHold(want && dataReceive); });
    }

    private void sendDataHold(boolean apply) {
        try {
            if (!engine.isOpen()) { log("data hold: radio not open"); return; }
            if (moduleVersion.trim().isEmpty()) versionHandshake(1500);
            if (!Firmware.stubTag(moduleVersion)) { log("data hold: stock firmware, not sent"); M17Station s = m17; if (s != null) s.setHeldPath(false); return; }
            Frame pic = sym40(new byte[] { 6, 3, (byte) (apply ? 1 : 0) }, 1000);
            log("data hold action=" + (apply ? 1 : 0) + (pic == null ? " missing" : " sr=" + pic.sr));
            M17Station s = m17;
            if (s != null) s.setHeldPath(apply && pic != null && pic.sr == 0);
        } catch (Throwable t) { log("data hold: " + t); }
    }

    // ---- FT8 over FM ----
    private volatile com.macdmr.radio.ham.Ft8Station ft8;
    private java.util.concurrent.ScheduledExecutorService ft8Exec;
    private java.util.concurrent.ScheduledFuture<?> ft8Ticks;
    /** The FT8 station (slots, decodes, the QSO sequence) on the current FM channel. */
    public synchronized com.macdmr.radio.ham.Ft8Station ft8() {
        if (ft8 == null) {
            ft8Exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ft8"); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });
            ft8 = new com.macdmr.radio.ham.Ft8Station(new com.macdmr.radio.ham.Ft8Station.Host() {
                @Override public long now() { return System.currentTimeMillis(); }
                @Override public boolean canTransmit() { ChannelStore.Channel c = current; return c != null && !c.isDigital() && isReady() && !pttDown && !txInhibited(); }
                @Override public void transmit(short[] pcm, String label, Runnable done) { transmitPcm(pcm, label, done); }
                @Override public boolean transmitTones(int[] tones, double f0, int leadMs, String label, Runnable done) {
                    if (!tonePath()) return false;
                    String e = RadioService.this.transmitTones(com.macdmr.radio.ham.ToneQueue.ft8(tones, f0, leadMs), com.macdmr.radio.ham.ToneQueue.AMPLITUDE, label, done);
                    if (e != null) log("FT8 tone path not used: " + e);
                    return e == null;
                }
                @Override public String callsign() { return packet != null && packet.callsignSet() ? packet.callsign() : null; }
                @Override public String grid() { return ft8Grid(); }
                @Override public void event(String line) { history("pkt", line); }
                @Override public void heard(String call, String grid, int snr) {
                    logHeardStation(call, "FT8", "FT8 over FM, " + snr + " dB");
                    if (grid != null) try { logbook().enrich(call, null, grid, null); } catch (Throwable t) { log("logbook: " + t); }
                }
                @Override public void logQso(String call, String grid, String sent, String rcvd) {
                    try {
                        com.macdmr.radio.ham.Logbook.Entry e = new com.macdmr.radio.ham.Logbook.Entry();
                        ChannelStore.Channel c = current;
                        e.ts = System.currentTimeMillis(); e.call = call; e.freqHz = c != null ? c.rxFreq : 0; e.mode = "FT8"; e.qso = true;
                        e.rstSent = sent == null ? "" : sent; e.rstRcvd = rcvd == null ? "" : rcvd; e.grid = grid == null ? "" : grid; e.source = "FT8 over FM";
                        logbook().save(e); enrichCall(call, null);
                    } catch (Throwable t) { log("logbook: " + t); }
                }
                @Override public void decodeAsync(Runnable job) { ft8Exec.execute(() -> { long t0 = android.os.SystemClock.elapsedRealtime(); job.run(); log("FT8 slot decoded in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms"); }); }
            });
        }
        return ft8;
    }
    public boolean ft8Running() { com.macdmr.radio.ham.Ft8Station f = ft8; return f != null && f.running(); }
    /** Start or stop listening for FT8; the scheduler ticks four times a second while it runs. */
    public synchronized void setFt8(boolean on) {
        final com.macdmr.radio.ham.Ft8Station s = ft8();
        if (on) { s.start(); if (ft8Ticks == null) ft8Ticks = ft8Exec.scheduleAtFixedRate(() -> { try { s.tick(); } catch (Throwable t) { log("ft8: " + t); } }, 0, 250, java.util.concurrent.TimeUnit.MILLISECONDS); }
        else { s.stop(); if (ft8Ticks != null) { ft8Ticks.cancel(false); ft8Ticks = null; } }
        pushModes();
    }
    /** The 4-character grid FT8 sends: the one set in FT8 settings, else the phone's location; "-" sends none. */
    public String ft8Grid() {
        String g = getSharedPreferences("radio", MODE_PRIVATE).getString("ft8Grid", "").trim().toUpperCase(java.util.Locale.US);
        if (g.equals("-")) return "";
        if (g.length() >= 4) return g.substring(0, 4);
        android.location.Location l = lastKnownLocation();
        return l != null ? com.macdmr.radio.ham.Maidenhead.grid(l.getLatitude(), l.getLongitude(), 4) : "";
    }
    public String ft8GridSetting() { return getSharedPreferences("radio", MODE_PRIVATE).getString("ft8Grid", ""); }
    public void setFt8Grid(String g) { getSharedPreferences("radio", MODE_PRIVATE).edit().putString("ft8Grid", g == null ? "" : g.trim().toUpperCase(java.util.Locale.US)).apply(); }

    private PacketFinder finder;
    /** The packet finder (listens for BBSs, nodes and Winlink gateways). */
    public synchronized PacketFinder finder() { if (finder == null) finder = new PacketFinder(this); return finder; }
    private com.macdmr.radio.winlink.Winlink winlink;
    /** Winlink e-mail (internet CMS or a packet gateway on the current channel). */
    public synchronized com.macdmr.radio.winlink.Winlink winlink() {
        if (winlink == null) winlink = new com.macdmr.radio.winlink.Winlink(this, new com.macdmr.radio.winlink.Winlink.Radio() {
            @Override public com.macdmr.radio.winlink.B2fClient.Wire open(String gateway, String via, com.macdmr.radio.winlink.Winlink.StatusSink status) throws java.io.IOException {
                PacketEngine pe = packet(); if (pe == null) throw new java.io.IOException("Take over the radio first");
                return pe.openWinlinkLink(gateway, via, status);
            }
            @Override public void close() { PacketEngine pe = packet(); if (pe != null) pe.closeWinlinkLink(); }
            @Override public String callsign() { PacketEngine pe = packet(); if (pe != null) return pe.callsignSet() ? pe.callsign() : ""; String c = getSharedPreferences("packet", MODE_PRIVATE).getString("callsign", "").trim().toUpperCase(java.util.Locale.US).replaceAll("-.*", ""); return "N0CALL".equals(c) ? "" : c; }
            @Override public void notifyUser(String title, String text) { RadioService.this.notifyUser(title, text, false); }
            @Override public void formText(String from, String line) { ui.post(() -> onFormText(from, line, "Winlink")); }
            @Override public void frameLog(java.util.function.Consumer<String> l) { PacketEngine pe = packet(); if (pe != null) pe.setFrameLog(l); }
            @Override public String describe() {
                ChannelStore.Channel ch = vfo != null ? vfo : current;
                return "profile " + profile.id + (ch == null ? "" : ", on " + ch.freqText(ch.rxFreq) + " MHz") + (packet != null ? ", packet lead-in " + packet.packetLeadMs() + " ms" : "");
            }
            @Override public double[] position() { android.location.Location l = lastKnownLocation(); return l == null ? null : new double[] { l.getLatitude(), l.getLongitude() }; }
        });
        return winlink;
    }
    public com.macdmr.radio.ham.FormStore forms() { if (forms == null) forms = new com.macdmr.radio.ham.FormStore(this); return forms; }
    private final com.macdmr.radio.ham.Forms.Assembler formParts = new com.macdmr.radio.ham.Forms.Assembler();
    /** An APRS message or DMR text that may be part of a form: rebuilt when all its parts are in. */
    public void onFormText(String from, String text, String via) {
        try {
            if (com.macdmr.radio.ham.Forms.part(text) == null) return;
            com.macdmr.radio.ham.Forms.Form f = formParts.add(from, text, System.currentTimeMillis());
            if (f == null) { int[] p = formParts.progress(from, text); if (p != null) log("form part " + p[0] + "/" + p[1] + " from " + from); return; }
            com.macdmr.radio.ham.FormStore.Row r = new com.macdmr.radio.ham.FormStore.Row(); r.ts = System.currentTimeMillis(); r.outgoing = false; r.peer = from; r.via = via; r.status = "received"; r.form = f;
            forms().save(r);
            String subj = f.f.containsKey("SU") ? f.f.get("SU") : f.f.containsKey("NR") ? "number " + f.f.get("NR") : "";
            history("sms", "\uD83D\uDCCB " + com.macdmr.radio.ham.Forms.title(f.kind) + " from " + from + (subj.isEmpty() ? "" : ": " + subj));
            notifyUser(com.macdmr.radio.ham.Forms.title(f.kind) + " from " + from, subj.isEmpty() ? "Open PKT \u2192 Message forms" : subj, false);
        } catch (Throwable t) { log("form: " + t); }
    }
    /** Send a form: "aprs" to a callsign (67-character parts, one every 12 s) or "dmr" to a contact (120-character parts). Null or an error. */
    public String sendForm(final com.macdmr.radio.ham.Forms.Form f, String via, final String toCall, final ChannelStore.Contact toContact) {
        if (f.id == null || f.id.isEmpty()) f.id = com.macdmr.radio.ham.Forms.newId(new java.util.Random());
        final com.macdmr.radio.ham.FormStore.Row r = new com.macdmr.radio.ham.FormStore.Row(); r.ts = System.currentTimeMillis(); r.outgoing = true; r.form = f;
        if ("aprs".equals(via)) {
            if (packet == null) return "The packet engine is not running";
            if (toCall == null || toCall.trim().isEmpty()) return "Enter the callsign to send it to";
            final java.util.List<String> parts = com.macdmr.radio.ham.Forms.split(f, 67);
            String first = packet.sendMessage(toCall, parts.get(0)); if (first != null) return first;
            for (int k = 1; k < parts.size(); k++) { final String p = parts.get(k); ui.postDelayed(() -> { String e = packet.sendMessage(toCall, p); if (e != null) log("form part: " + e); }, k * 12_000L); }
            r.peer = toCall.trim().toUpperCase(Locale.US); r.via = "APRS"; r.status = "sent in " + parts.size() + " message" + (parts.size() == 1 ? "" : "s");
        } else if ("dmr".equals(via)) {
            if (toContact == null) return "Choose the contact or talkgroup";
            ChannelStore.Channel cur = current; if (cur == null || !cur.isDigital()) return "DMR texts need a DMR channel";
            final java.util.List<String> parts = com.macdmr.radio.ham.Forms.split(f, 120);
            for (int k = 0; k < parts.size(); k++) { final String p = parts.get(k); ui.postDelayed(() -> sendSms(toContact, p), k * 4_000L); }
            r.peer = toContact.name.isEmpty() ? String.valueOf(toContact.number) : toContact.name; r.via = "DMR"; r.status = "sent in " + parts.size() + " text" + (parts.size() == 1 ? "" : "s");
        } else if ("winlink".equals(via)) {
            if (toCall == null || toCall.trim().isEmpty()) return "Enter the callsign or e-mail address to send it to";
            String[] m = com.macdmr.radio.ham.Forms.winlink(f);
            String e = winlink().compose(toCall, "", m[0], m[1]); if (e != null) return e;
            r.peer = toCall.trim(); r.via = "Winlink"; r.status = "in the Winlink outbox";
        } else { r.peer = ""; r.via = "saved"; r.status = "saved"; }
        forms().save(r);
        return null;
    }

    // ---- DMR roaming (Roaming): the nearest repeater with the same talkgroup, while moving ----
    public boolean roaming() { return getSharedPreferences("radio", MODE_PRIVATE).getBoolean("roaming", false); }
    public void setRoaming(boolean on) { getSharedPreferences("radio", MODE_PRIVATE).edit().putBoolean("roaming", on).apply(); if (on) startRoaming(); else stopRoaming(); }
    private android.location.LocationListener roamGps; private volatile long lastRoam; private String lastRoamNote = "";
    private final Runnable roamTick = new Runnable() { @Override public void run() { try { roamCheck(); } catch (Throwable t) { log("roaming: " + t); } if (roamGps != null) ui.postDelayed(this, 30_000); } };
    private void startRoaming() {
        if (roamGps != null) return;
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE); if (lm == null) return;
        roamGps = l -> { };                                                   // keeps fixes coming; the check reads the newest one
        try { lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 15_000, 100, roamGps, android.os.Looper.getMainLooper()); }
        catch (Throwable t) { log("roaming GPS: " + t); }
        ui.removeCallbacks(roamTick); ui.postDelayed(roamTick, 5_000);
        log("DMR roaming on");
    }
    private void stopRoaming() {
        ui.removeCallbacks(roamTick);
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm != null && roamGps != null) try { lm.removeUpdates(roamGps); } catch (Throwable ignored) { }
        roamGps = null; log("DMR roaming off");
    }
    /** What roaming last did or why it waits (logged whenever it changes, so a drive can be checked afterwards). */
    public String roamingNote() { return lastRoamNote; }
    private void roamCheck() { String before = lastRoamNote; roamCheck0(); if (!lastRoamNote.equals(before)) log("roaming: " + lastRoamNote); }
    private void roamCheck0() {
        long now = System.currentTimeMillis();
        ChannelStore.Channel cur = current;
        if (cur == null || vfo != null || !cur.isDigital()) { lastRoamNote = "waiting: the radio is not on a DMR repeater channel"; return; }
        if (cur.lat == 0 && cur.lon == 0) { lastRoamNote = "waiting: " + cur.name + " has no location (Nearby Repeaters adds one)"; return; }
        if (pttDown || receiving || scanning() || scope.running() || satBusy(now, now + 60_000)) return;      // never during a call, a scan, the scope or a pass
        if (now - lastRoam < 120_000) return;                                 // at most one switch every two minutes
        android.location.Location me = lastKnownLocation();
        if (me == null || now - me.getTime() > 3 * 60_000L) { lastRoamNote = "waiting for a GPS fix"; return; }
        ChannelStore.Channel to = Roaming.choose(cur, zoneChannels(), me.getLatitude(), me.getLongitude());
        double here = Roaming.km(me.getLatitude(), me.getLongitude(), cur.lat, cur.lon);
        if (to == null) { lastRoamNote = String.format(Locale.US, "on the nearest repeater for this talkgroup (%s, %.0f km)", cur.name, here); return; }
        double there = Roaming.km(me.getLatitude(), me.getLongitude(), to.lat, to.lon);
        lastRoam = now;
        selectChannel(to.number);
        lastRoamNote = String.format(Locale.US, "switched to %s (%.0f km; %s was %.0f km)", to.name, there, cur.name, here);
        history("chan", "\uD83D\uDE97 Roaming: " + lastRoamNote);
    }

    // ---- band scope (BandScope): receive-only sweeps ----
    final BandScope scope = new BandScope(new BandScope.Hooks() {
        @Override public void tune(int hz) throws Exception { tunedAt = System.currentTimeMillis(); engine.setAnalog(hz, hz, hz < 300_000_000 ? 1 : 0, 0, scopeSq, 0, 0, 0, 0, 8, 2); if (dataReceive) scheduleDataHold(true); }   // low power, nothing keyed
        @Override public void query() throws Exception { engine.queryRssi(); }
        @Override public void restore() {                                   // inline (Talk waits for it on the worker thread)
            ChannelStore.Channel ch = vfo != null ? vfo : current;
            if (ch != null && engine.isOpen()) applyChannelNow(ch, false);
            log("band scope stopped: back on " + (ch == null ? "?" : ch.freqText(ch.rxFreq)));
        }
        @Override public void sweep(int from, int step, float[] dbm, int n) { ui.post(() -> { for (Listener l : listeners) l.onScope(from, step, dbm); }); }
        @Override public void state(String s) { ui.post(() -> { for (Listener l : listeners) l.onScopeState(s); }); }
    });
    public BandScope scope() { return scope; }
    volatile int scopeSq = 9;                                                 // the squelch byte for sweep steps (bench: DIAG scope --ei sq)
    /** Start a sweep (null) or why not: never around a tracked satellite pass or while talking. */
    public String scopeStart(int from, int to, int step, int settleMs) {
        if (!isReady()) return "The radio is not ready";
        if (pttDown) return "Not while transmitting";
        long now = System.currentTimeMillis();
        if (satBusy(now, now + 15 * 60_000L)) return "A satellite pass is being tracked (or starts within 15 minutes): the scope would retune the radio away from it";
        if (scanning()) toggleScan();
        String e = scope.start(from, to, step, settleMs);
        if (e == null) log(String.format(Locale.US, "band scope %.3f\u2013%.3f MHz, %d kHz steps, %d ms settle", from / 1e6, to / 1e6, step / 1000, settleMs));
        return e;
    }
    public void scopeStop() { scope.stop(); }

    // ---- scheduled operations (Scheduler): start, then put the radio back when the time is up ----
    public void runSchedule(int id) { ui.post(() -> runScheduleNow(id, 0)); }
    public void endSchedule(int id) { ui.post(() -> endScheduleNow(id)); }
    private void runScheduleNow(final int id, final int tries) {
        Scheduler.Entry e = Scheduler.byId(this, id); if (e == null) return;
        String what = "\u23F0 Scheduled: " + e.actionName() + (e.label.isEmpty() ? "" : " (" + e.label + ")");
        long t0 = System.currentTimeMillis(), t1 = t0 + (e.hasDuration() ? e.minutes * 60_000L : 60_000L);
        if (satBusy(t0, t1)) { history("pkt", what + " skipped: it overlaps the satellite pass being tracked"); return; }
        if (pttDown) { if (tries < 6) ui.postDelayed(() -> runScheduleNow(id, tries + 1), 20_000); else history("pkt", what + " skipped: the radio was transmitting"); return; }
        if (scope.running()) { scope.stop(); history("pkt", "\u2702 Band scope stopped for a scheduled operation"); }
        if (Scheduler.runningEnd(this) > 0) endScheduleNow(Scheduler.runningId(this));          // one at a time
        ChannelStore.Channel cur = current;
        int prevCh = cur != null && vfo == null ? cur.number : -1;
        boolean wasRec = recordOn, wasScan = scanning(), wasAprs = packet != null && packet.monitoring(), wasSstv = packet != null && packet.sstvRx();
        String r = null;
        switch (e.action) {
            case Scheduler.LISTEN:
                if (e.channel >= 0 && store.byNumber(e.channel) == null) { history("pkt", what + " skipped: channel " + e.channel + " no longer exists"); return; }
                if (wasScan) toggleScan();
                if (e.channel >= 0) selectChannel(e.channel);
                if (e.record && !wasRec) setRecord(true);
                r = (e.channel >= 0 ? "channel " + e.channel : "this channel") + (e.record ? ", recording" : "");
                break;
            case Scheduler.SCAN: r = wasScan ? "already scanning" : toggleScan(); break;
            case Scheduler.APRS: r = wasAprs ? "already monitoring" : aprsMonitor(true); break;
            case Scheduler.SSTV:
                if (wasSstv) { r = "already receiving"; break; }
                if (e.channel >= 0) { selectChannel(e.channel); ui.postDelayed(() -> sstvMonitor(true, false), RETUNE_SETTLE_MS); r = "on channel " + e.channel; }
                else r = sstvMonitor(true, true);
                break;
            case Scheduler.BEACON: r = packet == null ? "the packet engine is not running" : packet.sendBeacon(); if (r == null) r = "sent"; break;
            case Scheduler.FINDER: r = finder().start(e.minutes); if (r == null) r = "listening"; break;
            case Scheduler.WINLINK_NET: {
                final com.macdmr.radio.winlink.Winlink w = winlink();
                r = w.connectInternet();
                if (r == null) {
                    r = "connecting to the CMS";
                    final long until = System.currentTimeMillis() + 10 * 60_000L;
                    ui.postDelayed(new Runnable() { @Override public void run() {
                        if (w.busy() && System.currentTimeMillis() < until) { ui.postDelayed(this, 5_000); return; }
                        if (w.busy()) w.cancel();
                        history("pkt", "\u23F0 Scheduled Winlink check: " + w.lastResult());
                    } }, 5_000);
                }
                break;
            }
            case Scheduler.WINLINK: {
                final com.macdmr.radio.winlink.Winlink w = winlink();
                if (w.gateway().isEmpty()) { r = "skipped: no gateway yet (connect once with Winlink \u2192 Connect: radio)"; break; }
                if (w.busy()) { r = "skipped: a Winlink session is already running"; break; }
                if (e.channel >= 0 && store.byNumber(e.channel) == null) { r = "skipped: channel " + e.channel + " no longer exists"; break; }
                final boolean move = e.channel >= 0 && e.channel != prevCh; final int back = prevCh;
                if (wasScan) toggleScan();
                if (move) selectChannel(e.channel);
                ui.postDelayed(() -> {
                    String err = w.connectRadio(w.gateway(), w.via());
                    if (err != null) { history("pkt", "\u23F0 Scheduled Winlink check: " + err); if (move && back >= 0) selectChannel(back); return; }
                    final long until = System.currentTimeMillis() + 20 * 60_000L;   // never hold the radio longer than 20 minutes
                    ui.postDelayed(new Runnable() { @Override public void run() {
                        if (w.busy() && System.currentTimeMillis() < until) { ui.postDelayed(this, 5_000); return; }
                        if (w.busy()) w.cancel();
                        history("pkt", "\u23F0 Scheduled Winlink check: " + w.lastResult() + (move && back >= 0 ? ", back to channel " + back : ""));
                        if (move && back >= 0 && store.byNumber(back) != null) selectChannel(back);
                        if (wasScan) ui.postDelayed(RadioService.this::toggleScan, RETUNE_SETTLE_MS);
                    } }, 5_000);
                }, move ? RETUNE_SETTLE_MS : 0);
                r = "calling " + w.gateway() + (move ? " on channel " + e.channel : "");
                break;
            }
        }
        history("pkt", what + (r == null ? "" : ": " + r) + (e.hasDuration() ? " for " + e.minutes + " min" : ""));
        if (!e.hasDuration()) return;
        getSharedPreferences("schedules", MODE_PRIVATE).edit().putInt("prevCh", prevCh).putBoolean("startedRec", e.record && !wasRec)
                .putBoolean("startedScan", Scheduler.SCAN.equals(e.action) && !wasScan).putBoolean("startedAprs", Scheduler.APRS.equals(e.action) && !wasAprs)
                .putBoolean("startedSstv", Scheduler.SSTV.equals(e.action) && !wasSstv).putBoolean("stoppedScan", Scheduler.LISTEN.equals(e.action) && wasScan).apply();
        Scheduler.setRunning(this, id, System.currentTimeMillis() + e.minutes * 60_000L);
        Scheduler.arm(this);
    }
    /** A tracked satellite pass needs the radio between {@code from} and {@code to} (2 min before it rises to 1 min after it sets). */
    boolean satBusy(long from, long to) {
        SatState st = satState; if (st == null) return false;
        if (!st.waiting) return true;
        return from < st.los + 60_000L && to > st.aos - 120_000L;
    }
    private void endScheduleNow(int id) {
        if (Scheduler.runningId(this) != id || Scheduler.runningEnd(this) == 0) return;
        android.content.SharedPreferences p = getSharedPreferences("schedules", MODE_PRIVATE);
        Scheduler.Entry e = Scheduler.byId(this, id);
        Scheduler.setRunning(this, 0, 0);
        if (p.getBoolean("startedRec", false) && recordOn) setRecord(false);
        if (p.getBoolean("startedScan", false) && scanning()) toggleScan();
        if (e != null && Scheduler.FINDER.equals(e.action) && finder != null && finder.running()) finder.stop();   // it puts the radio back itself
        boolean satOn = satState != null && !satState.waiting;                // a pass that started meanwhile keeps its decoder
        if (p.getBoolean("startedAprs", false) && packet != null && packet.monitoring() && !satOn) aprsMonitor(false);
        if (p.getBoolean("startedSstv", false) && packet != null && packet.sstvRx() && !satOn) sstvMonitor(false, false);
        int prev = p.getInt("prevCh", -1);
        boolean moved = e != null && e.channel >= 0 && (Scheduler.LISTEN.equals(e.action) || Scheduler.SSTV.equals(e.action)) && p.getInt("prevCh", -1) != e.channel;
        if (moved && prev >= 0 && !satOn) { ChannelStore.Channel cur = current; if (cur != null && cur.number == e.channel && store.byNumber(prev) != null) selectChannel(prev); }
        if (p.getBoolean("stoppedScan", false) && !scanning()) ui.postDelayed(this::toggleScan, RETUNE_SETTLE_MS);
        history("pkt", "\u23F0 Scheduled: " + (e == null ? "operation" : e.actionName()) + " ended" + (moved && prev >= 0 ? ": back to channel " + prev : ""));
        Scheduler.arm(this);
    }

    /** Bench (DiagReceiver): re-send the current analog channel/VFO with another module volume byte (normally 8). */
    public void diagAnalogVolume(final int vol) {
        final ChannelStore.Channel ch = vfo != null ? vfo : current; if (ch == null || ch.isDigital()) return;
        worker.execute(() -> { try { tunedAt = System.currentTimeMillis(); engine.setAnalog(ch.rxFreq, ch.txFreq, ch.band, ch.power, hardwareSq(ch), ch.rxType, ch.rxSubCode, ch.txType, ch.txSubCode, vol, 2); log("bench: module volume " + vol); } catch (Exception e) { log("bench volume: " + e); } });
    }

    // ---- bench: play a WAV into the receive path as if the radio heard it (DiagReceiver, adb only) ----
    private volatile boolean injecting;
    public void injectWav(final java.io.File f) {
        new Thread(() -> {
            try {
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(all).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int ch = 1, rate = 8000, pos = 12, dataAt = -1, dataLen = 0;
                while (pos + 8 <= all.length) {
                    String id = new String(all, pos, 4, java.nio.charset.StandardCharsets.US_ASCII); int len = bb.getInt(pos + 4);
                    if (id.equals("fmt ")) { ch = bb.getShort(pos + 10); rate = bb.getInt(pos + 12); }
                    if (id.equals("data")) { dataAt = pos + 8; dataLen = Math.min(len, all.length - dataAt); break; }
                    pos += 8 + len + (len & 1);
                }
                if (dataAt < 0) { log("inject: no data chunk in " + f); return; }
                int frames = dataLen / (2 * ch);
                int outN = (int) ((long) frames * 8000 / rate);
                log("inject " + f.getName() + ": " + rate + " Hz, " + ch + " ch, " + (outN / 8) + " ms");
                injecting = true;
                byte[] blk = new byte[2048];   // 512 stereo samples = 64 ms
                long t0 = System.currentTimeMillis(); int k = 0;
                for (int o = 0; o < outN; ) {
                    int m = 0;
                    for (; m < 512 && o < outN; m++, o++) {
                        int src = (int) ((long) o * rate / 8000); short v = bb.getShort(dataAt + src * 2 * ch);
                        blk[m * 4] = blk[m * 4 + 2] = (byte) v; blk[m * 4 + 1] = blk[m * 4 + 3] = (byte) (v >> 8);
                    }
                    onRxPcm(blk, m * 4);
                    k += m; long due = t0 + k / 8; long now = System.currentTimeMillis(); if (due > now) Thread.sleep(due - now);
                }
            } catch (Throwable t) { log("inject: " + t); }
            finally { injecting = false; byte[] quiet = new byte[2048]; for (int r = 0; r < 4; r++) onRxPcm(quiet, quiet.length); log("inject done"); }
        }, "diag-inject").start();
    }
}
