package jo.codeide

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Test d'intégration de la journalisation (critère d'acceptation de
 * l'étape 2) : le démarrage de l'application **réelle** initialise le
 * pipeline dans le processus principal et écrit l'en-tête de session au
 * format JSON Lines dans `filesDir/logs/current.jsonl`.
 *
 * Pas de règle Hilt ici : Hilt exige `HiltTestApplication` pour injecter
 * dans le test, mais ce que l'on éprouve est précisément le cycle de
 * démarrage réel — l'application s'assemble elle-même, comme en production.
 * L'écriture étant asynchrone (≤ 500 ms), le test sonde le fichier de façon
 * bornée.
 *
 * SDK 34 : `Application.getProcessName()` (API 28+) nomme le processus
 * principal — sous SDK 26, le repli `/proc/self/cmdline` lirait le nom du
 * worker de test Gradle et la détection de processus échouerait, ce qui
 * n'arrive jamais sur appareil réel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CodeIdeApplication::class)
class JournalisationIntegrationTest {
    private companion object {
        const val TENTATIVES = 100
        const val PAUSE_MS = 50L
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `le démarrage écrit l'en-tête de session en JSON Lines`() {
        val contexte = ApplicationProvider.getApplicationContext<Context>()

        val courant = attendreFichierCourant(contexte)

        val lignes = courant.readLines().filter { it.isNotBlank() }
        assertTrue("l'en-tête de session doit être écrit, obtenu : ${lignes.size} ligne(s)", lignes.isNotEmpty())

        val entrees = lignes.map { json.decodeFromString(LogEntry.serializer(), it) }
        val enTete = entrees.first()
        assertEquals("Session", enTete.tag)
        assertEquals(LogLevel.INFO, enTete.level)
        assertTrue(
            "l'en-tête porte la version, le code et le type de build : ${enTete.message}",
            enTete.message.contains("CodeIDE") && enTete.message.contains("("),
        )
        // Toutes les entrées du lancement partagent le même identifiant de
        // session, celui de l'en-tête.
        assertTrue(entrees.all { it.sessionId == enTete.sessionId })
    }

    /** Sonde le fichier courant jusqu'à ce que l'écriture asynchrone y pose l'en-tête. */
    private fun attendreFichierCourant(contexte: Context): File {
        val courant = File(File(contexte.filesDir, "logs"), "current.jsonl")
        repeat(TENTATIVES) {
            if (courant.exists() && courant.readLines().any { ligne -> ligne.isNotBlank() }) {
                return courant
            }
            Thread.sleep(PAUSE_MS)
        }
        throw AssertionError("Aucune entrée de journal écrite dans ${courant.absolutePath} après 5 s.")
    }
}
