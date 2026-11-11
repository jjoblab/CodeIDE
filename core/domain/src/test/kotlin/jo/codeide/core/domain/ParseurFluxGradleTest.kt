package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du parseur de flux Gradle (ADR 0160 § Décision 4, lot L1) —
 * frontières de tâches (en-tête texte + événements, jamais devinées),
 * verdicts et résumés, sections du bloc d'échec, positions cliquables
 * extraites au passage, remise à zéro pour une nouvelle exécution.
 */
class ParseurFluxGradleTest {
    private val parseur = ParseurFluxGradle()

    @Test
    fun `l en tete de tache pose la frontiere et son statut texte`() {
        val cas = listOf(
            Triple("> Task :app:compileDebugKotlin", StatutTacheGradle.EXECUTEE, ":app:compileDebugKotlin"),
            Triple("> Task :app:preBuild UP-TO-DATE", StatutTacheGradle.A_JOUR, ":app:preBuild"),
            Triple("> Task :lib:x FROM-CACHE", StatutTacheGradle.DEPUIS_CACHE, ":lib:x"),
            Triple("> Task :app:mergeResources NO-SOURCE", StatutTacheGradle.SANS_SOURCE, ":app:mergeResources"),
            Triple("> Task :app:compileKotlin FAILED", StatutTacheGradle.ECHOUEE, ":app:compileKotlin"),
            Triple("> Task :app:lintDebug SKIPPED", StatutTacheGradle.SAUTEE, ":app:lintDebug"),
        )
        for ((enTete, statutAttendu, cheminAttendu) in cas) {
            parseur.reinitialiser()
            val analyse = parseur.traiterLigne(enTete)

            assertEquals(cheminAttendu, analyse.tacheAnnoncee)
            assertEquals(cheminAttendu, analyse.tacheCourante)
            assertEquals(statutAttendu, analyse.statutTexte)
            assertEquals(SectionFluxGradle.FLUX, analyse.section)
            assertFalse(analyse.dansBlocEchec)
        }
    }

    @Test
    fun `les lignes de contenu se rattachent au groupe courant jamais devine`() {
        parseur.traiterLigne("> Task :app:compileDebugKotlin")
        val contenu = parseur.traiterLigne("w: fichier inconnu")

        assertNull(contenu.tacheAnnoncee)
        assertNull(contenu.statutTexte)
        assertEquals(":app:compileDebugKotlin", contenu.tacheCourante)

        // Sans frontière posée (aucun événement, aucun en-tête), une ligne
        // de contenu reste SANS tâche — jamais devinée de son texte (Q6).
        parseur.reinitialiser()
        val orpheline = parseur.traiterLigne("Task ':app:compileDebugKotlin' is not up-to-date")
        assertNull(orpheline.tacheCourante)
        assertNull(orpheline.tacheAnnoncee)
        // Une ligne qui mentionne « Task » mais pas sous la forme d'un
        // en-tête `> Task :…` n'ouvre pas de frontière.
        parseur.traiterLigne("Task ':app:preBuild' is not up-to-date because:")
        val suivant = parseur.traiterLigne("  No history is available.")
        assertNull(suivant.tacheCourante)
    }

    @Test
    fun `l evenement de tache pose la frontiere et la conclusion la laisse`() {
        parseur.surTache(
            EtatTacheBuild(
                buildId = "b1",
                chemin = ":app:compileDebugKotlin",
                statut = StatutTache.EN_COURS,
            ),
        )
        assertEquals(":app:compileDebugKotlin", parseur.traiterLigne("génération en cours").tacheCourante)

        // La CONCLUSION ne bascule pas le groupe : les lignes postérieures
        // (détails de la tâche) restent sous sa rangée.
        parseur.surTache(
            EtatTacheBuild(
                buildId = "b1",
                chemin = ":app:compileDebugKotlin",
                statut = StatutTache.REUSSIE,
                dureeMs = 1_250L,
            ),
        )
        assertEquals(":app:compileDebugKotlin", parseur.traiterLigne("Sortie postérieure").tacheCourante)

        // La FRONTIÈRE suivante (nouvel événement EN_COURS) bascule.
        parseur.surTache(
            EtatTacheBuild(buildId = "b1", chemin = ":app:preBuild", statut = StatutTache.EN_COURS),
        )
        assertEquals(":app:preBuild", parseur.traiterLigne("autre tâche").tacheCourante)
    }

    @Test
    fun `les verdicts de fin portent leur duree convertie en millisecondes`() {
        val cas = listOf(
            Triple("BUILD SUCCESSFUL in 42s", true, 42_000L),
            Triple("BUILD FAILED in 2m 3s", false, 123_000L),
            Triple("BUILD SUCCESSFUL in 954ms", true, 954L),
            Triple("BUILD SUCCESSFUL in 1h 2m", true, 3_720_000L),
            Triple("BUILD FAILED in 3.4s", false, 3_400L),
        )
        for ((texte, reussiAttendu, dureeAttendue) in cas) {
            parseur.reinitialiser()
            val analyse = parseur.traiterLigne(texte)

            assertEquals(reussiAttendu, analyse.verdict?.reussi)
            assertEquals(dureeAttendue, analyse.verdict?.dureeMs)
            assertEquals(SectionFluxGradle.FLUX, analyse.section)
        }
        // Sans « in … » imprimé, le texte n'est pas un verdict.
        assertNull(parseur.traiterLigne("BUILD SUCCESSFUL").verdict)
        assertNull(parseur.traiterLigne("Starting Build").verdict)
    }

    @Test
    fun `le resume des taches lit singulier pluriel et tous les statuts`() {
        val singulier = parseur.traiterLigne("1 actionable task: 1 executed")
        assertEquals(1, singulier.resume?.actionnables)
        assertEquals(1, singulier.resume?.executees)
        assertNull(singulier.resume?.aJour)

        parseur.reinitialiser()
        val pluriel = parseur.traiterLigne(
            "12 actionable tasks: 6 executed, 3 up-to-date, 2 from-cache, 1 no-source, 1 skipped",
        )
        assertEquals(12, pluriel.resume?.actionnables)
        assertEquals(6, pluriel.resume?.executees)
        assertEquals(3, pluriel.resume?.aJour)
        assertEquals(2, pluriel.resume?.depuisCache)
        assertEquals(1, pluriel.resume?.sansSource)
        assertEquals(1, pluriel.resume?.sautees)
    }

    @Test
    fun `les sections d echec avancent jusqu au verdict qui ramene au flux`() {
        val sequence = listOf(
            Triple("> Task :app:compileKotlin FAILED", SectionFluxGradle.FLUX, false),
            Triple("FAILURE: Build failed with an exception.", SectionFluxGradle.ECHEC_INTITULE, true),
            Triple("* Where:", SectionFluxGradle.ECHEC_OU, true),
            Triple("Build file '/p/build.gradle.kts' line: 2", SectionFluxGradle.ECHEC_OU, true),
            Triple("* What went wrong:", SectionFluxGradle.ECHEC_CAUSE, true),
            Triple("Script compilation error:", SectionFluxGradle.ECHEC_CAUSE, true),
            Triple("Caused by: org.gradle.api.InvalidUserCodeException: x", SectionFluxGradle.ECHEC_CAUSE, true),
            Triple("* Try:", SectionFluxGradle.ECHEC_CONSEIL, true),
            Triple("> Run with --stacktrace option to get the stack trace.", SectionFluxGradle.ECHEC_CONSEIL, true),
            Triple("* Exception is:", SectionFluxGradle.ECHEC_TRACE, true),
            Triple("org.gradle.internal.exceptions.LocationAwareException: x", SectionFluxGradle.ECHEC_TRACE, true),
            Triple("BUILD FAILED in 1s", SectionFluxGradle.FLUX, false),
        )
        for ((ligne, sectionAttendue, dansEchecAttendu) in sequence) {
            val analyse = parseur.traiterLigne(ligne)

            assertEquals(sectionAttendue, analyse.section)
            assertEquals(dansEchecAttendu, analyse.dansBlocEchec)
        }
    }

    @Test
    fun `les positions kotlin portent gravite chemin ligne et colonne`() {
        val avecColonne = parseur.traiterLigne(
            "e: file:///home/z/projet/build.gradle.kts:2:8: Unexpected tokens",
        ).positions
        assertEquals(1, avecColonne.size)
        assertEquals("/home/z/projet/build.gradle.kts", avecColonne[0].fichier)
        assertEquals(2, avecColonne[0].ligne)
        assertEquals(8, avecColonne[0].colonne)
        assertEquals(GravitePosition.ERREUR, avecColonne[0].gravite)

        val sansColonne = parseur.traiterLigne("e: file://src/main.kt:3: message d'erreur").positions
        assertEquals("src/main.kt", sansColonne[0].fichier)
        assertEquals(3, sansColonne[0].ligne)
        assertNull(sansColonne[0].colonne)

        val avertissement = parseur.traiterLigne("w: file://app/src/A.kt:10:1: never used").positions
        assertEquals("app/src/A.kt", avertissement[0].fichier)
        assertEquals(GravitePosition.AVERTISSEMENT, avertissement[0].gravite)

        // Une ligne sans position rend une liste vide.
        assertEquals(0, parseur.traiterLigne("Starting Build").positions.size)
    }

    @Test
    fun `les positions java et script se lisent au passage`() {
        val java = parseur.traiterLigne("MainActivity.java:12: error: cannot find symbol").positions
        assertEquals(1, java.size)
        assertEquals("MainActivity.java", java[0].fichier)
        assertEquals(12, java[0].ligne)
        assertNull(java[0].colonne)
        assertEquals(GravitePosition.ERREUR, java[0].gravite)

        val javaAverti = parseur.traiterLigne("Util.java:3:5: warning: unchecked").positions
        assertEquals(5, javaAverti[0].colonne)
        assertEquals(GravitePosition.AVERTISSEMENT, javaAverti[0].gravite)

        val script = parseur.traiterLigne("Build file '/p/build.gradle.kts' line: 2").positions
        assertEquals("/p/build.gradle.kts", script[0].fichier)
        assertEquals(2, script[0].ligne)
        assertEquals(GravitePosition.ERREUR, script[0].gravite)

        val scriptGroovy = parseur.traiterLigne("Script '/p/build.gradle' line: 9").positions
        assertEquals(9, scriptGroovy[0].ligne)
    }

    @Test
    fun `reinitialiser repart propre pour une nouvelle execution`() {
        parseur.traiterLigne("> Task :app:compileDebugKotlin")
        parseur.traiterLigne("FAILURE: Build failed with an exception.")

        parseur.reinitialiser()

        val apres = parseur.traiterLigne("une ligne quelconque")
        assertNull(apres.tacheCourante)
        assertEquals(SectionFluxGradle.FLUX, apres.section)
        assertFalse(apres.dansBlocEchec)
    }
}
