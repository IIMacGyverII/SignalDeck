/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ui;

import static org.junit.Assert.assertEquals;

import com.macdmr.radio.R;
import com.macdmr.radio.i18n.Txt;

import org.junit.Test;

/**
 * Last heard explains a history line by matching its stored English text. Each matcher must still match the English
 * template that writes that line (a reworded template silently loses its explanation: "30 s transmit limit" did).
 */
public class LastHeardExplainTest {
    private static void explains(int title, String english) { assertEquals(english, Txt.s(title), LastHeardDialog.explain(english)[0]); }

    @Test public void everyMatcherMatchesItsEnglishTemplate() {
        explains(R.string.intercom_lastheard_ex_usbdrop_title, Txt.en(R.string.service_car_dropped_tx, "Ch 1", "146.5200"));
        explains(R.string.intercom_lastheard_ex_usbdrop_title, Txt.en(R.string.service_usb_dropped_tx, "Ch 1", "146.5200"));
        explains(R.string.intercom_lastheard_ex_notloaded_title, Txt.en(R.string.service_car_not_loaded));
        explains(R.string.intercom_lastheard_ex_sound_title, Txt.en(R.string.service_car_sound_taken));
        explains(R.string.intercom_lastheard_ex_connected_title, Txt.en(R.string.service_car_connected_controls));
        explains(R.string.intercom_lastheard_ex_connected_title, Txt.en(R.string.service_car_connected_media));
        explains(R.string.intercom_lastheard_ex_disconnected_title, Txt.en(R.string.service_car_disconnected));
        explains(R.string.intercom_lastheard_ex_opened_title, Txt.en(R.string.intercom_car_history_opened_controls));
        explains(R.string.intercom_lastheard_ex_opened_title, Txt.en(R.string.intercom_car_history_opened_media));
        explains(R.string.intercom_lastheard_ex_noreply_title, Txt.en(R.string.packet_cov_event_no_reply, "Rptr"));
        explains(R.string.intercom_lastheard_ex_keyed_title, Txt.en(R.string.packet_cov_event_keyed, "Rptr", -90, "S7"));
        explains(R.string.intercom_lastheard_ex_coverage_title, Txt.en(R.string.service_cov_car_on));
        explains(R.string.intercom_lastheard_ex_coverage_title, Txt.en(R.string.packet_cov_event_no_permission));
        explains(R.string.intercom_lastheard_ex_rxonly_title, Txt.en(R.string.service_tx_blocked_receive_only));
        explains(R.string.intercom_lastheard_ex_toolong_title, Txt.en(R.string.service_tx_too_long_limit, "voice", 40, "30 s"));
        explains(R.string.intercom_lastheard_ex_toolong_title, Txt.en(R.string.service_tx_too_long_sender, "voice", 40, 28));
        explains(R.string.intercom_lastheard_ex_busy_title, Txt.en(R.string.service_channel_busy));
        explains(R.string.intercom_lastheard_ex_repeaterid_title, Txt.en(R.string.packet_morse_heard_repeater, "W1AW", "W1AW", "600", 20));
        explains(R.string.intercom_lastheard_ex_morse_title, Txt.en(R.string.packet_morse_heard, "CQ", "600", 20));
        explains(R.string.intercom_lastheard_ex_keyword_title, Txt.en(R.string.service_keyword_heard, "fire"));
        explains(R.string.intercom_lastheard_ex_notsent_title, Txt.en(R.string.packet_event_not_sent_analog, "APRS"));
        explains(R.string.intercom_lastheard_ex_notsent_title, Txt.en(R.string.packet_event_not_sent_dmr, "APRS"));
    }
}
