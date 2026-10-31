<!-- Generated from app/src/main/assets/guide/en.json by HelpExportTest. Do not edit by hand. -->

# Android Auto

*Pages · 31 of 34*

The radio on your car's screen.

## In the car

Connect the phone to Android Auto and the radio appears among the media apps (SignalDeck): on the car's app list (it may be on a later page) and as the media card on the car's home screen; tap the card for the full media screen. Press play there (or on the card) to hear the radio through the car's speakers: the radio then becomes the car's media source, like any music app, and pause hands the speakers back. Its card and media screen show a small version of the intercom panel: the zone (when the channel is in it), the channel digits (red while you transmit), frequency, mode chips, a state badge (receiving, transmitting, scanning, ready, paused) and a live signal meter. During a DMR call the chips become the talker's callsign and the talkgroup (or Private call), and the subtitle reads who is talking with their DMR ID and the talkgroup (for example N0CALL Demo Operator · 9999999 · TG 91); Radio controls shows the same on its Talking line; the channel list shows each channel's number tile. The radio keeps its connection to the radio module on the phone; Android Auto only shows and controls it.

## Choosing a channel

On the media screen, the arrow at the top left opens the channel list: Last heard first (who and what was received, newest first; tap one to tune that channel), then a tab for each zone (including Favourites once you have starred channels), with More holding the rest and All channels. Tap a channel to tune it. In Radio controls the Channels button lists the same. Previous and next, also on the steering wheel, step through the zone. The search button finds a channel by name, number or frequency, and "Hey Google, play NOAA WX6 on SignalDeck" works too.

## Talk

The microphone button on the media screen transmits: tap to start, tap again to stop; it ends by itself at the transmit limit (Device → Longest transmission, 2 minutes unless you change it). Receive-only channels refuse it. With Device → Car microphone in Android Auto on (the default) and the car connected over Bluetooth too, the car's own microphone is used through its hands-free link (the link takes a second to come up before the radio keys); otherwise the phone's microphone. The phone's side PTT key works as always.

## Radio controls (big Talk button)

Android Auto gives media apps only small buttons, so the radio also has its own car screen. It is off by default: turn on Device → Full-screen Radio controls in the car, then reconnect the car, and open Radio controls from the car's app list. It shows the live panel, the channel and its status, two wide buttons, TALK (green; red with STOP TALKING while you transmit) and Channels, and arrows at the top right for the previous and next channel. Channels lists Favourites first (your starred channels; until you star some it says how), then every zone and All channels; tap a channel to tune it. Inside a zone, Scan at the top scans exactly that zone (Stop scan ends it). While it is on, Android Auto lists it instead of the media app; the radio's media card, steering-wheel buttons and lock-screen controls still work. If the car does not list it, see If the radio is missing. The radio history records what Android Auto did (connected, opened the media app or Radio controls, took the sound away), so a drive can be checked afterwards. Talk works as on the media screen, with the car microphone when available.

## Scan, pause

The scan button starts and stops a scan of the zone. Pause mutes the radio's sound; it keeps receiving, decoding and logging underneath, and play brings the sound back. Pressing play in the car makes the radio the car's media source (other media pauses).

## How it sounds

When you are not playing the radio as the car's media, anything it receives still comes through the car speakers like a navigation prompt, with the car's music turned down meanwhile; the navigation volume sets its loudness.

## Messages read aloud

Incoming DMR texts, APRS messages (over the air and APRS-IS) and NOAA weather alerts arrive as conversations: on the phone, and in the car Android Auto announces them and reads them aloud. Reply by voice and the answer goes back the way the message came: a DMR text to that ID or talkgroup (needs a DMR channel tuned), an APRS message over APRS-IS when it came from there, otherwise over the air on the current channel. Weather alerts have no reply. Diagnostics → Post a test car message shows what it looks like (its reply is never sent).

## Coverage while you drive

With Device → Log coverage in the car on (the default), coverage logging starts when Android Auto connects and stops when it disconnects (unless you had switched it on yourself), so drives fill in the coverage maps by themselves.

## Next as a talk key

Device → Steering-wheel Next talks (tap to choose Off, Hold or Double-press; Double-press by default, only while Android Auto is connected). Hold: hold the wheel's Next button for half a second and the radio transmits until you let go; some cars send the release late or not at all, so if it keeps transmitting, press Next once more to stop. A short press still goes to the next channel. Double-press: press Next twice quickly to transmit and once to stop; a single press goes to the next channel after half a second. Double-press uses only presses, so it works even where releases are unreliable. Both need the car to pass the button itself to the app: some cars send Next only as a command, and the history then says so. Steering-wheel microphone chooses the microphone for transmissions started from the wheel. Car (as a call, the default): the car's microphone works through its hands-free link, and while that link is open the car treats the wheel as call controls, so a wheel start becomes a short "call" (SignalDeck: talking) and the wheel's hang-up button or End on the car screen stops the transmission; it never appears in the call log. If the car does not open its microphone within 4 seconds, that transmission uses the phone's microphone and the history says so. Phone: the phone's microphone, and the wheel's Next or play/pause stops as usual. Stop when I stop talking (Off, 1.5, 2.5 or 4 s; 1.5 s by default): for wheel-started transmissions, the radio unkeys once you have been quiet that long (or after 6 seconds if you never speak), measured against the road noise. With a mode on, the history also shows the first wheel presses of each drive.

## Steering-wheel Talk

Device → Steering-wheel play/pause talks (off by default): in Android Auto the wheel's play/pause button starts transmitting and a second press stops (the transmit limit as always). The play/pause button on the car screen still pauses the radio's sound. Only the wheel's media buttons reach an app (play/pause, previous, next): the voice button belongs to Google's assistant (it needs the Google app enabled on the phone; "Voice commands are not available right now" means it is not) and the phone button to the car's dialer.

## Last heard

In Radio controls → Channels, Last heard lists who and what was received, newest first, with the time and channel (and talkgroup for DMR). Tap one to tune that channel.

## If Android Auto drops while you talk

With the phone on a USB cable, the radio's own transmission can get into the cable and reset the phone's USB port: Android Auto disconnects about a second after you start talking and reconnects when you stop, and the phone stops charging meanwhile. It does not matter which Talk button you use. It depends on the frequency, not on where the antenna is (the cable itself picks up the signal): in one car a 2 m repeater transmitting on 144.770 MHz at high power dropped it every time while 146.370 MHz did not. The app notices it: the radio history notes the channel and frequency and the phone shows a notification. What helps: a snap-on ferrite choke on the cable close to the phone, a shorter, better-shielded cable, Low power for that channel (Channels → edit the channel), or wireless Android Auto if the car has it.

## If the radio is missing

Android Auto only trusts apps installed from the Play Store. For this app it plays the sound and the steering-wheel buttons work, but SignalDeck (and Radio controls) is missing from the car's app list, and tapping its card on the car's home screen opens the app on the phone instead of the car's media screen. The app checks this setting (with root): while it is off, the Device page shows Android Auto is hiding this app (tap it for the steps and a button that opens Android Auto's settings), and when the car connects the phone gets a notification and the radio history notes it. To fix it, allow Unknown sources once: on the phone open Android Auto's settings, tap the version number at the bottom ten times to unlock the developer settings, then in the ⋮ menu choose Developer settings and turn on Unknown sources. Disconnect and reconnect the car. Android Auto's desktop test head unit does not check this, so the radio appears there either way. Once, with Unknown sources already on, Android Auto connected without loading the radio at all (same symptoms); unplugging the cable and plugging it in again fixed it. The app notices this too: if Android Auto has not loaded the radio 30 seconds after connecting, the radio history and a notification say to reconnect.

[All topics](README.md)
