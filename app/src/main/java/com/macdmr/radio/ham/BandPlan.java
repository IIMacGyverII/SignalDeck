/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;

import java.util.Locale;

/**
 * What a frequency is for, within the radio's 136–174 and 400–480 MHz: the US amateur band plans for 2 m and 70 cm
 * (ARRL, typical; local coordination varies) and the other services in range, with a caution where transmitting
 * needs another licence, is not allowed, or would disturb a special use (satellites, weak signal, beacons).
 */
public final class BandPlan {
    private BandPlan() { }

    public static final class Info {
        public final String use; public final String caution; public final boolean amateur;
        Info(String use, String caution, boolean amateur) { this.use = use; this.caution = caution; this.amateur = amateur; }
        public String text() { return caution == null ? use : Txt.s(R.string.channels_bandplan_use_caution, use, caution); }
    }

    private static final String HAM = "Amateur licence needed to transmit";   // i18n: unused constant (no screen reads HAM_NOTE)

    private static Info ham(String use) { return new Info(use, null, true); }
    private static Info hamCare(String use, String caution) { return new Info(use, caution, true); }
    private static Info other(String use, String caution) { return new Info(use, caution, false); }

    /** Use of {@code hz}, or null outside the radio's range. */
    public static Info of(long hz) {
        double m = hz / 1e6;
        // ---- 2 m amateur band ----
        if (m >= 144 && m < 148) {
            if (m < 144.10) return hamCare(Txt.s(R.string.channels_bandplan_2_m_cw_moonbounce_eme), Txt.s(R.string.channels_bandplan_no_fm_here));
            if (m < 144.275) return hamCare(Txt.s(R.string.channels_bandplan_2_m_weak_signal_ssb), Txt.s(R.string.channels_bandplan_no_fm_here));
            if (m < 144.30) return hamCare(Txt.s(R.string.channels_bandplan_2_m_beacons), Txt.s(R.string.channels_bandplan_do_not_transmit));
            if (Math.abs(m - 144.39) < 0.0051) return ham(Txt.s(R.string.channels_bandplan_aprs_north_american_packet_frequency));
            if (m < 144.50) return hamCare(Txt.s(R.string.channels_bandplan_2_m_new_satellite_miscellaneous), Txt.s(R.string.channels_bandplan_keep_fm_off));
            if (m < 144.60) return hamCare(Txt.s(R.string.channels_bandplan_2_m_linear_translator_inputs), Txt.s(R.string.channels_bandplan_satellites_translators_not_fm));
            if (m < 144.90) return ham(Txt.s(R.string.channels_bandplan_2_m_fm_repeater_inputs));
            if (m < 145.10) return ham(m >= 145.01 && m <= 145.09 ? Txt.s(R.string.channels_bandplan_2_m_packet) : Txt.s(R.string.channels_bandplan_2_m_weak_signal_fm));
            if (m < 145.20) return hamCare(Txt.s(R.string.channels_bandplan_2_m_linear_translator_outputs), Txt.s(R.string.channels_bandplan_not_fm));
            if (m < 145.50) return ham(Txt.s(R.string.channels_bandplan_2_m_fm_repeater_outputs));
            if (m < 145.80) return ham(Txt.s(R.string.channels_bandplan_2_m_miscellaneous_experimental));
            if (m < 146.00) return hamCare(Math.abs(m - 145.80) < 0.0051 ? Txt.s(R.string.channels_bandplan_iss_voice_downlink) : Math.abs(m - 145.825) < 0.0051 ? Txt.s(R.string.channels_bandplan_satellite_aprs_iss_moved_437) : Math.abs(m - 145.99) < 0.0051 ? Txt.s(R.string.channels_bandplan_iss_repeater_uplink) : Txt.s(R.string.channels_bandplan_2_m_satellite_subband), Txt.s(R.string.channels_bandplan_satellites_only_no_terrestrial_fm));
            if (m < 146.40) return ham(Txt.s(R.string.channels_bandplan_2_m_fm_repeater_inputs));
            if (m < 146.60) return ham(Math.abs(m - 146.52) < 0.0051 ? Txt.s(R.string.channels_bandplan_2_m_national_fm_simplex) : Txt.s(R.string.channels_bandplan_2_m_fm_simplex));
            if (m < 147.00) return ham(Txt.s(R.string.channels_bandplan_2_m_fm_repeater_outputs));
            if (m < 147.40) return ham(Txt.s(R.string.channels_bandplan_2_m_fm_repeater_outputs_2));
            if (m < 147.60) return ham(Txt.s(R.string.channels_bandplan_2_m_fm_simplex));
            return ham(Txt.s(R.string.channels_bandplan_2_m_fm_repeater_inputs));
        }
        // ---- 70 cm amateur band ----
        if (m >= 420 && m < 450) {
            if (m < 432) return ham(Txt.s(R.string.channels_bandplan_70_cm_amateur_television_links));
            if (m < 432.07) return hamCare(Txt.s(R.string.channels_bandplan_70_cm_moonbounce_eme), Txt.s(R.string.channels_bandplan_no_fm_here));
            if (m < 432.30) return hamCare(Txt.s(R.string.channels_bandplan_70_cm_weak_signal_cw), Txt.s(R.string.channels_bandplan_no_fm_here));
            if (m < 433) return hamCare(Txt.s(R.string.channels_bandplan_70_cm_weak_signal_beacons), Txt.s(R.string.channels_bandplan_no_fm_here));
            if (m < 435) return ham(Txt.s(R.string.channels_bandplan_70_cm_auxiliary_repeater_links));
            if (m < 438) return hamCare(Math.abs(m - 437.825) < 0.0151 ? Txt.s(R.string.channels_bandplan_iss_aprs_digipeater) : Math.abs(m - 437.55) < 0.0151 ? Txt.s(R.string.channels_bandplan_iss_sstv_downlink) : Math.abs(m - 437.80) < 0.0101 ? Txt.s(R.string.channels_bandplan_iss_repeater_downlink) : Txt.s(R.string.channels_bandplan_70_cm_satellite_subband), Txt.s(R.string.channels_bandplan_satellites_only_no_terrestrial_fm));
            if (m < 442) return ham(Txt.s(R.string.channels_bandplan_70_cm_amateur_television_repeaters));
            if (m < 445) return ham(Txt.s(R.string.channels_bandplan_70_cm_fm_repeaters_local));
            if (m < 447) return ham(Math.abs(m - 446.0) < 0.0051 ? Txt.s(R.string.channels_bandplan_70_cm_national_fm_simplex) : Txt.s(R.string.channels_bandplan_70_cm_fm_simplex_links));
            return ham(Txt.s(R.string.channels_bandplan_70_cm_fm_repeaters_local));
        }
        // ---- other services ----
        if (m >= 136 && m < 137) return other(Txt.s(R.string.channels_bandplan_space_operations_aviation), Txt.s(R.string.channels_bandplan_receive_only));
        if (m >= 137 && m < 138) return other(Txt.s(R.string.channels_bandplan_weather_other_satellites_downlinks), Txt.s(R.string.channels_bandplan_receive_only));
        if (m >= 138 && m < 144) return other(Txt.s(R.string.channels_bandplan_us_government_military), Txt.s(R.string.channels_bandplan_do_not_transmit));
        if (m >= 148 && m < 150.8) return other(Txt.s(R.string.channels_bandplan_us_government_military), Txt.s(R.string.channels_bandplan_do_not_transmit));
        if (m >= 151.8175 && m <= 151.9425) return other(Txt.s(R.string.channels_bandplan_murs_licence_free_2_w), null);
        if (Math.abs(m - 154.57) < 0.006 || Math.abs(m - 154.60) < 0.006) return other(Txt.s(R.string.channels_bandplan_murs_licence_free_2_w), null);
        if (m >= 150.8 && m < 156) return other(Txt.s(R.string.channels_bandplan_land_mobile_public_safety_business), Txt.s(R.string.channels_bandplan_licensed_users_only_public_safety));
        if (m >= 156 && m <= 157.45) return other(Txt.s(R.string.channels_bandplan_marine_vhf), Txt.s(R.string.channels_bandplan_receive_only_radio));
        if (m >= 159.81 && m <= 161.565) return other(Txt.s(R.string.channels_bandplan_aar_railroad_channels), Txt.s(R.string.channels_bandplan_receive_only_radio));
        if (m > 157.45 && m < 162.4) return other(m >= 161.6 ? Txt.s(R.string.channels_bandplan_marine_shore_stations_land_mobile) : Txt.s(R.string.channels_bandplan_land_mobile_business_industry_utilities), Txt.s(R.string.channels_bandplan_licensed_users_only));
        if (m >= 162.4 && m <= 162.55) return other(Txt.s(R.string.channels_bandplan_noaa_weather_radio), Txt.s(R.string.channels_bandplan_receive_only));
        if (m > 162.55 && m <= 174) return other(Txt.s(R.string.channels_bandplan_us_federal_government), Txt.s(R.string.channels_bandplan_do_not_transmit));
        if (m >= 400 && m < 406.1) return other(Txt.s(R.string.channels_bandplan_weather_balloons_satellites), Txt.s(R.string.channels_bandplan_do_not_transmit));
        if (m >= 406.1 && m < 420) return other(Txt.s(R.string.channels_bandplan_us_federal_government), Txt.s(R.string.channels_bandplan_do_not_transmit));
        if (frs(m)) return other(m >= 467.5 && m <= 467.72 ? Txt.s(R.string.channels_bandplan_frs_gmrs_channels_8_14) : Txt.s(R.string.channels_bandplan_frs_gmrs), Txt.s(R.string.channels_bandplan_gmrs_needs_fcc_licence_frs));
        if (m >= 467.5375 && m <= 467.7375) return other(Txt.s(R.string.channels_bandplan_gmrs_repeater_inputs), Txt.s(R.string.channels_bandplan_fcc_gmrs_licence_needed));
        if (m >= 450 && m < 470) return other(Txt.s(R.string.channels_bandplan_land_mobile_business_public_safety), Txt.s(R.string.channels_bandplan_licensed_users_only));
        if (m >= 470 && m <= 480) return other(Txt.s(R.string.channels_bandplan_land_mobile_some_cities_tv), Txt.s(R.string.channels_bandplan_licensed_users_only));
        return null;
    }

    private static boolean frs(double m) {
        for (int n = 1; n <= 22; n++) {
            double f = n <= 7 ? 462.5625 + (n - 1) * 0.025 : n <= 14 ? 467.5625 + (n - 8) * 0.025 : 462.55 + (n - 15) * 0.025;
            if (Math.abs(m - f) < 0.003) return true;
        }
        return false;
    }

    /** One line for a channel: "146.5200 MHz: 2 m national FM simplex calling". */
    public static String line(long hz) {
        Info i = of(hz);
        return Txt.s(R.string.channels_bandplan_line, String.format(Locale.US, "%.4f", hz / 1e6), i == null ? Txt.s(R.string.channels_bandplan_outside) : i.text());

    }

    public static final String HAM_NOTE = HAM;
}
