package jo.codeide.feature.preview

import jo.codeide.core.domain.AppLogger
import jo.codeide.preview.xml.EtatPreviewXml
import jo.codeide.preview.xml.MoteurPreviewXml
import jo.codeide.preview.xml.ProblemesPreview
import jo.codeide.preview.xml.ProfilPeripherique
import jo.codeide.preview.xml.SelectionPreview
import jo.codeide.preview.xml.VuePreviewXml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Contrôleur de l'écran d'aperçu XML — DÉPLACÉ de `:feature:editor`
 * (PE1 → PE5, câblage overlay) vers `:feature:preview` et ADAPTÉ au
 * PE6-0 (maquette `docs/mockups/apercu-xml.html` § « Activité
 * dédiée ») : l'écran EST l'aperçu — plus de bascule d'overlay, plus de
 * colonne de réglages à montrer/cacher, plus de session directe.
 *
 * Il reste la COUTURE entre le moteur [MoteurPreviewXml] (détenu par le
 * [PreviewViewModel] — rotation sûre) et la vue [VuePreviewXml] :
 * - l'état du moteur → la vue (rendu, erreur, édition, accueil) ;
 * - le tap confirmé sur le rendu → hit-test ([SelectionPreview],
 *   coordonnées bitmap) → remontée à l'ancêtre sélectionnable → le
 *   callback [auSelection] remonte l'INDICE de la vue : le ViewModel
 *   pose le SPAN dans la session (l'éditeur retrouvera la sélection à
 *   son retour) et partage l'état avec la FEUILLE (PE6d) ; le
 *   surlignage suit la seule source de vérité (StateFlow).
 *
 * PE6a (calques) : les rendus passent à la vue avec la DENSITÉ du
 * profil — le nom et les dimensions de la sélection s'affichent en dp.
 *
 * La feature ne voit jamais LayoutLib : elle ne consomme que le module
 * `:preview:xml` (la dépendance au moteur de rendu y est confinée).
 *
 * Cycle de vie : vit dans l'activité (scope = lifecycleScope du hôte) ;
 * [fermer] au onDestroy coupe la collecte d'état — le MOTEUR, lui, vit
 * dans le ViewModel ([PreviewViewModel.onCleared]).
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde de
 * [selectionnerVueSous] (fermé, sans cible) est une issue DISTINCTE du
 * contrat P6 « ne devine jamais ».
 */
@Suppress("ReturnCount")
public class ControleurPreviewXml(
    private val vue: VuePreviewXml,
    private val moteur: MoteurPreviewXml,
    private val journal: AppLogger,
    scope: CoroutineScope,
    private val auSelection: (indice: Int) -> Unit,
) : AutoCloseable {
    private var collecteEtat: Job? = null
    private var ferme = false

    init {
        collecteEtat =
            scope.launch {
                moteur.etat.collect { etat -> appliquerEtat(etat) }
            }
        // P6 : tap confirmé sur le rendu → hit-test → sélection session.
        vue.auTap = { x, y -> selectionnerVueSous(x, y) }
    }

    /**
     * Profil device courant (PE3 — libellé de la puce de réglages).
     */
    public val profil: ProfilPeripherique get() = moteur.profil

    /** Régime nuit courant (PE3 — libellé de la puce de réglages). */
    public val nuit: Boolean get() = moteur.nuit

    /** Langue courante de l'aperçu (PE5a — null = défaut système). */
    public val langue: String? get() = moteur.langue

    /**
     * Profil device EXPLICITE (PE6b — feuille de choix de la maquette :
     * l'utilisateur pique un appareil dans le catalogue, plus de cycle
     * à l'aveugle).
     */
    public fun definirProfil(profil: ProfilPeripherique) {
        moteur.definirProfil(profil)
    }

    /** Basculer le régime jour/nuit (bouton de réglages). */
    public fun basculerNuit() {
        moteur.definirNuit(!moteur.nuit)
    }

    /** Basculer la langue de l'aperçu (PE5a — null → "fr" → "en" → null). */
    public fun basculerLangue() {
        val courante = moteur.langue
        val suivante =
            when (courante) {
                null -> "fr"
                "fr" -> "en"
                else -> null
            }
        moteur.definirLangue(suivante)
    }

    /** Coupe la collecte d'état (onDestroy de l'activité). Idempotent. */
    public fun fermer() {
        if (ferme) return
        ferme = true
        vue.auTap = null
        collecteEtat?.cancel()
        collecteEtat = null
    }

    override fun close() = fermer()

    /**
     * Tap sur le rendu (P6) : hit-test sur les vues du dernier état,
     * remonte à l'ancêtre sélectionnable, remonte l'INDICE via
     * [auSelection] (le ViewModel pose le span dans la session et
     * partage la sélection avec la feuille) + surlignage IMMÉDIAT
     * (retour visuel direct). Le hit-test ne DEVINE jamais :
     * aucune vue spanée → rien (drawables, values, includes non
     * résolus).
     *
     * v0.134.2 — PAS de compensation d'offset chrome : les bounds
     * InfoVueRendu sont ABSOLUES dans le bitmap depuis le pipeline, le
     * tap arrive déjà en coordonnées bitmap.
     */
    private fun selectionnerVueSous(
        x: Float,
        y: Float,
    ) {
        if (ferme) return
        val cible = SelectionPreview.vueSelectableEn(vue.vuesRendues, x, y) ?: return
        val indice = vue.vuesRendues.indexOf(cible)
        auSelection(indice)
        // Le surlignage suit immédiatement (retour visuel direct — la
        // sélection de session ne fait pas rebondir l'aperçu).
        vue.surlignerVue(indice)
    }

    /**
     * Applique un état du moteur à la vue (collecte sur le scope du hôte = thread UI).
     */
    private fun appliquerEtat(etat: EtatPreviewXml) {
        when (etat) {
            is EtatPreviewXml.RenduValide -> {
                // PE6a : la vue reçoit la densité du PROFIL (px → dp pour
                // les libellés des calques). PE10a : durée et vues partent
                // à la PASTILLE-VUE du chrome (l'activité collecte l'état).
                vue.afficherRendu(
                    bitmap = etat.bitmap,
                    vuesRendues = etat.vues,
                    densiteDpiProfil = moteur.profil.densiteDpi,
                )
                // PE6c : règles de problèmes (fonctions pures) évaluées
                // à chaque rendu valide — la vue en tire ses pastilles.
                vue.afficherProblemes(ProblemesPreview.evaluer(etat.vues, moteur.profil.densiteDpi))
            }

            is EtatPreviewXml.ErreurRendu -> {
                vue.afficherErreur(etat.message)
            }

            EtatPreviewXml.EnEdition -> {
                vue.afficherEnEdition()
            }

            EtatPreviewXml.Inactif -> {
                journal.d(TAG_APERCU) { "état inactif — panneau réinitialisé" }
                vue.reinitialiser()
            }
        }
    }

    private companion object {
        private const val TAG_APERCU: String = "ControleurPreviewXml"
    }
}
