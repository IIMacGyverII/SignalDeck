<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Band scope

*Pages · 20 of 34*

See which frequencies in a band are busy.

## Open it

PKT → Band scope (or the PKT tools button at the top of any mode page), or SignalDeck Web → Scope. Choose a band (2 m, 2 m FM simplex, 70 cm, 70 cm FM, GMRS/FRS, marine VHF, and NOAA weather where your region has weather radio; Show all bands lists it everywhere) or Custom range (from and to in MHz and the step in kHz, anywhere inside 136–174 or 400–480 MHz, up to 1200 steps; the sheet shows how long a sweep will take) and Start.

## What it shows

The radio steps across the band and reads the signal strength at each step. The cyan line is the latest sweep (the one in progress is drawn step by step over the previous one), the amber line the strongest seen at each frequency since Start or Clear peaks, and below it the waterfall: each row one sweep, newest at the top, from dark (noise) through cyan and green to amber and red (strong). Under the graph: the strongest signals at least 8 dB above the band's noise, with the channel name when one of your channels is there.

## How fast

About a second a step: after every retune the radio needs 0.7 s before its signal reading is right (measured: earlier readings are 15 to 20 dB low), then the reading itself takes 0.1 to 0.5 s. So NOAA weather (7 steps) takes about 7 seconds, 2 m FM simplex (81 steps) about a minute and a half, all of 2 m in 25 kHz steps about 3 minutes and GMRS/FRS (421 steps of 12.5 kHz) about 7 minutes; a narrow custom range with only the channels you care about comes round fastest. The line under the title shows the steps, the time a sweep takes (an estimate until the first sweep is done) and the step being measured; the frequency in the strip is the one being measured now (green), and the intercom's bar shows it too. It shows activity that lasts: repeaters, nets, beacons, interference, not a single short call. On the Unihertz Atom XL a step takes about a tenth of a second: its radio settles in about 50 ms after a retune and answers a reading in a few milliseconds (measured 2026-10-30), so the same band takes about a ninth of the time, and its scanner moves on from a quiet channel after half a second instead of one and a half.

## Listen and stop

Tap the graph (or a peak) to read a frequency, then Listen here: the scope stops and the VFO tunes there. The scope only receives; while it runs the speaker and the decoders are quiet. When it stops, the radio goes back to your channel. It stops by itself when you press Talk, for a scheduled operation and for a satellite pass, and it does not start while a pass is being tracked or begins within 15 minutes.

[All topics](README.md)
