package au.id.micolous.metrodroid.transit.ricaricami

import au.id.micolous.metrodroid.card.ultralight.UltralightCard
import au.id.micolous.metrodroid.card.ultralight.UltralightCardTransitFactory
import au.id.micolous.metrodroid.card.CardType
import au.id.micolous.metrodroid.multi.R
import au.id.micolous.metrodroid.multi.Localizer
import au.id.micolous.metrodroid.transit.CardInfo
import au.id.micolous.metrodroid.transit.TransitIdentity
import au.id.micolous.metrodroid.transit.TransitData
import au.id.micolous.metrodroid.transit.TransitRegion
import au.id.micolous.metrodroid.util.ImmutableByteArray
import java.math.BigInteger

object Ricaricami2004UltralightTransitFactory : UltralightCardTransitFactory {

    private const val NAME_RES = R.string.card_name_ricaricami2004

    private val CARD_INFO = CardInfo(
        name = NAME_RES,
        cardType = CardType.MifareUltralight,
        region = TransitRegion.ITALY,
        imageId = R.drawable.ic_contactless, // generic icon for now
        imageAlphaId = R.drawable.iso7810_id1_alpha,
        preview = true
    )

    override val allCards: List<CardInfo>
        get() = listOf(CARD_INFO)

    /**
     * Heuristics to detect Ricaricami EV1 (samples):
     *  - page 4 first two bytes == 0x8608 (operatore osservato)
     *  - page 36 (CFG0) last byte == 0xBD (config osservato)
     *  - serial plausibile decodificabile da pagine 17..19
     */
    override fun check(card: UltralightCard): Boolean {
        try {
            val p4 = card.getPage(4).data
            val p36 = card.getPage(36).data
            if (p4.size < 2 || p36.size < 4) return false
            val op = p4.byteArrayToInt(0, 2)
            if (op != 0x8608) return false
            if ((p36[3].toInt() and 0xff) != 0xBD) return false
            return decodeSerial(card) != null
        } catch (e: Exception) {
            return false
        }
    }

    override fun parseTransitIdentity(card: UltralightCard): TransitIdentity? {
        val serial = decodeSerial(card)
        val name = Localizer.localizeString(NAME_RES)
        return TransitIdentity(name, serial)
    }

    override fun parseTransitData(card: UltralightCard): TransitData? {
        // Implementazione saldo/log dopo raccolta di più dump.
        return null
    }

    // --- helper decoding methods ---

    private fun readRange(card: UltralightCard, from: Int, countPages: Int): ImmutableByteArray =
        card.readPages(from, countPages)

    private fun decodeBCD(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val hi = (b.toInt() ushr 4) and 0x0f
            val lo = b.toInt() and 0x0f
            sb.append(hi)
            sb.append(lo)
        }
        return sb.toString().trimStart('0')
    }

    private fun onlyDigits(s: String) = s.isNotEmpty() && s.all { it.isDigit() }

    private fun decodeSerial(card: UltralightCard): String? {
        val raw = try { readRange(card, 17, 3).toHexString() } catch (_: Exception) { null } ?: return null
        val rawBytes = ImmutableByteArray.fromHex(raw).dataCopy

        // 1) big-endian integer
        try {
            val big = BigInteger(1, rawBytes).toString(10)
            if (onlyDigits(big) && big.length in 10..22) return big.trimStart('0')
        } catch (_: Exception) {}

        // 2) little-endian integer
        try {
            val rev = rawBytes.reversedArray()
            val little = BigInteger(1, rev).toString(10)
            if (onlyDigits(little) && little.length in 10..22) return little.trimStart('0')
        } catch (_: Exception) {}

        // 3) BCD
        val bcd = decodeBCD(rawBytes)
        if (onlyDigits(bcd) && bcd.length in 10..22) return bcd

        // 4) ASCII digits extraction
        val ascii = rawBytes.map { if (it.toInt() in 0x30..0x39) it.toInt().toChar() else '.' }.joinToString("")
        val digits = ascii.filter { it.isDigit() }
        if (onlyDigits(digits) && digits.length in 10..22) return digits

        return null
    }
}
