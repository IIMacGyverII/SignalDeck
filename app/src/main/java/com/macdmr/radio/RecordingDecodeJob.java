/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import com.macdmr.radio.i18n.Msg;
import com.macdmr.radio.i18n.Txt;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Environment;

import com.macdmr.radio.pkt.APRSPacketDecoder;
import com.macdmr.radio.pkt.Ax25;
import com.macdmr.radio.pkt.SSTVImageDecoderIQ;
import com.macdmr.radio.pkt.SSTVMode;
import com.macdmr.radio.pkt.SSTVVISDetector;
import com.macdmr.radio.pkt.SSTVVISResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Decode a recording on the phone (SSTV page, APRS page, PKT menu, SignalDeck Web): one job at a time, in the
 * background, with progress that the screens poll. Recordings: SignalDeck's own (pass recordings, REC) and uploads
 * (Download/DMR/Audio/Uploaded), in WAV, MP3, M4A/AAC, OGG/Opus or FLAC. SSTV finds every mode SignalDeck receives from
 * its VIS header (Robot, Martin, Scottie, PD…) and, where no header was heard (a fade on a pass), Robot 36 pictures with
 * the continuous receiver; a picture found both ways is kept once. Pictures go to Download/DMR/SSTV like live ones,
 * named after the time they were on the air ("…_rec.png"); APRS packets are listed and written next to the recording
 * as "<name>.aprs.txt". Nothing is ever transmitted, digipeated or gated.
 */
public final class RecordingDecodeJob {
    public static final String SSTV = "sstv", APRS = "aprs";
    public static final String UPLOADED = "Uploaded";   // i18n: folder name
    static final String[] AUDIO = { ".wav", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".3gp", ".amr" };

    public final String mode; public final File file;
    public volatile int percent; public volatile boolean done; public volatile String error, stage = "";
    public final List<String> pictures = Collections.synchronizedList(new ArrayList<>());   // saved file names
    public final List<String> packets = Collections.synchronizedList(new ArrayList<>());    // "12:05  CALL>DEST: description"
    public volatile String summary;
    public final long startedAt = System.currentTimeMillis();
    private final RecordingDecoder dec = new RecordingDecoder();

    private static volatile RecordingDecodeJob current;
    public static RecordingDecodeJob current() { return current; }

    private RecordingDecodeJob(String mode, File file) { this.mode = mode; this.file = file; }

    static boolean isAudio(String name) { String n = name.toLowerCase(Locale.US); for (String e : AUDIO) if (n.endsWith(e)) return true; return false; }
    static File audioRoot() { return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DMR/Audio"); }

    /** The recordings that can be decoded, newest first: pass recordings, REC and uploads. */
    public static List<File> recordings(int max) {
        List<File> out = new ArrayList<>();
        File[] dirs = audioRoot().listFiles(); if (dirs == null) return out;
        for (File d : dirs) { File[] fs = d.isDirectory() ? d.listFiles() : null; if (fs != null) for (File f : fs) if (isAudio(f.getName()) && f.length() > 44) out.add(f); }
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
    /** A recording by its folder and name (SignalDeck Web), or null if it is not one of ours. */
    public static File recording(String folder, String name) {
        if (folder == null || name == null || folder.contains("..") || name.contains("..") || folder.contains("/") || name.contains("/") || name.contains("\\")) return null;
        File f = new File(new File(audioRoot(), folder), name);
        return f.isFile() && isAudio(name) ? f : null;
    }
    /** Keep an uploaded recording in Download/DMR/Audio/Uploaded (so it is listed with the others) and return it. */
    public static File saveUpload(String name, java.io.InputStream in) throws IOException {
        String clean = name == null ? "" : name.replaceAll(".*[/\\\\]", "").replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (clean.isEmpty() || !isAudio(clean)) throw new IOException("Upload an audio file: WAV, MP3, M4A, AAC, OGG, Opus or FLAC");
        File dir = new File(audioRoot(), UPLOADED); if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        int dot = clean.lastIndexOf('.'); String base = clean.substring(0, dot), ext = clean.substring(dot);
        File f = new File(dir, clean); for (int k = 2; f.exists(); k++) f = new File(dir, base + "_" + k + ext);
        try (FileOutputStream o = new FileOutputStream(f)) { byte[] b = new byte[65536]; int n; long total = 0; while ((n = in.read(b)) > 0) { o.write(b, 0, n); total += n; if (total > 200L * 1024 * 1024) throw new IOException("the file is larger than 200 MB"); } }
        catch (IOException e) { f.delete(); throw e; }
        return f;
    }

    /** Start decoding (null), or why not. */
    public static synchronized String start(String mode, File f, RadioService svc) {
        RecordingDecodeJob j = current;
        if (j != null && !j.done) return Txt.s(R.string.modes_rec_err_busy, j.file.getName());
        if (f == null || !f.isFile()) return Txt.s(R.string.modes_rec_err_missing);
        if (!SSTV.equals(mode) && !APRS.equals(mode)) return Txt.s(R.string.modes_rec_err_mode);
        final RecordingDecodeJob job = new RecordingDecodeJob(mode, f); current = job;
        Thread t = new Thread(() -> job.run(svc), "decode-recording"); t.setPriority(Thread.MIN_PRIORITY); t.start();
        return null;
    }
    public void cancel() { dec.cancel(); }

    private void run(RadioService svc) {
        try {
            stage = Txt.s(R.string.modes_rec_stage_reading); Msg sum;
            short[] s = load8k(file);
            long fromName = RecordingDecoder.startFromName(file.getName());
            final long start = fromName > 0 ? fromName : file.lastModified() - s.length * 1000L / RecordingDecoder.SR;
            String len = RecordingDecoder.clock(s.length * 1000L / RecordingDecoder.SR);
            if (APRS.equals(mode)) {
                stage = Txt.s(R.string.modes_rec_stage_aprs);
                int n = dec.aprs(s, new RecordingDecoder.Listener() {
                    @Override public void progress(int p) { percent = p; }
                    @Override public void packet(byte[] frame, long atMs) { packets.add(describe(frame, atMs, start)); }
                });
                if (dec.cancelled()) { summary = Txt.s(R.string.modes_rec_stopped); return; }
                if (n > 0) try { Files.write(new File(file.getParentFile(), file.getName() + ".aprs.txt").toPath(), (String.join("\n", packets) + "\n").getBytes(StandardCharsets.UTF_8)); } catch (Throwable ignored) { }
                sum = n == 0 ? Msg.of(R.string.modes_rec_aprs_none, len) : Msg.plural(R.plurals.modes_rec_aprs_found, n, n, len);
            } else {
                stage = Txt.s(R.string.modes_rec_stage_sstv);
                List<long[]> spans = new ArrayList<>();                           // {startMs, endMs} of the pictures found by their header
                int any = anyMode(s, start, spans);
                if (dec.cancelled()) { summary = Txt.s(R.string.modes_rec_stopped); return; }
                stage = Txt.s(R.string.modes_rec_stage_robot);
                final int[] extra = { 0 };
                dec.sstv(s, new RecordingDecoder.Listener() {
                    @Override public void progress(int p) { percent = 50 + p / 2; }
                    @Override public void picture(int[] argb, int w, int h, int lines, boolean complete, long atMs) {
                        long from = atMs - lines * 150L;                          // Robot 36: 150 ms a line
                        for (long[] sp : spans) if (from < sp[1] && atMs > sp[0]) return;   // already saved from its header
                        String n = save(Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888), "Robot36", complete, start + atMs); if (n != null) { pictures.add(n); extra[0]++; }   // i18n: file name part
                    }
                });
                if (dec.cancelled()) { summary = Txt.s(R.string.modes_rec_stopped); return; }
                int n = any + extra[0]; long whole = 0; for (String p : pictures) if (!p.contains("_partial")) whole++;
                sum = n == 0 ? Msg.of(R.string.modes_rec_sstv_none, len) : Msg.plural(R.plurals.modes_rec_sstv_found, n, n, (int) whole, len);
            }
            summary = sum.text(); percent = 100;
            if (svc != null) svc.history("pkt", Msg.of(R.string.modes_rec_event_decoded, file.getName(), sum));
        } catch (Throwable t) { error = t.getMessage() == null ? t.toString() : t.getMessage(); }
        finally { stage = ""; done = true; }
    }

    /**
     * Every SSTV mode SignalDeck knows, found by its VIS header: the header scan runs on 2 s windows every 0.5 s, then
     * the mode's own decoder takes the picture in sample time (sync lost for three lines = the picture ended; fewer
     * than 12 lines = a false header). The receivers run at 16 kHz with each 8 kHz sample twice, as live reception does.
     */
    private int anyMode(short[] s8, long start, List<long[]> spans) {
        final int R = 16000; short[] x = new short[s8.length * 2];
        for (int i = 0; i < s8.length; i++) x[2 * i] = x[2 * i + 1] = s8[i];
        SSTVVISDetector vd = new SSTVVISDetector(R);
        int win = 2 * R, step = R / 2, count = 0; short[] w = new short[win];
        for (int pos = 0; pos + win <= x.length && !dec.cancelled(); ) {
            percent = (int) (50L * pos / x.length);
            System.arraycopy(x, pos, w, 0, win);
            SSTVVISResult r = vd.detectVIS(w);
            SSTVMode.Mode m = r == null || r.visCode == SSTVMode.MODE_UNKNOWN ? null : SSTVMode.getModeByVIS(r.visCode);
            if (m != null) {
                int img = pos + r.imageStartSample;
                SSTVImageDecoderIQ d = new SSTVImageDecoderIQ(m, R); d.initStreaming();
                int lineSamples = m.fullLineSamples(R), lastLine = img, chunk = 1024, at = Math.max(0, img - R);
                while (at < x.length && !d.isComplete() && !dec.cancelled()) {
                    int n = Math.min(chunk, x.length - at); short[] c = Arrays.copyOfRange(x, at, at + n);
                    int before = d.getProcessedLines(); d.processChunk(c); at += n;
                    if (d.getProcessedLines() > before) lastLine = at;
                    if (at - lastLine > (d.getProcessedLines() == 0 ? 3.5 : 3) * lineSamples) break;
                }
                d.finalizeStreaming();
                int lines = d.getProcessedLines();
                if (lines >= 12) {
                    long fromMs = img * 1000L / R, toMs = lastLine * 1000L / R;
                    boolean whole = d.isComplete() || lines >= d.getTotalLines() - 4;          // the last line has no sync after it: a finished picture often stops 1-2 lines short
                    String n = save(d.getCurrentBitmap(), m.name.replaceAll("[^A-Za-z0-9]", ""), whole, start + toMs);
                    if (n != null) { pictures.add(n); count++; spans.add(new long[] { fromMs, toMs }); }
                    pos = Math.max(pos + step, lastLine - R);                     // the next header comes after this picture
                    continue;
                }
            }
            pos += step;
        }
        return count;
    }

    /** Any audio file as 8 kHz mono: WAV directly, other formats through Android's own decoders. */
    static short[] load8k(File f) throws IOException {
        byte[] head = new byte[12];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { if (in.read(head) < 12) throw new IOException("the file is empty"); }
        if (new String(head, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(head, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) {
            try { return RecordingDecoder.read8k(Files.readAllBytes(f.toPath())); }
            catch (IOException e) { if (!e.getMessage().contains("16-bit")) throw e; }     // a float / 24-bit WAV: let Android decode it
        }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            int track = -1; MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) { MediaFormat t = ex.getTrackFormat(i); String mime = t.getString(MediaFormat.KEY_MIME); if (mime != null && mime.startsWith("audio/")) { track = i; fmt = t; break; } }
            if (track < 0) throw new IOException("no audio in the file");
            ex.selectTrack(track);
            MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0); codec.start();
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            ByteArrayOutputStream mono = new ByteArrayOutputStream(); MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            while (!outDone) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ii); int n = ex.readSampleData(b, 0);
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { MediaFormat of = codec.getOutputFormat(); rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT); }
                else if (oi >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(oi); b.position(info.offset); b.limit(info.offset + info.size);
                    java.nio.ShortBuffer sb = b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    while (sb.remaining() >= ch) { int sum = 0; for (int c = 0; c < ch; c++) sum += sb.get(); short v = (short) (sum / ch); mono.write(v & 0xFF); mono.write((v >> 8) & 0xFF); }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    if (mono.size() > 400L * 1024 * 1024) throw new IOException("the recording is too long");
                }
            }
            codec.stop(); codec.release();
            byte[] raw = mono.toByteArray(); short[] pcm = new short[raw.length / 2];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            if (rate < 8000) throw new IOException("the sample rate is below 8 kHz");
            return RecordingDecoder.to8k(pcm, rate);
        } catch (IOException e) { throw e; }
        catch (Throwable t) { throw new IOException("this audio format cannot be read on this phone (" + t.getMessage() + ")"); }
        finally { ex.release(); }
    }

    private static String save(Bitmap b, String modeName, boolean complete, long when) {
        if (b == null) return null;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DMR/SSTV"); dir.mkdirs();
            String base = "SSTV_" + modeName + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(when)) + "_rec" + (complete ? "" : "_partial");
            File f = new File(dir, base + ".png"); for (int k = 2; f.exists(); k++) f = new File(dir, base + "_" + k + ".png");   // decoding twice keeps both
            try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
            f.setLastModified(when);                                                // sorts with the time it was received
            return f.getName();
        } catch (Throwable t) { return null; }
    }

    static String describe(byte[] frame, long atMs, long start) {
        String clock = RecordingDecoder.clock(atMs) + "  " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(start + atMs));
        try {
            APRSPacketDecoder.APRSPacket p = APRSPacketDecoder.decode(frame);
            String d = p.isValid ? APRSPacketDecoder.describe(p) : null;
            return clock + "  " + Ax25.tnc2(frame) + (d == null || d.trim().isEmpty() ? "" : "  — " + d.trim());
        } catch (Throwable t) { return clock + "  " + Arrays.toString(frame); }
    }
}
