package jo.codeide.feature.sorties

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.color.MaterialColors
import jo.codeide.core.ui.BandeauSorties
import jo.codeide.core.ui.BaseFragment
import jo.codeide.core.ui.NiveauEtat
import jo.codeide.core.ui.Retour
import jo.codeide.core.ui.SimpleTextWatcher
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.sorties.databinding.FragmentLecteurSortiesBinding

/**
 * Le lecteur du Centre de sorties (ADR 0159/0162, lot L2) — le fragment
 * qui assemble les ZONES de spec SORTIES § 2 : barre d'outils unifiée,
 * bandeaux d'état (honnêteté règle 14), lecteur virtualisé avec poignée
 * à repères, bouton « ⇣ suivre » du suivi honnête.
 *
 * Le fragment ne fait que RENDRE l'état du ViewModel et relayer les
 * gestes (règle 1 : aucune logique métier ici — la mécanique vit dans
 * `core:domain` et dans [LecteurSortiesViewModel]). Les surfaces (onglet
 * de la zone centrale, plein écran, panneau compact, volet tablette —
 * lots L5) l'embarquent comme UNE fenêtre sur le MÊME composant.
 *
 * Recherche : le champ déclenche [LecteurSortiesViewModel.definirRecherche]
 * (hors thread principal, anti-rebond de la frappe) ; un motif regex
 * invalide est AFFICHÉ en ligne (héritage `FiltreLogcat` — jamais
 * d'exception) et les correspondances masquées sont comptées dans N mais
 * leur saut est sauté.
 *
 * Sélection : appui long = ouvrir/étendre la sélection (toujours
 * explicite — spec § 3.6) ; bouton « copier » = copie du texte pur
 * (contrôleurs ANSI purgés) dans le presse-papiers.
 */
internal class FragmentLecteurSorties : BaseFragment<FragmentLecteurSortiesBinding>() {
    private val viewModel: LecteurSortiesViewModel by activityViewModels()
    private lateinit var lecteur: LecteurSorties

    /** Anti-rebond de la frappe (la relance du ViewModel annule et
     *  relance la recherche — le champ reste maître, spec § 3.4). */
    private val antiRebond = Handler(Looper.getMainLooper())
    private var rechercheEnCours: Runnable? = null
    private var regexActif = false

    private val champRecherche: EditText
        get() = binding.champRecherche

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        attachToRoot: Boolean,
    ): FragmentLecteurSortiesBinding = FragmentLecteurSortiesBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        brancherLecteur()
        brancherBarre()
        brancherEtats()
        // Rotation : retrouver la position retenue (spec SORTIES § 3.11 —
        // pivoter ne perd ni position, ni filtre, ni recherche : le
        // ViewModel survit, la position est repoussée au nouveau lecteur).
        lecteur.positionRetenue = viewModel.positionRetenue
    }

    override fun onStop() {
        super.onStop()
        viewModel.positionRetenue = lecteur.premiereRangeeVisible()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        antiRebond.removeCallbacksAndMessages(null)
    }

    /** Le moteur de lecture (contrat [LecteurSorties], implémenté sur
     *  RecyclerView — ADR 0159) et ses gestes. */
    private fun brancherLecteur() {
        val adapter = LignesSortiesAdapter(
            surLigneAppuyee = { indice ->
                // Appui simple : étendre la sélection SI le mode est ouvert
                // (la copie reste toujours explicite — spec § 3.6).
                if (viewModel.selection.plage.value != null) {
                    viewModel.selection.selectionner(indice)
                }
            },
            surLigneLongueAppuyee = { indice ->
                viewModel.selection.selectionner(indice)
            },
        )
        lecteur = LecteurSortiesRecycler(
            liste = binding.listeSorties,
            poignee = binding.poigneeSorties,
            adapter = adapter,
            surSuiviChange = { suit -> binding.boutonSuivre.isGone = suit },
            surZoomPolice = { taille ->
                viewModel.regler { options -> options.copy(taillePoliceSp = taille) }
            },
        )
        viewModel.projection.collectWithLifecycle(viewLifecycleOwner) { projection ->
            lecteur.afficherProjection(projection)
        }
        viewModel.reglages.collectWithLifecycle(viewLifecycleOwner) { options ->
            lecteur.definirReglages(options, viewModel.projection.value.origineMs)
        }
        viewModel.selection.plage.collectWithLifecycle(viewLifecycleOwner) { plage ->
            lecteur.definirSelection(plage)
            binding.boutonCopier.isVisible = plage != null
        }
        viewModel.marques.collectWithLifecycle(viewLifecycleOwner) { marques ->
            lecteur.definirMarques(marques)
        }
        viewModel.allerA.collectWithLifecycle(viewLifecycleOwner) { indice ->
            lecteur.allerALaRangee(indice)
        }
        binding.boutonSuivre.setOnClickListener { lecteur.reprendreSuivi() }
    }

    /** Les bascules de la barre d'outils (recherche, pause, réglages). */
    private fun brancherBarre() {
        champRecherche.addTextChangedListener(
            object : SimpleTextWatcher() {
                override fun afterTextChanged(texte: Editable?) {
                    rechercheEnCours?.let { antiRebond.removeCallbacks(it) }
                    val relance = Runnable {
                        viewModel.definirRecherche(champRecherche.text.toString(), regexActif)
                    }
                    rechercheEnCours = relance
                    antiRebond.postDelayed(relance, ANTI_REBOND_MS)
                }
            },
        )
        binding.boutonRegex.setOnClickListener {
            regexActif = !regexActif
            // Bascule visuelle par accent (même discipline que le filtre
            // Logcat — l'état ne dépend jamais d'une couleur seule :
            // TalkBack annonce via la description du bouton).
            val attributAccent = if (regexActif) {
                androidx.appcompat.R.attr.colorPrimary
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            binding.boutonRegex.setTextColor(
                MaterialColors.getColor(binding.boutonRegex, attributAccent, 0),
            )
            viewModel.definirRecherche(champRecherche.text.toString(), regexActif)
        }
        binding.boutonCorrespondancePrecedente.setOnClickListener { viewModel.naviguerCorrespondance(-1) }
        binding.boutonCorrespondanceSuivante.setOnClickListener { viewModel.naviguerCorrespondance(+1) }
        binding.boutonPause.setOnClickListener { viewModel.basculerPause() }
        binding.boutonHorodatage.setOnClickListener {
            // Relatif → absolu → masqué (trois états, un bouton).
            val actuelles = viewModel.reglages.value
            when {
                !actuelles.afficheHorodatage ->
                    viewModel.regler { it.copy(afficheHorodatage = true, horodatageAbsolu = false) }

                !actuelles.horodatageAbsolu ->
                    viewModel.regler { it.copy(horodatageAbsolu = true) }

                else ->
                    viewModel.regler { it.copy(afficheHorodatage = false, horodatageAbsolu = false) }
            }
        }
        binding.boutonNumeros.setOnClickListener {
            viewModel.regler { it.copy(afficheNumeros = !it.afficheNumeros) }
        }
        binding.boutonRetourLigne.setOnClickListener {
            viewModel.regler { it.copy(retourALaLigne = !it.retourALaLigne) }
        }
        binding.boutonSelection.setOnClickListener {
            // Le mode sélection se quitte par le même bouton (les taps
            // étendent la sélection pendant qu'il est ouvert).
            if (viewModel.selection.plage.value != null) {
                viewModel.selection.quitter()
            }
        }
        binding.boutonCopier.setOnClickListener {
            val texte = viewModel.selection.texteDe(viewModel.projection.value.rangees)
            if (texte != null) {
                val pressePapiers =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                pressePapiers.setPrimaryClip(ClipData.newPlainText("sorties", texte))
                viewModel.selection.quitter()
                Retour.snackbar(binding.root, requireContext().getString(R.string.sorties_copie_effectuee))
            }
        }
        binding.boutonEffacer.setOnClickListener { viewModel.effacer() }
    }

    /** Les états publiés : bandeaux d'honnêteté, compteur n/N, pause. */
    private fun brancherEtats() {
        viewModel.projection.collectWithLifecycle(viewLifecycleOwner) { projection ->
            val pertes = projection.pertes
            binding.bandeauPertes.isGone = pertes <= 0L
            if (pertes > 0L) {
                binding.bandeauPertes.setEtat(NiveauEtat.ERREUR)
                binding.bandeauPertes.titre = getString(R.string.sorties_bandeau_pertes, pertes)
            }
            val masquees = projection.masquees
            binding.bandeauMasquees.isGone = masquees <= 0
            if (masquees > 0) {
                binding.bandeauMasquees.setEtat(NiveauEtat.AVERTISSEMENT)
                binding.bandeauMasquees.titre = getString(R.string.sorties_bandeau_masquees, masquees)
            }
        }
        viewModel.recherche.collectWithLifecycle(viewLifecycleOwner) { etat ->
            binding.erreurMotif.isVisible = etat?.motifInvalide == true
            binding.compteurRecherche.isGone = etat == null
            if (etat != null) {
                binding.compteurRecherche.text = getString(
                    R.string.sorties_recherche_compteur,
                    etat.rang + 1,
                    etat.correspondances.size,
                )
            }
            binding.boutonCorrespondancePrecedente.isGone = etat == null
            binding.boutonCorrespondanceSuivante.isGone = etat == null
        }
        viewModel.enPauseDirect.collectWithLifecycle(viewLifecycleOwner) { enPause ->
            binding.bandeauPause.isGone = !enPause
            binding.boutonPause.contentDescription = if (enPause) {
                getString(R.string.sorties_reprise)
            } else {
                getString(R.string.sorties_pause)
            }
            binding.boutonPause.setIconResource(
                if (enPause) R.drawable.ic_reprise else R.drawable.ic_pause_sorties,
            )
        }
    }

    private companion object {
        /** Délai d'anti-rebond de la recherche (ms). */
        private const val ANTI_REBOND_MS = 300L
    }
}
