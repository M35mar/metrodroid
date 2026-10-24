/*
 * Ricaricami2004UltralightTransitFactory.kt
 *
 * Copyright 2026 M35mar
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package au.id.micolous.metrodroid.transit.ricaricami

import au.id.micolous.metrodroid.card.CardType
import au.id.micolous.metrodroid.card.UnauthorizedException
import au.id.micolous.metrodroid.card.ultralight.UltralightCard
import au.id.micolous.metrodroid.card.ultralight.UltralightCardTransitFactory
import au.id.micolous.metrodroid.multi.FormattedString
import au.id.micolous.metrodroid.multi.Parcelize
import au.id.micolous.metrodroid.multi.R
import au.id.micolous.metrodroid.transit.CardInfo
import au.id.micolous.metrodroid.transit.TransitIdentity
import au.id.micolous.metrodroid.transit.TransitRegion
import au.id.micolous.metrodroid.transit.serialonly.SerialOnlyTransitData
import au.id.micolous.metrodroid.ui.HeaderListItem
import au.id.micolous.metrodroid.ui.ListItem
import au.id.micolous.metrodroid.ui.ListItemInterface
import au.id.micolous.metrodroid.util.ImmutableByteArray

/**
 * "RicaricaMi" is the rechargeable transit ticket of ATM (Azienda Trasporti Milanesi),
 * Milan, Italy. The 2024 redesign is on a MIFARE Ultralight EV1 MF0UL21 (41 pages).
 *
 * There is no public documentation of the data format. Everything below is derived from
 * an event-by-event diff of the dumps collected in the user's spreadsheets (Ticket 1-10:
 * purchase, rides, recharges, February-May 2025) plus the dumps attached to
 * https://github.com/metrodroid/metrodroid/issues/875.
 *
 * Card layout:
 *
 * - 0-2:    ISO 14443 UID (7 bytes: 3 bytes of page 0, 4 bytes of page 1)
 * - 3:      OTP (empty in all observed dumps)
 * - 4:      [86080000] ATM operator marker (constant across observed cards)
 * - 5:      empty
 * - 6-9:    card/system data (MAC-protected; rewritten at reload)
 * - 10-14:  Contract (slot 2; used from the first recharge): header word 0x38071203
 * - 15-16:  empty
 * - 17-21:  Contract (slot 1; used on freshly loaded cards)
 * - 22-24:  empty
 * - Validation log, two alternating slots:
 *   - Slot A: page 6 = MAC, pages 24-26 = event
 *   - Slot B: page 7 = MAC, pages 30-32 = event
 * - 29:       [00000001/00] flag, toggles on some writes (meaning unknown)
 * - 35:     [00000001] status flag
 * - 36:     dynamic configuration: lock bytes / CHK byte = 0xBD (observed)
 * - 37:     CFG0: AUTH0 = 0xFF (data is publicly readable)
 * - 38-40:  CFG1 / PWD / PACK (not read)
 *
 * Validation event (pages 24-26 / 30-32), observed on 50+ events:
 *
 * - Timestamp page (24 / 30):
 *   - bits 8..23: 16-bit word; the low 13 bits equal 4 x minutes-since-midnight,
 *     the upper 3 bits hold an unresolved per-day/state code. Encoded minutes may
 *     therefore be off by multiples of 0x2000 from the 0x4000 base seen on some days.
 *   - byte 3: 0x04 = ATM reader (metro/Passante), 0x08 = Trenord reader (FS).
 *   - byte 0: status/MAC-related, varies (0xFB..0xFF).
 * - Event page (25 / 31): [kind][0x58][0x20][station]:
 *   - byte 0 event kind: 0x61 = tap-in (ATM), 0x62 = tap-out (ATM), 0x66 = tap-in
 *     again (after a previous exit), 0xA1/A2 = tap-in/out (Trenord), 0xA6 = extra
 *     scan at a Trenord train reader.
 *   - byte 3 station code (ATM in 0x3x, Trenord in 0x5x); see [STATIONS].
 * - Info page (26 / 32): [kind][reader-dependent bytes][0x5C/0x9C/0x7C..][0x40].
 *   Middle bytes vary per reader bank; not decoded.
 *
 * The contract pages and the counter changes per tap (counter 2 moves by 256/512/768)
 * are shown as raw values ("sviluppi") until their exact semantics are pinned down.
 */
@Parcelize
data class Ricaricami2004UltralightTransitData(
        private val mSerial: String?,
        private val mRides: Int? = null,
        private val mCountersRaw: List<Int> = emptyList(),
        private val mKeyData: ImmutableByteArray? = null,
        private val mContract1: ImmutableByteArray? = null,
        private val mContract2: ImmutableByteArray? = null,
        private val mTaps: List<Ricaricami2004Tap> = emptyList(),
        private val mLogA: ImmutableByteArray? = null,
        private val mLogB: ImmutableByteArray? = null) : SerialOnlyTransitData() {

    override val serialNumber get() = mSerial

    override val cardName get() = Ricaricami2004UltralightTransitFactory.NAME

    /** Test/parser-friendly view of the remaining rides (null = unknown). */
    val rides: Int? get() = mRides

    /** Decoded validation slots, slot A (oldest) first. */
    val taps: List<Ricaricami2004Tap> get() = mTaps

    /**
     * The card content is fully readable (AUTH0 = 0xFF), but the contract data is MAC
     * protected and still undecoded, so it is presented as raw hex.
     */
    override val reason get() = Reason.MORE_RESEARCH_NEEDED

    override val extraInfo: List<ListItemInterface>
        get() {
            val ret = mutableListOf<ListItemInterface>()

            if (mRides != null) {
                ret += ListItem(R.string.ricaricami2004_rides_remaining,
                        FormattedString(mRides.toString()))
            }
            // Raw counters, per "rich development" scope.
            if (mCountersRaw.isNotEmpty()) {
                ret += ListItem(R.string.ricaricami2004_counter_0,
                        FormattedString(mCountersRaw.joinToString(" ") { it.toString() }))
            }
            if (mTaps.isNotEmpty()) {
                ret += HeaderListItem(R.string.ricaricami2004_taps)
                mTaps.forEach { ret += it.toListItems() }
            }
            fun add(name: String, data: ImmutableByteArray?) {
                if (data != null)
                    ret.add(ListItem(FormattedString(name), data.toHexDump()))
            }
            add("Card data (page 9)", mKeyData)
            add("Contract 2 (pages 10-14)", mContract2)
            add("Contract 1 (pages 17-21)", mContract1)
            add("Validation slot A (pages 6, 24-26)", mLogA)
            add("Validation slot B (pages 7, 30-32)", mLogB)
            return ret
        }
}

object Ricaricami2004UltralightTransitFactory : UltralightCardTransitFactory {

    const val NAME = "RicaricaMi (2024)"

    /** ATM operator marker in page 4 (first two bytes, big-endian). */
    private const val ATM_MARKER = 0x8608

    /** Last byte of page 36 (dynamic configuration "CHK" byte) as observed on ATM cards. */
    private const val ATM_CHK_BYTE = 0xBD

    /**
     * Station codes from byte 3 of the event pages (raw observations mined from the
     * dataset extendable here). ATM gates use codes 0x30-0x3F, Trenord gates 0x50+.
     */
    private val STATIONS = mapOf(
            0x32 to "Rho Fiera (ATM)",
            0x33 to "Lima M1",
            0x34 to "Rondò (M1/M2)",
            0x36 to "Piola M2",
            0x3A to "Lambrate M2",
            0x51 to "Porta Venezia (Trenord)",
            0x52 to "Rho Fiera (Trenord)")

    private val CARD_INFO = CardInfo(
            name = NAME,
            cardType = CardType.MifareUltralight,
            region = TransitRegion.ITALY,
            locationId = R.string.location_milan,
            imageId = R.drawable.ricaricami,
            imageAlphaId = R.drawable.iso7810_id1_alpha,
            preview = true)

    override val allCards: List<CardInfo>
        get() = listOf(CARD_INFO)

    override fun check(card: UltralightCard): Boolean =
            try {
                // Page 4: ATM operator marker (MF0UL21 has user memory here; smaller
                // cards would throw IndexOutOfBoundsException, caught below).
                card.getPage(4).data.byteArrayToInt(0, 2) == ATM_MARKER &&
                        // Page 36, last byte: dynamic configuration CHK byte observed on
                        // ATM cards.
                        (card.getPage(36).data[3].toInt() and 0xff) == ATM_CHK_BYTE
            } catch (e: IndexOutOfBoundsException) {
                false
            } catch (e: UnauthorizedException) {
                false
            }

    override fun parseTransitIdentity(card: UltralightCard) =
            TransitIdentity(NAME, getSerial(card))

    override fun parseTransitData(card: UltralightCard) = Ricaricami2004UltralightTransitData(
            mSerial = getSerial(card),
            mRides = rideOf(card.counters),
            mCountersRaw = card.counters,
            mKeyData = readIfPresent(card, 9, 1),
            mContract2 = readIfPresent(card, 10, 5),
            mContract1 = readIfPresent(card, 17, 5),
            mTaps = listOfNotNull(
                    parseTap(card, 24),
                    parseTap(card, 30)),
            mLogA = readValidationSlot(card, 6, 24),
            mLogB = readValidationSlot(card, 7, 30))

    /**
     * Remaining rides, consistent across every event of the dataset: the low 16 bits
     * of counter 0 hold the inverted ride count of the current ticket block:
     * rides = 65535 - (counter0 & 0xFFFF).
     */
    private fun rideOf(counters: List<Int>): Int? =
            counters.getOrNull(0)?.let { 65535 - (it and 0xFFFF) }

    /**
     * Decodes one validation slot (the two alternate tap events).
     * @param tStart first event page (timestamp page); MAC page is tStart - 18
     */
    private fun parseTap(card: UltralightCard, tStart: Int): Ricaricami2004Tap? =
            try {
                val wTimestamp = card.getPage(tStart).data
                val wEvent = card.getPage(tStart + 1).data
                val wInfo = card.getPage(tStart + 2).data
                if (wTimestamp.isAllZero() || wEvent.isAllZero()) null
                else Ricaricami2004Tap.parse(if (tStart == 24) "A" else "B",
                        wTimestamp, wEvent, wInfo, STATIONS)
            } catch (e: IndexOutOfBoundsException) {
                null
            } catch (e: UnauthorizedException) {
                null
            }

    private fun readValidationSlot(card: UltralightCard, macPage: Int,
                                   timeStampPage: Int): ImmutableByteArray? =
            try {
                val data = card.readPages(macPage, 1) +
                        card.readPages(timeStampPage, 3)
                data.takeUnless { it.isAllZero() }
            } catch (e: IndexOutOfBoundsException) {
                null
            } catch (e: UnauthorizedException) {
                null
            }

    /**
     * The ticket serial number is the ISO 14443 UID: 3 bytes of page 0 followed by the
     * first 4 bytes of page 1.
     */
    private fun getSerial(card: UltralightCard): String? = try {
        (card.getPage(0).data.sliceOffLen(0, 3) + card.getPage(1).data).toHexString()
    } catch (e: UnauthorizedException) {
        null
    }

    private fun readIfPresent(card: UltralightCard, startPage: Int, pageCount: Int):
            ImmutableByteArray? = try {
        card.readPages(startPage, pageCount).takeUnless { it.isAllZero() }
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: UnauthorizedException) {
        null
    }
}
