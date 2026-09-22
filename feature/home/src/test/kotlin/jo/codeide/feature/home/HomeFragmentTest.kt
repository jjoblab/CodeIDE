package jo.codeide.feature.home

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.feature.home.databinding.FragmentHomeBinding
import jo.codeide.feature.home.databinding.ItemProjetBinding
import jo.codeide.feature.home.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests du layout et de l'écart DiffUtil de l'accueil (étape 7) :
 * libellés localisés, description d'accessibilité du bouton icône et
 * rafraîchissement d'une ligne quand seul son état d'accès change.
 *
 * Le fragment lui-même (Hilt, dialogues, sélecteurs SAF) est couvert par
 * le test d'intégration de `app`, qui lance l'activité et navigue
 * réellement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class HomeFragmentTest {
    private fun contexte(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_CodeIDE)

    @Test
    fun `le bouton parametres porte sa description d'accessibilite`() {
        val liaison = FragmentHomeBinding.inflate(LayoutInflater.from(contexte()))

        // Bouton icône : le rôle vit dans la description, pas dans un texte.
        assertEquals(
            contexte().getString(R.string.home_open_settings),
            liaison.buttonSettings.contentDescription,
        )
    }

    @Test
    fun `la recherche et le tri portent leurs libelles localises`() {
        val liaison = FragmentHomeBinding.inflate(LayoutInflater.from(contexte()))

        assertEquals(
            contexte().getString(R.string.accueil_recherche_indication),
            liaison.champRecherche.hint,
        )
        assertEquals(contexte().getString(R.string.accueil_tri_recents), liaison.boutonTriRecents.text.toString())
        assertEquals(contexte().getString(R.string.accueil_tri_nom), liaison.boutonTriNom.text.toString())
    }

    @Test
    fun `les actions flottantes portent leurs libelles`() {
        val liaison = FragmentHomeBinding.inflate(LayoutInflater.from(contexte()))

        assertEquals(contexte().getString(R.string.accueil_nouveau_projet), liaison.fabNouveauProjet.text.toString())
        assertEquals(
            contexte().getString(R.string.accueil_ouvrir_dossier),
            liaison.fabOuvrirDossier.contentDescription,
        )
    }

    @Test
    fun `l'etat vide porte son illustration et son bouton d'action`() {
        val liaison = FragmentHomeBinding.inflate(LayoutInflater.from(contexte()))

        assertEquals(
            contexte().getString(R.string.accueil_vide_action),
            liaison.boutonEtatVideAction.text.toString(),
        )
    }

    @Test
    fun `la ligne de projet masque description et badge quand ils sont vides`() {
        val liaison = ItemProjetBinding.inflate(LayoutInflater.from(contexte()))

        val adapteur = ProjetsAccueilAdapter(EcouteurSansEffet)
        adapteur.submitList(listOf(ProjetAffiche(projet("MonProjet", ""), null)))
        shadowOf(Looper.getMainLooper()).idle()
        adapteur.onBindViewHolder(ProjetsAccueilAdapter.VueProjet(liaison), 0)

        assertEquals("MonProjet", liaison.nomProjet.text.toString())
        assertFalse(liaison.rangeeEtat.isVisible)
    }

    /** Projet de fixture pour le rendu d'une ligne. */
    private fun projet(
        nom: String,
        description: String,
    ): Project =
        Project(
            id = ProjectId("fixture"),
            name = nom,
            description = description,
            location = StorageLocation("g", "d", nom),
            templateId = TemplateId.IMPORTED,
            createdAtMillis = 0L,
            lastOpenedAtMillis = null,
            isPinned = false,
        )

    /** Écouteur sans effet : le rendu seul est testé ici. */
    private object EcouteurSansEffet : ProjetsAccueilAdapter.EcouteurProjets {
        override fun surClicProjet(
            projet: Project,
            acces: ProjectAccessState?,
        ) = Unit

        override fun surMenuProjet(
            projet: Project,
            acces: ProjectAccessState?,
        ) = Unit
    }
}
