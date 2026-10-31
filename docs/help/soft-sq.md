<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Soft SQ

*On the intercom · 4 of 34*

A smarter squelch for weak signals.

## What it does

SOFT SQ holds the radio's own squelch open and lets the app decide what you hear: the channel's CTCSS tone when it has one, otherwise a noise squelch that tells speech from static. It catches weaker stations than the radio's squelch.

## The slider

The slider next to SOFT SQ sets how strict it is, from 1 (lenient) to 9 (strict); new channels start at 5. Each step asks a little more of the signal before the audio opens, and once open it stays open until the signal is clearly gone, so speech does not chop. The number turns green while the squelch is open and amber while it is closed. On the intercom the slider sits under SCAN and ZONE and shows only while Soft SQ is on for an FM channel; with Soft SQ off, and on DMR and M17 channels, it is hidden and the activity panel uses the space.

## Switching channels

After a channel change the audio stays muted for under a second while the receiver settles, so you do not hear a burst of static.

## Per channel

Soft SQ is remembered for each analog channel. With it off, the channel's own squelch level (Channels → Edit) applies.

[All topics](README.md)
