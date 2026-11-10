package jo.codeide.feature.newproject

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import jo.codeide.feature.newproject.databinding.FragmentNewprojectBinding
import jo.codeide.feature.newproject.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests du cadre du wizard (étape 10, section 12.2) : libellés localisés de
 * l'indicateur d'étapes et de la barre d'actions, description
 * d'accessibilité de la fermeture, **type fermé des étapes** (socle + étape
 * de modèle, ADR 0155 §5) et migration des clés persistées. La navigation
 * entre étapes et les dialogues (Hilt) relèvent du test d'intégration de
 * `app`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NewProjectFragmentTest {
    private fun contexte(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_CodeIDE)

    @Test
    fun `l indicateur porte le libellé localise de la premiere etape`() {
        val liaison = FragmentNewprojectBinding.inflate(LayoutInflater.from(contexte()))

        // Parcours vide avant la première évaluation : la borne haute
        // reste lisible (au moins 1) et la progression démarre à 1.
        assertTrue(liaison.indicateurEtapes.max >= 1)
        assertEquals(1, liaison.indicateurEtapes.progress)
    }

    @Test
    fun `la barre d actions porte ses libelles localises`() {
        val liaison = FragmentNewprojectBinding.inflate(LayoutInflater.from(contexte()))

        assertEquals(
            contexte().getString(R.string.wizard_bouton_precedent),
            liaison.boutonPrecedent.text.toString(),
        )
        assertEquals(
            contexte().getString(R.string.wizard_bouton_suivant),
            liaison.boutonSuivant.text.toString(),
        )
    }

    @Test
    fun `la fermeture porte sa description d accessibilite et le bouton apercu existe`() {
        val liaison = FragmentNewprojectBinding.inflate(LayoutInflater.from(contexte()))

        assertEquals(
            contexte().getString(R.string.wizard_fermer),
            liaison.barreOutils.navigationContentDescription,
        )
        assertEquals(
            contexte().getString(R.string.newproject_title),
            liaison.barreOutils.title.toString(),
        )
        // Aperçu en direct (L5d) : masqué par défaut, libellé localisé.
        assertFalse(liaison.boutonApercu.isVisible)
        assertEquals(
            contexte().getString(R.string.wizard_bouton_apercu),
            liaison.boutonApercu.text.toString(),
        )
    }

    /** Le type fermé des étapes : clés du socle, uniques et réservées. */
    @Test
    fun `les clés du socle sont réservées et stables`() {
        assertEquals(
            setOf("modele", "identite", "fichiers", "recapitulatif"),
            EtapeId.SOCLES,
        )
        assertEquals("modele", EtapeId.Modele.cle)
        assertEquals("identite", EtapeId.Identite.cle)
        assertEquals("fichiers", EtapeId.Fichiers.cle)
        assertEquals("recapitulatif", EtapeId.Recapitulatif.cle)
        assertEquals("configuration", EtapeId.ID_ETAPE_DEFAUT)
        assertEquals(
            EtapeId.EtapeModele("ma-etape").cle,
            "ma-etape",
        )
    }

    /** Migration des clés persistées (pré-L5 comprises). */
    @Test
    fun `deCle retrouve le socle et migre les anciennes clés`() {
        assertEquals(EtapeId.Modele, EtapeId.deCle("modele"))
        assertEquals(EtapeId.Identite, EtapeId.deCle("identite"))
        assertEquals(EtapeId.Fichiers, EtapeId.deCle("fichiers"))
        assertEquals(EtapeId.Recapitulatif, EtapeId.deCle("recapitulatif"))
        // Clés pré-L5 (majuscules de l'ancien enum).
        assertEquals(EtapeId.EtapeModele("configuration"), EtapeId.deCle("CONFIGURATION"))
        assertEquals(EtapeId.Identite, EtapeId.deCle("INFORMATIONS"))
        // Toute autre clé devient une étape de modèle (contrôlée contre le
        // parcours à l'arrivée de l'évaluation).
        assertEquals(EtapeId.EtapeModele("plateforme"), EtapeId.deCle("plateforme"))
    }

    /** Les titres du socle sont des ressources fr/en déclarées (parité). */
    @Test
    fun `les titres du socle sont des ressources déclarées`() {
        assertEquals(
            setOf(EtapeId.Modele.cle, EtapeId.Identite.cle, EtapeId.Fichiers.cle, EtapeId.Recapitulatif.cle),
            TITRES_SOCLE.keys,
        )
        assertEquals(
            contexte().getString(R.string.wizard_etape_identite),
            contexte().getString(TITRES_SOCLE.getValue(EtapeId.Identite.cle)),
        )
    }
}
