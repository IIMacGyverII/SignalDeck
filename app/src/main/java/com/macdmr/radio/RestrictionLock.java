/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The PIN behind Restricted mode: a salted SHA-256 hash, five wrong tries then a one-minute wait, and a timed unlock.
 * Pure: the state is a few strings and numbers the owner stores (the app keeps them in its preferences), and the
 * clock is passed in, so host tests drive it. There is no back door: clearing the app's data removes the PIN, which
 * is what makes the mode voluntary.
 */
public final class RestrictionLock {
    public static final int MIN_DIGITS = 4, MAX_DIGITS = 8, TRIES = 5;
    public static final long WAIT_MS = 60_000L;

    /** Stored state. */
    public static final class State {
        public String salt = "", hash = "";   // empty: no PIN set
        public int wrong;                     // wrong tries since the last right one
        public long waitUntil;                // the clock value until which tries are refused
        public long unlockedUntil;            // the clock value until which the mode is unlocked (0: locked)
        public boolean hasPin() { return !hash.isEmpty(); }
        public String pack() { return salt + "|" + hash + "|" + wrong + "|" + waitUntil + "|" + unlockedUntil; }
        public static State unpack(String s) {
            State st = new State();
            if (s == null) return st;
            String[] p = s.split("\\|", -1);
            if (p.length >= 2) { st.salt = p[0]; st.hash = p[1]; }
            try { if (p.length >= 3) st.wrong = Integer.parseInt(p[2]); } catch (NumberFormatException ignored) { }
            try { if (p.length >= 4) st.waitUntil = Long.parseLong(p[3]); } catch (NumberFormatException ignored) { }
            try { if (p.length >= 5) st.unlockedUntil = Long.parseLong(p[4]); } catch (NumberFormatException ignored) { }
            return st;
        }
    }

    /** Is this a PIN we accept: 4 to 8 digits. */
    public static boolean validPin(String pin) {
        if (pin == null || pin.length() < MIN_DIGITS || pin.length() > MAX_DIGITS) return false;
        for (int i = 0; i < pin.length(); i++) if (pin.charAt(i) < '0' || pin.charAt(i) > '9') return false;
        return true;
    }

    /** Set (or replace) the PIN: a fresh salt and its hash; tries and waits cleared; the mode locked. */
    public static State setPin(String pin) {
        if (!validPin(pin)) throw new IllegalArgumentException("PIN must be 4 to 8 digits");
        byte[] s = new byte[12]; new SecureRandom().nextBytes(s);
        State st = new State();
        st.salt = hex(s); st.hash = hash(st.salt, pin);
        return st;
    }

    /** The result of a try. */
    public enum Try { OK, WRONG, WAIT, NO_PIN }

    /** Check a PIN at this clock value; updates the try count and the wait. */
    public static Try check(State st, String pin, long now) {
        if (!st.hasPin()) return Try.NO_PIN;
        if (now < st.waitUntil) return Try.WAIT;
        if (pin != null && hash(st.salt, pin).equals(st.hash)) { st.wrong = 0; st.waitUntil = 0; return Try.OK; }
        st.wrong++;
        if (st.wrong >= TRIES) { st.wrong = 0; st.waitUntil = now + WAIT_MS; return Try.WAIT; }
        return Try.WRONG;
    }

    /** Milliseconds of wait left at this clock value, 0 when tries are allowed. */
    public static long waitLeft(State st, long now) { return Math.max(0, st.waitUntil - now); }

    /** Unlock until {@code now + ms} (0 ms or less: lock now). */
    public static void unlockFor(State st, long now, long ms) { st.unlockedUntil = ms <= 0 ? 0 : now + ms; }
    public static boolean unlocked(State st, long now) { return st.unlockedUntil > now; }
    public static void lock(State st) { st.unlockedUntil = 0; }

    static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8));
            return hex(d);
        } catch (Exception e) { return ""; }
    }

    private static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x & 0xFF)); return sb.toString(); }
}
