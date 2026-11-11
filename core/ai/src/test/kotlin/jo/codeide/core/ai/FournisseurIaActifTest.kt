package jo.codeide.core.ai

import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.ValidationUrlIa
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du fournisseur actif (mission IA, lot IA-2) — le critère
 * d'acceptation central : **sans consentement, aucune requête réseau IA
 * ne part** (la fabrique n'est jamais sollicitée), avec ou sans
 * interrupteur, pour les trois opérations du port.
 */
class FournisseurIaActifTest {
    /** Réglages factices : état mutable contrôlé par les tests. */
    private class FauxReglages(var valeur: AiSettings) : ReglagesIaRepository {
        val flux = MutableStateFlow(valeur)

        override fun observeAiSettings(): Flow<AiSettings> = flux

        override suspend fun courant(): AppResult<AiSettings> = AppResult.Success(valeur)

        override suspend fun update(update: (AiSettings) -> AiSettings): AppResult<Unit> {
            valeur = update(valeur)
            flux.value = valeur
            return AppResult.Success(Unit)
        }
    }

    /** Fabrique factice : compte les constructions (jamais réseau). */
    private class FauxFabrique : FabriqueFournisseurIa {
        var constructions = 0
        var dernieresReglages: AiSettings? = null
        val fournisseur = FauxProvider()

        override suspend fun creer(reglages: AiSettings): AiProvider? {
            constructions++
            dernieresReglages = reglages
            return if (reglages.fournisseurActif == FournisseursIa.ANTHROPIC) fournisseur else null
        }
    }

    private class FauxProvider : AiProvider {
        var appelsStream = 0
        var appelsPing = 0
        var appelsListModels = 0
        var derniereRequete: AiRequest? = null

        override fun stream(request: AiRequest): Flow<AiEvent> {
            appelsStream++
            derniereRequete = request
            return flowOf(AiEvent.TextDelta("ok"), AiEvent.Done(AiStopReason.EndTurn))
        }

        override suspend fun listModels(): AppResult<List<AiModelInfo>> {
            appelsListModels++
            return AppResult.Success(listOf(AiModelInfo("modele-a")))
        }

        override suspend fun ping(): AppResult<Unit> {
            appelsPing++
            return AppResult.Success(Unit)
        }
    }

    /** Réglages « tout prêt » : actif, consenti, URL et modèle remplis. */
    private fun reglagesPrets(): AiSettings = AiSettings(
        assistantActive = true,
        consentementDonne = true,
        fournisseurActif = FournisseursIa.ANTHROPIC,
        urlBaseAnthropic = "https://fournisseur-test.example",
        modeleAnthropic = "modele-actif",
    )

    @Test
    fun `sans consentement aucune requête ne part et l'accès est refusé`() = runTest {
        val reglages = FauxReglages(reglagesPrets().copy(consentementDonne = false))
        val fabrique = FauxFabrique()
        val routeur = FournisseurIaActif(reglages, fabrique)

        val evenements = routeur.stream(AiRequest(listOf(AiMessage.user("salut")), model = "m")).toList()
        val ping = routeur.ping()
        val modeles = routeur.listModels()

        assertEquals(0, fabrique.constructions)
        assertEquals(0, fabrique.fournisseur.appelsStream)
        assertEquals(0, fabrique.fournisseur.appelsPing)
        assertEquals(0, fabrique.fournisseur.appelsListModels)
        assertEquals(1, evenements.size)
        val echec = evenements.single() as AiEvent.Failure
        assertEquals(AppError.AiReason.ConsentRequired, (echec.error as AppError.Ai).reason)
        assertEquals(
            AppError.AiReason.ConsentRequired,
            (ping as AppResult.Failure).error.let { (it as AppError.Ai).reason },
        )
        assertEquals(
            AppError.AiReason.ConsentRequired,
            (modeles as AppResult.Failure).error.let { (it as AppError.Ai).reason },
        )
    }

    @Test
    fun `sans interrupteur d'activation l'accès est refusé`() = runTest {
        val reglages = FauxReglages(reglagesPrets().copy(assistantActive = false))
        val fabrique = FauxFabrique()
        val routeur = FournisseurIaActif(reglages, fabrique)

        val evenements = routeur.stream(AiRequest(listOf(AiMessage.user("salut")), model = "m")).toList()

        assertEquals(0, fabrique.constructions)
        assertTrue(evenements.single() is AiEvent.Failure)
    }

    @Test
    fun `avec consentement le flux est délégué et le modèle des réglages fait autorité`() = runTest {
        val reglages = FauxReglages(reglagesPrets())
        val fabrique = FauxFabrique()
        val routeur = FournisseurIaActif(reglages, fabrique)

        val evenements = routeur.stream(AiRequest(listOf(AiMessage.user("salut")), model = "autre")).toList()

        assertEquals(1, fabrique.constructions)
        assertEquals(1, fabrique.fournisseur.appelsStream)
        assertEquals("modele-actif", fabrique.fournisseur.derniereRequete?.model)
        assertEquals(2, evenements.size)
    }

    @Test
    fun `un modèle vide est refusé comme erreur de configuration`() = runTest {
        val reglages = FauxReglages(reglagesPrets().copy(modeleAnthropic = ""))
        val fabrique = FauxFabrique()
        val routeur = FournisseurIaActif(reglages, fabrique)

        val evenements = routeur.stream(AiRequest(listOf(AiMessage.user("salut")), model = "m")).toList()

        // La garde de configuration (modèle vide) précède la fabrique :
        assertEquals(0, fabrique.constructions)
        val echec = evenements.single() as AiEvent.Failure
        assertEquals(AppError.AiReason.Configuration, (echec.error as AppError.Ai).reason)
        assertEquals(0, fabrique.fournisseur.appelsStream)
    }

    @Test
    fun `sans modèle configuré listModels et ping passent quand même`() = runTest {
        // v0.190.0 (retour propriétaire OpenRouter) : au PREMIER paramétrage,
        // lister les modèles et tester la connexion précèdent le CHOIX du
        // modèle — exiger un modèle déjà configuré fermait la porte du
        // premier usage (œuf et poule : impossible de lister pour choisir).
        val reglages = FauxReglages(reglagesPrets().copy(modeleAnthropic = ""))
        val fabrique = FauxFabrique()
        val routeur = FournisseurIaActif(reglages, fabrique)

        val ping = routeur.ping()
        val modeles = routeur.listModels()

        assertTrue(ping is AppResult.Success)
        assertEquals(1, fabrique.fournisseur.appelsPing)
        assertEquals(1, fabrique.fournisseur.appelsListModels)
        assertEquals("modele-a", (modeles as AppResult.Success).value.single().id)
        assertEquals(0, fabrique.fournisseur.appelsStream)
    }

    @Test
    fun `une URL de base invalide est refusée avant la fabrique`() = runTest {
        val reglages = FauxReglages(reglagesPrets().copy(urlBaseAnthropic = "http://serveur.example"))
        val fabrique = FauxFabrique()
        val routeur = FournisseurIaActif(reglages, fabrique)

        val evenements = routeur.stream(AiRequest(listOf(AiMessage.user("salut")), model = "m")).toList()

        assertEquals(0, fabrique.constructions)
        val echec = evenements.single() as AiEvent.Failure
        assertEquals(AppError.AiReason.Configuration, (echec.error as AppError.Ai).reason)
    }

    @Test
    fun `un fournisseur inconnu est refusé comme erreur de configuration`() = runTest {
        val reglages = FauxReglages(reglagesPrets().copy(fournisseurActif = "inconnu"))
        val fabrique = FauxFabrique()
        val routeur = FournisseurIaActif(reglages, fabrique)

        val modeles = routeur.listModels()

        assertEquals(1, fabrique.constructions)
        assertEquals(AppError.AiReason.Configuration, ((modeles as AppResult.Failure).error as AppError.Ai).reason)
    }

    @Test
    fun `ping et listModels délègent au fournisseur construit`() = runTest {
        val reglages = FauxReglages(reglagesPrets())
        val fabrique = FauxFabrique()
        val routeur = FournisseurIaActif(reglages, fabrique)

        val ping = routeur.ping()
        val modeles = routeur.listModels()

        assertEquals(1, fabrique.fournisseur.appelsPing)
        assertEquals(1, fabrique.fournisseur.appelsListModels)
        assertTrue(ping is AppResult.Success)
        assertEquals("modele-a", (modeles as AppResult.Success).value.single().id)
    }

    @Test
    fun `la validation URL accepte HTTPS et la boucle locale seulement`() {
        assertEquals(null, ValidationUrlIa.erreur("https://api.fournisseur.example/v1"))
        assertEquals(null, ValidationUrlIa.erreur("http://localhost:8080/v1"))
        assertEquals(null, ValidationUrlIa.erreur("http://127.0.0.1:1234"))
        assertTrue(ValidationUrlIa.erreur("http://serveur.example") != null)
        assertTrue(ValidationUrlIa.erreur("") != null)
        assertTrue(ValidationUrlIa.erreur("https://a b.example") != null)
    }
}
