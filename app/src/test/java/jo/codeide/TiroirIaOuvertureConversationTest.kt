package jo.codeide

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import jo.codeide.feature.ai.IaTiroirFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import jo.codeide.feature.ai.R as RIa

/**
 * Test d'intégration du TIROIR IA (rapport IA, chantier IA-CODEIDE,
 * lot 1, B2) : l'appui sur une discussion de l'historique (le chemin
 * de production est [IaTiroirFragment.ouvrirConversationDansChat])
 * doit (1) SÉLECTIONNER l'onglet Chat du TabLayout — l'utilisateur
 * voit OÙ la discussion s'ouvre — et (2) POSER le FragmentResult que
 * le chat consommera à son passage à STARTED (l'attachement est
 * asynchrone : le résultat ATTEND dans le FragmentManager, plus
 * jamais d'appel direct sur un fragment potentiellement sans vue).
 *
 * L'activité hôte est une `FragmentActivity` nue : le tiroir vit SANS
 * contrôleur (le cast `as? ControleurIaTiroir` échoue silencieusement,
 * aucune commande hôte n'est sollicitée) — c'est le MÉCANISME du
 * tiroir qui est éprouvé, pas l'éditeur.
 *
 * La réception du résultat par le CHAT réel (chargement des messages
 * persistés) est verrouillée par `ChatViewModelTest` côté `:feature:ai`
 * (l'écouteur du fragment est le standard `setFragmentResultListener`
 * à trois lignes) ; la CONSOMMATION de bout en bout d'une conversation
 * réellement persistée reste un scénario d'appareil (docs/TESTS_MANUELS.md).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = dagger.hilt.android.testing.HiltTestApplication::class)
class TiroirIaOuvertureConversationTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    @Before
    fun preparer() {
        regleHilt.inject()
    }

    /**
     * Monte le tiroir dans une activité nue et retourne la paire
     * (activité, tiroir) — le conteneur est un `FrameLayout` au
     * premier plan.
     */
    private fun monterTiroir(): Pair<FragmentActivity, IaTiroirFragment> {
        val controleur = Robolectric.buildActivity(ActiviteHoteTiroirIa::class.java)
        controleur.setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activite = controleur.get()
        val conteneur = FrameLayout(activite)
        conteneur.id = View.generateViewId()
        activite.setContentView(conteneur)

        val tiroir = IaTiroirFragment()
        activite.supportFragmentManager
            .beginTransaction()
            .add(conteneur.id, tiroir)
            .commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        return activite to tiroir
    }

    @Test
    fun `l'appui sur une discussion sélectionne l'onglet Chat et pose le résultat`() {
        val (activite, tiroir) = monterTiroir()
        try {
            val onglets =
                tiroir.view!!.findViewById<com.google.android.material.tabs.TabLayout>(RIa.id.onglets_ia)
            assertNotNull("le TabLayout du tiroir doit exister", onglets)
            // Au démarrage le tiroir est sur Chat (position 0).
            assertEquals(0, onglets.selectedTabPosition)

            // L'utilisateur parcourt l'historique : l'onglet Historique
            // devient sélectionné (position 2) — c'est DEPUIS là qu'un
            // appui sur une discussion arrive.
            onglets.getTabAt(2)!!.select()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, onglets.selectedTabPosition)

            // L'écouteur de test est posé AVANT l'appui : le résultat
            // sera livré immédiatement à l'écouteur ACTIF (celui du
            // chat le remplacera à son attachement asynchrone — la
            // livraison nous précède, aucun chevauchement).
            var recupere: String? = null
            tiroir.childFragmentManager.setFragmentResultListener(
                "tiroir_ia_ouvrir_conversation",
                tiroir.viewLifecycleOwner,
            ) { _, extras -> recupere = extras.getString("conversation_id") }

            // L'appui sur une discussion (chemin de production exact
            // de HistoriqueFragment.ouvrir) : l'onglet Chat redevient
            // le tab SÉLECTIONNÉ — plus d'onglet Historique resté
            // actif pendant que le chat s'affiche.
            tiroir.ouvrirConversationDansChat("conv-integration")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                "l'onglet Chat doit être sélectionné après l'appui sur une discussion",
                0,
                onglets.selectedTabPosition,
            )

            // Le FragmentResult est POSÉ et porte l'identifiant : un
            // écouteur actif (le chat à son STARTED — ici l'écouteur
            // de test, miroir exact de l'enregistrement du chat) le
            // reçoit.
            assertNotNull("le FragmentResult doit être livré à l'écouteur", recupere)
            assertEquals("conv-integration", recupere)
        } finally {
            activite.finish()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
