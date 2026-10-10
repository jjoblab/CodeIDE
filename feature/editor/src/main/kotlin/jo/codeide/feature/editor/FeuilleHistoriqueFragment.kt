package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.EntreeHistorique
import jo.codeide.core.domain.LigneDiff
import jo.codeide.core.domain.TypeEntreeHistorique
import jo.codeide.core.domain.TypeLigneDiff
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FeuilleHistoriqueBinding
import jo.codeide.feature.editor.databinding.LigneDiffBinding
import jo.codeide.feature.editor.databinding.LigneEntetePeriodeBinding
import jo.codeide.feature.editor.databinding.LigneRevisionHistoriqueBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Une rangée de la liste : un en-tête de période OU une révision. */
internal sealed interface RangeeHistorique {
    data class Periode(
        val periode: PeriodeHistorique,
    ) : RangeeHistorique

    data class Revision(
        val revision: RevisionUi,
    ) : RangeeHistorique
}

/**
 * Feuille « Historique » (missions « Historique local » H2 puis H3,
 * spec HISTORIQUE_LOCAL.md § 5, maquette docs/preview/historique-local.html).
 * H2 : révisions d'un FICHIER groupées par période (moments relatifs),
 * diff unifié de la sélection (contre le contenu ACTUEL ou la révision
 * PRÉCÉDENTE), restauration AVEC confirmation — snackbar honnête +
 * Annuler. H3 : modes DOSSIER (tous les fichiers sous le préfixe) et
 * PROJET (« Modifications récentes »), filtre « Supprimés seuls »
 * (pierres tombales retrouvables) et RECRÉATION d'un fichier supprimé
 * (restauration d'une tombale, dossier parent résolu segment par
 * segment — jamais d'URI inventée).
 *
 * Le ViewModel porte TOUT l'état ; la feuille rend. Les moments
 * relatifs viennent de [CalculsDatesHistorique] (pur, testé).
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions") // Cycle + un rendu par zone (H2) + modes H3 — même surface que le panneau Logcat.
internal class FeuilleHistoriqueFragment : BottomSheetDialogFragment() {
    private val viewModel: HistoriqueViewModel by viewModels()

    /** ViewModel de l'espace — rafraîchit l'onglet ouvert après une
     *  restauration (sinon l'auto-sauvegarde écraserait la version
     *  restaurée, leçon H2). */
    private val viewModelEditeur: EditorViewModel by activityViewModels()

    private var liaisonAmorce: FeuilleHistoriqueBinding? = null

    private val liaison: FeuilleHistoriqueBinding
        get() =
            checkNotNull(liaisonAmorce) {
                "liaison de la feuille Historique indisponible — vue détruite ?"
            }

    /** Adaptateur des rangées (périodes + révisions). */
    private lateinit var adaptateurRevisions: AdaptateurRevisions

    /** Adaptateur des lignes du diff. */
    private lateinit var adaptateurDiff: AdaptateurDiff

    /** Format des dates anciennes (« 3 oct. à 09:12 » — locale de l'app). */
    private val formatAncien by lazy {
        SimpleDateFormat(getString(R.string.historique_format_date_ancien), Locale.getDefault())
    }

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FeuilleHistoriqueBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        super.onViewCreated(vue, etat)
        adaptateurRevisions =
            AdaptateurRevisions { revision -> viewModel.selectionner(revision.entree) }
        adaptateurDiff = AdaptateurDiff()
        liaison.listeRevisions.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeRevisions.adapter = adaptateurRevisions
        liaison.listeDiff.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeDiff.adapter = adaptateurDiff

        liaison.boutonFermerHistorique.setOnClickListener { dismiss() }
        liaison.boutonRetourDiff.setOnClickListener { viewModel.fermerDiff() }
        liaison.boutonModeDiff.setOnClickListener {
            viewModel.definirMode(!viewModel.etat.value.modeActuel)
        }
        // Filtre « Supprimés seuls » (H3) : bascule COCHÉE = pierres
        // tombales seules (l'état checked suit l'état du ViewModel).
        liaison.boutonFiltreSupprimes.setOnClickListener {
            viewModel.definirFiltreSupprimes(!liaison.boutonFiltreSupprimes.isChecked)
        }
        liaison.boutonRestaurer.setOnClickListener {
            val selection = viewModel.etat.value.selection ?: return@setOnClickListener
            demanderConfirmationRestauration(selection)
        }

        val paquet = arguments ?: Bundle.EMPTY
        viewModel.charger(
            uri = paquet.getString(CLE_URI).orEmpty(),
            cheminRelatif = paquet.getString(CLE_CHEMIN).orEmpty(),
            nomFichier = paquet.getString(CLE_NOM).orEmpty(),
            mode =
                paquet.getString(CLE_MODE)?.let { lu -> runCatching { ModeHistorique.valueOf(lu) }.getOrNull() }
                    ?: ModeHistorique.FICHIER,
        )
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Rend TOUT l'état : en-tête, liste/diff, rafraîchissement de
     *  l'onglet ouvert, snackbar. */
    private fun rendre(etat: EtatHistorique) {
        rendreEntete(etat)
        rendreListe(etat)
        rendreDiff(etat)
        // D'ABORD le rafraîchissement de l'onglet ouvert (contenu
        // restauré), PUIS le snackbar honnête (Annuler si possible).
        etat.contenuRestaure?.let { contenu ->
            viewModelEditeur.onAction(ActionEditor.RemplacerContenuFichier(etat.uri, contenu))
            viewModel.consommerContenuRestaure()
        }
        etat.message?.let { message -> montrerSnackbar(message) }
    }

    /** En-tête : titre (mode projet = « Modifications récentes »),
     *  sous-titre, filtre « Supprimés seuls » (l'état coché suit le
     *  ViewModel, pas le doigt — modes dossier/projet, liste seule). */
    private fun rendreEntete(etat: EtatHistorique) {
        liaison.titreHistorique.setText(
            if (etat.mode == ModeHistorique.PROJET) R.string.historique_titre_recentes else R.string.historique_titre,
        )
        liaison.sousTitreHistorique.text = etat.nomFichier
        liaison.boutonFiltreSupprimes.isChecked = etat.filtreSupprimes
        liaison.boutonFiltreSupprimes.isVisible =
            etat.mode != ModeHistorique.FICHIER && etat.selection == null
    }

    /** Liste : rangées groupées par période (un en-tête à CHAQUE
     *  changement), état vide honnête — le libellé dit la VÉRITÉ du
     *  filtre courant (H3). */
    private fun rendreListe(etat: EtatHistorique) {
        val rangees = ArrayList<RangeeHistorique>(etat.revisions.size + NB_MAX_PERIODES)
        var periodePrecedente: PeriodeHistorique? = null
        etat.revisions.forEach { revision ->
            if (revision.periode != periodePrecedente) {
                periodePrecedente = revision.periode
                rangees.add(RangeeHistorique.Periode(revision.periode))
            }
            rangees.add(RangeeHistorique.Revision(revision))
        }
        adaptateurRevisions.submitList(rangees)
        liaison.etatVideHistorique.isVisible = !etat.chargement && etat.revisions.isEmpty()
        liaison.texteVideHistorique.setText(
            if (etat.filtreSupprimes) R.string.historique_vide_filtre else R.string.historique_vide,
        )
    }

    /** Page de diff (si une révision est sélectionnée). */
    private fun rendreDiff(etat: EtatHistorique) {
        // Page courante : liste (aucune sélection) ou diff.
        liaison.flipperHistorique.displayedChild = if (etat.selection == null) 0 else 1
        val selection = etat.selection ?: return
        adaptateurDiff.submitList(etat.lignesDiff)
        liaison.ligneIndisponibleDiff.isVisible = etat.diffIndisponible
        liaison.boutonRestaurer.isVisible = selection.contenuDisponible
        liaison.boutonRestaurer.isEnabled = selection.contenuDisponible
        liaison.titreDiff.text =
            getString(
                R.string.historique_diff_titre,
                libelleMoment(selection),
                etat.lignesDiff.count { it.type != TypeLigneDiff.INCHANGE },
            )
        liaison.boutonModeDiff.setText(
            if (etat.modeActuel) R.string.historique_diff_vs_actuel else R.string.historique_diff_vs_precedente,
        )
    }

    /** Snackbar d'un message d'action (restauration, recréation,
     *  indisponibilité, échec, impasse). */
    private fun montrerSnackbar(message: MessageHistorique) {
        when (message) {
            MessageHistorique.Restauree,
            MessageHistorique.FichierRecree,
            -> {
                val texte =
                    if (message == MessageHistorique.Restauree) {
                        R.string.historique_restauree
                    } else {
                        R.string.historique_fichier_recree
                    }
                Snackbar
                    .make(liaison.root, texte, Snackbar.LENGTH_LONG)
                    .setAction(R.string.historique_annuler) { viewModel.annulerRestauration() }
                    .show()
            }

            MessageHistorique.ContenuIndisponible -> {
                Snackbar.make(liaison.root, R.string.historique_contenu_indisponible, Snackbar.LENGTH_LONG).show()
            }

            MessageHistorique.EchecEcriture -> {
                Snackbar.make(liaison.root, R.string.historique_echec_ecriture, Snackbar.LENGTH_LONG).show()
            }

            MessageHistorique.ParentIntrouvable -> {
                Snackbar.make(liaison.root, R.string.historique_parent_introuvable, Snackbar.LENGTH_LONG).show()
            }
        }
        viewModel.consommerMessage()
    }

    /** Confirmation honnête avant restauration (spec § 5) : une PIERRE
     *  TOMBALE annonce la RECRÉATION (le fichier n'existe plus — le
     *  dossier d'origine est résolu au moment voulu). */
    private fun demanderConfirmationRestauration(selection: EntreeHistorique) {
        val tombale = selection.type == TypeEntreeHistorique.SUPPRESSION
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.historique_confirmer_titre)
            .setMessage(
                if (tombale) R.string.historique_confirmer_recree_message else R.string.historique_confirmer_message,
            ).setPositiveButton(R.string.historique_confirmer_oui) { _, _ ->
                viewModel.restaurer(selection)
            }.setNegativeButton(R.string.historique_confirmer_non, null)
            .show()
    }

    /** Libellé localisé du TYPE d'une révision (maquette). */
    private fun libelleDeType(type: TypeEntreeHistorique): Int =
        when (type) {
            TypeEntreeHistorique.MODIFICATION -> R.string.historique_type_modification
            TypeEntreeHistorique.CREATION -> R.string.historique_type_creation
            TypeEntreeHistorique.SUPPRESSION -> R.string.historique_type_suppression
            TypeEntreeHistorique.RENOMMAGE -> R.string.historique_type_renommage
            TypeEntreeHistorique.EXTERNE -> R.string.historique_type_externe
            TypeEntreeHistorique.RESTAURATION -> R.string.historique_type_restauration
            TypeEntreeHistorique.ETIQUETTE -> R.string.historique_type_etiquette
        }

    /** Libellé localisé du moment d'une révision. */
    private fun libelleMoment(entree: EntreeHistorique): String =
        when (val moment = CalculsDatesHistorique.moment(entree.horodatageMs, System.currentTimeMillis())) {
            is MomentHistorique.IlYA -> {
                when (moment.unite) {
                    UniteMoment.MINUTES -> {
                        resources.getQuantityString(
                            R.plurals.historique_il_y_a_minutes,
                            moment.nombre.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            moment.nombre,
                        )
                    }

                    UniteMoment.HEURES -> {
                        resources.getQuantityString(
                            R.plurals.historique_il_y_a_heures,
                            moment.nombre.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            moment.nombre,
                        )
                    }
                }
            }

            is MomentHistorique.Hier -> {
                getString(R.string.historique_hier, moment.heureMinute)
            }

            is MomentHistorique.Ancien -> {
                formatAncien.format(Date(moment.horodatageMs))
            }
        }

    // ------------------------------------------------------------------
    // Adaptateurs.
    // ------------------------------------------------------------------

    /** Adaptateur des rangées de la liste (périodes + révisions). */
    private inner class AdaptateurRevisions(
        private val surChoix: (RevisionUi) -> Unit,
    ) : ListAdapter<RangeeHistorique, RecyclerView.ViewHolder>(DIFF_RANGEES) {
        override fun getItemViewType(position: Int): Int =
            if (getItem(position) is RangeeHistorique.Periode) TYPE_PERIODE else TYPE_REVISION

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == TYPE_PERIODE) {
                VuePeriode(
                    LigneEntetePeriodeBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                )
            } else {
                VueRevision(
                    LigneRevisionHistoriqueBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                    surChoix,
                )
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (val rangee = getItem(position)) {
                is RangeeHistorique.Periode -> {
                    (holder as VuePeriode).lier(rangee.periode)
                }

                is RangeeHistorique.Revision -> {
                    (holder as VueRevision).lier(
                        rangee.revision,
                        viewModel.etat.value.mode,
                    )
                }
            }
        }
    }

    /** En-tête de période. */
    private inner class VuePeriode(
        private val binding: LigneEntetePeriodeBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun lier(periode: PeriodeHistorique) {
            binding.libellePeriode.setText(
                when (periode) {
                    PeriodeHistorique.AUJOURDHUI -> R.string.historique_periode_aujourdhui
                    PeriodeHistorique.HIER -> R.string.historique_periode_hier
                    PeriodeHistorique.PLUS_ANCIEN -> R.string.historique_periode_plus_ancien
                },
            )
        }
    }

    /** Une révision. En mode DOSSIER/PROJET, le NOM DU FICHIER porte le
     *  libellé principal (les révisions mélangent les fichiers — H3) et
     *  le détail ajoute le type et le dossier parent. */
    private inner class VueRevision(
        private val binding: LigneRevisionHistoriqueBinding,
        private val surChoix: (RevisionUi) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun lier(
            revision: RevisionUi,
            mode: ModeHistorique,
        ) {
            val entree = revision.entree
            binding.iconeRevision.setImageResource(iconeDeType(entree.type))
            when (mode) {
                ModeHistorique.FICHIER -> {
                    binding.libelleRevision.setText(libelleDeType(entree.type))
                    binding.detailRevision.text =
                        listOfNotNull(
                            libelleMoment(entree),
                            entree.libelle,
                            if (entree.contenuDisponible) null else getString(R.string.historique_sans_contenu),
                        ).joinToString(" · ")
                }

                ModeHistorique.DOSSIER, ModeHistorique.PROJET -> {
                    binding.libelleRevision.text = entree.cheminRelatif.substringAfterLast('/')
                    binding.detailRevision.text =
                        listOfNotNull(
                            getString(libelleDeType(entree.type)),
                            libelleMoment(entree),
                            entree.cheminRelatif.substringBeforeLast('/', "").ifBlank { null },
                            if (entree.contenuDisponible) null else getString(R.string.historique_sans_contenu),
                        ).joinToString(" · ")
                }
            }
            binding.tailleRevision.text = tailleLisible(entree.tailleOctets)
            binding.root.setOnClickListener { surChoix(revision) }
            binding.root.isEnabled = entree.contenuDisponible
            binding.root.alpha = if (entree.contenuDisponible) ALPHA_ACTIVE else ALPHA_INACTIVE
        }

        private fun tailleLisible(octets: Long): String =
            when {
                octets >= OCTETS_PAR_KO -> String.format(Locale.ROOT, "%.1f Ko", octets / OCTETS_PAR_KO.toDouble())
                else -> "$octets o"
            }
    }

    /** Adaptateur des lignes du diff. */
    private inner class AdaptateurDiff : ListAdapter<LigneDiff, AdaptateurDiff.VueLigne>(DIFF_LIGNES) {
        inner class VueLigne(
            private val binding: LigneDiffBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun lier(ligne: LigneDiff) {
                val contexte = binding.root.context
                when (ligne.type) {
                    TypeLigneDiff.INCHANGE -> {
                        binding.texteLigneDiff.text =
                            binding.root.context.getString(R.string.historique_diff_ligne_inchange, ligne.texte)
                        binding.texteLigneDiff.setTextColor(
                            ContextCompat.getColor(contexte, R.color.explorateur_texte_2),
                        )
                        binding.texteLigneDiff.setBackgroundColor(TRANSPARENT)
                    }

                    TypeLigneDiff.AJOUT -> {
                        binding.texteLigneDiff.text =
                            binding.root.context.getString(R.string.historique_diff_ligne_ajout, ligne.texte)
                        binding.texteLigneDiff.setTextColor(
                            ContextCompat.getColor(contexte, R.color.logcat_niveau_info),
                        )
                        binding.texteLigneDiff.setBackgroundColor(
                            ContextCompat.getColor(contexte, R.color.historique_diff_ajout_fond),
                        )
                    }

                    TypeLigneDiff.RETRAIT -> {
                        binding.texteLigneDiff.text =
                            binding.root.context.getString(R.string.historique_diff_ligne_retrait, ligne.texte)
                        binding.texteLigneDiff.setTextColor(
                            ContextCompat.getColor(contexte, R.color.logcat_niveau_erreur),
                        )
                        binding.texteLigneDiff.setBackgroundColor(
                            ContextCompat.getColor(contexte, R.color.historique_diff_retrait_fond),
                        )
                    }
                }
            }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): VueLigne = VueLigne(LigneDiffBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(
            holder: VueLigne,
            position: Int,
        ) = holder.lier(getItem(position))
    }

    internal companion object {
        /** Ouvre la feuille de l'historique d'un fichier (H2), d'un
         *  dossier (H3 — préfixe récursif) ou du projet (« Modifications
         *  récentes », H3). */
        fun creer(
            uri: String,
            cheminRelatif: String,
            nom: String,
            mode: ModeHistorique = ModeHistorique.FICHIER,
        ): FeuilleHistoriqueFragment =
            FeuilleHistoriqueFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(CLE_URI, uri)
                        putString(CLE_CHEMIN, cheminRelatif)
                        putString(CLE_NOM, nom)
                        putString(CLE_MODE, mode.name)
                    }
            }

        private const val CLE_URI = "uri"
        private const val CLE_CHEMIN = "chemin"
        private const val CLE_NOM = "nom"
        private const val CLE_MODE = "mode"

        private const val TYPE_PERIODE = 0
        private const val TYPE_REVISION = 1

        private const val ALPHA_ACTIVE = 1f
        private const val ALPHA_INACTIVE = 0.45f
        private const val TRANSPARENT = 0x00000000

        /** Maximum d'en-têtes de période (aujourd'hui, hier, plus ancien). */
        private const val NB_MAX_PERIODES = 3

        /** Octets dans un kilooctet (taille lisible). */
        private const val OCTETS_PAR_KO = 1024L

        private val DIFF_RANGEES =
            object : DiffUtil.ItemCallback<RangeeHistorique>() {
                override fun areItemsTheSame(
                    ancienne: RangeeHistorique,
                    nouvelle: RangeeHistorique,
                ): Boolean =
                    when {
                        ancienne is RangeeHistorique.Periode && nouvelle is RangeeHistorique.Periode -> {
                            ancienne.periode == nouvelle.periode
                        }

                        ancienne is RangeeHistorique.Revision && nouvelle is RangeeHistorique.Revision -> {
                            ancienne.revision.entree.id == nouvelle.revision.entree.id
                        }

                        else -> {
                            false
                        }
                    }

                override fun areContentsTheSame(
                    ancienne: RangeeHistorique,
                    nouvelle: RangeeHistorique,
                ): Boolean = ancienne == nouvelle
            }

        private val DIFF_LIGNES =
            object : DiffUtil.ItemCallback<LigneDiff>() {
                override fun areItemsTheSame(
                    ancienne: LigneDiff,
                    nouvelle: LigneDiff,
                ): Boolean = ancienne == nouvelle

                override fun areContentsTheSame(
                    ancienne: LigneDiff,
                    nouvelle: LigneDiff,
                ): Boolean = ancienne == nouvelle
            }

        /** Icône d'un type de révision (jetons de la maquette). */
        private fun iconeDeType(type: TypeEntreeHistorique): Int =
            when (type) {
                TypeEntreeHistorique.MODIFICATION -> jo.codeide.core.ui.R.drawable.ic_editer
                TypeEntreeHistorique.CREATION -> jo.codeide.core.ui.R.drawable.ic_fichier
                TypeEntreeHistorique.SUPPRESSION -> jo.codeide.core.ui.R.drawable.ic_supprimer
                TypeEntreeHistorique.RENOMMAGE -> jo.codeide.core.ui.R.drawable.ic_renommer
                TypeEntreeHistorique.EXTERNE -> jo.codeide.core.ui.R.drawable.ic_actualiser
                TypeEntreeHistorique.RESTAURATION -> jo.codeide.core.ui.R.drawable.ic_enregistrer
                TypeEntreeHistorique.ETIQUETTE -> jo.codeide.core.ui.R.drawable.ic_etiquette
            }
    }
}
