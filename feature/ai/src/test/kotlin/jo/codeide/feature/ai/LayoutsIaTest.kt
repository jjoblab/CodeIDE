package jo.codeide.feature.ai

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests de layout des écrans IA (mission IA, lot IA-2) : les trois
 * fragments se gonflent sans crash, les identifiants clés existent,
 * les états initiaux sont corrects (clé masquée, bouton supprimer
 * masqué, progression masquée).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LayoutsIaTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()

    private fun gonfler(layout: Int): View {
        val theme = ContextThemeWrapper(contexte, jo.codeide.core.ui.R.style.Theme_CodeIDE)
        val parent = FrameLayout(theme)
        return LayoutInflater.from(theme).inflate(layout, parent, true)
    }

    @Test
    fun `les réglages IA se gonflent avec leurs contrôles`() {
        val racine = gonfler(R.layout.fragment_reglages_ia)

        assertNotNull(racine.findViewById<View>(R.id.interrupteur_ia_active))
        assertNotNull(racine.findViewById<View>(R.id.ligne_consentement))
        assertNotNull(racine.findViewById<View>(R.id.rangee_fournisseur_anthropic))
        assertNotNull(racine.findViewById<View>(R.id.rangee_fournisseur_openai))
        assertNotNull(racine.findViewById<View>(R.id.radio_fournisseur_anthropic))
        assertNotNull(racine.findViewById<View>(R.id.champ_modele_ia))
        assertNotNull(racine.findViewById<View>(R.id.bouton_actualiser_modeles))
        assertNotNull(racine.findViewById<View>(R.id.interrupteur_conf_fichier))
        assertNotNull(racine.findViewById<View>(R.id.interrupteur_conf_chemins))
        assertNotNull(racine.findViewById<View>(R.id.interrupteur_conf_diagnostics))
        assertNotNull(racine.findViewById<View>(R.id.champ_max_jetons))
        assertNotNull(racine.findViewById<View>(R.id.champ_delai))
        assertTrue(racine.findViewById<View>(R.id.interrupteur_ia_active).isEnabled)
    }

    @Test
    fun `le détail fournisseur se gonflle — champ de clé masqué et non restauré`() {
        val racine = gonfler(R.layout.fragment_fournisseur_ia)

        assertNotNull(racine.findViewById<View>(R.id.champ_url_fournisseur))
        val champCle = racine.findViewById<View>(R.id.champ_cle)
        assertNotNull(champCle)
        // ADR 0124 §1 / critère IA-2 : la saisie ne survit pas à une
        // recréation (saveEnabled=false) — rien dans les Bundle.
        assertEquals(false, (champCle as android.widget.EditText).isSaveEnabled)
        // Bouton « supprimer » masqué tant qu'aucune clé (gestion du fragment).
        assertEquals(View.GONE, racine.findViewById<View>(R.id.bouton_supprimer_cle).visibility)
    }

    @Test
    fun `le consentement se gonflle avec ses deux actions`() {
        val racine = gonfler(R.layout.fragment_consentement_ia)

        assertNotNull(racine.findViewById<View>(R.id.bouton_activer))
        assertNotNull(racine.findViewById<View>(R.id.bouton_pas_maintenant))
        assertNotNull(racine.findViewById<View>(R.id.toolbar_consentement_ia))
    }

    // ------------------------------------------------------------------
    // Tiroir IA (mission IA, lot IA-3) : onglets, chat, lignes de
    // messages, bloc de code, état « bientôt ».
    // ------------------------------------------------------------------

    @Test
    fun `le tiroir IA se gonfle avec ses onglets et son conteneur`() {
        val racine = gonfler(R.layout.fragment_ia_tiroir)

        assertNotNull(racine.findViewById<View>(R.id.onglets_ia))
        assertNotNull(racine.findViewById<View>(R.id.conteneur_ia))
    }

    @Test
    fun `le chat se gonfle avec compositeur et états — Arrêter masqué au repos`() {
        val racine = gonfler(R.layout.fragment_ia_chat)

        assertNotNull(racine.findViewById<View>(R.id.liste_messages))
        assertNotNull(racine.findViewById<View>(R.id.saisie_ia))
        assertNotNull(racine.findViewById<View>(R.id.bouton_envoyer))
        assertNotNull(racine.findViewById<View>(R.id.etat_transverse))
        assertNotNull(racine.findViewById<View>(R.id.etat_titre))
        assertNotNull(racine.findViewById<View>(R.id.etat_texte))
        assertNotNull(racine.findViewById<View>(R.id.bouton_consentement))
        assertNotNull(racine.findViewById<View>(R.id.zone_chat))
        // Critère IA-3 : au repos, c'est « Envoyer » qui est visible ;
        // « Arrêter » n'apparaît que pendant la génération.
        assertEquals(View.VISIBLE, racine.findViewById<View>(R.id.bouton_envoyer).visibility)
        assertEquals(View.GONE, racine.findViewById<View>(R.id.bouton_arreter).visibility)
    }

    @Test
    fun `les lignes de messages et le bloc de code se gonflent`() {
        val utilisateur = gonfler(R.layout.ligne_message_utilisateur)
        assertNotNull(utilisateur.findViewById<View>(R.id.texte_message))

        val assistant = gonfler(R.layout.ligne_message_assistant)
        assertNotNull(assistant.findViewById<View>(R.id.conteneur_blocs))
        assertNotNull(assistant.findViewById<View>(R.id.erreur_message))
        assertNotNull(assistant.findViewById<View>(R.id.bouton_copier_message))

        val bloc = gonfler(R.layout.ligne_bloc_code)
        assertNotNull(bloc.findViewById<View>(R.id.langue_bloc))
        assertNotNull(bloc.findViewById<View>(R.id.code_bloc))
        assertNotNull(bloc.findViewById<View>(R.id.bouton_copier_bloc))
        assertNotNull(bloc.findViewById<View>(R.id.bouton_inserer_bloc))
    }

    @Test
    fun `la vue « bientôt » se gonfle avec son texte`() {
        val racine = gonfler(R.layout.vue_ia_bientot)

        assertNotNull(racine.findViewById<View>(R.id.texte_bientot))
    }
}
