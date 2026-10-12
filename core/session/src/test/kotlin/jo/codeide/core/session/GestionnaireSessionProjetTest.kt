package jo.codeide.core.session

import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du [GestionnaireSessionProjet] — le garant d'**I1** (prompt 3 §4 :
 * au plus UNE session de projet active par processus) : ouvrir B pendant
 * que A vit FERME A D'ABORD (séquentiellement, délais compris) puis ouvre
 * B ; idempotence sur le même projet ; deux demandes d'ouverture
 * simultanées sérialisées ; ouverture pendant une fermeture en vol ;
 * réentrance depuis un Fermable refusée sans interblocage.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GestionnaireSessionProjetTest {
    private val projetA = ProjectId("projet-a")
    private val projetB = ProjectId("projet-b")
    private val projetC = ProjectId("projet-c")

    @Test
    fun `I1 — ouvrir B ferme A d abord puis ouvre B`() =
        runTest {
            val ordre = mutableListOf<String>()
            val gestionnaire =
                GestionnaireSessionProjet(
                    horloge = TimeProvider { testScheduler.currentTime },
                )
            val ressourceA = FermableEspion("ressource-a", ordre)

            gestionnaire.ouvrir(projetA) {
                gestionnaire.session.enregistrer(ressourceA, ms(1_000))
                ordre += "travaux-a"
                AppResult.Success(Unit)
            }

            assertEquals(EtatSession.Ouverte(projetA), gestionnaire.etat.value)

            val ressourceB = FermableEspion("ressource-b", mutableListOf())
            gestionnaire.ouvrir(projetB) {
                ordre += "travaux-b"
                gestionnaire.session.enregistrer(ressourceB, ms(1_000))
                AppResult.Success(Unit)
            }

            // PREUVE I1 : la ressource de A est fermée AVANT que les travaux
            // de B ne démarrent — jamais deux sessions vivantes
            assertEquals(listOf("travaux-a", "ressource-a", "travaux-b"), ordre)
            assertEquals(EtatSession.Ouverte(projetB), gestionnaire.etat.value)
            assertEquals(1, ressourceA.terminees)
            assertEquals(RaisonFermeture.ChangementDeProjet, ressourceA.dernierContexte?.raison)
            assertEquals(0, ressourceB.appels) // B vit, sa ressource n'est pas fermée
        }

    @Test
    fun `ouvrir le projet deja ouvert est un no-op — travaux non rejoues`() =
        runTest {
            val gestionnaire =
                GestionnaireSessionProjet(
                    horloge = TimeProvider { testScheduler.currentTime },
                )
            var ouvertures = 0

            gestionnaire.ouvrir(projetA) {
                ouvertures++
                AppResult.Success(Unit)
            }
            val seconde =
                gestionnaire.ouvrir(projetA) {
                    ouvertures++
                    AppResult.Success(Unit)
                }

            assertTrue(seconde is AppResult.Success)
            assertEquals(1, ouvertures)
            assertEquals(EtatSession.Ouverte(projetA), gestionnaire.etat.value)
        }

    @Test
    fun `deux ouvertures simultanees de projets differents — serializees, une seule session finale`() =
        runTest {
            val ordre = mutableListOf<String>()
            val gestionnaire =
                GestionnaireSessionProjet(
                    horloge = TimeProvider { testScheduler.currentTime },
                )
            val ressourceA = FermableEspion("ressource-a", ordre)

            val premiere =
                launch {
                    gestionnaire.ouvrir(projetA) {
                        gestionnaire.session.enregistrer(ressourceA, ms(1_000))
                        ordre += "travaux-a"
                        AppResult.Success(Unit)
                    }
                }
            val seconde =
                launch {
                    gestionnaire.ouvrir(projetB) {
                        ordre += "travaux-b"
                        AppResult.Success(Unit)
                    }
                }
            premiere.join()
            seconde.join()

            // cas limite §9 « deux demandes d'ouverture simultanées » : la
            // première ouvre A, la seconde BASCULE (ferme A puis ouvre B)
            assertEquals(listOf("travaux-a", "ressource-a", "travaux-b"), ordre)
            assertEquals(EtatSession.Ouverte(projetB), gestionnaire.etat.value)
            assertEquals(1, ressourceA.terminees)
        }

    @Test
    fun `ouverture pendant une fermeture en vol — attends la fin puis ouvre`() =
        runTest {
            val ordre = mutableListOf<String>()
            val gestionnaire =
                GestionnaireSessionProjet(
                    horloge = TimeProvider { testScheduler.currentTime },
                )
            val ressourceA = FermableEspion("ressource-a", ordre, dureeTravail = ms(50))
            gestionnaire.ouvrir(projetA) {
                gestionnaire.session.enregistrer(ressourceA, ms(1_000))
                AppResult.Success(Unit)
            }

            // fermeture DIRECTE (hors gestionnaire) lente : état Fermeture
            // observable pendant que le gestionnaire reçoit une ouverture
            val fermeture = launch { gestionnaire.session.fermer(RaisonFermeture.Nettoyage) }
            runCurrent() // la fermeture démarre : état Fermeture en vol

            val resultat = gestionnaire.ouvrir(projetB) { AppResult.Success(Unit) }
            fermeture.join()

            assertTrue(resultat is AppResult.Success)
            assertEquals(EtatSession.Ouverte(projetB), gestionnaire.etat.value)
            assertEquals(1, ressourceA.terminees)
        }

    @Test
    fun `reentrance depuis un Fermable — gestionnaire refuse sans interblocage`() =
        runTest {
            val ordre = mutableListOf<String>()
            val gestionnaire =
                GestionnaireSessionProjet(
                    horloge = TimeProvider { testScheduler.currentTime },
                )
            val refus = mutableListOf<AppError.SessionReason>()
            val indiscret =
                object : Fermable {
                    override val nom: String = "indiscret"

                    override suspend fun fermer(contexte: ContexteFermeture) {
                        ordre += nom
                        val reprocheFermer = gestionnaire.fermer(RaisonFermeture.Nettoyage)
                        val reprocheOuvrir = gestionnaire.ouvrir(projetC)
                        if (reprocheFermer is AppResult.Failure && reprocheFermer.error is AppError.Session) {
                            refus += (reprocheFermer.error as AppError.Session).reason
                        }
                        if (reprocheOuvrir is AppResult.Failure && reprocheOuvrir.error is AppError.Session) {
                            refus += (reprocheOuvrir.error as AppError.Session).reason
                        }
                    }
                }
            gestionnaire.ouvrir(projetA) {
                gestionnaire.session.enregistrer(indiscret, ms(1_000))
                AppResult.Success(Unit)
            }

            // LE test : il doit TERMINER (le gestionnaire tient son verrou
            // pendant la fermeture — sans la garde, interblocage garanti)
            val fermeture = gestionnaire.fermer(RaisonFermeture.Utilisateur)

            assertTrue(fermeture is AppResult.Success)
            assertEquals(
                listOf(AppError.SessionReason.Reentrance, AppError.SessionReason.Reentrance),
                refus,
            )
            assertEquals(listOf("indiscret"), ordre)
            assertEquals(EtatSession.Fermee, gestionnaire.etat.value)
        }

    @Test
    fun `fermer sans session ouverte — no-op reussi`() =
        runTest {
            val gestionnaire =
                GestionnaireSessionProjet(
                    horloge = TimeProvider { testScheduler.currentTime },
                )

            val fermeture = gestionnaire.fermer(RaisonFermeture.Nettoyage)

            assertTrue(fermeture is AppResult.Success)
            assertTrue((fermeture as AppResult.Success).value.elements.isEmpty())
            assertEquals(EtatSession.Fermee, gestionnaire.etat.value)
        }

    @Test
    fun `fermer ferme la session unique et rapporte chaque element`() =
        runTest {
            val ordre = mutableListOf<String>()
            val gestionnaire =
                GestionnaireSessionProjet(
                    horloge = TimeProvider { testScheduler.currentTime },
                )
            val premiere = FermableEspion("premiere", ordre)
            val seconde = FermableEspion("seconde", ordre, dureeTravail = ms(7))
            gestionnaire.ouvrir(projetA) {
                gestionnaire.session.enregistrer(premiere, ms(1_000))
                gestionnaire.session.enregistrer(seconde, ms(1_000))
                AppResult.Success(Unit)
            }

            val fermeture = gestionnaire.fermer(RaisonFermeture.Utilisateur)

            assertTrue(fermeture is AppResult.Success)
            val rapport = (fermeture as AppResult.Success).value
            assertEquals(listOf("seconde", "premiere"), ordre)
            assertEquals(2, rapport.nbFermes)
            assertEquals(ms(7), rapport.elements.first().duree)
            assertEquals(RaisonFermeture.Utilisateur, rapport.raison)
            assertEquals(EtatSession.Fermee, gestionnaire.etat.value)
        }

    @Test
    fun `etat du gestionnaire — le flux de LA session unique, observable en continu`() =
        runTest {
            val gestionnaire =
                GestionnaireSessionProjet(
                    horloge = TimeProvider { testScheduler.currentTime },
                )
            // l'observateur (ViewModel de l'étape C) garde SON abonnement à
            // travers les transitions : la session est RÉUTILISÉE (I1)
            val etats = mutableListOf<EtatSession>()
            val observateur = launch { gestionnaire.etat.collect { etats += it } }
            runCurrent() // l'observateur capte l'état initial (Fermee)

            gestionnaire.ouvrir(projetA) { AppResult.Success(Unit) }
            gestionnaire.ouvrir(projetB) { AppResult.Success(Unit) }
            gestionnaire.fermer(RaisonFermeture.Utilisateur)

            assertEquals(EtatSession.Fermee, gestionnaire.etat.value)
            // même flux sous-jacent : l'état du gestionnaire EST celui de la
            // session unique — la preuve tient à l'égalité des VALEURS vives
            assertEquals(gestionnaire.session.etat.value, gestionnaire.etat.value)
            assertTrue(etats.isNotEmpty())
            observateur.cancel()
        }

    @Test
    fun `changement de projet en pleine ouverture — ferme l ouverture en vol puis ouvre`() =
        runTest {
            val ordre = mutableListOf<String>()
            val gestionnaire =
                GestionnaireSessionProjet(
                    horloge = TimeProvider { testScheduler.currentTime },
                )
            val ressourceA = FermableEspion("ressource-a", ordre)

            val premiere =
                launch {
                    gestionnaire.ouvrir(projetA) {
                        delay(ms(100))
                        gestionnaire.session.enregistrer(ressourceA, ms(1_000))
                        ordre += "travaux-a"
                        AppResult.Success(Unit)
                    }
                }
            runCurrent() // l'ouverture de A démarre (état Ouverture, travaux lents)
            val seconde =
                gestionnaire.ouvrir(projetB) {
                    ordre += "travaux-b"
                    AppResult.Success(Unit)
                }
            premiere.join()

            // l'ouverture de A a été MENÉE À TERME (le gestionnaire attend),
            // PUIS fermée, PUIS B ouvert — jamais de session écrasée
            assertTrue(seconde is AppResult.Success)
            assertEquals(listOf("travaux-a", "ressource-a", "travaux-b"), ordre)
            assertEquals(EtatSession.Ouverte(projetB), gestionnaire.etat.value)
        }
}
