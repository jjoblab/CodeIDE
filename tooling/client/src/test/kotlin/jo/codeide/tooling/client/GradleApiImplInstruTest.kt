package jo.codeide.tooling.client

import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.BuildRequest
import jo.codeide.tooling.protocol.BuildStarted
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.StreamKind
import jo.codeide.tooling.protocol.SyncOutput
import jo.codeide.tooling.protocol.SyncRequest
import jo.codeide.tooling.protocol.SyncResult
import jo.codeide.tooling.protocol.SyncStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Tests de l'instrumentation client de la Phase 1 (ADR 0152) : résumé T3
 * détaillé du fil lecteur agrégé à la ligne `ConsoleLatence`, et
 * accumulateur de latence de TRANSPORT de la sync (INC-44 — angle mort
 * n°4 : la sync n'avait ni durée ni latence).
 */
class GradleApiImplInstruTest {
    /** API enregistrée pour le nettoyage automatique en fin de test. */
    private var api: GradleApiImpl? = null

    @After
    fun nettoyer() {
        api?.fermerSession()
        api = null
    }

    private val protocole = GradleProtocol.PROTOCOL_VERSION

    private fun nouvelId(): String = UUID.randomUUID().toString()

    /**
     * Sonde de test : rend un résumé FIXE (les durées mesurées par la vraie
     * session sont celles du fil de lecture — non reproductibles ici ; ce
     * qui EST testé, c'est que le résumé de la sonde rejoint la ligne
     * `ConsoleLatence` du build).
     */
    private class SondeFactice(private var resume: String?) : SondeLectureSocket {
        var frames = 0
            private set

        override fun frame(dureeLectureNs: Long, dureeDecodageNs: Long, dureeRoutageNs: Long) {
            frames++
        }

        override fun prendreResume(): String? = resume.also { resume = null }
    }

    @Test
    fun `le resume T3 du fil lecteur rejoint la ligne ConsoleLatence du build`() = runBlocking {
        val journal = FakeAppLogger()
        val sonde = SondeFactice("instru: lecteur — 3 frame(s) : lecture min 1 / moy 2 / max 3 µs ; …")
        val session =
            SessionFactice(
                sonde = sonde,
                reagir = { requete, soi ->
                    if (requete is BuildRequest) {
                        val buildId = requete.buildId
                        soi.emettre(BuildStarted(nouvelId(), protocole, buildId, listOf("saluer")))
                        // timestampMs ANCIEN : latence de transport mesurable.
                        soi.emettre(BuildOutput(nouvelId(), protocole, buildId, StreamKind.STDOUT, "ligne", 1_000L))
                        soi.emettre(BuildFinished(nouvelId(), protocole, buildId, true, 2_000, null))
                    }
                },
            )
        val api = GradleApiImpl(journal).also { api = it }
        api.ouvrirSession(session)

        val buildId = withTimeout(5_000) {
            val id = api.build(File("/p"), listOf("saluer"), emptyList())
            api.observeBuildState(id).first { it.statut != jo.codeide.core.domain.StatutBuild.EN_COURS }
            id
        }

        val ligne = journal.entries.single { it.tag == "ConsoleLatence" }.message
        assertTrue(
            "la ligne doit porter le résumé de transport ET le résumé du lecteur : $ligne",
            ligne.contains("sorties du build $buildId reçues") && ligne.contains("instru: lecteur — 3 frame(s)"),
        )
    }

    @Test
    fun `un build sans sonde ni ligne ne journalise aucune ligne de latence`() = runBlocking {
        val journal = FakeAppLogger()
        val session =
            SessionFactice { requete, soi ->
                if (requete is BuildRequest) {
                    soi.emettre(BuildStarted(nouvelId(), protocole, requete.buildId, listOf("saluer")))
                    soi.emettre(BuildFinished(nouvelId(), protocole, requete.buildId, true, 100, null))
                }
            }
        val api = GradleApiImpl(journal).also { api = it }
        api.ouvrirSession(session)
        withTimeout(5_000) {
            val id = api.build(File("/p"), listOf("saluer"), emptyList())
            api.observeBuildState(id).first { it.statut != jo.codeide.core.domain.StatutBuild.EN_COURS }
        }
        assertEquals(0, journal.entries.count { it.tag == "ConsoleLatence" })
    }

    @Test
    fun `INC-44 - la sync avec sorties journalise sa latence de transport au terme`() = runBlocking {
        val journal = FakeAppLogger()
        val session =
            SessionFactice { requete, soi ->
                if (requete is SyncRequest) {
                    soi.emettre(SyncStarted(nouvelId(), protocole, requete.projectDir))
                    // Deux lignes, timestamps anciens : min/moy/max mesurés.
                    soi.emettre(SyncOutput(nouvelId(), protocole, requete.projectDir, StreamKind.STDOUT, "a", 1_000L))
                    soi.emettre(SyncOutput(nouvelId(), protocole, requete.projectDir, StreamKind.STDERR, "b", 2_000L))
                    soi.emettre(SyncResult(requete.id, protocole, requete.projectDir, true, 5_000, null))
                }
            }
        val api = GradleApiImpl(journal).also { api = it }
        api.ouvrirSession(session)

        withTimeout(5_000) { api.synchroniser(File("/projet"), emptyList()) }

        val ligne = journal.entries.single { it.tag == "ConsoleLatence" }.message
        assertTrue(
            "la ligne doit nommer la sync et sa durée : $ligne",
            ligne.contains("sorties de la sync /projet reçues"),
        )
        assertTrue("la ligne doit porter la durée serveur : $ligne", ligne.contains("sync 5000 ms"))
        assertTrue("la ligne doit compter les lignes : $ligne", ligne.contains("2 ligne"))
    }

    @Test
    fun `INC-44 - une sync sans AUCUNE sortie ne journalise rien (rien mesure, rien invente)`() = runBlocking {
        val journal = FakeAppLogger()
        val session =
            SessionFactice { requete, soi ->
                if (requete is SyncRequest) {
                    soi.emettre(SyncStarted(nouvelId(), protocole, requete.projectDir))
                    soi.emettre(SyncResult(requete.id, protocole, requete.projectDir, true, 800, null))
                }
            }
        val api = GradleApiImpl(journal).also { api = it }
        api.ouvrirSession(session)

        withTimeout(5_000) { api.synchroniser(File("/projet"), emptyList()) }

        assertEquals(0, journal.entries.count { it.tag == "ConsoleLatence" })
    }

    @Test
    fun `INC-44 - la fermeture de session vide les accumulateurs de sync`() = runBlocking {
        val journal = FakeAppLogger()
        val session =
            SessionFactice { requete, soi ->
                if (requete is SyncRequest) {
                    // Sync SANS conclusion : la session meurt en cours de
                    // route (l'accumulateur ne doit pas fuir vers la suivante).
                    soi.emettre(SyncStarted(nouvelId(), protocole, requete.projectDir))
                    soi.emettre(SyncOutput(nouvelId(), protocole, requete.projectDir, StreamKind.STDOUT, "a", 1L))
                }
            }
        val api = GradleApiImpl(journal).also { api = it }
        // Délai d'inactivité raccourci : la sync interrompue conclut vite
        // en échec local (même patron que les tests d'inactivité existants).
        api.delaiInactiviteSyncMs = 200
        api.ouvrirSession(session)
        withTimeout(5_000) { api.synchroniser(File("/projet"), emptyList()) }
        api.fermerSession()

        // Le résumé de la sync interrompue n'est pas journalisé : sa
        // conclusion est l'échec local de l'appelant (déjà couvert par
        // ADR 0079) — ici on vérifie juste qu'aucune ligne de latence ne
        // sort d'un état résiduel.
        assertEquals(0, journal.entries.count { it.tag == "ConsoleLatence" })
    }
}
