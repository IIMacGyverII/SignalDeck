<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# FT8 over FM

*Pages · 17 of 34*

The WSJT-X digital mode, on an FM channel, with automatic QSOs.

## What it is

PKT → FT8 over FM runs FT8, the digital mode of WSJT-X: 15-second slots, short structured messages (calls, grid square, signal report) and contacts that run by themselves. Here it rides on the FM channel you are on, like any other audio mode, so it talks to stations sending FT8 audio on that FM channel: another SignalDeck, or WSJT-X through an FM radio. Agree on a simplex frequency with the other station; avoid the national calling frequencies. On the bench firmware (FW22 or later) the 79 tones go out through the module's tone generator instead of the audio path; the history line says so.

## What it cannot do

This radio receives FM only, so it cannot hear normal FT8, which is sent on SSB (144.174 and 432.174 MHz, and on HF). On FM the receiver lets only the strongest transmitter through, so each slot carries one station on the channel, not dozens as on SSB, and FT8's famous weak-signal reach mostly does not carry over: FM needs a usable signal before it passes anything but noise. Expect a little more range than voice, not dramatically more.

## The screen

The bar at the top is the slot clock: the time in the current 15 s slot and its period (1st: :00 and :30, 2nd: :15 and :45), red when you transmit in it. Under it, the waterfall of the channel's audio, 200 to 2800 Hz: the red lines mark your transmit frequency and width; tap the waterfall to move it (on FM it matters little). Then the QSO in progress and the station's state, and the list of decodes, newest first: time, signal in dB (in 2500 Hz, approximate), DT (the time offset in seconds), audio frequency and the message. CQs are green, messages to you amber. Your own transmissions are listed under Sent.

## Making contacts

Tap a CQ in the list to answer it: the app transmits in the other period and runs the exchange the way WSJT-X does (your grid, their report, R and your report, RR73, 73), logging the contact in the Logbook (mode FT8) as soon as the reports are exchanged. Call CQ calls until someone answers, then does the same with them. Halt TX stops transmitting; if nobody answers, it stops by itself after 8 tries (RR73 after 2). Send text sends one message of your own: any standard message, or up to 13 characters of free text (A-Z, 0-9, space and + - . / ?). Your callsign comes from APRS settings. A callsign FT8 counts as non-standard (more than three letters after the digit, or an unusual prefix) can only be sent in full in a message type that has no room for a grid or report, as in WSJT-X; the history says so once when you start, and your CQ then goes out without the grid. In the reports that follow it goes as a short code the other station learned from that first message (shown as \<CALL\>), so a contact still completes, and a caller with such a call is answered even though its first reply carries no grid.

## Settings and clock

FT8 settings: the grid sent with your messages (4 characters, about 100 by 200 km: empty uses the phone's location, a typed one is fixed, - sends none) and Clear list. FT8 needs both stations' clocks within about a second: the phone sets its clock from the network, and the settings show the average DT of recent decodes, which tells how far apart the clocks are. Each transmission is 14 seconds with the lead-in this radio needs. Stop FT8 ends it; the back arrow keeps it running, with a bar on the intercom to come back. SignalDeck Web has the same on its Packet page.

[All topics](README.md)
