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
 * Dumps from https://github.com/metrodroid/metrodroid/issues/875 (2025-04):
 *
 * - [DUMP_LOADED]: card bought (loaded) at a ticket machine, but not used afterwards;
 *   the contract is only in slot 1 (pages 17-21).
 *
 * - [DUMP_USED]: card used to enter a station, then recharged afterwards; both contract
 *   slots (pages 10-14 and 17-21) and the validation log (pages 22-34) contain data.
 */
private const val DUMP_LOADED = """
0444B47C 72A91A90 51480000 00000000
86080000 00000000 00000000 B4629F47
00000000 E45C689D 00000000 00000000
00000000 00000000 00000000 00000000
00000000 38071203 E5D7E138 6D6103D3
F923EC02 36F02000 00000000 00000000
00000000 00000000 00000000 00000000
00000000 00000000 00000000 00000000
00000000 00000000 00000000 00000001
000000BD 000000FF 00050000 00000000
00000000
"""

private const val DUMP_USED = """
0452F32D 92171D91 09480000 00000000
86080000 00000000 04E4A6CA 3727D51C
80C006F8 0EB769CB 38071203 E5D7E138
4D6103D3 F9A3FC04 5E442000 00000000
00000000 38071203 E5D4C138 3F49022B
81F48212 53F97F28 C045FEF0 01980000
FE4EFC08 A15C2045 7177FC40 18008AE0
02000000 00000001 00000000 00000000
00000000 00000000 00000000 00000000
000000BD 000000FF 00050000 00000000
00000000
"""

private fun pagesOf(dump: String): List<UltralightPage> =
        dump.trim().split(Regex("\\s+")).map { UltralightPage(ImmutableByteArray.fromHex(it)) }

private fun cardOf(dump: String): UltralightCard = UltralightCard(
        cardModel = "MF0UL21",
        pages = pagesOf(dump))

class Ricaricami2004Test {
    @Test
    fun testLoadedCard() {
        val card = cardOf(DUMP_LOADED)

        assertTrue(Ricaricami2004UltralightTransitFactory.check(card))

        val id = Ricaricami2004UltralightTransitFactory.parseTransitIdentity(card)
        assertNotNull(id)
        assertEquals(Ricaricami2004UltralightTransitFactory.NAME, id.name)
        // ISO 14443 UID: 3 bytes of page 0 + the 4 bytes of page 1.
        assertEquals("0444b472a91a90", id.serialNumber)

        val data = Ricaricami2004UltralightTransitFactory.parseTransitData(card)
        assertIs<Ricaricami2004UltralightTransitData>(data)
        assertEquals("0444b472a91a90", data.serialNumber)
        // Loaded card: contract 1 (pages 17-21) is present; contract 2 (pages 10-14)
        // is empty.
        val info = assertNotNull(data.info)
        assertTrue(info.any { it is ListItem && it.text1?.toString() == "Contract 1 (pages 17-21)" })
        assertFalse(info.any { it is ListItem && it.text1?.toString() == "Contract 2 (pages 10-14)" })
        assertFalse(info.any { it is ListItem && it.text1?.toString() == "Validation log (pages 22-34)" })
    }

    @Test
    fun testUsedCard() {
        val card = cardOf(DUMP_USED)

        assertTrue(Ricaricami2004UltralightTransitFactory.check(card))

        val id = Ricaricami2004UltralightTransitFactory.parseTransitIdentity(card)
        assertNotNull(id)
        assertEquals("0452f392171d91", id.serialNumber)

        val data = Ricaricami2004UltralightTransitFactory.parseTransitData(card)
        assertIs<Ricaricami2004UltralightTransitData>(data)
        // Used card: both contract slots and the validation log contain data.
        val info = assertNotNull(data.info)
        assertTrue(info.any { it is ListItem && it.text1?.toString() == "Contract 1 (pages 17-21)" })
        assertTrue(info.any { it is ListItem && it.text1?.toString() == "Contract 2 (pages 10-14)" })
        assertTrue(info.any { it is ListItem && it.text1?.toString() == "Validation log (pages 22-34)" })
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
            00000000 00000000 00000000
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
            00000000 00000000 00000000
        """)

        val card = UltralightCard(cardModel = "MF0ICU1", pages = pages)
        assertFalse(Ricaricami2004UltralightTransitFactory.check(card))
    }

    @Test
    fun testUnauthorizedPagesDoNotCrash() {
        val pages = pagesOf(DUMP_LOADED).mapIndexed { idx, page ->
            if (idx == 4 || idx == 36) UltralightPage.unauthorized() else page
        }

        val card = UltralightCard(cardModel = "MF0UL21", pages = pages)
        assertFalse(Ricaricami2004UltralightTransitFactory.check(card))
    }
}
