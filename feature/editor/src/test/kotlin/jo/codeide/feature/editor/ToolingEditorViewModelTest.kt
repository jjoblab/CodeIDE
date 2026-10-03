package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtatOutilsTerminal
import jo.codeide.core.domain.EtatSyncLocal
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.InfoTache
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.StatutTache
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des actions tooling du ViewModel de l'espace de travail (G5,
 * section 6) : garde du dossier introuvable (même limite documentée que
 * T6 — le dossier réel ne se résout pas en JVM), câblage des flux de
 * build par la couture [EditorViewModel.observerBuild], sélecteur de
 * tâches, diagnostics en ligne.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolingEditorViewModelTest : BaseEditorViewModelTest() {
    @Test
    fun `l ouverture du projet declenche la synchronisation automatique - etape 32`() =
        runTest {
            // JDK semé : la garde passe, la résolution du dossier échoue en
            // JVM (même garde que T6) — l'ÉCHEC PUBLIÉ SANS GESTE prouve que
            // la sync d'ouverture a bien tenté de partir.
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            val id = ajouterProjet("projet-ouverture")
            val viewModel = viewModel(id)
            avancer()

            val etat = viewModel.etatGradle.value
            assertTrue(
                "aucun geste : la sync d ouverture a déjà tenté (étape 32)",
                etat.synchronisationEnCours.not(),
            )
            assertEquals("dossier du projet introuvable", etat.messageEchecSync)
            assertEquals(
                "l orchestrateur n est pas sollicité (dossier irrésolvable en JVM)",
                0,
                tooling.nbSynchronisations,
            )

            // Une seconde boucle d avance ne RETENTE PAS : une seule sync
            // d ouverture par espace de travail.
            avancer()
            assertEquals(0, tooling.nbSynchronisations)
        }

    @Test
    fun `le classpath LSP n est pas sollicite quand la sync n a rien resolu - ADR 0058`() =
        runTest {
            // Même limite JVM que la sync d'ouverture : le dossier ne se
            // résout pas, la sync échoue AVANT l'orchestrateur — la
            // préparation du classpath LSP ne part jamais quand la sync
            // n'a rien résolu (elle suit une sync UTILE, jamais un échec).
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            val id = ajouterProjet("projet-classpath")
            val viewModel = viewModel(id)
            avancer()

            assertEquals(0, tooling.nbSynchronisations)
            assertEquals("le classpath LSP suit une sync résolue, rien d'autre", 0, tooling.nbClasspaths)
        }

    @Test
    fun `la sync d ouverture passe par la garde JDK quand les outils manquent`() =
        runTest {
            val id = ajouterProjet("projet-ouverture-sans-jdk")
            val viewModel = viewModel(id)
            avancer()

            // Aucun geste : la garde ADR 0048 a répondu dans le canal Sync.
            assertTrue(
                viewModel.etatGradle.value.messageEchecSync
                    ?.contains("JDK absent") == true,
            )
            assertEquals(0, tooling.nbSynchronisations)
        }

    @Test
    fun `un build en vol est rattache a la reouverture de l espace - etape 32`() =
        runTest {
            val id = ajouterProjet("projet-rattachement")
            val viewModel = viewModel(id)
            avancer()

            // Un build tourne (couture), l'espace se referme : le
            // ViewModel meurt, PAS l'état process-wide.
            viewModel.observerBuild("b-vol", listOf("assembleDebug"))
            tooling.emettreLigne("b-vol", ligne = "etape 1")
            avancer()

            // Ré-ouverture : le nouvel espace se rattache au build en vol.
            val rouvert = viewModel(id)
            avancer()

            assertEquals("b-vol", rouvert.etatGradle.value.buildId)
            assertEquals(
                "les tâches du build en vol voyagent au rattachement (étape 32)",
                listOf("assembleDebug"),
                rouvert.etatGradle.value.taches,
            )
            assertEquals(StatutBuild.EN_COURS, rouvert.etatGradle.value.statutBuild)

            tooling.emettreLigne("b-vol", ligne = "etape 2")
            tooling.terminerBuild("b-vol", StatutBuild.REUSSI)
            avancer()

            assertEquals(StatutBuild.REUSSI, rouvert.etatGradle.value.statutBuild)
            assertEquals(
                "la zone texte repart VIERGE à l attache (le Vider) puis la sortie " +
                    "CONTINUE d arriver au build rattache — v0.42.0 : le rejeu du flux " +
                    "dédié porte les lignes d APRÈS le dernier vidage",
                listOf("etape 2"),
                lignesConsoleBuildApresDernierVider(),
            )
        }

    @Test
    fun `la synchronisation sans dossier publie l echec sans appel tooling`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.Synchroniser)
            avancer()

            // Dossier introuvable en JVM (garde du domaine, même constat
            // que T6) : l'échec est publié, l'orchestrateur pas sollicité.
            val etat = viewModel.etatGradle.value
            assertTrue(etat.synchronisationEnCours.not())
            assertTrue(etat.messageEchecSync != null)
            assertNull(tooling.dossierRecu)
        }

    @Test
    fun `regression v0 43 0 - le sync-state se restitue via documentUri quand grantUri est l arbre parent`() =
        runTest {
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true, initialise = true))

            // Projet « créé dans le dossier de travail » (CreateProjectUseCase) :
            // grantUri = arbre PARENT, documentUri = document IMBRIQUÉ.
            val (grantParent, documentProjet) = semerProjetSousDocument()

            // Sync-state persisté SOUS LE DOCUMENT (là où les use cases le
            // cherchent), par les VRAIS use cases sur le MÊME faux.
            val tachesRestituees = listOf(InfoTache(chemin = ":app:build", nomAffiche = "build"))
            persisterSyncState(documentProjet, tachesRestituees, dureeMs = 4_321L)

            val ajout =
                depot.addProject(
                    "MonApp",
                    "",
                    jo.codeide.core.model.StorageLocation(
                        grantUri = grantParent,
                        documentUri = documentProjet,
                        displayPath = "dossier-travail/MonApp",
                    ),
                    jo.codeide.core.model.TemplateId.IMPORTED,
                )
            val id = (ajout as AppResult.Success).value.id
            val viewModel = viewModel(id)
            avancer()

            // L'ancien code lisait sous grantUri (l'arbre PARENT) : state
            // introuvable → sync manuelle → échec. Le nouveau lit sous
            // documentUri : état « Synchronisé » restitué immédiatement,
            // durée et tâches comprises, et AUCUNE sync manuelle lancée.
            val etat = viewModel.etatGradle.value
            assertEquals("aucune sync manuelle : la restitution a suffi", 0, tooling.nbSynchronisations)
            assertTrue(
                "l'état restitué est publié via documentUri (v0.43.0)",
                etat.synchronisationReussie?.reussie == true,
            )
            assertEquals(4_321L, etat.synchronisationReussie?.dureeMs)
            assertEquals(
                "les tâches du state sont restituées au retour du projet",
                tachesRestituees,
                etat.tachesDisponibles,
            )
        }

    @Test
    fun `la synchronisation sans JDK est refusée avec un message actionnable - v0 31 4`() =
        runTest {
            // ADR 0048 : les outils étant optionnels et différés, la
            // « demande ultérieure » se fait par un refus AVANT toute
            // tentative — message actionnable (où installer) au lieu
            // d'une connexion perdue opaque.
            val id = ajouterProjet("projet-sans-jdk")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.Synchroniser)
            avancer()

            val etat = viewModel.etatGradle.value
            assertTrue(etat.synchronisationEnCours.not())
            assertTrue(etat.messageEchecSync?.contains("JDK absent") == true)
            assertNull(tooling.dossierRecu)
        }

    @Test
    fun `l exécution de tâches sans JDK est refusée sans build - v0 31 4`() =
        runTest {
            val id = ajouterProjet("projet-sans-jdk")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.ExecuterTaches(listOf("saluer")))
            avancer()

            assertNull(viewModel.etatGradle.value.buildId)
            assertNull(tooling.dossierRecu)
            assertTrue(
                viewModel.etatGradle.value.messageEchecSync
                    ?.contains("JDK absent") == true,
            )
        }

    @Test
    fun `le JDK installe en pleine session ouvre la garde du build - v0 37 3`() =
        runTest {
            // v0.37.3 (retour d'appareil réel) : la garde JDK lisait le
            // disque en pull — les refus restaient corrects à l'action
            // mais l'affichage des outils restait FIGÉ, et chaque build
            // payait un scan multi-emplacements sur le thread principal.
            // Le cache POUSSÉ suit l'installation : la MÊME session
            // d'espace accepte le build dès que le JDK apparaît.
            val id = ajouterProjet("projet-jdk-tardif")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.ExecuterTaches(listOf("saluer")))
            avancer()
            assertTrue(
                "premier refus : garde JDK fermée",
                viewModel.etatGradle.value.messageEchecSync
                    ?.contains("JDK absent") == true,
            )

            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            avancer()

            viewModel.onAction(ActionEditor.ExecuterTaches(listOf("saluer")))
            avancer()

            assertTrue(
                "garde ouverte : l'échec éventuel n'est PLUS le refus JDK",
                viewModel.etatGradle.value.messageEchecSync
                    ?.contains("JDK absent") != true,
            )
        }

    @Test
    fun `l execution sans dossier ne lance aucun build`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.ExecuterTaches(listOf("saluer")))
            avancer()

            assertNull(viewModel.etatGradle.value.buildId)
            assertNull(tooling.dossierRecu)
        }

    @Test
    fun `le cablage des flux de build suit sortie et etat`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            val viewModel = viewModel(id)
            avancer()

            // Couture de test : le câblage complet (console + état) se
            // vérifie sans résolution de dossier.
            viewModel.observerBuild("b-g5")
            avancer()

            assertEquals("b-g5", viewModel.etatGradle.value.buildId)
            assertEquals(StatutBuild.EN_COURS, viewModel.etatGradle.value.statutBuild)

            tooling.emettreLigne("b-g5", ligne = "Bonjour")
            tooling.terminerBuild("b-g5", StatutBuild.REUSSI)
            avancer()

            assertEquals(
                "v0.46.0 : la sortie traverse le flux de la console BUILD " +
                    "(rejeu après le Vider du suivi de build), pas l état",
                listOf("Bonjour"),
                lignesConsoleBuildApresDernierVider(),
            )
            assertTrue(
                "l état ne porte plus AUCUNE ligne de console (v0.46.0) — " +
                    "seules les étapes de sync y vivent pour l en-tête",
                viewModel.etatGradle.value.etapesSync
                    .isEmpty(),
            )
            assertEquals(StatutBuild.REUSSI, viewModel.etatGradle.value.statutBuild)
        }

    // ------------------------------------------------------------------
    // v3 — tâches au fil du build, étapes de sync, réglages tooling.
    // ------------------------------------------------------------------

    @Test
    fun `les taches du build n ecrivent plus RIEN - Gradle ecrit ses lignes sur le flux`() =
        runTest {
            val id = ajouterProjet("projet-taches")
            val viewModel = viewModel(id)
            avancer()

            viewModel.observerBuild("b-taches")
            avancer()

            tooling.emettreTache("b-taches", ":app:compileKotlin", StatutTache.EN_COURS)
            tooling.emettreTache(
                "b-taches",
                ":app:compileKotlin",
                StatutTache.REUSSIE,
                dureeMs = 1_800,
            )
            avancer()

            assertTrue(
                "v0.46.0 (ADR 0078) : les événements de tâche ne produisent NI ligne NI état — " +
                    "Gradle écrit « > Task :app:compileKotlin » sur stdout, la console le montre " +
                    "tel quel (les dupliquer était le problème « deux endroits »)",
                lignesConsoleBuildApresDernierVider().isEmpty(),
            )
            assertEquals(
                "le build reste suivi (l'état du build ne dépend pas des lignes de tâche)",
                StatutBuild.EN_COURS,
                viewModel.etatGradle.value.statutBuild,
            )
        }

    // v0.46.0 : le test `le reglage cacher les taches filtre la console en
    // vol` est SUPPRIMÉ — le réglage « afficher les tâches » a disparu avec
    // les rangées structurées : le flux brut de Gradle porte ses propres
    // lignes « > Task », il n'y a plus RIEN à filtrer.

    @Test
    fun `les telechargements du build ecrivent UNE ligne par artefait - v0_46_0`() =
        runTest {
            val id = ajouterProjet("projet-telechargements")
            val viewModel = viewModel(id)
            avancer()

            viewModel.observerBuild("b-telechargements")
            avancer()

            tooling.emettreTelechargement(
                "b-telechargements",
                element = "kotlin-stdlib.jar",
                octetsRecus = 1_769_000,
                compteur = 1,
            )
            avancer()
            tooling.emettreTelechargement(
                "b-telechargements",
                element = "gradle-9.7.1-all.zip",
                octetsRecus = 130_000_000,
                compteur = 2,
            )
            avancer()

            val lignes =
                serviceGradleTest.lignesBrutes.replayCache
                    .filterIsInstance<EvenementConsoleTexte.Ligne>()
                    .filter { it.style == StyleLigne.TELECHARGEMENT }
            assertEquals(
                "la pompe process-wide draine le canal (aucun consommateur avant v0.45.1) " +
                    "et chaque artefat terminé écrit SA ligne (v0.46.0)",
                2,
                lignes.size,
            )
            assertTrue(lignes.all { it.canal == CanalTooling.BUILD })

            // Le terme du build n'efface pas l'historique (la conclusion de
            // Gradle reste visible, comme la fenêtre Build d'Android Studio).
            tooling.terminerBuild("b-telechargements", StatutBuild.REUSSI)
            avancer()
            assertEquals(StatutBuild.REUSSI, viewModel.etatGradle.value.statutBuild)
        }

    @Test
    fun `les etapes de sync annoncees par le serveur alimentent la console`() =
        runTest {
            val id = ajouterProjet("projet-etapes")
            val viewModel = viewModel(id)
            avancer()

            tooling.emettreEtapeSync(EtapeSync.DAEMON)
            tooling.emettreEtapeSync(EtapeSync.DAEMON, terminee = true, dureeMs = 900)
            avancer()

            val etapes = viewModel.etatGradle.value.etapesSync
            assertEquals(1, etapes.size)
            assertTrue(etapes.single().terminee)
            assertEquals(EtapeSync.DAEMON, etapes.single().etape)
        }

    @Test
    fun `l annulation vise le build suivi`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            val viewModel = viewModel(id)
            avancer()
            viewModel.observerBuild("b-42")
            avancer()

            viewModel.onAction(ActionEditor.AnnulerBuild)

            assertEquals("b-42", tooling.buildAnnule)
        }

    @Test
    fun `le selecteur de taches emet la liste recue`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            // Le listage exige le dossier : introuvable en JVM, l'effet
            // n'est pas émis — l'échec est journalisé (garde).
            tooling.prochainesTaches = AppResult.Success(emptyList())
            val recus = mutableListOf<EffetEditor>()
            val viewModel = viewModel(id)
            collecterEffets(viewModel, recus)
            avancer()

            viewModel.onAction(ActionEditor.OuvrirSelecteurTaches)
            avancer()

            assertTrue(recus.filterIsInstance<EffetEditor.OuvrirSelecteurTaches>().isEmpty())
            assertNull(tooling.dossierRecu)
        }

    @Test
    fun `le selecteur repond depuis le cache de la sync sans aller-retour`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            val recus = mutableListOf<EffetEditor>()
            val viewModel = viewModel(id)
            collecterEffets(viewModel, recus)
            avancer()

            // Cache rempli par une sync utile (v4, §3.1) : le sélecteur
            // répond SANS toucher le port — aucune latence, aucun
            // aller-retour (correctif n°6).
            serviceGradleTest.publierTachesDisponibles(
                listOf(InfoTache(chemin = ":app:assembleDebug", groupe = "build", nomAffiche = "assembleDebug")),
            )

            viewModel.onAction(ActionEditor.OuvrirSelecteurTaches)
            avancer()

            val effet = recus.filterIsInstance<EffetEditor.OuvrirSelecteurTaches>().single()
            assertEquals(":app:assembleDebug", effet.taches.single().chemin)
            assertNull(tooling.dossierRecu)
        }

    @Test
    fun `les diagnostics arrivent dans l etat et en inline sur l onglet ouvert`() =
        runTest {
            val id = ajouterProjet("Alpha")
            // Onglet ouvert sur src/Main.kt (même semis que les tests
            // d'onglets) : le diagnostic inline vise son fichier.
            semerDossier("src")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
            )
            val viewModel = viewModel(id)
            avancer()
            val uriSrc =
                viewModel.etat.value.noeuds
                    .first { noeud -> noeud.nom == "src" }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(uriSrc))
            avancer()
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { noeud -> noeud.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            avancer()

            tooling.diagnosticsInterne.value = listOf(diagnostic("/projets/Alpha/src/Main.kt", 5))
            avancer()

            assertEquals(1, viewModel.etatGradle.value.problemesTotal)
            val suivie = viewModel.sessionSuivieDe(uriMain)
            assertTrue("l'onglet devait avoir une session", suivie != null)
            assertEquals(1, suivie!!.session.diagnostics.size)
        }

    @Test
    fun `le terminal du flux sync conclut l UI meme sans coroutine lancante - regression v0_48_0`() =
        runTest {
            val id = ajouterProjet("projet-terminal")
            val viewModel = viewModel(id)
            avancer()

            // La sync part — annoncée PAR le serveur (Debut du flux ordonné,
            // v0.48.0) : l'état passe « en cours », la console Sync repart
            // vierge. C'est le chemin de la revalidation silencieuse (v0.40.1)
            // quand aucune coroutine lancante ne publiera le résultat.
            tooling.emettreDebutSync()
            avancer()
            assertTrue(
                "le Debut arme l'état en cours (le retour terrain voyait l'étape n/N figée)",
                serviceGradleTest.etat.value.synchronisationEnCours,
            )

            // Le VRAI flux de Gradle s'affiche dans le canal Sync (ADR 0079).
            tooling.emettreLigneSync("Starting Gradle Daemon")
            tooling.emettreLigneSync("warning: configuration", flux = FluxSortieBuild.STDERR)
            avancer()
            assertEquals(
                "les lignes réelles de la sync s'affichent dans la console",
                listOf("Starting Gradle Daemon", "warning: configuration"),
                lignesConsoleSyncApresDernierVider().map { (it.libelle as TexteTooling.Brut).texte },
            )

            // La coroutine lancante est MORTE (écran fermé — viewModelScope
            // annulé, exactement le retour terrain « à la fin du sync l'UI
            // n'est toujours pas à jour ») : PERSONNE n'appelle
            // publierResultatSync. Seul le TERMINAL du flux peut conclure.
            tooling.emettreTerminalSync(
                AppResult.Success(
                    ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 8_400),
                ),
            )
            avancer()

            val etat = serviceGradleTest.etat.value
            assertFalse(
                "le terminal déclare la sync terminée — l'en-tête ne reste pas « en cours » à jamais",
                etat.synchronisationEnCours,
            )
            assertEquals(8_400L, etat.synchronisationReussie?.dureeMs)
            val lignes = lignesConsoleSyncApresDernierVider()
            assertEquals(
                "les lignes réelles précèdent la conclusion (ordre du flux)",
                listOf("Starting Gradle Daemon", "warning: configuration"),
                lignes.dropLast(1).map { (it.libelle as TexteTooling.Brut).texte },
            )
            assertEquals(
                "la conclusion arrive en DERNIER, en style synthèse",
                StyleLigne.SYNTHESE,
                lignes.last().style,
            )
        }

    @Test
    fun `l echec de sync conclut AUSSI la console avec son message - v0_48_0`() =
        runTest {
            val id = ajouterProjet("projet-echec-sync")
            val viewModel = viewModel(id)
            avancer()

            tooling.emettreDebutSync()
            tooling.emettreLigneSync("> Configure project :app")
            tooling.emettreTerminalSync(
                AppResult.Success(
                    ResultatSynchronisation(
                        projectDir = "/p",
                        reussie = false,
                        dureeMs = 2_100,
                        messageEchec = "répertoire introuvable : /p",
                    ),
                ),
            )
            avancer()

            val lignes = lignesConsoleSyncApresDernierVider()
            assertEquals(
                "le verdict d'échec s'écrit en style synthèse (parité SYNC FAILED), reçu : ${lignes.map { it.style }}",
                StyleLigne.SYNTHESE,
                lignes[lignes.size - 2].style,
            )
            val message = lignes.last()
            assertEquals(
                "le message du serveur suit en ligne d'erreur",
                "répertoire introuvable : /p",
                (message.libelle as TexteTooling.Brut).texte,
            )
            assertEquals(StyleLigne.ERREUR, message.style)
        }

    // ------------------------------------------------------------------
    // Aides.
    // ------------------------------------------------------------------

    /** Avance le temps virtuel et laisse tourner les collectes. */
    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }

    /** Sème le SAF du projet « créé dans le dossier de travail »
     *  (CreateProjectUseCase) : grantUri = arbre PARENT accordé,
     *  documentUri = document IMBRIQUÉ de MonApp portant son script
     *  Gradle — la paire (grantUri, documentUri) du bug v0.43.0. */
    private fun semerProjetSousDocument(): Pair<String, String> {
        val grantParent = "content://autorite/tree/dossier-travail"
        val documentProjet = "$grantParent/document/dossier-travail%2FMonApp"
        fichiers.grantPermission(grantParent)
        fichiers.seedDocument(
            grantParent,
            FakeFileSystem.Document(name = "dossier-travail", isDirectory = true),
        )
        fichiers.seedDocument(
            documentProjet,
            FakeFileSystem.Document(name = "MonApp", isDirectory = true),
        )
        fichiers.seedDocument(
            "$documentProjet/build.gradle.kts",
            FakeFileSystem.Document(
                name = "build.gradle.kts",
                isDirectory = false,
                bytes = "plugins { }".toByteArray(),
            ),
        )
        return grantParent to documentProjet
    }

    /** Persiste un sync-state VALIDE sous le document du projet par les
     *  VRAIS use cases (empreinte calculée sur le même faux, écriture
     *  réelle) : la restitution de l'espace doit le retrouver tel quel. */
    private suspend fun persisterSyncState(
        documentProjet: String,
        taches: List<InfoTache>,
        dureeMs: Long,
    ) {
        val empreinte =
            jo.codeide.core.domain
                .CalculerEmpreinteGradleUseCase(
                    fichiers,
                    TestDispatcherProvider(regleMain.dispatcher),
                ).invoke(documentProjet)
        assertTrue("l'empreinte des fichiers Gradle semés est non vide", empreinte.isNotEmpty())
        val ecriture =
            jo.codeide.core.domain
                .EcrireSyncStateUseCase(
                    fichiers,
                    TestDispatcherProvider(regleMain.dispatcher),
                ).invoke(
                    documentProjet,
                    EtatSyncLocal(empreinte = empreinte, taches = taches, dureeMs = dureeMs),
                )
        assertTrue("le sync-state s'écrit sous le document du projet", ecriture is AppResult.Success)
    }

    /** Lignes BRUTES de la console du canal BUILD après le dernier vidage
     *  de CE canal (v0.42.0 ; v0.46.0 : par canal) — ce qu'une vue abonnée
     *  reconstruirait : le rejeu du flux est la vérité, le `Vider` de
     *  l'attache/d'un nouveau build borne la reconstruction. */
    private fun lignesConsoleBuildApresDernierVider(): List<String> {
        val evenements =
            serviceGradleTest.lignesBrutes.replayCache
                .filterIsInstance<EvenementConsoleTexte>()
        val dernierVider =
            evenements.indexOfLast {
                it is EvenementConsoleTexte.Vider && it.canal == CanalTooling.BUILD
            }
        return evenements
            .drop(if (dernierVider >= 0) dernierVider + 1 else 0)
            .filterIsInstance<EvenementConsoleTexte.Ligne>()
            .filter { it.canal == CanalTooling.BUILD }
            .map { (it.libelle as TexteTooling.Brut).texte }
    }

    /** Lignes de la console du canal SYNC après le dernier vidage de CE
     *  canal (v0.48.0) — les ÉVÉNEMENTS typés (libellé + style) : les
     *  conclusions LOCALISÉES s'assertent par leur STYLE, les lignes
     *  réelles de Gradle par leur TEXTE BRUT. */
    private fun lignesConsoleSyncApresDernierVider(): List<EvenementConsoleTexte.Ligne> {
        val evenements =
            serviceGradleTest.lignesBrutes.replayCache
                .filterIsInstance<EvenementConsoleTexte>()
        val dernierVider =
            evenements.indexOfLast {
                it is EvenementConsoleTexte.Vider && it.canal == CanalTooling.SYNC
            }
        return evenements
            .drop(if (dernierVider >= 0) dernierVider + 1 else 0)
            .filterIsInstance<EvenementConsoleTexte.Ligne>()
            .filter { it.canal == CanalTooling.SYNC }
    }
}
