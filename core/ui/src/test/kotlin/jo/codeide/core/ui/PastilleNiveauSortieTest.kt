package jo.codeide.core.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textview.MaterialTextView
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.test.R as RTest

/**
 * Tests d'inflation et de comportement de PastilleNiveauSortie (ADR 0162,
 * lot L2) : la LETTRE porte le niveau (V/D/I/W/E/F/·), les teintes
 * pleine/conteneur et le NOM localisé changent ENSEMBLE — jamais la
 * couleur seule (spec SORTIES § 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PastilleNiveauSortieTest {
    private lateinit var contexte: Context
    private lateinit var pastille: PastilleNiveauSortie

    @Before
    fun gonflerLaPastille() {
        contexte = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_CodeIDE)
        pastille = PastilleNiveauSortie(contexte)
    }

    private val lettre: MaterialTextView
        get() = pastille.findViewById(R.id.pastille_sortie_lettre)

    @Test
    fun `le niveau par défaut est indéterminé - lettre point médian et nom porté`() {
        assertEquals(NiveauPastilleSortie.INDETERMINE, pastille.niveau)
        assertEquals("·", lettre.text.toString())
        assertEquals(contexte.getString(R.string.sorties_niveau_indefini), pastille.contentDescription)
    }

    @Test
    fun `le XML lit le niveau et porte le nom localisé`() {
        val racine = LayoutInflater.from(contexte).inflate(RTest.layout.view_composants_test, null)
        val lue = racine.findViewById<PastilleNiveauSortie>(RTest.id.pastille_sortie_test)
        assertEquals(NiveauPastilleSortie.AVERTISSEMENT, lue.niveau)
        assertEquals("W", lue.findViewById<MaterialTextView>(R.id.pastille_sortie_lettre).text.toString())
        assertEquals(contexte.getString(R.string.sorties_niveau_avertissement), lue.contentDescription)
    }

    @Test
    fun `chaque niveau porte sa lettre sans la couleur`() {
        val attendus =
            mapOf(
                NiveauPastilleSortie.VERBEUX to "V",
                NiveauPastilleSortie.DEBOGAGE to "D",
                NiveauPastilleSortie.INFO to "I",
                NiveauPastilleSortie.AVERTISSEMENT to "W",
                NiveauPastilleSortie.ERREUR to "E",
                NiveauPastilleSortie.ASSERT to "F",
            )
        attendus.forEach { (niveau, lettreAttendue) ->
            pastille.setNiveau(niveau)
            assertEquals(lettreAttendue, lettre.text.toString())
            assertEquals(contexte.getString(nomDe(niveau)), pastille.contentDescription)
            // Le fond conteneur suit (teinté, jamais transparent).
            org.junit.Assert.assertNotNull(pastille.backgroundTintList)
        }
    }

    private fun nomDe(niveau: NiveauPastilleSortie): Int = when (niveau) {
        NiveauPastilleSortie.VERBEUX -> R.string.sorties_niveau_verbeux
        NiveauPastilleSortie.DEBOGAGE -> R.string.sorties_niveau_debogage
        NiveauPastilleSortie.INFO -> R.string.sorties_niveau_info
        NiveauPastilleSortie.AVERTISSEMENT -> R.string.sorties_niveau_avertissement
        NiveauPastilleSortie.ERREUR -> R.string.sorties_niveau_erreur
        NiveauPastilleSortie.ASSERT -> R.string.sorties_niveau_assert
        NiveauPastilleSortie.INDETERMINE -> R.string.sorties_niveau_indefini
    }
}
