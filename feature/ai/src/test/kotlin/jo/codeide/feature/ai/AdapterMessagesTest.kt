package jo.codeide.feature.ai

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test du RENDU après rebind partiel (rapport IA, lot 1, B1) : le
 * payload du comparateur (`Set<ClePayload>`) doit arriver DÉPLIÉ au
 * titulaire — le texte qui coule pendant le flux s'affiche SANS
 * recréer la ligne. L'ancienne lecture `payloads.mapNotNull { it as?
 * ClePayload }` rejetait le `Set` ENTIER : liste dépliée vide,
 * `lierPartiellement` sans effet, réponse INVISIBLE pendant le flux
 * (le curseur clignotait, seul un changement d'onglet — recréation
 * de vue — déclenchait le rendu complet).
 *
 * Les tests pilotent `onBindViewHolder(payloads)` : le rebind partiel
 * que le RecyclerView jouerait après le commit du diff est rejoué À
 * L'IDENTIQUE (même position, même payload, `getItem` au commit) —
 * c'est le CONTRAT B1 qui est verrouillé, sans dépendre du
 * séquencement interne du layout du RecyclerView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AdapterMessagesTest {
    private lateinit var liste: RecyclerView
    private lateinit var adaptateur: AdapterMessages

    @Before
    fun preparer() {
        val contexte: Context = ApplicationProvider.getApplicationContext()
        val theme = ContextThemeWrapper(contexte, jo.codeide.core.ui.R.style.Theme_CodeIDE)
        val racine = FrameLayout(theme)
        liste =
            RecyclerView(theme).apply {
                // Le LayoutManager est requis AVANT tout gonflage d'item
                // (generateLayoutParams y lit ses LayoutParams).
                layoutManager = LinearLayoutManager(theme)
                racine.addView(this, FrameLayout.LayoutParams(600, 1200))
            }
        adaptateur =
            AdapterMessages(
                onCopierMessage = {},
                onCopierBloc = {},
                onInsererBloc = {},
                onAppliquerBloc = {},
                onRegenererMessage = {},
                onExporterMessage = {},
                onActionErreur = { _, _ -> },
            )
    }

    /** Fabrique un message assistant en génération. */
    private fun messageAssistant(
        texte: String,
        generationEnCours: Boolean = true,
        raisonnement: String = "",
    ): MessageChatUi = MessageChatUi(
        identifiant = "ia-7",
        utilisateur = false,
        texte = texte,
        generationEnCours = generationEnCours,
        raisonnement = raisonnement,
    )

    /** Texte visible des blocs Markdown du titulaire assistant. */
    private fun texteRendu(titulaire: RecyclerView.ViewHolder): String {
        val conteneur =
            titulaire.itemView.findViewById<ViewGroup>(R.id.conteneur_blocs)
                ?: return ""
        return (0 until conteneur.childCount).joinToString(" ") { rang ->
            (conteneur.getChildAt(rang) as? TextView)?.text?.toString() ?: ""
        }.trim()
    }

    /**
     * Soumet la nouvelle liste et attend son COMMIT : le calcul du
     * diff vit sur un exécuteur d'E/S réel, la notification revient
     * sur le fil principal — le looper est joué en boucle pendant
     * l'attente (borne 5 s : un dépassement échoue le test, jamais de
     * blocage muet).
     */
    private fun soumettreEtCommmettre(nouvelle: List<MessageChatUi>) {
        val commit = CountDownLatch(1)
        adaptateur.submitList(nouvelle) { commit.countDown() }
        val debut = System.nanoTime()
        while (!commit.await(10, TimeUnit.MILLISECONDS)) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (System.nanoTime() - debut > TimeUnit.SECONDS.toNanos(5)) {
                fail("le diff de la liste n'a pas commis en 5 s")
            }
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `rebind partiel TEXTE - le texte qui coule est rendu, la ligne survit`() {
        // 1. Bind COMPLET du premier fragment reçu.
        val premier = messageAssistant("Bonjour")
        soumettreEtCommmettre(listOf(premier))
        val titulaire = adaptateur.onCreateViewHolder(liste, TYPE_ASSISTANT)
        adaptateur.onBindViewHolder(titulaire, 0)
        assertEquals("Bonjour", texteRendu(titulaire))

        // 2. Le fragment suivant enrichit le texte : le comparateur
        // paie TEXTE (un Set), l'adaptateur doit DÉPLIER et RENDRE —
        // la régression B1 laissait la vue sur l'ANCIEN texte (le Set
        // était rejeté, la liste dépliée vide).
        val fragmentSuivant = messageAssistant("Bonjour le monde")
        soumettreEtCommmettre(listOf(fragmentSuivant))
        val payload = ComparateurMessages.getChangePayload(premier, fragmentSuivant)
        assertTrue("le payload doit être un Set (contrat B1)", payload is Set<*>)
        adaptateur.onBindViewHolder(titulaire, 0, mutableListOf(payload!!))

        assertEquals("Bonjour le monde", texteRendu(titulaire))
    }

    @Test
    fun `rebind partiel RAISONNEMENT - la section apparaît sans regonfler les blocs`() {
        val premier = messageAssistant("Bonjour")
        soumettreEtCommmettre(listOf(premier))
        val titulaire = adaptateur.onCreateViewHolder(liste, TYPE_ASSISTANT)
        adaptateur.onBindViewHolder(titulaire, 0)
        val texteAvant = texteRendu(titulaire)

        val fragmentSuivant = premier.copy(raisonnement = "Je réfléchis…")
        soumettreEtCommmettre(listOf(fragmentSuivant))
        val payload = ComparateurMessages.getChangePayload(premier, fragmentSuivant)!!
        adaptateur.onBindViewHolder(titulaire, 0, mutableListOf(payload))

        // Le raisonnement est rendu (ligne visible)…
        val ligneRaisonnement =
            titulaire.itemView.findViewById<View>(R.id.ligne_raisonnement)
        assertTrue(
            "la section raisonnement doit être visible",
            ligneRaisonnement.visibility == View.VISIBLE,
        )
        // …et les blocs de texte ne sont PAS regonflés (même texte).
        assertEquals(texteAvant, texteRendu(titulaire))
    }

    @Test
    fun `rebind partiel FIN - le curseur s'éteint à la fin de la génération`() {
        val enCours = messageAssistant("Bonjour", generationEnCours = true)
        soumettreEtCommmettre(listOf(enCours))
        val titulaire = adaptateur.onCreateViewHolder(liste, TYPE_ASSISTANT)
        adaptateur.onBindViewHolder(titulaire, 0)

        val termine = enCours.copy(generationEnCours = false)
        soumettreEtCommmettre(listOf(termine))
        val payload = ComparateurMessages.getChangePayload(enCours, termine)!!
        adaptateur.onBindViewHolder(titulaire, 0, mutableListOf(payload))

        val curseur = titulaire.itemView.findViewById<View>(R.id.vue_curseur_flux)
        assertTrue(
            "le curseur de flux doit être éteint à la fin",
            curseur.visibility == View.GONE,
        )
    }

    @Test
    fun `clé nue en payload - compatibilité avec les anciens émetteurs`() {
        // Défense en profondeur : un émetteur qui poserait une clé NUE
        // (l'ancienne écriture du comparateur) est AUSSI reconnu par
        // le dépliage (`listOf(it)`) — jamais de rendu muet.
        val message = messageAssistant("Bonjour le monde")
        soumettreEtCommmettre(listOf(message))
        val titulaire = adaptateur.onCreateViewHolder(liste, TYPE_ASSISTANT)
        adaptateur.onBindViewHolder(titulaire, 0)
        assertEquals("Bonjour le monde", texteRendu(titulaire))

        // Clé nue : le dépliage la reconnaît et rend TEXTE (idempotent).
        adaptateur.onBindViewHolder(titulaire, 0, mutableListOf(ClePayload.TEXTE))
        assertEquals("Bonjour le monde", texteRendu(titulaire))
    }

    @Test
    fun `C3 - pendant la PENSEE le raisonnement est VISIBLE sans clic - puis se replie quand la reponse demarre`() {
        // Phase de pensée : génération en cours, texte vide — le corps
        // du raisonnement coule VISIBLE (l'ancien comportement le
        // masquait derrière un clic à deviner).
        val pensee = messageAssistant("", generationEnCours = true, raisonnement = "Je réfléchis au problème…")
        soumettreEtCommmettre(listOf(pensee))
        val titulaire = adaptateur.onCreateViewHolder(liste, TYPE_ASSISTANT)
        adaptateur.onBindViewHolder(titulaire, 0)

        val corpsPensee = titulaire.itemView.findViewById<View>(R.id.texte_raisonnement)
        assertTrue(
            "le corps du raisonnement doit être VISIBLE pendant la pensée",
            corpsPensee.visibility == View.VISIBLE,
        )

        // La réponse démarre : le raisonnement se REPLIE de lui-même.
        val reponse = pensee.copy(texte = "Voici la réponse.", generationEnCours = false)
        soumettreEtCommmettre(listOf(reponse))
        val payload = ComparateurMessages.getChangePayload(pensee, reponse)!!
        adaptateur.onBindViewHolder(titulaire, 0, mutableListOf(payload))

        assertTrue(
            "le corps du raisonnement doit être REPLIE quand la réponse démarre",
            corpsPensee.visibility != View.VISIBLE,
        )
        // …et l'utilisateur peut le ROUVrir (la ligne reste visible).
        val ligneRaisonnement = titulaire.itemView.findViewById<View>(R.id.ligne_raisonnement)
        assertTrue(ligneRaisonnement.visibility == View.VISIBLE)
    }

    private companion object {
        const val TYPE_ASSISTANT = 1
    }
}
