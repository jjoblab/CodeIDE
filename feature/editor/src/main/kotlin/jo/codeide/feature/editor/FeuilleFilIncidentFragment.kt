package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.feature.editor.databinding.FeuilleFilIncidentBinding
import javax.inject.Inject

/**
 * Le FIL DE L'INCIDENT (L5d — spec SORTIES § 5.7) : depuis un build
 * échoué, UN SEUL geste ouvre les destinations qui comptent — les
 * sorties du build, les problèmes, les diagnostics (journaux du serveur
 * et rapports de plantage) et le Logcat de la session. « Un seul geste,
 * pas quatre onglets à fouiller » : la feuille vit AU-DESSUS du bandeau
 * d'échec du panneau compact (son point d'entrée), elle ne remplace
 * aucune surface.
 *
 * Chaque destination est une ligne honnête : elle fait ce qu'elle dit
 * (les onglets du panneau passent par l'action [ActionEditor.SelectionnerOngletPanneau]
 * — la réconciliation de l'activité ouvre le panneau ; les diagnostics
 * passent par le navigateur applicatif, ADR 0048). La feuille se referme
 * au choix — le geste reste unique, jamais une pile de feuilles.
 *
 * Le message d'échec du canal voyage EN ARGUMENT (affiché sous le titre,
 * tronqué à trois lignes — la vérité complète vit dans les sorties).
 */
@AndroidEntryPoint
public class FeuilleFilIncidentFragment : BottomSheetDialogFragment() {
    /** ViewModel de l'espace de travail (les destinations panneau). */
    private val viewModel: EditorViewModel by activityViewModels()

    /** Le navigateur applicatif (destination diagnostics — ADR 0048). */
    @Inject
    lateinit var navigateur: AppNavigator

    private var liaisonFeuille: FeuilleFilIncidentBinding? = null

    override fun onCreateView(inflateur: LayoutInflater, conteneur: ViewGroup?, etat: Bundle?): View {
        liaisonFeuille = FeuilleFilIncidentBinding.inflate(inflateur, conteneur, false)
        return liaisonFeuille!!.root
    }

    override fun onViewCreated(vue: View, etat: Bundle?) {
        val liaison = checkNotNull(liaisonFeuille)
        // Le message d'échec porté par le bandeau (contexte de l'incident).
        liaison.filIncidentMessage.text = arguments?.getString(ARG_MESSAGE).orEmpty()
        liaison.filIncidentMessage.isVisible = liaison.filIncidentMessage.text.isNotEmpty()

        liaison.filDestinationSorties.setOnClickListener {
            viewModel.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.CONSOLE))
            dismissAllowingStateLoss()
        }
        liaison.filDestinationProblemes.setOnClickListener {
            viewModel.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.PROBLEMES))
            dismissAllowingStateLoss()
        }
        liaison.filDestinationDiagnostics.setOnClickListener {
            navigateur.openDiagnostics()
            dismissAllowingStateLoss()
        }
        liaison.filDestinationLogcat.setOnClickListener {
            viewModel.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.LOGCAT))
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        liaisonFeuille = null
        super.onDestroyView()
    }

    public companion object {
        /** Argument : le message d'échec affiché sous le titre. */
        public const val ARG_MESSAGE: String = "fil_incident_message"

        /** Étiquette d'affichage (anti-doublon). */
        public const val ETIQUETTE: String = "fil_incident"

        /** Crée la feuille pour un incident au message [message]. */
        public fun creer(message: String): FeuilleFilIncidentFragment = FeuilleFilIncidentFragment().apply {
            arguments =
                Bundle().apply {
                    putString(ARG_MESSAGE, message)
                }
        }
    }
}
