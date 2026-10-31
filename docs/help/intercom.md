<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Intercom

*On the intercom · 2 of 34*

The main page: channel, talk key, activity.

## Channel

The big number is the current channel and the title above it is its name. The round arrows step to the previous or next channel of the current zone. On NOAA weather, marine and railroad channels the talk key is dimmed: those frequencies are receive-only.

## Talkgroup

On a DMR channel the CALL cell under the frequencies shows who you transmit to. Long-press it to pick another talkgroup without editing the channel (a plain tap does nothing, so it cannot change by accident). The list starts with the channel's programmed call, then your group contacts (Contacts page, type Group). After a pick the radio transmits group calls to that talkgroup and hears it (it is added to the receive list for the time being), the cell turns amber and reads CALL · PICKED, and the channel itself is not changed. It lasts until you change channel, scan to another one or use the VFO; pick the programmed entry to go back. Not while transmitting.

## Talk key

Hold to transmit, release to listen. Amber: idle. Green: the radio is receiving. Red: transmitting. The phone's side PTT button and, if you switch them on in Device, the volume-up key and a custom PTT key you taught it do the same.

## Activity panel

The panel says what is happening (ready, receiving, transmitting, who called) and lists the last events on this channel; the bars are the spectrum of what is heard, low notes on the left and high on the right (200 Hz to 3.6 kHz), and fall back to silence when nothing is heard. SignalDeck Web draws the same bars on its Intercom page. Tap the panel for Last heard. At the top, Alerts lists the warnings and refusals of the last 7 days (for example Android Auto dropping while you talk, a transmission refused on a receive-only or DMR channel, channel busy, keyword alerts), and Car and coverage lists Android Auto and coverage events. Tap one for what it means and what to do, with every line logged within 20 seconds of it, and a button to tune its channel. Below that is everyone and every channel with activity, newest first, with names from your contacts and the RadioID list, plus the recordings player and the caption transcript; tap a heard line to switch to that channel. The small cross on the panel clears its list and hides it until the next reception or transmission; the small button left in its corner brings it back at once. Last heard keeps everything.

## Activity statistics

Last heard → Activity counts what the radio received today, in the last 7 or 30 days: receptions and minutes of audio per channel, busiest first, and a bar for each hour of the day with the busiest hour in amber. Tap a channel to tune it. Make a scan zone of the busiest puts the twelve busiest channels into a zone called Busiest channels and selects it, so SCAN searches where the activity is.

## Tone offer

When a CTCSS tone is heard on an analog channel that has none set, the panel says so. Tap it to make that tone the channel's receive and transmit tone.

## Signal

The classic page shows the signal strength in dBm in the yellow box. The new look shows a segmented S-meter: S1 to S9 in cyan, then amber and red above S9 (+20, +40), with the S-unit and dBm beside it. When nothing is being received the meter is dark and says NO SIGNAL, with the noise floor the radio reads beside it.

## Two looks

Device → Radio settings → New intercom look switches between the redesigned page (the default) and the classic one. The new page's menu button and a long-press on the classic page's title switch too. Both have the same controls.

## Small screens

On a phone whose screen is less than 700 dp tall (for example a 480 × 854 handheld), the new page is drawn smaller so everything fits above the tab bar: the channel number, its arrows and the FM/UHF/power chips move inside the readout card, text and buttons are smaller, the talk key is 132 dp and the six buttons are 66 × 44 dp in their usual places, and the tab bar is lower. It is the same page with the same controls, only smaller. Device → Radio settings → Intercom layout sets it: Automatic (the default, chosen by the screen height, which it shows), Regular or Compact. Changing it rebuilds the screen.

## Music, calls and the car

While something is heard, the radio asks Android for the audio: music and podcasts dip under it and come back 2.5 seconds after the transmission ends. In Android Auto this is what sends the radio to the car speakers: the car plays it like a navigation prompt, turning its music down meanwhile, and the car's navigation volume sets its loudness. When Android Auto starts it takes the audio for a moment; the radio takes it back within a few seconds.

[All topics](README.md)
