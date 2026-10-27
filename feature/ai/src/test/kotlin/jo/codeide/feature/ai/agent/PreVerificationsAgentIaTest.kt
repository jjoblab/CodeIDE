package jo.codeide.feature.ai.agent

import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiModelInfo
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.AiStopReason
import jo.codeide.core.ai.AppliqueurChangeSetIa
import jo.codeide.core.ai.DiagnosticsFichierIa
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.EtatSessionProjetVue
import jo.codeide.core.domain.ObservateurSessionProjet
import jo.codeide.core.domain.RegistreDossierProjetIa
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.SourceProjetHistorique
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeHistoriqueLocal
import jo.codeide.core.testing.TestDispatcherProvider
import jo.codeide.feature.ai.FakeCoffre
import jo.codeide.feature.ai.FakeReglagesIa
import jo.codeide.feature.ai.FauxFournisseurContexte
import jo.codeide.feature.ai.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des PRÉ-VÉRIFICATIONS de lancement de l'agent (A2, rapport IA,
 * lot 2) : le bouton « Lancer » ne part JAMAIS sans assistant actif +
 * consentement, clé du fournisseur actif, modèle CHOISI (le déguisement
 * `"modele-a-configurer"` est mort) et projet ouvert — chacune des
 * défaillances porte un MOTIF lisible et AUCUNE requête ne part au
 * fournisseur.
 *
 * Le contrôleur est testé en JVM pur grâce au port
 * [ServiceAgentIaPremierPlan] (double no-op) et au port
 * [ObservateurSessionProjet] (double à StateFlow pilotable).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // une classe par écran : les quatre défaillances y déploient le contrat.
class PreVerificationsAgentIaTest {
    /** Fournisseur espion : enregistre les requêtes, répond texte final
     *  ou l'ÉCHEC semé par le test (A3 — l'erreur typée voyage). */
    private class FournisseurEspion(private var echec: AppError? = null) : AiProvider {
        val requetes = mutableListOf<AiRequest>()

        override fun stream(request: AiRequest) = flow {
            requetes += request
            echec?.let {
                emit(AiEvent.Failure(it))
                return@flow
            }
            emit(AiEvent.TextDelta("Réponse immédiate."))
            emit(AiEvent.Done(AiStopReason.EndTurn))
        }

        override suspend fun listModels(): AppResult<List<AiModelInfo>> = AppResult.Success(emptyList())

        override suspend fun ping(): AppResult<Unit> = AppResult.Success(Unit)
    }

    /** Session pilotable — l'état change au fil des scénarios. */
    private class SessionPilotable(etatInitial: EtatSessionProjetVue) : ObservateurSessionProjet {
        private val interne = MutableStateFlow(etatInitial)

        override val etatSession: StateFlow<EtatSessionProjetVue> = interne

        fun basculer(etat: EtatSessionProjetVue) {
            interne.value = etat
        }
    }

    /** Service premier plan no-op (le port A2 rend le contrôleur testable). */
    private object ServiceNoOp : ServiceAgentIaPremierPlan {
        var demarrages = 0

        override fun demarrer() {
            demarrages++
        }

        override fun arreter() = Unit
    }

    /** Réglages complets : assistant actif, consentement, modèle posé. */
    private fun reglagesPrets() = FakeReglagesIa(
        AiSettings(
            assistantActive = true,
            consentementDonne = true,
            modeleAnthropic = "claude-test",
        ),
    )

    private suspend fun coffreAvecCle(): FakeCoffre = FakeCoffre().apply {
        enregistrer("anthropic", "cle".toCharArray())
    }

    @Suppress("LongParameterList") // fabrique de test : chaque paramètre pivote UN scénario.
    private fun controleur(
        horlogeTest: kotlinx.coroutines.test.TestCoroutineScheduler,
        reglages: ReglagesIaRepository = reglagesPrets(),
        coffre: FakeCoffre,
        session: SessionPilotable = SessionPilotable(
            EtatSessionProjetVue.Ouverte(ProjectId("alpha")),
        ),
        dossier: String? = "/projet/alpha",
        fournisseur: FournisseurEspion = FournisseurEspion(),
        editeur: RegistrePortEditeurActif = RegistrePortEditeurActif(),
        surRegistre: (RegistreDossierProjetIa) -> Unit = {},
    ): Pair<ControleurAgentIa, FournisseurEspion> {
        val registreDossier = RegistreDossierProjetIa()
        registreDossier.publier(dossier)
        surRegistre(registreDossier)
        val controleur =
            ControleurAgentIa(
                fournisseur = fournisseur,
                contextes = FauxFournisseurContexte(),
                reglages = reglages,
                dossierProjet = registreDossier,
                racineHistorique = SourceProjetHistorique(),
                appliqueur = AppliqueurChangeSetIa(FakeFileSystem(), FakeHistoriqueLocal(), TimeProvider { 0L }),
                demarreurService = ServiceNoOp,
                coffre = coffre,
                sessionProjet = session,
                dispatchers = TestDispatcherProvider(StandardTestDispatcher(horlogeTest)),
                horloge = TimeProvider { 0L },
                registreEditeur = editeur,
                sourceDiagnostics = { DiagnosticsFichierIa(cheminActif = null) },
            )
        return controleur to fournisseur
    }

    @Test
    fun `sans assistant actif, le lancement est refuse et AUCUNE requete ne part`() = runTest {
        val reglages = FakeReglagesIa(AiSettings(assistantActive = false, consentementDonne = false))
        val (controleur, fournisseur) = controleur(testScheduler, reglages = reglages, coffre = coffreAvecCle())

        controleur.definirObjectif("Fais quelque chose")
        controleur.lancer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.INACTIF, controleur.etat.value.statut)
        assertEquals(R.string.agent_preverif_assistant, controleur.etat.value.message?.ressource)
        assertEquals(0, fournisseur.requetes.size)
        assertFalse(controleur.etat.value.preVerifications.pret)
    }

    @Test
    fun `sans cle du fournisseur actif, le lancement est refuse et AUCUNE requete ne part`() = runTest {
        val (controleur, fournisseur) = controleur(testScheduler, coffre = FakeCoffre())

        controleur.definirObjectif("Fais quelque chose")
        controleur.lancer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.INACTIF, controleur.etat.value.statut)
        assertEquals(R.string.agent_preverif_cle, controleur.etat.value.message?.ressource)
        assertEquals(0, fournisseur.requetes.size)
        assertFalse(controleur.etat.value.preVerifications.clePresente)
    }

    @Test
    fun `sans modele choisi, le lancement est refuse - le deguisement modele-a-configurer est mort`() = runTest {
        val reglages =
            FakeReglagesIa(AiSettings(assistantActive = true, consentementDonne = true, modeleAnthropic = ""))
        val (controleur, fournisseur) = controleur(testScheduler, reglages = reglages, coffre = coffreAvecCle())

        controleur.definirObjectif("Fais quelque chose")
        controleur.lancer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.INACTIF, controleur.etat.value.statut)
        assertEquals(R.string.agent_preverif_modele, controleur.etat.value.message?.ressource)
        assertEquals(0, fournisseur.requetes.size)
        // Le déguisement n'existe plus : aucune requête ne porte
        // "modele-a-configurer".
        assertTrue(fournisseur.requetes.none { it.model == "modele-a-configurer" })
        assertFalse(controleur.etat.value.preVerifications.modeleChoisi)
    }

    @Test
    fun `session fermee, le lancement est refuse - le registre ne suffit plus`() = runTest {
        // Le registre PORTE encore le dossier (publication périmée) :
        // la SESSION fermée doit refuser le lancement — c'est la
        // source de vérité du projet ouvert (A2).
        val (controleur, fournisseur) =
            controleur(testScheduler, coffre = coffreAvecCle(), session = SessionPilotable(EtatSessionProjetVue.Aucune))

        controleur.definirObjectif("Fais quelque chose")
        controleur.lancer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.INACTIF, controleur.etat.value.statut)
        assertEquals(R.string.agent_preverif_projet, controleur.etat.value.message?.ressource)
        assertEquals(0, fournisseur.requetes.size)
        assertFalse(controleur.etat.value.preVerifications.projetOuvert)
    }

    @Test
    fun `session ouverte mais dossier non resolu, le lancement est refuse`() = runTest {
        val (controleur, fournisseur) = controleur(testScheduler, coffre = coffreAvecCle(), dossier = null)

        controleur.definirObjectif("Fais quelque chose")
        controleur.lancer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.INACTIF, controleur.etat.value.statut)
        assertEquals(R.string.agent_preverif_projet, controleur.etat.value.message?.ressource)
        assertEquals(0, fournisseur.requetes.size)
    }

    @Test
    fun `C1 - le chemin du projet qui atterrit TARD reactive la puce Projet sans repasser par l'ecran`() = runTest {
        // C1 (retour propriétaire) : l'utilisateur ouvre le tiroir AVANT
        // la fin de la résolution FUSE — l'écran Agent affiche « projet
        // non ouvert » ; le chemin atterrit (republication à
        // l'ouverture du tiroir) et la puce passe au VERT SANS que
        // l'écran soit quitté : le contrôleur COLLECTE les signaux.
        var registre: RegistreDossierProjetIa? = null
        val (controleur, _) =
            controleur(
                testScheduler,
                coffre = coffreAvecCle(),
                dossier = null,
                surRegistre = { registre = it },
            )
        // Premier rafraîchissement explicite (arrivée sur l'écran) :
        // la résolution n'est PAS terminée — puce Projet ROUGE.
        controleur.rafraichirPreVerifications()
        advanceUntilIdle()
        assertFalse(controleur.etat.value.preVerifications.projetOuvert)
        assertFalse(controleur.etat.value.preVerifications.pret)

        // Le chemin atterrit (l'activité a republié) : la puce passe au
        // vert PAR LE FLUX — aucun nouvel appel à rafraichirPreVerifications.
        registre?.publier("/projet/alpha")
        advanceUntilIdle()

        assertTrue(controleur.etat.value.preVerifications.projetOuvert)
        assertTrue(controleur.etat.value.preVerifications.pret)
    }

    @Test
    fun `C1 - la fermeture de session reactive aussi la puce Projet`() = runTest {
        // Miroir inverse : le projet se FERME pendant que l'écran
        // Agent est visible — la puce repasse au ROUGE par le flux
        // (plus de puce verte menteuse sur un projet fermé).
        val session = SessionPilotable(EtatSessionProjetVue.Ouverte(ProjectId("alpha")))
        val (controleur, _) = controleur(testScheduler, coffre = coffreAvecCle(), session = session)
        controleur.rafraichirPreVerifications()
        advanceUntilIdle()
        assertTrue(controleur.etat.value.preVerifications.projetOuvert)

        session.basculer(EtatSessionProjetVue.Aucune)
        advanceUntilIdle()

        assertFalse(controleur.etat.value.preVerifications.projetOuvert)
    }

    @Test
    fun `toutes les conditions reunies, le lancement part - requete au VRAI modele`() = runTest {
        val (controleur, fournisseur) = controleur(testScheduler, coffre = coffreAvecCle())

        controleur.definirObjectif("Fais quelque chose")
        controleur.lancer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.TERMINE, controleur.etat.value.statut)
        assertEquals(1, fournisseur.requetes.size)
        assertEquals("claude-test", fournisseur.requetes.single().model)
        assertTrue(controleur.etat.value.preVerifications.pret)
    }

    @Test
    fun `l'echec fournisseur devient message LISIBLE et action de remediation (A3)`() = runTest {
        val (controleur, _) =
            controleur(
                testScheduler,
                coffre = coffreAvecCle(),
                fournisseur =
                FournisseurEspion(
                    jo.codeide.core.model.AppError.Ai(
                        jo.codeide.core.model.AppError.AiReason.Authentication,
                    ),
                ),
            )

        controleur.definirObjectif("Fais quelque chose")
        controleur.lancer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.ECHOUE, controleur.etat.value.statut)
        // Message LISIBLE (table du chat, plus de "échec fournisseur :
        // Ai") et ACTION de remédiation portée par l'état.
        assertEquals(R.string.ia_erreur_authentication, controleur.etat.value.message?.ressource)
        assertEquals(jo.codeide.feature.ai.ActionErreurIa.OUVRIR_REGLAGES, controleur.etat.value.actionErreur)
    }

    @Test
    fun `le TEMPS REEL publie la reflexion, l'outil en cours et l'etape active (A4)`() = runTest {
        // Scénario : raisonnement → appel d'outil (observation) → réponse.
        // (Les outils du contrôleur passent par le FAUX contexte — le
        // read_file réel refuse « a.kt » : la PUBLICATION temps réel est
        // ce qu'on éprouve, pas la réussite de l'outil.)
        val fournisseur =
            object : AiProvider {
                val requetes = mutableListOf<AiRequest>()

                override fun stream(request: AiRequest) = flow {
                    requetes += request
                    if (requetes.size == 1) {
                        // delay entre chaque événement : l'horloge virtuelle
                        // laisse la COLLECTE d'état jouer à chaque étape —
                        // sans suspension, StateFlow conflate et le test ne
                        // verrait que l'état final.
                        emit(AiEvent.ReasoningDelta("Je réfléchis "))
                        delay(1)
                        emit(AiEvent.ReasoningDelta("au projet…"))
                        delay(1)
                        emit(AiEvent.ToolCallStarted("call-1", "read_file"))
                        delay(1)
                        emit(AiEvent.ToolCallArguments("call-1", "{\"chemin\":\"a.kt\"}"))
                        delay(1)
                        emit(AiEvent.Done(AiStopReason.ToolUse))
                    } else {
                        emit(AiEvent.TextDelta("Fait."))
                        emit(AiEvent.Done(AiStopReason.EndTurn))
                    }
                }

                override suspend fun listModels(): AppResult<List<AiModelInfo>> = AppResult.Success(emptyList())

                override suspend fun ping(): AppResult<Unit> = AppResult.Success(Unit)
            }
        val registreDossier = RegistreDossierProjetIa()
        registreDossier.publier("/projet/alpha")
        val controleur =
            ControleurAgentIa(
                fournisseur = fournisseur,
                contextes = FauxFournisseurContexte(),
                reglages = reglagesPrets(),
                dossierProjet = registreDossier,
                racineHistorique = SourceProjetHistorique(),
                appliqueur = AppliqueurChangeSetIa(FakeFileSystem(), FakeHistoriqueLocal(), TimeProvider { 0L }),
                demarreurService = ServiceNoOp,
                coffre = coffreAvecCle(),
                sessionProjet = SessionPilotable(EtatSessionProjetVue.Ouverte(ProjectId("alpha"))),
                dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                horloge = TimeProvider { 0L },
                registreEditeur = RegistrePortEditeurActif(),
                sourceDiagnostics = { DiagnosticsFichierIa(cheminActif = null) },
            )

        controleur.definirObjectif("Fais quelque chose")
        controleur.lancer()
        // On capture le temps réel AU FIL de l'eau : la collecte d'état
        // voit la réflexion AVANT l'observation (pas de bufferisation).
        val vus = mutableListOf<EtatAgentIa>()
        val collecte = launch {
            controleur.etat.collect { vus.add(it) }
        }
        advanceUntilIdle()
        collecte.cancel()

        // La réflexion a été publiée PENDANT l'exécution (au moins un
        // état intermédiaire la porte).
        assertTrue(
            "la réflexion doit vivre dans un état intermédiaire",
            vus.any { it.reflexion.isNotBlank() },
        )
        // L'outil en cours et l'étape active ont été publiés, puis
        // refermés à la fin.
        assertTrue(vus.any { it.outilEnCours == "read_file" && it.etapeActive == 1 })
        val final = controleur.etat.value
        assertEquals(StatutAgentIa.TERMINE, final.statut)
        assertEquals(0, final.etapeActive)
        assertEquals(null, final.outilEnCours)
        // La dernière observation est restée (extrait borné) — un REFUS
        // typé du faux contexte, portant le chemin demandé.
        org.junit.Assert.assertNotNull(final.derniereObservation)
        org.junit.Assert.assertTrue(final.derniereObservation!!.contains("a.kt"))
    }

    @Test
    fun `rafraichirPreVerifications calcule les puces sans lancer`() = runTest {
        val (controleur, _) = controleur(testScheduler, coffre = FakeCoffre())

        controleur.rafraichirPreVerifications()
        advanceUntilIdle()

        val puces = controleur.etat.value.preVerifications
        assertTrue(puces.assistantActif)
        assertFalse(puces.clePresente)
        assertTrue(puces.modeleChoisi)
        assertTrue(puces.projetOuvert)
        assertFalse(puces.pret)
    }
}
