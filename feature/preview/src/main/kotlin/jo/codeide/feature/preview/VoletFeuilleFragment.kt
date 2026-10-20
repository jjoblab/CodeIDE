package jo.codeide.feature.preview

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import jo.codeide.preview.xml.EtatPreviewXml
import kotlinx.coroutines.launch

/**
 * Hôte des volets de la feuille (lot UI-6, audit C5) : SEULE l'activité
 * possède le retour transitoire maison — la vue `toast_apercu` en
 * haut-centre (ADR 0134 D6) qui « remplace TOUT Toast système ». Un
 * volet qui annonce un refus ou un succès DÉLÈGUE ici ; hôte absent
 * (volet hors de l'aperçu) : sans effet — même garde que le contrat
 * hôte de l'éditeur (ADR 0126).
 */
internal interface HoteVoletsApercu {
    /** Retour transitoire maison (vue `toast_apercu`, fond inversé). */
    fun annoncer(resId: Int)
}

/**
 * BASE des quatre volets de la feuille (PE9a) : la collecte COMMUNE de
 * l'état partagé (rendu du moteur, sélection, onglet actif de la
 * feuille) et la porte « l'onglet ACTIF seul se re-marche » (miroir
 * `majVolets` de l'ancien [FeuilleApercuFragment] : les volets non
 * actifs attendent leur onglet — le passage RecyclerView/DiffUtil en
 * PE9a n'a pas changé cette économie). Chaque volet déclare SON index
 * d'onglet ([onglet]) et SON travail de mise en forme ([marcher]).
 *
 * Le flux `ongletActif` appartient à la feuille ([FeuilleApercuFragment.ongletActif])
 * : le volet le COLLECTE, il ne l'écrit jamais — la sélection d'onglet
 * reste la seule source de vérité du chrome.
 */
internal abstract class VoletFeuilleFragment : Fragment() {
    /** L'état partagé (activityViewModels — même état que la vue, miroir maquette). */
    protected val viewModel: PreviewViewModel by activityViewModels()

    /** L'index d'onglet porté par CE volet (miroir `S.tab`). */
    protected abstract val onglet: Int

    /** La mise en forme du volet (l'onglet est ACTIF à l'appel direct). */
    protected abstract fun marcher()

    /** Le dernier rendu valide (collecte commune, frais même inactif). */
    protected var renduCourant: EtatPreviewXml.RenduValide? = null
        private set

    /** La sélection partagée (collecte commune, fraîche même inactive). */
    protected var selectionCourante: Int = -1
        private set

    /** La feuille porteuse (null avant attach — jamais bloquant). */
    protected fun feuille(): FeuilleApercuFragment? = parentFragment as? FeuilleApercuFragment

    /** L'onglet de CE volet est-il actif ? */
    protected fun estActif(): Boolean = feuille()?.ongletActif?.value == onglet

    /**
     * Re-marche SEULEMENT si l'onglet du volet est actif (miroir
     * `majVolets` : `when (ongletActif) { … }`) — les collectes passent
     * TOUJOURS ici ; un volet inactif met seulement ses champs à frais.
     */
    protected fun reMarcher() {
        if (estActif()) marcher()
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // Patron repeatOnLifecycle (miroir la collecte de l'ancien
        // fragment) : rebranché à chaque vue recréée.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.moteur.etat.collect { etat ->
                        if (etat is EtatPreviewXml.RenduValide) renduCourant = etat
                        reMarcher()
                    }
                }
                launch {
                    viewModel.indiceSelectionne.collect { indice ->
                        selectionCourante = indice
                        reMarcher()
                    }
                }
                feuille()?.ongletActif?.let { flux ->
                    launch { flux.collect { reMarcher() } }
                }
            }
        }
    }
}
