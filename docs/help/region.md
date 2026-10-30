<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Region

*Pages · 27 of 34*

Your country's APRS frequency and other defaults.

## What it sets

Device → Region (the first row of Radio settings) is the country whose defaults SignalDeck uses: the terrestrial APRS frequency (APRS monitoring, automatic beacons, SmartBeaconing, telemetry, weather reports, the two-way iGate and the scheduler use it, and the ham simplex list's APRS channel), the FM calling and SSTV frequencies, the licence-free services, whether NOAA-style weather radio exists, the amateur band edges, kilometres or miles, and the station-ID interval. The sheet lists each value; values still being checked against the regulator's pages say "to be confirmed".

## How it was chosen

On the first start the app takes the country of your SIM card, else the mobile network's, else the phone's language setting, once, and shows it in a card at the bottom of the intercom ("Region: Belgium (from your SIM)"; Change, or close the card). When the SIM and the network disagree (roaming), the SIM wins and the card names the network's country. It never changes by itself afterwards: when you travel, change it yourself. If the phone says nothing usable, nothing is chosen and the North American values stay in use (APRS on 144.390) until you choose.

## Choosing

Choose your region shows the countries with checked values; type to find any other country (it uses its IARU region's defaults: Region 1 for Europe, Africa and the Middle East, 2 for the Americas, 3 for Asia and the Pacific). Custom starts from one of the three IARU regions' values. If you changed values yourself, the app asks whether to keep them.

## What changes with it

Add a zone lists your region's licence-free services first and marks the others "not legal here"; the ham simplex list uses its calling, APRS and SSTV frequencies. NOAA weather lists, the band scope's weather band and the SAME alerts card are offered where weather radio exists (elsewhere under Show all). The Morse ID and the ID reminder use its amateur band edges (for example 144 to 146 MHz in most of Europe, so 146.520 is not treated as an amateur channel there) and its station-ID interval. Distances (APRS stations, the fox hunt, the map, nearby repeaters, contacts, the logbook) are in kilometres or miles as the region uses, and Distances on the sheet changes it.

## Your own values

Change next to a value sets your own (for example a local APRS frequency); Reset goes back to the region's. A frequency this radio cannot tune is saved with a warning, and what parks on it (APRS monitoring) is then not offered, as on a radio that does not tune 2 m. ISS and satellite APRS, the KISS TNC and frequencies you tune yourself are not affected.

## Safe by design

Changing the region never transmits, never starts monitoring and never changes a channel you made. It only sets defaults: you remain responsible for your licence and for following your country's rules. SignalDeck Web shows the region on its Settings page and uses its APRS frequency for its buttons; the region is changed only on the phone.

## How accurate it is

The country values come from public band plans and national rules, and some are not yet confirmed: they may be wrong or out of date for your country. Check them against your own licence and band plan. If you find a wrong value, please report it on GitHub (Issues, title "Region data: \<country\>") with the correct value and a source, such as your national society's band plan or the regulator's page, so the next release can fix it for everyone. A licence-free service in the list means the band is licence-free there for radios approved for it, not that this phone is one: such services usually need approved equipment, a power limit and a fixed antenna.

[All topics](README.md)
