/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import com.macdmr.radio.dsp.ToneConverter;
import com.macdmr.radio.i18n.Txt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standard channel lists offered when adding a zone (FRS, GMRS, NOAA weather, MURS, marine, ham simplex, DMR simplex,
 * PMR446). Installing one adds its channels (reusing any channel the user already has on the same frequencies and
 * tones) and a zone holding them, named after the list. {@link #offers} orders them for the region (Device → Region):
 * its licence-free services first, licence-free services it does not have marked "not legal here", and lists that only
 * exist elsewhere (NOAA weather outside weather-radio countries, US business and railroad channels) under Show all.
 */
public final class ChannelPresets {
    private ChannelPresets() { }

    public static final class Ch {
        public final String name; public final int rx, tx; public final boolean digital, high, wide, repeater;
        public final double txTone;   // a fixed CTCSS tone sent on transmit (Hz, 0 = none), e.g. a satellite's access tone
        Ch(String name, double rxMhz, double txMhz, boolean digital, boolean high, boolean wide, boolean repeater) { this(name, rxMhz, txMhz, digital, high, wide, repeater, 0); }
        Ch(String name, double rxMhz, double txMhz, boolean digital, boolean high, boolean wide, boolean repeater, double txTone) {
            this.name = name; rx = hz(rxMhz); tx = hz(txMhz); this.digital = digital; this.high = high; this.wide = wide; this.repeater = repeater; this.txTone = txTone;
        }
    }

    public static final class Preset {
        public final String zone, region, about, licence; public final List<Ch> channels = new ArrayList<>();
        /** A licence-free personal radio service (FRS, GMRS, MURS, UHF CB, PMR446): legal where the region lists its frequencies. */
        public boolean licenceFree;
        /** Only of use in these countries (null: anywhere). */
        public java.util.Set<String> where;
        /** NOAA-style weather radio: shown where the region has it. */
        public boolean weather;
        Preset(String zone, String region, String about, String licence) { this.zone = zone; this.region = region; this.about = about; this.licence = licence; }
        public boolean hasRepeaters() { for (Ch c : channels) if (c.repeater) return true; return false; }
        public boolean receiveOnly() { for (Ch c : channels) if (!ChannelStore.receiveOnly(c.tx)) return false; return true; }
        /** How many of its channels this phone's radio can tune (receive and transmit frequency inside the profile's bands). */
        public int tunableCount() { return tunableCount(DeviceProfile.detect()); }
        public int tunableCount(DeviceProfile dp) { int n = 0; for (Ch c : channels) if (tunable(dp, c)) n++; return n; }
        Preset fm(String name, double mhz, boolean high, boolean wide) { channels.add(new Ch(name, mhz, mhz, false, high, wide, false)); return this; }
        Preset split(String name, double rxMhz, double txMhz, boolean high, boolean wide, double txTone) { channels.add(new Ch(name, rxMhz, txMhz, false, high, wide, false, txTone)); return this; }
    }

    /** The DMR simplex talkgroup (BrandMeister and most networks): every DMR simplex channel calls and listens on it. */
    public static final int DMR_SIMPLEX_TG = 99;

    private static int hz(double mhz) { return (int) Math.round(mhz * 1e6); }
    /** Whether the radio of {@code dp} tunes the channel: both frequencies inside its bands (NOAA, marine and 2 m lists on a UHF-only radio: no). */
    public static boolean tunable(DeviceProfile dp, Ch c) { return dp.tunable(c.rx) && dp.tunable(c.tx); }

    public static List<Preset> all() { return all(com.macdmr.radio.region.Region.cached()); }
    /** The lists, with the region's calling, APRS and SSTV frequencies (Device → Region) in the ham list. */
    public static List<Preset> all(com.macdmr.radio.region.Region region) {
        List<Preset> out = new ArrayList<>();

        Preset frs = new Preset("FRS", "US", Txt.s(R.string.channels_preset_frs_about), Txt.s(R.string.channels_preset_licence_none));   // i18n: service name
        for (int n = 1; n <= 22; n++) frs.fm(String.format(Locale.US, "FRS %d", n), frsMhz(n), false, false);   // i18n: channel names (stored)
        frs.licenceFree = true; out.add(frs);

        Preset gmrs = new Preset("GMRS", "US", Txt.s(R.string.channels_preset_gmrs_about), Txt.s(R.string.channels_preset_gmrs_licence));   // i18n: service name
        for (int n = 1; n <= 22; n++) { boolean interstitial467 = n >= 8 && n <= 14; gmrs.fm(String.format(Locale.US, "GMRS %d", n), frsMhz(n), !interstitial467, !interstitial467); }   // i18n: channel names (stored)
        for (int n = 15; n <= 22; n++) gmrs.channels.add(new Ch(String.format(Locale.US, "GMRS %dR", n), frsMhz(n), frsMhz(n) + 5, false, true, true, true));   // i18n: channel names (stored)
        gmrs.licenceFree = true; out.add(gmrs);

        Preset wx = new Preset("NOAA Weather", Txt.s(R.string.channels_preset_where_us_canada), Txt.s(R.string.channels_preset_wx_about), Txt.s(R.string.channels_preset_licence_rx_only));   // i18n: service name
        double[] wxMhz = { 162.550, 162.400, 162.475, 162.425, 162.450, 162.500, 162.525 };
        for (int i = 0; i < wxMhz.length; i++) wx.fm("NOAA WX" + (i + 1), wxMhz[i], false, true);   // i18n: channel names (stored)
        wx.weather = true; out.add(wx);

        Preset murs = new Preset("MURS", "US", Txt.s(R.string.channels_preset_murs_about), Txt.s(R.string.channels_preset_licence_none));   // i18n: service name
        murs.fm("MURS 1", 151.820, false, true).fm("MURS 2", 151.880, false, true).fm("MURS 3", 151.940, false, true).fm("MURS 4 Blue", 154.570, false, true).fm("MURS 5 Green", 154.600, false, true);   // i18n: channel names (stored)
        murs.licenceFree = true; out.add(murs);

        Preset marine = new Preset("Marine VHF", Txt.s(R.string.channels_preset_where_us_intl), Txt.s(R.string.channels_preset_marine_about), Txt.s(R.string.channels_preset_licence_rx_only));   // i18n: service name
        marine.fm("Marine 16", 156.800, false, true).fm("Marine 9", 156.450, false, true).fm("Marine 13", 156.650, false, true).fm("Marine 22A", 157.100, false, true)   // i18n: channel names (stored)
              .fm("Marine 6", 156.300, false, true).fm("Marine 68", 156.425, false, true).fm("Marine 69", 156.475, false, true).fm("Marine 71", 156.575, false, true)   // i18n: channel names (stored)
              .fm("Marine 72", 156.625, false, true).fm("Marine 78A", 156.925, false, true);   // i18n: channel names (stored)
        out.add(marine);

        out.add(ham(region));

        Preset dmr = new Preset("DMR simplex", Txt.s(R.string.channels_preset_where_worldwide), Txt.s(R.string.channels_preset_dmr_about), Txt.s(R.string.channels_preset_licence_amateur));   // i18n: list (zone) name
        for (double f : new double[] { 441.000, 446.500, 446.075, 433.450, 145.790, 145.510 }) dmr.channels.add(new Ch(String.format(Locale.US, "DMR S %.3f", f), f, f, true, true, true, false));   // i18n: channel names (stored)
        out.add(dmr);

        Preset biz = new Preset("Business", "US", Txt.s(R.string.channels_preset_biz_about), Txt.s(R.string.channels_preset_biz_licence));   // i18n: list (zone) name
        biz.fm("Red Dot", 151.625, true, true).fm("Purple Dot", 151.955, true, true).fm("Biz 151.700", 151.700, true, true).fm("Biz 151.760", 151.760, true, true)   // i18n: channel names (stored)
           .fm("Brown Dot", 464.500, true, false).fm("Yellow Dot", 464.550, true, false).fm("Biz 469.500", 469.500, true, false).fm("Biz 469.550", 469.550, true, false)   // i18n: channel names (stored)
           .fm("Silver Star", 467.850, true, false).fm("Gold Star", 467.875, true, false).fm("Red Star", 467.900, true, false).fm("Blue Star", 467.925, true, false);   // i18n: channel names (stored)
        biz.where = new java.util.HashSet<>(java.util.Arrays.asList("US")); out.add(biz);

        Preset rail = new Preset("Railroad", Txt.s(R.string.channels_preset_where_us_canada), Txt.s(R.string.channels_preset_rail_about), Txt.s(R.string.channels_preset_licence_rx_only));   // i18n: list (zone) name
        rail.fm("AAR 02", 159.810, false, true).fm("AAR 03", 159.930, false, true).fm("AAR 04", 160.050, false, true);   // i18n: channel names (stored)
        for (int n = 5; n <= 97; n++) rail.fm(String.format(Locale.US, "AAR %02d", n), 160.185 + (n - 5) * 0.015, false, true);   // i18n: channel names (stored)
        rail.where = new java.util.HashSet<>(java.util.Arrays.asList("US", "CA")); out.add(rail);

        Preset sat = new Preset("ISS and satellites", Txt.s(R.string.channels_preset_where_worldwide), Txt.s(R.string.channels_preset_sat_about), Txt.s(R.string.channels_preset_licence_amateur_tx));   // i18n: list (zone) name
        sat.split("ISS Voice", 145.800, 144.490, true, true, 0)
           .split("ISS APRS +", 437.835, 437.815, true, true, 0).fm("ISS APRS", 437.825, true, true).split("ISS APRS -", 437.815, 437.835, true, true, 0)   // i18n: channel names (stored)
           .split("ISS SSTV +", 437.560, 437.560, true, true, 0).split("ISS SSTV", 437.550, 437.550, true, true, 0).split("ISS SSTV -", 437.540, 437.540, true, true, 0)
           .split("ISS Rptr +", 437.810, 145.990, true, true, 67.0).split("ISS Rptr", 437.800, 145.990, true, true, 67.0).split("ISS Rptr -", 437.790, 145.990, true, true, 67.0)
           .split("SO-50 +", 436.805, 145.850, true, true, 67.0).split("SO-50", 436.795, 145.850, true, true, 67.0).split("SO-50 -", 436.785, 145.850, true, true, 67.0);
        out.add(sat);

        Preset cb = new Preset("UHF CB", Txt.s(R.string.channels_preset_where_au_nz), Txt.s(R.string.channels_preset_cb_about), Txt.s(R.string.channels_preset_cb_licence));   // i18n: service name
        for (int n = 1; n <= 80; n++) {
            boolean output = n <= 8 || (n >= 41 && n <= 48), input = (n >= 31 && n <= 38) || (n >= 71 && n <= 78);
            if (n == 22 || n == 23 || (n >= 61 && n <= 63) || input) continue;
            double f = n <= 40 ? 476.425 + (n - 1) * 0.025 : 476.4375 + (n - 41) * 0.025;
            String tag = n == 5 ? " Emerg" : n == 40 ? " Road" : n == 18 ? " Caravan" : n == 10 ? " 4WD" : n == 11 ? " Call" : "";
            if (output) cb.channels.add(new Ch(String.format(Locale.US, "CB %dR%s", n, tag), f, f + 0.750, false, true, false, true));   // i18n: channel names (stored)
            else cb.fm(String.format(Locale.US, "CB %d%s", n, tag), f, true, false);   // i18n: channel names (stored)
        }
        cb.licenceFree = true; out.add(cb);

        Preset pmr = new Preset("PMR446", Txt.s(R.string.channels_preset_where_eu_uk), Txt.s(R.string.channels_preset_pmr_about), Txt.s(R.string.channels_preset_pmr_licence));   // i18n: service name
        for (int n = 1; n <= 16; n++) pmr.fm(String.format(Locale.US, "PMR %d", n), 446.00625 + (n - 1) * 0.0125, false, false);   // i18n: channel names (stored)
        pmr.licenceFree = true; out.add(pmr);
        return out;
    }

    /** Amateur FM simplex for the region: calling channels, APRS, FM SSTV, and the North American simplex channels where its calling channels are 146.520 / 446.000. */
    static Preset ham(com.macdmr.radio.region.Region region) {
        boolean na2 = region.call2mHz == 146_520_000, na70 = region.call70Hz == 446_000_000;
        String where = !region.chosen || region.id.equals("US") ? "US" : region.name;
        boolean common = na2 || na70, sstv = region.sstvHz > 0;
        String aprs = com.macdmr.radio.region.Region.mhzShown(region.aprsHz);
        String about = common ? Txt.s(sstv ? R.string.channels_preset_ham_about_common_sstv : R.string.channels_preset_ham_about_common, aprs)
                : Txt.s(sstv ? R.string.channels_preset_ham_about_sstv : R.string.channels_preset_ham_about, aprs);
        Preset ham = new Preset("Ham simplex", where, about, Txt.s(R.string.channels_preset_licence_amateur));   // i18n: list (zone) name
        if (region.call2mHz > 0) ham.fm("2m Call", region.call2mHz / 1e6, true, true);
        if (na2) for (double f : new double[] { 146.550, 146.580, 147.420, 147.450, 147.480, 147.510, 147.540, 147.570 }) ham.fm(String.format(Locale.US, "2m %.3f", f), f, true, true);
        ham.fm("APRS", region.aprsHz / 1e6, true, true);
        if (region.sstvHz > 0) ham.fm("SSTV", region.sstvHz / 1e6, true, true);
        if (region.call70Hz > 0) ham.fm("70cm Call", region.call70Hz / 1e6, true, true);   // i18n: channel names (stored)
        if (na70) for (double f : new double[] { 445.950, 446.050, 446.100, 446.150 }) ham.fm(String.format(Locale.US, "70cm %.3f", f), f, true, true);   // i18n: channel names (stored)
        return ham;
    }

    public static final int NA = -1, LEGAL = 0, PARTLY = 1, NOT_LEGAL = 2;
    /** For a licence-free list: are its transmit channels inside one of the region's licence-free services? NA for other lists or before a region is chosen. */
    public static int legality(Preset p, com.macdmr.radio.region.Region r) {
        if (!p.licenceFree || !r.chosen) return NA;
        int tx = 0, in = 0;
        for (Ch c : p.channels) { if (ChannelStore.receiveOnly(c.tx)) continue; tx++; for (com.macdmr.radio.region.Region.Service s : r.licenseFree) if (s.covers(c.tx)) { in++; break; } }
        return in == 0 ? NOT_LEGAL : in == tx ? LEGAL : PARTLY;
    }
    /** One list as the Add-a-zone sheet offers it. */
    public static final class Offer {
        public final Preset preset; public final int legal; public final boolean hidden;
        Offer(Preset p, int legal, boolean hidden) { preset = p; this.legal = legal; this.hidden = hidden; }
    }
    /**
     * The lists in the region's order: its licence-free services (legal, then partly), the lists of use everywhere and those
     * of this region, then licence-free services it does not have ("not legal here", the user's call), then the lists that
     * only exist elsewhere, hidden until Show all. Before a region is chosen: every list, as before.
     */
    public static List<Offer> offers(com.macdmr.radio.region.Region r) {
        List<Preset> all = all(r); List<Offer> out = new ArrayList<>();
        if (!r.chosen) { for (Preset p : all) out.add(new Offer(p, NA, false)); return out; }
        for (int pass = 0; pass < 5; pass++) for (Preset p : all) {
            int l = legality(p, r);
            boolean here = p.weather ? r.weatherRadio : p.where == null || p.where.contains(r.id);
            int k = l == LEGAL ? 0 : l == PARTLY ? 1 : l == NOT_LEGAL ? 3 : here ? 2 : 4;
            if (k == pass) out.add(new Offer(p, l, k == 4));
        }
        return out;
    }

    /** FRS/GMRS channel n (1..22) in MHz. */
    private static double frsMhz(int n) {
        if (n <= 7) return 462.5625 + (n - 1) * 0.025;
        if (n <= 14) return 467.5625 + (n - 8) * 0.025;
        return 462.5500 + (n - 15) * 0.025;
    }

    /** The radio's CTCSS sub-code for a tone in Hz, or -1. */
    public static int ctcssIndex(double hzTone) {
        for (int i = 0; i < ToneConverter.ctcssCount(); i++) if (Math.abs(ToneConverter.ctcssHz(i) - hzTone) < 0.05) return i;
        return -1;
    }

    public static final class Result { public long zoneId; public int added, reused, skipped; public String summary; }

    /** Whether this radio can use the channel: its receive and transmit frequencies both in the radio's bands. */
    public static boolean inBands(Ch c, java.util.function.LongPredicate tunable) { return tunable.test(c.rx) && tunable.test(c.tx); }
    /** How many of the list's channels this radio can use (a UHF-only phone cannot use Marine VHF or a 2 m list). */
    public static int tunableCount(Preset p, java.util.function.LongPredicate tunable) { int n = 0; for (Ch c : p.channels) if (inBands(c, tunable)) n++; return n; }

    /**
     * Add the list's channels and its zone. {@code repeaterTone}: CTCSS sub-code for the repeater channels (sent and
     * received), or -1 for none. A channel already present (same frequencies, mode and tones) is reused, not duplicated.
     */
    public static Result install(ChannelStore store, Preset p, int repeaterTone) { return install(store, p, repeaterTone, hz -> true); }
    /** The same, leaving out the channels this radio cannot tune ({@code tunable}: the profile's bands). */
    public static Result install(ChannelStore store, Preset p, int repeaterTone, java.util.function.LongPredicate tunable) {
        Result r = new Result();
        DeviceProfile dp = DeviceProfile.detect();
        if (tunableCount(p, tunable) == 0) throw new IllegalStateException(Txt.s(R.string.channels_preset_none_tunable, dp.bandsText()));
        ChannelStore.Zone zone = null; for (ChannelStore.Zone z : store.zones()) if (z.name.equals(p.zone)) zone = z;
        if (zone == null) { zone = new ChannelStore.Zone(); zone.name = p.zone; zone.numbers = new int[0]; }
        List<Integer> nums = new ArrayList<>(); for (int n : zone.numbers) nums.add(n);
        if (p.channels.stream().anyMatch(c -> c.digital)) ensureSimplexContact(store);
        for (Ch c : p.channels) {
            if (!inBands(c, tunable)) { r.skipped++; continue; }
            ChannelStore.Channel ch = build(c, repeaterTone);
            ChannelStore.Channel have = find(store, ch);
            if (have != null) { r.reused++; if (!nums.contains(have.number)) nums.add(have.number); continue; }
            store.insert(ch); r.added++; nums.add(ch.number);
        }
        zone.numbers = new int[nums.size()]; for (int i = 0; i < nums.size(); i++) zone.numbers[i] = nums.get(i);
        r.zoneId = store.saveZone(zone);
        r.summary = r.reused > 0 ? Txt.p(r.skipped > 0 ? R.plurals.channels_preset_added_reused_skipped : R.plurals.channels_preset_added_reused, r.added, r.added, p.zone, r.reused, r.skipped)
                : r.skipped > 0 ? Txt.p(R.plurals.channels_preset_added_skipped, r.added, r.added, p.zone, r.skipped) : Txt.p(R.plurals.channels_preset_added, r.added, r.added, p.zone);
        return r;
    }

    static ChannelStore.Channel build(Ch c, int repeaterTone) {
        ChannelStore.Channel ch = new ChannelStore.Channel();
        ch.name = c.name; ch.rxFreq = c.rx; ch.txFreq = c.tx; ch.power = c.high ? 1 : 0; ch.relay = 2; ch.sq = 2; ch.encryptSw = 2;
        ch.band = c.rx < 300_000_000 || c.wide ? 1 : 0;   // VHF must be wide on this radio
        if (c.digital) {
            ch.type = 0; ch.cc = 1; ch.inSlot = ch.outSlot = 0; ch.mode = 0; ch.contactType = 1; ch.txContact = DMR_SIMPLEX_TG; ch.groups = new int[] { DMR_SIMPLEX_TG }; ch.interrupt = 2;
        } else {
            ch.type = 1; ch.contactType = 0; ch.interrupt = 0;
            if (c.repeater && repeaterTone >= 0) { ch.rxType = ch.txType = 1; ch.rxSubCode = ch.txSubCode = repeaterTone; }
            int t = c.txTone > 0 ? ctcssIndex(c.txTone) : -1;
            if (t >= 0) { ch.txType = 1; ch.txSubCode = t; }
        }
        return ch;
    }

    private static ChannelStore.Channel find(ChannelStore store, ChannelStore.Channel w) {
        for (ChannelStore.Channel h : store.all()) {
            if (h.type != w.type || h.rxFreq != w.rxFreq || h.txFreq != w.txFreq) continue;
            if (w.isDigital()) { if (h.cc == w.cc && h.inSlot == w.inSlot && h.txContact == w.txContact) return h; }
            else if (h.rxType == w.rxType && h.txType == w.txType && (w.rxType == 0 || h.rxSubCode == w.rxSubCode) && (w.txType == 0 || h.txSubCode == w.txSubCode)) return h;
        }
        return null;
    }

    private static void ensureSimplexContact(ChannelStore store) {
        for (ChannelStore.Contact c : store.contacts()) if (c.number == DMR_SIMPLEX_TG && c.type == 1) return;
        ChannelStore.Contact c = new ChannelStore.Contact(); c.name = "Simplex"; c.number = DMR_SIMPLEX_TG; c.type = 1; store.insertContact(c);   // i18n: contact name (stored)
    }

    /** One line per channel for the preview: name, frequency (and repeater input), power. */
    public static String preview(Preset p) { return preview(p, DeviceProfile.detect()); }
    static String preview(Preset p, DeviceProfile dp) {
        StringBuilder sb = new StringBuilder();
        for (Ch c : p.channels) {
            if (!tunable(dp, c)) { sb.append(String.format(Locale.US, "%-13s %9.4f", c.name, c.rx / 1e6)).append("  ").append(Txt.s(R.string.channels_preset_preview_not_tunable)).append("\n"); continue; }   // skipped by Add
            sb.append(String.format(Locale.US, "%-13s %9.4f", c.name, c.rx / 1e6));
            if (c.tx != c.rx) sb.append(String.format(Locale.US, " TX %.4f", c.tx / 1e6));   // i18n: column label (TX)
            if (c.txTone > 0) sb.append(String.format(Locale.US, " %.1f", c.txTone));
            sb.append(ChannelStore.receiveOnly(c.tx) ? "  " + Txt.s(R.string.channels_preset_preview_rx_only) : c.digital ? "  DMR" : c.high ? "" : "  " + Txt.s(R.string.channels_preset_preview_low)).append('\n');
        }
        return sb.toString().trim();
    }
}
