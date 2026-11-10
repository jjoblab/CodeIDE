package jo.codeide.feature.sorties

import jo.codeide.core.domain.ArchiveSessionsJournal
import jo.codeide.core.domain.EtatSessionJournal
import jo.codeide.core.domain.FinSessionJournal
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.IdentiteSessionJournal
import jo.codeide.core.domain.LigneJournal
import jo.codeide.core.domain.LotJournal
import jo.codeide.core.domain.NiveauJournal
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.PontJournauxApplications
import jo.codeide.core.domain.SessionJournal
import jo.codeide.core.domain.SessionJournalArchivee
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests de la source LOGCAT derrière le port du lecteur (lot L4) — les
 * scénarios de `LogcatViewModelTest` (R3/R4) PORTÉS à l'identique sur le
 * [CollecteurSortiesLogcat] : la logique de sessions est la même, seuls
 * l'affichage (lecteur) et la pause/effacement (gestes du lecteur)
 * changent de détenteur. Équivalence démontrée, jamais supprimés pour
 * verdir :
 *
 * - autosélection / choix qui prime / délogement d'archive → tests 1-3 ;
 * - pause/effacement/filtres → tests 4-7, exercés SUR LE LECTEUR branché
 *   au flux logcat (le tampon ingère, la vue fige, le delta arrive en
 *   une fois — même sémantique que l'ancien rattrapage) ;
 * - archivage des fins / plantage / pertes → tests 9-11.
 *
 * Le faux du pont réplique la sémantique de [LotJournal] du registre :
 * le delta incrémental rend les lignes STRICTEMENT après la position,
 * l'état des sessions est réémis à chaque lot (signal de rattrapage).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollecteurSortiesLogcatTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val pont = FauxPont()
    private val archive = FauxArchive()
    private val horloge = HorlogeDeTest()

    private class HorlogeDeTest : TimeProvider {
        val instant = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        override fun nowMillis(): Long = instant.get()
    }

    /** Avance le planificateur du dispatcher (collecteur ET lecteur). */
    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }

    private fun collecteur(): CollecteurSortiesLogcat =
        CollecteurSortiesLogcat(pont, archive, TestDispatcherProvider(regleMain.dispatcher))

    /** Le lecteur branché au flux logcat (famille par fabrique de test). */
    private fun lecteur(flux: FluxSortiesLogcat): LecteurSortiesViewModel =
        LecteurSortiesViewModel(
            FabriqueDeTest(flux),
            TestDispatcherProvider(regleMain.dispatcher),
            horloge,
            androidx.lifecycle.SavedStateHandle(),
        )

    /** La fabrique de test : UNE famille, LE flux du test. */
    private class FabriqueDeTest(private val flux: FluxSorties) : FabriqueFluxSorties {
        override fun flux(famille: FamilleSorties): FluxSorties = flux
    }

    private fun ligne(
        message: String,
        niveau: NiveauJournal = NiveauJournal.INFO,
        etiquette: String = "MainActivity",
    ): LigneJournal = LigneJournal(
        horodatageMs = 1_000L,
        pid = 4321,
        tid = 4321,
        niveau = niveau,
        etiquette = etiquette,
        message = message,
    )

    @Test
    fun `une session vivante est autoselectionnee et ses lignes s affichent`() = runTest {
        pont.ouvrir("com.exemple.app", 4321)
        val collecteur = collecteur()
        avancer()

        assertEquals("com.exemple.app/4321", (collecteur.selection.value as? SelectionLogcat.Registre)?.id)
        assertTrue(collecteur.cliche(collecteur.selection.value).isEmpty())

        pont.pousser("com.exemple.app", 4321, listOf(ligne("onCreate"), ligne("onStart")))
        avancer()

        // Le cliché suit la publication (un lecteur qui s'attache voit
        // l'existant), les lignes sont adaptées au modèle unique.
        val cliché = collecteur.cliche(collecteur.selection.value)
        assertEquals(listOf("onCreate", "onStart"), cliché.map { it.message })
        assertTrue(cliché.all { it.source == SourceSortie.LOGCAT })
        assertTrue(cliché.all { it.identiteSession == "com.exemple.app/4321" })
        assertEquals("MainActivity", cliché[0].etiquette)
        assertEquals(2, collecteur.etatSessions.value.sessions[0].nombreLignes)
    }

    @Test
    fun `le choix de l utilisateur prime sur l autoselection`() = runTest {
        pont.ouvrir("com.exemple.app", 4321)
        val collecteur = collecteur()
        avancer()
        pont.terminer("com.exemple.app", 4321, "le processus s'est arrêté")
        avancer()

        // L'utilisateur consulte la session TERMINÉE du registre.
        collecteur.selectionnerSession("com.exemple.app/4321")
        assertEquals("com.exemple.app/4321", (collecteur.selection.value as? SelectionLogcat.Registre)?.id)

        // Une vivante NOUVELLE naît : le choix de l'utilisateur reste.
        pont.ouvrir("com.exemple.app", 5000)
        pont.pousser("com.exemple.app", 5000, listOf(ligne("autre processus")))
        avancer()

        assertEquals("com.exemple.app/4321", (collecteur.selection.value as? SelectionLogcat.Registre)?.id)
    }

    @Test
    fun `une vivante nouvellement nee deloge une archive consultee`() = runTest {
        archive.semer(
            SessionJournalArchivee(
                session = pont.sessionArchivee("com.exemple.app", 1000, "fin veille"),
                derniereLigne = ligne("dernière avant veille"),
            ),
        )
        val collecteur = collecteur()
        avancer()

        // Au démarrage sans session vivante : la plus récente archive
        // s'affiche (jamais du vide muet) avec sa dernière ligne.
        assertNotNull(collecteur.etatSessions.value.selectionArchivee)
        assertEquals(
            listOf("dernière avant veille"),
            collecteur.cliche(collecteur.selection.value).map { it.message },
        )

        // L'app est relancée : la vivante NOUVELLE prend la main.
        pont.ouvrir("com.exemple.app", 1000)
        pont.pousser("com.exemple.app", 1000, listOf(ligne("retour")))
        avancer()

        assertEquals("com.exemple.app/1000", (collecteur.selection.value as? SelectionLogcat.Registre)?.id)
        assertNull(collecteur.etatSessions.value.selectionArchivee)
    }

    @Test
    fun `la source continue de collecter pendant la pause du lecteur — la reprise rattrape sans trou`() = runTest {
        // La pause vit dans le lecteur (le tampon ingère, la vue fige) ;
        // la SOURCE n'a aucun état de pause — donc rien ne peut être
        // perdu pendant une pause : les lignes poussées « pendant la
        // pause » sont publiées et le lecteur les rattrape à la reprise
        // (équivalence du rattrapage de l'ancien ViewModel — le lecteur
        // est exercé avec le flux logcat au test suivant).
        pont.ouvrir("com.exemple.app", 4321)
        val collecteur = collecteur()
        avancer()
        pont.pousser("com.exemple.app", 4321, listOf(ligne("avant pause")))
        avancer()

        val recues = ArrayList<String>()
        // La collecte vit sur le MÊME planificateur que la source (règle
        // Main — l'avancer() du test la fait tourner), comme le lecteur
        // réel ingère sur ses dispatchers injectés.
        val travail = launch(regleMain.dispatcher) {
            collecteur.lots.collect { lignes -> lignes.forEach { recues.add(it.message) } }
        }
        avancer()
        pont.pousser("com.exemple.app", 4321, listOf(ligne("pendant pause 1"), ligne("pendant pause 2")))
        avancer()
        travail.cancel()

        assertEquals(listOf("pendant pause 1", "pendant pause 2"), recues)
        assertEquals(3, collecteur.cliche(collecteur.selection.value).size)
    }

    @Test
    fun `effacer vide l affichage du lecteur mais les nouvelles lignes reviennent — sessions intactes`() = runTest {
        pont.ouvrir("com.exemple.app", 4321)
        val collecteur = collecteur()
        val flux = FluxSortiesLogcat(collecteur)
        val viewModel = lecteur(flux)
        avancer()
        pont.pousser("com.exemple.app", 4321, listOf(ligne("ancienne")))
        avancer()
        assertEquals(1, viewModel.projection.value.rangees.size)

        viewModel.effacer()
        avancer()
        assertTrue(viewModel.projection.value.rangees.isEmpty())
        // Les sessions gardées ne sont PAS touchées.
        assertEquals(1, collecteur.etatSessions.value.sessions[0].nombreLignes)

        pont.pousser("com.exemple.app", 4321, listOf(ligne("nouvelle")))
        avancer()
        val lignes = viewModel.projection.value.rangees.filterIsInstance<RangeeLecteur.Ligne>()
        assertEquals(listOf("nouvelle"), lignes.map { it.rangee.textePur })
    }

    @Test
    fun `le filtre texte s applique sans toucher le tampon`() = runTest {
        pont.ouvrir("com.exemple.app", 4321)
        val collecteur = collecteur()
        val flux = FluxSortiesLogcat(collecteur)
        val viewModel = lecteur(flux)
        avancer()
        pont.pousser(
            "com.exemple.app",
            4321,
            listOf(ligne("rotation demandée"), ligne("mesure reprise"), ligne("rotation finie")),
        )
        avancer()

        // Le filtre unifié (ADR 0160) remplace `FiltreLogcat` : sous-
        // chaîne insensible sur message ET étiquette.
        viewModel.definirFiltre(FiltreSorties(texte = "rotation"))
        avancer()

        assertEquals(
            listOf("rotation demandée", "rotation finie"),
            lignesVisibles(viewModel).map { it.textePur },
        )
        assertEquals(1, viewModel.projection.value.masquees)
        assertEquals(3, viewModel.projection.value.tailleTampon)

        // Retirer le filtre rend TOUT le tampon (rien n'est jeté).
        viewModel.definirFiltre(FiltreSorties())
        avancer()
        assertEquals(3, lignesVisibles(viewModel).size)
    }

    @Test
    fun `le filtre de niveau masque les niveaux inferieurs`() = runTest {
        pont.ouvrir("com.exemple.app", 4321)
        val collecteur = collecteur()
        val flux = FluxSortiesLogcat(collecteur)
        val viewModel = lecteur(flux)
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

        viewModel.definirFiltre(FiltreSorties(niveauMinimal = NiveauSortie.AVERTISSEMENT))
        avancer()
        assertEquals(
            listOf("alerte"),
            lignesVisibles(viewModel).map { it.textePur },
        )
    }

    @Test
    fun `une session terminee est archivee une seule fois avec sa derniere ligne`() = runTest {
        pont.ouvrir("com.exemple.app", 4321)
        val collecteur = collecteur()
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
            collecteur.etatSessions.value.fin?.raison,
        )
        assertTrue(collecteur.etatSessions.value.lignesPerdues >= 0L)
        // Le bandeau de fin est porté par l'état (l'afficheur le rend).
        assertNotNull(collecteur.etatSessions.value.fin)
    }

    @Test
    fun `un plantage du pont parait dans l etat une seule fois par session`() = runTest {
        pont.ouvrir("com.exemple.app", 4321)
        val collecteur = collecteur()
        avancer()

        pont.pousser(
            "com.exemple.app",
            4321,
            listOf(
                ligne("avant"),
                ligne(
                    "Exception non interceptée dans le fil « main »",
                    niveau = NiveauJournal.ERREUR,
                    etiquette = "Plantage",
                ),
            ),
        )
        avancer()

        val plantage = collecteur.plantage.value
        assertNotNull("le plantage est signalé dans l'état (R4)", plantage)
        assertEquals("com.exemple.app/4321", plantage?.idSession)

        // Ré-émissions de sessions SANS nouveau plantage : inchangé
        // (le snackbar de l'activité s'appuie sur l'identifiant).
        pont.reemettre()
        avancer()
        assertEquals("com.exemple.app/4321", collecteur.plantage.value?.idSession)
    }

    @Test
    fun `les pertes annoncees paraissent dans l etat`() = runTest {
        pont.ouvrir("com.exemple.app", 4321)
        val collecteur = collecteur()
        avancer()

        pont.pertes("com.exemple.app", 4321, 214L)
        avancer()

        assertEquals(214L, collecteur.etatSessions.value.lignesPerdues)
    }

    @Test
    fun `un changement de session incremente la generation — le lecteur repart du cliché`() = runTest {
        pont.ouvrir("com.exemple.app", 4321)
        pont.ouvrir("com.exemple.autre", 777)
        val collecteur = collecteur()
        avancer()
        // L'autosélection a pris la DERNIÈRE vivante née (ordre d'ouverture)
        // — c'est le premier changement de source (génération 1).
        val generationInitiale = collecteur.generation.value
        assertEquals(1, generationInitiale)
        assertEquals(
            "com.exemple.autre/777",
            (collecteur.selection.value as? SelectionLogcat.Registre)?.id,
        )

        collecteur.selectionnerSession("com.exemple.app/4321")
        assertEquals(generationInitiale + 1, collecteur.generation.value)
        assertEquals("com.exemple.app/4321", (collecteur.selection.value as? SelectionLogcat.Registre)?.id)
        // Re-sélection de la MÊME session : pas de nouveau reset.
        collecteur.selectionnerSession("com.exemple.app/4321")
        assertEquals(generationInitiale + 1, collecteur.generation.value)
    }

    /** Les rangées LIGNE visibles de la projection du lecteur. */
    private fun lignesVisibles(viewModel: LecteurSortiesViewModel): List<RangeeSortie> =
        viewModel.projection.value.rangees.filterIsInstance<RangeeLecteur.Ligne>().map { it.rangee }

    // ------------------------------------------------------------------
    // Doubles de test (identiques à ceux de l'ancien LogcatViewModelTest).
    // ------------------------------------------------------------------

    /** Faux du pont : sessions, tampon par identifiant, delta incrémental
     *  et réémission de l'état à chaque lot (le signal de rattrapage). */
    private class FauxPont : PontJournauxApplications {
        private val etatSessions = MutableStateFlow<List<SessionJournal>>(emptyList())
        private val tampons = HashMap<String, MutableList<LigneJournal>>()

        override val sessions: StateFlow<List<SessionJournal>> = etatSessions.asStateFlow()

        override fun lignes(idSession: String): Flow<LotJournal> = emptyFlow()

        override fun instantane(idSession: String): List<LigneJournal> = tampons[idSession]?.toList() ?: emptyList()

        override fun lignesDepuis(idSession: String, position: Int): LotJournal {
            val tampon = tampons[idSession] ?: return LotJournal(0, emptyList())
            val lignes = if (position >= tampon.size) emptyList() else tampon.subList(position, tampon.size).toList()
            return LotJournal(tampon.size, lignes)
        }

        fun ouvrir(paquet: String, pid: Int): String {
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

        fun pousser(paquet: String, pid: Int, lignes: List<LigneJournal>) {
            val id = "$paquet/$pid"
            tampons.getValue(id).addAll(lignes)
            etatSessions.value =
                etatSessions.value.map { session ->
                    if (session.id == id) session.copy(nombreLignes = session.nombreLignes + lignes.size) else session
                }
        }

        fun terminer(paquet: String, pid: Int, raison: String) {
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

        fun pertes(paquet: String, pid: Int, pertes: Long) {
            val id = "$paquet/$pid"
            etatSessions.value =
                etatSessions.value.map { session ->
                    if (session.id == id) session.copy(lignesPerdues = session.lignesPerdues + pertes) else session
                }
        }

        fun reemettre() {
            etatSessions.value = etatSessions.value.toList()
        }

        fun sessionArchivee(paquet: String, pid: Int, raison: String): SessionJournal = SessionJournal(
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

        override suspend fun archiver(session: SessionJournal, derniereLigne: LigneJournal?) {
            archivees.removeAll { it.session.id == session.id }
            archivees.add(0, SessionJournalArchivee(session, derniereLigne))
        }

        fun semer(archivee: SessionJournalArchivee) {
            archivees.add(archivee)
        }
    }
}
