package jo.codeide.feature.newproject

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.commit
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Aperçu en direct en **feuille basse** (téléphone — L5d, ADR 0155 §6) :
 * enveloppe [BottomSheetDialogFragment] embarquant [ApercuPlanFragment] —
 * le même rendu que le panneau latéral de la tablette. La fermeture par
 * glissement ou retour système referme l'aperçu côté état (le retour
 * système doit reprendre le parcours, jamais quitter le wizard).
 */
class ApercuFeuilleFragment : BottomSheetDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_apercu_fond, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.conteneur_apercu_feuille) == null) {
            childFragmentManager.commit(allowStateLoss = true) {
                replace(R.id.conteneur_apercu_feuille, ApercuPlanFragment())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Hauteur utile : la feuille occupe les 7/10 de l'écran — l'arbre
        // reste lisible sans recouvrir tout le parcours.
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * HAUTEUR_FRACTION).toInt(),
        )
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Glissement de fermeture : l'état suit (le ViewModel referme
        // l'aperçu ; l'hôte ne rouvre pas la feuille pour un état fermé).
        (parentFragment as? NewProjectFragment)?.notifierFermetureApercu()
    }

    private companion object {
        /** Fraction d'écran occupée par la feuille (hauteur utile). */
        const val HAUTEUR_FRACTION = 0.7
    }
}
