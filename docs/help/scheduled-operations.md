<!-- Generated from app/src/main/assets/guide/en.json by HelpExportTest. Do not edit by hand. -->

# Scheduled operations

*Pages · 21 of 34*

Start something at a set time, every week or once.

## Open it

PKT → Scheduled operations → Schedules, or SignalDeck Web → Settings → Scheduled operations. The list shows each schedule, when it runs next and a switch to turn it off; tap one to edit or delete it, Add a schedule for a new one.

## What it can do

Listen to a channel (optionally recording it the whole time, into Download/DMR/Audio/\<channel\>), scan the zone, APRS monitoring on your region's APRS frequency, SSTV receive (on 144.500 or a channel you choose), send one APRS position beacon, run the packet finder, or check your Winlink mail: by radio (the gateway you last connected to, on the channel you choose; the radio goes back afterwards, and a check never holds the radio more than 20 minutes) or over the internet. The result (sent and received) goes in the history. Tick weekdays for a weekly schedule, or none for once on a date; set the start time and how many minutes it lasts.

## When it runs

SignalDeck sets an exact Android alarm, so it starts on the minute with the screen off (it grants itself that permission with root). When the time is up, the radio goes back to the channel it was on and switches off what the schedule switched on. Only one runs at a time. A satellite pass being tracked always comes first: a schedule whose time overlaps the pass (from two minutes before it rises to a minute after it sets) is skipped and the history says so; while you are transmitting it waits up to two minutes. SignalDeck must own the radio at that time: after handing the radio back to the stock app, schedules do not run.

## Example

Record a weekly net: Listen to a channel, the net's repeater, Record it on, Tuesday, 19:00, 60 minutes.

[All topics](README.md)
