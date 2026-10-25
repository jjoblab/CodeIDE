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
        // IA-8b : complétion fantôme présente et DÉSACTIVÉE par défaut (ADR 0129).
        val completion = racine.findViewById<View>(R.id.interrupteur_completion_fantome)
        assertNotNull(completion)
        assertTrue((completion as com.google.android.material.materialswitch.MaterialSwitch).isChecked.not())
    }

    /**
     * Régression v0.187.0 (rapport IA §5 Lot 2, B2b — boucle du
     * consentement) : les interrupteurs qui REFLETTENT un état persisté
     * (maître, complétion fantôme, confidentialité ×3) ne sont JAMAIS
     * sauvés dans l'état de la vue — Android les restaurant à
     * `onViewStateRestored`, APRÈS le rendu, l'ancien écouteur d'état se
     * réarmait tout seul et rouvrait le consentement en boucle au retour
     * de « Pas maintenant ».
     */
    @Test
    fun `les interrupteurs refletant un etat persiste ne sont jamais sauves`() {
        val racine = gonfler(R.layout.fragment_reglages_ia)
        listOf(
            R.id.interrupteur_ia_active,
            R.id.interrupteur_completion_fantome,
            R.id.interrupteur_conf_fichier,
            R.id.interrupteur_conf_chemins,
            R.id.interrupteur_conf_diagnostics,
        ).forEach { identifiant ->
            val interrupteur = racine.findViewById<View>(identifiant)
            assertNotNull("l'interrupteur $identifiant doit exister", interrupteur)
            assertEquals(
                "B2b : saveEnabled=false — l'état vient du DataStore, jamais " +
                    "de la restauration de vue ($identifiant)",
                false,
                interrupteur?.isSaveEnabled,
            )
        }
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
        // Lot 5, point 1 : la carte des préréglages d'URL et son
        // conteneur existent (les lignes sont posées par le fragment).
        assertNotNull(racine.findViewById<View>(R.id.carte_prereglages))
        assertNotNull(racine.findViewById<View>(R.id.conteneur_prereglages))
    }

    @Test
    fun `la ligne de préréglage se gonflle avec nom et url`() {
        val racine = gonfler(R.layout.ligne_prereglage_ia)

        assertNotNull(racine.findViewById<View>(R.id.nom_prereglage))
        assertNotNull(racine.findViewById<View>(R.id.url_prereglage))
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
        assertNotNull(racine.findViewById<View>(R.id.zone_messages))
        assertNotNull(racine.findViewById<View>(R.id.composeur_ia))
        assertNotNull(racine.findViewById<View>(R.id.bouton_ajouter_cle))
        // Critère IA-3 : au repos, c'est « Envoyer » qui est visible ;
        // « Arrêter » n'apparaît que pendant la génération.
        assertEquals(View.VISIBLE, racine.findViewById<View>(R.id.bouton_envoyer).visibility)
        assertEquals(View.GONE, racine.findViewById<View>(R.id.bouton_arreter).visibility)

        // Régression v0.188.0 (rapport IA §5 Lot 3, B1b) : le calque
        // d'état vit DANS la zone des messages — AVANT il était un frère
        // FULL-SCREEN du compositeur, déclaré APRÈS lui : il le
        // recouvrait quand l'IA était active et la conversation vide.
        val zoneMessages = racine.findViewById<View>(R.id.zone_messages)
        val calque = racine.findViewById<View>(R.id.etat_transverse)
        assertEquals(
            "B1b : le calque est ENFANT de la zone des messages",
            (calque.parent as? android.view.ViewGroup)?.id,
            zoneMessages.id,
        )
        val composeur = racine.findViewById<View>(R.id.composeur_ia)
        assertEquals(
            "B1b : le compositeur est un ENFANT DIRECT de la racine — plus JAMAIS recouvert par le calque",
            (composeur.parent as? android.view.ViewGroup)?.id,
            racine.id,
        )
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
    fun `la puce de contexte et le sélecteur de fichiers se gonflent`() {
        val puce = gonfler(R.layout.ligne_puce_contexte)
        assertNotNull(puce.findViewById<View>(R.id.libelle_puce))
        assertNotNull(puce.findViewById<View>(R.id.croix_puce))

        val selecteur = gonfler(R.layout.vue_selecteur_fichiers_ia)
        assertNotNull(selecteur.findViewById<View>(R.id.recherche_fichiers))
        assertNotNull(selecteur.findViewById<View>(R.id.liste_fichiers))
    }

    @Test
    fun `la vue « bientôt » se gonfle avec son texte`() {
        val racine = gonfler(R.layout.vue_ia_bientot)

        assertNotNull(racine.findViewById<View>(R.id.texte_bientot))
    }

    @Test
    fun `l'écran agent se gonfle avec ses contrôles`() {
        val racine = gonfler(R.layout.fragment_ia_agent)

        assertNotNull(racine.findViewById<View>(R.id.champ_objectif))
        assertNotNull(racine.findViewById<View>(R.id.bouton_lancer))
        assertNotNull(racine.findViewById<View>(R.id.bouton_arreter))
        assertNotNull(racine.findViewById<View>(R.id.bouton_continuer))
        assertNotNull(racine.findViewById<View>(R.id.liste_etapes))
        assertNotNull(racine.findViewById<View>(R.id.carte_approbation))
        assertNotNull(racine.findViewById<View>(R.id.bouton_autoriser))
        assertNotNull(racine.findViewById<View>(R.id.bouton_refuser))
        assertNotNull(racine.findViewById<View>(R.id.carte_changements))
        assertNotNull(racine.findViewById<View>(R.id.bouton_examiner))
        assertNotNull(racine.findViewById<View>(R.id.bouton_tout_appliquer))
        assertNotNull(racine.findViewById<View>(R.id.bouton_rejeter))
        assertNotNull(racine.findViewById<View>(R.id.bandeau_annulation))
        assertNotNull(racine.findViewById<View>(R.id.bouton_annuler_execution))
        // États initiaux : approbation, changements et annulation MASQUÉS.
        assertEquals(View.GONE, racine.findViewById<View>(R.id.carte_approbation).visibility)
        assertEquals(View.GONE, racine.findViewById<View>(R.id.carte_changements).visibility)
        assertEquals(View.GONE, racine.findViewById<View>(R.id.bandeau_annulation).visibility)
    }

    @Test
    fun `la revue des changements se gonfle avec ses contrôles`() {
        val racine = gonfler(R.layout.fragment_revue_changements)

        assertNotNull(racine.findViewById<View>(R.id.liste_fichiers))
        assertNotNull(racine.findViewById<View>(R.id.bouton_appliquer_selection))
        assertNotNull(racine.findViewById<View>(R.id.bouton_retour))
        assertNotNull(racine.findViewById<View>(R.id.bandeau_annulation))
        assertNotNull(racine.findViewById<View>(R.id.texte_vide))
        // État initial : message vide masqué (le fragment le révèle si la revue est vide).
        assertEquals(View.GONE, racine.findViewById<View>(R.id.texte_vide).visibility)
    }

    @Test
    fun `la ligne de revue affiche le badge conflit masqué par défaut`() {
        val racine = gonfler(R.layout.ligne_fichier_revue)

        assertNotNull(racine.findViewById<View>(R.id.case_retenue))
        assertNotNull(racine.findViewById<View>(R.id.chemin_fichier))
        assertNotNull(racine.findViewById<View>(R.id.badge_conflit))
        assertEquals(View.GONE, racine.findViewById<View>(R.id.badge_conflit).visibility)
    }

    @Test
    fun `la ligne d'étape se gonfle avec son libellé`() {
        val racine = gonfler(R.layout.ligne_etape_agent)

        assertNotNull(racine.findViewById<View>(R.id.texte_etape))
    }
}
