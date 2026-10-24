package jo.codeide.feature.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import jo.codeeditor.session.EditorSession
import jo.codeeditor.view.EditorView
import jo.codeeditor.view.preview.EditorPreviewHost
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.lsp.host.SessionLspKotlin
import jo.codeide.preview.compose.EtatPreviewCompose
import jo.codeide.preview.compose.EvenementTactileCompose
import jo.codeide.preview.compose.MoteurPreviewCompose
import jo.codeide.preview.compose.RenduPreviewCompose
import jo.codeide.preview.compose.RequeteRenduCompose
import jo.lspkotlin.api.PreviewKotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * C2 — Définit le PROJET du rendu (dossier `res/`, langue, thème
     * d'activité) : ressources et thème de la composition. Un
     * changement relance un rendu complet.
     */
    fun definirProjet(dossierRes: String?, langue: String?, themeName: String?)

    /**
     * C3 — HORLOGE INTERACTIVE : arme (ou éteint) le pompier de
     * l'onglet AFFICHÉ de la carte (`identiteDe(uri, nom, onglet)` —
     * `null` = carte fermée : les animations se figent).
     */
    fun definirInteractif(identite: String?, onglet: Int)

    /**
     * C3 — FORWARDING : pousse UNE touche du premier doigt à la session
     * interactive courante (no-op honnête si aucune). Le CONTRAT
     * [EvenementTactileCompose] voyage ENTIER (action, point en px
     * bitmap — l'hôte a inversé son FIT, horodatages, pression) : le
     * port ne déroule jamais les six champs (detekt LongParameterList
     * tué par RESTRUCTURE — l'objet EST la donnée).
     */
    fun transmettreTouch(evenement: EvenementTactileCompose)
}

/** Adaptateur 1:1 du moteur réel sur le port (sept délégations pures). */
private class MoteurComposeSurPort(private val moteur: MoteurPreviewCompose) : PortMoteurPreviewCompose {
    override val etat: StateFlow<EtatPreviewCompose> = moteur.etat

    override fun brancher(uri: String, fournisseur: () -> List<PreviewKotlin>) = moteur.brancher(uri, fournisseur)

    override fun soumettre() = moteur.soumettre()

    override fun reactiverIsolation() = moteur.reactiverIsolation()

    override fun definirProjet(dossierRes: String?, langue: String?, themeName: String?) =
        moteur.definirProjet(dossierRes, langue, themeName)

    override fun definirInteractif(identite: String?, onglet: Int) = moteur.definirInteractif(identite, onglet)

    override fun transmettreTouch(evenement: EvenementTactileCompose) = moteur.transmettreTouch(evenement)

    override fun close() = moteur.close()
}

/** Enveloppe le moteur réel dans le port consommé par l'hôte. */
internal fun MoteurPreviewCompose.enPort(): PortMoteurPreviewCompose = MoteurComposeSurPort(this)

/**
 * C2/C4 — les RÉSOLVEURS DE DOCUMENT de l'hôte (défauts = comportement
 * C1 pur — chaque champ est indépendamment optionnel) : le PROJET de
 * l'onglet (dossier `res/` + thème du manifeste), l'URI LSP du didOpen
 * (`"file://" + FUSE` — miroir du `createEditor` du CoordonnateurLsp)
 * et le DOUBLAGE de la découverte (tests : `SessionLspKotlin` est une
 * classe FINALE ; null = la session LSP vivante, repli uri d'onglet).
 */
internal class PortsDocumentApercuCompose(
    val fournisseurProjet: suspend (String) -> ProjetApercuCompose? = { null },
    val resolveurUriLsp: suspend (String) -> String? = { null },
    val decouvreur: PortDecouvertePreviews? = null,
)

/**
 * C4 — la découverte des `@Preview` côté session Kotlin (port INJECTABLE :
 * les tests doublent la CLÉ interrogée — [SessionLspKotlin] est une
 * classe FINALE, invraisemblable à doubler honnêtement). Le DÉFAUT vit
 * au constructeur de [HostApercuCompose] : session vivante relue à
 * chaque passe, URI LSP du didOpen d'abord puis repli best-effort sur
 * l'uri d'onglet.
 */
internal fun interface PortDecouvertePreviews {
    /**
     * Les `@Preview` du document actif, dans l'ordre du document.
     *
     * @param uriLsp     l'URI LSP du didOpen ("file://" + FUSE — clé de
     *                   la carte `documents` du moteur Kotlin), ou null
     *                   si la résolution FUSE a échoué.
     * @param uriOnglet  l'uri SAF de l'onglet (repli best-effort).
     */
    fun decouvrir(uriLsp: String?, uriOnglet: String): List<PreviewKotlin>
}

/**
 * Le PROJET de l'aperçu Compose d'un document (C2) — résolu par
 * [HostApercuCompose.fournisseurProjet] côté [EditorViewModel] :
 * dossier `res/` (ressources + thème de la composition) et thème
 * d'activité du manifeste (miroir PE1). `null`/absent = la composition
 * rend sur les DÉFAUTS DU RUNTIME (comportement C1).
 */
internal data class ProjetApercuCompose(
    /** Dossier `res/` du projet (`null` = repli défaults runtime). */
    val dossierRes: String?,
    /** Thème d'activité du manifeste (`null` = chaîne par défaut du résolveur). */
    val themeName: String?,
)

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
 * sans casser l'interface. LongParameterList — SEPT frontières
 * DISTINCTES et irréductibles (le contexte des ressources, les DEUX
 * flux d'état — espace et session LSP, la portée, les dispatcheurs,
 * le port du moteur, les résolveurs de document) : chaque paramètre
 * est une frontière consommée — le regroupement supplémentaire serait
 * FORCÉ (miroir CoordonnateurLsp.brancher).
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal class HostApercuCompose(
    private val contexte: Context,
    etat: StateFlow<EtatEditor>,
    private val sessionKotlin: StateFlow<SessionLspKotlin?>,
    scope: CoroutineScope,
    dispatcheurs: DispatcherProvider,
    private val moteur: PortMoteurPreviewCompose,
    /**
     * C2/C4 — les résolveurs DE DOCUMENT : projet (dossier `res/` + thème
     * du manifeste), URI LSP du didOpen ("file://" + FUSE — la clé de la
     * carte `documents` du moteur Kotlin, jamais l'uri SAF de l'onglet)
     * et doublage de la découverte (null = session vivante).
     */
    ports: PortsDocumentApercuCompose = PortsDocumentApercuCompose(),
) : EditorPreviewHost,
    AutoCloseable {
    /** La portée des tirages d'ancres hors collect (débounces de frappe). */
    private val portee: CoroutineScope = scope

    /** C4 — la marche de découverte vit HORS fil d'UI (miroir moteur). */
    private val dispatcheurs = dispatcheurs

    /** C2 — résolution suspend FUSE du projet de l'onglet (défauts runtime). */
    private val fournisseurProjet = ports.fournisseurProjet

    /** C4 — l'URI LSP du didOpen ("file://" + FUSE — clé du moteur Kotlin). */
    private val resolveurUriLsp = ports.resolveurUriLsp

    /**
     * C4 — la découverte : doublée par les tests (clé traduite), sinon la
     * SESSION VIVANTE (relue à chaque passe — jamais une session morte
     * capturée) : URI LSP d'abord, repli best-effort sur l'uri d'onglet.
     */
    private val decouvreur: PortDecouvertePreviews =
        ports.decouvreur
            ?: PortDecouvertePreviews { uriLsp, uriOnglet ->
                val session = sessionKotlin.value
                    ?: return@PortDecouvertePreviews emptyList()
                if (uriLsp != null && uriLsp != uriOnglet) {
                    val trouvees = session.previews(uriLsp)
                    if (trouvees.isNotEmpty()) {
                        return@PortDecouvertePreviews trouvees
                    }
                }
                session.previews(uriOnglet)
            }

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

    /** C3 — l'onglet AFFICHÉ de la carte (miroir du selectedTab cel-ui, vérité au frame). */
    @Volatile
    private var ongletAffiche: Int = 0

    /** C3 — le FIT du DERNIER dessin (la carte touche ce qu'elle dessine — repère partagé). */
    @Volatile
    private var fitDernier: AjustementEcran? = null

    /** C3 — l'uptimeMillis du DOWN du geste en cours (les clics de la composition se règlent sur le temps du doigt). */
    @Volatile
    private var downTimeGeste: Long = 0L

    /** C4 — l'URI LSP du document ACTIF ("file://" + FUSE — clé du didOpen). */
    @Volatile
    private var uriLspDocument: String? = null

    /**
     * C4 — les ANCRES des yeux : (ligne 0-indexée de l'annotation, indice
     * de la preview) — l'ordre EST le mapping tap → onglet (B28 cel-ui
     * 4.2.0 : un œil par @Preview, à SA ligne, zéro sans).
     */
    @Volatile
    private var ancres: List<Pair<Int, Int>> = emptyList()

    /** C4 — la session écoutée pour le rafraîchissement des yeux à la frappe. */
    @Volatile
    private var sessionEcoutee: EditorSession? = null

    /** C4 — le débouncer des yeux (400 ms — miroir du debounce moteur). */
    private val debounceAncres = android.os.Handler(android.os.Looper.getMainLooper())

    /** C4 — le tirage débouncé de la découverte (les yeux vivent carte FERMÉE). */
    private val tirageAncres = Runnable {
        portee.launch {
            rafraichirAncres()
        }
    }

    /** C4 — l'écoute de frappe : une ligne bougée = les yeux se recalculent. */
    private val ecouteurTexte = EditorSession.OnTextEditListener { _, _, _ ->
        debounceAncres.removeCallbacks(tirageAncres)
        debounceAncres.postDelayed(tirageAncres, DEBOUNCE_ANCRES_MS)
    }

    /** Peinture du bitmap (filtrage bilinéaire du FIT). */
    private val peintureBitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Peinture des messages honnêtes (état vide / échec). */
    private val peintureTexte = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Peinture du CONTOUR de surlignage (C2 — trait 2 dp, teinte d'appui du chrome). */
    private val peintureContour = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Peinture du REMPLISSAGE de surlignage (C2 — même teinte translucide). */
    private val peintureRemplissage = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

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
                    // C2 — le PROJET suit l'onglet actif : ressources et
                    // thème de la composition (un CHANGEMENT relance un
                    // rendu complet côté moteur ; null = défauts du
                    // runtime, comportement C1 — projet absent).
                    var projet: ProjetApercuCompose? = null
                    if (actif != null) {
                        projet = fournisseurProjet(actif.first)
                    }
                    moteur.definirProjet(projet?.dossierRes, null, projet?.themeName)
                    val uri = actif?.first ?: return@collect
                    // C4 — l'URI LSP du didOpen ("file://" + FUSE) : la
                    // carte `documents` du moteur Kotlin est indexée par
                    // CETTE clé — l'uri SAF de l'onglet n'y figure JAMAIS
                    // (la découverte de C1-C3 rendait donc TOUJOURS vide
                    // sur les fichiers projet : le bug « pas de preview »
                    // des tests manuels v0.176.0).
                    uriLspDocument = resolveurUriLsp(uri)
                    // C4 — les yeux du document précédent partent : la
                    // découverte du NOUVEAU document les repose (vidage
                    // honnête d'abord — zéro œil périmé).
                    publierAncres(emptyList())
                    ecouterSessionVue()
                    // Le fournisseur vit sur le FIL DU MOTEUR (après le
                    // debounce) : la session et l'URI traduit sont relus à
                    // CHAQUE passe — jamais une session morte capturée.
                    moteur.brancher(uri) { decouvrirPreviews(uri) }
                    // Yeux immédiats : la découverte directe (débarrassée
                    // du rendu) pose les ancres SANS attendre le premier
                    // bitmap (la machine à froid du :preview peut prendre
                    // des secondes — l'œil doit être là AVANT).
                    rafraichirAncres()
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
                        // C4 — chaque rendu RAFRAÎCHIT les yeux : les lignes
                        // des @Preview voyagent avec le rendu (le moteur
                        // enrichit chaque onglet depuis SA découverte).
                        publierAncres(
                            etat.rendus.mapIndexed { indice, rendu ->
                                rendu.ligneAnnotation to indice
                            },
                        )
                        // C3 — la carte ouverte suit les frames : le pompier
                        // interactif publie un bitmap AVANCÉ toutes les ~33 ms,
                        // l'éditeur REDESSINE (sinon la carte afficherait le
                        // bitmap figé du frame précédent).
                        armerInteractif()
                        if (carteOuverte) vueEditeur?.invalidate()
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
        ecouterSessionVue()
        // C4 — les ancres calculées AVANT la pose (onglet actif restauré
        // avant brancherEditeur) rejoignent la vue maintenant : les yeux
        // ne restent JAMAIS en attente d'un hypothétique changement
        // d'onglet.
        vue.setPreviewAnchors(ancres.map { it.first })
    }

    /** Coupe le moteur (déliaison :preview — le processus meurt seul). Idempotent par délégation. */
    override fun close() {
        debounceAncres.removeCallbacks(tirageAncres)
        sessionEcoutee?.removeOnTextEditListener(ecouteurTexte)
        sessionEcoutee = null
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
            // Miroir d'openAt (cel-ui) : toute ouverture repart à
            // l'onglet 0 — le branchement interactif suit la vérité.
            ongletAffiche = 0
            // C3 — l'ouverture ARME l'horloge sur l'onglet affiché s'il
            // porte déjà un rendu (le collecteur d'état re-branche à
            // chaque RenduValide, l'ouverture qui suit un rendu déjà
            // publié arme ici — jamais d'attente de la prochaine frame).
            armerInteractif()
        } else {
            // C3 — carte fermée : l'horloge interactive S'ARRÊTE (les
            // animations se figent, le processus redevient inactif à
            // l'idle — jamais d'animation en arrière-fond).
            moteur.definirInteractif(null, 0)
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
        // C3 — l'onglet affiché change : le pompier interactif suit
        // (l'onglet VISIBLE s'anime, les autres attendent — jamais de
        // re-rendu des onglets cachés). Le reste ne bouge pas : le
        // moteur re-rend TOUT le document par passe (diff d'identité,
        // PC1) et drawDropPreview reçoit l'onglet à chaque frame.
        ongletAffiche = index
        armerInteractif()
    }

    override fun onPreviewAnchorTapped(line: Int) {
        // C4 (B28 cel-ui 4.2.0) — l'œil d'UNE @Preview vient d'être tapé :
        // la carte s'ouvre sur SA ligne, on sélectionne SON onglet (la
        // ligne introuvable = ancres périmées entre deux rendus : no-op
        // honnête, l'onglet 0 reste).
        val indice = ancres.firstOrNull { it.first == line }?.second ?: return
        vueEditeur?.selectPreviewDropTab(indice)
    }

    override fun drawDropPreview(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, tab: Int) {
        ongletAffiche = tab
        val rendu = dernierValide?.rendus?.getOrNull(tab)
        val bitmap = rendu?.bitmap
        if (bitmap != null) {
            // FIT centré (letterbox) — le bitmap porte SON fond
            // jour/nuit ; l'écran de la carte reste la base du painter.
            val echelle = minOf(w / bitmap.width, h / bitmap.height)
            val gauche = x + (w - bitmap.width * echelle) / 2f
            val haut = y + (h - bitmap.height * echelle) / 2f
            // C3 — le FIT du dessin devient le REPÈRE des touches : la
            // carte touche au pixel près ce qu'elle dessine (l'inverse
            // du FIT rejoue le doigt en px bitmap).
            fitDernier = AjustementEcran(echelle, gauche, haut, bitmap.width, bitmap.height)
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(gauche, haut, gauche + bitmap.width * echelle, haut + bitmap.height * echelle),
                peintureBitmap,
            )
            // C2 — SURLIGNAGE du nœud du caret : les géométries extraites
            // (rendu.noeuds — bounds bitmap + ligne source, écho
            // ComposeViewInfoParser) désignent le nœud de la LIGNE du
            // caret de l'éditeur ; le sens inverse (APPUIS LONG sur un
            // nœud → saut source) vit en C3
            // ([onPreviewDropLongPress]).
            dessinerSurlignage(canvas, rendu, echelle, gauche, haut)
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

    // ── C3 : le CORPS interactif (B27-C3 de cel-ui 4.1.0) ───────────

    /** C3 — le corps de la carte est INTERACTIF : touches et appui long transférés. */
    override fun wantsInteractiveDropPreview(): Boolean = true

    /**
     * C3 — FORWARDING : le doigt de l'écran du mini-téléphone rejoint la
     * composition vivante du processus :preview (écho
     * `triggerTouchEvent`). L'inverse du FIT rejoue le contact en PX
     * BITMAP (le repère du canevas) ; le DOWN mémorise son HORODATAGE
     * (les clics/long-clics de la composition se règlent sur le TEMPS
     * RÉEL du doigt). Sans dessin récent (aucun FIT), sans bitmap : le
     * DOWN est DÉCLINÉ — cel-ui retombe sur le corps inerte.
     */
    override fun onPreviewDropTouch(action: Int, sx: Float, sy: Float): Boolean {
        if (action == MotionEvent.ACTION_DOWN) {
            downTimeGeste = SystemClock.uptimeMillis()
        }
        val point = bitmapDe(sx, sy) ?: return false
        moteur.transmettreTouch(
            EvenementTactileCompose(
                action = action,
                xPx = point[0],
                yPx = point[1],
                downTimeMs = downTimeGeste,
                eventTimeMs = SystemClock.uptimeMillis(),
                pression = PRESSION_DOIGT,
            ),
        )
        return true
    }

    /**
     * C3 — INSPECTION (appui long, le « Ctrl+clic » mobile) : le nœud le
     * plus PETIT contenant le point rejoint son code — le caret SAUTE à
     * la ligne source et l'éditeur la REVELE (la carte esquive le bloc
     * de frappe au frame suivant, elle reste ouverte). Sans nœud sous le
     * doigt, sans vue : RIEN (aucun faux saut).
     */
    override fun onPreviewDropLongPress(sx: Float, sy: Float) {
        // UNE SEULE cible calculée en expression : chaque absence (vue,
        // session, document, FIT, nœud sous le doigt) se court-circuite
        // SANS empiler les retours (patron maison rectangleDuCaret —
        // detekt ReturnCount tué par RESTRUCTURE, pas de desserrage).
        val cible = cibleInspection(sx, sy) ?: return
        cible.session.setSelection(cible.debutLigne)
        cible.vue.scrollToLine(cible.ligne)
    }

    /**
     * La cible d'INSPECTION d'un appui long : la vue, sa session et la
     * ligne 0-indexée VISÉE (le nœud le plus PETIT contenant le doigt —
     * le plus profond —, jamais la colonne qui englobe tout) avec
     * l'offset de début de cette ligne.
     */
    private class CibleInspection(val vue: EditorView, val session: EditorSession, val ligne: Int, val debutLigne: Int)

    /** Calcule la cible d'inspection en UNE expression nullable. */
    private fun cibleInspection(sx: Float, sy: Float): CibleInspection? = vueEditeur
        ?.let { vue ->
            vue.session
                ?.let { session -> session.getDocument()?.let { Triple(vue, session, it) } }
        }?.let { (vue, session, document) ->
            ligneSourceSousLeDoigt(document, sx, sy)
                ?.let { ligne -> CibleInspection(vue, session, ligne, document.lineStart(ligne)) }
        }

    /**
     * La LIGNE source 0-indexée du nœud sous le doigt (null sans FIT,
     * sans nœud contenant le point) — le nœud de plus PETITE aire gagne
     * : c'est le plus PROCHE du doigt (l'enfant battant le conteneur).
     */
    private fun ligneSourceSousLeDoigt(document: jo.codeeditor.document.EditorDocument, sx: Float, sy: Float): Int? =
        bitmapDe(sx, sy)
            ?.let { point ->
                dernierValide
                    ?.rendus
                    ?.getOrNull(ongletAffiche)
                    ?.noeuds
                    ?.filter {
                        it.gauche <= point[0] && point[0] <= it.droite &&
                            it.haut <= point[1] && point[1] <= it.bas
                    }?.minByOrNull { (it.droite - it.gauche) * (it.bas - it.haut) }
                    ?.let { noeud -> (noeud.ligne - 1).coerceIn(0, document.lineCount() - 1) }
            }

    // ── Internes ────────────────────────────────────────────────────

    /**
     * C3 — branche l'HORLOGE INTERACTIVE sur l'onglet AFFICHÉ : la carte
     * est ouverte ET cet onglet porte un bitmap → le pompier du moteur
     * tourne sur SON identité (`uri#nom#index`) ; sinon (carte fermée,
     * onglet sans rendu, document absent) il s'arrête — les animations
     * ne vivent que sur ce qui est VISIBLE. Idempotent côté moteur (le
     * re-branchement d'une identité déjà pompée est un no-op).
     */
    private fun armerInteractif() {
        val actif = document
            ?.let { (uri, _) -> uri }
            ?.let { uri ->
                dernierValide?.rendus?.getOrNull(ongletAffiche)
                    ?.takeIf { it.bitmap != null }
                    ?.let { rendu -> RequeteRenduCompose.identiteDe(uri, rendu.nom, ongletAffiche) }
            }
        moteur.definirInteractif(if (carteOuverte) actif else null, ongletAffiche)
    }

    /**
     * C3 — rejoue un point ÉCRAN en PX BITMAP : l'inverse du FIT du
     * DERNIER dessin ([drawDropPreview] pose le repère), clampé aux
     * bornes du bitmap (un contact au bord reste honnête). `null` sans
     * dessin récent ou échelle dégénérée — le DOWN est décliné.
     */
    internal fun bitmapDe(sx: Float, sy: Float): FloatArray? = fitDernier
        ?.takeIf { it.echelle > 0f && it.largeur > 0 && it.hauteur > 0 }
        ?.let { fit ->
            floatArrayOf(
                ((sx - fit.gauche) / fit.echelle).coerceIn(0f, fit.largeur - 1f),
                ((sy - fit.haut) / fit.echelle).coerceIn(0f, fit.hauteur - 1f),
            )
        }

    /**
     * C3 — le FIT du dernier dessin de la carte : le REPÈRE partagé du
     * dessin ([drawDropPreview]) et des touches ([bitmapDe] l'inverse).
     */
    private class AjustementEcran(
        val echelle: Float,
        val gauche: Float,
        val haut: Float,
        val largeur: Int,
        val hauteur: Int,
    )

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

    /**
     * C2 — SURLIGNAGE du nœud du caret (miroir « Ligne n » de la planche
     * XML : le caret désigne, la carte montre) : le nœud de la ligne du
     * caret reçoit un contour 2 dp + remplissage translucide de la MÊME
     * teinte d'appui, par-dessus le bitmap FIT. Dessiné à CHAQUE frame —
     * le surlignage suit le caret en DIRECT.
     */
    internal fun dessinerSurlignage(
        canvas: Canvas,
        rendu: RenduPreviewCompose,
        echelle: Float,
        gauche: Float,
        haut: Float,
    ) {
        val rectangle = rectangleDuCaret(rendu, echelle, gauche, haut) ?: return
        val densite = contexte.resources.displayMetrics.density
        peintureRemplissage.color = COULEUR_SURLIGNAGE
        peintureRemplissage.alpha = ALPHA_SURLIGNAGE
        canvas.drawRect(rectangle, peintureRemplissage)
        peintureContour.color = COULEUR_SURLIGNAGE
        peintureContour.strokeWidth = EPAISSEUR_SURLIGNAGE_DP * densite
        canvas.drawRect(rectangle, peintureContour)
    }

    /**
     * Le rectangle CARTE du nœud du caret (bornes px bitmap passées à
     * l'échelle du FIT) — `null` sans vue, sans nœud de la ligne, ou
     * géométrie vide : RIEN (aucun faux rectangle sur un état périmé).
     * Le PREMIER nœud de la ligne l'emporte (l'appel le plus externe —
     * le construct de la ligne).
     */
    private fun rectangleDuCaret(rendu: RenduPreviewCompose, echelle: Float, gauche: Float, haut: Float): RectF? =
        ligneCaret()?.let { ligne ->
            rendu.noeuds.firstOrNull {
                it.ligne == ligne && it.droite > it.gauche && it.bas > it.haut
            }
        }?.let { noeud ->
            RectF(
                gauche + noeud.gauche * echelle,
                haut + noeud.haut * echelle,
                gauche + noeud.droite * echelle,
                haut + noeud.bas * echelle,
            )
        }

    /**
     * La ligne 1-indexée du caret de l'éditeur (0 → 1) — null sans vue
     * ni session (aucun surlignage possible, la carte reste au bitmap).
     */
    internal fun ligneCaret(): Int? {
        val session = vueEditeur?.session ?: return null
        return session.getDocument().lineForOffset(session.getSelection().start) + 1
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

    // ── C4 : la découverte parle l'URI LSP, les yeux suivent ───────

    /**
     * C4 — la découverte du document actif : par l'URI LSP ("file://" +
     * FUSE — la clé du didOpen, le correctif du bug v0.176.0) puis, à
     * vide, par l'uri d'onglet (repli best-effort : échantillons, tests
     * à la même clé). Le port double la CLÉ en tests — la politique de
     * repli vit dans son DÉFAUT (ci-dessus), jamais cachée au moteur.
     */
    private fun decouvrirPreviews(uriOnglet: String): List<PreviewKotlin> =
        decouvreur.decouvrir(uriLspDocument, uriOnglet)

    /**
     * C4 — tirage HORS rendu des yeux : la découverte directe (arbre
     * VIVANT de la session) pose les ancres sans attendre un bitmap
     * (changement d'onglet, frappe débouncée). Hors fil principal : la
     * marche est bornée (budget 500 nœuds) mais le fil d'UI ne la paie
     * pas.
     */
    private suspend fun rafraichirAncres() {
        val uri = document?.first ?: return
        val previews = withContext(dispatcheurs.default) {
            decouvrirPreviews(uri)
        }
        publierAncres(previews.mapIndexed { indice, preview -> preview.ligneAnnotation to indice })
    }

    /**
     * C4 — POSE les yeux : (ligne 1-indexée de l'annotation, indice de la
     * preview) → ancres 0-indexées triées par ligne, poussées à la vue
     * (B28 cel-ui : un œil par @Preview, VIDE = aucun — parité AS).
     */
    private fun publierAncres(paires: List<Pair<Int, Int>>) {
        val valides = paires.filter { it.first > 0 }.sortedBy { it.first }
        ancres = valides.map { it.first - 1 to it.second }
        vueEditeur?.setPreviewAnchors(ancres.map { it.first })
    }

    /**
     * C4 — écoute la SESSION de la vue (chaque onglet possède la sienne)
     * pour le rafraîchissement débouncé des yeux ; le réécoute d'un
     * changement d'onglet RETIRE l'écouteur de l'ancienne (jamais de
     * doublon).
     */
    private fun ecouterSessionVue() {
        val session = vueEditeur?.session ?: return
        val courante = sessionEcoutee
        if (courante === session) return
        courante?.removeOnTextEditListener(ecouteurTexte)
        sessionEcoutee = session
        session.addOnTextEditListener(ecouteurTexte)
    }

    private companion object {
        /** C4 — débounce du tirage d'ancres à la frappe (miroir du 400 ms moteur). */
        internal const val DEBOUNCE_ANCRES_MS: Long = 400L

        /** Longueur maximale d'un libellé d'onglet (l'en-tête partage la place avec le titre et ✕). */
        internal const val LONGUEUR_ONGLET: Int = 14

        /** Taille du message honnête (dp — coordonnées carte, densité appliquée au dessin). */
        internal const val TAILLE_MESSAGE_DP: Float = 11f

        /** Marge latérale du message honnête (dp). */
        internal const val MARGE_MESSAGE_DP: Float = 12f

        /** Gris d'appui M3 sur l'écran clair de la carte (0xFF6D6D72 — onSurfaceVariant). */
        internal const val COULEUR_MESSAGE: Int = 0xFF6D6D72.toInt()

        /**
         * Teinte d'appui du surlignage C2 : le `--s-pri` CLAIR du chrome
         * de la planche XML (`#43559E`) — la carte partage sa couleur
         * d'appui avec le chrome de l'aperçu (cohérence maison, une
         * seule teinte pour « vous êtes ici »).
         */
        internal const val COULEUR_SURLIGNAGE: Int = 0xFF43559E.toInt()

        /** Translucide du remplissage de surlignage (14 % — le bitmap reste lisible dessous). */
        internal const val ALPHA_SURLIGNAGE: Int = 36

        /** Épaisseur du contour de surlignage (dp — coordonnées carte). */
        internal const val EPAISSEUR_SURLIGNAGE_DP: Float = 2f

        /** C3 — pression du doigt transférée (normalisée — 1 est le défaut honnête). */
        internal const val PRESSION_DOIGT: Float = 1f
    }
}
