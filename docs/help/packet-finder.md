<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Packet finder

*Pages · 14 of 34*

Find the BBSs, nodes and Winlink gateways around you.

## What it does

PKT → Packet finder (or SignalDeck Web's Packet page) listens on the usual 1200-baud packet frequencies one after another and lists every packet station it hears that is not plain APRS: Winlink gateways, BBSs, nodes (their NET/ROM broadcasts and ID beacons), station IDs and beacons, and stations heard in a connection. For each: the frequency, the best signal, how often and when it was last heard, and what it said. It only listens; it never transmits.

## How long

Nodes and BBSs identify only every 10 to 30 minutes, so a short look finds little: let it run an hour or two, or add a schedule (Scheduled operations → Packet finder, for example overnight). It stops by itself if you transmit, change the channel or a tracked satellite pass starts, and the radio goes back to its channel (or VFO) when it ends. Settings: the frequencies (in MHz; empty means the 2 m packet frequencies 144.910 to 145.090 MHz; add your area's 70 cm packet frequencies if you know them; Use my channels fills in the frequencies of your own analog channels the radio tunes, a start on a UHF-only radio such as the ANYSECU W6+, where the 2 m list does not apply), minutes on each, and hours in total.

## Using a station

Tap a station to tune its frequency and open the packet terminal with its callsign filled in, or, for a Winlink gateway, Winlink with the gateway set. Clear the list to start a fresh search. Winlink also lists gateways on its website (winlink.org, RMS Channels).

[All topics](README.md)
