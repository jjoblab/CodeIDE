package jo.codeide.feature.newproject

import androidx.fragment.app.viewModels
import androidx.viewbinding.ViewBinding
import jo.codeide.core.ui.BaseFragment

/**
 * Fragment d'une étape du wizard (section 12.2) : **aucun état propre** —
 * le [WizardViewModel] de l'hôte est la source de vérité unique, partagé
 * par tous les fragments d'étapes, et le ViewBinding suit le cycle de la
 * vue ([BaseFragment]).
 *
 * @param VB type du ViewBinding de l'étape.
 */
abstract class EtapeFragment<VB : ViewBinding> : BaseFragment<VB>() {
    /** ViewModel partagé, scopé à l'hôte [NewProjectFragment]. */
    protected val wizard: WizardViewModel by viewModels({ requireParentFragment() })
}
