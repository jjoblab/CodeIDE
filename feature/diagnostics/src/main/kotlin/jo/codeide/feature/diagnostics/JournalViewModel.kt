package jo.codeide.feature.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.ClearLogsUseCase
import jo.codeide.core.domain.ExportLogsUseCase
import jo.codeide.core.domain.MeasureLogDiskUsageUseCase
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ReadAllLogsUseCase
import jo.codeide.core.domain.SetLogVerbosityUseCase
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.LogVerbosity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de l'onglet Journaux (étape 12) : visionneuse performante de
 * l'historique complet.
 *
 * Stratégie (ADR 0025) : l'historique persisté est lu **une fois**
 * ([ReadAllLogsUseCase]) ; le flot des entrées récentes
 * ([ObserveLogsUseCase]) fusionne ensuite les nouveautés par dédoublonnage
 * d'égalité — une même entrée déjà lue sur disque n'est jamais affichée
 * deux fois. La fenêtre affichée est bornée ; le défilement révèle les
 * entrées plus anciennes **en mémoire**, sans relecture disque.
 *
 * Les I/O vivent dans les implémentations du domaine (dispatchers
 * injectés) ; toutes les mutations d'état sont confinées au thread
 * principal du [viewModelScope].
 *
 * La recherche, les filtres et le suivi direct survivent à la rotation et
 * à la mort du processus via [SavedStateHandle].
 */
@HiltViewModel
// Exemptions detekt ciblées (règle 16 du prompt maître) : un cas d'usage par
// dépendance injectée (le découper serait du pontage artificiel), et huit
// actions distinctes + quatre collectes — chacune a son objet.
@Suppress("LongParameterList", "TooManyFunctions")
class JournalViewModel
    @Inject
    constructor(
        private val lireTout: ReadAllLogsUseCase,
        private val observerRecents: ObserveLogsUseCase,
        private val effacerJournaux: ClearLogsUseCase,
        private val exporterJournaux: ExportLogsUseCase,
        private val mesurerUsage: MeasureLogDiskUsageUseCase,
        private val choisirVerbosite: SetLogVerbosityUseCase,
        observerParametres: ObserveSettingsUseCase,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val etatInterne =
            MutableStateFlow(
                EtatJournal(
                    recherche = savedStateHandle[CLE_RECHERCHE] ?: "",
                    filtresNiveaux = restaurerFiltres(),
                    suivreDirect = savedStateHandle[CLE_SUIVI_DIRECT] ?: false,
                ),
            )

        /** État observable de la visionneuse. */
        val etat: StateFlow<EtatJournal> = etatInterne.asStateFlow()

        private val effetsInterne = Channel<EffetJournal>(Channel.BUFFERED)

        /** Effets ponctuels (consommés une fois). */
        val effets = effetsInterne.receiveAsFlow()

        /** Historique complet fusionné — privauté de la fenêtre affichée. */
        private val toutesEntrees = mutableListOf<LogEntry>()

        /** Index d'égalité des entrées connues (dédoublonnage de la fusion). */
        private val connues = mutableSetOf<LogEntry>()

        /** Taille de la fenêtre affichée (plus récentes conservées). */
        private var fenetre = FENETRE_INITIALE

        /** Recherche en cours — fusion des frappes par délai. */
        private val requeteInterne = MutableStateFlow(etatInterne.value.recherche)

        /** Première émission de recherche : passe sans attendre le délai. */
        private var premiereRequete = true

        init {
            chargerHistorique()
            suivreRecents()
            observerVerbosite(observerParametres)
            observerRecherche()
        }

        /** Traite une action intentionnelle de l'onglet. */
        fun onAction(action: ActionJournal) {
            when (action) {
                is ActionJournal.Rechercher -> traiterRecherche(action.texte)
                is ActionJournal.BasculerNiveau -> basculerNiveau(action.niveau)
                ActionJournal.BasculerSuiviDirect -> basculerSuiviDirect()
                ActionJournal.ChargerPlusAnciennes -> chargerPlusAnciennes()
                ActionJournal.Effacer -> effacer()
                ActionJournal.Partager -> partager()
                is ActionJournal.Enregistrer -> enregistrer(action.destinationUri)
                is ActionJournal.ChoisirVerbosite -> reglerVerbosite(action.verbosite)
            }
        }

        /** Charge l'historique persisté et l'usage disque initial. */
        private fun chargerHistorique() {
            viewModelScope.launch {
                when (val lecture = lireTout()) {
                    is AppResult.Success -> {
                        remplacerHistorique(lecture.value)
                        etatInterne.update { it.copy(chargement = false) }
                    }

                    is AppResult.Failure -> {
                        etatInterne.update {
                            it.copy(chargement = false, erreur = lecture.error)
                        }
                    }
                }
                mesurerUsageDisque()
            }
        }

        /** Fusionne en continu les entrées récentes du tampon mémoire. */
        private fun suivreRecents() {
            viewModelScope.launch {
                observerRecents(FENETRE_OBSERVATION).collect { fenetreRecents ->
                    var nouvelles = 0
                    fenetreRecents.forEach { entree ->
                        if (entree !in connues) {
                            connues += entree
                            toutesEntrees += entree
                            nouvelles++
                        }
                    }
                    if (nouvelles > 0) {
                        rafraichirFenetre()
                        if (etatInterne.value.suivreDirect) {
                            effetsInterne.trySend(EffetJournal.DefilerVersBas)
                        }
                    }
                }
            }
        }

        /** Suit la verbosité persistée (affichage du réglage courant). */
        private fun observerVerbosite(observerParametres: ObserveSettingsUseCase) {
            observerParametres()
                .map { it.logLevel }
                .distinctUntilChanged()
                .onEach { verbosite -> etatInterne.update { it.copy(verbosite = verbosite) } }
                .launchIn(viewModelScope)
        }

        /** Applique la recherche avec un délai de fusion des frappes. */
        @OptIn(FlowPreview::class)
        private fun observerRecherche() {
            requeteInterne
                .debounce { _ ->
                    if (premiereRequete) {
                        premiereRequete = false
                        0L
                    } else {
                        DELAI_RECHERCHE_MS
                    }
                }.distinctUntilChanged()
                .onEach { texte ->
                    etatInterne.update { it.copy(recherche = texte) }
                    rafraichirFenetre()
                }.launchIn(viewModelScope)
        }

        /** Enregistre puis fusionne la recherche demandée. */
        private fun traiterRecherche(texte: String) {
            savedStateHandle[CLE_RECHERCHE] = texte
            requeteInterne.value = texte
        }

        /** Bascule un niveau du filtre. */
        private fun basculerNiveau(niveau: LogLevel) {
            etatInterne.update { etat ->
                val filtres =
                    if (niveau in etat.filtresNiveaux) {
                        etat.filtresNiveaux - niveau
                    } else {
                        etat.filtresNiveaux + niveau
                    }
                savedStateHandle[CLE_FILTRES] = ArrayList(filtres.map { it.name })
                etat.copy(filtresNiveaux = filtres)
            }
            rafraichirFenetre()
        }

        /** Bascule le suivi direct des nouvelles entrées. */
        private fun basculerSuiviDirect() {
            val active = !etatInterne.value.suivreDirect
            savedStateHandle[CLE_SUIVI_DIRECT] = active
            etatInterne.update { it.copy(suivreDirect = active) }
            if (active) {
                effetsInterne.trySend(EffetJournal.DefilerVersBas)
            }
        }

        /** Révèle le palier suivant d'entrées plus anciennes. */
        private fun chargerPlusAnciennes() {
            fenetre += PAS_FENETRE
            rafraichirFenetre()
        }

        /** Efface les journaux après confirmation côté UI. */
        private fun effacer() {
            viewModelScope.launch {
                when (val resultat = effacerJournaux()) {
                    is AppResult.Success -> {
                        remplacerHistorique(emptyList())
                        mesurerUsageDisque()
                        etatInterne.update { it.copy(erreur = null) }
                    }

                    is AppResult.Failure -> {
                        etatInterne.update { it.copy(erreur = resultat.error) }
                    }
                }
            }
        }

        /** Produit l'archive de journaux pour la feuille de partage. */
        private fun partager() {
            viewModelScope.launch {
                when (val resultat = exporterJournaux()) {
                    is AppResult.Success -> {
                        effetsInterne.trySend(EffetJournal.ArchivePrete(resultat.value))
                    }

                    is AppResult.Failure -> {
                        etatInterne.update { it.copy(erreur = resultat.error) }
                    }
                }
            }
        }

        /** Écrit l'archive directement à la destination choisie. */
        private fun enregistrer(destinationUri: String) {
            viewModelScope.launch {
                when (val resultat = exporterJournaux(destinationUri)) {
                    is AppResult.Success -> {
                        etatInterne.update { it.copy(erreur = null) }
                    }

                    is AppResult.Failure -> {
                        etatInterne.update { it.copy(erreur = resultat.error) }
                    }
                }
            }
        }

        /** Persiste puis applique la verbosité de journalisation. */
        private fun reglerVerbosite(verbosite: LogVerbosity) {
            viewModelScope.launch {
                when (val resultat = choisirVerbosite(verbosite)) {
                    is AppResult.Success -> {
                        etatInterne.update { it.copy(verbosite = verbosite, erreur = null) }
                    }

                    is AppResult.Failure -> {
                        etatInterne.update { it.copy(erreur = resultat.error) }
                    }
                }
            }
        }

        /** Mesure l'occupation disque des journaux. */
        private suspend fun mesurerUsageDisque() {
            when (val resultat = mesurerUsage()) {
                is AppResult.Success -> {
                    etatInterne.update { it.copy(usageDisque = resultat.value) }
                }

                is AppResult.Failure -> {
                    etatInterne.update { it.copy(usageDisque = null) }
                }
            }
        }

        /** Remplace tout l'historique (chargement initial, effacement). */
        private fun remplacerHistorique(entrees: List<LogEntry>) {
            toutesEntrees.clear()
            toutesEntrees.addAll(entrees)
            connues.clear()
            connues.addAll(entrees)
            fenetre = FENETRE_INITIALE
            rafraichirFenetre()
        }

        /** Recalcule la fenêtre affichée depuis filtres, recherche et borne. */
        private fun rafraichirFenetre() {
            val etat = etatInterne.value
            val filtres = etat.filtresNiveaux
            val recherche = etat.recherche.trim()
            val filtrees =
                toutesEntrees.filter { entree ->
                    (filtres.isEmpty() || entree.level in filtres) && correspond(entree, recherche)
                }
            etatInterne.update {
                it.copy(
                    entrees = filtrees.takeLast(fenetre),
                    plusAnciennesDisponibles = filtrees.size > fenetre,
                )
            }
        }

        /** Correspondance insensible à la casse et aux accents, sur message,
         * étiquette et classe d'exception (même règle que la recherche de l'accueil). */
        private fun correspond(
            entree: LogEntry,
            recherche: String,
        ): Boolean {
            val cible = recherche.normaliser()
            if (cible.isEmpty()) return true
            return entree.message.normaliser().contains(cible) ||
                entree.tag.normaliser().contains(cible) ||
                (
                    entree.exception
                        ?.className
                        ?.normaliser()
                        ?.contains(cible) ?: false
                )
        }

        /** Normalisation de recherche : accents supprimés (NFD), casse pliée. */
        private fun String.normaliser(): String =
            java.text.Normalizer
                .normalize(this, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .trim()

        /** Restitue les filtres de niveaux sauvegardés. */
        private fun restaurerFiltres(): Set<LogLevel> {
            val noms: List<String> = savedStateHandle[CLE_FILTRES] ?: emptyList()
            return noms.mapNotNull { nom -> LogLevel.entries.firstOrNull { it.name == nom } }.toSet()
        }

        private companion object {
            /** Clé de la recherche sauvegardée. */
            const val CLE_RECHERCHE = "journal.recherche"

            /** Clé des filtres de niveaux sauvegardés. */
            const val CLE_FILTRES = "journal.filtres"

            /** Clé du suivi direct sauvegardé. */
            const val CLE_SUIVI_DIRECT = "journal.suivi_direct"

            /** Fenêtre initiale : les 500 dernières entrées (section 5.7). */
            const val FENETRE_INITIALE = 500

            /** Palier révélé à chaque demande de chargement plus ancien. */
            const val PAS_FENETRE = 500

            /** Fenêtre d'observation du tampon mémoire (capacité des filons). */
            const val FENETRE_OBSERVATION = 200

            /** Délai de fusion des frappes de recherche (comme l'accueil). */
            const val DELAI_RECHERCHE_MS = 250L
        }
    }
