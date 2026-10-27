package jo.codeide.feature.ai.agent

import jo.codeide.core.ai.AiContentBlock
import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiMessage
import jo.codeide.core.ai.AiModelInfo
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.AiRole
import jo.codeide.core.ai.AiStopReason
import jo.codeide.core.ai.AppliqueurChangeSetIa
import jo.codeide.core.ai.DiagnosticsFichierIa
import jo.codeide.core.domain.EtatSessionProjetVue
import jo.codeide.core.domain.ObservateurSessionProjet
import jo.codeide.core.domain.RegistreDossierProjetIa
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.domain.SourceProjetHistorique
import jo.codeide.core.domain.TimeProvider
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'INTELLIGENCE de l'agent (rapport IA, chantier IA-CODEIDE,
 * lot 2, A5) : prompt système RICHE dans la requête, mode « Planifier
 * d'abord » (plan proposé → validation AVANT exécution), reprise «
 * Continuer » qui conserve la conversation RÉELLE (pas seulement
 * l'objectif), et coût (jetons) publié dans l'état.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // une classe par écran : les scénarios A5 y déploient le contrat.
class IntelligenceAgentIaTest {
    /** Fournisseur scénarisé : rejoue des tours, un appel d'outil par tour tant que voulu. */
    private class FournisseurScenario(var plan: String = "1. Lire le projet.\n2. Corriger App.kt.\n3. Résumer.") :
        AiProvider {
        val requetes = mutableListOf<AiRequest>()
        var appelerOutil = true
        var rapportUsage = false
        var nomOutil = "read_file"
        var argumentsOutil = "{\"chemin\":\"a.kt\"}"

        /** Tours ÉMETTEURS d'appel d'outil restants (rapport IA, lot 3 —
         *  un appel PUIS la réponse finale, sans retour manuel du test). */
        var toursOutilRestants = Int.MAX_VALUE

        override fun stream(request: AiRequest) = flow {
            requetes += request
            if (appelerOutil && toursOutilRestants > 0) {
                toursOutilRestants--
                emit(AiEvent.ToolCallStarted("call-1", nomOutil))
                emit(AiEvent.ToolCallCompleted("call-1", argumentsOutil))
                emit(AiEvent.Done(AiStopReason.ToolUse))
            } else {
                if (rapportUsage) {
                    emit(AiEvent.Usage(inputTokens = 120L, outputTokens = 30L))
                }
                emit(AiEvent.TextDelta(plan))
                emit(AiEvent.Done(AiStopReason.EndTurn))
            }
        }

        override suspend fun listModels(): AppResult<List<AiModelInfo>> = AppResult.Success(emptyList())

        override suspend fun ping(): AppResult<Unit> = AppResult.Success(Unit)
    }

    /** Session toujours ouverte (le projet est la source de vérité). */
    private class SessionOuverte : ObservateurSessionProjet {
        override val etatSession: StateFlow<EtatSessionProjetVue> =
            MutableStateFlow(EtatSessionProjetVue.Ouverte(ProjectId("alpha")))
    }

    private object ServiceNoOp : ServiceAgentIaPremierPlan {
        override fun demarrer() = Unit

        override fun arreter() = Unit
    }

    @Suppress("LongParameterList") // fabrique de test : chaque paramètre pivote UN scénario.
    private suspend fun controleur(
        horlogeTest: kotlinx.coroutines.test.TestCoroutineScheduler,
        fournisseur: FournisseurScenario,
        contexte: FauxFournisseurContexte = FauxFournisseurContexte(),
        editeur: RegistrePortEditeurActif = RegistrePortEditeurActif(),
        racineDocument: String? = null,
        fichiersTest: FakeFileSystem = FakeFileSystem(),
    ): ControleurAgentIa {
        val registreDossier = RegistreDossierProjetIa()
        registreDossier.publier("/projet/alpha")
        return ControleurAgentIa(
            fournisseur = fournisseur,
            contextes = contexte,
            reglages =
            FakeReglagesIa(
                jo.codeide.core.domain.AiSettings(
                    assistantActive = true,
                    consentementDonne = true,
                    modeleAnthropic = "claude-test",
                ),
            ),
            dossierProjet = registreDossier,
            racineHistorique = SourceProjetHistorique().apply { this.racineDocument = racineDocument },
            appliqueur = AppliqueurChangeSetIa(fichiersTest, FakeHistoriqueLocal(), TimeProvider { 0L }),
            demarreurService = ServiceNoOp,
            coffre = FakeCoffre().apply { enregistrer("anthropic", "cle".toCharArray()) },
            sessionProjet = SessionOuverte(),
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(horlogeTest)),
            horloge = TimeProvider { 0L },
            registreEditeur = editeur,
            sourceDiagnostics = { DiagnosticsFichierIa(cheminActif = null) },
        )
    }

    @Test
    fun `la requete ouvre sur le prompt systeme RICHE - role, langue, regles, arborescence`() = runTest {
        val contexte = FauxFournisseurContexte()
        contexte.chemins =
            listOf("settings.gradle.kts", "app/src/main/kotlin/App.kt", "core/src/main/kotlin/Core.kt")
        val editeur = RegistrePortEditeurActif()
        editeur.enregistrer(
            object : jo.codeide.core.domain.PortEditeurActif {
                override val cheminRelatifFichier = "app/src/main/kotlin/App.kt"
                override val selection = null
                override val diagnostics = emptyList<jo.codeide.core.domain.DiagnosticEditeur>()

                override fun revision(): Long = 1L

                override fun appliquerRemplacement(debut: Int, fin: Int, texte: String) = AppResult.Success(Unit)
            },
        )
        val fournisseur = FournisseurScenario().apply { appelerOutil = false }
        val controleur = controleur(testScheduler, fournisseur, contexte, editeur)

        controleur.definirObjectif("Corrige le bug de App.kt")
        controleur.lancer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.TERMINE, controleur.etat.value.statut)
        val requete = fournisseur.requetes.single()
        val systeme = requete.messages.first()
        assertEquals(AiRole.System, systeme.role)
        val texte = systeme.blocks.filterIsInstance<AiContentBlock.Text>().single().text
        // Le prompt RICHE porte le rôle, la langue, les règles, le build
        // détecté, l'arborescence élaguée et le fichier ACTIF de l'éditeur.
        assertTrue(texte.contains("agent de développement de CodeIDE"))
        assertTrue(texte.contains("TOUJOURS en français"))
        assertTrue(texte.contains("LIS avant de modifier"))
        assertTrue(texte.contains("Système de build du projet : Gradle (Kotlin DSL)."))
        assertTrue(texte.contains("app/ (1 fichier)"))
        assertTrue(texte.contains("Fichier actif dans l'éditeur : app/src/main/kotlin/App.kt."))
        // L'objectif part en message UTILISATEUR après le système.
        assertEquals(AiRole.User, requete.messages[1].role)
    }

    @Test
    fun `mode Planifier d'abord - le plan est propose, la validation precede TOUTE execution`() = runTest {
        val fournisseur = FournisseurScenario().apply { appelerOutil = false }
        val controleur = controleur(testScheduler, fournisseur)

        controleur.definirObjectif("Refonte de l'écran")
        controleur.basculerPlanPremier(true)
        controleur.lancer()
        advanceUntilIdle()

        // Le plan est la réponse de la phase de plan — AUCUNE exécution.
        assertEquals(StatutAgentIa.PLAN_ATTENDU, controleur.etat.value.statut)
        assertEquals(fournisseur.plan, controleur.etat.value.plan)
        // Le prompt de la phase de plan porte la consigne.
        val systeme =
            fournisseur.requetes.single().messages[0]
                .blocks.filterIsInstance<AiContentBlock.Text>().single().text
        assertTrue(systeme.contains("MODE PLANIFIER D'ABORD"))
    }

    @Test
    fun `valider le plan execute depuis la conversation REELLE - le plan y vit`() = runTest {
        val fournisseur = FournisseurScenario().apply { appelerOutil = false }
        val controleur = controleur(testScheduler, fournisseur)

        controleur.definirObjectif("Refonte de l'écran")
        controleur.basculerPlanPremier(true)
        controleur.lancer()
        advanceUntilIdle()
        assertEquals(StatutAgentIa.PLAN_ATTENDU, controleur.etat.value.statut)

        // Validation : l'exécution repart du plan (conversation conservée).
        fournisseur.appelerOutil = false
        controleur.validerPlan()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.TERMINE, controleur.etat.value.statut)
        assertEquals(2, fournisseur.requetes.size)
        val messages = fournisseur.requetes[1].messages
        // L'historique RÉEL : le message utilisateur de validation arrive
        // APRÈS le plan (la réponse de la phase de plan y vit).
        val dernier = messages.last()
        assertEquals(AiRole.User, dernier.role)
        val texteValidation = dernier.blocks.filterIsInstance<AiContentBlock.Text>().single().text
        assertTrue(texteValidation.contains("Plan validé"))
        // Le plan lui-même vit dans la conversation (réponse assistant conservée).
        assertTrue(
            messages.any { message ->
                message.role == AiRole.Assistant &&
                    message.blocks.any {
                        it is AiContentBlock.Text &&
                            (it as AiContentBlock.Text).text == fournisseur.plan
                    }
            },
        )
    }

    @Test
    fun `rejeter le plan ramene l'ecran a l'inactif - le plan est efface`() = runTest {
        val fournisseur = FournisseurScenario().apply { appelerOutil = false }
        val controleur = controleur(testScheduler, fournisseur)

        controleur.definirObjectif("Refonte de l'écran")
        controleur.basculerPlanPremier(true)
        controleur.lancer()
        advanceUntilIdle()
        controleur.rejeterPlan()

        assertEquals(StatutAgentIa.INACTIF, controleur.etat.value.statut)
        assertEquals(null, controleur.etat.value.plan)
        assertEquals(R.string.agent_plan_rejete, controleur.etat.value.message?.ressource)
    }

    @Test
    fun `Continuer conserve la conversation REELLE - pas seulement l'objectif`() = runTest {
        val fournisseur = FournisseurScenario()
        val controleur = controleur(testScheduler, fournisseur)

        controleur.definirObjectif("Explore le projet")
        controleur.lancer()
        advanceUntilIdle()

        // 25 étapes d'appels d'outils : INTERROMPU, conversation mémorisée.
        assertEquals(StatutAgentIa.INTERROMPU, controleur.etat.value.statut)
        val requetesPremiereExecution = fournisseur.requetes.size

        // « Continuer » : le fournisseur répond enfin.
        fournisseur.appelerOutil = false
        controleur.continuer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.TERMINE, controleur.etat.value.statut)
        val reprise = fournisseur.requetes[requetesPremiereExecution]
        val messages = reprise.messages
        // La requête de reprise n'est PAS un redémarrage à zéro : elle
        // porte l'objectif initial ET l'historique réel (appels d'outils,
        // observations) ET le message de continuation en dernier.
        assertTrue(messages.size > 2)
        assertEquals("Explore le projet", messages[1].blocks.filterIsInstance<AiContentBlock.Text>().single().text)
        assertTrue(messages.any { message -> message.role == AiRole.Tool })
        val dernier = messages.last()
        assertEquals(AiRole.User, dernier.role)
        assertTrue(
            dernier.blocks.filterIsInstance<AiContentBlock.Text>().single().text.contains("Continue vers l'objectif"),
        )
    }

    @Test
    fun `un lancement FRANC apres une interruption part d'une conversation NEUVE`() = runTest {
        val fournisseur = FournisseurScenario()
        val controleur = controleur(testScheduler, fournisseur)

        controleur.definirObjectif("Explore le projet")
        controleur.lancer()
        advanceUntilIdle()
        assertEquals(StatutAgentIa.INTERROMPU, controleur.etat.value.statut)

        // Nouvel objectif + Lancer : conversation NEUVE (2 messages).
        fournisseur.appelerOutil = false
        controleur.definirObjectif("Autre objectif")
        controleur.lancer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.TERMINE, controleur.etat.value.statut)
        val derniere = fournisseur.requetes.last()
        assertEquals(2, derniere.messages.size)
        assertEquals(
            "Autre objectif",
            derniere.messages[1].blocks.filterIsInstance<AiContentBlock.Text>().single().text,
        )
    }

    @Test
    fun `le cout de l'execution - compteurs du fournisseur et estimation locale - vit dans l'etat`() = runTest {
        val fournisseur = FournisseurScenario().apply {
            appelerOutil = false
            rapportUsage = true
        }
        val controleur = controleur(testScheduler, fournisseur)

        controleur.definirObjectif("Fais quelque chose")
        controleur.lancer()
        advanceUntilIdle()

        val jetons = controleur.etat.value.jetons
        assertNotNull(jetons)
        assertEquals(120L, jetons?.entree)
        assertEquals(30L, jetons?.sortie)
        assertTrue("l'estimation locale doit être positive", (jetons?.estimes ?: 0L) > 0L)
    }

    // ------------------------------------------------------------------
    // Mode « revue » (rapport IA, lot 3 — d'après la maquette).
    // ------------------------------------------------------------------

    @Test
    fun `mode revue - la proposition d'ecriture SUSPEND la boucle et Continuer la LIBERE`() = runTest {
        val fournisseur = FournisseurScenario().apply {
            nomOutil = "create_file"
            argumentsOutil = "{\"chemin\":\"Nouveau.kt\",\"contenu\":\"class A\",\"raison\":\"test\"}"
            toursOutilRestants = 1
        }
        val controleur = controleur(
            testScheduler,
            fournisseur,
            racineDocument = "/",
            fichiersTest = FakeFileSystem().apply {
                seedDocument("/", FakeFileSystem.Document("/", isDirectory = true))
            },
        )

        controleur.basculerModeRevue(true)
        controleur.definirObjectif("Crée Nouveau.kt")
        controleur.lancer()
        advanceUntilIdle()

        // La boucle est SUSPENDUE à la porte : l'exécution est EN COURS
        // (elle ne se termine PAS), la carte de changements est PRÉPARÉE
        // (résolution faite — 1 fichier, +1 ligne), le message dit le
        // geste suivant.
        val suspendu = controleur.etat.value
        assertEquals(StatutAgentIa.EN_COURS, suspendu.statut)
        assertTrue(suspendu.modeRevue)
        assertEquals(R.string.agent_mode_revue_attente, suspendu.message?.ressource)
        val revue = suspendu.revue
        assertNotNull(revue)
        assertEquals(1, revue?.fichiers?.size)
        assertEquals(1, revue?.lignesAjoutees)
        // UNE seule requête partie : le second tour attend la porte.
        assertEquals(1, fournisseur.requetes.size)

        // « Continuer » LIBÈRE la porte : le tour final part, la réponse
        // termine l'exécution.
        controleur.continuer()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.TERMINE, controleur.etat.value.statut)
        assertEquals(2, fournisseur.requetes.size)
    }

    @Test
    fun `mode revue - Arreter pendant la porte ANNULE proprement l'execution`() = runTest {
        val fournisseur = FournisseurScenario().apply {
            nomOutil = "create_file"
            argumentsOutil = "{\"chemin\":\"Nouveau.kt\",\"contenu\":\"class A\",\"raison\":\"test\"}"
            toursOutilRestants = 1
        }
        val controleur = controleur(
            testScheduler,
            fournisseur,
            racineDocument = "/",
            fichiersTest = FakeFileSystem().apply {
                seedDocument("/", FakeFileSystem.Document("/", isDirectory = true))
            },
        )

        controleur.basculerModeRevue(true)
        controleur.definirObjectif("Crée Nouveau.kt")
        controleur.lancer()
        advanceUntilIdle()
        assertEquals(StatutAgentIa.EN_COURS, controleur.etat.value.statut)

        // « Arrêter » pendant la porte : la boucle suspendue meurt d'une
        // annulation PROPRE — état ANNULE, plus aucun tour ne part.
        controleur.arreter()
        advanceUntilIdle()

        assertEquals(StatutAgentIa.ANNULE, controleur.etat.value.statut)
        assertEquals(1, fournisseur.requetes.size)
    }

    @Test
    fun `sans mode revue - la proposition d'ecriture ne SUSPEND rien et l'execution va au bout`() = runTest {
        val fournisseur = FournisseurScenario().apply {
            nomOutil = "create_file"
            argumentsOutil = "{\"chemin\":\"Nouveau.kt\",\"contenu\":\"class A\",\"raison\":\"test\"}"
            toursOutilRestants = 1
        }
        val controleur = controleur(
            testScheduler,
            fournisseur,
            racineDocument = "/",
            fichiersTest = FakeFileSystem().apply {
                seedDocument("/", FakeFileSystem.Document("/", isDirectory = true))
            },
        )

        // Mode revue DÉSARMÉ (défaut) : la proposition n'arrête PAS la
        // boucle — le tour suivant part jusqu'à la réponse finale.
        controleur.definirObjectif("Crée Nouveau.kt")
        controleur.lancer()
        advanceUntilIdle()

        assertTrue(controleur.etat.value.modeRevue.not())
        assertEquals(StatutAgentIa.TERMINE, controleur.etat.value.statut)
        assertEquals(2, fournisseur.requetes.size)
    }
}
