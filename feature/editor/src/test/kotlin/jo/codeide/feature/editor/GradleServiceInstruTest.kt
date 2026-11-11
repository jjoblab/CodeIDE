package jo.codeide.feature.editor

import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtapeSyncTooling
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.LigneSortieSync
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeAppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests INC-44 (Phase 1, ADR 0152) du [GradleService] : la sync conclue
 * laisse dans le journal applicatif SA latence de publication (miroir
 * client du résumé de transport de GradleApiImpl) et le récapitulatif de
 * ses phases — l'angle mort n°4 (sync de 194 s sans la moindre trace) se
 * referme.
 */
class GradleServiceInstruTest {
    /** Horloge pilotable — les écarts de latence se vérifient sans cadre Android. */
    private var instant = 10_000L

    private val journal = FakeAppLogger()

    private val service =
        GradleService(horloge = TimeProvider { instant }, demarreur = FauxDemarreurServiceTooling(), journal = journal)

    @Test
    fun `INC-44 - la sync conclue journalise sa latence de publication et son recapitulatif de phases`() {
        service.marquerSyncEnCours()
        // Une ligne émise 500 ms plus tôt (horodatage serveur) : l'écart de
        // publication se mesure à l'arrivée dans la zone texte.
        service.ajouterLigneSync(LigneSortieSync("/p", FluxSortieBuild.STDOUT, "a", horodatageMs = instant - 500))
        service.ajouterEtapeSync(
            EtapeSyncTooling(projectDir = "/p", etape = EtapeSync.CONFIGURATION, terminee = true, dureeMs = 1_200),
        )
        service.ajouterEtapeSync(
            EtapeSyncTooling(projectDir = "/p", etape = EtapeSync.MODELE_TACHES, terminee = true, dureeMs = 3_400),
        )

        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 5_000)),
        )

        val publication = journal.entries.single { it.message.startsWith("zone texte de la sync alimentée") }.message
        assertTrue("la publication compte les lignes : $publication", publication.contains("1 ligne"))
        assertTrue("la publication mesure l'écart max : $publication", publication.contains("max 500 ms"))

        val phases = journal.entries.single { it.message.startsWith("sync réussie") }.message
        assertTrue(
            "le récapitulatif porte le verdict et la durée : $phases",
            phases.contains("sync réussie en 5000 ms"),
        )
        assertTrue(
            "le récapitulatif détaille les phases dans l'ordre : $phases",
            phases.contains("CONFIGURATION 1200 ms"),
        )
        assertTrue(
            "le récapitulatif détaille les phases dans l'ordre : $phases",
            phases.contains("MODELE_TACHES 3400 ms"),
        )
    }

    @Test
    fun `INC-44 - une sync sans ligne publie zero ligne et dit quand meme ses phases`() {
        service.marquerSyncEnCours()
        service.ajouterEtapeSync(
            EtapeSyncTooling(projectDir = "/p", etape = EtapeSync.DAEMON, terminee = false, dureeMs = 300),
        )

        service.publierResultatSync(
            AppResult.Success(
                ResultatSynchronisation(
                    projectDir = "/p",
                    reussie = false,
                    dureeMs = 800,
                    messageEchec = "échec simulé",
                ),
            ),
        )

        val publication = journal.entries.single { it.message.startsWith("zone texte de la sync alimentée") }.message
        assertTrue(
            "aucune ligne publiée se DIT (c'est une information) : $publication",
            publication.contains("0 ligne"),
        )

        val phases = journal.entries.single { it.message.startsWith("sync échouée") }.message
        assertTrue("le récapitulatif porte la durée : $phases", phases.contains("sync échouée en 800 ms"))
        assertTrue("le récapitulatif détaille la phase : $phases", phases.contains("DAEMON 300 ms"))
    }

    @Test
    fun `INC-44 - une conclusion dupliquee ne rejournalise pas les mesures`() {
        val resultat =
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 5_000))

        service.marquerSyncEnCours()
        service.ajouterLigneSync(LigneSortieSync("/p", FluxSortieBuild.STDOUT, "a", horodatageMs = instant))
        service.publierResultatSync(resultat)
        // Le MÊME verdict conclus par une seconde voie (échec local tardif,
        // SyncResult dupliqué) : la déduplication par contenu s'applique.
        service.publierResultatSync(resultat)

        assertEquals(1, journal.entries.count { it.message.startsWith("zone texte de la sync alimentée") })
        assertEquals(1, journal.entries.count { it.message.startsWith("sync ") })
    }

    @Test
    fun `INC-44 - l accumulateur repart vierge a la sync suivante`() {
        service.marquerSyncEnCours()
        service.ajouterLigneSync(LigneSortieSync("/p", FluxSortieBuild.STDOUT, "a", horodatageMs = instant - 9_000))
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 1_000)),
        )

        // NOUVELLE sync : la ligne de la précédente ne pèse plus.
        service.marquerSyncEnCours()
        service.ajouterLigneSync(LigneSortieSync("/p", FluxSortieBuild.STDOUT, "b", horodatageMs = instant))
        service.publierResultatSync(
            AppResult.Success(ResultatSynchronisation(projectDir = "/p", reussie = true, dureeMs = 2_000)),
        )

        val publications = journal.entries.filter { it.message.startsWith("zone texte de la sync alimentée") }
        assertEquals(2, publications.size)
        assertTrue(
            "la seconde sync repart à zéro : ${publications[1].message}",
            publications[1].message.contains("max 0 ms"),
        )
    }
}
