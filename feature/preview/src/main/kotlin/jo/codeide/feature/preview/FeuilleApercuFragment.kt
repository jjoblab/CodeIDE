package jo.codeide.feature.preview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.preview.databinding.FragmentFeuilleApercuBinding
import jo.codeide.preview.xml.EtatPreviewXml
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.ProblemesPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * FEUILLE inférieure de l'écran d'aperçu XML (PE6d, maquette
 * `docs/mockups/apercu-xml.html` § « Feuille inférieure ») — LE CHROME
 * seul depuis PE9a : la poignée de tirage, le fil d'Ariane, les QUATRE
 * onglets, le badge compteur des problèmes et le conteneur des volets.
 * Le CONTENU de chaque onglet vit dans SON fragment
 * ([VoletArbreFragment], [VoletAttributsFragment],
 * [VoletProblemesFragment], [VoletSourceFragment] — ajoutés au
 * conteneur puis montrés/cachés, jamais recréés), tous alimentés PAR LE
 * MÊME ÉTAT que la vue ([PreviewViewModel] partagé, `activityViewModels`)
 * et alignés DEUX VOIES sur la sélection.
 *
 * L'onglet ACTIF est un flux ([ongletActif]) : les volets le collectent
 * et « l'onglet actif seul se re-marche » (miroir `majVolets` de l'ancien
 * fragment monolithique, conservé tel quel). Le badge compteur de
 * l'onglet Problèmes est tenu ICI (miroir `nProbs`, caché à zéro) —
 * évalué à chaque rendu valide, il est TOUJOURS frais.
 */
@AndroidEntryPoint
public class FeuilleApercuFragment : Fragment() {
    private val viewModel: PreviewViewModel by activityViewModels()

    private lateinit var liaison: FragmentFeuilleApercuBinding

    /** L'onglet affiché, en FLUX (miroir `S.tab`) — source des volets. */
    private val ongletActifInterne = MutableStateFlow(ONGLET_ARBRE)

    /** La lecture seule de l'onglet actif (collectée par les volets). */
    public val ongletActif: StateFlow<Int> = ongletActifInterne.asStateFlow()

    /** Le dernier rendu valide (le fil d'Ariane s'en alimente). */
    private var renduCourant: EtatPreviewXml.RenduValide? = null

    /** La sélection partagée (indice dans l'arbre, -1 aucune). */
    private var selectionCourante: Int = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        conteneur: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaison = FragmentFeuilleApercuBinding.inflate(inflater, conteneur, false)
        construireOnglets()
        liaison.grabFeuille.setOnClickListener { basculerFeuille() }
        return liaison.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // Le comportement vit sur le CONTENEUR de l'activité (FrameLayout
        // `conteneur_feuille`, enfant direct du CoordinatorLayout, posé
        // par app:layout_behavior XML) — PAS sur la racine du fragment,
        // qui est enfant du conteneur (BottomSheetBehavior.from exige un
        // enfant direct du CoordinatorLayout, sinon plantage). Le parent
        // est déjà rattaché quand onViewCreated est appelé (Fragment 1.6+).
        // v0.166.5 : la GÉOMÉTRIE (peek couvrant la barre de navigation,
        // volets REMPLISSANT l'espace sous l'en-tête moins le clavier)
        // appartient à l'ACTIVITÉ ([PreviewActivity.majGeometrieFeuille],
        // [GeometrieFeuilleApercu]) — elle seule connaît l'inset de
        // navigation, le CLAVIER et la hauteur de l'en-tête ; le fragment
        // garde le COMPORTEMENT (états, onglets, volets).
        monterVolets()
        // Un onglet touché DÉPLOYE la feuille (miroir maquette : « if
        // (!S.open) S.open = true »).
        liaison.ongletsFeuille.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(onglet: TabLayout.Tab) {
                    ongletActifInterne.value = onglet.position
                    montrerVolet(onglet.position)
                    basculerFeuille(deplierSeulement = true)
                }

                override fun onTabUnselected(onglet: TabLayout.Tab) = Unit

                override fun onTabReselected(onglet: TabLayout.Tab) = Unit
            },
        )
        // La collecte vit sur le viewLifecycleOwner (la feuille se
        // rebranche à chaque vue recréée — patron repeatOnLifecycle).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.indiceSelectionne.collect { indice ->
                        selectionCourante = indice
                        majFilAriane()
                    }
                }
                launch {
                    viewModel.moteur.etat.collect { etat ->
                        if (etat is EtatPreviewXml.RenduValide) {
                            renduCourant = etat
                        }
                        // PE12f — le badge suit AUSSI les problèmes de
                        // famille lus dans le TEXTE : ils changent à
                        // chaque état (le texte a bougé), rendu valide ou
                        // pas — la boucle § 12 se compte alors que le
                        // rendu vient d'échouer.
                        majBadgeProblemes()
                        majFilAriane()
                    }
                }
            }
        }
    }

    /**
     * La feuille : CYCLE des TROIS niveaux (v0.173.0 — retour propriétaire
     * « deux niveaux pour expand à moitié puis total sous le appbar ») :
     * replié → MOITIÉ → TOTAL → replié — ou SE DÉPLOIE au premier niveau
     * sans toucher une feuille déjà ouverte ([deplierSeulement] : sélection
     * d'onglet, « Ligne n » — la MOITIÉ garde le rendu visible pendant
     * l'édition des attributs). La formule est PURE
     * ([GeometrieFeuilleApercu.etatSuivantExpansion], testée à part).
     */
    private fun basculerFeuille(deplierSeulement: Boolean = false) {
        val comportement = comportementFeuille() ?: return
        val cible =
            GeometrieFeuilleApercu.etatSuivantExpansion(
                etatCourant = comportement.state,
                deplierSeulement = deplierSeulement,
                etatReplie = BottomSheetBehavior.STATE_COLLAPSED,
                etatMoitie = BottomSheetBehavior.STATE_HALF_EXPANDED,
                etatTotal = BottomSheetBehavior.STATE_EXPANDED,
            )
        if (cible != comportement.state) {
            comportement.state = cible
        }
    }

    /**
     * Le comportement de feuille, résolu sur le CONTENEUR parent (l'enfant
     * direct du CoordinatorLayout qui porte `app:layout_behavior`), pas sur
     * la racine du fragment : [BottomSheetBehavior.from] exige un enfant
     * direct, la racine du fragment en est séparée par le conteneur.
     * Renvoie null si la hiérarchie n'est pas encore prête (jamais bloquant,
     * la feuille reste alors à son état XML par défaut).
     */
    private fun comportementFeuille(): BottomSheetBehavior<*>? {
        val conteneur = liaison.root.parent as? ViewGroup ?: return null
        return try {
            BottomSheetBehavior.from(conteneur)
        } catch (_: IllegalArgumentException) {
            // Conteneur sans comportement (hiérarchie inattendue) : ignorer.
            null
        }
    }

    /** Les quatre onglets (miroir `.tabs` de la maquette). */
    private fun construireOnglets() {
        val titres =
            listOf(
                R.string.preview_feuille_onglet_arbre,
                R.string.preview_feuille_onglet_attributs,
                R.string.preview_feuille_onglet_problemes,
                R.string.preview_feuille_onglet_source,
            )
        titres.forEachIndexed { indice, titre ->
            val onglet = liaison.ongletsFeuille.newTab()
            if (indice == ONGLET_PROBLEMES) {
                // Le badge de compte vit DANS l'onglet (miroir `.tabs .n`) :
                // le customView REMPLACE le titre — libellé + pastille ensemble.
                onglet.customView =
                    layoutInflater.inflate(R.layout.onglet_problemes, liaison.ongletsFeuille, false)
            } else {
                onglet.text = getString(titre)
            }
            liaison.ongletsFeuille.addTab(onglet, indice, indice == ONGLET_ARBRE)
        }
    }

    /**
     * Monte les QUATRE volets dans le conteneur (PE9a) : ajoutés UNE fois
     * (les fragments enfant survivent à la recréation de la vue du parent —
     * on les RETROUVE par tag, jamais re-ajoutés), les trois non-initiaux
     * cachés d'emblée. Montrer/cacher conserve l'état de chaque volet
     * (miroir `.pane` : un seul visible).
     */
    private fun monterVolets() {
        val gestion = childFragmentManager
        if (gestion.findFragmentByTag(TAG_ARBRE) != null) return
        gestion
            .beginTransaction()
            .add(R.id.conteneur_volets, VoletArbreFragment(), TAG_ARBRE)
            .add(R.id.conteneur_volets, VoletAttributsFragment(), TAG_ATTRIBUTS)
            .add(R.id.conteneur_volets, VoletProblemesFragment(), TAG_PROBLEMES)
            .add(R.id.conteneur_volets, VoletSourceFragment(), TAG_SOURCE)
            .commitNow()
        listOf(TAG_ATTRIBUTS, TAG_PROBLEMES, TAG_SOURCE).forEach { tag ->
            gestion.findFragmentByTag(tag)?.let { fragment ->
                gestion.beginTransaction().hide(fragment).commitNow()
            }
        }
    }

    /**
     * Ouvre l'onglet SOURCE amené à une LIGNE (PE11i — bouton « Ligne n »
     * du bandeau d'erreur, ADR 0134 D6) : sélection de l'onglet (le
     * tiroir suit par son écouteur), déploiement de la feuille, puis
     * défilement du volet source sur la ligne.
     */
    public fun ouvrirSourceALigne(ligne: Int) {
        liaison.ongletsFeuille.getTabAt(ONGLET_SOURCE)?.select()
        basculerFeuille(deplierSeulement = true)
        (childFragmentManager.findFragmentByTag(TAG_SOURCE) as? VoletSourceFragment)?.allerALigne(ligne)
    }

    /** Le volet ACTIF seul visible (miroir `montrerVolet`, une transaction). */
    private fun montrerVolet(onglet: Int) {
        val gestion = childFragmentManager
        val transaction = gestion.beginTransaction()
        listOf(TAG_ARBRE, TAG_ATTRIBUTS, TAG_PROBLEMES, TAG_SOURCE).forEachIndexed { indice, tag ->
            gestion.findFragmentByTag(tag)?.let { fragment ->
                if (indice == onglet) transaction.show(fragment) else transaction.hide(fragment)
            }
        }
        transaction.commitNow()
    }

    /** Le fil d'Ariane de la poignée (miroir `renderCrumb`). */
    private fun majFilAriane() {
        val vues = renduCourant?.vues
        val vue = vues?.getOrNull(selectionCourante)
        liaison.filAriane.text =
            when {
                vue == null -> {
                    getString(R.string.preview_feuille_sans_selection)
                }

                vue.indiceParent < 0 || selectionCourante == 0 -> {
                    LibellesVolet.libelleVue(requireContext(), vue)
                }

                else -> {
                    val parent = vues.getOrNull(vue.indiceParent)
                    getString(
                        R.string.preview_feuille_croisement,
                        parent?.let { LibellesVolet.libelleVue(requireContext(), it) } ?: "",
                        LibellesVolet.libelleVue(requireContext(), vue),
                    )
                }
            }
    }

    /** Le badge compteur de l'onglet Problèmes (miroir `nProbs`, caché à zéro) :
     * la PASTILLE ambre de la maquette (`.tabs .n`) — BadgeDrawable ne porte
     * pas la police mono (PREVIEW_UI.md § 8). PE12f : compte AUSSI les cinq
     * genres de FAMILLE lus dans le texte (spec § 9) — comme le `nProbs`
     * de la maquette, qui compte TOUT `S.probs`. */
    private fun majBadgeProblemes() {
        val onglet = liaison.ongletsFeuille.getTabAt(ONGLET_PROBLEMES) ?: return
        val pastille = onglet.customView?.findViewById<TextView>(R.id.pastille_onglet) ?: return
        val vues = renduCourant?.vues.orEmpty()
        val problemes =
            ProblemesPreview.evaluer(vues, viewModel.moteur.profil.densiteDpi).size +
                viewModel.problemesFamilles().size
        pastille.isVisible = problemes > 0
        // Le compteur est une suite de CHIFFRES neutres (miroir BadgeDrawable
        // .number) — Locale.ROOT explicite pour l'outillage i18n.
        pastille.text = String.format(Locale.ROOT, "%d", problemes)
    }

    public companion object {
        /** Les index d'onglet (miroir `S.tab` — consommés par les volets). */
        internal const val ONGLET_ARBRE: Int = 0
        internal const val ONGLET_ATTRIBUTS: Int = 1
        internal const val ONGLET_PROBLEMES: Int = 2
        internal const val ONGLET_SOURCE: Int = 3

        /** Les tags des volets (retrouvés par [childFragmentManager]). */
        private const val TAG_ARBRE: String = "volet_arbre"
        private const val TAG_ATTRIBUTS: String = "volet_attributs"
        private const val TAG_PROBLEMES: String = "volet_problemes"
        private const val TAG_SOURCE: String = "volet_source"
    }
}
