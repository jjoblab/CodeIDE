package jo.codeide.feature.editor.preview

import android.view.View
import jo.codeeditor.session.EditorSession
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.feature.editor.EtatEditor
import jo.codeide.preview.xml.ClassificateurPreview
import jo.codeide.preview.xml.EtatPreviewXml
import jo.codeide.preview.xml.GenrePreview
import jo.codeide.preview.xml.MoteurPreviewXml
import jo.codeide.preview.xml.VuePreviewXml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Contrôleur de la preview XML — PE1 (PREVIEW.md §3.2, câblage côté
 * éditeur) : il BRANCHE le moteur [MoteurPreviewXml] du module
 * `:preview:xml` sur l'espace de travail :
 * - l'onglet ACTIF (classification [ClassificateurPreview], réattachement
 *   de l'écoute des frappes à chaque changement d'onglet — même patron
 *   que [jo.codeide.feature.editor.CoordonnateurLsp]) ;
 * - le PROJET (résolution FUSE de `res/` — [ResoudreRepertoireProjet], le
 *   même résolveur que la session LSP et le terminal, ADR 0038) ;
 * - l'UI ([VuePreviewXml] : états du moteur → vue, bascule du panneau).
 *
 * La feature ne voit jamais LayoutLib : elle ne consomme que le module
 * `:preview:xml` (la dépendance au moteur de rendu y est confinée —
 * préparation PE2, processus `:preview`).
 *
 * Cycle de vie : vit dans l'activité (scope = lifecycleScope du hôte,
 * fourni au constructeur) ; [fermer] au onDestroy coupe moteur et écoute
 * d'onglet.
 */
class ControleurPreviewXml(
    private val vue: VuePreviewXml,
    dispatchers: DispatcherProvider,
    journal: AppLogger,
    private val scope: CoroutineScope,
    private val resolveurChemin: ResoudreRepertoireProjet,
) : AutoCloseable {
    private val moteur =
        MoteurPreviewXml(
            contexte = vue.context,
            dispatchers = dispatchers,
            journal = journal,
            scope = scope,
        )

    private val genreActifInterne = MutableStateFlow<GenrePreview?>(null)

    /** Genre de l'onglet actif (null = pas previewable) — l'activité règle le menu dessus. */
    val genreActif: StateFlow<GenrePreview?> = genreActifInterne.asStateFlow()

    /** Panneau visible (bascule par le menu, jamais d'auto-ouverture à PE1). */
    var visible: Boolean = false
        private set

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
        vue.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            val session = sessionEcoutee
            if (session != null) {
                moteur.soumettreImmediat { session.text }
            } else {
                vue.reinitialiser()
            }
        }
        return visible
    }

    /** Ferme moteur et écoutes (onDestroy de l'activité). Idempotent. */
    fun fermer() {
        if (ferme) return
        ferme = true
        detacherEcouteSession()
        collecteEtat?.cancel()
        suiviProjet?.cancel()
        moteur.close()
        vue.reinitialiser()
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
        if (session === sessionEcoutee) return
        detacherEcouteSession()
        if (session == null || genre == null) {
            if (visible) vue.reinitialiser()
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
            is EtatPreviewXml.RenduValide -> vue.afficherRendu(etat.bitmap)
            is EtatPreviewXml.ErreurRendu -> vue.afficherErreur(etat.message)
            EtatPreviewXml.EnEdition -> vue.afficherEnEdition()
            EtatPreviewXml.Inactif -> vue.reinitialiser()
        }
    }
}
