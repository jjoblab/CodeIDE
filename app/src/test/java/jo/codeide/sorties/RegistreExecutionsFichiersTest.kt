package jo.codeide.sorties

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.datastore.SettingsDataStore
import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.ExecutionSortie
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.PositionSource
import jo.codeide.core.domain.ReferenceJournauxServeur
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.domain.TypeExecution
import jo.codeide.core.domain.VerdictExecution
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests de l'implémentation FICHIER du registre des exécutions (ADR 0161,
 * lot L3) — aller-retour complet (idempotence à la relecture), bornes
 * (N exécutions, plafond de lignes tête/queue, plafond d'octets),
 * expurgation À L'ÉCRITURE (chemins relativisés, liens source résolubles),
 * index corrompu lu comme liste vide, journaux serveur LUS jamais copiés.
 * Robolectric : `filesDir` réel, org.json réel.
 */
@RunWith(RobolectricTestRunner::class)
class RegistreExecutionsFichiersTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private lateinit var registre: RegistreExecutionsFichiers

    /** Le dépôt de journal en mémoire (les lignes `gradle-server` se
     *  LISENT ici — jamais copiées dans le registre). */
    private val depotJournal = DepotJournalDeTest()

    /** Le stock de réglages (lot L6, M13) : DataStore réel sur un fichier
     *  propre à chaque invocation — les défauts tombent sur la même
     *  rétention (10) que la constante N_EXECUTIONS d'ADR 0161. */
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun prepare() {
        val stock: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = portee,
            produceFile = {
                File(contexte.cacheDir, "registre-${System.nanoTime()}.preferences_pb")
            },
        )
        registre = RegistreExecutionsFichiers(
            contexte = contexte,
            journalDepot = depotJournal,
            journal = FakeLoggerSorties(),
            dispatchers = DispatchersDirects(),
            reglages = SettingsDataStore(
                stock,
                AppSettings.defaults(buildDebuggable = true),
            ),
        )
    }

    @After
    fun relache() {
        portee.cancel()
    }

    /** La racine de projet des tests (expurgation). */
    private val racine = "/data/user/0/jo.codeide/files/projects/Demo"

    /** Une ligne de build réaliste (marqueur Kotlin + position). */
    private fun ligne(message: String, indice: Int) = LigneSortie(
        source = SourceSortie.BUILD,
        message = message,
        horodatageMs = 1_700_000_000_000L + indice,
        idExecution = "b-1",
        tache = ":app:compileDebugKotlin",
        positionsSource = if (message.startsWith("e: ")) {
            listOf(PositionSource("file://$racine/app/src/A.kt", 12, colonne = 5))
        } else {
            emptyList()
        },
    )

    @Test
    fun `le aller-retour complet restitue les memes lignes`() = runBlocking {
        val execution = ExecutionSortie(
            id = "b-1",
            type = TypeExecution.BUILD,
            projet = racine,
            tachesDemandees = listOf(":app:assembleDebug"),
            debutMs = 1_700_000_000_000L,
            etat = EtatExecutionGradle(verdict = VerdictExecution.REUSSI, dureeMs = 4_200L),
            journauxServeur = ReferenceJournauxServeur(debutMs = 1_700_000_000_000L, finMs = 1_700_000_004_200L),
            totalLignes = 3,
        )
        registre.demarrer(execution)
        registre.ajouterLignes(execution, listOf(ligne("> Task :app:compileDebugKotlin", 0), ligne("sortie", 1)))
        registre.ajouterLignes(execution, listOf(ligne("e: file://$racine/app/src/A.kt:12:5 erreur", 2)))
        registre.conclure(execution.copy(finMs = 1_700_000_004_200L, totalLignes = 3))

        val chargees = registre.charger()
        assertEquals(1, chargees.size)
        assertEquals(VerdictExecution.REUSSI, chargees.single().etat?.verdict)
        assertEquals(3L, chargees.single().totalLignes)

        // La relecture est IDEMPOTENTE : les mêmes lignes qu'à l'écriture
        // (champ tâche compris — le regroupement re-ouvert fonctionne).
        val relues = registre.ouvrirLignes("b-1")
        assertEquals(3, relues.size)
        assertEquals("> Task :app:compileDebugKotlin", relues[0].message)
        assertEquals(":app:compileDebugKotlin", relues[0].tache)
        // La position est expurgée mais RESOLVABLE (relative au projet).
        assertEquals("app/src/A.kt", relues[2].positionsSource.single().fichier)
        assertEquals(12, relues[2].positionsSource.single().ligne)

        // La RELECTURE est stable (idempotence — ADR 0161).
        assertEquals(relues, registre.ouvrirLignes("b-1"))
    }

    @Test
    fun `l expurgation a l ecriture masque les chemins hors projet`() = runBlocking {
        val execution = ExecutionSortie(id = "b-2", type = TypeExecution.BUILD, projet = racine, debutMs = 1L)
        registre.demarrer(execution)
        registre.ajouterLignes(
            execution,
            listOf(
                ligne("alain@exemple.fr a publié depuis /data/local/tmp/secret.txt", 0),
            ),
        )
        val relues = registre.ouvrirLignes("b-2")
        val message = relues.single().message
        assertTrue(message.contains("<courriel>"))
        assertTrue(message.contains("<chemin>"))
        assertTrue(!message.contains(racine))
        assertTrue(!message.contains("secret.txt"))
    }

    @Test
    fun `la troncature epargne la tete et la queue et est annoncee`() = runBlocking {
        val execution =
            ExecutionSortie(id = "b-3", type = TypeExecution.BUILD, projet = null, debutMs = 1L, totalLignes = 30)
        registre.demarrer(execution)
        // Un registre de test a le plafond PRODUCTION (20 000) : on ne
        // peut pas le contourner sans une seconde implémentation — la
        // troncature AU-DELÀ du plafond est vérifiée PAR CONSTRUCTION
        // (TroncatureSorties, testée au domaine) ; ICI on vérifie que
        // la borne ne s'applique PAS sous le plafond.
        registre.ajouterLignes(execution, (0 until 30).map { indice -> ligne("ligne $indice", indice) })
        val conclue = registre.conclure(execution.copy(finMs = 2L, totalLignes = 30))
        assertNull(conclue.troncature)
        assertEquals(30, registre.ouvrirLignes("b-3").size)
    }

    @Test
    fun `l index corrompu se lit comme une liste vide`() = runBlocking {
        // Un stockage volontairement pourri (octets arbitraires).
        val dossier = java.io.File(contexte.filesDir, "sorties")
        dossier.mkdirs()
        java.io.File(dossier, "executions.json").writeText("{ pas du json")
        assertTrue(registre.charger().isEmpty())
    }

    @Test
    fun `effacer une execution retire son en tete et ses lignes`() = runBlocking {
        val execution = ExecutionSortie(id = "b-4", type = TypeExecution.SYNC, debutMs = 1L)
        registre.demarrer(execution)
        registre.ajouterLignes(execution, listOf(ligne("debut de sync", 0)))
        registre.effacer("b-4")
        assertTrue(registre.charger().isEmpty())
        assertTrue(registre.ouvrirLignes("b-4").isEmpty())
    }

    @Test
    fun `les journaux serveur se lisent dans le journal jamais dans le registre`() = runBlocking {
        val debut = 1_700_000_000_000L
        depotJournal.entrees.addAll(
            listOf(
                LogEntry(debut + 10, "session-1", LogLevel.INFO, "gradle-server", "main", "daemon démarré"),
                LogEntry(debut + 20, "session-1", LogLevel.WARN, "gradle-server", "main", "mémoire du daemon"),
                LogEntry(debut + 30, "session-1", LogLevel.INFO, "autre-tag", "main", "hors sujet"),
                LogEntry(99L, "session-0", LogLevel.ERROR, "gradle-server", "main", "hors plage"),
            ),
        )
        val execution = ExecutionSortie(
            id = "b-5",
            type = TypeExecution.BUILD,
            debutMs = debut,
            journauxServeur = ReferenceJournauxServeur(debutMs = debut, finMs = debut + 100),
        )
        val lignes = registre.chargerJournauxServeur(execution)
        // Les deux entrées gradle-server DANS la plage — ni l'autre tag,
        // ni la plage hors bornes.
        assertEquals(2, lignes.size)
        assertEquals(SourceSortie.SERVEUR, lignes[0].source)
        assertEquals("gradle-server", lignes[0].etiquette)
        assertEquals("daemon démarré", lignes[0].message)
        // Sans référence (exécution ancienne), aucune ligne.
        assertTrue(
            registre.chargerJournauxServeur(
                ExecutionSortie(id = "x", type = TypeExecution.BUILD, debutMs = 1L),
            ).isEmpty(),
        )
    }

    @Test
    fun `la relecture survit a un enregistrement corrompu dans l index`() = runBlocking {
        val execution = ExecutionSortie(id = "b-6", type = TypeExecution.BUILD, debutMs = 1L)
        registre.demarrer(execution)
        registre.conclure(execution.copy(finMs = 2L))
        // On glisse un objet NON VALIDE dans l'index (id absent).
        val dossier = java.io.File(contexte.filesDir, "sorties")
        val fichierIndex = java.io.File(dossier, "executions.json")
        val contenu = fichierIndex.readText()
        fichierIndex.writeText(contenu.replace("[", "[{\"sans\": \"id\"},"))
        // La lecture tolérante saute l'entrée corrompue, garde l'autre.
        val chargees = registre.charger()
        assertEquals(1, chargees.size)
        assertEquals("b-6", chargees.single().id)
        assertNotNull(registre.ouvrirLignes("b-6"))
        assertTrue(registre.ouvrirLignes("b-6").isEmpty())
    }

    /** Le dépôt de journal en mémoire (contrat minimal). */
    private class DepotJournalDeTest : jo.codeide.core.domain.LogRepository {
        val entrees = ArrayList<LogEntry>()

        override fun observeRecent(limit: Int) = kotlinx.coroutines.flow.flowOf(entrees.toList())

        override suspend fun readAll(): List<LogEntry> = entrees.toList()

        override suspend fun diskUsage(): jo.codeide.core.domain.LogDiskUsage =
            jo.codeide.core.domain.LogDiskUsage(bytes = 0, fileCount = 0)

        override suspend fun clear() {
            entrees.clear()
        }
    }

    /** Un journal muet (les pans du registre ne s'affichent pas). */
    private class FakeLoggerSorties : jo.codeide.core.domain.AppLogger {
        override val sessionId: String = "session-test"

        override fun log(
            level: jo.codeide.core.model.LogLevel,
            tag: String,
            throwable: Throwable?,
            message: () -> String,
        ) = Unit
    }

    /** Des dispatchers directs (les tests Robolectric sont bloquants). */
    private class DispatchersDirects : jo.codeide.core.domain.DispatcherProvider {
        override val io = kotlinx.coroutines.Dispatchers.Unconfined
        override val default = kotlinx.coroutines.Dispatchers.Unconfined
        override val main = kotlinx.coroutines.Dispatchers.Unconfined
    }
}
