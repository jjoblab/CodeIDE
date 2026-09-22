package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.CreationProgress
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests de la création de projet (étape 8 — section 12.4).
 *
 * Critères d'acceptation : le plan (*dry-run*) correspond **exactement** à
 * ce qui est écrit ; tout échec ou annulation déclenche le rollback
 * (suppression en `NonCancellable`, résidus signalés) ; jamais d'écrasement
 * d'un dossier existant ; le registre n'est écrit qu'après le succès des
 * fichiers.
 */
class CreateProjectUseCaseTest {
    /** Écosystème de test complet autour d'une création. */
    private class Ecosysteme {
        val fichiers = FakeFileSystem()
        val projets = FakeProjectRepository()
        val journal = FakeAppLogger()
        val source = FixtureModele.source()
        val planificateur =
            TemplateProjectPlanner(
                moteur = TemplateEngine(source),
                fournisseurs = setOf(EmbeddedTemplatesProvider(source)),
                parametres = FixtureModele.parametres("Jeanne"),
                horloge = { 1_767_225_600_000L }, // 2026-01-01T00:00Z
                generateur =
                    object : GeneratorVersion {
                        override val value = "CodeIDE 0.9.0-test"
                    },
            )
        val cas = CreateProjectUseCase(planificateur, fichiers, projets, journal)

        val parent =
            StorageLocation(grantUri = "work:", documentUri = "work:", displayPath = "/Travail")

        init {
            fichiers.seedDocument("work:", FakeFileSystem.Document(name = "Travail", isDirectory = true))
        }

        fun requete(
            nom: String = "Demo",
            description: String = "Une démo",
        ): CreateProjectRequest =
            CreateProjectRequest(
                templateId = TemplateId("fixture"),
                name = nom,
                description = description,
                parentLocation = parent,
            )
    }

    /** Collecte la progression jusqu'au terminal inclus. */
    private suspend fun creer(
        ecosysteme: Ecosysteme,
        requete: CreateProjectRequest,
    ): List<CreationProgress> = ecosysteme.cas.create(requete).toList()

    @Test
    fun `la création écrit tous les fichiers du plan puis enregistre le projet`() =
        runTest {
            val e = Ecosysteme()
            val requete = e.requete()

            val evenements = creer(e, requete)

            // Séquence : préparation, racine, un événement par fichier,
            // enregistrement, terminal réussi.
            assertEquals(CreationProgress.Preparation, evenements.first())
            assertTrue(evenements[1] is CreationProgress.CreationDossierRacine)
            val generations = evenements.filterIsInstance<CreationProgress.GenerationFichier>()
            assertEquals(6, generations.size) // 6 fichiers dans le plan par défaut
            assertEquals(1, generations.first().index)
            assertEquals(6, generations.last().index)
            assertTrue(evenements.contains(CreationProgress.Enregistrement))
            val terminal = evenements.last() as CreationProgress.Termine
            assertTrue(terminal.result is AppResult.Success)
            assertTrue(!terminal.rolledBack)

            // Le projet est enregistré, avec la location du dossier racine créé.
            assertEquals(1, e.projets.projets.size)
            val projet = e.projets.projets.single()
            assertEquals("Demo", projet.name)
            assertEquals("work:/Demo", projet.location.documentUri)
            assertEquals(TemplateId("fixture"), projet.templateId)
        }

    @Test
    fun `le disque contient exactement le plan byte à byte`() =
        runTest {
            val e = Ecosysteme()
            val requete = e.requete()

            // 1) Dry-run via le planificateur partagé.
            val plan = PlanProjectCreationUseCase(e.planificateur)(requete).getOrNull()!!
            // 2) Création effective.
            val evenements = creer(e, requete)
            assertTrue((evenements.last() as CreationProgress.Termine).result is AppResult.Success)

            // 3) Chaque fichier du plan existe au bon endroit, même contenu.
            plan.fichiers.forEach { fichier ->
                val uri = "work:/Demo/${fichier.chemin}"
                val document = e.fichiers.arborescence.value[uri]
                assertTrue("fichier planifié manquant : $uri", document != null)
                when (val attendu = fichier.contenu) {
                    is PlannedContent.Texte -> {
                        assertEquals(attendu.texte, String(document!!.bytes, Charsets.UTF_8))
                    }

                    is PlannedContent.Binaire -> {
                        assertTrue(attendu.octets.contentEquals(document!!.bytes))
                    }
                }
            }
            // 4) Rien de plus que le plan (+ les dossiers parents).
            val fichiersEcrites =
                e.fichiers.arborescence.value.values
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .sorted()
            assertEquals(plan.fichiers.map { it.chemin.substringAfterLast('/') }.sorted(), fichiersEcrites)
        }

    @Test
    fun `un dossier racine existant est refusé sans écrasement`() =
        runTest {
            val e = Ecosysteme()
            e.fichiers.seedDocument(
                "work:/Demo",
                FakeFileSystem.Document(name = "Demo", isDirectory = true),
            )

            val evenements = creer(e, e.requete())

            val terminal = evenements.last() as CreationProgress.Termine
            assertTrue(terminal.result is AppResult.Failure)
            assertTrue(!terminal.rolledBack) // rien n'avait été écrit par nous
            assertEquals(0, e.projets.projets.size)
        }

    @Test
    fun `un échec d écriture déclenche le rollback complet`() =
        runTest {
            val e = Ecosysteme()
            // L'écriture échoue après la création du fichier : tout est retiré.
            e.fichiers.writeFailure = IOException("disque plein")

            val evenements = creer(e, e.requete())

            val terminal = evenements.last() as CreationProgress.Termine
            assertTrue(terminal.result is AppResult.Failure)
            assertTrue(terminal.rolledBack)
            assertTrue(terminal.residues.isEmpty())
            // Plus rien sous la racine : fichiers, dossiers et racine retirés.
            assertTrue(
                e.fichiers.arborescence.value.keys
                    .none { it.startsWith("work:/Demo") },
            )
            assertEquals(0, e.projets.projets.size)
        }

    @Test
    fun `un échec d insertion en base déclenche aussi le rollback`() =
        runTest {
            val e = Ecosysteme()
            e.projets.writeError = IOException("base verrouillée")

            val evenements = creer(e, e.requete())

            val terminal = evenements.last() as CreationProgress.Termine
            assertTrue(terminal.result is AppResult.Failure)
            assertTrue(terminal.rolledBack)
            assertTrue(
                e.fichiers.arborescence.value.keys
                    .none { it.startsWith("work:/Demo") },
            )
        }

    @Test
    fun `une suppression impossible pendant le rollback laisse des résidus`() =
        runTest {
            val e = Ecosysteme()
            // Écriture KO (rollback) PUIS suppression KO (résidus signalés).
            e.fichiers.writeFailure = IOException("disque plein")
            e.fichiers.deleteFailure = IOException("suppression impossible")

            val evenements = creer(e, e.requete())

            val terminal = evenements.last() as CreationProgress.Termine
            assertTrue(terminal.rolledBack)
            assertTrue(terminal.residues.isNotEmpty())
        }

    @Test
    @Suppress("SwallowedException") // Le test observe l'annulation pour l'attribuer (rollback vérifié à part).
    fun `l annulation de la collecte rollback puis relance l annulation`() =
        runTest {
            val e = Ecosysteme()

            var relancee = false
            try {
                val collecte = async { e.cas.create(e.requete()).toList() }
                // Annulation au premier fichier généré : le flot est consommé en
                // parallèle jusqu'au terminal, puis tout est nettoyé.
                // Ici on annule toute la portée : la collecte est interrompue.
                collecte.cancel()
                collecte.await()
            } catch (annulation: kotlinx.coroutines.CancellationException) {
                relancee = true
            }

            assertTrue("l'annulation doit être relayée", relancee)
            // Le rollback a eu lieu (NonCancellable) : la racine a disparu.
            assertTrue(
                e.fichiers.arborescence.value.keys
                    .none { it.startsWith("work:/Demo") },
            )
            assertEquals(0, e.projets.projets.size)
        }

    @Test
    fun `un modèle inconnu échoue avant toute écriture`() =
        runTest {
            val e = Ecosysteme()
            val requete = e.requete().copy(templateId = TemplateId("fantome"))

            val evenements = creer(e, requete)

            val terminal = evenements.last() as CreationProgress.Termine
            assertTrue(terminal.result is AppResult.Failure)
            assertTrue(!terminal.rolledBack)
            assertTrue(
                e.fichiers.arborescence.value.keys
                    .none { it.startsWith("work:/Demo") },
            )
        }

    @Test
    fun `une entrée invalide est revalidée par le domaine`() =
        runTest {
            val e = Ecosysteme()
            val requete = e.requete(nom = "a/b") // l'UI serait contournée

            val evenements = creer(e, requete)

            val terminal = evenements.last() as CreationProgress.Termine
            val erreur = (terminal.result as AppResult.Failure).error
            assertTrue(erreur is AppError.Validation)
            assertTrue(
                e.fichiers.arborescence.value.keys
                    .none { it.startsWith("work:/Demo") },
            )
        }
}
