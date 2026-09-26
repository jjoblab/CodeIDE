package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.ui.ThemeHarmonizer
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauConsoleBinding
import java.util.Locale

/**
 * Onglet Sortie du panneau inférieur (G5 §6, v0.32.4 ADR 0055) : état de
 * synchronisation/build (annulation visible en vol), console du build
 * avec auto-défilement (le suivi s'arrête quand la liste cesse de
 * grandir — un build fini ne défile plus).
 *
 * Le contenu migrent du layout empilé de l'activité (v0.32.3) vers ce
 * fragment ; l'activité ne collecte plus l'état tooling — chaque
 * fragment collecte ce qu'il rend.
 */
class PanneauConsoleFragment : Fragment() {
    private var liaisonAmorce: FragmentPanneauConsoleBinding? = null
    private val liaison get() = liaisonAmorce!!

    /** ViewModel de l'espace de travail (porté par l'activité). */
    private val viewModel: EditorViewModel by activityViewModels()

    /** Console du build. */
    private lateinit var adaptateur: SortieAdapter

    /** Taille de la dernière fenêtre rendue (auto-défilement). */
    private var tailleDerniereFenetre = 0

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentPanneauConsoleBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        adaptateur = SortieAdapter()
        liaison.listeSortie.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeSortie.adapter = adaptateur
        liaison.boutonAnnulerBuild.setOnClickListener {
            viewModel.onAction(ActionEditor.AnnulerBuild)
        }

        viewModel.etatGradle.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Rend le statut (balisé de SON canal — v0.32.5), l'annulation, la
     *  console (fenêtre bornée) et l'état vide. */
    private fun rendre(etat: EtatGradle) {
        liaison.statutSortie.text = libelleStatutTooling(etat)
        baliserCanalStatut(etat)
        liaison.boutonAnnulerBuild.isVisible = etat.statutBuild == StatutBuild.EN_COURS

        adaptateur.submitList(etat.lignes)
        val enVol = etat.statutBuild == StatutBuild.EN_COURS
        if (enVol && etat.lignes.size > tailleDerniereFenetre && etat.lignes.isNotEmpty()) {
            liaison.listeSortie.scrollToPosition(etat.lignes.lastIndex)
        }
        tailleDerniereFenetre = etat.lignes.size
        liaison.texteSortieVide.isVisible = etat.lignes.isEmpty()
    }

    /** Canal du statut (v0.32.5, ADR 0056 décision 5) : l'icône signature
     *  de la provenance de l'information — Sync quand la ligne parle de
     *  synchronisation, Build quand elle parle du build ; éteinte quand
     *  la console est vide (aucune information, aucun canal). */
    private fun baliserCanalStatut(etat: EtatGradle) {
        val canal =
            when {
                etat.synchronisationEnCours || etat.synchronisationReussie != null ||
                    etat.messageEchecSync != null -> CanalTooling.SYNC

                etat.statutBuild != null -> CanalTooling.BUILD

                else -> null
            }
        liaison.iconeCanalSortie.isVisible = canal != null
        if (canal != null) {
            liaison.iconeCanalSortie.setImageResource(canal.icone)
            // Couleur de marque du canal : harmonisée avec le primaire du
            // thème courant (ADR 0059 — se rapproche du fond d'écran en
            // couleurs dynamiques).
            liaison.iconeCanalSortie.setColorFilter(
                ThemeHarmonizer.harmoniserAvecPrimaire(requireContext(), canal.couleur),
            )
        }
    }

    /** Libellé du statut tooling : synchronisation, puis build, puis repli. */
    private fun libelleStatutTooling(etat: EtatGradle): String =
        when {
            etat.synchronisationEnCours -> {
                getString(R.string.editor_sortie_sync_en_cours)
            }

            etat.synchronisationReussie != null -> {
                getString(R.string.editor_sortie_sync_reussie, dureeLisible(etat.synchronisationReussie.dureeMs))
            }

            etat.messageEchecSync != null -> {
                etat.messageEchecSync
            }

            etat.statutBuild == StatutBuild.EN_COURS -> {
                getString(R.string.editor_sortie_build_en_cours)
            }

            etat.statutBuild == StatutBuild.REUSSI -> {
                getString(R.string.editor_sortie_build_reussi, dureeLisible(etat.dureeBuildMs ?: 0L))
            }

            etat.statutBuild == StatutBuild.ECHOUE -> {
                etat.messageEchecBuild ?: getString(R.string.editor_sortie_build_echoue)
            }

            etat.statutBuild == StatutBuild.ANNULE -> {
                getString(R.string.editor_sortie_build_annule)
            }

            etat.connexion == EtatConnexion.ECHOUEE -> {
                getString(R.string.editor_outil_deconnecte)
            }

            else -> {
                getString(R.string.editor_sortie_vide)
            }
        }

    /** Durée lisible (s, ou ms sous la seconde). */
    private fun dureeLisible(dureeMs: Long): String =
        if (dureeMs >= SEUIL_SECONDE_MS) {
            String.format(Locale.ROOT, "%.1fs", dureeMs / SECONDE_MS)
        } else {
            String.format(Locale.ROOT, "%dms", dureeMs)
        }

    private companion object {
        /** Seuil d'affichage en secondes (sous une seconde : ms). */
        const val SEUIL_SECONDE_MS = 1_000L

        /** Seconde en millisecondes (Double : division flottante, %.1fs). */
        const val SECONDE_MS = 1_000.0
    }
}
