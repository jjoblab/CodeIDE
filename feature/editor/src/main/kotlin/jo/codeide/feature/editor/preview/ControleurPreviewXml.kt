package jo.codeide.feature.editor.preview

import android.view.View
import jo.codeeditor.document.Selection
import jo.codeeditor.session.EditorSession
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.feature.editor.EtatEditor
import jo.codeide.preview.xml.ClassificateurPreview
import jo.codeide.preview.xml.EtatPreviewXml
import jo.codeide.preview.xml.GenrePreview
import jo.codeide.preview.xml.MoteurPreviewXml
import jo.codeide.preview.xml.ProfilPeripherique
import jo.codeide.preview.xml.SelectionPreview
import jo.codeide.preview.xml.VuePreviewXml
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
    private val vue: VuePreviewXml,
    private val colonneApercu: View,
    dispatchers: DispatcherProvider,
    private val journal: AppLogger,
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
        vue.auTap = { x, y -> selectionnerVueSous(x, y) }
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
                vue.reinitialiser()
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
        val vues = vue.vuesRendues
        val offset = if (debut == fin) debut else debut
        val indice = SelectionPreview.indiceVuePourSpan(vues, offset)
        vue.surlignerVue(indice)
    }

    /** Ferme moteur et écoutes (onDestroy de l'activité). Idempotent. */
    fun fermer() {
        if (ferme) return
        ferme = true
        vue.auTap = null
        detacherEcouteSession()
        collecteEtat?.cancel()
        suiviProjet?.cancel()
        moteur.close()
        vue.reinitialiser()
    }

    override fun close() = fermer()

    /** Suit le projet courant : résout `res/` + thème activité via FUSE. */
    private fun suivreProjet(etat: EtatEditor) {
        val uriDocument =
            etat.projet?.location?.documentUri ?: run {
                // Plus de projet : le moteur revient aux ressources natives.
                if (uriProjetSuivi != null) {
                    uriProjetSuivi = null
                    moteur.definirDossierRes(null)
                    moteur.definirThemeName(null)
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
                // v0.133.1 — G2/G5 : résoudre le thème activité depuis
                // AndroidManifest.xml. Le thème est dans l'attribut
                // android:theme de <application> ou <activity>.
                val themeName = racine?.let { resoudreThemeActivite(it) }
                moteur.definirThemeName(themeName)
            }
    }

    /**
     * v0.133.1 — G2/G5 : Résout le thème activité depuis AndroidManifest.xml.
     *
     * Lit le fichier `app/src/main/AndroidManifest.xml` (ou
     * `src/main/AndroidManifest.xml`) et extrait l'attribut
     * `android:theme` de la balise `<application>`. Si non trouvé,
     * retourne null (pas de chrome système).
     */
    private fun resoudreThemeActivite(racineProjet: String): String? {
        val manifeste = trouverManifeste(racineProjet) ?: return null
        val contenu =
            try {
                java.io.File(manifeste).readText()
            } catch (_: Throwable) {
                return null
            }
        // Regex simple : android:theme="@style/..."
        val regex = Regex("""android:theme\s*=\s*"(@style/[^"]+|@android:style/[^"]+)"""")
        val match = regex.find(contenu) ?: return null
        return match.groupValues[1]
    }

    /** Cherche AndroidManifest.xml dans les chemins standards. */
    private fun trouverManifeste(racineProjet: String): String? {
        val base = racineProjet.trimEnd('/')
        val candidats =
            listOf(
                "$base/app/src/main/AndroidManifest.xml",
                "$base/src/main/AndroidManifest.xml",
                "$base/AndroidManifest.xml",
                "$base/../AndroidManifest.xml",
            )
        return candidats.firstOrNull { java.io.File(it).isFile }
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
            if (visible) vue.reinitialiser()
            return
        }
        // v0.133.3 — nettoyer le rendu précédent avant de brancher la
        // nouvelle session : évite que le rendu du layout précédent reste
        // affiché pendant le debounce du nouveau.
        if (visible) vue.reinitialiser()
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
    private fun selectionnerVueSous(
        x: Float,
        y: Float,
    ) {
        if (ferme || !visible) return
        val cible = SelectionPreview.vueSelectableEn(vue.vuesRendues, x, y) ?: return
        val session = sessionEcoutee ?: return
        val texte = session.text
        val debut = cible.debutElement.coerceIn(0, texte.length)
        val fin = cible.finElement.coerceIn(debut, texte.length)
        session.setSelection(Selection.range(debut, fin))
        // Le surlignage suit immédiatement (la sélection programmatique
        // déclenche onSelectionEditeur via l'écouteur de l'activité, mais
        // pas de dépendance à ce chemin : le retour visuel est direct).
        vue.surlignerVue(vue.vuesRendues.indexOf(cible))
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
            is EtatPreviewXml.RenduValide -> vue.afficherRendu(etat.bitmap, etat.vues)
            is EtatPreviewXml.ErreurRendu -> vue.afficherErreur(etat.message)
            EtatPreviewXml.EnEdition -> vue.afficherEnEdition()
            EtatPreviewXml.Inactif -> vue.reinitialiser()
        }
    }

    private companion object {
        private const val TAG_APERCU: String = "ControleurPreviewXml"
    }
}
