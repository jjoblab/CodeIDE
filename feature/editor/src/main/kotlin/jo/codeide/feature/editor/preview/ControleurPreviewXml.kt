package jo.codeide.feature.editor.preview

import android.view.View
import android.view.ViewGroup
import jo.codeeditor.document.Selection
import jo.codeeditor.session.EditorSession
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.feature.editor.EtatEditor
import jo.codeide.preview.xml.ClassificateurPreview
import jo.codeide.preview.xml.EtatPreviewXml
import jo.codeide.preview.xml.GenrePreview
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.MoteurPreviewXml
import jo.codeide.preview.xml.ProfilPeripherique
import jo.codeide.preview.xml.SelectionPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Contrôleur de la preview XML — PE1 (câblage) → PE3 (PREVIEW.md §3.4 :
 * sélection bidirectionnelle P6, profils device, jour/nuit) : il BRANCHE
 * le moteur [MoteurPreviewXml] du module `:preview:xml` sur l'espace de
 * travail :
 * - l'onglet ACTIF (classification [ClassificateurPreview], réattachement
 *   de l'écoute des frappes à chaque changement d'onglet, GENRE poussé au
 *   moteur — le pipeline dispatche layout/drawable/values) ;
 * - le PROJET (résolution FUSE de `res/` — [ResoudreRepertoireProjet], le
 *   même résolveur que la session LSP et le terminal, ADR 0038) ;
 * - l'UI ([VuePreviewXml] : états du moteur → vue, bascule du panneau,
 *   barre de réglages profil/nuit) ;
 * - la SÉLECTION P6 (bidirectionnelle) :
 *   - tap sur le rendu → hit-test ([SelectionPreview], coordonnées
 *     bitmap) → **sélection du SPAN source dans l'éditeur**
 *     ([EditorSession.setSelection] — remonte à l'ancêtre sélectionnable,
 *     comme Android Studio) ;
 *   - sélection de l'éditeur → [onSelectionEditeur] → **surlignage de la
 *     vue correspondante** ([VuePreviewXml.surlignerVue] — l'élément le
 *     plus interne contenant le curseur).
 *
 * La feature ne voit jamais LayoutLib : elle ne consomme que le module
 * `:preview:xml` (la dépendance au moteur de rendu y est confinée).
 *
 * Cycle de vie : vit dans l'activité (scope = lifecycleScope du hôte,
 * fourni au constructeur) ; [fermer] au onDestroy coupe moteur et écoute
 * d'onglet.
 *
 * Exemptions detekt ciblées (règle 16) : TooManyFunctions — contrôleur
 * de COUTURE (brancher, basculer, régler, sélectionner, fermer : chaque
 * face du moteur a SA méthode publique, précédent CoordonnateurLsp) ;
 * ReturnCount — chaque garde de [selectionnerVueSous] (fermé, sans
 * cible, sans session) est une issue DISTINCTE du contrat P6 « ne
 * devine jamais ».
 */
@Suppress("TooManyFunctions", "ReturnCount")
class ControleurPreviewXml(
    private val conteneurRendu: ViewGroup,
    private val colonneApercu: View,
    dispatchers: DispatcherProvider,
    private val journal: AppLogger,
    private val scope: CoroutineScope,
    private val resolveurChemin: ResoudreRepertoireProjet,
) : AutoCloseable {
    private val moteur =
        MoteurPreviewXml(
            contexte = conteneurRendu.context,
            dispatchers = dispatchers,
            journal = journal,
            scope = scope,
        )

    private val genreActifInterne = MutableStateFlow<GenrePreview?>(null)

    /** Dernières vues rendues (pour P6 hit-test — temporairement inutilisé). */
    private val dernieresVues: List<InfoVueRendu> get() = emptyList()

    /** Genre de l'onglet actif (null = pas previewable) — l'activité règle le menu dessus. */
    val genreActif: StateFlow<GenrePreview?> = genreActifInterne.asStateFlow()

    /** Panneau visible (bascule par le menu, jamais d'auto-ouverture). */
    var visible: Boolean = false
        private set

    /** Profil device courant (PE3 — libellé du bouton de l'activité). */
    val profil: ProfilPeripherique get() = moteur.profil

    /** Régime nuit courant (PE3 — icône du bouton de l'activité). */
    val nuit: Boolean get() = moteur.nuit

    private var collecteEtat: Job? = null
    private var suiviProjet: Job? = null
    private var uriProjetSuivi: String? = null
    private var sessionEcoutee: EditorSession? = null
    private var ecouteurSession: EditorSession.OnTextEditListener? = null
    private var ferme = false

    init {
        collecteEtat =
            scope.launch {
                moteur.etat.collect { etat -> appliquerEtat(etat) }
            }
        // P6 : tap confirmé sur le rendu → hit-test → sélection éditeur.
        // P6 : tap sur le rendu — désactivé temporairement (sera recâblé
        // avec un overlay une fois le rendu direct view stabilisé).
    }

    /**
     * À chaque état de l'espace : rebranche l'écoute de l'onglet actif et
     * suit le projet (résolution FUSE de `res/`, une fois par projet).
     */
    fun onEtat(
        etat: EtatEditor,
        sessionActive: EditorSession?,
    ) {
        if (ferme) return
        suivreProjet(etat)
        rebrancherOnglet(etat, sessionActive)
    }

    /** Bascule le panneau (menu « Aperçu XML ») ; ouvrir rend immédiatement. */
    fun bascule(): Boolean {
        visible = !visible
        // La COLONNE (barre de réglages + rendu) bascule — pas le seul
        // rendu : PE3 a ajouté la barre profil/nuit au-dessus.
        colonneApercu.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            val session = sessionEcoutee
            if (session != null) {
                moteur.soumettreImmediat { session.text }
            } else {
                journal.w(TAG_APERCU) {
                    "bascule ouverte mais sessionEcoutee=null — " +
                        "session pas prête ou onglet non previewable " +
                        "(genre=${genreActifInterne.value})"
                }
                conteneurRendu.removeAllViews()
            }
        }
        return visible
    }

    /**
     * Profil device suivant (PE3 — bouton de l'activité, cycle du
     * catalogue) : le moteur relance un rendu immédiat.
     */
    fun profilSuivant() {
        val catalogue = ProfilPeripherique.CATALOGUE
        val courant = moteur.profil
        val indice = catalogue.indexOfFirst { it == courant }
        moteur.definirProfil(catalogue[(indice + 1).mod(catalogue.size)])
    }

    /** Basculer le régime jour/nuit (PE3 — bouton de l'activité). */
    fun basculerNuit() {
        moteur.definirNuit(!moteur.nuit)
    }

    /** Basculer la langue de l'aperçu (PE5a — null → "fr" → "en" → null). */
    fun basculerLangue() {
        val courante = moteur.langue
        val suivante =
            when (courante) {
                null -> "fr"
                "fr" -> "en"
                else -> null
            }
        moteur.definirLangue(suivante)
    }

    /** Langue courante de l'aperçu (PE5a — null = défaut système). */
    val langue: String? get() = moteur.langue

    /**
     * Sélection de l'ÉDITEUR → surlignage preview (P6, direction inverse) :
     * l'élément le plus INTERNE contenant l'offset du curseur. Appelé par
     * l'activité sur `OnSelectionChangedListener` (thread UI).
     */
    fun onSelectionEditeur(
        debut: Int,
        fin: Int,
    ) {
        if (ferme || !visible) return
        val vues = dernieresVues
        val offset = if (debut == fin) debut else debut
        val indice = SelectionPreview.indiceVuePourSpan(vues, offset)
        // P6 surlignage — désactivé temporairement
    }

    /** Ferme moteur et écoutes (onDestroy de l'activité). Idempotent. */
    fun fermer() {
        if (ferme) return
        ferme = true
        // P6 tap cleanup — désactivé temporairement
        detacherEcouteSession()
        collecteEtat?.cancel()
        suiviProjet?.cancel()
        moteur.close()
        conteneurRendu.removeAllViews()
    }

    override fun close() = fermer()

    /** Suit le projet courant : résout `res/` via FUSE, une fois par projet. */
    private fun suivreProjet(etat: EtatEditor) {
        val uriDocument =
            etat.projet?.location?.documentUri ?: run {
                // Plus de projet : le moteur revient aux ressources natives.
                if (uriProjetSuivi != null) {
                    uriProjetSuivi = null
                    moteur.definirDossierRes(null)
                }
                return
            }
        if (uriDocument == uriProjetSuivi) return
        uriProjetSuivi = uriDocument
        suiviProjet?.cancel()
        suiviProjet =
            scope.launch {
                val racine = resolveurChemin.resoudreEntree(uriDocument)
                val cheminRes = racine?.let { "${it.trimEnd('/')}/res" }
                moteur.definirDossierRes(cheminRes)
            }
    }

    /** Rebranche l'écoute de frappe sur l'onglet actif, et le classifie. */
    private fun rebrancherOnglet(
        etat: EtatEditor,
        session: EditorSession?,
    ) {
        val onglet = etat.onglets.getOrNull(etat.indexOngletActif)
        val genre =
            if (onglet == null || session == null) {
                null
            } else {
                ClassificateurPreview.genreDe(onglet.cheminRelatif, session.text)
            }
        genreActifInterne.value = genre
        // PE3 : le genre pilote le pipeline (layout / drawable / values) ;
        // un changement relance un rendu immédiat côté moteur.
        moteur.definirGenre(genre)
        if (session === sessionEcoutee) return
        detacherEcouteSession()
        if (session == null || genre == null) {
            if (visible) conteneurRendu.removeAllViews()
            return
        }
        val sessionCapturee = session
        val ecouteur =
            EditorSession.OnTextEditListener { _, _, _ ->
                // Fournisseur paresseux : le texte n'est tiré qu'après debounce.
                moteur.soumettre { sessionCapturee.text }
            }
        sessionCapturee.addOnTextEditListener(ecouteur)
        sessionEcoutee = sessionCapturee
        ecouteurSession = ecouteur
        if (visible) moteur.soumettreImmediat { sessionCapturee.text }
    }

    /**
     * Tap sur le rendu (P6) : hit-test sur les vues du dernier état,
     * remonte à l'ancêtre sélectionnable, sélectionne le SPAN source dans
     * l'éditeur. Le hit-test ne DEVINE jamais : aucune vue spanée →
     * rien (drawables, values, includes non résolus).
     */
    @Suppress("UnusedPrivateMember")
    private fun selectionnerVueSous(
        x: Float,
        y: Float,
    ) {
        if (ferme || !visible) return
        val cible = SelectionPreview.vueSelectableEn(dernieresVues, x, y) ?: return
        val session = sessionEcoutee ?: return
        val texte = session.text
        val debut = cible.debutElement.coerceIn(0, texte.length)
        val fin = cible.finElement.coerceIn(debut, texte.length)
        session.setSelection(Selection.range(debut, fin))
        // Le surlignage suit immédiatement (la sélection programmatique
        // déclenche onSelectionEditeur via l'écouteur de l'activité, mais
        // pas de dépendance à ce chemin : le retour visuel est direct).
        // P6 surlignage — désactivé temporairement
    }

    /** Détache l'écoute de la session précédente (bascule d'onglet). */
    private fun detacherEcouteSession() {
        val session = sessionEcoutee
        val ecouteur = ecouteurSession
        if (session != null && ecouteur != null) {
            session.removeOnTextEditListener(ecouteur)
        }
        sessionEcoutee = null
        ecouteurSession = null
    }

    /** Applique un état du moteur à la vue (collecte sur le scope du hôte = thread UI). */
    private fun appliquerEtat(etat: EtatPreviewXml) {
        when (etat) {
            is EtatPreviewXml.RenduValide -> {
                // Direct view rendering : on ajoute la vue au conteneur.
                conteneurRendu.removeAllViews()
                val vueRendue = etat.vue
                if (vueRendue != null) {
                    // La vue est déjà mesurée + layoutée par LayoutLib.
                    // On l'ajoute directement — elle se dessine elle-même.
                    val parent = vueRendue.parent
                    if (parent is ViewGroup) parent.removeView(vueRendue)
                    conteneurRendu.addView(vueRendue)
                }
            }

            is EtatPreviewXml.ErreurRendu -> {
                // Garder le dernier rendu — l'erreur est journalisée.
            }

            EtatPreviewXml.EnEdition -> {
                // Garder le dernier rendu — frappe en cours.
            }

            EtatPreviewXml.Inactif -> {
                conteneurRendu.removeAllViews()
            }
        }
    }

    private companion object {
        private const val TAG_APERCU: String = "ControleurPreviewXml"
    }
}
