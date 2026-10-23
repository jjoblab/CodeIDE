package jo.codeide.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import jo.codeeditor.document.EditorDocument
import jo.codeeditor.session.EditorSession
import jo.codeeditor.view.EditorView
import jo.codeide.preview.compose.EtatPreviewCompose
import jo.codeide.preview.compose.MetriquesRenduCompose
import jo.codeide.preview.compose.RenduPreviewCompose
import jo.lspkotlin.api.PreviewKotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hôte de la carte « goutte d'eau » Compose (mission « Previews » C1) —
 * verrouille le contrat cel-ui B27 vu de CodeIDE :
 * - [HostApercuCompose.canPreview] répond pour le KOTLIN SEUL — l'œil ne
 *   doit JAMAIS apparaître sur un layout XML (garde de régression :
 *   l'aperçu XML reste l'écran dédié :feature:preview) ;
 * - les ONGLETS de la carte sont les `@Preview` du dernier rendu VALIDE
 *   (contrat « jamais de carte vide » — les signaux discrets du moteur
 *   ne vident pas la carte), ellipsés pour l'en-tête ;
 * - le BADGE porte le document et la preview rendue ;
 * - le BITMAP se dessine FIT centré dans l'écran du mini-téléphone ;
 * - les messages HONNÊTES distinguent la recherche (aucune passe), la
 *   carte vide (une passe a trouvé rien), l'échec du document et
 *   l'échec LOCAL d'un onglet ;
 * - le moteur suit l'ONGLET ACTIF (brancher), reçoit les frappes
 *   (soumettre) et les ré-ouvertures (reactiverIsolation) ;
 * - le contrat cel-ui des onglets (« mesurés à l'ouverture, stables
 *   tant que la carte vit ») : une liste qui change la carte vivante
 *   la REFERME/RÉ-OUVRE au même caret.
 *
 * Le moteur est DOUBLÉ par un faux port (un flux d'état piloté à la
 * main) — le moteur réel a SES tests (:preview:compose) ; le pixel du
 * dessin passe par le mode graphique NATIF (vrais pixels, miroir
 * RenduComposeTest).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class HostApercuComposeTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()

    /** Le moteur doublé : compteurs + flux d'état piloté à la main. */
    private class FauxMoteur : PortMoteurPreviewCompose {
        val etatInterne = MutableStateFlow<EtatPreviewCompose>(EtatPreviewCompose.Inactif)
        override val etat: StateFlow<EtatPreviewCompose> = etatInterne.asStateFlow()
        val branchements = mutableListOf<String>()
        var soumissions = 0
            private set
        var reactivations = 0
            private set

        override fun brancher(uri: String, fournisseur: () -> List<PreviewKotlin>) {
            branchements += uri
        }

        override fun soumettre() {
            soumissions++
        }

        override fun reactiverIsolation() {
            reactivations++
        }

        override fun close() = Unit
    }

    /** Un rendu de fixture (bitmap carré plein, ou échec local verbeux). */
    private fun rendu(
        nom: String,
        index: Int = 0,
        bitmap: Bitmap? = null,
        messageErreur: String? = null,
    ): RenduPreviewCompose = RenduPreviewCompose(
        nom = nom,
        index = index,
        bitmap = bitmap,
        metriques =
        MetriquesRenduCompose(
            dureeMs = 1,
            nombreNoeuds = 1,
            largeur = bitmap?.width ?: 0,
            hauteur = bitmap?.height ?: 0,
        ),
        erreurs = emptyList(),
        messageErreur = messageErreur,
    )

    /** Un bitmap CARRÉ plein de la couleur donnée (vrais pixels NATIVE). */
    private fun carre(cote: Int, couleur: Int): Bitmap =
        Bitmap.createBitmap(cote, cote, Bitmap.Config.ARGB_8888).also { it.eraseColor(couleur) }

    private fun etatAvecOnglet(uri: String, nom: String): EtatEditor = EtatEditor(
        onglets = listOf(EditorTabState(uri = uri, cheminRelatif = nom, nom = nom, langage = "kotlin")),
        indexOngletActif = 0,
    )

    private fun hote(
        moteur: FauxMoteur,
        etatEditeur: MutableStateFlow<EtatEditor> = MutableStateFlow(EtatEditor()),
        scope: CoroutineScope,
    ): HostApercuCompose = HostApercuCompose(
        contexte = contexte,
        etat = etatEditeur,
        sessionKotlin = MutableStateFlow(null),
        scope = scope,
        moteur = moteur,
    )

    @Test
    fun `canPreview - Kotlin SEULEMENT (garde de régression - l'XML garde son écran dédié)`() {
        val hote = hote(FauxMoteur(), scope = CoroutineScope(UnconfinedTestDispatcher()))
        assertTrue("onglet .kt", hote.canPreview("EcranPrincipal.kt"))
        assertTrue("onglet .kts", hote.canPreview("build.gradle.kts"))
        assertTrue("extension majuscule", hote.canPreview("Ecran.KT"))
        assertFalse("un layout XML ne doit JAMAIS montrer l'œil", hote.canPreview("activity_main.xml"))
        assertFalse("markdown", hote.canPreview("README.md"))
        assertFalse("sans nom", hote.canPreview(null))
    }

    @Test
    fun `onglets - les Previews du dernier rendu VALIDE, ellipsées - les signaux discrets ne vident PAS`() = runTest {
        val moteur = FauxMoteur()
        val hote = hote(moteur, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals("aucun rendu : carte mono-vue (aucun onglet)", emptyList<String>(), hote.previewDropTabs())

        moteur.etatInterne.value =
            EtatPreviewCompose.RenduValide(
                listOf(rendu("ApercuProfilUtilisateurEtendu"), rendu("B")),
            )
        assertEquals(
            "une pastille par @Preview, ordre du document, noms ellipsés",
            listOf("ApercuProfilU…", "B"),
            hote.previewDropTabs(),
        )

        // Le signal discret (frappe en cours) ne remplace JAMAIS les
        // rendus — la carte vit sur le dernier VALIDE.
        moteur.etatInterne.value = EtatPreviewCompose.EnEdition
        assertEquals("EnEdition : les onglets RESTENT", listOf("ApercuProfilU…", "B"), hote.previewDropTabs())
    }

    @Test
    fun `badge - le document et la Preview rendue`() = runTest {
        val moteur = FauxMoteur()
        val etatEditeur = MutableStateFlow(etatAvecOnglet("file:///app/Ecran.kt", "Ecran.kt"))
        val hote = hote(moteur, etatEditeur, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals("sans rendu : le document seul", "Ecran.kt", hote.previewDropBadge(0))

        moteur.etatInterne.value =
            EtatPreviewCompose.RenduValide(listOf(rendu("CarteProfil"), rendu("Bulle")))
        assertEquals("Ecran.kt · @Preview CarteProfil", hote.previewDropBadge(0))
        assertEquals("Ecran.kt · @Preview Bulle", hote.previewDropBadge(1))
    }

    @Test
    fun `dessin - bitmap FIT centré dans l'écran de la carte (vrais pixels)`() = runTest {
        val moteur = FauxMoteur()
        val hote = hote(moteur, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val rouge = Color.rgb(0xE0, 0x31, 0x31)
        moteur.etatInterne.value =
            EtatPreviewCompose.RenduValide(listOf(rendu("A", bitmap = carre(8, rouge))))

        // Écran de carte 40×60 px, bitmap 8×8 → échelle 5 → 40×40
        // POSÉ au CENTRE vertical (y ∈ [10, 50]) — letterbox nu.
        val cible = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        hote.drawDropPreview(Canvas(cible), 0f, 0f, 40f, 60f, 0)
        assertEquals("le centre est couvert", rouge, cible.getPixel(20, 30))
        assertEquals("letterbox haut transparent", Color.TRANSPARENT, cible.getPixel(20, 5))
        assertEquals("letterbox bas transparent", Color.TRANSPARENT, cible.getPixel(20, 55))
    }

    @Test
    fun `messages honnêtes - recherche, carte vide, échec du document, échec LOCAL`() = runTest {
        val moteur = FauxMoteur()
        val hote = hote(moteur, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Inactif : le branchement vient d'être posé, AUCUNE passe
        // n'a répondu — la carte annonce la RECHERCHE.
        assertEquals(
            contexte.getString(R.string.apercu_compose_recherche),
            hote.messageDe(0),
        )

        // EnEdition : une passe a répondu VIDE — plus rien à chercher.
        moteur.etatInterne.value = EtatPreviewCompose.EnEdition
        assertEquals(contexte.getString(R.string.apercu_compose_aucune), hote.messageDe(0))

        // Échec du document ENTIER : le message du moteur prime.
        moteur.etatInterne.value = EtatPreviewCompose.ErreurRendu("processus :preview indisponible")
        assertEquals("processus :preview indisponible", hote.messageDe(0))

        // Un rendu VALIDE avec un onglet en échec LOCAL : l'échec de
        // CET onglet prime (les autres se rendent — isolation).
        moteur.etatInterne.value =
            EtatPreviewCompose.RenduValide(
                listOf(
                    rendu("Ok", bitmap = carre(4, Color.BLUE)),
                    rendu("Fautif", index = 1, messageErreur = "composition gelée (budget)"),
                ),
            )
        assertEquals("composition gelée (budget)", hote.messageDe(1))
    }

    @Test
    fun `brancher - le moteur suit l'onglet ACTIF, la frappe soumet, la ré-ouverture retente`() = runTest {
        val moteur = FauxMoteur()
        val etatEditeur = MutableStateFlow(etatAvecOnglet("file:///a.kt", "a.kt"))
        val hote = hote(moteur, etatEditeur, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals(listOf("file:///a.kt"), moteur.branchements)

        // Même onglet re-émis (rendu, sauvegarde) : PAS de re-branchement.
        etatEditeur.value = etatAvecOnglet("file:///a.kt", "a.kt").copy(chargement = false)
        assertEquals("distinctUntilChanged — une seule liaison", listOf("file:///a.kt"), moteur.branchements)

        // Changement d'onglet : le moteur re-branche l'URI active.
        etatEditeur.value = etatAvecOnglet("file:///b.kt", "b.kt")
        assertEquals(listOf("file:///a.kt", "file:///b.kt"), moteur.branchements)

        // La frappe pousse le contenu (le moteur débounce).
        hote.onPreviewContentChanged("fun A() {}")
        assertEquals(1, moteur.soumissions)

        // L'OUVERTURE de la carte est une nouvelle tentative (miroir
        // « Réessayer ») ; la fermeture, elle, ne relance rien.
        hote.onPreviewModeChanged(EditorView.PreviewMode.DROP, 0, 0)
        assertEquals(1, moteur.reactivations)
        hote.onPreviewModeChanged(EditorView.PreviewMode.NONE, 0, 0)
        assertEquals("la fermeture ne réactive pas", 1, moteur.reactivations)
    }

    @Test
    fun `contrat cel-ui - une liste d'onglets qui change referme et ré-ouvre la carte au même caret`() = runTest {
        val moteur = FauxMoteur()
        val etatEditeur = MutableStateFlow(etatAvecOnglet("file:///a.kt", "a.kt"))
        val hote = hote(moteur, etatEditeur, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        val vue = EditorView(contexte)
        vue.setSession(EditorSession(EditorDocument.of("fun A() {}\n")))
        hote.installerSur(vue)
        vue.setFileName("a.kt")
        vue.measure(1080, 1920)
        vue.layout(0, 0, 1080, 1920)

        moteur.etatInterne.value = EtatPreviewCompose.RenduValide(listOf(rendu("A")))
        vue.openPreviewDropAt(2)
        assertEquals("la carte vit", EditorView.PreviewMode.DROP, vue.getPreviewMode())
        assertEquals("onglets mesurés à l'OUVERTURE", listOf("A"), hote.previewDropTabs())
        assertEquals(1, moteur.reactivations)

        // La liste change la carte VIVANTE : le contrat cel-ui impose
        // des libellés stables — l'hôte referme/ré-ouvre (glissade
        // 180 ms côté bibliothèque), l'onglet retombe à 0.
        moteur.etatInterne.value =
            EtatPreviewCompose.RenduValide(listOf(rendu("A"), rendu("B")))
        assertEquals("ré-ouverte — toujours vivante", EditorView.PreviewMode.DROP, vue.getPreviewMode())
        assertEquals("les NOUVEAUX libellés sont mesurés", listOf("A", "B"), hote.previewDropTabs())
        assertEquals("la ré-ouverture est une nouvelle tentative", 2, moteur.reactivations)

        // Une liste qui ne CHANGE PAS (re-rendu du même document) ne
        // fait PAS danser la carte.
        val reactivations = moteur.reactivations
        moteur.etatInterne.value =
            EtatPreviewCompose.RenduValide(listOf(rendu("A", bitmap = carre(4, Color.GREEN)), rendu("B")))
        assertEquals("aucune ré-ouverture parasite", reactivations, moteur.reactivations)
    }
}
