/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Msg;
import com.macdmr.radio.i18n.Txt;

/**
 * DTMF remote control: another radio keys in the PIN and a command digit (for example 1234 then 2, sent as one
 * sequence "12342", an optional * before and # after), and the phone answers on the channel. Three wrong PINs within
 * ten minutes lock the remote for ten minutes. Commands:
 * <pre>
 * 1  position by voice      2  status by voice (battery, channel, signal)
 * 3  APRS position beacon   4  parrot on      5  parrot off      6  Morse ID
 * </pre>
 */
public final class DtmfRemote {
    public interface Actions {
        void speakPosition(); void speakStatus(); void beacon(); void parrot(boolean on); void morseId();
        void event(String line);
        /** The same line kept as a message, so the history can show it in the app's language; by default its English. */
        default void event(Msg m) { event(m.english()); }
    }

    private static final int[] COMMAND_NAMES = { 0, R.string.modes_dtmf_cmd_position, R.string.modes_dtmf_cmd_status, R.string.modes_dtmf_cmd_beacon, R.string.modes_dtmf_cmd_parrot_on, R.string.modes_dtmf_cmd_parrot_off, R.string.modes_dtmf_cmd_morse_id };
    /** The commands' names in English, by digit ([0] is empty); {@link #commandName} gives them in the app's language. */
    public static final String[] COMMANDS = new String[COMMAND_NAMES.length];
    static { COMMANDS[0] = ""; for (int i = 1; i < COMMANDS.length; i++) COMMANDS[i] = Txt.en(COMMAND_NAMES[i]); }
    /** Command {@code i}'s name (1 to 6) in the app's language. */
    public static String commandName(int i) { return i <= 0 || i >= COMMAND_NAMES.length ? "" : Txt.s(COMMAND_NAMES[i]); }
    private static final long WINDOW_MS = 10 * 60_000L;

    private final Actions actions;
    private int failures; private long firstFailureMs, lockedUntil;

    public DtmfRemote(Actions a) { actions = a; }

    /** A decoded DTMF sequence; returns the command run (1 to 6) or 0. */
    public synchronized int handle(String seq, String pin, boolean enabled, long now) {
        if (!enabled || pin == null || pin.length() < 4 || seq == null) return 0;
        String s = seq.trim();
        while (s.startsWith("*")) s = s.substring(1);
        while (s.endsWith("#")) s = s.substring(0, s.length() - 1);
        if (s.length() != pin.length() + 1) return 0;                    // not shaped like PIN + command: someone else's code
        if (now < lockedUntil) return 0;
        if (!s.startsWith(pin)) {
            if (failures == 0 || now - firstFailureMs > WINDOW_MS) { failures = 0; firstFailureMs = now; }
            if (++failures >= 3) { lockedUntil = now + WINDOW_MS; failures = 0; actions.event(Msg.of(R.string.modes_dtmf_event_locked)); }
            else actions.event(Msg.of(R.string.modes_dtmf_event_wrong_pin));
            return 0;
        }
        failures = 0;
        char c = s.charAt(pin.length());
        int cmd = c >= '1' && c <= '6' ? c - '0' : 0;
        if (cmd == 0) { actions.event(Msg.of(R.string.modes_dtmf_event_unknown, String.valueOf(c))); return 0; }
        actions.event(Msg.of(R.string.modes_dtmf_event_command, Msg.of(COMMAND_NAMES[cmd])));
        switch (cmd) {
            case 1: actions.speakPosition(); break;
            case 2: actions.speakStatus(); break;
            case 3: actions.beacon(); break;
            case 4: actions.parrot(true); break;
            case 5: actions.parrot(false); break;
            case 6: actions.morseId(); break;
        }
        return cmd;
    }

    public synchronized boolean locked(long now) { return now < lockedUntil; }
}
