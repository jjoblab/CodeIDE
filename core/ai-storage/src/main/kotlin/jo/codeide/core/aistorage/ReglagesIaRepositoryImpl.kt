package jo.codeide.core.aistorage

import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation du port [ReglagesIaRepository] (mission IA, lot IA-2) :
 * délégation pure au [AiSettingsStore] (DataStore dédié) + journalisation
 * d'opérations (identifiants et causes techniques uniquement — jamais de
 * donnée personnelle, règle 15 du prompt maître).
 */
@Singleton
public class ReglagesIaRepositoryImpl
@Inject
constructor(
    private val store: AiSettingsStore,
    private val journal: AppLogger,
) : ReglagesIaRepository {
    override fun observeAiSettings(): Flow<AiSettings> = store.observe()

    override suspend fun courant(): AppResult<AiSettings> {
        val resultat = store.courant()
        if (resultat is AppResult.Failure) {
            val cause = resultat.error.javaClass.simpleName
            journal.w(TAG) { "lecture des réglages IA impossible ($cause)" }
        }
        return resultat
    }

    override suspend fun update(update: (AiSettings) -> AiSettings): AppResult<Unit> {
        val resultat = store.update(update)
        when (resultat) {
            is AppResult.Success -> {
                journal.d(TAG) { "réglages IA mis à jour" }
            }

            is AppResult.Failure -> {
                val cause = resultat.error.javaClass.simpleName
                journal.w(TAG) { "écriture des réglages IA impossible ($cause)" }
            }
        }
        return resultat
    }

    private companion object {
        const val TAG = "ReglagesIa"
    }
}

/**
 * Source de clé **par fournisseur** (mission IA, lot IA-2) : pont entre
 * le port `ApiKeySource` du domaine (ADR 0123 — la clé ne circule jamais
 * dans la requête) et le [CoffreClesIa]. La clé en clair vit le temps de
 * la conversion ; le tableau d'origine est effacé après usage.
 *
 * Une clé absente ou illisible renvoie `null` : le fournisseur traduit
 * alors en `AppError.Ai(Authentication)` AVANT toute requête — l'écran de
 * réglages, lui, affiche l'état exact (« à saisir » / « à ressaisir »)
 * via [CoffreClesIa.lire].
 */
public class SourceCleFournisseur(private val coffre: CoffreClesIa, private val identifiant: String) :
    jo.codeide.core.ai.ApiKeySource {
    override suspend fun obtain(): String? = when (val resultat = coffre.lire(identifiant)) {
        is AppResult.Success -> {
            val cle = String(resultat.value)
            resultat.value.fill('\u0000')
            cle
        }

        is AppResult.Failure -> {
            null
        }
    }
}
