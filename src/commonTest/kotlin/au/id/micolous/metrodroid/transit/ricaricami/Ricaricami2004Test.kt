package au.id.micolous.metrodroid.transit.ricaricami

import au.id.micolous.metrodroid.serializers.CardSerializer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

class Ricaricami2004Test {
    @Test
    fun testDetectAndParseSerialFromDump() {
        val jsonText = File("src/commonTest/resources/testdata/Ricaricami.json").readText()
        val card = CardSerializer.fromPersist(jsonText)
        val ul = card.mifareUltralight
        assertNotNull(ul, "Ultralight card must deserialize from fixture")
        val ok = Ricaricami2004UltralightTransitFactory.check(ul!!)
        assertTrue(ok, "Factory should recognize the sample card")
        val id = Ricaricami2004UltralightTransitFactory.parseTransitIdentity(ul)
        assertNotNull(id?.serialNumber, "Serial should be extracted (or at least not null)")
    }
}
