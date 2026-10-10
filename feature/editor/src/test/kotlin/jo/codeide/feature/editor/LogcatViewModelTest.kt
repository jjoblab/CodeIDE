package jo.codeide.feature.editor

import jo.codeide.core.domain.ArchiveSessionsJournal
import jo.codeide.core.domain.EtatSessionJournal
import jo.codeide.core.domain.FinSessionJournal
import jo.codeide.core.domain.IdentiteSessionJournal
import jo.codeide.core.domain.LigneJournal
import jo.codeide.core.domain.LotJournal
import jo.codeide.core.domain.NiveauJournal
import jo.codeide.core.domain.PontJournauxApplications
import jo.codeide.core.domain.SessionJournal
import jo.codeide.core.domain.SessionJournalArchivee
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'onglet Logcat (mission « Exécuter » R3) — JVM pur
 * avec les faux du pont et de l'archive : autosélection, choix qui prime,
 * pause/reprise par rattrapage, effacement local, filtres, archivage des
 * fins, sessions précédentes.
 *
 * Le faux du pont réplique la sémantique de [LotJournal] du registre : le
 * delta incrémental rend les lignes STRICTEMENT après la position, l'état
 * des sessions est réémis à chaque lot (c'est le signal de rattrapage).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LogcatViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val pont = FauxPont()
    private val archive = FauxArchive()

    private fun viewModel(): LogcatViewModel = LogcatViewModel(pont, archive)

    /** Avance le planificateur du dispatcher Main (celui du ViewModel). */
    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }

    private fun ligne(
        message: String,
        niveau: NiveauJournal = NiveauJournal.INFO,
        etiquette: String = "MainActivity",
    ): LigneJournal =
        LigneJournal(
            horodatageMs = 1_000L,
            pid = 4321,
            tid = 4321,
            niveau = niveau,
            etiquette = etiquette,
            message = message,
        )

    @Test
    fun `une session vivante est autoselectionnee et ses lignes s affichent`() =
        runTest {
            pont.ouvrir("com.exemple.app", 4321)
            val viewModel = viewModel()
            avancer()

            assertEquals("com.exemple.app/4321", viewModel.etat.value.idSelection)
            assertTrue(
                viewModel.etat.value.lignes
                    .isEmpty(),
            )

            pont.pousser("com.exemple.app", 4321, listOf(ligne("onCreate"), ligne("onStart")))
            avancer()

            assertEquals(
                listOf("onCreate", "onStart"),
                viewModel.etat.value.lignes
                    .map { it.ligne.message },
            )
            // Numéros strictement croissants (identité DiffUtil).
            assertTrue(
                viewModel.etat.value.lignes
                    .zipWithNext()
                    .all { (a, b) -> a.numero < b.numero },
            )
            assertEquals(
                2,
                viewModel.etat.value.sessions[0]
                    .nombreLignes,
            )
        }

    @Test
    fun `le choix de l utilisateur prime sur l autoselection`() =
        runTest {
            pont.ouvrir("com.exemple.app", 4321)
            val viewModel = viewModel()
            avancer()
            pont.terminer("com.exemple.app", 4321, "le processus s'est arrêté")
            avancer()

            // L'utilisateur consulte la session TERMINÉE du registre.
            viewModel.selectionnerSession("com.exemple.app/4321")
            assertEquals("com.exemple.app/4321", viewModel.etat.value.idSelection)

            // Une vivante NOUVELLE naît : le choix de l'utilisateur reste.
            pont.ouvrir("com.exemple.app", 5000)
            pont.pousser("com.exemple.app", 5000, listOf(ligne("autre processus")))
            avancer()

            assertEquals("com.exemple.app/4321", viewModel.etat.value.idSelection)
        }

    @Test
    fun `une vivante nouvellement nee deloge une archive consultee`() =
        runTest {
            archive.semer(
                SessionJournalArchivee(
                    session = pont.sessionArchivee("com.exemple.app", 1000, "fin veille"),
                    derniereLigne = ligne("dernière avant veille"),
                ),
            )
            val viewModel = viewModel()
            avancer()

            // Au démarrage sans session vivante : la plus récente archive
            // s'affiche (jamais du vide muet).
            assertNotNull(viewModel.etat.value.sessionArchivee)
            assertEquals(
                listOf("dernière avant veille"),
                viewModel.etat.value.lignes
                    .map { it.ligne.message },
            )

            // L'app est relancée : la vivante NOUVELLE prend la main.
            pont.ouvrir("com.exemple.app", 1000)
            pont.pousser("com.exemple.app", 1000, listOf(ligne("retour")))
            avancer()

            assertEquals("com.exemple.app/1000", viewModel.etat.value.idSelection)
            assertEquals(null, viewModel.etat.value.sessionArchivee)
        }

    @Test
    fun `la pause fige l affichage et la reprise rattrape`() =
        runTest {
            pont.ouvrir("com.exemple.app", 4321)
            val viewModel = viewModel()
            avancer()
            pont.pousser("com.exemple.app", 4321, listOf(ligne("avant pause")))
            avancer()

            viewModel.basculerPause()
            assertTrue(viewModel.etat.value.enPause)
            pont.pousser("com.exemple.app", 4321, listOf(ligne("pendant pause 1"), ligne("pendant pause 2")))
            avancer()

            // Figer : la collecte continue (registre) mais le tampon
            // d'affichage n'a PAS bougé.
            assertEquals(
                listOf("avant pause"),
                viewModel.etat.value.lignes
                    .map { it.ligne.message },
            )

            viewModel.basculerPause()
            avancer()
            assertFalse(viewModel.etat.value.enPause)
            // Reprise : RATTRAPAGE — les lignes de la pause apparaissent.
            assertEquals(
                listOf("avant pause", "pendant pause 1", "pendant pause 2"),
                viewModel.etat.value.lignes
                    .map { it.ligne.message },
            )
        }

    @Test
    fun `effacer vide l affichage mais les nouvelles lignes reviennent`() =
        runTest {
            pont.ouvrir("com.exemple.app", 4321)
            val viewModel = viewModel()
            avancer()
            pont.pousser("com.exemple.app", 4321, listOf(ligne("ancienne")))
            avancer()

            viewModel.effacer()
            assertTrue(
                viewModel.etat.value.lignes
                    .isEmpty(),
            )
            // Les sessions gardées ne sont PAS touchées.
            assertEquals(
                1,
                viewModel.etat.value.sessions[0]
                    .nombreLignes,
            )

            pont.pousser("com.exemple.app", 4321, listOf(ligne("nouvelle")))
            avancer()
            assertEquals(
                listOf("nouvelle"),
                viewModel.etat.value.lignes
                    .map { it.ligne.message },
            )
        }

    @Test
    fun `le filtre texte s applique sans toucher le tampon`() =
        runTest {
            pont.ouvrir("com.exemple.app", 4321)
            val viewModel = viewModel()
            avancer()
            pont.pousser(
                "com.exemple.app",
                4321,
                listOf(ligne("rotation demandée"), ligne("mesure reprise"), ligne("rotation finie")),
            )
            avancer()

            viewModel.definirFiltreTexte("rotation", regex = false)
            avancer()

            assertEquals(
                listOf("rotation demandée", "rotation finie"),
                viewModel.etat.value.lignes
                    .map { it.ligne.message },
            )

            // Retirer le filtre rend TOUT le tampon (rien n'est jeté).
            viewModel.definirFiltreTexte("", regex = false)
            assertEquals(3, viewModel.etat.value.lignes.size)
        }

    @Test
    fun `le filtre de niveau masque les niveaux inferieurs`() =
        runTest {
            pont.ouvrir("com.exemple.app", 4321)
            val viewModel = viewModel()
            avancer()
            pont.pousser(
                "com.exemple.app",
                4321,
                listOf(
                    ligne("bruit", niveau = NiveauJournal.DEBOGAGE),
                    ligne("alerte", niveau = NiveauJournal.AVERTISSEMENT),
                ),
            )
            avancer()

            viewModel.definirNiveauMinimal(NiveauJournal.AVERTISSEMENT)
            assertEquals(
                listOf("alerte"),
                viewModel.etat.value.lignes
                    .map { it.ligne.message },
            )
        }

    @Test
    fun `un motif regex invalide est signale en etat`() =
        runTest {
            val viewModel = viewModel()

            viewModel.definirFiltreTexte("*debut", regex = true)

            assertTrue(viewModel.etat.value.erreurMotif)
        }

    @Test
    fun `une session terminee est archivee une seule fois avec sa derniere ligne`() =
        runTest {
            pont.ouvrir("com.exemple.app", 4321)
            val viewModel = viewModel()
            avancer()
            pont.pousser("com.exemple.app", 4321, listOf(ligne("première"), ligne("dernière")))
            avancer()

            pont.terminer("com.exemple.app", 4321, "le processus s'est arrêté")
            avancer()
            // Ré-émissions de l'état des sessions : UNE archive, pas deux.
            pont.reemettre()
            avancer()

            assertEquals(1, archive.archivees.size)
            assertEquals("dernière", archive.archivees[0].derniereLigne?.message)
            assertEquals(
                "le processus s'est arrêté",
                viewModel.etat.value.fin
                    ?.raison,
            )
            assertTrue(viewModel.etat.value.lignesPerdues >= 0L)
            // Le bandeau de fin est porté par l'état (l'afficheur le rend).
            assertNotNull(viewModel.etat.value.fin)
        }

    @Test
    fun `les pertes annoncees paraissent dans l etat`() =
        runTest {
            pont.ouvrir("com.exemple.app", 4321)
            val viewModel = viewModel()
            avancer()

            pont.pertes("com.exemple.app", 4321, 214L)
            avancer()

            assertEquals(214L, viewModel.etat.value.lignesPerdues)
        }

    // ------------------------------------------------------------------
    // Doubles de test.
    // ------------------------------------------------------------------

    /** Faux du pont : sessions, tampon par identifiant, delta incrémental
     *  et réémission de l'état à chaque lot (le signal de rattrapage). */
    private class FauxPont : PontJournauxApplications {
        private val etatSessions = MutableStateFlow<List<SessionJournal>>(emptyList())
        private val tampons = HashMap<String, MutableList<LigneJournal>>()

        override val sessions: StateFlow<List<SessionJournal>> = etatSessions.asStateFlow()

        override fun lignes(idSession: String): Flow<LotJournal> = emptyFlow()

        override fun instantane(idSession: String): List<LigneJournal> = tampons[idSession]?.toList() ?: emptyList()

        override fun lignesDepuis(
            idSession: String,
            position: Int,
        ): LotJournal {
            val tampon = tampons[idSession] ?: return LotJournal(0, emptyList())
            val lignes = if (position >= tampon.size) emptyList() else tampon.subList(position, tampon.size).toList()
            return LotJournal(tampon.size, lignes)
        }

        fun ouvrir(
            paquet: String,
            pid: Int,
        ): String {
            val id = "$paquet/$pid"
            tampons[id] = mutableListOf()
            etatSessions.value =
                etatSessions.value.filter { it.id != id } +
                SessionJournal(
                    id = id,
                    identite = IdentiteSessionJournal(paquet, pid, paquet),
                    etat = EtatSessionJournal.VIVANTE,
                    fin = null,
                    nombreLignes = 0,
                    lignesPerdues = 0,
                    sortiePrecedente = null,
                )
            return id
        }

        fun pousser(
            paquet: String,
            pid: Int,
            lignes: List<LigneJournal>,
        ) {
            val id = "$paquet/$pid"
            tampons.getValue(id).addAll(lignes)
            etatSessions.value =
                etatSessions.value.map { session ->
                    if (session.id == id) session.copy(nombreLignes = session.nombreLignes + lignes.size) else session
                }
        }

        fun terminer(
            paquet: String,
            pid: Int,
            raison: String,
        ) {
            val id = "$paquet/$pid"
            etatSessions.value =
                etatSessions.value.map { session ->
                    if (session.id == id) {
                        session.copy(etat = EtatSessionJournal.TERMINEE, fin = FinSessionJournal(raison, 2_000L))
                    } else {
                        session
                    }
                }
        }

        fun pertes(
            paquet: String,
            pid: Int,
            pertes: Long,
        ) {
            val id = "$paquet/$pid"
            etatSessions.value =
                etatSessions.value.map { session ->
                    if (session.id == id) session.copy(lignesPerdues = session.lignesPerdues + pertes) else session
                }
        }

        fun reemettre() {
            etatSessions.value = etatSessions.value.toList()
        }

        fun sessionArchivee(
            paquet: String,
            pid: Int,
            raison: String,
        ): SessionJournal =
            SessionJournal(
                id = "$paquet/$pid",
                identite = IdentiteSessionJournal(paquet, pid, paquet),
                etat = EtatSessionJournal.TERMINEE,
                fin = FinSessionJournal(raison, 1_500L),
                nombreLignes = 3,
                lignesPerdues = 0,
                sortiePrecedente = null,
            )
    }

    /** Faux de l'archive : en mémoire, la plus récente d'abord. */
    private class FauxArchive : ArchiveSessionsJournal {
        val archivees = mutableListOf<SessionJournalArchivee>()

        override suspend fun charger(): List<SessionJournalArchivee> = archivees.toList()

        override suspend fun archiver(
            session: SessionJournal,
            derniereLigne: LigneJournal?,
        ) {
            archivees.removeAll { it.session.id == session.id }
            archivees.add(0, SessionJournalArchivee(session, derniereLigne))
        }

        fun semer(archivee: SessionJournalArchivee) {
            archivees.add(archivee)
        }
    }
}
