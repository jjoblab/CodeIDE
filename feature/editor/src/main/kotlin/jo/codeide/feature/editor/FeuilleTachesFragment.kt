package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import jo.codeide.core.domain.InfoTache
import jo.codeide.feature.editor.databinding.FeuilleTachesBinding
import jo.codeide.feature.editor.databinding.LigneGroupeTachesBinding
import jo.codeide.feature.editor.databinding.LigneTacheFeuilleBinding

/**
 * Sélecteur de tâches Gradle (v4, §3.3) : BottomSheetDialogFragment
 * Material 3 — champ de RECHERCHE (filtre en direct), tâches RÉCENTES en
 * chips, GROUPES (build, verification, install…) par ordre d'apparition,
 * lancement au clic (la feuille se referme, la console prend le relais).
 *
 * Alimenté depuis `tachesDisponibles` (le cache de la sync, v4) : aucune
 * latence, aucun aller-retour — les tâches voyagent dans les ARGUMENTS
 * (la feuille survit à la mort du processus). L'état vide distingue
 * « synchronisation en cours » (trop tôt) d'« aucune correspondance ».
 *
 * Les groupes et le filtre sont décidés par [construireRangeesTaches] —
 * une fonction PURE testée, la feuille n'est que de la colle.
 */
internal class FeuilleTachesFragment : BottomSheetDialogFragment() {
    private var liaisonAmorce: FeuilleTachesBinding? = null

    /** Liaison de la vue courante (plus de `!!` — diagnostic lisible). */
    private val liaison
        get() =
            checkNotNull(liaisonAmorce) {
                "liaison de la feuille des tâches indisponible — vue détruite ?"
            }

    /** ViewModel de l'espace de travail (porté par l'activité). */
    private val viewModel: EditorViewModel by activityViewModels()

    /** Adaptateur des groupes + tâches filtrées. */
    private lateinit var adaptateur: AdaptateurFeuilleTaches

    /** Tâches passées à la création (arguments — survit au processus).
     *
     * v0.80.4 (correctif crash a6d72e9d) : `arguments` et NON
     * `requireArguments` — un fragment instancié sans arguments (ajout
     * programmatique, restauration) rend une liste VIDE au lieu de
     * planter l'activité ; l'état vide « synchronisation en cours »
     * prend alors le relais, honnête plutôt que fatal. */
    private val taches: List<InfoTache>
        get() {
            val paquet = arguments ?: return emptyList()
            val chemins = paquet.getStringArrayList(CLE_CHEMINS).orEmpty()
            val groupes = paquet.getStringArrayList(CLE_GROUPES).orEmpty()
            val noms = paquet.getStringArrayList(CLE_NOMS).orEmpty()
            return chemins.indices.map { indice ->
                InfoTache(
                    chemin = chemins[indice],
                    groupe = groupes.getOrNull(indice)?.takeIf { it.isNotEmpty() },
                    nomAffiche = noms.getOrNull(indice) ?: chemins[indice],
                )
            }
        }

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FeuilleTachesBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        adaptateur =
            AdaptateurFeuilleTaches { chemin ->
                viewModel.onAction(ActionEditor.ExecuterTaches(listOf(chemin)))
                dismiss()
            }
        liaison.listeTachesFeuille.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeTachesFeuille.adapter = adaptateur

        // Recherche : filtre EN DIRECT (une frappe = une rangée de moins).
        liaison.champRechercheTaches.doAfterTextChanged { filtrer() }

        peuplerRecentes()
        filtrer()
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }

    /** Chips des tâches récentes (la dernière exécution suivie) — la
     *  section entière disparaît quand il n'y en a pas.
     *
     *  v0.80.4 : `arguments` tolérant (même correctif que [taches]). */
    private fun peuplerRecentes() {
        val recents = arguments?.getStringArrayList(CLE_RECENTES).orEmpty()
        liaison.libelleRecentesTaches.isVisible = recents.isNotEmpty()
        liaison.defilementRecentesTaches.isVisible = recents.isNotEmpty()
        recents.forEach { chemin ->
            val chip = Chip(requireContext())
            chip.text = chemin.substringAfterLast(':')
            chip.isCheckable = false
            chip.contentDescription = chemin
            chip.setOnClickListener {
                viewModel.onAction(ActionEditor.ExecuterTaches(listOf(chemin)))
                dismiss()
            }
            liaison.groupeRecentesTaches.addView(chip)
        }
    }

    /** Filtre courant : recherche + état vide distinct (trop tôt vs rien). */
    private fun filtrer() {
        val requete =
            liaison.champRechercheTaches.text
                ?.toString()
                .orEmpty()
        val rangees = construireRangeesTaches(taches, requete)
        adaptateur.submitList(rangees)
        val avecTaches = rangees.any { it is RangeeTache.Tache }
        liaison.texteTachesVides.isVisible = !avecTaches
        liaison.texteTachesVides.text =
            if (taches.isEmpty()) {
                getString(R.string.editor_taches_sync_en_cours)
            } else {
                getString(R.string.editor_taches_vide)
            }
    }

    /** Fabrique de la feuille (tâches + récentes dans les arguments). */
    companion object {
        const val CLE_CHEMINS = "taches-chemins"

        const val CLE_GROUPES = "taches-groupes"

        const val CLE_NOMS = "taches-noms"

        const val CLE_RECENTES = "taches-recentes"

        fun creer(
            taches: List<InfoTache>,
            recents: List<String>,
        ): FeuilleTachesFragment =
            FeuilleTachesFragment().apply {
                arguments =
                    Bundle().apply {
                        putStringArrayList(CLE_CHEMINS, ArrayList(taches.map { it.chemin }))
                        putStringArrayList(CLE_GROUPES, ArrayList(taches.map { it.groupe.orEmpty() }))
                        putStringArrayList(CLE_NOMS, ArrayList(taches.map { it.nomAffiche }))
                        putStringArrayList(CLE_RECENTES, ArrayList(recents))
                    }
            }
    }

    /** Adaptateur des rangées de la feuille : en-tête de groupe / tâche. */
    private class AdaptateurFeuilleTaches(
        private val surExecution: (chemin: String) -> Unit,
    ) : ListAdapter<RangeeTache, RecyclerView.ViewHolder>(DiffRangeesTache) {
        private enum class Type {
            GROUPE,
            TACHE,
        }

        override fun getItemViewType(position: Int): Int =
            when (getItem(position)) {
                is RangeeTache.EnTete -> Type.GROUPE
                is RangeeTache.Tache -> Type.TACHE
            }.ordinal

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val inflateur = LayoutInflater.from(parent.context)
            return when (viewType) {
                Type.GROUPE.ordinal -> {
                    GroupeHolder(LigneGroupeTachesBinding.inflate(inflateur, parent, false))
                }

                else -> {
                    TacheHolder(LigneTacheFeuilleBinding.inflate(inflateur, parent, false))
                }
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (val rangee = getItem(position)) {
                is RangeeTache.EnTete -> (holder as GroupeHolder).lier(rangee)
                is RangeeTache.Tache -> (holder as TacheHolder).lier(rangee, surExecution)
            }
        }

        /** En-tête de groupe : capitulé — le groupe SANS groupe se dit
         *  « autres » (localisé au rendu). */
        private class GroupeHolder(
            private val liaison: LigneGroupeTachesBinding,
        ) : RecyclerView.ViewHolder(liaison.root) {
            fun lier(rangee: RangeeTache.EnTete) {
                liaison.libelleGroupeTaches.text =
                    rangee.libelle.ifEmpty {
                        liaison.root.context.getString(R.string.editor_taches_groupe_autres)
                    }
            }
        }

        /** Tâche : nom + module d'origine (racine pour les tâches non
         *  préfixées), lancement au clic. */
        private class TacheHolder(
            private val liaison: LigneTacheFeuilleBinding,
        ) : RecyclerView.ViewHolder(liaison.root) {
            fun lier(
                rangee: RangeeTache.Tache,
                surExecution: (String) -> Unit,
            ) {
                val contexte = liaison.root.context
                liaison.nomTacheFeuille.text = rangee.tache.nomAffiche
                liaison.moduleTacheFeuille.text = moduleDe(rangee.tache.chemin, contexte)
                liaison.root.setOnClickListener { surExecution(rangee.tache.chemin) }
            }

            /** Module d'une tâche : segment avant le dernier « : » — les
             *  tâches de la racine (sans préfixe de module) se disent
             *  « racine » (comme la vue Build d'Android Studio). */
            private fun moduleDe(
                chemin: String,
                contexte: android.content.Context,
            ): String =
                if (chemin.startsWith(':') && chemin.count { it == ':' } >= 2) {
                    chemin.substringBeforeLast(':').removePrefix(":")
                } else {
                    contexte.getString(R.string.editor_taches_module_racine)
                }
        }

        private object DiffRangeesTache : DiffUtil.ItemCallback<RangeeTache>() {
            override fun areItemsTheSame(
                ancienne: RangeeTache,
                nouvelle: RangeeTache,
            ): Boolean = ancienne.idCle == nouvelle.idCle

            override fun areContentsTheSame(
                ancienne: RangeeTache,
                nouvelle: RangeeTache,
            ): Boolean = ancienne == nouvelle
        }
    }
}

/**
 * Une rangée de la feuille des tâches (v4, §3.3) : en-tête de GROUPE ou
 * TÂCHE cliquable — modèle pur, le filtre et le regroupement vivent dans
 * [construireRangeesTaches] (testés).
 */
internal sealed interface RangeeTache {
    /** Identité stable de la rangée (DiffUtil). */
    val idCle: String

    /** En-tête de groupe (libellé localisable : le groupe ou « autres »). */
    data class EnTete(
        val libelle: String,
    ) : RangeeTache {
        override val idCle: String
            get() = "groupe-$libelle"
    }

    /** Tâche cliquable (lancement au clic). */
    data class Tache(
        val tache: InfoTache,
    ) : RangeeTache {
        override val idCle: String
            get() = "tache-${tache.chemin}"
    }
}

/**
 * Construit les rangées de la feuille (§3.3) : FILTRE de recherche en
 * direct (nom affiché ou chemin complet, insensible à la casse), GROUPES
 * dans l'ordre d'apparition — FONCTION PURE, testée ; le groupe SANS
 * groupe (`null`) devient une chaîne vide, le rendu le dit « autres ».
 */
internal fun construireRangeesTaches(
    taches: List<InfoTache>,
    requete: String,
): List<RangeeTache> {
    val q = requete.trim().lowercase()
    val filtrees =
        taches.filter { tache ->
            q.isEmpty() ||
                tache.nomAffiche.lowercase().contains(q) ||
                tache.chemin.lowercase().contains(q)
        }
    return filtrees
        .groupBy { it.groupe }
        .flatMap { (_, tachesDuGroupe) ->
            listOf(RangeeTache.EnTete(tachesDuGroupe.firstOrNull()?.groupe.orEmpty())) +
                tachesDuGroupe.map { RangeeTache.Tache(it) }
        }
}
