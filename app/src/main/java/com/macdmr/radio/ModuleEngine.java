/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.io.IOException;

/**
 * What RadioService asks of a radio module. {@link RadioEngine} speaks the DMR module's {@code 68 … 10} frames (the
 * Armor family, the Xplore 1); an engine for another module family (the W6's SA818-class AT module) implements the same
 * calls, answers through the same {@link RadioEngine.Events}, and refuses what its module cannot do. Callers check
 * {@link #dmr()} before DMR-only calls (channels with talkgroups, texts, caller info, the {@code 0x40} bench and firmware
 * commands), so a DMR phone's code paths are the same as before this interface existed.
 */
public interface ModuleEngine {
    /** When a command that moves the module's speaker switch last went out (0 when this engine has no such switch). */
    default long lastSpeakerCmdAt() { return 0; }
    /** "mcu68" for the DMR module's frames, "sa818" for AT-command modules. */
    default String protocol() { return "mcu68"; }
    /** True when the module does DMR (digital channels, texts, caller info, 0x40 commands). */
    default boolean dmr() { return true; }
    /** Bytes the Agold bridge's reception line (/dev/stmcu) has delivered since the radio came up; -1 where there is none. */
    default long receptionLineBytes() { return -1; }
    /**
     * For a module that drives the phone's speaker itself (the W6's: {@link DeviceProfile#muteLines} set): the level that
     * follows Android's media volume, 0 = speaker off, 1-8 the module's volume. Other modules ignore it.
     */
    default void setSpeakerLevel(int level) { }

    DeviceProfile profile();
    boolean isOpen();
    void open() throws IOException;
    void close();

    // power
    String powerNode();
    String pttNode();
    String powerState();
    boolean powerOn() throws IOException;
    void powerOff() throws IOException;
    void powerCycle() throws IOException;
    boolean waitBooted(long ms);
    void bootHandshake(Runnable setChannel, boolean digital) throws IOException;

    // queries
    void queryVersion() throws IOException;
    void queryRssi() throws IOException;
    void queryCallerInfo() throws IOException;
    void queryInit() throws IOException;

    // settings and channel
    void setTot(int seconds) throws IOException;
    /** The module timer value the boot handshake sends (TxLimit.moduleTot of the setting); engines without a timer ignore it. */
    default void setBootTot(int seconds) { }
    void setVolume(int v) throws IOException;
    void setMic(int gain) throws IOException;
    void setSquelch(int level) throws IOException;
    void setMonitor(boolean on) throws IOException;
    void setMonitorRaw(int v) throws IOException;
    void setSpkEn(boolean on) throws IOException;
    void setInterrupt(boolean on) throws IOException;
    /** 0x37 polite (busy-channel rule), only where the profile asks for it (DeviceProfile.politeAfterChannel). */
    default void setPolite(int v) throws IOException { }
    void setAnalog(int rxHz, int txHz, int band, int power, int sq, int rxType, int rxSub, int txType, int txSub, int volume, int monitor) throws IOException;
    void setDigital(int rxHz, int txHz, int localId, int[] groups, int txContact, int contactType, int power, int cc, int inSlot, int outSlot, int volume, int mic) throws IOException;
    void ptt(boolean down) throws IOException;

    // DMR texts
    void sendSms(int msgType, int callNumber, int groupNumber, String text) throws IOException;
    void fetchSms() throws IOException;

    // health and the device report
    long unansweredForMs();
    /** True while a write to the control port has missed its deadline (only the Atom XL's bridge sets one). */
    default boolean writeStalled() { return false; }
    java.util.Map<Integer, RadioEngine.CommandStats> stats();
    int[] rssiCounts();

    // raw port use (firmware loader) and bench commands
    void startDivert();
    void stopDivert();
    boolean rawSession();
    void writeRaw(byte[] b) throws IOException;
    int readRaw(int timeoutMs) throws IOException;
    void diagCommand(int cmd) throws IOException;
    void diagCommand(int cmd, int data) throws IOException;
    void diagSine(int n, int amp, int hz) throws IOException;
    void diagPcm(int offset, byte[] samples) throws IOException;
    Frame diag40(byte[] body) throws IOException;
    Frame diag40(byte[] body, int waitMs) throws IOException;
    java.util.List<Frame> diag40n(byte[] body, int n, int waitMs) throws IOException;
}
