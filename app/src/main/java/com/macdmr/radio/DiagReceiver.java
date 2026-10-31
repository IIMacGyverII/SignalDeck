/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Bench tools for the developer, reachable only from adb: RadioService registers it at runtime with the DUMP
 * permission required of the sender, which the shell holds and apps cannot get.
 * <pre>
 * am broadcast -a com.macdmr.radio.DIAG --es cmd inject --es path /sdcard/Download/x.wav
 * am broadcast -a com.macdmr.radio.DIAG --es cmd isline --es line "SRC>APRS,TCPIP*::DEST     :hi{1"
 * am broadcast -a com.macdmr.radio.DIAG --es cmd heard --es call DEST        (as if DEST was heard on the radio)
 * am broadcast -a com.macdmr.radio.DIAG --es cmd benchIgate --ez on true       (gate to RF without APRS-IS and off the APRS frequency)
 * am broadcast -a com.macdmr.radio.DIAG --es cmd pkt --es what object|bulletin|telemetry|weather|cwid|beacon|connect|disconnect
 * am broadcast -a com.macdmr.radio.DIAG --es cmd checks --es watch auto --el hosttime 1793000000000   (Run all checks, no screen)
 * </pre>
 * {@code inject} plays a WAV file (16-bit PCM, any rate, mono or stereo) into the receive path in real time, as if
 * the radio heard it: decoders, the parrot and the DTMF remote see it, nothing is keyed by the injection itself.
 * i18n: stays English (developer report/bench text, plan §1)
 */
public final class DiagReceiver extends BroadcastReceiver {
    /** A result for a DIAG line in English: a Msg's English, or the English of a text a method returned in the app language. */
    static String en(Object o) {
        if (o instanceof com.macdmr.radio.i18n.Msg) return ((com.macdmr.radio.i18n.Msg) o).english();
        return o == null ? "null" : com.macdmr.radio.i18n.Txt.english(String.valueOf(o));
    }
    public static final String ACTION = "com.macdmr.radio.DIAG";
    @Override public void onReceive(Context c, Intent i) {
        RadioService s = RadioService.running;
        String cmd = i.getStringExtra("cmd");
        Log.i("DiagReceiver", "cmd " + cmd);
        if (s == null || cmd == null) return;
        switch (cmd) {
            case "inject": s.injectWav(new java.io.File(i.getStringExtra("path"))); break;
            case "isline": if (s.packet() != null) s.packet().diagServerLine(i.getStringExtra("line")); break;
            case "heard": if (s.packet() != null) s.packet().diagHeard(i.getStringExtra("call")); break;
            case "vfo": {                                                       // bench: the VFO on --ei hz (wide; low power unless --ei power 1; --ef ctcss Hz: transmit tone; nothing is keyed)
                int hz = i.getIntExtra("hz", 0); if (!com.macdmr.radio.DeviceProfile.inBands(hz)) { Log.w("DiagReceiver", "vfo: bad hz " + hz); break; }
                s.diagVfo(hz, i.getIntExtra("power", 0) == 1 ? 1 : 0, i.getFloatExtra("ctcss", 0f)); break;
            }
            case "scope": {                                                     // bench: --ei from --ei to --ei step --ei settle (ms) --ei sweeps N: log N sweeps, then stop
                final int sw = Math.max(1, i.getIntExtra("sweeps", 2));
                s.scope().setTimeoutMs(i.getIntExtra("timeout", 900));
                s.scopeSq = Math.max(0, Math.min(9, i.getIntExtra("sq", 9)));
                String e = s.scopeStart(i.getIntExtra("from", 146_400_000), i.getIntExtra("to", 146_640_000), i.getIntExtra("step", 25_000), i.getIntExtra("settle", 40));
                Log.i("DiagReceiver", "scope: " + (e == null ? "started" : en(e)));
                if (e == null) new Thread(() -> {
                    int done = 0; long seen = -1;
                    for (int k = 0; k < 600 && done < sw; k++) {
                        try { Thread.sleep(250); } catch (InterruptedException x) { return; }
                        java.util.List<float[]> h = s.scope().history(); if (h.size() > seen && h.size() > 0) { seen = h.size(); done++; float[] r = h.get(h.size() - 1); StringBuilder b = new StringBuilder(); for (float v : r) b.append(Float.isNaN(v) ? "nan" : String.format(java.util.Locale.US, "%.1f", v)).append(' '); Log.i("DiagReceiver", "scope sweep " + done + " (" + s.scope().sweepMs() + " ms): " + b + " | ms " + java.util.Arrays.toString(s.scope().latency())); }
                    }
                    s.scopeStop();
                }, "diag-scope").start();
                break;
            }
            case "checks": FirstRunCheck.benchRun(c.getApplicationContext(), s, i.getStringExtra("watch"), i.getBooleanExtra("trial", false), i.getLongExtra("hosttime", 0)); break;   // bench: Run all checks with no screen (--es watch auto|none|<pkg>, --ez trial, --el hosttime ms); scripts/w6/run_checks.sh
            case "reimport": { final String area = i.getStringExtra("area"); new Thread(() -> android.util.Log.i("SignalDeck", "reimport: " + en(s.reimportFromStock(area))), "diag-reimport").start(); break; }   // bench: Device → Re-import without the dialog (--es area "3 …"; none = the default/current area). Replaces the channel list
            case "audioprobe": { final int ms = i.getIntExtra("ms", 2000); final double hz = i.getIntExtra("hz", 1000); final String tag = String.valueOf(i.getStringExtra("tag"));   // bench: does the receive audio reach Android's recorder? one line per source to the log
                new Thread(() -> { for (String l : AudioSourceProbe.run(ms, hz).split("\n")) Log.i("AudioProbe", tag + " " + l); Log.i("AudioProbe", tag + " done"); }, "audioprobe").start(); break; }
            case "reportzip": new Thread(() -> { try { Log.i("DiagReceiver", "reportzip: " + DeviceReport.zipNewest(c, false, false)); } catch (Throwable t) { Log.w("DiagReceiver", "reportzip: " + t); } }, "diag-zip").start(); break;   // bench: the summary + zip
            case "txwav": { String nm = i.getStringExtra("name"); if (nm != null && nm.matches("[A-Za-z0-9_-]{1,40}")) Log.i("DiagReceiver", "txwav: " + en(s.diagTxWav(new java.io.File(c.getCacheDir(), "tx_" + nm + ".wav")))); break; }   // bench: transmit cache/tx_<name>.wav (low power VFO)
            case "rxvol": s.diagAnalogVolume(Math.max(0, Math.min(255, i.getIntExtra("v", 8)))); break;   // bench: the module volume byte of the current analog channel
            case "bringup": s.bringUp(); break;                             // bench: Device -> Take over without a tap (also for an untested profile)
            case "exit": s.exit(i.getBooleanExtra("stock", false), r -> Log.i("DiagReceiver", "exit: " + en(r))); break;   // bench: Exit (stock false) or Hand back (true) without a tap
            case "restartmodule": s.diagRestartModule(); break;              // bench: the stuck-module watchdog's restart (power cycle, channel, MON, audio back)
            case "raw40": s.diagRaw40(new java.io.File(i.getStringExtra("path"))); break;  // bench: one 0x40 body from a file, reply in cache/raw40.bin, does not key
            case "fwload": s.diagFwLoad(new java.io.File(i.getStringExtra("file")), i.getBooleanExtra("abort50", false), i.hasExtra("pause") ? i.getIntExtra("pause", 300) : -1); break;  // bench: YModem-1K into the module. abort50 closes the port halfway. --ei pause sets the gap before each data block.
            case "fwcatalog": new Thread(() -> android.util.Log.i("DiagReceiver", "fwcatalog " + com.macdmr.radio.fw.FirmwareCatalog.fetch(c)), "fwcatalog").start(); break;
            case "fwfind": new Thread(() -> {                                   // bench: the Radio firmware files scan headless; every entry to the log, nothing copied
                try {
                    String stock = s.profile().stockApp;
                    java.util.List<String> apks = FirmwareFinder.stockApks(c, stock);
                    java.util.List<FirmwareFinder.Found> fs = FirmwareFinder.scan(c, apks, m -> Log.i("DiagReceiver", "fwfind: " + m));
                    if (apks.isEmpty()) fs.add(FirmwareFinder.noStockApk(stock));
                    Log.i("DiagReceiver", "fwfind: " + fs.size() + " entries, stock apks " + apks);
                    for (FirmwareFinder.Found f : fs) Log.i("DiagReceiver", "fwfind: " + f.line());
                } catch (Throwable t) { Log.w("DiagReceiver", "fwfind: " + t); }
                finally { FirmwareFinder.cleanUp(c); }
            }, "fwfind").start(); break;
            case "fwunlistedbad": {                                                                 // bench: load the first unlisted file, then treat the tag as wrong
                java.util.List<com.macdmr.radio.fw.ImageTable.Row> files = com.macdmr.radio.fw.Unlisted.files(c);
                if (files.isEmpty()) { android.util.Log.w("DiagReceiver", "fwunlistedbad: no unlisted file"); break; }
                com.macdmr.radio.fw.ImageTable.Row row = files.get(0);
                byte[] image = com.macdmr.radio.fw.FirmwareVault.bytesFor(c, row);
                if (image == null) { android.util.Log.w("DiagReceiver", "fwunlistedbad: file not readable"); break; }
                com.macdmr.radio.fw.Unlisted.allow(c, true);
                s.loadFirmware(row, image, new com.macdmr.radio.fw.FirmwareSession.Ui() {
                    public void phase(String name) { android.util.Log.i("DiagReceiver", "fwunlistedbad phase " + name); }
                    public void log(String line) { android.util.Log.i("DiagReceiver", "fwunlistedbad " + line); }
                    public void block(int done, int total, long ackMs, int retries) { if (done == 1 || done == total || done % 50 == 0) android.util.Log.i("DiagReceiver", "fwunlistedbad block " + done + "/" + total); }
                    public void checks(java.util.List<com.macdmr.radio.fw.FirmwareSession.Check> checks) { for (com.macdmr.radio.fw.FirmwareSession.Check k : checks) android.util.Log.i("DiagReceiver", "fwunlistedbad check " + (k.ok ? "ok" : "no") + " " + k.name + " " + k.detail); }
                    public void finished(String line) { android.util.Log.i("DiagReceiver", "fwunlistedbad done " + line); }
                }, true);
                break;
            }
            case "fwpage": new android.os.Handler(android.os.Looper.getMainLooper()).post(com.macdmr.radio.MainActivity.openFirmwarePage); break;
            case "fwbadtag": {                                                                 // bench: load FW22, then treat the tag as wrong so the page's restore runs
                byte[] image = com.macdmr.radio.fw.FirmwareVault.bytesFor(c, com.macdmr.radio.fw.ImageTable.FW22);
                if (image == null) { Log.w("DiagReceiver", "fwbadtag: FW22 is not in Download/DMR/Firmware"); break; }
                s.loadFirmware(com.macdmr.radio.fw.ImageTable.FW22, image, new com.macdmr.radio.fw.FirmwareSession.Ui() {
                    public void phase(String name) { Log.i("DiagReceiver", "fwbadtag phase " + name); }
                    public void log(String line) { Log.i("DiagReceiver", "fwbadtag " + line); }
                    public void block(int done, int total, long ackMs, int retries) { if (done == 1 || done == total || done % 50 == 0) Log.i("DiagReceiver", "fwbadtag block " + done + "/" + total); }
                    public void checks(java.util.List<com.macdmr.radio.fw.FirmwareSession.Check> checks) { for (com.macdmr.radio.fw.FirmwareSession.Check k : checks) Log.i("DiagReceiver", "fwbadtag check " + (k.ok ? "ok" : "no") + " " + k.name + " " + k.detail); }
                    public void finished(String line) { Log.i("DiagReceiver", "fwbadtag done " + line); }
                }, true);
                break;
            }
            case "cmdbyte": s.diagCmd(i.getIntExtra("b", 0), i.getIntExtra("d", 1)); break;        // bench: one command, body byte --ei d (default 1), does not key
            case "cmdsine": s.diagSine(i.getIntExtra("n", 64), i.getIntExtra("amp", 2048), i.getIntExtra("hz", 1000)); break;  // bench: store a sine on 0x40, does not key
            case "cmdpcm": s.diagPcm(new java.io.File(i.getStringExtra("path")), i.getIntExtra("off", 0), i.getIntExtra("n", 398)); break;  // bench: store one piece, does not key
            case "cmdsym": {                                                     // bench: stream packed symbols. --ez eot marks the last chunk op 7 and unkeys after status 4. --ez key keys with no end bit. --ez nounkey leaves the unkey unsent.
                if ("status".equals(i.getStringExtra("op"))) s.diagSymStatus();
                else if ("render".equals(i.getStringExtra("op"))) s.diagSymRender(new java.io.File(i.getStringExtra("path")), i.getIntExtra("n", 0));
                else if ("hold".equals(i.getStringExtra("op"))) s.diagHold(i.getIntExtra("mode", 0), i.getIntExtra("action", 0));
                else if ("rxram".equals(i.getStringExtra("op"))) s.diagRxRam(i.getIntExtra("mode", 0), i.getIntExtra("n", 4));
                else s.diagSym(new java.io.File(i.getStringExtra("path")), i.getBooleanExtra("eot", false), i.getBooleanExtra("key", false), i.getBooleanExtra("nounkey", false));
                break;
            }
            case "page": {                                                      // bench: one POCSAG page, unsigned. --ei baud, --ei hz, --es rics "1000001,1000002", --es text
                String ricText = i.getStringExtra("rics"); String text = i.getStringExtra("text");
                if (ricText == null || text == null || text.isEmpty()) { Log.w("DiagReceiver", "page: rics and text"); break; }
                String[] parts = ricText.split("[, ]+");
                int[] rics = new int[parts.length];
                try { for (int k = 0; k < parts.length; k++) rics[k] = Integer.parseInt(parts[k]); }
                catch (NumberFormatException e) { Log.w("DiagReceiver", "page: bad ric"); break; }
                String err = s.sendPage(i.getIntExtra("baud", 1200), i.getIntExtra("hz", 467_625_000), rics, 3, text, false);
                Log.i("DiagReceiver", err == null ? "page started " + rics.length + " at " + i.getIntExtra("baud", 1200) : "page: " + en(err));
                break;
            }
            case "contact": {                                              // bench: save a contact the way the editor does
                com.macdmr.radio.ChannelStore.Contact ct = new com.macdmr.radio.ChannelStore.Contact();
                ct.name = i.getStringExtra("name");
                if (ct.name == null || ct.name.isEmpty()) ct.name = "Contact";
                ct.callsign = ct.name;
                ct.type = 0;
                int ric = i.getIntExtra("ric", -1);
                if (ric >= 0) ct.rics = String.valueOf(ric);
                s.store().insertContact(ct);
                Log.i("DiagReceiver", "contact saved id " + ct.id + " ric " + ct.rics);
                break;
            }
            case "pager": {
                boolean hold = !i.hasExtra("hold") || i.getBooleanExtra("hold", true);
                if (i.hasExtra("monitor")) {
                    String raw = i.getStringExtra("freqs");
                    java.util.List<Integer> list = new java.util.ArrayList<>();
                    if (raw != null) for (String part : raw.split("[, ]+")) { try { list.add(Integer.parseInt(part.trim())); } catch (Exception ignored) { } }
                    int[] hz = new int[list.size()]; for (int k = 0; k < list.size(); k++) hz[k] = list.get(k);
                    Log.i("DiagReceiver", "pager monitor: " + en(s.setPagerMonitor(i.getBooleanExtra("monitor", false), hz, i.getIntExtra("baud", 1200))));
                    break;
                }
                if (i.hasExtra("saveRic")) {
                    com.macdmr.radio.ChannelStore.Contact ct = new com.macdmr.radio.ChannelStore.Contact();
                    String name = i.getStringExtra("name");
                    ct.name = name == null || name.isEmpty() ? "Bench Page" : name;
                    ct.type = 0;
                    ct.rics = String.valueOf(i.getIntExtra("saveRic", 0));
                    s.store().insertContact(ct);
                    Log.i("DiagReceiver", "contact saved id " + ct.id + " ric " + ct.rics);
                    break;
                }
                if (i.hasExtra("on")) Log.i("DiagReceiver", "pager: " + en(s.setPager(i.getBooleanExtra("on", false), hold)));
                break;
            }
            case "m17": {                                                       // bench: M17 receive on/off (--ez on); --ei tx ms: a short Codec 2 tone through the symbol engine; --ei mic ms: hold the microphone path (not the tone) for that long, at most 12 s; --es enc NAME: Codec 2 3200 encode cache/NAME.wav to cache/NAME.c2; --es dec NAME: decode cache/NAME.c2 back to cache/NAME.wav
                if (i.hasExtra("mic")) {
                    int ms = Math.max(1000, Math.min(12000, i.getIntExtra("mic", 10000)));
                    playAlarmWav(new java.io.File("/sdcard/Download/sdvoice.wav"));
                    s.m17Voice(true);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> s.m17Voice(false), ms);
                    Log.i("DiagReceiver", "m17 mic " + ms + " ms");
                    break;
                }
                if (i.hasExtra("tx")) { s.m17BenchTone(i.getIntExtra("tx", 1500)); break; }
                String sms = i.getStringExtra("sms");
                if (sms != null) { Log.i("DiagReceiver", "m17 sms: " + en(s.sendM17Sms(i.getStringExtra("to"), sms))); break; }
                if (i.hasExtra("dump")) com.macdmr.radio.M17Station.benchDump = i.getBooleanExtra("dump", false);
                if (i.hasExtra("on")) Log.i("DiagReceiver", "m17: " + en(s.setM17(i.getBooleanExtra("on", false), !i.hasExtra("hold") || i.getBooleanExtra("hold", true))));
                String nm = i.getStringExtra("enc");
                if (nm != null && nm.matches("[A-Za-z0-9_-]{1,40}")) {
                    try (com.macdmr.radio.ham.Codec2 c2 = new com.macdmr.radio.ham.Codec2(com.macdmr.radio.ham.Codec2.MODE_3200)) {
                        byte[] w = java.nio.file.Files.readAllBytes(new java.io.File(c.getCacheDir(), nm + ".wav").toPath());
                        int data = 12; while (!(w[data] == 'd' && w[data + 1] == 'a' && w[data + 2] == 't' && w[data + 3] == 'a')) data += 8 + ((w[data + 4] & 0xFF) | (w[data + 5] & 0xFF) << 8);
                        int n = Math.min((w.length - data - 8) / 2, ((w[data + 4] & 0xFF) | (w[data + 5] & 0xFF) << 8 | (w[data + 6] & 0xFF) << 16) / 2);
                        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); short[] fr = new short[c2.samples];
                        for (int k = 0; k + c2.samples <= n; k += c2.samples) { for (int j = 0; j < c2.samples; j++) fr[j] = (short) ((w[data + 8 + 2 * (k + j)] & 0xFF) | (w[data + 9 + 2 * (k + j)] << 8)); o.write(c2.encode(fr)); }
                        java.nio.file.Files.write(new java.io.File(c.getCacheDir(), nm + ".c2").toPath(), o.toByteArray());
                        Log.i("DiagReceiver", "m17 enc: " + o.size() + " bytes, " + o.size() / c2.bytes + " frames");
                    } catch (Throwable t) { Log.w("DiagReceiver", "m17 enc: " + t); }
                }
                String decNm = i.getStringExtra("dec");
                if (decNm != null && decNm.matches("[A-Za-z0-9_-]{1,40}")) {
                    try (com.macdmr.radio.ham.Codec2 c2 = new com.macdmr.radio.ham.Codec2(com.macdmr.radio.ham.Codec2.MODE_3200)) {
                        byte[] bits = java.nio.file.Files.readAllBytes(new java.io.File(c.getCacheDir(), decNm + ".c2").toPath());
                        int nf = bits.length / c2.bytes;
                        short[] pcm = new short[nf * c2.samples];
                        byte[] frb = new byte[c2.bytes];
                        for (int k = 0; k < nf; k++) {
                            System.arraycopy(bits, k * c2.bytes, frb, 0, c2.bytes);
                            short[] speech = c2.decode(frb);
                            System.arraycopy(speech, 0, pcm, k * c2.samples, speech.length);
                        }
                        byte[] raw = new byte[pcm.length * 2];
                        for (int j = 0; j < pcm.length; j++) { raw[2 * j] = (byte) pcm[j]; raw[2 * j + 1] = (byte) (pcm[j] >> 8); }
                        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(44 + raw.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        bb.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + raw.length);
                        bb.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
                        bb.putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
                        bb.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(raw.length).put(raw);
                        java.nio.file.Files.write(new java.io.File(c.getCacheDir(), decNm + ".wav").toPath(), bb.array());
                        Log.i("DiagReceiver", "m17 dec: " + nf + " frames");
                    } catch (Throwable t) { Log.w("DiagReceiver", "m17 dec: " + t); }
                }
                com.macdmr.radio.M17Station m = s.m17();
                for (com.macdmr.radio.M17Station.Heard h : m.heard()) Log.i("DiagReceiver", "m17 heard " + h.src + " -> " + h.dst + " frames " + h.frames + " good " + h.good + (h.meta != null ? " meta " + h.meta : ""));
                Log.i("DiagReceiver", "m17: running " + m.running() + ", receiving " + m.receiving() + ", " + m.calibrationEnglish());
                break;
            }
            case "ft8": {                                                       // bench: FT8 on/off (--ez on), send one message (--es msg) in a period (--ei parity 0/1); decodes go to the log
                com.macdmr.radio.ham.Ft8Station f = s.ft8();
                if (i.hasExtra("on")) s.setFt8(i.getBooleanExtra("on", false));
                if (i.hasExtra("parity")) f.setTxParity(i.getIntExtra("parity", 0));
                if (i.hasExtra("advance")) com.macdmr.radio.ham.Ft8Station.txAdvanceMs = Math.max(0, Math.min(3000, i.getIntExtra("advance", 1100)));
                String m = i.getStringExtra("msg"); if (m != null) Log.i("DiagReceiver", "ft8 send: " + en(f.sendText(m)));
                if (i.getBooleanExtra("cq", false)) Log.i("DiagReceiver", "ft8 cq: " + en(f.callCq()));
                if (i.getBooleanExtra("reply", false)) {                      // bench: answer the newest CQ heard, as a tap on it would
                    com.macdmr.radio.ham.Ft8Station.Heard cq = null;
                    for (com.macdmr.radio.ham.Ft8Station.Heard h : f.heard()) if (h.cq) cq = h;
                    Log.i("DiagReceiver", "ft8 reply: " + (cq == null ? "no CQ heard" : f.reply(cq)));
                }
                if (i.getBooleanExtra("halt", false)) f.haltTx();
                for (com.macdmr.radio.ham.Ft8Station.Heard h : f.heard()) Log.i("DiagReceiver", "ft8 heard " + h.time() + " " + h.d);
                Log.i("DiagReceiver", "ft8: " + f.statusMsg().english() + ", running " + f.running() + ", tx " + f.txEnabled() + " " + f.nextMessage() + ", parity " + f.txParity() + ", advance " + com.macdmr.radio.ham.Ft8Station.txAdvanceMs);
                break;
            }
            case "rxclip": {                                                    // bench: record the receive audio (8 kHz mono, before any app squelch) for --ei ms into cache/rxclip_<name>.wav
                final int ms = Math.max(1000, Math.min(120_000, i.getIntExtra("ms", 10_000)));
                String nm = i.getStringExtra("name"); if (nm == null || !nm.matches("[A-Za-z0-9_-]{1,40}")) nm = "clip";
                try {
                    final java.io.File f = new java.io.File(c.getCacheDir(), "rxclip_" + nm + ".wav");
                    final ClipRecorder cr = new ClipRecorder(f, ms); s.recordClip(cr, false);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { s.recordClip(null, false); cr.finish(); Log.i("DiagReceiver", "rxclip done: " + f + " " + f.length() + " B"); }, ms + 300);
                    Log.i("DiagReceiver", "rxclip start " + ms + " ms");
                } catch (Throwable t) { Log.w("DiagReceiver", "rxclip: " + t); }
                break;
            }
            case "wlbench": com.macdmr.radio.winlink.Winlink.benchPort = Math.max(0, Math.min(65535, i.getIntExtra("port", 0))); Log.i("DiagReceiver", "wlbench port " + com.macdmr.radio.winlink.Winlink.benchPort); break;   // bench: test CMS on 127.0.0.1 (0 = off)
            case "wldiagreset": s.winlink().resetDiag(); Log.i("DiagReceiver", "wldiagreset"); break;   // bench: the first-connection question again, log off
            case "wlconnect": Log.i("DiagReceiver", "wlconnect: " + en(s.winlink().connectInternet())); break;
            case "smsbench": s.sms(i.getIntExtra("type", 0), i.getIntExtra("from", 0), "[bench] " + i.getStringExtra("text")); break;   // bench: a DMR text as if received (0x2D type, sender ID)
            case "smsbenchclear": Log.i("DiagReceiver", "smsbenchclear: " + s.store().deleteBenchMessages()); break;
            case "formtext": s.onFormText(i.getStringExtra("from"), i.getStringExtra("text"), "bench"); break;   // bench: a text as if it arrived (form parts)
            case "testtone": {                                                  // bench: key a 1 kHz tone for --ei ms (≤ 5 s, or ≤ 40 s with --ez lift) on the current channel or VFO
                boolean lift = i.getBooleanExtra("lift", false);
                int ms = Math.max(200, Math.min(lift ? 40000 : 5000, i.getIntExtra("ms", 2000)));
                int hz = Math.max(50, Math.min(3800, i.getIntExtra("hz", 1000))), amp = Math.max(100, Math.min(30000, i.getIntExtra("amp", 12000)));
                Log.i("DiagReceiver", "testtone " + hz + " Hz amp " + amp + " " + ms + " ms" + (lift ? " lift" : "")); s.diagTestTone(ms, lift, hz, amp); break;
            }
            case "txscale": Log.i("DiagReceiver", "txscale: " + s.diagTxScale(i.getFloatExtra("v", 1f))); break;   // bench: the gateway route's sample factor (--ef v)
            case "tonesweep": {                                                 // bench: 100-3500 Hz, 400 ms each with 100 ms gaps (7 s), --ei amp (default 12000)
                int amp = Math.max(100, Math.min(30000, i.getIntExtra("amp", 12000)));
                Log.i("DiagReceiver", "tonesweep amp " + amp); s.diagToneSweep(amp); break;
            }
            case "vfooff": s.diagVfoOff(); break;
            case "audiopath": { String pth = i.getStringExtra("path"); if ("auto".equals(pth) || "vendor".equals(pth) || "direct".equals(pth)) s.diagAudioPath(pth); else Log.w("DiagReceiver", "audiopath: auto | vendor | direct"); break; }   // bench: the receive/transmit audio path
            // bench: load a signed profile list from a file (--es path …), regardless of its serial, to try advisories on this
            // phone without publishing them; "profileclear" removes it again (and the owner's overrides)
            case "profiletest": Log.i("DiagReceiver", "profiletest: " + ProfileRegistry.loadForTest(new java.io.File(i.getStringExtra("path")))); break;
            // bench: the DMR audio check for --ei seconds (default 10) on the current channel; the section goes to logcat, not to a report
            // bench: the update path's root install (older versions included) from a local APK (--es path …); the app restarts as that APK
            case "rootinstall": { final String path = i.getStringExtra("path"); if (path != null && path.endsWith(".apk")) new Thread(() -> Log.i("DiagReceiver", "rootinstall: " + Updater.rootInstall(path)), "rootinstall").start(); break; }
            case "audiocheck": case "dmrcheck": { final int sec = i.getIntExtra("seconds", 10); final Context ctx = c.getApplicationContext();
                new Thread(() -> { try { for (String l : DmrAudioCheck.run(ctx, s, sec, t -> { }).split("\n")) Log.i("DmrAudioCheck", l); } catch (Throwable t) { Log.w("DmrAudioCheck", t); } }, "dmrcheck").start(); break; }
            case "profileclear": Log.i("DiagReceiver", "profileclear: " + ProfileRegistry.clearForTest()); break;
            case "web": if (s.web() != null && i.hasExtra("tx")) s.web().setAllowTx(i.getBooleanExtra("tx", false));   // bench: --ez tx = Device -> SignalDeck Web -> Transmit from the web
                Log.i("DiagReceiver", "web: " + (s.web() == null ? "no host" : String.valueOf(s.web().setEnabled(i.getBooleanExtra("on", true))) + " running=" + s.web().running() + " " + s.web().urls())); break;
            case "aprs144": Log.i("DiagReceiver", "aprs144: " + en(s.aprsMonitor(i.getBooleanExtra("on", true)))); break;      // standard APRS monitoring on/off (receive)
            case "issaprs": {                                                   // ISS APRS: track the next pass and every one after it
                String e = s.startIssMode(com.macdmr.radio.ham.Satellites.APRS);
                if (e == null) s.setIssRepeat(com.macdmr.radio.ham.Satellites.APRS);
                Log.i("DiagReceiver", "issaprs: " + (e == null ? "tracking" : en(e))); break;
            }
            case "isssstv": {                                                   // ISS SSTV: track the next pass and every one after it (an SSTV event)
                String e = s.startIssMode(com.macdmr.radio.ham.Satellites.SSTV);
                if (e == null) s.setIssRepeat(com.macdmr.radio.ham.Satellites.SSTV);
                Log.i("DiagReceiver", "isssstv: " + (e == null ? "tracking" : en(e))); break;
            }
            case "afsk": {                                                      // bench: --ez legacy true = the old single-slicer decoder
                boolean legacy = i.getBooleanExtra("legacy", false);
                com.macdmr.radio.pkt.AFSKDecoder.MULTI_SLICER = !legacy; com.macdmr.radio.pkt.AFSKDecoder.BIT_FIX = !legacy;
                com.macdmr.radio.pkt.AFSKDecoderIQ.PREFILTER = !legacy; com.macdmr.radio.pkt.AFSKDecoderIQ.toneFilterSize = legacy ? 32 : 52;
                Log.i("DiagReceiver", "afsk decoder: " + (legacy ? "legacy" : "new")); break;
            }
            case "text": if (s.packet() != null) {                              // bench: --ei mode 0 off / 1 RTTY / 2 PSK31 (listen + send mode), --es send TEXT, --ez dump (log what was received), --ez clear
                com.macdmr.radio.PacketEngine pe = s.packet();
                if (i.hasExtra("mode")) pe.setTextMode(Math.max(0, Math.min(2, i.getIntExtra("mode", 0))));
                if (i.getBooleanExtra("clear", false)) pe.textClear();
                String t = i.getStringExtra("send"); if (t != null) Log.i("DiagReceiver", "text send: " + en(pe.textSend(t)));
                if (i.getBooleanExtra("dump", false)) for (String l : pe.textReceived().split("\n")) Log.i("DiagReceiver", "text rx: " + l);
                Log.i("DiagReceiver", "text: mode " + pe.textMode() + ", " + pe.textStatusMsg().english()); break;
            } else break;
            case "term": if (s.packet() != null) {                              // bench: packet terminal: --ez accept, --es connect CALL-N, --es send LINE, --ez disconnect, --ez dump (log the transcript)
                com.macdmr.radio.PacketEngine pe = s.packet();
                if (i.hasExtra("accept")) pe.setAcceptConnections(i.getBooleanExtra("accept", false));
                String to = i.getStringExtra("connect"); if (to != null) Log.i("DiagReceiver", "term connect: " + en(pe.terminalConnect(to, i.getStringExtra("via"))));
                String line = i.getStringExtra("send"); if (line != null) Log.i("DiagReceiver", "term send: " + en(pe.terminalSend(line)));
                if (i.getBooleanExtra("disconnect", false)) pe.terminalDisconnect();
                if (i.getBooleanExtra("dump", false)) for (String l : pe.transcriptText().split("\n")) if (!l.isEmpty()) Log.i("DiagReceiver", "term: " + l);
                Log.i("DiagReceiver", "term state: " + pe.terminalStateMsg().english() + ", accept " + pe.acceptConnections()); break;
            } else break;
            case "aprsmsg": if (s.packet() != null) Log.i("DiagReceiver", "aprsmsg: " + en(s.packet().sendMessage(i.getStringExtra("to"), i.getStringExtra("text")))); break;   // bench: an APRS message (--es to CALL-N, --es text), acked and retried as from the UI
            case "dtmfsend": if (s.packet() != null) Log.i("DiagReceiver", "dtmfsend: " + en(s.packet().sendDtmf(i.getStringExtra("seq")))); break;   // bench: send a DTMF sequence (--es seq 0-9*#ABCD)
            case "sstvtx": if (s.packet() != null) {                            // bench: send a colour-bar test card marked N0CALL, --ei mode (SSTVEncoder mode, default Robot 24 colour)
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(320, 240, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bmp); android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                int[] bars = { 0xFFFFFFFF, 0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00, 0xFFFF00FF, 0xFFFF0000, 0xFF0000FF, 0xFF000000 };
                for (int k = 0; k < bars.length; k++) { pt.setColor(bars[k]); cv.drawRect(k * 40, 0, k * 40 + 40, 160, pt); }
                pt.setColor(0xFF202020); cv.drawRect(0, 160, 320, 240, pt); pt.setColor(0xFFFFFFFF); pt.setTextSize(44); cv.drawText("N0CALL", 70, 220, pt);
                Log.i("DiagReceiver", "sstvtx: " + en(s.packet().sendSstv(bmp, i.getIntExtra("mode", com.macdmr.radio.pkt.SSTVEncoder.ROBOT_24_COLOR)))); break;
            } else break;
            case "sstv": {                                                      // bench: --ez on, --ez park (144.500), --ez cont (continuous Robot 36)
                if (s.packet() != null && i.hasExtra("cont")) s.packet().setSstvContinuousMode(i.getBooleanExtra("cont", false));
                Log.i("DiagReceiver", "sstv: " + en(s.sstvMonitor(i.getBooleanExtra("on", false), i.getBooleanExtra("park", true)))); break;
            }
            case "leadtest": if (s.packet() != null) Log.i("DiagReceiver", "leadtest: " + s.packet().diagLeadTest(i.getIntExtra("kind", 0), i.getIntExtra("ms", 1600), i.getIntExtra("reps", 1), String.valueOf(i.getStringExtra("tag")))); break;   // one status packet, current channel
            case "sattest": {                                                   // a fake ISS pass from a minute from now for 30 s (or --el ms), --es key iss-rptr|iss-aprs|iss-sstv; nothing is keyed
                com.macdmr.radio.ham.Sgp4 e = s.satellites().elements().get(25544);
                if (e == null) { Log.w("DiagReceiver", "no ISS elements"); break; }
                long len = i.getLongExtra("len", 30_000);
                com.macdmr.radio.ham.SatTrack.Pass p = new com.macdmr.radio.ham.SatTrack.Pass(); p.aos = System.currentTimeMillis() + 60_000; p.los = p.aos + len; p.tca = p.aos; p.maxEl = 45;
                String key = i.getStringExtra("key"); if (key == null) key = "iss-rptr";
                Log.i("DiagReceiver", "sattest " + key + ": " + en(s.trackSat(com.macdmr.radio.ham.Satellites.byKey(s, key), e, p))); break;
            }
            case "iconpng": try {                                  // the launcher icon as the launcher draws it (adaptive, circle mask), for checking the artwork
                android.graphics.drawable.Drawable d = s.getPackageManager().getApplicationIcon(s.getPackageName());
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(432, 432, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(bm); d.setBounds(0, 0, 432, 432); d.draw(cv);
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(s.getExternalFilesDir(null), "icon.png"))) { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); }
                Log.i("DiagReceiver", "icon saved");
            } catch (Throwable t) { Log.w("DiagReceiver", "iconpng: " + t); } break;
            case "callstart": Log.i("DiagReceiver", "talk call: " + com.macdmr.radio.car.RadioCallService.start(s)); break;   // places the call only: no transmission unless a wheel start is pending
            case "callend": com.macdmr.radio.car.RadioCallService.end(); break;
            case "benchIgate": if (s.packet() != null) s.packet().benchIgate = i.getBooleanExtra("on", false); break;
            case "pkt": if (s.packet() != null) Log.i("DiagReceiver", "pkt " + i.getStringExtra("what") + ": " + en(s.packet().diagSend(i.getStringExtra("what")))); break;
            default: Log.w("DiagReceiver", "unknown cmd " + cmd);
        }
    }

    /** Bench only. Plays a wav on the alarm stream so the handset speaker reaches the microphone. */
    private static void playAlarmWav(java.io.File f) {
        new Thread(() -> {
            try {
                byte[] w = java.nio.file.Files.readAllBytes(f.toPath());
                int rate = (w[24] & 0xFF) | (w[25] & 0xFF) << 8 | (w[26] & 0xFF) << 16 | (w[27] & 0xFF) << 24;
                int data = 12;
                while (data + 8 < w.length && !(w[data] == 'd' && w[data + 1] == 'a' && w[data + 2] == 't' && w[data + 3] == 'a'))
                    data += 8 + ((w[data + 4] & 0xFF) | (w[data + 5] & 0xFF) << 8);
                int n = Math.min(w.length - data - 8, (w[data + 4] & 0xFF) | (w[data + 5] & 0xFF) << 8 | (w[data + 6] & 0xFF) << 16);
                int buf = Math.max(rate, 4096);
                android.media.AudioTrack t = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_ALARM, rate, android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT, buf, android.media.AudioTrack.MODE_STREAM);
                t.play();
                for (int at = 0; at < n; at += buf) t.write(w, data + 8 + at, Math.min(buf, n - at));
                Log.i("DiagReceiver", "m17 mic wav " + n + " bytes at " + rate);
                Thread.sleep(Math.max(1000, n / 2 * 1000L / rate) + 500);
                t.stop();
                t.release();
            } catch (Throwable e) { Log.w("DiagReceiver", "m17 mic wav: " + e); }
        }, "m17-mic-wav").start();
    }
}
