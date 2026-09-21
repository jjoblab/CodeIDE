package jo.codeide.core.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding

/**
 * Fragment de base de CodeIDE : création et libération du ViewBinding
 * (section 5.3 — le binding est nettoyé dans `onDestroyView`).
 *
 * Le fragment ne fait que rendre l'état et émettre des actions ; toute la
 * logique vit dans le ViewModel et les use cases du domaine.
 *
 * Utilisation :
 * ```kotlin
 * class HomeFragment : BaseFragment<FragmentHomeBinding>() {
 *     override fun createBinding(inflater: LayoutInflater, container: ViewGroup?) =
 *         FragmentHomeBinding.inflate(inflater, container, false)
 * }
 * ```
 *
 * NB : l'interface ViewBinding vit dans le paquet `androidx.viewbinding`,
 * fournie par la bibliothèque `androidx.databinding:viewbinding` (AGP 9
 * l'ajoute automatiquement aux modules qui activent viewBinding ; core:ui
 * la déclare explicitement car BaseFragment n'active pas la génération).
 *
 * @param VB type du ViewBinding de l'écran.
 */
public abstract class BaseFragment<VB : ViewBinding> : Fragment() {
    private var liaisonInterne: VB? = null

    /**
     * ViewBinding de l'écran, non nul uniquement entre `onCreateView` et
     * `onDestroyView` — jamais mémorisé au-delà du cycle de la vue.
     */
    protected val binding: VB
        get() =
            checkNotNull(liaisonInterne) {
                "Le ViewBinding n'est accessible qu'entre onCreateView et onDestroyView."
            }

    /**
     * Gonfle le ViewBinding de l'écran.
     *
     * @param inflater inflater de la vue.
     * @param container parent éventuel (peut être nul).
     * @param attachToRoot attacher au parent — toujours `false` pour un fragment.
     * @return l'instance de ViewBinding de l'écran.
     */
    protected abstract fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): VB

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?,
    ): View {
        val liaison = createBinding(inflater, container, false)
        liaisonInterne = liaison
        return liaison.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liaisonInterne = null
    }
}
