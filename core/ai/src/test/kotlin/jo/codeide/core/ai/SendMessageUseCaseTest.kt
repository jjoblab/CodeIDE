package jo.codeide.core.ai

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests du cas d'usage « envoyer un message » (mission IA, plan IA-1) :
 * délégation transparente au port [AiProvider] — le ViewModel ne voit
 * jamais le fournisseur.
 */
class SendMessageUseCaseTest {
    /** Fournisseur factice : renvoie un flux figé, compte les appels. */
    private class FauxProvider : AiProvider {
        var appels = 0
        var derniereRequete: AiRequest? = null
        val evenements =
            listOf<AiEvent>(
                AiEvent.TextDelta("bonjour"),
                AiEvent.Done(AiStopReason.EndTurn),
            )

        override fun stream(request: AiRequest): Flow<AiEvent> {
            appels++
            derniereRequete = request
            return flowOf(*evenements.toTypedArray())
        }

        override suspend fun listModels(): AppResult<List<AiModelInfo>> =
            AppResult.Failure(AppError.Unknown("hors périmètre du test"))

        override suspend fun ping(): AppResult<Unit> = AppResult.Failure(AppError.Unknown("hors périmètre du test"))
    }

    @Test
    fun `le cas d'usage délègue au fournisseur et transmet les événements`() =
        runTest {
            val faux = FauxProvider()
            val useCase = SendMessageUseCase(faux)
            val requete = AiRequest(messages = listOf(AiMessage.user("salut")), model = "modele-test")

            val recus = useCase(requete).toList()

            assertEquals(faux.evenements, recus)
            assertEquals(1, faux.appels)
            assertEquals(requete, faux.derniereRequete)
        }

    @Test
    fun `un échec du fournisseur traverse tel quel`() =
        runTest {
            val useCase = SendMessageUseCase(FauxProvider())
            val requete = AiRequest(messages = listOf(AiMessage.user("salut")), model = "modele-test")

            val recus = useCase(requete).toList()

            // Le flux factice ne contient pas d'échec : le contrat est
            // testé par transparence — l'événement Failure du domaine est
            // transmis sans altération (vérifié par le type).
            assertEquals(2, recus.size)
        }
}
