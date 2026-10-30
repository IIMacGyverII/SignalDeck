<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Packet radio

*On the intercom · 8 of 34*

APRS, APRS-IS, TNC, parrot, Morse, DTMF, weather alerts, SSTV.

## Mode pages

APRS monitoring (on your region's APRS frequency), ISS APRS, the KISS TNC, SSTV receive (also ISS SSTV) and the parrot each open their own full-screen page when they start; for an ISS pass the page also shows the pass (see ISS). The top of the page shows the frequency, whether the radio is listening, receiving or transmitting, the signal and how long the mode has run. APRS: counters (packets since it started, stations, messages for you, last heard), switches for auto-ack, the digipeater, automatic beacons and APRS-IS, and three lists: Live (every packet as it arrives, and what you send), Stations (distance and direction; tap to open in maps) and Messages (tap one for you to reply), with Message, Beacon, Map, Settings and Stop. SSTV: the picture building up, its progress, the pictures received (tap one to view, share or delete it; long-press one to select several, tap the others, then Share or Delete; Done ends it; Combine with two or more receptions of the same picture selected makes a cleaner one: the ISS repeats its slides, so receptions from different passes can be lined up and averaged, which removes much of the noise of a weak pass. A sheet shows the result first: a partial picture is drawn at the bottom, so its true height is unknown and a thin, noisy strip can fool the automatic line-up (it says when it is not sure); choose a picture, move it with the arrows (long-press: 8 steps) or leave it out, Automatic goes back to the automatic line-up, and Save keeps it as a new picture beside them), Send picture, switching between 144.500 and the current channel, and Stop. Parrot: what it is doing, how many it has repeated, and Stop. The back arrow returns to the intercom and the mode keeps running; a green bar above the tabs brings its page back, and PKT always opens the packet tools with a Running now row at the top: one tap to the page of each mode that is running (several can run at once, for example an ISS pass waiting and M17 receive). The mode's icon at the top right of a page opens all the packet tools too. FT8 and M17 receive have pages of their own as well (see FT8 over FM and M17 receive; M17 is under Digital voice in the packet tools). Opening the app while a mode runs shows its page first.

## APRS

Every analog channel is decoded passively, below the squelch too (a damaged packet fails its checksum, so noise never shows as one): positions, messages, weather and objects go to Stations and the log. While a data mode runs (APRS monitoring, SSTV, FT8, M17) its decoders hear everything the radio receives and the speaker and SignalDeck Web stay quiet; turn MON on to listen along. Monitor parks the radio on your region's APRS frequency (Device → Region: 144.390 MHz in North America, 144.800 MHz in most of Europe, Africa and the Middle East; the button names it), acknowledges messages addressed to your callsign and can act as a fill-in digipeater. On a radio without a 2 m band (UHF only, such as the ANYSECU W6 phones and the Atom XL) messages are never acknowledged automatically, whatever the Auto-ack setting says; the APRS page shows "No auto-ack (UHF)". Send message and Position beacon transmit from your callsign, set in APRS settings: the callsign alone in its box (letters and digits, up to six) and the SSID in its own (typing CALL-7 in the callsign box is split for you). Packets are real AX.25 frames built each time from your callsign, path and text, sent as 1200-baud AFSK: a 1.6-second steady tone while the transmitter settles, then the packet twice. To the ear every packet sounds alike, so use a decoder (another radio with APRS, or APRSdroid listening through a speaker) to check what went out. Packet, DTMF and SSTV need an analog channel; on a DMR channel they are refused. For your packets to reach aprs.fi a digipeater or iGate has to hear them on your region's APRS frequency: use high power and check the raw packets page for your callsign. While APRS monitoring, a satellite pass or another mode runs, the app keeps the phone awake (and exempts itself from battery optimisation) so timed beacons and pass tracking keep going with the screen off; automatic beacons switch the GPS on so each one carries a fresh position. The packet decoder band-passes the audio and runs five slicers side by side at different tone balances, so it also copes with radios whose audio makes 2200 Hz much louder or quieter than 1200 Hz (on direwolf’s standard noise benchmark it decodes more than direwolf itself), and it repairs a packet with a single wrong bit. APRS settings → Transmit power chooses High (default) or Low on the frequencies the app tunes itself (APRS monitoring, ISS, SSTV 144.500); messages and beacons sent from your own channel use its power. APRS settings → Packet lead-in sets what is sent before each packet while the radio’s transmit audio settles: flags (the standard AX.25 TXDELAY, default), a steady tone or silence, and its length (1.6 s by default; shorter values are for testing).

## APRS-IS

Connects to the internet APRS network. With your passcode it gates what the radio hears, beacons your position online and delivers internet messages addressed to you; without a passcode it only receives.

## Two-way iGate

Settings → Two-way iGate also sends internet messages out on the radio: when someone on the internet writes to a station this radio heard in the last 30 minutes (and the sender is not local too), the message goes out once as a third-party packet via WIDE1-1, and the station's answer comes back to the internet through the normal gating. It needs APRS-IS with your passcode and the radio on your region's APRS frequency (Monitor); PKT shows how many were sent.

## Automatic beacons

Settings → Beacon: Off, Every N minutes, or SmartBeaconing, which beacons every 30 minutes when parked, more often the faster you go (every 3 minutes above 60 mph) and once more when you turn a corner, so your track looks right on the map without filling the channel. Automatic beacons, telemetry and weather reports only go out while the radio is on your region's APRS frequency (Monitor) and no TNC client owns the radio.

## Telemetry and weather

Telemetry sends the phone's battery level, battery voltage and temperature, air pressure and the received signal as APRS telemetry; the first report and every tenth also send the names, units and scaling, so APRS sites draw graphs. Weather sends a weather report at your position with the barometer's air pressure, converted to sea level from your altitude. Both are buttons in PKT and can run every 30 minutes (Settings).

## Objects and bulletins

Object places a marker that you own on everyone's APRS map, such as a net control point, an event or a hazard, at your position or one you type; Remove deletes it from the maps. Bulletin sends a short notice to everyone in range (BLN0 to BLN9); Bulletins lists those heard, including NWS announcements.

## Text and email gateways

Send message → Text a phone or Send an email reaches the internet through APRS: SMSGTE texts a US or Canadian phone ("@number message"), EMAIL-2 emails an address ("address message"), 67 characters in all. It needs an iGate within reach of your signal, or APRS-IS. Replies from a phone come back as APRS messages when SMSGTE knows your callsign.

## Message groups

Settings → Message groups: messages addressed to a group name you follow (for example CQ or ARES) notify you like a message to you, without an acknowledgement.

## Satellite passes

PKT → Satellite passes lists, for ISS APRS, ISS SSTV, the ISS repeater, ISS voice, SO-50, PO-101 and the other FM satellites people work (AMSAT’s FM list, with frequencies and tones from SatNOGS, sorted by the next pass), the passes over you in the next 24 hours that climb above 10 degrees: when, how high, how long and where they rise and set. Orbits come from AMSAT (CelesTrak if AMSAT is down) and refresh after two days; Update orbits fetches them now. Tap a pass to track it: a minute before it rises the VFO takes the satellite's listen and talk frequencies with its access tone, corrected for Doppler (up to about 10 kHz on 70 cm) and retuned whenever the error passes 2 kHz, never while you are talking. After it sets the radio goes back to your channel. The sheet shows the satellite's direction and height and the frequencies live; Stop tracking ends it early. Higher passes are the strong ones; on SO-50 send a 74.4 Hz tone for two seconds first to switch its repeater on. Tracking an ISS APRS or ISS SSTV pass opens its page.

## Is it working?

Under each satellite's name: AMSAT’s reports of the last 4 days from operators who listened to it (heard, telemetry only, not heard, and when it was last heard): green when people hear it, amber when only its telemetry is heard, grey when it seems off. Satellites switch off, fail or change schedules, so check this before a pass. Update orbits downloads the orbits and the FM satellite list again (it also refreshes by itself every few days); nothing about you is sent. SignalDeck Web’s Satellites page shows the same.

## VHF/UHF conditions

PKT → VHF/UHF conditions (and SignalDeck Web’s Satellites page) answers “is it worth getting on the air beyond the usual range?”. Tropo: the next 48 hours, hour by hour, from the weather forecast’s temperature and humidity near the ground (Open-Meteo; your position is rounded to about 50 km first): grey normal, cyan slightly enhanced, green super-refraction (signals bend back toward the ground: longer range), amber ducting likely (hundreds of km). The forecast’s layers are a few hundred metres apart, so thin ducts right above the ground are underestimated; calm, clear nights after a warm day are the classic time. Aurora: NOAA’s Kp index now and forecast, and the chance of aurora overhead (NOAA’s map is downloaded whole and read on the phone). VHF aurora needs about Kp 5 at mid latitudes: point north, expect a buzzing sound. Meteor showers: the next major ones; each trail reflects 2 m for a moment, 800 to 2000 km, best before dawn. And the next satellite passes above 20°.

## ISS

The space station's packet digipeater is on 437.825 MHz (70 cm) since ARISS moved it in 2026, and its SSTV picture events use 437.550. On 70 cm a pass shifts the signal by up to 10 kHz (Doppler), more than an FM channel tolerates, so PKT → ISS APRS and ISS SSTV do not just park on the frequency: they wait for the next pass above 10° (or the one in progress), tune a minute before the ISS rises, follow the shift every couple of seconds and come back to your channel when it sets, then wait for the next pass, until you stop. This carries on after SignalDeck restarts (an update, a reboot): it picks the ISS up again by itself. Their page shows the countdown, then the ISS's height and direction, the corrected frequency and when it sets. Fifteen minutes before a pass, orbits more than a day old are downloaded again, so a reboost of the station does not throw the pass times or the Doppler off. With Satellite passes → Record each pass on (the default), the whole pass is saved as a WAV in Download/DMR/Audio/\<satellite\> (about 1 MB a minute, below the squelch too), so a picture or packet that was missed can be decoded again later. Until a minute before the pass the radio stays on your channel (you can keep using it), so the top of the page says WAITING FOR THE PASS, shows the pass frequency dimmed and the time it tunes; an ISS SSTV session starts with an empty picture. ISS APRS decodes what the station digipeats; Beacon on its page (or PKT → Beacon via ISS) sends your position through it with the path ARISS, with both frequencies corrected, and only while the ISS is in view. ISS SSTV is receive only (transmitting is blocked) and saves each Robot 36 picture; during a picture the retune waits until the error reaches 5 kHz so the picture is not torn. ARISS changes the station's modes often: Satellite passes → ISS frequencies holds them (APRS, SSTV, repeater, crew voice) with a link to the ARISS status page. The ISS and satellites zone list has fixed + / plain / - channels too.

## KISS TNC

Serves APRSdroid or YAAC on 127.0.0.1:8001 on whatever channel you are on, sending and receiving. The app sends complete frames and the radio transmits them as they come (each with the same 1.6-second settling tone first); tune an analog APRS channel first.

## DTMF

Keys heard on any analog channel are logged. Send a sequence from the keypad or from your saved sequences; saved ones are kept in backups as DTMF.csv.

## DTMF remote control

PKT → DTMF → Remote lets another radio control this phone: turn it on and set a PIN of 4 to 6 digits. From the other radio, key the PIN and one command digit as one sequence (a \* before and # after are fine): 1 says the position (nearest town and coordinates), 2 says the status (battery, channel, signal), 3 sends an APRS position beacon, 4 and 5 turn the parrot on and off, 6 sends the Morse ID. The phone answers on the channel 1.5 seconds after the caller lets go, with your callsign in Morse after a voice reply on amateur channels. Three wrong PINs within ten minutes lock it for ten minutes, and the PIN is never written to the history or the DTMF log. It is off by default.

## Weather alerts

NOAA SAME alerts are decoded automatically on the weather frequencies (162.400 to 162.550 MHz) and raise a notification. Where your region has no NOAA-style weather radio (Device → Region), the PKT sheet shows only a Show NOAA weather alerts button and the weather lists sit under Show all; the decoder itself still runs.

## SSTV

Receive pictures on 144.500 MHz or on the current channel, and send a picture from your gallery. Short modes: Robot 8 BW, 12 BW and 24 Color. Long modes, marked long in the picker: Robot 36, Martin M2, Scottie S2, PD 90, Scottie S1 and Martin M1 (up to about 115 s); they fit the default 2-minute transmit limit (Device → Longest transmission), and a mode longer than the limit you set is refused with the reason. After a long transmission the phone warms up (a 120 s key raised the radio's thermal zone by about 11 °C on the bench), so the next long picture waits until that zone is back within 1 °C of where it was, about 17 minutes at most; the send button tells you how long, the history says why, and a short mode can go meanwhile. A long picture also does not start while the radio zone is already at 60 °C or the battery at 45 °C, and while it runs the phone is read every five seconds: past 70 °C on the radio zone, 48 °C on the battery, or a 2 °C climb in ten seconds, the transmission is ended early and the history says so. These sit below where the phone itself slows down; the bench used stricter numbers. Two decoders, switched on the SSTV page: Any mode recognises Robot, Martin, Scottie and PD pictures from the VIS header that starts every transmission. Robot 36 continuous works like MMSSTV: it needs no header, it locks on the line sync pulses, so a picture picked up halfway is still drawn (and moved to the bottom, where its last line belongs), it keeps the line rhythm through fades instead of losing lines, it reads each line’s colour from its separator tone, and the screen shows the static until a picture comes in. ISS SSTV always uses it (ISS events are Robot 36). Pictures, and partial ones of 16 lines or more, are saved to Download/DMR/SSTV.

## Decode a recording

PKT → SSTV or APRS → Decode a recording (also at the bottom of the SSTV and APRS pages) finds the pictures or packets in a recording after the fact: a pass recording (Satellite passes → Record each pass), a REC recording, or one from anywhere with Upload a recording… (WAV, MP3, M4A/AAC, OGG/Opus or FLAC, any sample rate; it is kept in Download/DMR/Audio/Uploaded so it is listed next time). SSTV finds every mode SignalDeck receives from the header that starts each picture (Robot, Martin, Scottie, PD and more), plus Robot 36 pictures whose header was lost in a fade on a pass; a picture found both ways is saved once. Pictures go with the received ones, named after their mode and the time they were on the air (…\_rec.png; \_partial when a fade cut it short). APRS lists every packet with its time and writes the list next to the recording (.aprs.txt). It runs in the background, much faster than the recording plays (a ten-minute pass takes seconds), on its own decoders: the radio, the speaker and live reception are not touched, and nothing is sent, digipeated or gated. SignalDeck Web has the same on its SSTV page.

## RTTY and PSK31

PKT → RTTY and PSK31 are the keyboard chat modes of fldigi and other sound-card programs, used by clubs and emergency groups. Pick RTTY 45 (45.45 baud, 170 Hz shift, Baudot) or PSK31 and Listen: the decoder finds the signal anywhere in the audio and follows it, and prints only while a clean signal is there, not static. Type a line and Send to answer on the frequency being received (or on 2125 Hz mark for RTTY and 1000 Hz for PSK31 when nothing is). Long text is split into several transmissions of about 26 seconds each. Capital letters are slower in PSK31 than small ones. On the bench firmware (FW22 or later) RTTY goes out through the module's tone generator (clean mark and space tones from the modulator); PSK31 keeps the audio path, since its phase reversals are not tones.

## Packet terminal

PKT → Terminal connects to a packet bulletin board (BBS), node or another station's terminal on the current channel with AX.25 connected mode, the way a TNC does: enter the callsign (with its SSID, for example N0ABC-1), optionally up to two digipeaters, and Connect. Type a line and Send; what the other side sends appears below. Every transmission starts with this radio's 1.6-second settling tone, so each exchange takes a few seconds; frames that get lost are sent again, up to six tries 15 seconds apart before the link is given up. Incoming: accept lets other stations connect to you (you get a notification). It needs your callsign in APRS settings.

## Simplex repeater (parrot)

PKT → Parrot on this channel records each transmission heard on the current channel and sends it back a second after it ends, up to 26 seconds. A station can check how it sounds, or two stations that cannot hear each other can talk through you. Very short key-ups are not repeated. On amateur channels, with your callsign set in APRS settings, the playback ends with your callsign in Morse at most once per station-ID interval (ten minutes, or your region's: Device → Region). The squelch must close on silence (a squelch level above 0, or Soft SQ), or the parrot would repeat static. It stops when you change channel; each repeat is listed in the channel history.

## Morse trainer

PKT → Morse trainer teaches Morse code with the Koch method on the phone's speaker (nothing is transmitted). Each lesson adds one character in the usual LCWO order, starting with K and M. Play 5 groups sends five groups of five characters at the character speed with Farnsworth spacing; type what you copied and Check: at 90 % or better the next lesson opens. Set the character speed (5 to 35 WPM, 20 is a good start) and the spacing speed with the - and + buttons; your lesson and speeds are remembered. Sending practice: tap and hold the KEY for dits and dahs; after a short pause the app's Morse decoder shows what it read and your speed.

## Voice keyer

PKT → Voice keyer holds three recorded messages (named CQ, Net preamble and My ID at first; tap a name to rename it). Record, speak, Stop: up to 25 seconds from the phone's microphone, with silence at the ends trimmed and the level evened out. Listen plays it on the phone; Send transmits it on the current analog channel after a 1.5-second lead, with your callsign in Morse after it when ID is on and the channel is an amateur one.

## ID timer

Amateur rules ask for your callsign at intervals during a contact and at its end: every ten minutes in the US; Device → Region sets your country's interval where it is known (for example five minutes in the Netherlands), and you can change it there. Amateur channels are those inside your region's amateur bands. On them the timer starts with your first transmission after you last identified; once the interval has passed the phone vibrates and reminds you as you key (unless Morse ID after I talk does it for you). Any Morse ID restarts it; after saying your callsign, tap I just identified in the Voice keyer. PKT shows the time left.

## Morse

Morse code heard on an analog channel, such as a repeater's identification, is decoded and written to the channel history with its tone and speed; when it contains the callsign in the channel's name it is shown as the repeater's ID, confirming you hear the repeater you programmed (5 to 40 words per minute; Settings → Morse decoder turns it off). It needs a clean keyed tone: voice, static and DTMF are ignored, and a message shows once it has ended and contains a callsign (or at least eight characters of text), so stray tones are not taken for Morse. Morse decoding pauses while packet audio (APRS and other AFSK packets) is being received and for 2 seconds after it, because packet tones through the radio can look like keyed Morse; a Morse ID sent within those 2 seconds is not decoded. Send my callsign in Morse transmits your APRS callsign on the current channel. Settings → Morse ID after I talk adds it at the end of your transmission on amateur channels (2 m, 1.25 m, 70 cm) at most every ten minutes, the identification amateur rules ask for; it goes out like the roger beep, before the radio unkeys, and is skipped when the transmission ran to within 3 seconds of the transmit limit (the radio's own timer would cut it). On the bench firmware (FW22 or later) the Morse ID goes out through the module's own tone generator instead of the phone's audio path, so it needs no 1.5 s of lead and is not shaped by the audio path's filters; on stock firmware it goes through the audio path as before.

## The PKT key

PKT lights while APRS monitoring, SSTV receive, the TNC or the parrot is running.

[All topics](README.md)
