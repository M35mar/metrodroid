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
import au.id.micolous.metrodroid.ui.ListItem
import au.id.micolous.metrodroid.ui.ListItemInterface
import au.id.micolous.metrodroid.util.ImmutableByteArray

/**
 * "RicaricaMi" is the rechargeable transit ticket of ATM (Azienda Trasporti Milanesi),
 * Milan, Italy. The 2024 redesign is on a MIFARE Ultralight EV1 MF0UL21 (41 pages).
 *
 * There is no public documentation of the data format. Everything below is based on
 * observations of dumps at https://github.com/metrodroid/metrodroid/issues/875 (2025-04
 * and 2025-06), so the decoder is marked as "preview".
 *
 * Card layout (named per page-diff of the dumps above):
 *
 * - 0-2:    ISO 14443 UID (7 bytes: 3 bytes of page 0, 4 bytes of page 1)
 * - 3:      OTP (empty)
 * - 4:      [86080000] ATM operator marker (constant across observed cards)
 * - 5:      empty
 * - 6-9:    Card/MAC data (constant per card, likely card-SMAC related)
 * - 10-14:  Contract (slot) — header word 0x38071203 when loaded (MAC protected data)
 * - 15-16:  empty
 * - 17-21:  Contract (slot) — header word 0x38071203 when loaded; on freshly loaded
 *           cards this is the only slot with data.
 * - 22-34:  validation log (per Excel analysis of issue 875: pages 24-26 = entry event;
 *           30-32 = exit event; all MAC protected)
 * - 35:     [00000001] status flag
 * - 36:     dynamic configuration: lock bytes / CHK byte = 0xBD (observed)
 * - 37:     CFG0: AUTH0 = 0xFF (data is publicly readable)
 * - 38-40:  CFG1 / PWD / PACK (not dumped)
 */
@Parcelize
data class Ricaricami2004UltralightTransitData(
        private val mSerial: String?,
        private val mContract1: ImmutableByteArray? = null,
        private val mContract2: ImmutableByteArray? = null,
        private val mKeyData: ImmutableByteArray? = null,
        private val mValidationLog: ImmutableByteArray? = null) : SerialOnlyTransitData() {

    override val serialNumber get() = mSerial

    override val cardName get() = Ricaricami2004UltralightTransitFactory.NAME

    /**
     * The card content is fully readable (AUTH0 = 0xFF), but the format of the contract
     * and validation data is not publicly documented, so it is presented as raw hex.
     */
    override val reason get() = Reason.MORE_RESEARCH_NEEDED

    override val extraInfo: List<ListItemInterface>
        get() {
            val ret = mutableListOf<ListItemInterface>()
            fun add(name: String, data: ImmutableByteArray?) {
                if (data != null)
                    ret.add(ListItem(FormattedString(name), data.toHexDump()))
            }
            add("Card data (page 9)", mKeyData)
            add("Contract 2 (pages 10-14)", mContract2)
            add("Contract 1 (pages 17-21)", mContract1)
            add("Validation log (pages 22-34)", mValidationLog)
            return ret
        }
}

object Ricaricami2004UltralightTransitFactory : UltralightCardTransitFactory {

    const val NAME = "RicaricaMi (2024)"

    /** ATM operator marker in page 4 (first two bytes, big-endian). */
    private const val ATM_MARKER = 0x8608

    /** Last byte of page 36 (dynamic configuration "CHK" byte) as observed on ATM cards. */
    private const val ATM_CHK_BYTE = 0xBD

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
                // Page 4: ATM operator marker (MF0UL21 has user memory here, smaller cards
                // would throw IndexOutOfBoundsException, which is caught below).
                card.getPage(4).data.byteArrayToInt(0, 2) == ATM_MARKER &&
                // Page 36, last byte: dynamic configuration CHK byte observed on ATM cards.
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
            mKeyData = readIfPresent(card, 9, 1),
            mContract2 = readIfPresent(card, 10, 5),
            mContract1 = readIfPresent(card, 17, 5),
            mValidationLog = readIfPresent(card, 22, 13))

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
