package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests de [RegistrePortEditeurActif] (mission IA, ADR 0122) : UN SEUL
 * port actif (remplacement), retrait volontaire, lecture après retrait.
 */
class RegistrePortEditeurActifTest {
    @Test
    fun `un port enregistré est lisible`() {
        val registre = RegistrePortEditeurActif()
        val port = portFaux(revision = 1L)

        registre.enregistrer(port)

        assertSame(port, registre.actif())
    }

    @Test
    fun `l'enregistrement REMPLACE le port précédent (un seul éditeur actif)`() {
        val registre = RegistrePortEditeurActif()
        val premier = portFaux(revision = 1L)
        val second = portFaux(revision = 2L)

        registre.enregistrer(premier)
        registre.enregistrer(second)

        assertSame(second, registre.actif())
    }

    @Test
    fun `enregistrer null retire le port (onglet fermé)`() {
        val registre = RegistrePortEditeurActif()
        registre.enregistrer(portFaux(revision = 1L))

        registre.enregistrer(null)

        assertNull(registre.actif())
    }

    @Test
    fun `sans enregistrement, le port est null (processus recréé)`() {
        assertNull(RegistrePortEditeurActif().actif())
    }

    /** Port minimal de test — le contrat sera implémenté par l'éditeur (IA-5). */
    private fun portFaux(revision: Long): PortEditeurActif =
        object : PortEditeurActif {
            override val cheminRelatifFichier: String? = null

            override val selection: SelectionEditeur? = null

            override val diagnostics: List<DiagnosticEditeur> = emptyList()

            override fun revision(): Long = revision

            override fun appliquerRemplacement(
                debut: Int,
                fin: Int,
                texte: String,
            ): AppResult<Unit> = AppResult.Success(Unit)
        }
}
