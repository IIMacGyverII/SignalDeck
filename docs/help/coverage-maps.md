<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Coverage maps

*Pages · 30 of 34*

Where your repeaters really work, measured as you go.

## What it does

With coverage logging on, the app measures each repeater wherever you hear it and checks whether it answers when you transmit. Over time the Map shows two pictures: where the repeater reaches you, and where you reach the repeater. You just use the radio normally; walks and drives fill the map in.

## Turn it on

Device → Coverage logging, or Map → the violet Coverage pill → Log coverage (the same setting). It uses GPS while it is on, and keeps working with the screen off once you have switched it on with the app open. While it is on, the radio notification says Mapping coverage. Switch it off the same way. Everything stays on the phone. On a radio without signal readings (the ANYSECU W6+) only key-ups are recorded; the Heard layer stays empty.

## Which channels

Channels that have a location: the repeater's position, which channels added from Nearby repeaters get automatically (or set it in the channel editor → Location). Key-ups are judged only on repeater channels, where the transmit and receive frequencies differ.

## Heard: repeater to phone

Each time you hear the repeater, the signal strength is stored with your GPS position (only fixes better than 50 m, at most one reading every 5 seconds unless you moved 25 m). On DMR the radio reports a call only with the channel's colour code; on analog with a CTCSS tone the app checks the tone, so other stations on the frequency are not counted. The map shows 150 m squares coloured by the typical reading, from blue (barely heard, about S3) through green to red (S9+30).

## Key-ups: phone to repeater

After each transmission of more than half a second on a repeater channel, the app reads the signal five times in the next two seconds. A repeater keeps transmitting for a moment after you let go (its tail or hang time); if that signal stands 8 dB above the channel's quiet noise floor, you keyed it from there (green). If nothing comes back it is a no reply (red). The Last heard history notes each result.

## Driving with a steady signal

While a station keeps transmitting, a reading is stored every 2 seconds as long as you keep moving (every 5 seconds when you stand still), so a drive draws a continuous track: at 60 mph about one reading every 55 m.

## NOAA weather: the best way to start

NOAA Weather Radio channels (162.400 to 162.550 MHz) transmit all the time, so on them every reading is stored, heard or not: weak spots and dead zones show up in blue instead of staying empty. Give the NOAA channel its transmitter's location (channel editor → Location), select it, turn on logging and drive around: the map shows that transmitter's real coverage, and the prediction calibrates itself from it.

## On the Unihertz Atom XL

Coverage logging reads the signal during receptions, because that is what it maps. On this phone, whose radio sits behind a USB bridge chip, readings during a reception may cut its audio for a few seconds (SignalDeck restarts the bridge by itself); the stored readings are not affected.

## While scanning

Logging carries on during a scan: whenever the scan stops on a channel that has a location and hears it, the readings are stored for that repeater. Each channel keeps its own quiet noise floor across scan visits, so a transmission after the scan stops on a repeater is judged too.

## Key-up test

Coverage sheet → Key-up test here: on an FM repeater channel it transmits your callsign in Morse (about 3 seconds at 20 words per minute, your station identification) and records whether the repeater answered, without having to call anyone. It needs your callsign (PKT → APRS settings) and logging on, and asks before transmitting. On DMR, call the Parrot (private call 9990 on BrandMeister) instead: hearing your own call back proves both directions. Use it sparingly; repeaters are shared.

## Reading the two layers

The Heard layer usually reaches further than Key-ups: the repeater has a big antenna and more power than a handheld. Plan by Key-ups. No-reply squares are shown only for repeaters that have answered you somewhere, since a repeater with no tail never answers. Someone else talking right after you can look like a key-up; it is rare.

## Live while you watch

With the map open, new readings appear within a couple of seconds and your blue dot follows you. The prediction re-calibrates itself from the new readings at most every 2 minutes (a full recalculation takes about half a minute).

## Focus and details

The Coverage sheet picks the layer (Off, Heard, Key-ups) and one repeater or all of them (each square then shows the best one). Tap a square for its reading, or a channel pin → Coverage to focus on it. The legend in the corner opens the sheet.

## Terrain prediction

Pick one repeater (Coverage sheet, or a repeater or channel on the map → Coverage) and the map adds faint squares: the range predicted from the hills and valleys between you and the repeater, by default (Auto) as far as the transmitter's power and antenna height can carry over flat ground, up to 50 miles, or a range you pick. It works before you have measured anything, so you can check a repeater before adding it. A picked range is a minimum: the prediction always reaches out to your position and to everything you have measured (up to 50 miles), so a reception far outside the expected range is never left off the map. The repeater's antenna height is Auto by default: once your Heard readings cover a range of distances, the app tries heights from 10 to 350 m and keeps the one that explains them best (a tall tower carries much further over the hills); with fewer readings it uses 30 m. Pick a height, or Set the exact one, if you know it. Transmitter power works the same way: Unknown assumes 50 W and lets your measurements correct the level; NOAA Weather Radio stations publish theirs (most are 1000 W), and repeater owners often list theirs.

## How it is calculated

For each square: the loss over smooth ground (the direct signal plus its reflection off the ground within the radio horizon, the earth's curve beyond it, per ITU-R P.526), plus whatever the hills between add as obstacles. Heights are taken above the surrounding ground, so a transmitter on a hill or a phone on a rise gets credit, and a valley costs signal. The transmitter's power and antenna height set the level; your measurements correct it.

## How the prediction learns

It starts from typical numbers (a 50 W repeater, a 4 W handheld, 12 dB for trees, buildings and your body). Your Heard readings shift its signal level to match what you really received; your key-ups move the limit of where you can reach the repeater. Until you have key-ups, the reach follows the Heard correction too, since it is the same path both ways. Each square's card says whether it is measured or predicted and how well calibrated the prediction is. Your altitude counts through the terrain: the ground height at your position and at the repeater is in every calculation, so a hilltop or a valley is accounted for (the phone's own GPS altitude is not used: it is typically 10 to 30 m off). Trees and buildings are not in the terrain data, so forests and towns usually come out weaker than predicted until you have measured there.

## In the browser

SignalDeck Web’s Map shows the same layers: the measured squares for all repeaters or one, and the terrain prediction for one repeater (the same settings as here: antenna height, power and range from the Coverage sheet).

## Terrain data

Heights come from the open Terrain Tiles (AWS Open Data, about 55 m detail). The first prediction for an area downloads them (a few megabytes); after that they are kept on the phone and predictions work offline.

## Export and clear

Export writes Download/DMR/Coverage/\<date\>/ with coverage\_rx.csv and coverage\_tx.csv (every reading, for a spreadsheet) and coverage.kml, which opens in Google Earth with coloured squares and green/red key-up points per repeater. Clear deletes the readings of the selected repeater, or all of them.

## Good habits

Carry the phone the same way when you compare places: a pocket or a car costs 10 to 20 dB. Do not key a repeater just to test it; use your normal conversations, and identify with your callsign as always.

[All topics](README.md)
