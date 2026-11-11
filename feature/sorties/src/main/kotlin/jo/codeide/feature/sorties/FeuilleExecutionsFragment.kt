package jo.codeide.feature.sorties

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.ExecutionSortie
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.RegistreExecutions
import jo.codeide.core.domain.VerdictExecution
import jo.codeide.feature.sorties.databinding.FeuilleExecutionsBinding
import jo.codeide.feature.sorties.databinding.RangExecutionBinding
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * La feuille d'HISTORIQUE des exécutions (lot L6 — l'entrée M11 attendue
 * depuis la note du banc L2, ADR 0161) : les exécutions conservées du
 * [RegistreExecutions] (builds et syncs, la plus récente d'abord), ouvertes
 * depuis la barre du lecteur Gradle. Le tap d'une rangée RELIT les lignes
 * persistées et les remet au lecteur en CONSULTATION (ADR 0161.5 —
 * [FragmentLecteurSorties.ouvrirExecutionPersistee]) : le direct est
 * retenu, « revenir au direct » l'efface (l'exécution passée reste dans
 * le registre).
 *
 * L'effacement de tout l'historique est EXPLICITE et confirmé (M13) —
 * jamais un geste destructeur déguisé en lecture. Une liste vide est un
 * état honnête qui dit quoi faire (règle 14).
 */
@AndroidEntryPoint
public class FeuilleExecutionsFragment : BottomSheetDialogFragment() {
    /** Le registre des exécutions (port core:domain, lié côté :app). */
    @Inject
    lateinit var registre: RegistreExecutions

    private var _liaison: FeuilleExecutionsBinding? = null
    private val liaison: FeuilleExecutionsBinding
        get() = checkNotNull(_liaison)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _liaison = FeuilleExecutionsBinding.inflate(inflater, container, false)
        return liaison.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        liaison.historiqueEffacer.setOnClickListener { confirmerEffacement() }
        recharger()
    }

    override fun onDestroyView() {
        _liaison = null
        super.onDestroyView()
    }

    /** Recharge le registre et rend la liste (la plus récente d'abord). */
    private fun recharger() {
        viewLifecycleOwner.lifecycleScope.launch {
            val executions = registre.charger()
            rendre(executions)
        }
    }

    /** Rend la liste (une rangée par exécution) ou l'état vide honnête. */
    private fun rendre(executions: List<ExecutionSortie>) {
        val vide = executions.isEmpty()
        liaison.historiqueVide.isGone = !vide
        liaison.historiqueEffacer.isGone = vide
        liaison.conteneurExecutions.removeAllViews()
        executions.forEach { execution ->
            val rang = RangExecutionBinding.inflate(layoutInflater, liaison.conteneurExecutions, false)
            remplir(rang, execution)
            rang.racine.setOnClickListener { ouvrir(execution) }
            liaison.conteneurExecutions.addView(rang.root)
        }
    }

    /** Remplit UNE rangée : nature, cible, verdict, date, durée, volume. */
    private fun remplir(rang: RangExecutionBinding, execution: ExecutionSortie) {
        rang.executionNature.setText(
            if (execution.type == jo.codeide.core.domain.TypeExecution.BUILD) {
                R.string.sorties_historique_nature_build
            } else {
                R.string.sorties_historique_nature_sync
            },
        )
        val cible = execution.tachesDemandees.joinToString(" ")
        rang.executionCible.text = cible.ifEmpty {
            getString(R.string.sorties_historique_cible_vide)
        }
        rang.executionVerdict.text = libelleVerdict(execution.etat?.verdict)
        rang.executionQuand.text = quand(execution)
        rang.executionVolume.text = volume(execution)
        rang.racine.contentDescription = getString(
            R.string.sorties_historique_ouvrir_cd,
            rang.executionNature.text,
            rang.executionCible.text,
        )
    }

    /** Le libellé du verdict (ou « en vol » — honnêteté règle 14). */
    private fun libelleVerdict(verdict: VerdictExecution?): String = getString(
        when (verdict) {
            VerdictExecution.REUSSI -> R.string.sorties_verdict_reussi
            VerdictExecution.ECHOUE -> R.string.sorties_verdict_echoue
            VerdictExecution.ANNULE -> R.string.sorties_verdict_annule
            null -> R.string.sorties_verdict_en_vol
        },
    )

    /** La date relative (framework, localisée) et la durée si conclue. */
    private fun quand(execution: ExecutionSortie): String {
        val relatif = DateUtils.getRelativeTimeSpanString(
            execution.debutMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        val duree = execution.etat?.dureeMs
        return if (duree != null && duree > 0L) {
            getString(R.string.sorties_historique_duree, relatif, DateUtils.formatElapsedTime(duree / 1000L))
        } else {
            relatif
        }
    }

    /** Le volume affiché : lignes conservées (troncature annoncée). */
    private fun volume(execution: ExecutionSortie): String {
        val tronquee = execution.troncature
        return if (tronquee != null) {
            getString(
                R.string.sorties_historique_tronquee,
                tronquee.tete + tronquee.queue,
                tronquee.totalLignes,
            )
        } else {
            getString(R.string.sorties_historique_lignes, execution.totalLignes)
        }
    }

    /** OUVRE l'exécution : les lignes relisent le registre, le lecteur
     *  passe en consultation (le parent est LE lecteur qui a ouvert la
     *  feuille — childFragmentManager). */
    private fun ouvrir(execution: ExecutionSortie) {
        viewLifecycleOwner.lifecycleScope.launch {
            val lignes = registre.ouvrirLignes(execution.id)
            (parentFragment as? FragmentLecteurSorties)?.ouvrirExecutionPersistee(lignes, execution)
            dismiss()
        }
    }

    /** L'effacement de TOUT l'historique, confirmé (jamais un accident). */
    private fun confirmerEffacement() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sorties_historique_effacer)
            .setMessage(R.string.sorties_historique_effacer_confirmation)
            .setPositiveButton(R.string.sorties_historique_effacer) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    registre.effacer(null)
                    recharger()
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
