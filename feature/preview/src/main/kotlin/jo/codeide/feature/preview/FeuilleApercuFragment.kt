package jo.codeide.feature.preview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        // `conteneur_feuille`, enfant direct du CoordinatorLayout, posedé
        // par app:layout_behavior XML) — PAS sur la racine du fragment,
        // qui est enfant du conteneur (BottomSheetBehavior.from exige un
        // enfant direct du CoordinatorLayout, sinon plantage). Le parent
        // est déjà rattaché quand onViewCreated est appelé (Fragment 1.6+).
        comportementFeuille()?.peekHeight = StyleVolet.dp(requireContext(), PEEK_DP)
        monterVolets()
        // Un onglet touché DÉPLOYE la feuille (miroir maquette : « if
        // (!S.open) S.open = true »).
        liaison.ongletsFeuille.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(onglet: TabLayout.Tab) {
                    ongletActifInterne.value = onglet.position
                    montrerVolet(onglet.position)
                    deploierFeuille()
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
                            majBadgeProblemes(
                                ProblemesPreview.evaluer(
                                    etat.vues,
                                    viewModel.moteur.profil.densiteDpi,
                                ),
                            )
                        }
                        majFilAriane()
                    }
                }
            }
        }
    }

    /** La feuille déployée ↔ repliée (miroir `grab.onclick : S.open = !S.open`). */
    private fun basculerFeuille() {
        val comportement = comportementFeuille() ?: return
        if (comportement.state == BottomSheetBehavior.STATE_EXPANDED) {
            comportement.state = BottomSheetBehavior.STATE_COLLAPSED
        } else {
            comportement.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun deploierFeuille() {
        val comportement = comportementFeuille() ?: return
        if (comportement.state != BottomSheetBehavior.STATE_EXPANDED) {
            comportement.state = BottomSheetBehavior.STATE_EXPANDED
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
            onglet.text = getString(titre)
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

    /** Le badge compteur de l'onglet Problèmes (miroir `nProbs`, caché à zéro). */
    private fun majBadgeProblemes(problemes: List<ProblemesPreview.ProblemeApercu>) {
        val onglet = liaison.ongletsFeuille.getTabAt(ONGLET_PROBLEMES) ?: return
        val badge = onglet.orCreateBadge
        badge.isVisible = problemes.isNotEmpty()
        badge.number = problemes.size
    }

    public companion object {
        /** Les index d'onglet (miroir `S.tab` — consommés par les volets). */
        internal const val ONGLET_ARBRE: Int = 0
        internal const val ONGLET_ATTRIBUTS: Int = 1
        internal const val ONGLET_PROBLEMES: Int = 2
        internal const val ONGLET_SOURCE: Int = 3

        /** Hauteur repliée de la feuille : poignée + onglets (miroir 72 px). */
        private const val PEEK_DP: Int = 72

        /** Les tags des volets (retrouvés par [childFragmentManager]). */
        private const val TAG_ARBRE: String = "volet_arbre"
        private const val TAG_ATTRIBUTS: String = "volet_attributs"
        private const val TAG_PROBLEMES: String = "volet_problemes"
        private const val TAG_SOURCE: String = "volet_source"
    }
}
