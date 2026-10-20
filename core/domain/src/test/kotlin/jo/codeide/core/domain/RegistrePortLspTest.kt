package jo.codeide.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests de [RegistrePortLsp] (lot LSP-3, ADR 0128 §4) : miroir observable
 * de `RegistrePortEditeurActif` — enregistrement, remplacement, retrait
 * par `null` et OBSERVATION de l'apparition/disparition du port.
 */
class RegistrePortLspTest {
    @Test
    fun `sans enregistrement le port observé est absent`() {
        val registre = RegistrePortLsp()

        assertNull(registre.port.value)
    }

    @Test
    fun `un port enregistré est observable`() {
        val registre = RegistrePortLsp()
        val port = portFaux()

        registre.enregistrer(port)

        assertSame(port, registre.port.value)
    }

    @Test
    fun `l'enregistrement REMPLACE le port précédent (un seul espace)`() {
        val registre = RegistrePortLsp()
        val premier = portFaux()
        val second = portFaux()

        registre.enregistrer(premier)
        registre.enregistrer(second)

        assertSame(second, registre.port.value)
    }

    @Test
    fun `enregistrer null retire le port (éditeur refermé)`() {
        val registre = RegistrePortLsp()
        registre.enregistrer(portFaux())

        registre.enregistrer(null)

        assertNull(registre.port.value)
    }

    @Test
    fun `l'observation suit l'apparition puis la disparition`() =
        runTest {
            val registre = RegistrePortLsp()
            val port = portFaux()

            registre.enregistrer(port)
            assertEquals(port, registre.port.first { it != null })

            registre.enregistrer(null)
            assertNull(registre.port.first { it == null })
        }

    /** Port factice — le registre ne touche QUE l'identité, jamais le port. */
    private fun portFaux(): PortLsp =
        object : PortLsp {
            override val moteurs: StateFlow<MoteursLsp> = MutableStateFlow(MoteursLsp.INACTIFS)

            override fun tailleCacheOctets(): Long? = null

            override fun reindexer(): Boolean = false

            override fun viderCache(): ResultatVidageCacheLsp? = null
        }
}
