/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.os.Build;

import java.io.File;

/**
 * Everything that differs between phones that carry this DMR module family, and phones with an SA818-class analog
 * module ({@link #protocol} "sa818", driven by {@link AtModuleEngine}). Detection: build fingerprint first, then which
 * sysfs nodes exist.
 */
public final class DeviceProfile {
    /** TINY_SERVICE: PrizeTinyService; UART_PCM: a second UART; CAMCORDER: Android's camcorder input carries the radio (W6); EXTMODULE: the vendor daemon's audio socket (Armor 3WT). */
    public enum Audio { TINY_SERVICE, UART_PCM, CAMCORDER, EXTMODULE }

    public final String name;
    public final String controlPort;     // MCU command UART
    public final int baud;
    public final Audio audio;
    public final String pcmPort;         // UART_PCM only
    public final int pcmBaud;
    public final String powerNode;       // pwd / power enable
    public final String pttNode;         // hardware PTT line (module side)
    public final String powerOnToken;    // read() value when powered
    public final String[] pttActions;    // framework broadcasts for the hardware PTT key: down/up pairs (even index = down, odd = up)
    public final String stockApp;        // package to disable while we own the UART
    public final String[] sepolicy;      // Magisk sepolicy.rule lines an untrusted app needs
    public final String serialLib;       // the stock app's serial JNI library on /system (configures the line); null = stty fallback
    public final boolean supported;      // false = a phone we have no profile for: discovery mode only
    public final boolean verified;       // false = written from an APK and dumps, never run on the phone: no automatic take-over, no transmit
    public String id = "unknown";      // stable profile id (the profile list matches on it)
    public int revision;                 // higher = newer; the profile list only ever replaces a built-in profile with a higher one
    public String source = "built-in";  // or "updated" (from the signed profile list)
    public boolean txBlocked;            // an advisory switched transmit off (the owner can override it)
    public String restrictedBy;          // the advisory id that restricted this copy, or null
    public boolean globalSquelch;        // the module follows its global squelch (0x30) rather than the channel's sq byte: send it with every analog channel
    /** How long the scanner listens on each channel before moving on (the module's "receiving" report must arrive in time). */
    public long scanDwellMs = 1500;
    /**
     * Timing of the module after a retune, per phone (DEVICE_CERTIFICATION 3.7): how long the scanner waits before it
     * trusts the reception report, how long the band scope waits before it reads the signal, and how long a reading
     * takes. The defaults are the Armor's (2026-10-22: the reading is 15-20 dB low until 0.7 s after a retune, a reading
     * takes 0.1-0.5 s). The Atom XL settles in about 50 ms and answers in 6-7 ms (2026-10-30).
     */
    public long scanSettleMs = 700;
    public int scopeSettleMs = 700, scopeReadMs = 300;
    /** High-pass time constant of this phone's pager audio, seconds. 0 means no DC restoration. The Armor 26 Ultra's is from a 512 baud clip. */
    public double pocsagTauSec;
    /** Least time between two channel writes (0 = none); quicker channel changes collapse into the last one. */
    public long minWriteGapMs;
    public String[][] lineSetup;         // {node, value} written in this order before the power line, every bring-up (null = none)
    /** TINY_SERVICE phones: how to reach the radio audio straight on the sound hardware when the ROM lacks the vendor service (null = not known). */
    public DirectAudio directAudio;
    /**
     * "mcu68" (the DMR module's 68 … 10 frames, RadioEngine), "agold" (those commands inside the 7E 96 69 envelope),
     * "agold-legacy" (the envelope around the older command set, through the vendor daemon: Armor 3WT) or "sa818"
     * (AT+DMO… text commands, AtModuleEngine).
     */
    public String protocol = "mcu68";
    /**
     * What the module tunes, as {low, high} pairs in Hz (both ends included). The HR-C7000 module itself refuses
     * anything outside 136-174 and 400-480 MHz (measured 2026-10-21); the W6's SA818-class module is UHF only.
     */
    public long[] bandsHz = { 136_000_000L, 174_000_000L, 400_000_000L, 480_000_000L };
    /** Whether the module reports signal strength (S-meter, band scope, fox hunt); the W6's answers no RSSI query. */
    public boolean rssi = true;
    /**
     * The signal reading in dBm is -120 + raw x {@code rssiStepDb} + {@code rssiOffsetDb}. The Armor's module reads half a
     * dB per step; the Atom XL's DMR006 about one (2026-10-30, HackRF in 10 dB steps: 0x3A, 0x44, 0x50, 0x5B, 0x65, 0x6C),
     * with the offset keeping its idle reading where it showed before (raw 0x22 = -103 dBm).
     */
    public double rssiStepDb = 0.5, rssiOffsetDb = 0;
    /**
     * Soft SQ sets the module's own squelch level instead of gating the audio in the app. The W6: its module drives the
     * speaker itself, its audio level follows the volume (no fixed noise threshold fits) and passes nothing above ~3.4 kHz
     * where the noise detector listens, nor the sub-audible tones; its module squelch (levels 1-8) works.
     */
    public boolean softSqInModule;
    /**
     * SignalDeck plays the received audio itself (an AudioTrack on Android's media stream) and keeps the module's own
     * speaker route off; the module volume stays at {@link #fixedModuleVolume}. The W6: Android's volume then works as on
     * other phones, the received level is fixed (a squelch can be calibrated), and the app's gate mutes what you hear.
     */
    public boolean appPlayback;
    /** The receive audio carries sub-audible tones, so Soft SQ can detect CTCSS. False on the W6 (cut below ~300 Hz). */
    public boolean subaudible = true;
    /** Soft SQ noise thresholds for levels 1-9 and the close cap on this phone's audio scale; null = the detector's own (the Armor's). */
    public double[] softSqThresholds; public double softSqCloseCap;
    /** The module volume held while {@link #appPlayback} is on (the camcorder gain is calibrated at 4 of 8 on the W6). */
    public int fixedModuleVolume = 4;
    /**
     * The module squelch data modes use: 0 = fully open (the default). The W6 measured worse at 0 (its monitor mode: passive
     * APRS 38 of 48 packets against 23 of 24 at squelch 2, HackRF 2026-10-28), so there data modes keep at least this.
     */
    public int dataSquelch;
    /**
     * True only on a phone whose firmware load was bench-tested end to end (owner, 2026-10-30: flashing must work only on
     * certified phones): SignalDeck's loader (and so the bench firmware's symbol engine) is off everywhere else. Off by
     * default, so a new profile, a profile from the signed list or a phone that only falls back to another phone's profile
     * never gets it; see {@link #LOADER_CERTIFIED}.
     */
    public boolean moduleLoader = false;
    /**
     * The profile ids whose firmware load is certified (bench-tested from the stock image through the Module firmware page,
     * restore included). The signed profile list can switch a certified phone's loader off, never on for another phone;
     * adding a phone here needs an app release.
     */
    static final java.util.Set<String> LOADER_CERTIFIED = java.util.Collections.singleton("ulefone-armor26ultra");
    /** Audio.CAMCORDER: the fixed gain on the camcorder input (the W6+'s module is ~21 dB quieter than the W6Plus DMR's). */
    public int camcorderGain = 12;
    /** sa818: the module's receive line, a GPIO input that reads 1 while the squelch is open (null = none). */
    public String rxDetectNode;
    /** sa818: {node, value} written in order when reception starts / ends and when the transmitter keys / unkeys (the stock app's audio and PTT lines). */
    public String[][] rxLinesOn, rxLinesOff, txLinesOn, txLinesOff;
    /**
     * Set when the module drives the speaker itself, outside Android's audio (the W6: module to amplifier through switches):
     * written after {@link #rxLinesOn} while Android's media volume is 0, so the speaker is silent and the recorded audio
     * stays. The module's own volume then follows the media volume.
     */
    public String[][] muteLines;
    /**
     * Camcorder phones whose module microphone input can be switched to the phone's own audio output (the W6Plus DMR stock
     * app's PoC gateway, SpeakUtil.start8541ToDmr / stop8541ToDmr): written around a transmission SignalDeck makes itself
     * (APRS, SSTV, tones) while keyed, so the samples reach the module instead of the microphone. Null = no such path.
     */
    public String[][] gatewayLinesOn, gatewayLinesOff;
    /** Gateway route: the factor on SignalDeck's own transmit samples (its encoders write near full scale), so data tones stay below the module's limiter. */
    public float txAudioScale = 1f;
    /**
     * The CTCSS decoder's dead-air floor (per-sample energy of the receive audio): set on the Armor's loud vendor audio; a
     * camcorder-input phone carries a carrier with only its tone ~30 dB lower, which the default never lets through.
     */
    public double toneEnergyMin = com.macdmr.radio.dsp.CtcssDecoderBank.ENERGY_MIN;
    /**
     * Gateway route: Android's media volume, as a share of its maximum, held while SignalDeck's own audio plays (the samples
     * go through the media stream, so at the user's volume 0 nothing would be sent); the user's volume comes back afterwards.
     */
    public float gatewayVolume = 0.4f;
    /**
     * The boot handshake's third step: interrupt off (digital) or mic gain (analog), as the Armor 26 Ultra's stock app does.
     * The Armor 20WT's stock app never sends either (its handshake is channel, version, time-out), so it is left out there.
     */
    public boolean bootMicInterrupt = true;
    /** Voice transmit: open the audio path this long before the key command (the Armor 20WT's stock app opens it 600 ms early; 0 = key first). */
    public long txAudioLeadMs;
    /** Voice transmit: a key less than this long after the last release waits for the rest (the Armor 20WT's stock app: 2 s; 0 = none). */
    public long minKeyGapMs;
    /** The volume byte in analog channel writes (the Armor 26 Ultra's stock app sends 8, the Armor 20WT's 5). */
    public int analogVolume = 8;
    /** The stock app's per-area channel files: {@code <prefix><area>.db} holding table {@code <prefix><area>}, and the area to use when none is chosen. */
    public String stockAreaPrefix = "database_channel_area_", stockDefaultArea = "default_uhf";
    /**
     * The module commands the stock app is seen to send (null = not listed). The device report lists every other command
     * SignalDeck sent with the module's answers, since nothing shows that this module implements them.
     */
    public int[] stockCommands;
    /**
     * An untested profile whose owner may run the guided Transmit test session (Device report → Transmit test session) to
     * collect what verifying it needs. Only that session keys the radio; the Talk key, the side key, packet modes and the
     * web stay off until the profile is verified. The Armor 20WT (owner, 2026-10-30).
     */
    public boolean txTestUnverified;
    /** False on a module whose digital channel has no time slot (the Armor 3WT's legacy set): the slot is not offered. */
    public boolean timeSlots = true;
    /**
     * The phone's own side key as {input device name, key code as getevent prints it}, used as the custom PTT key when the
     * owner has not chosen one (the Armor 3WT: its key reaches only getevent). Null = none.
     */
    public String[] pttKey;
    /**
     * Transmit audio starts when the module reports SEND_START (status 3) after the key, not at the key: the Xplore 1's
     * QuickChat opens its microphone only on that report (2 s at most here; without it nothing is sent).
     */
    public boolean txAudioOnSendStart;
    /** Voice transmit: the microphone keeps feeding the module this long after the release (QuickChat: 200 ms; 0 = stop first). */
    public long txAudioTailMs;
    /** After each channel's acknowledgement send 0x35 2 (interrupt off) and then 0x37 with this value (QuickChat: 0); -1 = neither. */
    public int politeAfterChannel = -1;
    /**
     * DMR texts as QuickChat sends them: type, target little-endian, the text in UTF-8, no second ID; received texts are
     * read as UTF-8 unless they look like UTF-16LE. False = the Armor 26 Ultra's body (9-byte header, UTF-16LE).
     */
    public boolean smsUtf8;
    /** The text body this profile sends, for the reports. */
    public String textBody() { return smsUtf8 ? "type, target, UTF-8 text (QuickChat's)" : "type, target, second ID, UTF-16LE text"; }

    /**
     * The radio's audio as plain ALSA streams on the phone's sound card, for a ROM without the vendor audio service
     * (a custom ROM or GSI). SignalDeck's root helper (sdalsa) sets the mixer controls and moves the PCM. Each control
     * is "NAME=VALUE" exactly as the kernel names it; the on lists are set before a stream opens, the off lists after it.
     */
    public static final class DirectAudio {
        public final int card, captureDevice, playbackDevice;
        public final String[] rxOn, rxOff, txOn, txOff;
        /** The card's rate: 8000 (the phone's codec, as the app uses it) or a multiple of it (the Atom XL's USB card: 32000), converted in the app. */
        public int rate = 8000;
        /** Capture period in frames and period count; 0 = the default (256 frames per 8 kHz, 4 periods, 16 on a USB card). */
        public int capturePeriod, capturePeriods;
        public DirectAudio(int card, int captureDevice, int playbackDevice, String[] rxOn, String[] rxOff, String[] txOn, String[] txOff) {
            this.card = card; this.captureDevice = captureDevice; this.playbackDevice = playbackDevice;
            this.rxOn = rxOn; this.rxOff = rxOff; this.txOn = txOn; this.txOff = txOff;
        }
        /** Letters, digits, space and _ - . , = only: the controls end up on a root command line. */
        static boolean safeControl(String c) { return c != null && c.matches("[A-Za-z0-9 _.,-]{1,80}=[A-Za-z0-9 _.,-]{1,40}"); }
        boolean valid() {
            if (card < 0 || card > 31 || captureDevice < 0 || captureDevice > 63 || playbackDevice < 0 || playbackDevice > 63) return false;
            if (rate < 8000 || rate > 48000 || rate % 8000 != 0) return false;
            for (String[] l : new String[][] { rxOn, rxOff, txOn, txOff }) { if (l == null) return false; for (String c : l) if (!safeControl(c)) return false; }
            return true;
        }
        static DirectAudio fromJson(org.json.JSONObject o) throws org.json.JSONException {
            DirectAudio d = new DirectAudio(o.getInt("card"), o.getInt("captureDevice"), o.getInt("playbackDevice"),
                    strings(o.optJSONArray("rxOn")), strings(o.optJSONArray("rxOff")), strings(o.optJSONArray("txOn")), strings(o.optJSONArray("txOff")));
            d.rate = o.optInt("rate", 8000);
            if (!d.valid()) throw new org.json.JSONException("directAudio: bad device number or control");
            return d;
        }
    }

    private DeviceProfile(String name, String controlPort, int baud, Audio audio, String pcmPort, int pcmBaud,
                          String powerNode, String pttNode, String powerOnToken, String[] pttActions,
                          String stockApp, String[] sepolicy, String serialLib, boolean supported, boolean verified) {
        this.name = name; this.controlPort = controlPort; this.baud = baud; this.audio = audio;
        this.pcmPort = pcmPort; this.pcmBaud = pcmBaud; this.powerNode = powerNode; this.pttNode = pttNode;
        this.powerOnToken = powerOnToken; this.pttActions = pttActions; this.stockApp = stockApp; this.sepolicy = sepolicy; this.serialLib = serialLib; this.supported = supported; this.verified = verified;
    }

    /** Ulefone Armor 26 Ultra (walkie-talkie model) — measured 2026-10-17. */
    public static final DeviceProfile ARMOR_26_ULTRA = new DeviceProfile(
            "Ulefone Armor 26 Ultra", "/dev/ttyS0", 57600, Audio.TINY_SERVICE, null, 0,
            "/sys/devices/platform/dmr009/pwd", "/sys/devices/platform/dmr009/ptt", "pwd :1",
            // the framework ("smartlinkkey", KEY_SAT keycode 381) sends android.intent.action.PTT.down/up for the side key;
            // com.interphone.ptt.down/up is what the stock app listens for when the key is assigned to it (measured 2026-10-18)
            new String[] { "android.intent.action.PTT.down", "android.intent.action.PTT.up", "com.interphone.ptt.down", "com.interphone.ptt.up" },
            "com.pri.prizeinterphone",
            new String[] {
                    "allow untrusted_app ttyS_device chr_file { open read write ioctl getattr lock }",
                    "typeattribute ttyS_device mlstrustedobject",
                    "allow untrusted_app sysfs_prizefactory file { open read write getattr }",
                    "typeattribute sysfs_prizefactory mlstrustedobject" },
            "/system/lib64/libinterphone_serial_port.so", true, true);

    /**
     * Ulefone Armor 20WT — from an owner's device report (beta.18, 2026-10-24) and its stock app (the same PriInterPhone
     * family as the Armor 26 Ultra: dmr009 pwd/ptt nodes, PrizeTinyService audio, the same command table). The
     * differences: the module is on /dev/ttyS1 (the stock app opens only that port, at 57600; ttyS0 here is another
     * device), the serial library is libserial_port_app.so in the stock app's own folder, bound to
     * com.serial.port.SerialPort, and the side key arrives as keycode 301 with com.interphone.ptt.down/up. The report's
     * probe got no answer because the module was powered off at the time. Not run on the phone yet: unverified.
     * Revision 2 (stock-app review, phonedmrapp docs/armor20wt/stock-app-review.md): the stock app is an older build
     * with a smaller command set; see the fields set in the static block.
     */
    public static final DeviceProfile ARMOR_20WT = new DeviceProfile(
            "Ulefone Armor 20WT", "/dev/ttyS1", 57600, Audio.TINY_SERVICE, null, 0,
            "/sys/devices/platform/dmr009/pwd", "/sys/devices/platform/dmr009/ptt", "pwd :1",
            new String[] { "com.interphone.ptt.down", "com.interphone.ptt.up", "android.intent.action.PTT.down", "android.intent.action.PTT.up" },
            "com.pri.prizeinterphone",
            new String[] {
                    "allow untrusted_app ttyS_device chr_file { open read write ioctl getattr lock }",
                    "typeattribute ttyS_device mlstrustedobject",
                    "allow untrusted_app sysfs_prizefactory file { open read write getattr }",
                    "typeattribute sysfs_prizefactory mlstrustedobject" },
            "/system/priv-app/PriInterPhone/lib/arm64/libserial_port_app.so", true, false);

    /**
     * Blackview Xplore 1 — from its QuickChat APK and an owner's dumps (docs/multi-device-xplore1-findings.md): ttyS0/ttyS1
     * are ttyS_device, the intercom_* nodes plain sysfs (mode 0777, so SELinux is the only gate; SysfsGpio writes them
     * through root rather than opening all of sysfs to apps). Not run on the phone yet: unverified.
     */
    public static final DeviceProfile XPLORE_1 = new DeviceProfile(
            "Blackview Xplore 1", "/dev/ttyS0", 57600, Audio.UART_PCM, "/dev/ttyS1", 230400,
            "/sys/devices/platform/intercom/intercom_pwd_control", "/sys/devices/platform/intercom/intercom_ptt_control", "1",
            new String[] { "com.android.intent.ptt.down", "com.android.intent.ptt.up" },
            "com.chamsion.quickchat",
            new String[] {
                    "allow untrusted_app ttyS_device chr_file { open read write ioctl getattr lock }",
                    "typeattribute ttyS_device mlstrustedobject" },
            null, true, false);
    static {
        // QuickChat's power-up (j0.a.run): power_control, pwd_control and ptt_control all 1; its power-off writes all 0, and
        // ptt_control 0 is its transmit state (setPttLaunch). So the PTT line goes to receive first, before the module is
        // powered, or a module left by QuickChat's power-off would come up with its PTT line asserted.
        ARMOR_26_ULTRA.id = "ulefone-armor26ultra"; ARMOR_26_ULTRA.revision = 1;
        ARMOR_26_ULTRA.moduleLoader = true;     // certified: FW57 loaded from the stock image through the Module firmware page (2026-10-30)
        // Fitted on the 17-bit POCSAG padding run (bits 806–822 of the padded RESTORE B clip): τ ≈ 7.49 ms.
        ARMOR_26_ULTRA.pocsagTauSec = 0.0075;
        // Measured on the owner's Armor 2026-10-24 (stock ROM, mixer read while tinyserver received and while it sent a
        // low-power test tone): receive is capture device 11 (8 kHz stereo) with I2S2 routed to UL2; transmit is playback
        // device 8 with DL8 routed to I2S1, the ES8311's DAC turned up and its ADC muted. Used only when the ROM has no
        // PrizeTinyService (custom ROMs / GSIs).
        ARMOR_26_ULTRA.directAudio = new DirectAudio(0, 11, 8,
                new String[] { "UL2_CH1 I2S2_CH1=1", "UL2_CH2 I2S2_CH2=1", "ADC SDP MUTE=0", "DAC VOLUME=0", "DAC SDP SRC MUX=SELECT SDP RIGHT DATA" },
                new String[] { "UL2_CH1 I2S2_CH1=0", "UL2_CH2 I2S2_CH2=0" },
                new String[] { "I2S1_HD_Mux=Low_Jitter", "I2S1_CH1 DL8_CH1=1", "I2S1_Out_Mux=Output_Widget", "ADC SDP MUTE=1", "DAC VOLUME=163", "DAC SDP SRC MUX=SELECT SDP LEFT DATA" },
                new String[] { "I2S1_CH1 DL8_CH1=0", "I2S1_Out_Mux=Normal", "I2S1_HD_Mux=Normal", "DAC VOLUME=0", "ADC SDP MUTE=0", "DAC SDP SRC MUX=SELECT SDP RIGHT DATA" });
        ARMOR_20WT.id = "ulefone-armor20wt"; ARMOR_20WT.revision = 2;
        // Its stock app (review 2026-10-30): channel, version, time-out at boot and nothing else; opens the audio path and
        // waits 600 ms before keying; keys no sooner than 2 s after a release; analog volume byte 5; channel files
        // channel_database_<area>.db (areas Default, USA_FRS, …); no firmware updater and no image in the app, so the
        // loader stays off (an undiffed variant).
        ARMOR_20WT.bootMicInterrupt = false;
        ARMOR_20WT.txAudioLeadMs = 600;
        ARMOR_20WT.minKeyGapMs = 2000;
        ARMOR_20WT.analogVolume = 5;
        ARMOR_20WT.stockAreaPrefix = "channel_database_"; ARMOR_20WT.stockDefaultArea = "Default";
        ARMOR_20WT.moduleLoader = false;
        ARMOR_20WT.stockCommands = new int[] { 0x22, 0x23, 0x26, 0x27, 0x2C, 0x2D, 0x34, 0x36, 0x3B };
        ARMOR_20WT.txTestUnverified = true;    // owner, 2026-10-30: the device report's transmit tests may key it
        XPLORE_1.id = "blackview-xplore1"; XPLORE_1.revision = 3;
        // QuickChat, read 2026-10-30 (phonedmrapp docs/xplore1/stock-app-review.md): it never sends 0x26; it keys with
        // intercom_ptt_control 0 and releases with 1, opens the microphone at the module's SEND_START, stops it 200 ms
        // after the release, refuses a key within 1.5 s of the last release, sends neither 0x2A nor 0x35 at boot but 0x35 2
        // and 0x37 0 after every channel, and sends texts as type + target + UTF-8. Its updater has no image (undiffed variant).
        XPLORE_1.txLinesOn = new String[][] { { "/sys/devices/platform/intercom/intercom_ptt_control", "0" } };
        XPLORE_1.txLinesOff = new String[][] { { "/sys/devices/platform/intercom/intercom_ptt_control", "1" } };
        XPLORE_1.txAudioOnSendStart = true;
        XPLORE_1.txAudioTailMs = 200;
        XPLORE_1.minKeyGapMs = 1500;
        XPLORE_1.bootMicInterrupt = false;
        XPLORE_1.politeAfterChannel = 0;
        XPLORE_1.smsUtf8 = true;
        XPLORE_1.moduleLoader = false;
        XPLORE_1.stockCommands = new int[] { 0x22, 0x23, 0x27, 0x28, 0x2C, 0x2D, 0x2F, 0x30, 0x34, 0x35, 0x37, 0x3B };
        XPLORE_1.txTestUnverified = true;     // owner, 2026-10-30: the device report's transmit tests may key it (the guided session only)
        XPLORE_1.scanDwellMs = 2500;
        XPLORE_1.minWriteGapMs = 1000;        // QuickChat never writes channels less than 1 s apart; a burst of writes made the module refuse MON (2026-10-22)          // owner's tester 2026-10-22: the first scan stepped past an active channel; its receive report comes later than the Armor's
        XPLORE_1.globalSquelch = true;        // QuickChat sends 0x30 after each channel; the beta.3/5 trials showed the channel's sq 0 alone did not open it
        XPLORE_1.lineSetup = new String[][] {
                { "/sys/devices/platform/intercom/intercom_ptt_control", "1" },
                { "/sys/devices/platform/intercom/intercom_power_control", "1" } };
    }

    /**
     * ANYSECU W6+ (UNISOC, analog UHF 400–470 MHz, 32-bit Android 12): an SA818-class module (PB202EU05) on /dev/ttyS2 at
     * 9600, driven with AT+DMO… commands, from the device report's serial sniff and the stock app's code
     * (docs/w6/ENGINE_DESIGN.md). Power is gpio322; reception is gpio189 (an input); the receive audio reaches Android on
     * the camcorder input (docs/w6/RX_AUDIO_TEST.md). Transmit is not tested on air: unverified, so it never transmits.
     * Never read the flyscale_misc attributes on this phone: two of them panic the kernel.
     */
    public static final DeviceProfile ANYSECU_W6 = new DeviceProfile(
            "ANYSECU W6+", "/dev/ttyS2", 9600, Audio.CAMCORDER, null, 0,
            "/sys/class/gpio/gpio322/value", "/sys/class/gpio/gpio188/value", "1",
            new String[] { "fly.privkey.poc.down", "fly.privkey.poc.up" },
            "com.flyscale.analogpoc",
            new String[] {
                    "allow untrusted_app console_device chr_file { open read write ioctl getattr lock }",
                    "typeattribute console_device mlstrustedobject" },
            null, true, true);
    static {
        ANYSECU_W6.id = "anysecu-w6plus"; ANYSECU_W6.revision = 3; ANYSECU_W6.protocol = "sa818";
        // Revision 2 (2026-10-28): tested for receive on the owner's W6+ (take-over, 128 channels, tones, APRS, SSTV, FT8,
        // M17 from a HackRF), so it takes the radio over like a supported phone.
        // Revision 3 (2026-10-28): transmit confirmed on the air by the owner in the cage, so txBlocked is cleared.
        ANYSECU_W6.bandsHz = new long[] { 400_000_000L, 470_000_000L }; ANYSECU_W6.rssi = false; ANYSECU_W6.appPlayback = true; ANYSECU_W6.subaudible = false; ANYSECU_W6.dataSquelch = 1;
        // Soft SQ thresholds measured on the W6 at module volume 4 (HackRF, 2026-10-28, scratchpad sqsim.py): static 12.8-27.6;
        // TTS speech at HackRF gain 7 (barely readable) ~11, gain 9 ~4.4, gain 11 and up <= 3.5. Level 1 passes the barely
        // readable, levels 7-9 only clean signals, static never opens.
        ANYSECU_W6.softSqThresholds = new double[] { 11, 9.5, 8, 6.5, 5, 4, 3.3, 2.8, 2.4 }; ANYSECU_W6.softSqCloseCap = 12;
        ANYSECU_W6.rxDetectNode = "/sys/class/gpio/gpio189/value";
        // the W6Plus DMR's gateway relay for SignalDeck's own transmit audio: the same nodes exist on the W6+ (same board) though
        // its stock app never uses them; a tone and an N0CALL APRS packet sent through them decoded on a W6Plus DMR (2026-10-29)
        ANYSECU_W6.gatewayLinesOn = new String[][] { { "/sys/kernel/dmr_state/dmr_state", "0" }, { "/sys/kernel/relay_state/relay_state", "1" }, { "/sys/class/gpio/gpio223/value", "1" }, { "/sys/class/gpio/gpio222/value", "1" }, { "/sys/class/gpio/gpio179/value", "1" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "0" } };
        ANYSECU_W6.gatewayLinesOff = new String[][] { { "/sys/kernel/relay_state/relay_state", "0" }, { "/sys/class/gpio/gpio223/value", "0" }, { "/sys/class/gpio/gpio179/value", "0" }, { "/sys/kernel/dmr_state/dmr_state", "0" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "1" } };
        // the relay path on the W6+ is ~16 dB quieter than the W6Plus DMR's for the same samples: at 0.03 its DTMF arrived at
        // RMS 50 (under the decoder's floor) and PSK31 lost words; it is linear up to 0.5 (no harmonics) and at 0.2 DTMF (16/16),
        // PSK31, SSTV Robot 24 (clean, 118 lines), RTTY and FT8 all decoded on a W6Plus DMR (2026-10-29). HackRF 2026-10-30
        // (§3.7 item 9, media volume held at 10/25): an APRS packet at 0.2 deviates 2900 Hz p99 (0.3: 4086), on target.
        ANYSECU_W6.txAudioScale = 0.2f; ANYSECU_W6.gatewayVolume = 0.4f;
        // the stock app's receStart / receEnd (speaker path; the camcorder input carries the audio while these are set)
        ANYSECU_W6.rxLinesOn = new String[][] { { "/sys/kernel/dmr_state/dmr_state", "1" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "0" }, { "/sys/class/gpio/gpio183/value", "1" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "1" } };
        // the speaker route only: the stock app skips it at volume 0; the amplifier must stay on, the camcorder input is behind it (measured: amplifier off = silent recording)
        ANYSECU_W6.muteLines = new String[][] { { "/sys/class/gpio/gpio183/value", "0" } };
        ANYSECU_W6.rxLinesOff = new String[][] { { "/sys/kernel/dmr_state/dmr_state", "0" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "0" }, { "/sys/class/gpio/gpio183/value", "0" } };
        // the stock app's startSpeaker / stopSpeaker: mic to the module, built-in mic, then PTT (used only once verified)
        ANYSECU_W6.txLinesOn = new String[][] { { "/sys/class/gpio/gpio222/value", "1" }, { "/sys/class/gpio/gpio179/value", "0" }, { "/sys/class/gpio/gpio188/value", "1" } };
        ANYSECU_W6.txLinesOff = new String[][] { { "/sys/class/gpio/gpio188/value", "0" }, { "/sys/class/gpio/gpio222/value", "0" } };
        ANYSECU_W6.scanDwellMs = 1500;
    }

    /**
     * ANYSECU W6Plus with DMR (model string V69, UNISOC sl8541e, UHF, 32-bit Android 12): the W6+'s phone body with a DMR
     * module of the Armor family on /dev/ttyS2 at 57600 (it answers DMR006.U4A.V140, the Atom XL's module family without
     * the USB bridge; the stock app carries image DMR-006-HHT-400M-V140 and the 68 … 10 command set, CMD 0x22-0x39 name for
     * name), from the device report of 2026-10-29 and the stock app com.flyscale.dmr (FlyscaleDmr400). The phone side is
     * the W6+'s: power gpio322; the receive audio reaches Android on the camcorder input behind the speaker route
     * (dmr_state, ext_pa_en, gpio183), which the stock app switches on the module's RECEIVE_START / RECEIVE_STOP pushes
     * (SpeakUtil.startGroup / stopGroup); it keys with the PTT line gpio188 and the mic lines and never sends 0x26. The
     * module keeps the vendor's image (no loader). Never read the flyscale_misc attributes. Revision 2 (owner, 2026-10-29):
     * verified; transmit tested in the cage on 446.000 MHz at low power (FM tone key, DMR group call CC1 TG 9 decoded from
     * a HackRF capture, the 28 s watchdog unkeyed), carrier about +1.6 kHz against the HackRF.
     */
    public static final DeviceProfile ANYSECU_W6_DMR = new DeviceProfile(
            "ANYSECU W6Plus DMR", "/dev/ttyS2", 57600, Audio.CAMCORDER, null, 0,
            "/sys/class/gpio/gpio322/value", "/sys/class/gpio/gpio188/value", "1",
            new String[] { "fly.privkey.poc.down", "fly.privkey.poc.up" },
            "com.flyscale.dmr",
            new String[] {
                    "allow untrusted_app console_device chr_file { open read write ioctl getattr lock }",
                    "typeattribute console_device mlstrustedobject" },
            null, true, true);
    static {
        ANYSECU_W6_DMR.id = "anysecu-w6plus-dmr"; ANYSECU_W6_DMR.revision = 2;
        // 0x23 writes 2026-10-29 (nothing keyed): 400.000-480.000 acknowledged; 399.995, 395, 136 and 174 MHz never answered
        // (UHF only, whatever "dual band" in the listing means); 480.005 and 485 acknowledged too, receive there unmeasured,
        // so the band stays the stock app's 400.000-480.000.
        ANYSECU_W6_DMR.bandsHz = new long[] { 400_000_000L, 480_000_000L };
        ANYSECU_W6_DMR.appPlayback = true; ANYSECU_W6_DMR.moduleLoader = false;
        // HackRF on 446.000 MHz, 2026-10-29: a 1 kHz tone at 2.5 kHz deviation reaches the camcorder input at -32 dBFS (module
        // volume 8), 12 dB louder than the W6+'s; gain 4 puts it at about -20 dBFS and static near the Armor's scale for the
        // noise squelch: static (smoothed high band 128-231) never opens, speech low-passed at 3 kHz like a transmitter's
        // (2.5-6.7) opens at every level, so the built-in Soft SQ table stands. 62.5 Hz CTCSS arrives ~40 dB down, 44 dB
        // above the floor, like the Armor's.
        ANYSECU_W6_DMR.camcorderGain = 4;
        // the stock app's startGroup / stopGroup (the W6+'s receive lines) and startSpeaker / stopSpeaker (mic, built-in mic, PTT)
        ANYSECU_W6_DMR.rxLinesOn = new String[][] { { "/sys/kernel/dmr_state/dmr_state", "1" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "0" }, { "/sys/class/gpio/gpio183/value", "1" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "1" } };
        ANYSECU_W6_DMR.muteLines = new String[][] { { "/sys/class/gpio/gpio183/value", "0" } };
        ANYSECU_W6_DMR.rxLinesOff = new String[][] { { "/sys/kernel/dmr_state/dmr_state", "0" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "0" }, { "/sys/class/gpio/gpio183/value", "0" } };
        ANYSECU_W6_DMR.txLinesOn = new String[][] { { "/sys/class/gpio/gpio222/value", "1" }, { "/sys/class/gpio/gpio179/value", "0" }, { "/sys/class/gpio/gpio188/value", "1" } };
        ANYSECU_W6_DMR.txLinesOff = new String[][] { { "/sys/class/gpio/gpio188/value", "0" }, { "/sys/class/gpio/gpio222/value", "0" } };
        // the stock app's start8541ToDmr (PoC gateway): the relay puts the phone's audio output on the module's microphone input,
        // poc_spk_en on, speaker amplifier off; stop8541ToDmr turns the relay and poc_spk_en off again
        ANYSECU_W6_DMR.gatewayLinesOn = new String[][] { { "/sys/kernel/dmr_state/dmr_state", "0" }, { "/sys/kernel/relay_state/relay_state", "1" }, { "/sys/class/gpio/gpio223/value", "1" }, { "/sys/class/gpio/gpio222/value", "1" }, { "/sys/class/gpio/gpio179/value", "1" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "0" } };
        // HackRF 2026-10-29, 446.000 MHz: 1 kHz deviates ~1.09 Hz per sample unit, the module limits near 4.3 kHz, and the
        // path lifts 2.5 kHz by ~8 dB (pre-emphasis), cuts below 300 Hz (100-250 Hz at the floor) and above 3 kHz, so no M17
        // over audio. That 0.03 was set at an unknown media volume; with the volume held at 10/25 (gatewayVolume) an APRS packet
        // at 0.03 deviated only 879 Hz p99 (HackRF 2026-10-30, §3.7 item 9): 0.10 -> 2040, 0.12 -> 2408, 0.15 -> 2954 Hz, all decoded.
        ANYSECU_W6_DMR.txAudioScale = 0.15f;
        // §3.7 item 1 (HackRF carriers stepped 10 dB, 2026-10-30): one raw unit per dB, linear over at least 56 dB above the idle
        // 42-43, like the Atom XL's module of the same DMR006 family; its offset kept (idle 0x22 = -103 dBm). Item 4: squelch
        // levels 1-9 open at one point, 4-5 dB above the floor; 0 is monitor (no reception reported).
        ANYSECU_W6_DMR.rssiStepDb = 1.0; ANYSECU_W6_DMR.rssiOffsetDb = -17;
        // a carrier with only its CTCSS arrives at RMS ~24 on this camcorder input (100.0 Hz from a W6+, 2026-10-30): the tone
        // stands ~300x above the other bins, but the default dead-air floor (RMS ~630) never let it count; 200 = RMS ~14, above
        // the idle input (RMS 7-10), the ratio tests still keep noise and voice out
        ANYSECU_W6_DMR.toneEnergyMin = 200;
        // the media volume held during those plays: 10 of 25, at which APRS, RTTY, FT8 and SSTV from this phone all decoded on a
        // W6+ (2026-10-29); at the user's volume 0 the route had sent a silent carrier
        ANYSECU_W6_DMR.gatewayVolume = 0.4f;
        // ... and the idle values of what start8541ToDmr changed (speaker amplifier on, dmr_state 0, measured between receptions
        // 2026-10-29), so a play whose snapshot of the nodes failed still leaves the speaker working
        ANYSECU_W6_DMR.gatewayLinesOff = new String[][] { { "/sys/kernel/relay_state/relay_state", "0" }, { "/sys/class/gpio/gpio223/value", "0" }, { "/sys/class/gpio/gpio179/value", "0" }, { "/sys/kernel/dmr_state/dmr_state", "0" }, { "/sys/kernel/ext_pa_en/ext_pa_en", "1" } };
    }

    /** Any other phone: nothing is touched until a device report has been made and a profile written. */
    /**
     * Unihertz Atom XL (Agold): the module sits behind a USB bridge MCU on the phone's only USB controller, so the
     * command port {@code /dev/ttyACM0} and the radio's USB sound card exist only while the bridge is powered, and a
     * computer on the cable loses the phone meanwhile. SignalDeck stops the vendor HAL and powers the bridge itself
     * ({@link AgoldUsb#bridgeUp}); Exit and Hand back power it off and give the USB port back. Measured on the bench
     * Atom XL 2026-10-28 (docs/atomxl in phonedmrapp). Revision 2 (owner, 2026-10-28): verified; transmit tested in the cage (FM, DMR voice, DMR text, APRS decoded) on module DMR006.U4A.V067.
     */
    public static final DeviceProfile ATOM_XL = new DeviceProfile(
            "Unihertz Atom XL", AgoldUsb.BRIDGE_PORT, 115200, Audio.TINY_SERVICE, null, 0,
            AgoldUsb.MCU_POWER, AgoldUsb.HANDLE_PTT, "power on = 1",
            new String[] { "android.intent.action.PTT.down", "android.intent.action.PTT.up" },
            "com.agold.intercom",
            new String[] {
                    "allow untrusted_app ttyACM_device chr_file { open read write ioctl getattr lock }",
                    "typeattribute ttyACM_device mlstrustedobject",
                    "allow untrusted_app agui_device chr_file { open read getattr }",       // /dev/stmcu: reception start / end
                    "typeattribute agui_device mlstrustedobject" },
            null, true, true);
    static {
        ATOM_XL.id = "unihertz-atomxl"; ATOM_XL.revision = 2; ATOM_XL.protocol = "agold";
        ATOM_XL.bandsHz = new long[] { 400_000_000L, 480_000_000L };          // the UHF module (DMR006.U4A); to be measured
        // the bridge's USB Audio Class card: S16_LE, 2 channels, 32 kHz both ways, no mixer controls (the HAL's pcm_open)
        ATOM_XL.directAudio = new DirectAudio(1, 0, 0, new String[0], new String[0], new String[0], new String[0]);
        ATOM_XL.rssiStepDb = 1.0; ATOM_XL.rssiOffsetDb = -17;                  // one step per dB, idle 0x22 = -103 dBm as before
        ATOM_XL.scopeSettleMs = 100; ATOM_XL.scopeReadMs = 20;               // settles in ~50 ms, answers in 6-7 ms (2026-10-30, HackRF); 100 ms leaves margin
        ATOM_XL.scanSettleMs = 100; ATOM_XL.scanDwellMs = 500;               // the reception line reports 0.22 s after a retune onto an active channel
        ATOM_XL.directAudio.capturePeriod = 1280; ATOM_XL.directAudio.capturePeriods = 4;   // what the stock HAL opens (vendor.mediatek.hardware.aguiextmodule@1.0-service: 32 kHz, 2 ch, 1280 x 4)
        ATOM_XL.directAudio.rate = 32000;
    }

    /**
     * Ulefone Armor 3WT (Agold, MT6771, board s62v71c2k_gq_3wt): the Atom XL's USB bridge family with an older module
     * ({@code ver0.5.4_470M_TONE}: the legacy command set, DMR without a time slot) behind the vendor daemon
     * {@code /system/bin/extmodule}, which the tester's own Magisk module starts at boot on a GSI. SignalDeck talks to the
     * daemon as the intercom app does: commands on 127.0.0.1:9980, audio on 9985 ({@link ExtModuleLink}, {@link
     * AgoldLegacyCodec}, {@link ExtModuleAudio}). Written from the intercom app, the daemon and one device report
     * (phonedmrapp docs/armor3wt/stock-app-review.md); never run by us, so unverified: no transmit. No signal reading
     * (the module has no known RSSI command). Bands: what the intercom app accepts, 100–470 MHz, without 174–400 MHz;
     * the module's own range is not measured.
     */
    public static final DeviceProfile ARMOR_3WT = new DeviceProfile(
            "Ulefone Armor 3WT", ExtModuleLink.CONTROL_PORT, 0, Audio.EXTMODULE, null, 0,
            AgoldUsb.MCU_POWER, AgoldUsb.HANDLE_PTT, "power on = 1",
            new String[] { "android.intent.action.PTT.down", "android.intent.action.PTT.up" },
            "com.agold.intercom",
            new String[0],                                                       // TCP to 127.0.0.1 only: no device node, no new rule
            null, true, false);
    static {
        ARMOR_3WT.id = "ulefone-armor3wt"; ARMOR_3WT.revision = 1; ARMOR_3WT.protocol = "agold-legacy";
        ARMOR_3WT.bandsHz = new long[] { 100_000_000L, 174_000_000L, 400_000_000L, 470_000_000L };
        ARMOR_3WT.rssi = false; ARMOR_3WT.moduleLoader = false;
        ARMOR_3WT.bootMicInterrupt = false;                                     // its stock app: channel after the version, nothing else
        ARMOR_3WT.timeSlots = false;
        // what the intercom app sends, in the engine's (Armor) numbers: channels, version, volume, call info, texts
        ARMOR_3WT.stockCommands = new int[] { 0x22, 0x23, 0x2B, 0x2C, 0x2D, 0x2E, 0x34 };
        ARMOR_3WT.pttKey = new String[] { "mtk-kpd", "00f9" };                  // scancode 249 at getevent; no keycode reached an app in the report
    }

    public static final DeviceProfile UNKNOWN = new DeviceProfile(
            Build.MANUFACTURER + " " + Build.MODEL + " (not supported yet)", "/dev/ttyS0", 57600, Audio.TINY_SERVICE, null, 0,
            null, null, "1", null, "", new String[0], null, false, false);

    /** The profile in use: a built-in one, or a newer one from the signed profile list, with any advisories applied. */
    public static DeviceProfile detect() { return ProfileRegistry.active(); }

    /** The profile built into this release for this phone (no profile list, no advisories). */
    public static DeviceProfile detectBuiltIn() {
        String m = (Build.MANUFACTURER + " " + Build.MODEL + " " + Build.DEVICE).toLowerCase();
        if (armor3wt(m, Build.BOARD, vendorFingerprint(), new File(ExtModuleLink.DAEMON).exists())) return ARMOR_3WT;
        return detectBuiltIn(m, new File("/sys/devices/platform/dmr009").exists(), new File("/sys/devices/platform/intercom").exists());
    }

    /**
     * The Armor 3WT: its model, or on a GSI (model "TrebleDroid …") its board or vendor build, and in either case the radio
     * daemon {@code extmodule} installed (without it SignalDeck has nothing to talk to).
     */
    static boolean armor3wt(String model, String board, String vendorFingerprint, boolean daemon) {
        if (!daemon) return false;
        String m = model == null ? "" : model.toLowerCase(java.util.Locale.US);
        return m.contains("armor 3wt") || m.contains("armor_3wt") || "s62v71c2k_gq_3wt".equals(board)
                || (vendorFingerprint != null && vendorFingerprint.contains("/Armor_3WT/"));
    }
    private static String vendorFingerprint() {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "ro.vendor.build.fingerprint"); }
        catch (Throwable t) { return null; }
    }

    /** Model text first (the Armor 20WT has the same dmr009 node as the Armor 26 Ultra, on another port), then the nodes. */
    static DeviceProfile detectBuiltIn(String m, boolean dmr009, boolean intercom) {
        if (m.contains("armor 20wt") || m.contains("armor_20wt")) return ARMOR_20WT;
        if (m.contains("w6+") && m.contains("sl8541e")) return ANYSECU_W6;          // "UNISOC W6+ sl8541e_3h10_32b"
        if (m.contains(" v69 ") && m.contains("sl8541e")) return ANYSECU_W6_DMR;     // "UNISOC V69 sl8541e_3h10_32b"
        if (m.contains("unihertz atom_xl")) return ATOM_XL;                         // "Unihertz Atom_XL Atom_XL"
        if (m.contains("armor 26")) return ARMOR_26_ULTRA;
        if (dmr009) {                                                            // another phone with the same power node: the 26 Ultra's radio path, never its loader
            DeviceProfile p = ARMOR_26_ULTRA.copy(ARMOR_26_ULTRA.supported, ARMOR_26_ULTRA.verified); p.moduleLoader = false; return p;
        }
        if (intercom || m.contains("xplore")) return XPLORE_1;
        return UNKNOWN;
    }

    public SysfsGpio power() { return new SysfsGpio(powerNode, powerOnToken); }

    /** False on a phone whose module has no DMR (an SA818-class analog module): the DMR pages and choices are hidden. */
    public boolean dmr() { return !"sa818".equals(protocol); }
    /**
     * Whether the Messages page has anything to carry on this phone. Today it holds DMR texts, so it follows {@link #dmr};
     * once APRS or another text mode works on an analog-only radio (owner, 2026-10-27: expected on the W6), return true
     * here for it and the page comes back.
     */
    public boolean hasMessages() { return dmr(); }
    /** Whether the Contacts page is useful here: its entries are DMR IDs and talkgroups today; same rule as {@link #hasMessages}. */
    public boolean hasContacts() { return dmr(); }
    /**
     * Whether this phone's module firmware can be read and catalogued (its stock app carries the image, our loader can
     * dump it). False for an SA818-class module: no firmware file in the stock app, no read command in the module, so
     * there is nothing the firmware project could take from a submission and the variant check is skipped.
     */
    public boolean firmwareCatalogued() { return dmr(); }

    /**
     * Whether SignalDeck's firmware loader can reach this module: the dmr009 knock and YModem on the module's own UART.
     * The Atom XL's module sits behind a USB bridge whose update path is the bridge's own (route 00 cmd 0x09), so no.
     */
    public boolean firmwareLoader() { return "mcu68".equals(protocol) && moduleLoader; }

    /** Whether the module family has the bench firmware's symbol engine (POCSAG pages, M17 voice out): the HR-C7000 family, never an SA818 module. */
    public boolean symbolEngine() { return "mcu68".equals(protocol) && moduleLoader; }

    /** Whether the module tunes {@code hz}. */
    public boolean tunable(long hz) { for (int i = 0; i + 1 < bandsHz.length; i += 2) if (hz >= bandsHz[i] && hz <= bandsHz[i + 1]) return true; return false; }
    /** Whether this phone's radio tunes {@code hz} (the active profile). */
    /** The radio has the 2 m APRS frequencies (144.390 / 144.800 / 145.825 MHz); a UHF-only radio sends no automatic APRS acknowledgements (owner, 2026-10-30). */
    public boolean hasTwoMetre() { return tunable(144_390_000L) || tunable(144_800_000L) || tunable(145_825_000L); }
    public static boolean inBands(long hz) { return detect().tunable(hz); }
    /** The nearest tunable frequency from {@code hz} going up ({@code dir} > 0) or down, or {@code hz} itself when it is tunable; -1 when there is none that way. */
    public long nextTunable(long hz, int dir) {
        if (tunable(hz)) return hz;
        long best = -1;
        for (int i = 0; i + 1 < bandsHz.length; i += 2) {
            long edge = dir > 0 ? bandsHz[i] : bandsHz[i + 1];
            if (dir > 0 ? edge > hz && (best < 0 || edge < best) : edge < hz && (best < 0 || edge > best)) best = edge;
        }
        return best;
    }
    /** The bands for people: "136–174 and 400–480 MHz". */
    public String bandsText() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < bandsHz.length; i += 2) {
            if (b.length() > 0) b.append(i + 2 >= bandsHz.length ? " and " : ", ");
            b.append(mhz(bandsHz[i])).append('–').append(mhz(bandsHz[i + 1]));
        }
        return b.append(" MHz").toString();
    }
    private static String mhz(long hz) { return hz % 1_000_000L == 0 ? String.valueOf(hz / 1_000_000L) : String.format(java.util.Locale.US, "%.3f", hz / 1e6); }

    /** The same profile with other support flags (an advisory's restricted copy). */
    DeviceProfile copy(boolean supported, boolean verified) {
        DeviceProfile p = new DeviceProfile(name, controlPort, baud, audio, pcmPort, pcmBaud, powerNode, pttNode, powerOnToken, pttActions, stockApp, sepolicy, serialLib, supported, verified);
        p.id = id; p.revision = revision; p.source = source; p.txBlocked = txBlocked; p.restrictedBy = restrictedBy; p.globalSquelch = globalSquelch; p.lineSetup = lineSetup; p.scanDwellMs = scanDwellMs; p.protocol = protocol; p.bandsHz = bandsHz.clone(); p.rssi = rssi; p.softSqInModule = softSqInModule; p.appPlayback = appPlayback; p.fixedModuleVolume = fixedModuleVolume; p.subaudible = subaudible; p.dataSquelch = dataSquelch; p.moduleLoader = moduleLoader; p.camcorderGain = camcorderGain; p.softSqThresholds = softSqThresholds; p.softSqCloseCap = softSqCloseCap; p.rxDetectNode = rxDetectNode; p.rxLinesOn = rxLinesOn; p.muteLines = muteLines; p.rxLinesOff = rxLinesOff; p.txLinesOn = txLinesOn; p.txLinesOff = txLinesOff; p.gatewayLinesOn = gatewayLinesOn; p.gatewayLinesOff = gatewayLinesOff; p.txAudioScale = txAudioScale; p.toneEnergyMin = toneEnergyMin; p.gatewayVolume = gatewayVolume; p.minWriteGapMs = minWriteGapMs; p.directAudio = directAudio;
        p.bootMicInterrupt = bootMicInterrupt; p.txAudioLeadMs = txAudioLeadMs; p.minKeyGapMs = minKeyGapMs; p.analogVolume = analogVolume; p.stockAreaPrefix = stockAreaPrefix; p.stockDefaultArea = stockDefaultArea; p.stockCommands = stockCommands; p.txTestUnverified = txTestUnverified;
        p.txAudioOnSendStart = txAudioOnSendStart; p.txAudioTailMs = txAudioTailMs; p.politeAfterChannel = politeAfterChannel; p.smsUtf8 = smsUtf8;
        p.timeSlots = timeSlots; p.pttKey = pttKey;
        return p;
    }

    /** A profile from the signed profile list (same fields as the constants above). */
    static DeviceProfile fromJson(org.json.JSONObject o) throws org.json.JSONException {
        DeviceProfile p = new DeviceProfile(o.getString("name"), o.getString("controlPort"), o.getInt("baud"), Audio.valueOf(o.getString("audio")),
                o.optString("pcmPort", null), o.optInt("pcmBaud", 0), o.optString("powerNode", null), o.optString("pttNode", null), o.optString("powerOnToken", "1"),
                strings(o.optJSONArray("pttActions")), o.optString("stockApp", ""), strings(o.optJSONArray("sepolicy")), o.optString("serialLib", null),
                o.optBoolean("supported", true), o.optBoolean("verified", false));
        p.id = o.getString("id"); p.revision = o.getInt("revision"); p.globalSquelch = o.optBoolean("globalSquelch", false); p.scanSettleMs = o.optLong("scanSettleMs", p.scanSettleMs); p.scopeSettleMs = o.optInt("scopeSettleMs", p.scopeSettleMs); p.scopeReadMs = o.optInt("scopeReadMs", p.scopeReadMs); p.scanDwellMs = o.optLong("scanDwellMs", 1500); p.minWriteGapMs = o.optLong("minWriteGapMs", 0);
        org.json.JSONArray ls = o.optJSONArray("lineSetup");
        if (ls != null) { p.lineSetup = new String[ls.length()][]; for (int i = 0; i < ls.length(); i++) p.lineSetup[i] = strings(ls.getJSONArray(i)); }
        p.protocol = o.optString("protocol", "mcu68"); p.rxDetectNode = o.optString("rxDetectNode", null);
        org.json.JSONArray bands = o.optJSONArray("bandsHz");                // {low, high} pairs in Hz; default the HR-C7000 module's
        if (bands != null) {
            if (bands.length() < 2 || bands.length() % 2 != 0) throw new org.json.JSONException("bandsHz: pairs of low, high");
            p.bandsHz = new long[bands.length()];
            for (int i = 0; i < bands.length(); i++) p.bandsHz[i] = bands.getLong(i);
            for (int i = 0; i < bands.length(); i += 2) if (p.bandsHz[i] >= p.bandsHz[i + 1]) throw new org.json.JSONException("bandsHz: low below high");
        }
        p.rssi = o.optBoolean("rssi", true); p.rssiStepDb = o.optDouble("rssiStepDb", p.rssiStepDb); p.rssiOffsetDb = o.optDouble("rssiOffsetDb", p.rssiOffsetDb); p.softSqInModule = o.optBoolean("softSqInModule", false); p.appPlayback = o.optBoolean("appPlayback", false); p.subaudible = o.optBoolean("subaudible", true); p.dataSquelch = Math.max(0, Math.min(8, o.optInt("dataSquelch", 0))); p.moduleLoader = o.optBoolean("moduleLoader", true) && LOADER_CERTIFIED.contains(p.id);   // the list can switch it off, never on for an uncertified phone p.camcorderGain = Math.max(1, Math.min(32, o.optInt("camcorderGain", 12)));
        org.json.JSONArray sst = o.optJSONArray("softSqThresholds");
        if (sst != null && sst.length() == 9) { p.softSqThresholds = new double[9]; for (int i = 0; i < 9; i++) p.softSqThresholds[i] = sst.optDouble(i, 0); p.softSqCloseCap = o.optDouble("softSqCloseCap", 0); } p.fixedModuleVolume = Math.max(1, Math.min(8, o.optInt("fixedModuleVolume", 4)));
        p.rxLinesOn = pairs(o.optJSONArray("rxLinesOn")); p.rxLinesOff = pairs(o.optJSONArray("rxLinesOff")); p.muteLines = pairs(o.optJSONArray("muteLines"));
        p.txLinesOn = pairs(o.optJSONArray("txLinesOn")); p.txLinesOff = pairs(o.optJSONArray("txLinesOff"));
        p.gatewayLinesOn = pairs(o.optJSONArray("gatewayLinesOn")); p.gatewayLinesOff = pairs(o.optJSONArray("gatewayLinesOff"));
        p.txAudioScale = (float) Math.max(0.001, Math.min(1, o.optDouble("txAudioScale", 1)));
        p.toneEnergyMin = Math.max(1, o.optDouble("toneEnergyMin", com.macdmr.radio.dsp.CtcssDecoderBank.ENERGY_MIN));
        p.gatewayVolume = (float) Math.max(0.05, Math.min(1, o.optDouble("gatewayVolume", 0.4)));
        org.json.JSONObject da = o.optJSONObject("directAudio");
        if (da != null) p.directAudio = DirectAudio.fromJson(da);
        p.bootMicInterrupt = o.optBoolean("bootMicInterrupt", true); p.txTestUnverified = o.optBoolean("txTestUnverified", false);
        p.txAudioOnSendStart = o.optBoolean("txAudioOnSendStart", false); p.txAudioTailMs = Math.max(0, Math.min(1000, o.optLong("txAudioTailMs", 0)));
        p.politeAfterChannel = Math.max(-1, Math.min(1, o.optInt("politeAfterChannel", -1))); p.smsUtf8 = o.optBoolean("smsUtf8", false);
        p.timeSlots = o.optBoolean("timeSlots", true);
        org.json.JSONArray pk = o.optJSONArray("pttKey");                    // {input device name, key code}: compared with getevent output, never on a command line
        if (pk != null) {
            String[] k = strings(pk);
            if (k.length != 2 || !k[0].matches("[A-Za-z0-9 _.:-]{1,60}") || !k[1].matches("(KEY|BTN)_[A-Z0-9_]{1,30}|[0-9a-f]{4}")) throw new org.json.JSONException("pttKey: device name, key code");
            p.pttKey = k;
        }
        p.txAudioLeadMs = Math.max(0, Math.min(2000, o.optLong("txAudioLeadMs", 0))); p.minKeyGapMs = Math.max(0, Math.min(5000, o.optLong("minKeyGapMs", 0)));
        p.analogVolume = Math.max(1, Math.min(8, o.optInt("analogVolume", 8)));
        p.stockAreaPrefix = o.optString("stockAreaPrefix", p.stockAreaPrefix); p.stockDefaultArea = o.optString("stockDefaultArea", p.stockDefaultArea);
        // both end up in root shell command lines (cp, ls)
        if (!p.stockAreaPrefix.matches("[A-Za-z0-9_]{1,40}") || !p.stockDefaultArea.matches("\\w{1,40}")) throw new org.json.JSONException("stockAreaPrefix / stockDefaultArea: letters, digits and _ only");
        org.json.JSONArray sc = o.optJSONArray("stockCommands");
        if (sc != null) { p.stockCommands = new int[sc.length()]; for (int i = 0; i < sc.length(); i++) p.stockCommands[i] = sc.getInt(i) & 0xFF; }
        return p;
    }
    /** [[node, value], …] from the profile list; only /sys paths and short digit values (they end up on a root command line). */
    private static String[][] pairs(org.json.JSONArray a) throws org.json.JSONException {
        if (a == null) return null;
        String[][] out = new String[a.length()][];
        for (int i = 0; i < out.length; i++) {
            String[] p = strings(a.getJSONArray(i));
            if (p.length != 2 || !p[0].matches("/sys/[A-Za-z0-9_./-]{1,120}") || !p[1].matches("[0-9]{1,3}")) throw new org.json.JSONException("bad line " + i);
            out[i] = p;
        }
        return out;
    }
    private static String[] strings(org.json.JSONArray a) {
        if (a == null) return new String[0];
        String[] s = new String[a.length()]; for (int i = 0; i < s.length; i++) s[i] = a.optString(i);
        return s;
    }
}
