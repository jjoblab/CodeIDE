package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registre des sources d'aperçu (PE6-0, maquette « Partage de la
 * session ») : enregistrement, libération, tri déterministe du
 * sélecteur, remplacement à URI égale.
 */
class RegistreSourcesApercuTest {
    private class SourceFausse(
        override val uri: String,
        override val cheminRelatif: String,
        override val uriProjet: String? = null,
    ) : SourceApercu {
        override fun texte(): String = ""

        override fun remplacer(
            debut: Int,
            fin: Int,
            remplacement: String,
        ): Boolean = false

        override fun selection(): Pair<Int, Int> = 0 to 0

        override fun definirSelection(
            debut: Int,
            fin: Int,
        ) = Unit

        override fun annuler(): Boolean = false

        override fun retablir(): Boolean = false

        override fun ajouterEcouteur(ecouteur: SourceApercu.EcouteurMutation) = Unit

        override fun retirerEcouteur(ecouteur: SourceApercu.EcouteurMutation) = Unit
    }

    @Test
    fun `enregistre puis retrouve par uri`() {
        val registre = RegistreSourcesApercu()
        val source = SourceFausse("uri://a", "app/src/main/res/layout/a.xml")
        registre.enregistrer(source)
        assertEquals(source, registre.sourceDe("uri://a"))
    }

    @Test
    fun `uri absente - null`() {
        val registre = RegistreSourcesApercu()
        assertNull(registre.sourceDe("uri://absente"))
    }

    @Test
    fun `liberer purge la source`() {
        val registre = RegistreSourcesApercu()
        registre.enregistrer(SourceFausse("uri://a", "a.xml"))
        registre.liberer("uri://a")
        assertNull(registre.sourceDe("uri://a"))
        assertTrue(registre.sources().isEmpty())
    }

    @Test
    fun `remplacement a uri egale - la nouvelle gagne`() {
        val registre = RegistreSourcesApercu()
        registre.enregistrer(SourceFausse("uri://a", "a.xml"))
        val remplacement = SourceFausse("uri://a", "a.xml")
        registre.enregistrer(remplacement)
        assertEquals(1, registre.sources().size)
        assertEquals(remplacement, registre.sourceDe("uri://a"))
    }

    @Test
    fun `sources triees par chemin relatif - determinisme du selecteur`() {
        val registre = RegistreSourcesApercu()
        registre.enregistrer(SourceFausse("uri://c", "res/layout/c.xml"))
        registre.enregistrer(SourceFausse("uri://a", "app/res/layout/a.xml"))
        registre.enregistrer(SourceFausse("uri://b", "b.xml"))
        assertEquals(
            listOf("app/res/layout/a.xml", "b.xml", "res/layout/c.xml"),
            registre.sources().map { it.cheminRelatif },
        )
    }

    @Test
    fun `liberer uri inconnue - sans effet ni exception`() {
        val registre = RegistreSourcesApercu()
        registre.liberer("uri://jamais-enregistree")
        assertFalse(registre.sources().isNotEmpty())
    }
}
