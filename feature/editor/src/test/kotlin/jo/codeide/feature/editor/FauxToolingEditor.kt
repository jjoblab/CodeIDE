package jo.codeide.feature.editor

import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.InfoTache
import jo.codeide.core.domain.InstantaneTas
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.SeveriteDiagnostic
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/**
 * Faux du port tooling pour les tests de l'espace de travail (G5) : états
 * pilotables par le test, sorties et états de build par canaux — l'ordre
 * et la non-conflation se vérifient comme côté client.
 *
 * @property prochainBuildId identifiant renvoyé par `build`.
 * @property prochaineSynchronisation réponse de `synchroniser`.
 * @property prochainesTaches réponse de `taches`.
 */
class FauxToolingEditor : GradleToolingRepository {
    var prochainBuildId: String = "b-test"

    var prochaineSynchronisation: AppResult<ResultatSynchronisation> =
        AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))

    var prochainesTaches: AppResult<List<InfoTache>> =
        AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))

    /** Dossier passé à chaque opération (assertions). */
    var dossierRecu: File? = null

    /** Tâches du dernier build demandé. */
    var tachesDemandees: List<String> = emptyList()

    /** Identifiant du dernier build annulé. */
    var buildAnnule: String? = null

    /** Connexion observable (pilotable par le test). */
    val connexionInterne = MutableStateFlow(EtatConnexion.CONNECTEE)

    /** Diagnostics observables (pilotables par le test). */
    val diagnosticsInterne = MutableStateFlow<List<DiagnosticBuild>>(emptyList())

    /** Sorties par build — canal borné comme l'implémentation réelle. */
    private val sorties = HashMap<String, Channel<LigneSortieBuild>>()

    /** États par build. */
    private val etats = HashMap<String, MutableStateFlow<EtatBuild>>()

    override fun observeBuildOutput(buildId: String): Flow<LigneSortieBuild> = canal(buildId).receiveAsFlow()

    override fun observeBuildState(buildId: String): Flow<EtatBuild> = etat(buildId)

    override suspend fun synchroniser(projectDir: File): AppResult<ResultatSynchronisation> {
        dossierRecu = projectDir
        return prochaineSynchronisation
    }

    override suspend fun taches(projectDir: File): AppResult<List<InfoTache>> {
        dossierRecu = projectDir
        return prochainesTaches
    }

    override suspend fun build(
        projectDir: File,
        tasks: List<String>,
    ): String {
        dossierRecu = projectDir
        tachesDemandees = tasks
        etat(prochainBuildId)
        return prochainBuildId
    }

    override fun cancel(buildId: String) {
        buildAnnule = buildId
    }

    override fun observeHeap(): Flow<InstantaneTas> = MutableStateFlow(InstantaneTas(0, 0))

    override fun observeConnectionState(): Flow<EtatConnexion> = connexionInterne

    override fun observeDiagnostics(projectDir: File): Flow<List<DiagnosticBuild>> = diagnosticsInterne

    /** Simule une ligne de sortie pour le build [buildId]. */
    fun emettreLigne(
        buildId: String,
        flux: FluxSortieBuild = FluxSortieBuild.STDOUT,
        ligne: String,
    ) {
        canal(buildId).trySend(
            LigneSortieBuild(buildId = buildId, flux = flux, ligne = ligne, horodatageMs = 0L),
        )
    }

    /** Simule la fin d'un build (état final + fermeture du canal). */
    fun terminerBuild(
        buildId: String,
        statut: StatutBuild,
        messageEchec: String? = null,
    ) {
        etat(buildId).value = EtatBuild(buildId = buildId, statut = statut, messageEchec = messageEchec)
        canal(buildId).close()
    }

    private fun canal(buildId: String): Channel<LigneSortieBuild> =
        sorties.getOrPut(buildId) { Channel(Channel.UNLIMITED) }

    private fun etat(buildId: String): MutableStateFlow<EtatBuild> =
        etats.getOrPut(buildId) { MutableStateFlow(EtatBuild(buildId = buildId, statut = StatutBuild.EN_COURS)) }
}
