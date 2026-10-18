package jo.codeide.feature.ai

import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiModelInfo
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.FabriqueFournisseurIa
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

// Doubles de test de la mission IA (lot IA-2) — en mémoire, aucun
// secret réel (valeurs factices évidentes, règle 10 du plan).

/** Réglages IA en mémoire : état mutable contrôlé par les tests. */
internal class FakeReglagesIa(
    valeur: AiSettings = AiSettings(),
) : ReglagesIaRepository {
    val flux = MutableStateFlow(valeur)
    var ecritures = 0

    override fun observeAiSettings(): Flow<AiSettings> = flux

    override suspend fun courant(): AppResult<AiSettings> = AppResult.Success(flux.value)

    override suspend fun update(update: (AiSettings) -> AiSettings): AppResult<Unit> {
        flux.value = update(flux.value)
        ecritures++
        return AppResult.Success(Unit)
    }
}

/** Coffre en mémoire : suit le port à la lettre (apercu, perte simulable). */
internal class FakeCoffre : CoffreClesIa {
    /** Clés stockées en clair (test) + drapeau « maître perdue ». */
    private val cles = mutableMapOf<String, String>()
    private val apercus = mutableMapOf<String, String>()
    var maitrePerdue = false
    var dernieresClesEnregistrees = mutableListOf<CharArray>()

    override suspend fun enregistrer(
        identifiant: String,
        cle: CharArray,
    ): AppResult<Unit> {
        dernieresClesEnregistrees.add(cle.copyOf())
        cles[identifiant] = cle.concatToString()
        apercus[identifiant] =
            if (cle.size <= 4) "•".repeat(cle.size) else "••••" + cle.concatToString().takeLast(4)
        cle.fill('\u0000')
        return AppResult.Success(Unit)
    }

    override suspend fun lire(identifiant: String): AppResult<CharArray> =
        when {
            !cles.containsKey(identifiant) -> AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound))
            maitrePerdue -> AppResult.Failure(AppError.Storage(AppError.StorageReason.KeyUnavailable, "test"))
            else -> AppResult.Success(cles.getValue(identifiant).toCharArray())
        }

    override suspend fun supprimer(identifiant: String): AppResult<Unit> {
        cles.remove(identifiant)
        apercus.remove(identifiant)
        return AppResult.Success(Unit)
    }

    override suspend fun contient(identifiant: String): Boolean = cles.containsKey(identifiant)

    override suspend fun apercu(identifiant: String): String? = apercus[identifiant]
}

/** Fournisseur factice : compte les sollicitations (jamais de réseau). */
internal class FauxFournisseur : AiProvider {
    var appelsStream = 0
    var appelsPing = 0
    var appelsListModels = 0

    override fun stream(request: AiRequest): Flow<AiEvent> {
        appelsStream++
        return flowOf(AiEvent.Failure(AppError.Unknown("hors périmètre")))
    }

    override suspend fun listModels(): AppResult<List<AiModelInfo>> {
        appelsListModels++
        return AppResult.Success(listOf(AiModelInfo("modele-test-1"), AiModelInfo("modele-test-2")))
    }

    override suspend fun ping(): AppResult<Unit> {
        appelsPing++
        return AppResult.Success(Unit)
    }
}

/** Fabrique factice : renvoie le fournisseur factice ou `null` (inconnu). */
internal class FauxFabrique : FabriqueFournisseurIa {
    val fournisseur = FauxFournisseur()
    var constructions = 0
    var dernieresReglages: AiSettings? = null

    override suspend fun creer(reglages: AiSettings): AiProvider? {
        constructions++
        dernieresReglages = reglages
        return if (reglages.fournisseurActif ==
            jo.codeide.core.domain.FournisseursIa.OPENAI_COMPATIBLE
        ) {
            null
        } else {
            fournisseur
        }
    }
}
