package jo.codeide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de l'adaptateur de la console actuelle vers le Centre de sorties
 * (ADR 0160 § Décision 2, lot L1) — projection du canal sur la source,
 * du style sur le niveau normalisé, résolution du libellé par le
 * résolveur de l'appelant (le modèle ne porte jamais de libellé résolu).
 */
class AdaptateurConsoleSortiesTest {
    /** Un résolveur de test : rend chaque sous-type de TexteTooling en
     *  une chaîne distinguable, sans Context Android (pur JVM). */
    private val resoudre: (TexteTooling) -> String = { texte ->
        when (texte) {
            is TexteTooling.Ressource -> "R(${texte.id})"
            is TexteTooling.Brut -> texte.texte
            is TexteTooling.Compose -> texte.parties.joinToString("") { resoudre(it) }
            is TexteTooling.Taille -> "${texte.octets}o"
            is TexteTooling.Pluriel -> "P(${texte.id}, ${texte.quantite})"
        }
    }

    private fun ligne(
        canal: CanalTooling = CanalTooling.BUILD,
        libelle: TexteTooling = TexteTooling.Brut("ligne de flux"),
        style: StyleLigne = StyleLigne.SORTIE,
        horodatageMs: Long = 1_000L,
    ): EvenementConsoleTexte.Ligne = EvenementConsoleTexte.Ligne(
        canal = canal,
        libelle = libelle,
        style = style,
        horodatageMs = horodatageMs,
    )

    @Test
    fun `les canaux se projettent sur leurs familles de source`() {
        assertEquals(
            jo.codeide.core.domain.SourceSortie.SYNC,
            ligne(canal = CanalTooling.SYNC).versLigneSortie(resoudre).source,
        )
        assertEquals(
            jo.codeide.core.domain.SourceSortie.BUILD,
            ligne(canal = CanalTooling.BUILD).versLigneSortie(resoudre).source,
        )
        assertEquals(
            jo.codeide.core.domain.SourceSortie.TACHES,
            ligne(canal = CanalTooling.TACHES).versLigneSortie(resoudre).source,
        )
    }

    @Test
    fun `les styles se projettent sur le niveau normalise`() {
        val cas = listOf(
            StyleLigne.ERREUR to jo.codeide.core.domain.NiveauSortie.ERREUR,
            StyleLigne.APAISEE to jo.codeide.core.domain.NiveauSortie.AVERTISSEMENT,
            StyleLigne.ETAPE to jo.codeide.core.domain.NiveauSortie.INFO,
            StyleLigne.SYNTHESE to jo.codeide.core.domain.NiveauSortie.INFO,
            StyleLigne.SORTIE to jo.codeide.core.domain.NiveauSortie.INDETERMINE,
            StyleLigne.TELECHARGEMENT to jo.codeide.core.domain.NiveauSortie.INDETERMINE,
        )
        for ((style, niveauAttendu) in cas) {
            assertEquals(
                "style $style",
                niveauAttendu,
                ligne(style = style).versLigneSortie(resoudre).niveau,
            )
        }
    }

    @Test
    fun `le texte brut se resolve tel quel dans le message`() {
        val sortie = ligne(
            libelle = TexteTooling.Brut("> Task :app:compileDebugKotlin"),
        ).versLigneSortie(resoudre)

        assertEquals("> Task :app:compileDebugKotlin", sortie.message)
    }

    @Test
    fun `la ressource passe par le resoudre sans etre resolue dans le modele`() {
        val sortie = ligne(
            libelle = TexteTooling.Ressource(R.string.editor_console_synthese_annule),
        ).versLigneSortie(resoudre)

        // Le modèle ne porte que le message résolu PAR L'APPELANT.
        assertEquals("R(${R.string.editor_console_synthese_annule})", sortie.message)
    }

    @Test
    fun `le compose concatene ses parties dans l ordre`() {
        val sortie = ligne(
            libelle = TexteTooling.Compose(
                listOf(
                    TexteTooling.Brut("Cache "),
                    TexteTooling.Pluriel(R.plurals.editor_tooling_detail_elements, 3),
                ),
            ),
        ).versLigneSortie(resoudre)

        assertEquals("Cache P(${R.plurals.editor_tooling_detail_elements}, 3)", sortie.message)
    }

    @Test
    fun `l horodatage se porte et les champs absents restent absents`() {
        val sortie = ligne(horodatageMs = 1_790_000_000_000L).versLigneSortie(resoudre)

        assertEquals(1_790_000_000_000L, sortie.horodatageMs)
        assertNull(sortie.etiquette)
        assertNull(sortie.tache)
        assertNull(sortie.idExecution)
        assertNull(sortie.identiteSession)
    }
}
