/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio.ui;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.macdmr.radio.RadioService;
import com.macdmr.radio.fw.FirmwareCatalog;
import com.macdmr.radio.fw.FirmwareSession;
import com.macdmr.radio.fw.FirmwareVault;
import com.macdmr.radio.fw.ImageTable;
import com.macdmr.radio.fw.Unlisted;

import java.util.List;
import java.util.Locale;

/** The module firmware page: what is loaded, the pre-flight, the transfer, and recovery on the same screen. */
public final class FirmwareScreen extends LinearLayout {
    private static final String[] PHASES = { "preparing", "update", "handshake", "erasing", "transfer", "finish", "restart", "verify" };
    private static final int[] PHASE_LABELS = { R.string.device_fw_phase_prepare, R.string.device_fw_phase_update, R.string.device_fw_phase_hello, R.string.device_fw_phase_erasing,
            R.string.device_fw_phase_transfer, R.string.device_fw_phase_finish, R.string.device_fw_phase_restart, R.string.device_fw_phase_verify };

    private final RadioService svc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView installed, chosen, backupLine, result, figures, log;
    private final LinearLayout checks, images, confirm;
    private final PhaseBar bar;
    private final TextView load, restore, stop, logToggle, download, allowUnlisted;
    private final EditText hashEntry;
    private ImageTable.Row selected = ImageTable.FACTORY;
    private final java.util.HashSet<String> onPhone = new java.util.HashSet<>();
    private boolean logOpen, running, recovery;
    private int retries;
    private long startedAt, eraseAt, lastAck = 590;
    private final Runnable eraseTick = new Runnable() { @Override public void run() {
        if (!"erasing".equals(bar.phaseName())) return;
        bar.setFill(Math.min(1f, (System.currentTimeMillis() - eraseAt) / 12000f));
        long leftErase = Math.max(0, 12000 - (System.currentTimeMillis() - eraseAt));
        long left = leftErase + 370L * lastAck;
        figures.setText(Txt.s(R.string.device_fw_erasing_left, (leftErase + 999) / 1000));
        ui.postDelayed(this, 200);
    } };

    public FirmwareScreen(Context c, RadioService svc, Runnable onClose) {
        super(c);
        this.svc = svc;
        float d = Theme.dp(c);
        setOrientation(VERTICAL);
        setBackground(Neo.page(c));
        setClickable(true);
        setKeepScreenOn(true);

        LinearLayout header = Neo.header(c, Txt.s(R.string.device_fw_title), svc.profile().name);
        android.widget.ImageView back = Neo.iconButton(c, "left", Neo.CYAN, Txt.s(R.string.common_back));
        back.setOnClickListener(v -> { if (!running) onClose.run(); });
        LayoutParams bl = new LayoutParams(dp(44), dp(44)); bl.rightMargin = dp(12);
        header.addView(back, 0, bl);
        addView(header);

        ScrollView sv = new ScrollView(c); sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(c); body.setOrientation(VERTICAL); body.setPadding(dp(14), dp(8), dp(14), dp(18));
        sv.addView(body);
        addView(sv, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        section(body, Txt.s(R.string.device_fw_risk_heading)).setText(Txt.s(R.string.device_fw_disclaimer));
        installed = section(body, Txt.s(R.string.device_fw_installed));
        chosen = section(body, Txt.s(R.string.device_fw_chosen));
        backupLine = section(body, Txt.s(R.string.device_fw_factory_backup));
        body.addView(Neo.caps(c, Txt.s(R.string.device_fw_images)));
        images = new LinearLayout(c); images.setOrientation(VERTICAL); body.addView(images);
        body.addView(gap());
        body.addView(Neo.caps(c, Txt.s(R.string.device_fw_preflight)));
        checks = new LinearLayout(c); checks.setOrientation(VERTICAL); body.addView(checks);
        body.addView(gap());
        bar = new PhaseBar(c); body.addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(64)));
        figures = Neo.text(c, Txt.s(R.string.device_fw_waiting), Neo.MUTED, 13, Neo.mono(c)); figures.setPadding(0, dp(8), 0, 0); body.addView(figures);
        result = Neo.text(c, "", Neo.TEXT, 16, Neo.ui(c)); result.setPadding(0, dp(8), 0, 0); body.addView(result);

        logToggle = Neo.button(c, Txt.s(R.string.device_fw_handshake_log), "diag", Neo.CYAN);
        body.addView(logToggle, buttonPad());
        log = Neo.text(c, "", Neo.TEXT_SOFT, 12, Neo.mono(c)); log.setVisibility(GONE); body.addView(log);
        logToggle.setOnClickListener(v -> { logOpen = !logOpen; log.setVisibility(logOpen ? VISIBLE : GONE); });

        LinearLayout row = new LinearLayout(c);
        load = Neo.button(c, Txt.s(R.string.device_fw_load), "update", Neo.GREEN);
        restore = Neo.button(c, Txt.s(R.string.device_fw_restore_factory), "restore", Neo.AMBER);
        stop = Neo.button(c, Txt.s(R.string.common_stop), "stop", Neo.RED);
        stop.setEnabled(false);
        row.addView(load, weight()); row.addView(restore, weight()); row.addView(stop, weight());
        body.addView(row);
        confirm = new LinearLayout(c); confirm.setOrientation(VERTICAL); confirm.setVisibility(GONE);
        confirm.setPadding(dp(12), dp(12), dp(12), dp(12)); confirm.setBackground(Neo.card(c, Neo.AMBER, 14));
        body.addView(confirm, buttonPad());

        download = Neo.button(c, Txt.s(R.string.device_fw_download_factory), "update", Neo.AMBER);
        download.setVisibility(GONE);
        download.setOnClickListener(v -> { if (factoryMissing) downloadFactory(); else if (fetchChosen) downloadChosen(); else checkCatalogue(); });
        body.addView(download, buttonPad());

        body.addView(Neo.caps(c, Txt.s(R.string.device_fw_advanced)));
        allowUnlisted = Neo.button(c, Txt.s(R.string.device_fw_unlisted_off), "lock", Neo.AMBER);
        allowUnlisted.setOnClickListener(v -> toggleUnlisted());
        body.addView(allowUnlisted, buttonPad());
        hashEntry = new EditText(c);
        hashEntry.setHint(Txt.s(R.string.device_fw_hash_hint));
        hashEntry.setSingleLine(true);
        hashEntry.setTextColor(Neo.TEXT);
        hashEntry.setHintTextColor(Neo.MUTED);
        hashEntry.setVisibility(GONE);
        body.addView(hashEntry, buttonPad());

        load.setOnClickListener(v -> ask(false));
        restore.setOnClickListener(v -> { selected = ImageTable.FACTORY; showChosen(); ask(false); });
        stop.setOnClickListener(v -> svc.haltFirmware());
        refreshFacts();
    }

    /** The verified factory backup is missing (the download button fetches it); otherwise it fetches the chosen image. */
    private boolean factoryMissing, fetchChosen;

    /** Fetch the signed catalogue again (new released images show up in the list). */
    private void checkCatalogue() {
        if (running) return;
        result.setText(Txt.s(R.string.device_fw_checking_catalogue));
        result.setTextColor(Neo.TEXT);
        new Thread(() -> { String msg = FirmwareCatalog.fetch(getContext()); onUi(() -> { result.setText(msg); refreshFacts(); }); }, "fw-catalog").start();
    }

    /** Fetch the chosen catalogue image (signed row; size, hash and version string checked) so the pre-flight can see it. */
    private void downloadChosen() {
        if (running || selected.factory || selected.unlisted) return;
        final ImageTable.Row want = selected;
        result.setText(Txt.s(R.string.device_fw_downloading, want.tag));
        result.setTextColor(Neo.TEXT);
        new Thread(() -> {
            String msg = FirmwareCatalog.fetch(getContext());
            byte[] got = FirmwareCatalog.download(getContext(), want.tag);
            String line = got != null ? Txt.s(R.string.device_fw_downloaded_checked, want.tag) : FirmwareCatalog.isFetchLine(msg) ? Txt.s(R.string.device_fw_download_failed) : msg;
            onUi(() -> { result.setText(line); result.setTextColor(got != null ? Neo.GREEN_LIGHT : Neo.AMBER_LIGHT); refreshFacts(); });
        }, "fw-download").start();
    }

    private void downloadFactory() {
        if (running) return;
        result.setText(Txt.s(R.string.device_fw_checking_catalogue));
        new Thread(() -> {
            String msg = FirmwareCatalog.fetch(getContext());
            byte[] got = FirmwareCatalog.isFetchLine(msg) ? FirmwareCatalog.download(getContext(), ImageTable.FACTORY.tag) : null;
            String line = got != null ? Txt.s(R.string.device_fw_factory_downloaded) : msg;
            onUi(() -> { result.setText(line); refreshFacts(); });
        }, "fw-catalog").start();
    }

    private void toggleUnlisted() {
        if (running) return;
        if (Unlisted.allowed(getContext())) { Unlisted.allow(getContext(), false); refreshFacts(); return; }
        new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.device_fw_unlisted_q1_title))
                .setMessage(Txt.s(R.string.device_fw_unlisted_q1))
                .setPositiveButton(Txt.s(R.string.common_continue), (d, w) -> new android.app.AlertDialog.Builder(getContext()).setTitle(Txt.s(R.string.device_fw_unlisted_q2_title))
                        .setMessage(Txt.s(R.string.device_fw_unlisted_q2))
                        .setPositiveButton(Txt.s(R.string.device_fw_unlisted_allow), (d2, w2) -> { Unlisted.allow(getContext(), true); refreshFacts(); })
                        .setNegativeButton(Txt.s(R.string.common_cancel), null).show())
                .setNegativeButton(Txt.s(R.string.common_cancel), null).show();
    }

    private void ask(boolean wrongTag) {
        if (running) return;
        if (selected.unlisted && Unlisted.allowed(getContext())) {
            String typed = hashEntry.getText() == null ? "" : hashEntry.getText().toString().trim().toLowerCase(java.util.Locale.US);
            if (typed.length() < 8 || !selected.sha256.startsWith(typed.substring(0, 8))) {
                result.setText(Txt.s(R.string.device_fw_type_hash));
                result.setTextColor(Neo.AMBER_LIGHT);
                return;
            }
        }
        confirm.removeAllViews();
        TextView q = Neo.text(getContext(), Txt.s(R.string.device_fw_confirm, selected.tag),
                Neo.TEXT, 15, Neo.uiMedium(getContext()));
        confirm.addView(q);
        LinearLayout r = new LinearLayout(getContext());
        TextView go = Neo.button(getContext(), Txt.s(R.string.device_fw_load), "update", Neo.GREEN);
        TextView no = Neo.button(getContext(), Txt.s(R.string.common_cancel), "close", Neo.CYAN);
        r.addView(go, weight()); r.addView(no, weight());
        confirm.addView(r);
        confirm.setVisibility(VISIBLE);
        no.setOnClickListener(v -> confirm.setVisibility(GONE));
        go.setOnClickListener(v -> { confirm.setVisibility(GONE); begin(wrongTag); });
    }

    private void begin(boolean wrongTag) {
        running = true; stop.setEnabled(true); retries = 0; startedAt = System.currentTimeMillis(); lastAck = 300;
        result.setText(""); log.setText("");
        bar.setPhase("preparing");
        new Thread(() -> {
            byte[] image = FirmwareVault.bytesFor(getContext(), selected);
            if (image == null && !selected.factory && !selected.unlisted) {        // a catalogue image not on the phone yet: fetch it first
                final String tag = selected.tag;
                onUi(() -> { bar.setPhase("downloading"); log.append(Txt.s(R.string.device_fw_log_downloading, tag) + "\n"); });
                FirmwareCatalog.fetch(getContext());
                image = FirmwareCatalog.download(getContext(), tag);
                if (image != null) onUi(() -> log.append(Txt.s(R.string.device_fw_log_downloaded, tag) + "\n"));
            }
            if (image == null) { onUi(() -> finishUi(selected.factory ? Txt.s(R.string.device_fw_no_factory_copy)
                    : selected.unlisted ? Txt.s(R.string.device_fw_not_in_folder) : Txt.s(R.string.device_fw_download_failed))); return; }
            final byte[] chosenImage = image;
            svc.loadFirmware(selected, chosenImage, new FirmwareSession.Ui() {
                public void phase(String name) { onUi(() -> {
                    bar.setPhase(name);
                    if ("erasing".equals(name)) { eraseAt = System.currentTimeMillis(); ui.removeCallbacks(eraseTick); ui.post(eraseTick); }
                    else ui.removeCallbacks(eraseTick);
                }); }
                public void log(String line) { onUi(() -> log.append(line + "\n")); }
                public void block(int done, int total, long ackMs, int nretry) {
                    onUi(() -> {
                        retries = nretry; lastAck = Math.max(200, ackMs);
                        bar.setFill(total == 0 ? 0 : done / (float) total);
                        long elapsed = System.currentTimeMillis() - startedAt;
                        long bytes = Math.min(selected.size, (long) done * 1024);
                        long left = (long) (total - done) * lastAck;
                        double rate = elapsed > 0 ? bytes / (elapsed / 1000.0) : 0;
                        figures.setText(Txt.s(R.string.device_fw_progress, done, total, String.format(Locale.US, "%,d", bytes), String.format(Locale.US, "%.0f", rate),
                                elapsed / 60000, (elapsed / 1000) % 60, left / 60000, (left / 1000) % 60, retries));
                    });
                }
                public void checks(List<FirmwareSession.Check> list) { onUi(() -> showChecks(list)); }
                public void finished(String line) { onUi(() -> finishUi(line)); }
            }, wrongTag);
        }, "fw-prepare").start();
    }

    private void finishUi(String line) {
        running = false; stop.setEnabled(false);
        result.setText(line == null ? "" : line);
        result.setTextColor(line != null && (line.startsWith("DMR") || FirmwareSession.isRestoredLine(line)) ? Neo.GREEN_LIGHT : Neo.AMBER_LIGHT);   // a module tag, or the factory restore after a wrong tag
        refreshFacts();
    }

    private void refreshFacts() {
        new Thread(() -> {
            FirmwareVault.Copy copy = FirmwareVault.factory(getContext());
            String now = svc.moduleVersion() == null ? "" : svc.moduleVersion().trim();
            java.util.HashSet<String> present = new java.util.HashSet<>();
            byte[] chosenBytes = null;
            for (ImageTable.Row row : FirmwareCatalog.rows(getContext())) {
                byte[] b = FirmwareVault.bytesFor(getContext(), row);
                if (b != null) { present.add(row.tag); if (row.tag.equals(selected.tag) && !selected.unlisted) chosenBytes = b; }
            }
            if (selected.unlisted) chosenBytes = FirmwareVault.bytesFor(getContext(), selected);
            List<FirmwareSession.Check> preview = FirmwareSession.inspect(svc, selected, chosenBytes);
            List<ImageTable.Row> unlistedRows = Unlisted.files(getContext());
            onUi(() -> {
                onPhone.clear(); onPhone.addAll(present);
                if (!running) showChecks(preview);
                ImageTable.Row runningRow = ImageTable.byTag(now, FirmwareCatalog.rows(getContext()));
                installed.setText(now.isEmpty() ? Txt.s(R.string.device_fw_not_answered)
                        : runningRow == null ? Txt.s(R.string.device_fw_unknown_version, now) : now + "\n" + ImageTable.summary(runningRow));
                recovery = now.isEmpty();
                if (recovery) selected = ImageTable.FACTORY;
                backupLine.setText(copy.verified ? Txt.s(R.string.device_fw_backup_verified, copy.source, ImageTable.FACTORY.tag, ImageTable.FACTORY.sha256.substring(0, 8))
                        : copy.sha256.isEmpty() ? Txt.s(R.string.device_fw_backup_unread) : Txt.s(R.string.device_fw_backup_mismatch));
                factoryMissing = !copy.verified;
                fetchChosen = copy.verified && !selected.factory && !selected.unlisted && !present.contains(selected.tag);
                download.setText(factoryMissing ? Txt.s(R.string.device_fw_download_factory) : fetchChosen ? Txt.s(R.string.device_fw_download_tag, selected.tag) : Txt.s(R.string.device_fw_check_new));
                download.setVisibility(running ? GONE : VISIBLE);
                images.removeAllViews();
                for (ImageTable.Row row : FirmwareCatalog.rows(getContext())) {
                    if (recovery && !row.factory) continue;
                    TextView t = Neo.button(getContext(), row.tag, row.factory ? "restore" : "update", row == selected ? Neo.GREEN : Neo.CYAN);
                    t.setOnClickListener(v -> {
                        if (running) return;
                        selected = row; showChosen();
                        if (!row.factory && !onPhone.contains(row.tag) && !factoryMissing) downloadChosen(); else refreshFacts();
                    });
                    images.addView(t, buttonPad());
                }
                allowUnlisted.setText(Unlisted.allowed(getContext()) ? Txt.s(R.string.device_fw_unlisted_on) : Txt.s(R.string.device_fw_unlisted_off));
                if (!recovery) for (ImageTable.Row row : unlistedRows) {
                    TextView t = Neo.button(getContext(), row.tag, "update", row.tag.equals(selected.tag) && selected.unlisted ? Neo.GREEN : Neo.AMBER);
                    t.setOnClickListener(v -> { if (!running) { selected = row; showChosen(); refreshFacts(); } });
                    images.addView(t, buttonPad());
                }
                showChosen();
            });
        }, "fw-facts").start();
    }

    private void showChosen() {
        boolean here = onPhone.contains(selected.tag) || selected.unlisted;
        chosen.setText(selected.tag + "\n" + ImageTable.summary(selected) + "\n" + Txt.s(here ? R.string.device_fw_chosen_here : R.string.device_fw_chosen_missing,
                String.format(Locale.US, "%,d", selected.size), selected.sha256));
        hashEntry.setVisibility(selected.unlisted && Unlisted.allowed(getContext()) ? VISIBLE : GONE);
    }

    private void showChecks(List<FirmwareSession.Check> list) {
        checks.removeAllViews();
        for (FirmwareSession.Check c : list) {
            TextView t = Neo.text(getContext(), "● " + c.name + " — " + c.detail, !c.ok ? Neo.RED : c.warn ? Neo.AMBER_LIGHT : Neo.GREEN_LIGHT, 14, Neo.uiMedium(getContext()));
            t.setPadding(0, dp(3), 0, 0);
            checks.addView(t);
        }
    }

    // The owner's disclaimer for loading module firmware (2026-10-30), shown on the page: R.string.device_fw_disclaimer.

    private TextView section(LinearLayout body, String title) {
        body.addView(Neo.caps(getContext(), title));
        TextView t = Neo.text(getContext(), "…", Neo.TEXT_SOFT, 14, Neo.uiMedium(getContext()));
        t.setPadding(0, dp(4), 0, dp(10));
        body.addView(t);
        return t;
    }

    private LayoutParams buttonPad() {
        LayoutParams p = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); p.topMargin = dp(8); return p;
    }
    private LayoutParams weight() { LayoutParams p = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); p.setMargins(dp(3), dp(8), dp(3), 0); return p; }
    private View gap() { View v = new View(getContext()); v.setLayoutParams(new LayoutParams(1, dp(8))); return v; }
    private int dp(int n) { return Theme.dp(getContext(), n); }
    private void onUi(Runnable r) { ui.post(r); }

    /** The seven phases as one bar. The active phase is filled; earlier phases stay lit. */
    private static final class PhaseBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int phase;
        private float part = 1f;
        private String name = "preparing";

        PhaseBar(Context c) {
            super(c);
            track.setColor(Neo.TRACK); fill.setColor(Neo.CYAN); label.setColor(Neo.MUTED);
            label.setTextSize(Theme.dp(c, 8)); label.setTextAlign(Paint.Align.CENTER); label.setTypeface(Neo.ui(c));
        }

        String phaseName() { return name; }

        void setPhase(String next) {
            name = next;
            for (int i = 0; i < PHASES.length; i++) if (PHASES[i].equals(next)) phase = i;
            part = "transfer".equals(next) || "erasing".equals(next) ? 0f : 1f;
            invalidate();
        }

        void setFill(float fraction) { part = Math.max(0f, Math.min(1f, fraction)); invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            float d = Theme.dp(getContext());
            float y = d * 16, h = d * 6, gap = d * 2;
            float w = (getWidth() - gap * (PHASES.length - 1)) / PHASES.length;
            for (int i = 0; i < PHASES.length; i++) {
                float x = i * (w + gap);
                canvas.drawRoundRect(x, y, x + w, y + h, h, h, track);
                float lit = i < phase ? 1f : i == phase ? part : 0f;
                if (lit > 0f) canvas.drawRoundRect(x, y, x + w * lit, y + h, h, h, fill);
                label.setColor(i == phase ? Neo.CYAN_LIGHT : Neo.MUTED);
                canvas.drawText(Txt.s(PHASE_LABELS[i]), x + w / 2f, y + h + d * 12, label);
            }
        }
    }
}
