# ANYSECU W6Plus DMR (V69): findings, 2026-10-29

The W6Plus with DMR. It has the W6+'s phone body and an Armor-family DMR module. Sources:
- the device report, run on the unknown phone with `scripts/w6/run_checks.sh`;
- the stock app `com.flyscale.dmr` (FlyscaleDmr400), read with the SDK's dexdump;
- receive-only checks on the phone. Nothing was keyed, and no firmware was loaded.

**Never read the `/sys/devices/virtual/misc/flyscale_misc/*` attributes on this family.** On the W6+, two of them panic the kernel. The stock app here doesn't use them; it only uses the GPIO and `/sys/kernel` files below.

## Phone

| What | Value |
|---|---|
| Model string | `V69` (manufacturer `UNISOC`), device `sl8541e_3h10_32b`, product `sl8541e_1h10_32b_Natv` |
| Build | `V69_V1.1_DMR_JG_FT13_R_202607301448`, Android 12, 32-bit only |
| Stock radio app | `com.flyscale.dmr` (priv-app `FlyscaleDmr400`, uid 1000) |
| Root / SELinux | Magisk. SELinux is permissive on this unit; the profile still carries its sepolicy lines (`console_device`) |

## Module and protocol

- **Port:** `/dev/ttyS2` at 57600 8N1, raw. `stty` sets the speed on this port, so no vendor library is needed.
- **Version reply (0x34):** `DMR006.U4A.V140`. This is the Atom XL's module family (`DMR006.U4A.V067`), here on a plain UART without the Agold envelope.
- **Command set:** the Armor's 68 … 10 frames. The stock app's `CMD` enum is 0x22 SET_DIGITAL … 0x39 QUERY_MODULE_CONSISTENCY, name for name; the 0x36 status pushes are acknowledged as on the Armor. SignalDeck's `RadioEngine` drives it unchanged.
- **Module image** bundled in the stock app (recorded only, never loaded): `DMR-006-HHT-400M-V140-MC0-RESP1-20260708.bin`, 307,848 bytes, SHA-256 `268f1130ddbd50cea0336a0fa941704c31c494c7041b403ffdc4238cf959bb2b`. The stock app also has a YModem class. The profile sets `moduleLoader = false`, so the loader and the symbol engine are off.
- **Bands** (0x23 writes, nothing keyed):
  - 400.000, 446.000, 470.000 and 480.000 MHz are acknowledged (sr=0).
  - 399.995, 395, 136 and 174 MHz are never answered, so it is UHF only.
  - 480.005 and 485 MHz are acknowledged too, but receive there is unmeasured.
  - The profile keeps 400–480 MHz, the stock app's own limit.
- **Signal readings (0x32):** they work. About −96 to −99 dBm idle, and −82 dBm with a HackRF carrier.

## Phone lines

From `SpeakUtil` in the stock app:

| Name in the stock app | Node | Use |
|---|---|---|
| `dmr_pwd` | gpio322 | module power (`openModule` writes 1) |
| `dmr_ptt` | gpio188 | PTT |
| `dmr_mic_en` | gpio222 | microphone to the module |
| `m6_mic_en` | gpio179 | built-in (0) / headset (1) microphone |
| `spk_dmr_en` | gpio183 | speaker route from the module |
| `dmr_spk_en` | gpio189 | read as "receiving" |
| `audio_pa_en` | `/sys/kernel/ext_pa_en/ext_pa_en` | amplifier |
| `dmr_state` | `/sys/kernel/dmr_state/dmr_state` | receive state |
| `relay_state`, `relay_switch`, `m6_*`, gpio181/212/223/237/249 | | gateway relay and the second (POC) path, not used |

- **Receive:** `startGroup` and `stopGroup` run on the module's RECEIVE_START and RECEIVE_STOP pushes. They switch `dmr_state` 1, the amplifier off, gpio183 1, then the amplifier on; on stop, the reverse. These are the W6+'s receive lines.
- **Transmit:** `startSpeaker` and `stopSpeaker` switch the mic (gpio222 1, gpio179 0), then PTT gpio188. **The stock app never sends 0x26**, so PTT is the line. Transmit is untested and off in the profile.
- **Side key:** `fly.privkey.poc.down/up` (as on the W6+), plus `fly.privkey.ptt.long`. Not captured yet.

## Receive audio

HackRF on 446.000 MHz, VFO, module volume 8:
- The audio arrives on the camcorder input, exactly while the module reports a reception, played by SignalDeck through the gate as on the W6+.
- A 1 kHz tone at 2.5 kHz deviation reads −32 dBFS raw, 12 dB louder than on the W6+. The profile's `camcorderGain` is 4 (the W6+ keeps 12).
- Static with MON open reads about −27 dBFS raw.
- The path passes up to about 4 kHz: unfiltered TTS speech fills the noise-squelch band the way static does. Speech low-passed at 3 kHz, as a transmitter does, reads 2.5–6.7 on the detector at gain 4, and static reads 128–231. So the built-in Soft SQ table works: static never opens it, and transmitter-like speech opens it at every level.
- 62.5 Hz CTCSS arrives about 40 dB down, still 44 dB above the floor, like the Armor's.

## Stock channels

`channel_list_detial.db` holds 128 slots:
- `CHANNEL_LIST` gives each slot's type: 1 analog, 0 digital.
- `ANALOG_CHANNEL_INFO` and `DIGITAL_CHANNEL_INFO` hold the module's 0x23 and 0x22 bytes as hex text. The DMR ID, the call number and the 32-entry group list are little-endian.
- `config.xml` holds `channel_index` (the slot in use) and `dmr_version`.
- Factory channels: 405.525 MHz upwards in 0.5 MHz steps, CTCSS 62.5 Hz both ways, squelch 3.
- `ChannelStore.importFromFlyscaleDmr` imports them, with the DMR ID from the slot in use.

## What the device report got right, and what it missed

- **Right, unaided:**
  - the stock app (watched);
  - protocol `mcu68` (0x34 answered);
  - `/dev/ttyS2` at 57600 (the line settings while the stock app ran);
  - the 68 … 10 frames in the serial sniff.
- **Missed:**
  - **Power and PTT lines:** the stock app names them in static fields (`dmr_pwd` = gpio322 …), and the report didn't read those pairs.
  - **A misleading protocol hint:** the code-clues line said "SA818-class AT commands". This app's code also has a few AT+DMO strings, while the serial watch decided correctly.
  - **Audio:** unknown, as expected for a camcorder-input phone.

  These go into a separate report PR.

## Transmit (owner-authorized test, 2026-10-29)

Setup:
- A local test build with transmit allowed. The profile stays untested until the owner confirms.
- 446.000 MHz, low power, bench DMR ID 1234568, keyed through SignalDeck Web PTT.
- HackRF receive-only captures at 2.4 MS/s, analysed with phonedmrapp `tools/dmr-replay/iqan_tx.py`.

Results:
- **Keying:** the PTT line gpio188, with the mic lines, keys the module, which reports status 3 SEND_START and 4 SEND_STOP. No 0x26. Every key ended on release.
- **FM:** carrier 27.5 dB over the HackRF floor. Deviation p99 about 1 kHz with room sound: the microphone is the only transmit audio, so Morse, APRS or tones from the app are not possible on this phone.
- **DMR:** two 6 s keys decoded identically:
  - MS-sourced direct mode; 3 voice LC headers 60 ms apart, 16 voice syncs 360 ms apart, a terminator with LC;
  - colour code 1, group call, destination TG 9, source 1234568;
  - outer symbol deviation 1815 Hz (nominal 1944).
- **Watchdog:** PTT held for 30 s; SignalDeck released at 28 s (28.24 s of carrier).
- **Heat:** battery 42.1 to 42.8 °C over the session, CPU zone 51–53 °C.
- **Effect on other radios:** a W6 key desensitises a nearby radio on another UHF frequency (the Atom XL on 401.025 MHz read lower RSSI during each key), but does not open it.

**Carrier offset: about +1.6 kHz on every key, FM and DMR alike** (+1598, +1575, +1604 Hz). This is the W6's and the HackRF's crystal errors together. The Armor 26 Ultra with the same HackRF reads +0.8 to 1.0 kHz, so the W6 is probably 0.6 to 0.8 kHz high (about 1.5 ppm), but only a frequency reference can separate the two. It is inside what DMR receivers usually tolerate. **Flag it for narrow or strict DMR receivers and hotspots:** an MMDVM hotspot may need its RX offset adjusted, or show a high BER, for this radio.

## Still open

- DMR voice and text receive. This needs a DMR source in the cage: the hotspot, or a recorded key replayed by the HackRF.
- The owner's listening check: the speaker plays FM reception and volume 0 is silent.
- The side-key capture.
- The owner's decision on `verified` and an automatic take-over (transmit test above), and a voice key heard by ear on another radio.
- A Soft SQ level sweep with 3 kHz-filtered speech at several HackRF levels.
