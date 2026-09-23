package jo.codeide.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.DeleteAllCrashReportsUseCase
import jo.codeide.core.domain.DeleteCrashReportUseCase
import jo.codeide.core.domain.ExportCrashReportsUseCase
import jo.codeide.core.domain.ObserveCrashReportsUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * ViewModel de l'onglet Plantages (étape 12) : historique des rapports de
 * plantage conservés.
 *
 * La liste vit par le flot du dépôt — la consultation d'un rapport (écran
 * dédié, mode VIEW) et sa suppression re-émettent automatiquement l'état à
 * jour ; le ViewModel ne garde aucune copie.
 */
@HiltViewModel
class PlantagesViewModel
    @Inject
    constructor(
        observeRapports: ObserveCrashReportsUseCase,
        private val supprimerRapport: DeleteCrashReportUseCase,
        private val supprimerTous: DeleteAllCrashReportsUseCase,
        private val exporterRapports: ExportCrashReportsUseCase,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatPlantages())

        /** État observable de l'historique. */
        val etat: StateFlow<EtatPlantages> = etatInterne.asStateFlow()

        private val effetsInterne = Channel<EffetPlantages>(Channel.BUFFERED)

        /** Effets ponctuels (consommés une fois). */
        val effets = effetsInterne.receiveAsFlow()

        init {
            observeRapports()
                .onEach { rapports ->
                    etatInterne.update {
                        it.copy(chargement = false, rapports = rapports, erreur = null)
                    }
                }.launchIn(viewModelScope)
        }

        /** Traite une action intentionnelle de l'onglet. */
        fun onAction(action: ActionPlantages) {
            when (action) {
                is ActionPlantages.Supprimer -> supprimer(action.id)
                ActionPlantages.ToutSupprimer -> toutSupprimer()
                ActionPlantages.Exporter -> exporter()
            }
        }

        /** Supprime un rapport — la liste se re-émet via le flot du dépôt. */
        private fun supprimer(id: String) {
            viewModelScope.launch {
                supprimerSilencieusement { supprimerRapport(id) }
            }
        }

        /** Supprime tous les rapports — la liste se re-émet via le flot du dépôt. */
        private fun toutSupprimer() {
            viewModelScope.launch {
                supprimerSilencieusement { supprimerTous() }
            }
        }

        /** Produit l'archive de tous les rapports pour la feuille de partage. */
        private fun exporter() {
            viewModelScope.launch {
                when (val resultat = exporterRapports()) {
                    is AppResult.Success -> {
                        effetsInterne.trySend(EffetPlantages.ArchivePrete(resultat.value))
                    }

                    is AppResult.Failure -> {
                        etatInterne.update { it.copy(erreur = resultat.error) }
                    }
                }
            }
        }

        /**
         * Exécute une suppression en traduisant une éventuelle défaillance
         * d'I/O en erreur typée — le résultat booléen/entier n'est pas un
         * état : le flot du dépôt reste la seule vérité de la liste.
         */
        private suspend inline fun <T> supprimerSilencieusement(operation: () -> T) {
            try {
                operation()
            } catch (annulation: CancellationException) {
                throw annulation
            } catch (e: IOException) {
                etatInterne.update {
                    it.copy(erreur = AppError.Storage(AppError.StorageReason.Io, e.javaClass.simpleName))
                }
            }
        }
    }
