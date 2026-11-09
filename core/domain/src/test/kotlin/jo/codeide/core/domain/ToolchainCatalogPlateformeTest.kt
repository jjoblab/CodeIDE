package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de la majeure de plateforme exposée au moteur de modèles
 * (`plateformeMajeure`, lot L1 du chantier générateur, ADR 0153 §3) :
 * extraction de l'exigence `platform@android-36` du catalogue — source de
 * vérité unique, verrouillée aussi par `AlignementCatalogueTemplatesTest`
 * côté app.
 */
class ToolchainCatalogPlateformeTest {
    @Test
    fun `la plateforme du catalogue par défaut expose la majeure 36`() {
        assertEquals(36, ToolchainCatalog().plateformeMajeure)
    }

    @Test
    fun `la mineure d une plateforme est ignorée`() {
        val catalogue =
            ToolchainCatalog(
                requiredComponents =
                listOf(
                    ComponentRequirement("platform", "android-36.2"),
                ),
            )
        assertEquals(36, catalogue.plateformeMajeure)
    }

    @Test
    fun `un catalogue sans plateforme vaut zéro`() {
        val catalogue =
            ToolchainCatalog(
                requiredComponents =
                listOf(
                    ComponentRequirement("build-tools", "35.0.2"),
                ),
            )
        assertEquals(0, catalogue.plateformeMajeure)
    }

    @Test
    fun `une plateforme illisible vaut zéro plutôt qu une borne fausse`() {
        val catalogue =
            ToolchainCatalog(
                requiredComponents =
                listOf(
                    ComponentRequirement("platform", "???"),
                ),
            )
        assertEquals(0, catalogue.plateformeMajeure)
    }
}
