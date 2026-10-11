package jo.codeide.core.bootstrap

import jo.codeide.core.domain.DiagnostiquerGitProjetUseCase
import jo.codeide.core.domain.EtatDepot
import jo.codeide.core.domain.RaisonDepotInaccessible
import jo.codeide.core.domain.ResultatGit
import jo.codeide.core.domain.StatutGit
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeMoteurGit
import jo.codeide.core.testing.FakeNativeProcessLauncher
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.ProcessusScripte
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Tests du Diagnostic Git au niveau du moteur (v0.90.1, mission « section
 * Git figée » étape A) : [MoteurGitCli.etatDepot] sur le faux lanceur
 * (classification + journalisation), [MoteurGitCli.diagnostiquer]
 * (assemblage du rapport complet), et le cas d'usage
 * [DiagnostiquerGitProjetUseCase] (sélection du projet le plus récent,
 * chemin FUSE irrésolvable).
 *
 * Les tests rouges/verts sur de VRAIS sous-processus vivent dans
 * [MoteurGitCliFluxUniqueTest] (v0.80.7) — ici, le faux rejoue des
 * réponses canoniques de git.
 */
class MoteurGitCliDiagnosticTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val lanceur = FakeNativeProcessLauncher()
    private val journal = FakeAppLogger()
    private val sondes = SondesFictives()

    /** Moteur sur le faux lanceur, binaire toujours présent. */
    private fun moteur(binaire: String? = "/prefix/bin/git"): MoteurGitCli =
        MoteurGitCli(
            lanceur = lanceur,
            resoudreBinaireGit = { binaire },
            identite = null,
            journal = journal,
            sondes = sondes,
        )

    // ------------------------------------------------------------------
    // etatDepot — classification au travers du moteur
    // ------------------------------------------------------------------

    @Test
    fun `etatDepot dit Depot quand rev-parse repond true`() =
        runTest {
            lanceur.fabrique = { ProcessusScripte(lignesStdout = listOf("true")) }

            assertEquals(EtatDepot.Depot, moteur().etatDepot("/projets/depot"))
        }

    @Test
    fun `etatDepot dit PasUnDepot sur le message canonique de git`() =
        runTest {
            lanceur.fabrique = {
                ProcessusScripte(
                    codeSortie = 128,
                    lignesStderr = listOf("fatal: not a git repository (or any of the parent directories): .git"),
                )
            }

            assertEquals(EtatDepot.PasUnDepot, moteur().etatDepot("/projets/vide"))
        }

    @Test
    fun `etatDepot dit Inaccessible sur dubious ownership et preserve le stderr`() =
        runTest {
            val stderr = "fatal: detected dubious ownership in repository at /projets/depot"
            lanceur.fabrique = { ProcessusScripte(codeSortie = 128, lignesStderr = listOf(stderr)) }

            val etat = moteur().etatDepot("/projets/depot")

            assertTrue(etat is EtatDepot.Inaccessible)
            assertEquals(RaisonDepotInaccessible.REFUS_GIT, (etat as EtatDepot.Inaccessible).raison)
            assertEquals(stderr, etat.stderrExpurge)
            assertEquals(128, etat.codeSortie)
        }

    @Test
    fun `etatDepot dit Inaccessible quand le binaire est absent`() =
        runTest {
            val etat = moteur(binaire = null).etatDepot("/projets/depot")

            assertTrue(etat is EtatDepot.Inaccessible)
            assertEquals(RaisonDepotInaccessible.BINAIRE_ABSENT, (etat as EtatDepot.Inaccessible).raison)
            assertEquals(0, lanceur.lancements.size)
        }

    @Test
    fun `etatDepot dit Inaccessible quand le lancement echoue`() =
        runTest {
            lanceur.echecLancement = IOException("Permission denied")

            val etat = moteur().etatDepot("/projets/depot")

            assertTrue(etat is EtatDepot.Inaccessible)
            assertEquals(RaisonDepotInaccessible.LANCEMENT_IMPOSSIBLE, (etat as EtatDepot.Inaccessible).raison)
        }

    @Test
    fun `l echec de rev-parse est journalise avec commande code et stderr`() =
        runTest {
            lanceur.fabrique = {
                ProcessusScripte(
                    codeSortie = 128,
                    lignesStderr = listOf("fatal: detected dubious ownership in repository at /projets/depot"),
                )
            }

            moteur().etatDepot("/projets/depot")

            val entrees = journal.entries.filter { it.tag == "git-cli" }
            assertTrue(entrees.isNotEmpty())
            val avertissement = entrees.first()
            assertTrue(avertissement.message.contains("rev-parse --is-inside-work-tree"))
            assertTrue(avertissement.message.contains("128"))
            assertTrue(avertissement.message.contains("dubious ownership"))
        }

    // ------------------------------------------------------------------
    // diagnostiquer — assemblage du rapport
    // ------------------------------------------------------------------

    @Test
    fun `le diagnostic assemble binaire version rev-parse config et sondes`() =
        runTest {
            // Une réponse par commande : --version, rev-parse, config.
            lanceur.fabrique = { commande ->
                when {
                    "--version" in commande.command -> {
                        ProcessusScripte(lignesStdout = listOf("git version 2.47.3"))
                    }

                    "rev-parse" in commande.command -> {
                        ProcessusScripte(
                            codeSortie = 128,
                            lignesStderr = listOf("fatal: detected dubious ownership in repository at /projets/depot"),
                        )
                    }

                    else -> {
                        ProcessusScripte(
                            lignesStdout = listOf("file:/prefix/etc/gitconfig  safe.directory=/projets/depot"),
                        )
                    }
                }
            }

            // Chemin SOUS le faux point de montage des sondes factices
            // (/storage/emulated/0) — le plus long préfixe gagne.
            val rapport =
                moteur().diagnostiquer(
                    nomProjet = "ProjetA",
                    cheminFuse = "/storage/emulated/0/depot",
                )

            assertEquals("ProjetA", rapport.nomProjet)
            assertEquals("/storage/emulated/0/depot", rapport.cheminFuse)
            assertEquals("/prefix/bin/git", rapport.cheminBinaire)
            assertEquals("git version 2.47.3", rapport.versionGit)
            assertEquals(10_123L, rapport.uidEffectif)
            assertEquals(10_247L, rapport.uidProprietaireDossier)
            assertEquals("/storage/emulated/0", rapport.pointDeMontage)
            assertEquals("fuse", rapport.typeSystemeFichiers)
            assertNotNull(rapport.revParse)
            assertEquals(128, rapport.revParse?.code)
            assertTrue(rapport.revParse?.sortieErreur?.contains("dubious ownership") == true)
            assertNotNull(rapport.configList)
            assertTrue(rapport.configList?.sortieStandard?.contains("safe.directory") == true)
            // Environnement filtré (LANG écarté) : HOME/PATH/TMPDIR/GIT_*,
            // triés — un rapport reproductible d'une exécution à l'autre.
            assertEquals(listOf("GIT_CONFIG_GLOBAL", "HOME", "PATH", "TMPDIR"), rapport.environnement.keys.toList())
        }

    @Test
    fun `sans projet le diagnostic garde les sondes globales`() =
        runTest {
            // La fabrique répond à --version (la seule commande exécutée).
            lanceur.fabrique = { commande ->
                when {
                    "--version" in commande.command -> {
                        ProcessusScripte(lignesStdout = listOf("git version 2.47.3"))
                    }

                    else -> {
                        ProcessusScripte()
                    }
                }
            }

            val rapport = moteur().diagnostiquer(nomProjet = null, cheminFuse = null)

            assertNull(rapport.nomProjet)
            assertNull(rapport.cheminFuse)
            assertNull(rapport.revParse)
            assertNull(rapport.configList)
            // Le cas « git est-il installé ? » reste diagnostiqué.
            assertEquals("/prefix/bin/git", rapport.cheminBinaire)
            assertEquals("git version 2.47.3", rapport.versionGit)
            assertNotNull(rapport.uidEffectif)
            assertEquals(1, lanceur.lancements.size) // --version seul : rien d'autre à exécuter.
        }

    @Test
    fun `sans binaire le diagnostic rapporte les echecs sans exception`() =
        runTest {
            val rapport = moteur(binaire = null).diagnostiquer(nomProjet = "ProjetA", cheminFuse = "/projets/depot")

            assertNull(rapport.cheminBinaire)
            assertNull(rapport.versionGit)
            assertEquals(null, rapport.revParse?.code)
            assertTrue(rapport.revParse?.sortieErreur?.contains("git n'est pas installé") == true)
        }

    // ------------------------------------------------------------------
    // Cas d'usage — projet le plus récent, chemin irrésolvable
    // ------------------------------------------------------------------

    @Test
    fun `le cas d usage diagnostique le projet le plus recemment ouvert`() =
        runTest {
            val projets = FakeProjectRepository()
            val moteurGit = FakeMoteurGit()
            enregistrerProjet(projets, "Ancien", horodatageOuverture = 100L)
            enregistrerProjet(projets, "Recent", horodatageOuverture = 500L)
            val useCase =
                DiagnostiquerGitProjetUseCase(
                    depotProjets = projets,
                    resoudreChemin = { "/projets/resolu" },
                    moteurGit = moteurGit,
                    repartiteurs = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                )

            useCase()

            assertEquals(1, moteurGit.operations.count { it.startsWith("diagnostiquer:Recent") })
        }

    @Test
    fun `le cas d usage passe chemin null quand le FUSE est irresolvable`() =
        runTest {
            val projets = FakeProjectRepository()
            val moteurGit = FakeMoteurGit()
            enregistrerProjet(projets, "ProjetA", horodatageOuverture = 100L)
            val useCase =
                DiagnostiquerGitProjetUseCase(
                    depotProjets = projets,
                    resoudreChemin = { null },
                    moteurGit = moteurGit,
                    repartiteurs = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                )

            useCase()

            assertEquals(1, moteurGit.operations.count { it == "diagnostiquer:ProjetA" })
            assertNull(moteurGit.rapportDiagnostic.cheminFuse)
        }

    @Test
    fun `le cas d usage sans projet appelle le diagnostic global`() =
        runTest {
            val moteurGit = FakeMoteurGit()
            val useCase =
                DiagnostiquerGitProjetUseCase(
                    depotProjets = FakeProjectRepository(),
                    resoudreChemin = { "/projets/resolu" },
                    moteurGit = moteurGit,
                    repartiteurs = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                )

            useCase()

            assertEquals(1, moteurGit.operations.count { it == "diagnostiquer:-" })
        }

    @Test
    fun `les operations metier restent integrales apres le refactoring`() =
        runTest {
            // Le remaniement d'executer vers executerBrut ne doit rien
            // changer au comportement métier (régression v0.80.7).
            lanceur.fabrique = { ProcessusScripte(lignesStdout = listOf("M  fichier.txt")) }

            val statut = moteur().statut("/projets/depot")

            assertTrue(statut is ResultatGit.Succes)
            assertEquals(1, (statut as ResultatGit.Succes).valeur.modifications.size)
            assertEquals(
                "fichier.txt",
                statut.valeur.modifications
                    .first()
                    .chemin,
            )
        }

    /** Sondes fictives : uid/propriétaire différents (cas dubious ownership), montages typiques. */
    private class SondesFictives : SondesEnvironnementGit {
        override fun uidEffectif(): Long? = 10_123L

        override fun uidProprietaire(chemin: String): Long? = 10_247L

        override fun contenuMonts(): String? =
            listOf(
                "/dev/block/bootdevice / ext4 rw 0 0",
                "/dev/fuse /storage/emulated/0 fuse rw 0 0",
                "/dev/fuse /storage/emulated/0/Documents fuse rw 0 0",
            ).joinToString("\n")

        override fun environnement(): Map<String, String> =
            mapOf(
                "HOME" to "/prefix/home",
                "PATH" to "/prefix/bin:/system/bin",
                "GIT_CONFIG_GLOBAL" to "/prefix/home/.gitconfig",
                "TMPDIR" to "/prefix/tmp",
                "LANG" to "fr_FR.UTF-8",
            )
    }

    /** Enregistre un projet ouvert à [horodatageOuverture] (API du fake). */
    private suspend fun enregistrerProjet(
        projets: FakeProjectRepository,
        nom: String,
        horodatageOuverture: Long,
    ) {
        val resultat =
            projets.addProject(
                name = nom,
                description = "",
                location =
                    jo.codeide.core.model.StorageLocation(
                        grantUri = "content://autorite/tree/primary%3Aprojets",
                        documentUri = "content://autorite/tree/primary%3Aprojets/document/primary%3Aprojets%2F$nom",
                        displayPath = "/projets/$nom",
                    ),
                templateId = jo.codeide.core.model.TemplateId.IMPORTED,
            )
        val projet = (resultat as jo.codeide.core.model.AppResult.Success).value
        projets.markOpened(projet.id, horodatageOuverture)
    }
}
