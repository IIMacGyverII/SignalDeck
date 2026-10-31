/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;
// i18n: stays English (serial and module diagnostics: log lines, shell commands and device-report text)

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One long-lived root shell for small commands that run often (the W6's speaker and receive lines at every reception).
 * Magisk shows its "granted Superuser rights" toast for every su session, so {@link RootSetup#run} (one su per
 * command) toasted at each reception; this opens su once and feeds it commands, each followed by an end marker.
 * Same su flags as {@link RootSetup#run}; if the shell cannot be started or dies twice, the command falls back to it.
 */
public final class RootShell {
    private Process proc;
    private OutputStream in;
    private BufferedReader out;
    private int seq;

    /** Run {@code cmd} as root; returns its output (stdout and stderr), trimmed. */
    public synchronized String run(String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (proc == null) start();
                String end = "__signaldeck_end_" + (++seq) + "__";
                in.write((cmd + "\necho " + end + "\n").getBytes(StandardCharsets.UTF_8)); in.flush();
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = out.readLine()) != null) {
                    if (line.equals(end)) return sb.toString().trim();
                    sb.append(line).append('\n');
                }
                close();                                                   // end of stream: the shell is gone, start a new one
            } catch (IOException e) { close(); }
        }
        return RootSetup.run(cmd);
    }

    private void start() throws IOException {
        proc = new ProcessBuilder("su", "--mount-master", "--context", "u:r:magisk:s0").redirectErrorStream(true).start();
        in = proc.getOutputStream();
        out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
    }

    public synchronized void close() {
        if (proc != null) { try { in.write("exit\n".getBytes(StandardCharsets.UTF_8)); in.flush(); } catch (IOException ignored) { } proc.destroy(); }
        proc = null; in = null; out = null;
    }
}
