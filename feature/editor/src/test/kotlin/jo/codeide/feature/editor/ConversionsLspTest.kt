package jo.codeide.feature.editor

import jo.codeide.core.model.LspSettings
import jo.codeide.lsp.host.ConfigurationLsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Test de la FRONTIÈRE [versConfigurationHote] (lot LSP-2, ADR 0128 §4)
 * — champ à champ, sans transformation : les bornes vivent dans les
 * DEUX modèles (mêmes contrats : `null` = Auto, `0` = surveillance
 * coupée, défauts = comportement historique de l'hôte v1.2.0).
 */
class ConversionsLspTest {
    @Test
    fun `defauts - configuration historique de l hote`() {
        val configuration = LspSettings.parDefaut().versConfigurationHote()
        assertEquals(ConfigurationLsp.parDefaut(), configuration)
        assertNull("Auto = pas de plafond", configuration.budgetCacheOctets)
        assertEquals(5_000L, configuration.surveillanceFichiersMs)
    }

    @Test
    fun `reglage explicite - budget et surveillance transportes tels quels`() {
        val configuration =
            LspSettings(
                budgetCacheOctets = 1024L * LspSettings.OCTETS_PAR_MO,
                surveillanceFichiersMs = 30_000L,
            ).versConfigurationHote()
        assertEquals(1024L * 1024 * 1024, configuration.budgetCacheOctets)
        assertEquals(30_000L, configuration.surveillanceFichiersMs)
    }

    @Test
    fun `surveillance coupee - periode nulle transportee`() {
        assertEquals(
            0L,
            LspSettings(surveillanceFichiersMs = 0)
                .versConfigurationHote()
                .surveillanceFichiersMs,
        )
    }
}
