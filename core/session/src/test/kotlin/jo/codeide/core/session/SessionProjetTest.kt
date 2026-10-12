package jo.codeide.core.session

import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Tests de [SessionProjet] — la machine à états observable de l'étape B
 * (prompt 3 §6-Étape B) : transitions `{Fermee, Ouverture, Ouverte,
 * Fermeture}` ; **fermeture pendant l'ouverture** ; **ouverture pendant
 * la fermeture** ; **réentrance** (refus typé, jamais d'interblocage) ;
 * **annulation de l'appelant** (état propre + exception relancée) ;
 * idempotence de la fermeture ; enregistrement selon l'état.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionProjetTest {
    private val projetA = ProjectId("projet-a")
    private val projetB = ProjectId("projet-b")

    private fun horlogeVirtuelle(): TimeProvider = TimeProvider { 0L }

    @Test
    fun `transitions nominal — Fermee Ouverture Ouverte Fermeture Fermee`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            var etatPendantTravaux: EtatSession? = null

            assertEquals(EtatSession.Fermee, session.etat.value)
            val ouverture =
                session.ouvrir(projetA) {
                    etatPendantTravaux = session.etat.value
                    AppResult.Success(Unit)
                }
            assertEquals(EtatSession.Ouverture(projetA), etatPendantTravaux)
            assertTrue(ouverture is AppResult.Success)
            assertEquals(EtatSession.Ouverte(projetA), session.etat.value)

            val ressource = FermableEspion("ressource", ordre)
            assertTrue(session.enregistrer(ressource, ms(1_000)) is AppResult.Success)

            val fermeture = session.fermer(RaisonFermeture.Utilisateur)
            assertTrue(fermeture is AppResult.Success)
            assertEquals(EtatSession.Fermee, session.etat.value)
            assertEquals(1, ressource.terminees)
            assertEquals(RaisonFermeture.Utilisateur, ressource.dernierContexte?.raison)
        }

    @Test
    fun `ouvrir refuse quand une session vit deja (I1)`() =
        runTest {
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            session.ouvrir(projetA)

            val refuse = session.ouvrir(projetB)

            assertTrue(refuse is AppResult.Failure)
            val erreur = (refuse as AppResult.Failure).error
            assertTrue(erreur is AppError.Session)
            assertEquals(AppError.SessionReason.DejaOuverte, (erreur as AppError.Session).reason)
            assertEquals(EtatSession.Ouverte(projetA), session.etat.value)
        }

    @Test
    fun `fermer idempotent — rapport memorise, une seule liberation`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            val ressource = FermableEspion("ressource", ordre)
            session.ouvrir(projetA) {
                session.enregistrer(ressource, ms(1_000))
                AppResult.Success(Unit)
            }

            val premiere = session.fermer(RaisonFermeture.Utilisateur)
            val seconde = session.fermer(RaisonFermeture.Nettoyage)

            assertEquals(1, ressource.appels)
            assertTrue(premiere is AppResult.Success)
            assertTrue(seconde is AppResult.Success)
            assertEquals(
                (premiere as AppResult.Success).value,
                (seconde as AppResult.Success).value,
            )
            assertEquals(EtatSession.Fermee, session.etat.value)
        }

    @Test
    fun `echec applicatif des travaux — nettoyage complet et retour a Fermee`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            val premiere = FermableEspion("premiere", ordre)
            val seconde = FermableEspion("seconde", ordre)

            val resultat =
                session.ouvrir(projetA) {
                    session.enregistrer(premiere, ms(1_000))
                    session.enregistrer(seconde, ms(1_000))
                    AppResult.Failure(AppError.Unknown("refusé"))
                }

            assertTrue(resultat is AppResult.Failure)
            assertEquals(EtatSession.Fermee, session.etat.value)
            // ce que les travaux ont démarré est refermé, en ordre inverse
            assertEquals(listOf("seconde", "premiere"), ordre)
            assertEquals(RaisonFermeture.Nettoyage, premiere.dernierContexte?.raison)
            // la session est RÉUTILISABLE après un échec d'ouverture
            val reouverture = session.ouvrir(projetB)
            assertTrue(reouverture is AppResult.Success)
            assertEquals(EtatSession.Ouverte(projetB), session.etat.value)
        }

    @Test
    fun `exception dans les travaux — nettoyage et echec type Unknown`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            val ressource = FermableEspion("ressource", ordre)

            val resultat =
                session.ouvrir(projetA) {
                    session.enregistrer(ressource, ms(1_000))
                    error("cassé")
                }

            assertTrue(resultat is AppResult.Failure)
            val erreur = (resultat as AppResult.Failure).error
            assertTrue(erreur is AppError.Unknown)
            assertTrue((erreur as AppError.Unknown).details.contains("cassé"))
            assertEquals(listOf("ressource"), ordre)
            assertEquals(EtatSession.Fermee, session.etat.value)
        }

    @Test
    fun `fermeture pendant l ouverture — attends la fin des travaux puis ferme tout`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            val ressource = FermableEspion("ressource", ordre)
            val rapportFermeture = AtomicReference<AppResult<RapportFermeture>?>(null)

            // la fermeture est demandée PENDANT que l'ouverture tient le verrou
            val fermeture =
                launch {
                    rapportFermeture.set(session.fermer(RaisonFermeture.Utilisateur))
                }
            val ouverture =
                session.ouvrir(projetA) {
                    delay(ms(100)) // l'ouverture est lente : la fermeture attend
                    session.enregistrer(ressource, ms(1_000))
                    AppResult.Success(Unit)
                }

            // l'ouverture a ABOUTI avant que la fermeture n'agisse (elle
            // attendait le verrou) : jamais de fermeture d'une session en
            // devenir, jamais de ressource non fermée
            assertTrue(ouverture is AppResult.Success)
            assertEquals(EtatSession.Ouverte(projetA), session.etat.value)
            fermeture.join()
            assertEquals(EtatSession.Fermee, session.etat.value)
            assertEquals(listOf("ressource"), ordre)
            val rapport = rapportFermeture.get() as? AppResult.Success
            assertNotNull(rapport)
            assertEquals(1, rapport?.value?.nbFermes)
        }

    @Test
    fun `ouverture pendant la fermeture — attends la fermeture puis ouvre`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            val ressourceA = FermableEspion("ressource-a", ordre, dureeTravail = ms(50))
            session.ouvrir(projetA) {
                session.enregistrer(ressourceA, ms(1_000))
                AppResult.Success(Unit)
            }
            var ouverturesB = 0

            val fermeture = launch { session.fermer(RaisonFermeture.ChangementDeProjet) }
            runCurrent() // la fermeture démarre (état Fermeture)
            val ouverture =
                launch {
                    ouverturesB++
                    session.ouvrir(projetB) { AppResult.Success(Unit) }
                }
            ouverture.join()
            fermeture.join()

            // ressource-a fermée AVANT l'ouverture de B (jamais deux sessions)
            assertEquals(listOf("ressource-a"), ordre.take(1))
            assertEquals(1, ouverturesB)
            assertEquals(EtatSession.Ouverte(projetB), session.etat.value)
            assertEquals(1, ressourceA.terminees)
        }

    @Test
    fun `reentrance — fermer et ouvrir rappeles depuis un Fermable refuses sans interblocage`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            val refus = mutableListOf<AppError.SessionReason>()

            val indiscret =
                object : Fermable {
                    override val nom: String = "indiscret"

                    override suspend fun fermer(contexte: ContexteFermeture) {
                        ordre += nom
                        val reprocheFermer = session.fermer(RaisonFermeture.Nettoyage)
                        val reprocheOuvrir = session.ouvrir(projetB)
                        if (reprocheFermer is AppResult.Failure && reprocheFermer.error is AppError.Session) {
                            refus += (reprocheFermer.error as AppError.Session).reason
                        }
                        if (reprocheOuvrir is AppResult.Failure && reprocheOuvrir.error is AppError.Session) {
                            refus += (reprocheOuvrir.error as AppError.Session).reason
                        }
                    }
                }
            session.ouvrir(projetA) {
                session.enregistrer(indiscret, ms(1_000))
                AppResult.Success(Unit)
            }

            // LE test : il doit TERMINER (aucun interblocage) et refuser proprement
            val fermeture = session.fermer(RaisonFermeture.Utilisateur)

            assertTrue(fermeture is AppResult.Success)
            assertEquals(listOf("indiscret"), ordre)
            assertEquals(
                listOf(AppError.SessionReason.Reentrance, AppError.SessionReason.Reentrance),
                refus,
            )
            assertEquals(EtatSession.Fermee, session.etat.value)
        }

    @Test
    fun `annulation pendant la fermeture — etat Fermee, non annulables fermes, exception relancee`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            val critique = FermableEspion("critique", ordre, dureeTravail = ms(5))
            val bloquant = FermableEspion("bloquant", ordre, attente = CompletableDeferred())
            session.ouvrir(projetA) {
                session.enregistrer(critique, ms(60_000), annulable = false)
                session.enregistrer(bloquant, ms(60_000), annulable = true)
                AppResult.Success(Unit)
            }
            val capture = AtomicReference<FermetureInterrompue?>(null)

            val appelant =
                launch {
                    try {
                        session.fermer(RaisonFermeture.Utilisateur)
                    } catch (interrompue: FermetureInterrompue) {
                        capture.set(interrompue)
                    }
                }
            runCurrent() // la fermeture démarre, bloquant suspend sur son signal
            appelant.cancelAndJoin()

            // I7 : le critique (non annulable) est fermé, l'état est PROPRE
            assertEquals(EtatSession.Fermee, session.etat.value)
            assertEquals(1, critique.terminees)
            assertEquals(1, bloquant.appels)
            assertEquals(0, bloquant.terminees)
            // l'annulation est RELANCÉE, avec le rapport partiel (règle projet)
            val interrompue = capture.get()
            assertNotNull(interrompue)
            assertEquals(1, interrompue?.rapport?.nbAnnules)
            assertEquals(1, interrompue?.rapport?.nbFermes)
            // la session reste réutilisable après une fermeture interrompue
            assertTrue(session.ouvrir(projetB) is AppResult.Success)
        }

    @Test
    fun `enregistrement refuse selon l etat — Fermee et Fermeture`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })

            // Fermee : personne ne porte la ressource
            val refuseFermee = session.enregistrer(FermableEspion("orpheline", ordre), ms(1_000))
            assertTrue(refuseFermee is AppResult.Failure)
            assertEquals(
                AppError.SessionReason.AucuneSession,
                ((refuseFermee as AppResult.Failure).error as AppError.Session).reason,
            )

            // Fermeture : une ressource tente de s'enregistrer pendant la
            // fermeture en cours (via la libération d'une autre)
            val refuses = mutableListOf<AppError.SessionReason>()
            val tentateur =
                object : Fermable {
                    override val nom: String = "tentateur"

                    override suspend fun fermer(contexte: ContexteFermeture) {
                        ordre += nom
                        val resultat = session.enregistrer(FermableEspion("tardive", ordre), ms(1_000))
                        if (resultat is AppResult.Failure && resultat.error is AppError.Session) {
                            refuses += (resultat.error as AppError.Session).reason
                        }
                    }
                }
            session.ouvrir(projetA) {
                session.enregistrer(tentateur, ms(1_000))
                AppResult.Success(Unit)
            }
            val fermeture = session.fermer(RaisonFermeture.Utilisateur)

            assertTrue(fermeture is AppResult.Success)
            assertEquals(listOf(AppError.SessionReason.FermetureEnCours), refuses)
            assertEquals(listOf("tentateur"), ordre) // la « tardive » n'existe pas
        }

    @Test
    fun `deux fermetures concurrentes — meme rapport, une seule liberation`() =
        runTest {
            val ordre = mutableListOf<String>()
            val session = SessionProjet(horloge = TimeProvider { testScheduler.currentTime })
            val ressource = FermableEspion("ressource", ordre, dureeTravail = ms(50))
            session.ouvrir(projetA) {
                session.enregistrer(ressource, ms(1_000))
                AppResult.Success(Unit)
            }
            val rapports = mutableListOf<RapportFermeture>()
            val premiere =
                async {
                    (session.fermer(RaisonFermeture.Utilisateur) as AppResult.Success).also { rapports += it.value }
                }
            val seconde =
                async {
                    (session.fermer(RaisonFermeture.Utilisateur) as AppResult.Success).also { rapports += it.value }
                }
            premiere.await()
            seconde.await()

            assertEquals(1, ressource.appels)
            assertEquals(2, rapports.size)
            assertEquals(rapports[0], rapports[1]) // mémoïsation : même rapport
            assertEquals(EtatSession.Fermee, session.etat.value)
        }

    @Test
    fun `fermer une session jamais ouverte — reussite avec rapport vide`() =
        runTest {
            val session = SessionProjet(horloge = horlogeVirtuelle())

            val fermeture = session.fermer(RaisonFermeture.Nettoyage)

            assertTrue(fermeture is AppResult.Success)
            assertTrue((fermeture as AppResult.Success).value.elements.isEmpty())
            assertEquals(Duration.ZERO, fermeture.value.dureeTotale)
        }
}
