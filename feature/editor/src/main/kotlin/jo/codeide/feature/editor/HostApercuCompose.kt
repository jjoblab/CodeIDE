package jo.codeide.feature.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import jo.codeeditor.view.EditorView
import jo.codeeditor.view.preview.EditorPreviewHost
import jo.codeide.lsp.host.SessionLspKotlin
import jo.codeide.preview.compose.EtatPreviewCompose
import jo.codeide.preview.compose.MoteurPreviewCompose
import jo.codeide.preview.compose.RenduPreviewCompose
import jo.lspkotlin.api.PreviewKotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Port étroit du moteur de preview Compose consommé par l'hôte (C1) —
 * [MoteurPreviewCompose] le remplit à l'identique via [enPort] ; les
 * tests de la feature injectent un faux port (un flux d'état piloté à
 * la main) SANS dépendre du moteur réel ni du processus :preview.
 */
internal interface PortMoteurPreviewCompose : AutoCloseable {
    /** État courant du moteur (rendus par preview — l'indice = l'onglet). */
    val etat: StateFlow<EtatPreviewCompose>

    /** Branche un document (changement d'onglet) : re-rendu immédiat. */
    fun brancher(uri: String, fournisseur: () -> List<PreviewKotlin>)

    /** Re-soumet le document branché (le contenu a changé — debounce moteur). */
    fun soumettre()

    /** Ré-activation du processus `:preview` (ré-ouverture = nouvelle tentative). */
    fun reactiverIsolation()
}

/** Adaptateur 1:1 du moteur réel sur le port (quatre délégations pures). */
private class MoteurComposeSurPort(private val moteur: MoteurPreviewCompose) : PortMoteurPreviewCompose {
    override val etat: StateFlow<EtatPreviewCompose> = moteur.etat

    override fun brancher(uri: String, fournisseur: () -> List<PreviewKotlin>) = moteur.brancher(uri, fournisseur)

    override fun soumettre() = moteur.soumettre()

    override fun reactiverIsolation() = moteur.reactiverIsolation()

    override fun close() = moteur.close()
}

/** Enveloppe le moteur réel dans le port consommé par l'hôte. */
internal fun MoteurPreviewCompose.enPort(): PortMoteurPreviewCompose = MoteurComposeSurPort(this)

/**
 * Hôte de la carte d'aperçu « goutte d'eau » pour les previews Compose
 * (mission « Previews » C1 — PREVIEW.md §4, cel-ui B27) : l'alternative
 * mobile au split view ACCUEILLE le rendu du moteur — l'œil de
 * gouttière d'un fichier Kotlin ouvre la carte ancrée au caret, les
 * ONGLETS de la carte sont les `@Preview` découvertes du document (une
 * par fonction, ordre du document — l'indice de
 * [EtatPreviewCompose.RenduValide] EST l'onglet), l'écran du
 * mini-téléphone reçoit le bitmap du rendu, FIT centré.
 *
 * PÉRIMÈTRE C1 (décisions) :
 * - SEUL le Kotlin est concerné — [canPreview] répond vrai pour
 *   `.kt`/`.kts` et FAUX pour tout le reste : l'œil n'apparaît donc
 *   JAMAIS sur les layouts XML, dont l'aperçu reste l'écran dédié
 *   (:feature:preview — aucun changement de comportement) ;
 * - [prefersDropPreview] vrai : le tap ouvre la CARTE (jamais la
 *   feuille/le panneau scindé — CodeIDE n'entre jamais en SPLIT/FULL
 *   pour le Kotlin, les méthodes canvas de ce chemin sont des no-ops
 *   honnêtes) ;
 * - CONTRAT « jamais de carte vide » (miroir du moteur) : pendant les
 *   frappes, le DERNIER rendu valide de chaque onglet RESTE affiché —
 *   `EnEdition`/`Inactif` sont des signaux discrets, jamais des
 *   remplacements ; [previewDropTabs] lit le dernier rendu VALIDE ;
 * - CONTRAT cel-ui des onglets (« mesurés à l'ouverture, stables tant
 *   que la carte vit ») : quand la liste des previews change la carte
 *   VIVANTE, l'hôte la REFERME/RÉ-OUVRE au même caret (glissade 180 ms
 *   de la bibliothèque — l'onglet retombe à 0, documenté) ;
 * - la barre de taille de la carte VIT déjà (cel-ui) ; sa persistance
 *   inter-processus relève du pipeline Réglages (chantier ultérieur) —
 *   [onPreviewDropScaleChanged] documente le renoncement.
 *
 * CYCLE DE VIE : l'hôte appartient à [EditorViewModel] (survit aux
 * re-créations de l'activité) ; [installerSur] pose la référence de la
 * vue UNE fois (l'activité, qui seule connaît `liaison.vueEditeur`) ;
 * `close()` coupe le moteur → déliaison du transport → le processus
 * `:preview` meurt seul (idle-shutdown).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — le contrat
 * [EditorPreviewHost] de cel-ui EXIGE ses dix méthodes (cinq sans
 * défaut), chacune a SA responsabilité ; il n'y a rien à regrouper
 * sans casser l'interface.
 */
@Suppress("TooManyFunctions")
internal class HostApercuCompose(
    private val contexte: Context,
    etat: StateFlow<EtatEditor>,
    private val sessionKotlin: StateFlow<SessionLspKotlin?>,
    scope: CoroutineScope,
    private val moteur: PortMoteurPreviewCompose,
) : EditorPreviewHost,
    AutoCloseable {
    /** Dernier rendu VALIDE (contrat « jamais de carte vide » — survit aux signaux discrets). */
    @Volatile
    private var dernierValide: EtatPreviewCompose.RenduValide? = null

    /** Message d'échec du document ENTIER ([EtatPreviewCompose.ErreurRendu]). */
    @Volatile
    private var messageDocument: String? = null

    /** Dernier état brut du moteur (vide honnête tant qu'aucun rendu). */
    @Volatile
    private var etatMoteur: EtatPreviewCompose = EtatPreviewCompose.Inactif

    /** Onglet actif courant : (uri, nom) — badge + brancher le moteur. */
    @Volatile
    private var document: Pair<String, String>? = null

    /** Libellés mesurés par la carte à l'OUVERTURE (contrat cel-ui — le miroir des onglets). */
    @Volatile
    private var ongletsMesures: List<String> = emptyList()

    /** La carte est-elle ouverte (miroir des onPreviewModeChanged DROP/NONE). */
    @Volatile
    private var carteOuverte = false

    /** La vue éditeur (posée par [installerSur] — la seule écriture). */
    @Volatile
    private var vueEditeur: EditorView? = null

    /** Peinture du bitmap (filtrage bilinéaire du FIT). */
    private val peintureBitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Peinture des messages honnêtes (état vide / échec). */
    private val peintureTexte = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        scope.launch {
            // L'onglet ACTIF suit l'état de l'espace : chaque CHANGEMENT
            // d'onglet re-branche le moteur (re-rendu immédiat — un
            // onglet non-Kotlin dégrade honnêtement : la session Kotlin
            // ne détient pas ce document, previews() rend VIDE, l'état
            // passe EnEdition, la carte — refermée au changement de
            // session par cel-ui — ne montre rien de périmé).
            etat
                .map { etatEditeur ->
                    etatEditeur.onglets
                        .getOrNull(etatEditeur.indexOngletActif)
                        ?.let { onglet -> onglet.uri to onglet.nom }
                }.distinctUntilChanged()
                .collect { actif ->
                    document = actif
                    val uri = actif?.first ?: return@collect
                    // Le fournisseur vit sur le FIL DU MOTEUR (après le
                    // debounce) : la session est relue À CHAQUE passe —
                    // jamais une session morte capturée.
                    moteur.brancher(uri) { sessionKotlin.value?.previews(uri) ?: emptyList() }
                }
        }
        scope.launch {
            moteur.etat.collect { etat ->
                etatMoteur = etat
                when (etat) {
                    is EtatPreviewCompose.RenduValide -> {
                        dernierValide = etat
                        messageDocument = null
                        rafraichirOngletsSiCarteOuverte(etat)
                    }

                    is EtatPreviewCompose.ErreurRendu -> {
                        messageDocument = etat.message
                    }

                    // Inactif/EnEdition : signaux DISCRETS — les
                    // derniers rendus restent affichés (contrat du
                    // moteur, « jamais de carte vide »).
                    else -> {}
                }
            }
        }
    }

    /** Pose la vue éditeur et S'Y INSTALLE comme hôte (une fois, à brancherEditeur). */
    fun installerSur(vue: EditorView) {
        vueEditeur = vue
        vue.setPreviewHost(this)
    }

    /** Coupe le moteur (déliaison :preview — le processus meurt seul). Idempotent par délégation. */
    override fun close() {
        moteur.close()
    }

    // ── Contrat EditorPreviewHost : la carte « goutte d'eau » ────────

    override fun canPreview(fileName: String?): Boolean {
        // C1 : SEUL le Kotlin. L'aperçu XML reste l'écran dédié
        // (:feature:preview) — l'œil ne doit JAMAIS apparaître sur un
        // layout (garde de régression testée).
        val nom = fileName ?: return false
        return nom.endsWith(".kt", ignoreCase = true) || nom.endsWith(".kts", ignoreCase = true)
    }

    override fun onPreviewModeChanged(mode: EditorView.PreviewMode?, previewLeft: Int, previewWidth: Int) {
        carteOuverte = mode == EditorView.PreviewMode.DROP
        if (carteOuverte) {
            // La (ré-)ouverture est un GESTE utilisateur = nouvelle
            // tentative du processus :preview (miroir « Réessayer ») ;
            // moteur sain → no-op (digeste inchangé, zéro Binder).
            moteur.reactiverIsolation()
        }
    }

    override fun onPreviewContentChanged(text: CharSequence?) {
        // Le moteur débounce (400 ms) et diff par digeste : les frappes
        // sans effet sémantique ne re-rendent RIEN.
        moteur.soumettre()
    }

    override fun drawPreview(canvas: Canvas?, offsetX: Float, offsetY: Float) {
        // Jamais appelé : prefersDropPreview() vrai → la carte ; et
        // CodeIDE n'entre jamais en SPLIT/FULL pour le Kotlin.
    }

    override fun hasPreviewContent(): Boolean = false

    override fun hitTestPreview(x: Float, y: Float): Boolean = false

    override fun prefersDropPreview(): Boolean = true

    override fun previewDropTabs(): List<String> = nomsCourants().also { ongletsMesures = it }

    override fun onPreviewDropTabSelected(index: Int) {
        // Aucun état à maintenir : drawDropPreview reçoit l'onglet à
        // chaque frame (miroir DemoPreviewHost) et le moteur re-rend
        // TOUT le document par passe (diff d'identité, PC1).
    }

    override fun drawDropPreview(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, tab: Int) {
        val rendu = dernierValide?.rendus?.getOrNull(tab)
        val bitmap = rendu?.bitmap
        if (bitmap != null) {
            // FIT centré (letterbox) — le bitmap porte SON fond
            // jour/nuit ; l'écran de la carte reste la base du painter.
            val echelle = minOf(w / bitmap.width, h / bitmap.height)
            val gauche = x + (w - bitmap.width * echelle) / 2f
            val haut = y + (h - bitmap.height * echelle) / 2f
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(gauche, haut, gauche + bitmap.width * echelle, haut + bitmap.height * echelle),
                peintureBitmap,
            )
            return
        }
        dessinerMessage(canvas, RectF(x, y, x + w, y + h), messageDe(tab))
    }

    override fun previewDropBadge(tab: Int): String? {
        val nomDocument = document?.second ?: return null
        val nomPreview = dernierValide?.rendus?.getOrNull(tab)?.nom
        return if (nomPreview != null) "$nomDocument · @Preview $nomPreview" else nomDocument
    }

    override fun onPreviewDropScaleChanged(scale: Float) {
        // C1 : la barre de taille VIT déjà (cel-ui) ; la persistance
        // inter-processus relève du pipeline Réglages (AppSettings +
        // :feature:settings) — chantier ultérieur documenté au CHANGELOG.
    }

    // ── Internes ────────────────────────────────────────────────────

    /**
     * Le message honnête d'un onglet sans bitmap : l'échec LOCAL de cet
     * onglet ([RenduPreviewCompose.messageErreur]), sinon l'échec du
     * document ([EtatPreviewCompose.ErreurRendu]), sinon l'état vide —
     * « recherche » tant qu'AUCUNE passe n'a répondu (Inactif :
     * branchement tout juste posé, rendu en vol), « aucune » dès qu'une
     * passe a trouvé RIEN (EnEdition). Sonde interne (les tests
     * l'appellent directement — le dessin reste pixel-vérifié à part).
     */
    internal fun messageDe(tab: Int): String = dernierValide?.rendus?.getOrNull(tab)?.messageErreur
        ?: messageDocument
        ?: when (etatMoteur) {
            is EtatPreviewCompose.Inactif -> {
                contexte.getString(R.string.apercu_compose_recherche)
            }

            else -> {
                contexte.getString(R.string.apercu_compose_aucune)
            }
        }

    /** Les noms des previews du dernier rendu VALIDE, ellipsés pour la carte. */
    private fun nomsCourants(): List<String> = dernierValide?.rendus?.map { nomCourt(it.nom) } ?: emptyList()

    /** Ellipse d'un libellé d'onglet (l'en-tête de la carte est étroit). */
    private fun nomCourt(nom: String): String =
        if (nom.length <= LONGUEUR_ONGLET) nom else nom.take(LONGUEUR_ONGLET - 1) + "…"

    /**
     * Contrat cel-ui : les libellés sont MESURÉS à l'ouverture — un
     * changement de la liste pendant que la carte vit la REFERME/
     * RÉ-OUVRE au même caret (glissade 180 ms de la bibliothèque ;
     * l'onglet retombe à 0 — coût documenté, les listes ne bougent que
     * quand une `@Preview` est ajoutée/retirée en cours d'édition).
     */
    private fun rafraichirOngletsSiCarteOuverte(etat: EtatPreviewCompose.RenduValide) {
        val vue = vueEditeur
        val noms = etat.rendus.map { nomCourt(it.nom) }
        if (vue != null && carteOuverte && noms != ongletsMesures) {
            val ancre =
                vue.session
                    ?.let { session -> session.getDocument().lineForOffset(session.getSelection().start) }
                    ?: 0
            vue.closePreviewDrop()
            vue.openPreviewDropAt(ancre)
        }
    }

    /** Message centré dans l'écran du mini-téléphone (couleur d'appui sur fond clair de la carte). */
    private fun dessinerMessage(canvas: Canvas, ecran: RectF, message: String) {
        val densite = contexte.resources.displayMetrics.density
        peintureTexte.textSize = TAILLE_MESSAGE_DP * densite
        peintureTexte.color = COULEUR_MESSAGE
        peintureTexte.textAlign = Paint.Align.CENTER
        val marge = MARGE_MESSAGE_DP * densite
        // Ellipse honnête à la largeur de l'écran (le message peut être
        // un messageErreur du processus — verbeux par nature).
        var texte = message
        val largeurMax = ecran.width() - 2 * marge
        if (peintureTexte.measureText(texte) > largeurMax) {
            while (texte.isNotEmpty() &&
                peintureTexte.measureText(texte + "…") > largeurMax
            ) {
                texte = texte.dropLast(1)
            }
            texte += "…"
        }
        canvas.drawText(texte, ecran.centerX(), ecran.centerY(), peintureTexte)
    }

    private companion object {
        /** Longueur maximale d'un libellé d'onglet (l'en-tête partage la place avec le titre et ✕). */
        internal const val LONGUEUR_ONGLET: Int = 14

        /** Taille du message honnête (dp — coordonnées carte, densité appliquée au dessin). */
        internal const val TAILLE_MESSAGE_DP: Float = 11f

        /** Marge latérale du message honnête (dp). */
        internal const val MARGE_MESSAGE_DP: Float = 12f

        /** Gris d'appui M3 sur l'écran clair de la carte (0xFF6D6D72 — onSurfaceVariant). */
        internal const val COULEUR_MESSAGE: Int = 0xFF6D6D72.toInt()
    }
}
