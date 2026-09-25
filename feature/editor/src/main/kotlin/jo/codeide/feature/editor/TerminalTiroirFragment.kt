package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentTerminalTiroirBinding

/**
 * Fragment **Terminal** du tiroir à fragments (étape 31) : entête propre
 * (emblème violet, titre, sous-titre) + carte d'aperçu des sessions (T6,
 * section 8) **migrée telle quelle** de l'entête v1 de
 * `activity_editor.xml` (mêmes identifiants, même rendu). Zéro dépendance
 * Termux : tout vient de l'état observé du `EditorViewModel` — critère
 * d'acceptation de la section 11 du prompt Terminal-1.
 */
@AndroidEntryPoint
class TerminalTiroirFragment : Fragment() {
    private var liaisonAmorce: FragmentTerminalTiroirBinding? = null
    private val liaison get() = liaisonAmorce!!

    /** ViewModel de l'espace de travail (porté par l'activité). */
    private val viewModel: EditorViewModel by activityViewModels()

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentTerminalTiroirBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        liaison.carteTerminal.setOnClickListener { viewModel.onAction(ActionEditor.OuvrirTerminal) }
        liaison.boutonAgrandirTerminal.setOnClickListener { viewModel.onAction(ActionEditor.OuvrirTerminal) }
        liaison.boutonNouvelleSessionTerminal.setOnClickListener {
            viewModel.onAction(ActionEditor.NouvelleSessionTerminal)
        }
        liaison.boutonInstallerTerminal.setOnClickListener {
            viewModel.onAction(ActionEditor.InstallerOutilsTerminal)
        }
        viewModel.etatTerminal.collectWithLifecycle(viewLifecycleOwner) { rendreCarte(it) }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Rendu de la carte (T6) : métadonnées du registre global uniquement. */
    private fun rendreCarte(etat: EtatTerminalTiroir) {
        liaison.compteurSessionsTerminal.text =
            resources.getQuantityString(
                R.plurals.editor_terminal_sessions_actives,
                etat.sessionsVivantes,
                etat.sessionsVivantes,
            )

        // Bootstrap absent : l'installation d'abord (garde-fou § 7).
        liaison.texteNonInstalleTerminal.isVisible = !etat.bootstrapInstalle
        liaison.boutonInstallerTerminal.isVisible = !etat.bootstrapInstalle

        // État vide : aucune session du tout (vivantes ou terminées).
        val aucuneSession = etat.bootstrapInstalle && etat.nbSessions == 0
        liaison.texteAucuneSessionTerminal.isVisible = aucuneSession
        liaison.boutonNouvelleSessionTerminal.isVisible = aucuneSession

        // Session active : pastille, libellé, dernière sortie.
        val session = etat.sessionActive
        liaison.libelleSessionTerminal.isVisible = session != null
        liaison.sortieSessionTerminal.isVisible =
            session != null && session.lastOutputPreview.isNotBlank()
        if (session != null) {
            liaison.libelleSessionTerminal.text =
                if (session.isAlive) {
                    session.label
                } else {
                    getString(R.string.editor_terminal_session_terminee, session.label)
                }
            liaison.sortieSessionTerminal.text = session.lastOutputPreview
            val couleurPastille =
                MaterialColors.getColor(
                    liaison.root,
                    if (session.isAlive) {
                        androidx.appcompat.R.attr.colorPrimary
                    } else {
                        com.google.android.material.R.attr.colorOutline
                    },
                )
            liaison.pastilleTerminal.backgroundTintList =
                android.content.res.ColorStateList
                    .valueOf(couleurPastille)
        }

        // Accessibilité : la carte se lit d'un geste.
        val resume =
            session?.label
                ?: getString(
                    if (etat.bootstrapInstalle) {
                        R.string.editor_terminal_aucune_session
                    } else {
                        R.string.editor_terminal_non_installe
                    },
                )
        liaison.carteTerminal.contentDescription =
            getString(R.string.editor_terminal_carte_cd, resume)
    }
}
