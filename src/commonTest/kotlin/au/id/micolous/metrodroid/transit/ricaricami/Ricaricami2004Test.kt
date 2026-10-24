/*
 * Ricaricami2004Test.kt
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

import au.id.micolous.metrodroid.card.ultralight.UltralightCard
import au.id.micolous.metrodroid.card.ultralight.UltralightPage
import au.id.micolous.metrodroid.ui.ListItem
import au.id.micolous.metrodroid.util.ImmutableByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dumps captured while travelling with real tickets (2025-03/04):
 *
 * - [DUMP_SINGLE_USED]: single-ride ticket, tap-in 08:10 at Rondò, tap-out 08:59 at
 *   Rho Fiera (pages 24-26 and 30-32); one-way counter 0 reports no rides left.
 *
 * - [DUMP_CARNET_RECHARGED]: 10-ride carnet recharged at 10 rides. A recharge clears
 *   validation slot A; slot B still holds the last validation before the recharge
 *   (tap-out 00:48 at Rondò). Note the timestamp word 0x00C0 without the 0x4000 day
 *   marker. Counter 0 = rides 10.
 */
private const val DUMP_SINGLE_USED = """
04FD87F6 32881D95 32480000 00000000
86080000 00000000 84BEB8B7 DB167269
00000000 CEEDBD7F 00000000 00000000
00000000 00000000 00000000 00000000
00000000 38071203 E5D7E13A FD6103D3
F923EC02 36D22000 00000000 00000000
FE47A804 61582034 813D5C40 00000000
00000000 00000001 FE486C04 62582032
B13D5C40 00000000 00000000 00000001
000000BD 000000FF 00050000 00000000
00000000
"""

private const val DUMP_CARNET_RECHARGED = """
04FCFE8E 3A7E1C90 C8480000 00000000
86080000 00000000 C5B0F647 DC1AEF17
C5CB0B26 C9F85CAB 38071203 E5D7ED3C
3D6103D3 F9526C02 2BD42000 00000000
00000000 38071203 E5D7ED3E 0D6103D3
F9525C02 2B7E2000 00000000 00000000
00000000 00000000 00000000 00000000
00000000 00000001 FB00C004 62582034
81041C41 00000000 00000000 00000000
000000BD 000000FF 00050000 00000000
00000000
"""

private fun pagesOf(dump: String): List<UltralightPage> =
        dump.trim().split(Regex("\\s+")).map { UltralightPage(ImmutableByteArray.fromHex(it)) }

private fun cardOf(dump: String, counters: List<Int> = emptyList()): UltralightCard =
        UltralightCard(
                cardModel = "MF0UL21",
                pages = pagesOf(dump),
                counters = counters)

class Ricaricami2004Test {
    @Test
    fun testSingleRideUsedCard() {
        // Counters as read on the card at this state: counter0 = ride complement,
        // counter1 = 0, counter2 grows by 256/512/768 per event.
        val card = cardOf(DUMP_SINGLE_USED, counters = listOf(131071, 0, 1792))

        assertTrue(Ricaricami2004UltralightTransitFactory.check(card))

        val id = Ricaricami2004UltralightTransitFactory.parseTransitIdentity(card)
        assertNotNull(id)
        assertEquals(Ricaricami2004UltralightTransitFactory.NAME, id.name)
        // ISO 14443 UID: 3 bytes of page 0 + the 4 bytes of page 1.
        assertEquals("04fd8732881d95", id.serialNumber)

        val data = Ricaricami2004UltralightTransitFactory.parseTransitData(card)
        assertIs<Ricaricami2004UltralightTransitData>(data)
        assertEquals("04fd8732881d95", data.serialNumber)

        // One ride used at this state.
        assertEquals(0, data.rides)

        // Validation slots: tap-in 08:10 Rondò (ATM), then tap-out 08:59 Rho (ATM).
        val taps = data.taps
        assertEquals(2, taps.size)
        assertEquals(490, taps[0].timeMinutes)
        assertEquals(0x34, taps[0].stationCode)
        assertEquals(0x61, taps[0].kindCode)
        assertEquals(539, taps[1].timeMinutes)
        assertEquals(0x32, taps[1].stationCode)
        assertEquals(0x62, taps[1].kindCode)

        // Raw slot data is shown for undecoded fields ("sviluppi" scope).
        val info = assertNotNull(data.info)
        assertTrue(info.any { it is ListItem && it.text1?.toString() == "Validation slot A (pages 6, 24-26)" })
        assertTrue(info.any { it is ListItem && it.text1?.toString() == "Contract 1 (pages 17-21)" })
    }

    @Test
    fun testCarnetRechargedCard() {
        // Counter0 complement of 10 rides after the recharge; counter1 = 0.
        val card = cardOf(DUMP_CARNET_RECHARGED, counters = listOf(262133, 0, 13312))

        assertTrue(Ricaricami2004UltralightTransitFactory.check(card))

        val data = Ricaricami2004UltralightTransitFactory.parseTransitData(card)
        assertIs<Ricaricami2004UltralightTransitData>(data)

        assertEquals(10, data.rides)

        // The recharge cleared slot A; slot B holds the last tap-out of the old block:
        // 00:48 (48 minutes of day) at Rondò. This exercises the timestamp parse
        // without the 0x4000 day marker.
        val taps = data.taps
        assertEquals(1, taps.size)
        assertEquals(48, taps[0].timeMinutes)
        assertEquals(0x34, taps[0].stationCode)
        assertEquals(0x62, taps[0].kindCode)

        val info = assertNotNull(data.info)
        // The reload zeroed the event pages of slot A (24-26), but its MAC page (6)
        // is still non-zero, so the raw item is still shown.
        assertTrue(info.any { it is ListItem && it.text1?.toString() == "Validation slot A (pages 6, 24-26)" })
        assertTrue(info.any { it is ListItem && it.text1?.toString() == "Contract 1 (pages 17-21)" })
    }

    @Test
    fun testBlankCardNotRecognized() {
        val pages = pagesOf("""
            047926D3 228E3A80 16480000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
            00000000
        """)

        val card = UltralightCard(cardModel = "MF0UL21", pages = pages)
        assertFalse(Ricaricami2004UltralightTransitFactory.check(card))
    }

    @Test
    fun testShortCardDoesNotCrash() {
        // Ultralight (MF0ICU1) has only 16 pages; pages 4 and 36 cannot both exist here.
        val pages = pagesOf("""
            047926D3 228E3A80 16480000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
            00000000 00000000 00000000 00000000
        """)

        val card = UltralightCard(cardModel = "MF0ICU1", pages = pages)
        assertFalse(Ricaricami2004UltralightTransitFactory.check(card))
    }

    @Test
    fun testUnauthorizedPagesDoNotCrash() {
        val pages = pagesOf(DUMP_SINGLE_USED).mapIndexed { idx, page ->
            if (idx == 4 || idx == 36) UltralightPage.unauthorized() else page
        }

        val card = UltralightCard(cardModel = "MF0UL21", pages = pages)
        assertFalse(Ricaricami2004UltralightTransitFactory.check(card))
    }
}
