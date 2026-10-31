/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.pkt;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Msg;
import com.macdmr.radio.i18n.Txt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;



/**
 * NOAA Weather Radio / EAS "SAME" (Specific Area Message Encoding) decoder.
 *
 * SAME is 520.83 baud AFSK: mark 2083.3 Hz (1), space 1562.5 Hz (0), bytes LSB-first, no start/stop
 * bits. A burst is 16 × 0xAB preamble then ASCII
 * {@code ZCZC-ORG-EEE-PSSCCC(-PSSCCC…)+TTTT-JJJHHMM-LLLLLLLL-}, sent three times one second apart,
 * followed (for real alerts) by an 8–10 s 1050 Hz attention tone and the voice message, and closed by
 * three {@code NNNN} end-of-message bursts.
 *
 * Input: the OEM RX stream (8 kHz, stereo L == R → one sample per 4 bytes). Demodulation is a
 * one-bit-long I/Q correlator per tone; the bit clock (15.36 samples/bit) is nudged toward each
 * mark/space transition. Bytes are assembled on every bit (24-bit window) until the preamble aligns
 * the byte boundary; the message is then collected until it ends or the carrier stops.
 */
public class SAMEDecoder {
    private static final String TAG = "DMRModHooks-SAME";

    public static final double BAUD = 520.8333;
    public static final double F_MARK = 2083.3333, F_SPACE = 1562.5, F_ATTN = 1050.0;

    /** A decoded header (ZCZC) or end-of-message (NNNN). */
    public static class Message {
        public final String raw;          // full ASCII message as received
        public final boolean eom;         // NNNN
        public String originator = "", event = "", eventName = "", sender = "";
        public List<String> areas = new ArrayList<String>();   // PSSCCC codes
        public int durationMin = 0;       // from +TTTT (HHMM)
        public String issued = "";        // JJJHHMM
        public final long receivedMs = System.currentTimeMillis();
        Message(String raw, boolean eom) { this.raw = raw; this.eom = eom; }

        /** One line for the alert list and the history, in English (stored with the alert). */
        public String summary() { return summaryMsg().english(); }

        /** The same line as a message: "Tornado Warning — 1h00 — 3 areas — KXYZ/NWS". */
        public Msg summaryMsg() {
            if (eom) return Msg.of(R.string.packet_same_end_of_message);
            Object name = eventName.isEmpty() ? event : eventArg(event);
            Msg areasN = Msg.plural(R.plurals.packet_same_areas, areas.size(), areas.size());
            Msg dur = Msg.of(R.string.packet_same_duration, durationMin / 60, String.format(Locale.US, "%02d", durationMin % 60));
            String snd = sender.trim();
            if (durationMin > 0) return sender.isEmpty() ? Msg.of(R.string.packet_same_summary_dur, name, dur, areasN) : Msg.of(R.string.packet_same_summary_dur_sender, name, dur, areasN, snd);
            return sender.isEmpty() ? Msg.of(R.string.packet_same_summary, name, areasN) : Msg.of(R.string.packet_same_summary_sender, name, areasN, snd);
        }
    }

    public interface Listener {
        void onMessage(Message m);
        void onAttentionTone(boolean on);
    }

    private final Listener listener;
    private final int bytesPerSample;
    private final double fs;

    // demod state
    private final double spb;             // samples per bit
    private final int win;                // correlator window (one bit)
    private final double[] hist;          // input history for the correlators
    private int hpos = 0;
    private double phM = 0, phS = 0, phA = 0;       // NCO phases
    private final double dM, dS, dA;
    private double mi, mq, si, sq;                  // running correlator sums
    private final double[] cM, sM, cS, sS;          // per-history-slot mixed values
    private boolean lastBit = false;
    private double clock = 0;                       // samples until next bit sample
    private long nSamples = 0;
    private int lastEdgeAge = 0;

    // bit/byte assembly
    private int bitWindow = 0;            // last 24 bits (LSB = oldest? no: newest shifted in at bit 23)
    private int bitCount = 0;             // bits since alignment (mod 8)
    private boolean aligned = false;
    private int preambleRun = 0;
    private final StringBuilder msg = new StringBuilder();
    private int idleBits = 0;
    private String lastHeader = null; private long lastHeaderMs = 0;

    // attention tone
    private double attnAcc = 0; private int attnN = 0; private int attnOn = 0; private boolean attnState = false;
    private double levelAcc = 0;

    public SAMEDecoder(int sampleRate, boolean stereoDuplicated, Listener l) {
        this.fs = sampleRate; this.bytesPerSample = stereoDuplicated ? 4 : 2; this.listener = l;
        spb = fs / BAUD; win = (int) Math.round(spb);
        hist = new double[win]; cM = new double[win]; sM = new double[win]; cS = new double[win]; sS = new double[win];
        dM = 2 * Math.PI * F_MARK / fs; dS = 2 * Math.PI * F_SPACE / fs; dA = 2 * Math.PI * F_ATTN / fs;
        clock = spb / 2;
    }

    public synchronized void reset() {
        java.util.Arrays.fill(hist, 0); java.util.Arrays.fill(cM, 0); java.util.Arrays.fill(sM, 0);
        java.util.Arrays.fill(cS, 0); java.util.Arrays.fill(sS, 0);
        mi = mq = si = sq = 0; hpos = 0; clock = spb / 2; aligned = false; preambleRun = 0; bitWindow = 0; bitCount = 0;
        msg.setLength(0); idleBits = 0; attnAcc = 0; attnN = 0; attnOn = 0; attnState = false;
    }

    /** Feed raw PCM (16-bit LE). */
    public synchronized void processAudio(byte[] data, int length) {
        for (int i = 0; i + 1 < length; i += bytesPerSample) {
            short raw = (short) (((data[i + 1] & 0xFF) << 8) | (data[i] & 0xFF));
            sample(raw / 32768.0);
        }
    }

    private void sample(double x) {
        // ── mark/space one-bit correlators (running sums over the last `win` samples) ──
        double cm = x * Math.cos(phM), sm = x * Math.sin(phM), cs = x * Math.cos(phS), ss = x * Math.sin(phS);
        phM += dM; if (phM > 2 * Math.PI) phM -= 2 * Math.PI;
        phS += dS; if (phS > 2 * Math.PI) phS -= 2 * Math.PI;
        mi += cm - cM[hpos]; mq += sm - sM[hpos]; si += cs - cS[hpos]; sq += ss - sS[hpos];
        cM[hpos] = cm; sM[hpos] = sm; cS[hpos] = cs; sS[hpos] = ss;
        hpos = (hpos + 1 == win) ? 0 : hpos + 1;
        double eM = mi * mi + mq * mq, eS = si * si + sq * sq;
        boolean bit = eM > eS;
        levelAcc = 0.999 * levelAcc + 0.001 * (eM + eS);

        // ── bit clock: nudge toward transitions ──
        if (bit != lastBit) {
            // a transition should sit half a bit before the sampling instant
            double err = clock - spb / 2;
            clock -= 0.25 * err;
            lastBit = bit;
        }
        clock -= 1;
        if (clock <= 0) {
            clock += spb;
            onBit(bit);
        }

        // ── 1050 Hz attention tone (100 ms Goertzel-ish via I/Q) ──
        attnAcc += x * Math.cos(phA); phA += dA; if (phA > 2 * Math.PI) phA -= 2 * Math.PI;
        if (++attnN >= (int) (fs / 10)) {
            double a = Math.abs(attnAcc) / attnN;          // ~amplitude/2 of a 1050 Hz component
            attnAcc = 0; attnN = 0;
            boolean strong = a > 0.05;
            attnOn = strong ? Math.min(attnOn + 1, 100) : Math.max(attnOn - 2, 0);
            boolean st = attnOn >= 8;                       // ≥ 0.8 s sustained
            if (st != attnState) { attnState = st; if (listener != null) listener.onAttentionTone(st); }
        }
    }

    private void onBit(boolean bit) {
        bitWindow = ((bitWindow << 1) | (bit ? 1 : 0)) & 0xFFFFFF;
        // bytes are LSB-first on the air: the byte formed by the last 8 bits, oldest bit = LSB
        int b = reverse8(bitWindow & 0xFF);
        if (!aligned) {
            // look for the 0xAB preamble; two consecutive aligned 0xAB bytes (16 bits apart) lock the boundary
            if (b == 0xAB && reverse8((bitWindow >> 8) & 0xFF) == 0xAB && reverse8((bitWindow >> 16) & 0xFF) == 0xAB) {
                aligned = true; bitCount = 0; msg.setLength(0); preambleRun = 3; idleBits = 0;
            }
            return;
        }
        if (++bitCount < 8) return;
        bitCount = 0;
        if (b == 0xAB && msg.length() == 0) { preambleRun++; return; }
        char c = (char) b;
        if (c >= 0x20 && c < 0x7F) {
            msg.append(c); idleBits = 0;
            String m = msg.toString();
            if (m.length() >= 4 && m.startsWith("NNNN")) { emit(m, true); aligned = false; msg.setLength(0); return; }
            if (m.length() > 4 && !m.startsWith("ZCZC")) { aligned = false; msg.setLength(0); return; }
            if (m.startsWith("ZCZC") && m.length() > 8) {
                // complete when the sender field (8 chars after the last '-' following '+') is closed by '-'
                int plus = m.indexOf('+');
                if (plus > 0) {
                    int t = m.indexOf('-', plus);          // after +TTTT
                    int j = t > 0 ? m.indexOf('-', t + 1) : -1;   // after JJJHHMM
                    if (j > 0 && m.length() >= j + 10 && m.charAt(j + 9) == '-') { emit(m.substring(0, j + 10), false); aligned = false; msg.setLength(0); }
                }
            }
            if (m.length() > 268) { aligned = false; msg.setLength(0); }
        } else {
            // non-printable: the burst ended (or noise); if we hold a plausible partial header, drop it
            aligned = false; msg.setLength(0);
        }
    }

    private void emit(String m, boolean eom) {
        if (eom) {
            if (lastHeader != null && System.currentTimeMillis() - lastHeaderMs < 3000 && lastHeader.equals("NNNN")) return;
            lastHeader = "NNNN"; lastHeaderMs = System.currentTimeMillis();
            Message msg = new Message("NNNN", true);
            android.util.Log.d("pkt", TAG + ": EOM");
            if (listener != null) listener.onMessage(msg);
            return;
        }
        // the header is sent 3× — report the first good copy only
        if (m.equals(lastHeader) && System.currentTimeMillis() - lastHeaderMs < 5000) return;
        lastHeader = m; lastHeaderMs = System.currentTimeMillis();
        Message msg = parse(m);
        android.util.Log.d("pkt", TAG + ": header " + m + " -> " + msg.summary());
        if (listener != null) listener.onMessage(msg);
    }

    /** Parse {@code ZCZC-ORG-EEE-PSSCCC-…+TTTT-JJJHHMM-LLLLLLLL-}. */
    public static Message parse(String m) {
        Message msg = new Message(m, false);
        try {
            int plus = m.indexOf('+');
            String head = plus > 0 ? m.substring(0, plus) : m;
            String[] f = head.split("-");
            if (f.length >= 3) { msg.originator = f[1]; msg.event = f[2]; }
            for (int i = 3; i < f.length; i++) if (f[i].length() == 6) msg.areas.add(f[i]);
            if (plus > 0) {
                String[] t = m.substring(plus + 1).split("-");
                if (t.length >= 1 && t[0].length() == 4) msg.durationMin = Integer.parseInt(t[0].substring(0, 2)) * 60 + Integer.parseInt(t[0].substring(2));
                if (t.length >= 2) msg.issued = t[1];
                if (t.length >= 3) msg.sender = t[2];
            }
            msg.eventName = eventName(msg.event);
        } catch (Exception e) {
            android.util.Log.d("pkt", TAG + ": parse error: " + e.getMessage());
        }
        return msg;
    }

    private static int reverse8(int b) {
        int r = 0; for (int i = 0; i < 8; i++) { r = (r << 1) | (b & 1); b >>= 1; } return r;
    }

    /** SAME event codes and their display names (the codes stay as sent; the names are translated). */
    private static final Map<String, Integer> EVENTS = new HashMap<String, Integer>();
    static {
        Object[][] e = {
            {"TOR", R.string.packet_same_tor},{"TOA", R.string.packet_same_toa},{"SVR", R.string.packet_same_svr},{"SVA", R.string.packet_same_sva},
            {"SVS", R.string.packet_same_svs},{"FFW", R.string.packet_same_ffw},{"FFA", R.string.packet_same_ffa},{"FFS", R.string.packet_same_ffs},
            {"FLW", R.string.packet_same_flw},{"FLA", R.string.packet_same_fla},{"FLS", R.string.packet_same_fls},{"WSW", R.string.packet_same_wsw},
            {"WSA", R.string.packet_same_wsa},{"BZW", R.string.packet_same_bzw},{"HWW", R.string.packet_same_hww},{"HWA", R.string.packet_same_hwa},
            {"SMW", R.string.packet_same_smw},{"SPS", R.string.packet_same_sps},{"HUW", R.string.packet_same_huw},{"HUA", R.string.packet_same_hua},
            {"HLS", R.string.packet_same_hls},{"TRW", R.string.packet_same_trw},{"TRA", R.string.packet_same_tra},{"TSW", R.string.packet_same_tsw},
            {"TSA", R.string.packet_same_tsa},{"SSW", R.string.packet_same_ssw},{"SSA", R.string.packet_same_ssa},{"EWW", R.string.packet_same_eww},
            {"DSW", R.string.packet_same_dsw},{"FRW", R.string.packet_same_frw},{"FZW", R.string.packet_same_fzw},{"HMW", R.string.packet_same_hmw},
            {"LEW", R.string.packet_same_lew},{"LAE", R.string.packet_same_lae},{"NUW", R.string.packet_same_nuw},{"RHW", R.string.packet_same_rhw},
            {"SPW", R.string.packet_same_spw},{"VOW", R.string.packet_same_vow},{"CAE", R.string.packet_same_cae},{"CDW", R.string.packet_same_cdw},
            {"CEM", R.string.packet_same_cem},{"EVI", R.string.packet_same_evi},{"EQW", R.string.packet_same_eqw},{"AVW", R.string.packet_same_avw},
            {"AVA", R.string.packet_same_ava},{"CFW", R.string.packet_same_cfw},{"CFA", R.string.packet_same_cfa},{"EAN", R.string.packet_same_ean},
            {"EAT", R.string.packet_same_eat},{"NIC", R.string.packet_same_nic},{"NPT", R.string.packet_same_npt},{"RMT", R.string.packet_same_rmt},
            {"RWT", R.string.packet_same_rwt},{"ADR", R.string.packet_same_adr},{"DMO", R.string.packet_same_dmo},{"BHW", R.string.packet_same_bhw},
            {"BWW", R.string.packet_same_bww},{"CHW", R.string.packet_same_chw},{"TOE", R.string.packet_same_toe},{"WFW", R.string.packet_same_wfw},
            {"WFA", R.string.packet_same_wfa},
        };
        for (Object[] p : e) EVENTS.put((String) p[0], (Integer) p[1]);
    }

    /** The event's name in English (stored with the alert), or the code when it is not a known one. */
    public static String eventName(String code) {
        Integer n = EVENTS.get(code);
        return n != null ? Txt.en(n) : (code == null ? "" : code);
    }

    /** The event's name in the app's language, or the code when it is not a known one. */
    public static String eventTitle(String code) {
        Integer n = EVENTS.get(code);
        return n != null ? Txt.s(n) : (code == null ? "" : code);
    }

    /** The event's name as a message argument (the code itself when it is not a known one). */
    static Object eventArg(String code) {
        Integer n = EVENTS.get(code);
        return n != null ? Msg.of(n) : (code == null ? "" : code);
    }
}
