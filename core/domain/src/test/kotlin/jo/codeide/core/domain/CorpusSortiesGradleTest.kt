package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NON-RÉGRESSION sur le corpus RÉEL (spec SORTIES § 12, lot L1) — chaque
 * log capturé du dépôt (`docs/archive/tooling-scenarios/sorties/`, 11
 * scénarios daemon froid/vivant, deps, multi-modules, Groovy DSL,
 * buildSrc, erreur de configuration, cache hors ligne, résolution de
 * dépendances) repasse par le parseur : verdict attendu au fil du
 * fichier, zéro exception, texte transparé.
 *
 * Les fichiers se lisent depuis le répertoire du MODULE (`core/domain`,
 * répertoire de travail des tests Gradle) : deux niveaux au-dessus.
 */
class CorpusSortiesGradleTest {
    private val parseur = ParseurFluxGradle()

    /** Les chemins des 11 logs du corpus, triés comme le dépôt les nomme. */
    private fun corpus(): List<File> = File("../../docs/archive/tooling-scenarios/sorties")
        .listFiles { fichier -> fichier.extension == "log" }
        ?.sortedBy { it.name }
        ?: emptyList()

    /** Charge UN log en lignes (terminaison retirée, `\r` compris). */
    private fun charger(nom: String): List<String> {
        val brut = File("../../docs/archive/tooling-scenarios/sorties/$nom").readText()
        return brut.split("\n").dropLastWhile { it.isEmpty() }
    }

    /** La durée du verdict de la dernière ligne « BUILD … in … ». */
    private fun verdictDuFlux(lignes: List<String>): VerdictGradle? {
        var verdict: VerdictGradle? = null
        for (ligne in lignes) {
            parseur.traiterLigne(ligne).verdict?.let { verdict = it }
        }
        return verdict
    }

    @Test
    fun `chaque log du corpus passe au parseur sans exception et transparé`() {
        val fichiers = corpus()
        assertEquals(11, fichiers.size)
        for (fichier in fichiers) {
            val lignes = fichier.readText().split("\n").dropLastWhile { it.isEmpty() }
            parseur.reinitialiser()
            var compteur = 0
            for (ligne in lignes) {
                val analyse = parseur.traiterLigne(ligne, horodatageMs = compteur.toLong())
                // Le texte transparé : le parseur ne reformule rien.
                assertEquals(ligne, analyse.texte)
                compteur += 1
            }
            assertEquals(lignes.size, compteur)
        }
    }

    @Test
    fun `les builds reussis du corpus concluent par le verdict attendu`() {
        // Fichiers, puis (durée imprimée, résumé « 1 task: 1 executed »).
        val cas = listOf(
            "01-kotlin-jvm-pur-daemon-froid.log" to 42_000L,
            "02-kotlin-jvm-pur-daemon-vivant.log" to 954L,
            "03-kotlin-jvm-avec-deps-premier-run.log" to 2_000L,
            "04-kotlin-jvm-avec-deps-chaud.log" to 1_000L,
            "05-multi-modules.log" to 3_000L,
            "06-groovy-dsl.log" to 2_000L,
            "09-offline-cache-complet.log" to 1_000L,
            "10-deps-kotlinx-datetime.log" to 2_000L,
            "10b-dependencies-resolution.log" to 1_000L,
        )
        for ((nom, dureeAttendue) in cas) {
            parseur.reinitialiser()
            val lignes = charger(nom)
            val verdict = verdictDuFlux(lignes)

            assertTrue("verdict du corpus $nom", verdict?.reussi == true)
            assertEquals("durée du corpus $nom", dureeAttendue, verdict?.dureeMs)
            // Aucun bloc d'échec dans un build réussi.
            for (ligne in lignes) {
                val analyse = parseur.traiterLigne(ligne)
                assertFalse("bloc d'échec dans $nom : ${ligne.take(60)}", analyse.dansBlocEchec)
            }
        }
    }

    @Test
    fun `le buildsrc echoue avec son resume de sept taches executees`() {
        val lignes = charger("07-avec-buildsrc.log")
        val verdict = verdictDuFlux(lignes)

        assertTrue(verdict?.reussi == false)
        assertEquals(22_000L, verdict?.dureeMs)
    }

    @Test
    fun `la configuration fautive ouvre toutes ses sections sans trace`() {
        val lignes = charger("08-erreur-config.log")
        val verdict = verdictDuFlux(lignes)

        assertTrue(verdict?.reussi == false)
        assertEquals(1_000L, verdict?.dureeMs)

        val sections = lignes.map { parseur.traiterLigne(it).section }
        assertTrue("intitulé FAILURE manquant", SectionFluxGradle.ECHEC_INTITULE in sections)
        assertTrue("section Where manquante", SectionFluxGradle.ECHEC_OU in sections)
        assertTrue("section What went wrong manquante", SectionFluxGradle.ECHEC_CAUSE in sections)
        assertTrue("section Try manquante", SectionFluxGradle.ECHEC_CONSEIL in sections)
        assertFalse("trace inattendue dans 08", SectionFluxGradle.ECHEC_TRACE in sections)
    }

    @Test
    fun `les deux positions de la configuration fautive sont extraites`() {
        val positions = mutableListOf<PositionSource>()
        for (ligne in charger("08-erreur-config.log")) {
            positions.addAll(parseur.traiterLigne(ligne).positions)
        }

        // La ligne `e: file:///…build.gradle.kts:2:8:` (compilation du script).
        val erreurKotlin = positions.singleOrNull { it.fichier.endsWith("build.gradle.kts") && it.colonne != null }
        assertEquals("build.gradle.kts", erreurKotlin?.fichier?.substringAfterLast('/'))
        assertEquals(2, erreurKotlin?.ligne)
        assertEquals(8, erreurKotlin?.colonne)
        assertEquals(GravitePosition.ERREUR, erreurKotlin?.gravite)

        // La ligne `Build file '…' line: 2` de la section * Where:.
        val emplacementScript = positions.singleOrNull { it.fichier.endsWith("build.gradle.kts") && it.colonne == null }
        assertEquals(2, emplacementScript?.ligne)
        assertEquals(GravitePosition.ERREUR, emplacementScript?.gravite)
    }

    @Test
    fun `les en tetes de taches du buildsrc gardent leur statut fin`() {
        var sautees = 0
        var echouees = 0
        var annonces = 0
        for (ligne in charger("07-avec-buildsrc.log")) {
            val analyse = parseur.traiterLigne(ligne)
            if (analyse.tacheAnnoncee != null) {
                annonces += 1
                when (analyse.statutTexte) {
                    StatutTacheGradle.SAUTEE -> sautees += 1
                    StatutTacheGradle.ECHOUEE -> echouees += 1
                    else -> Unit
                }
            }
        }

        assertEquals(9, annonces)
        assertEquals(1, sautees)
        assertEquals(1, echouees)
    }

    @Test
    fun `le resume du buildsrc compte sept taches executees`() {
        var resume: ResumeTachesGradle? = null
        for (ligne in charger("07-avec-buildsrc.log")) {
            parseur.traiterLigne(ligne).resume?.let { resume = it }
        }

        assertEquals(7, resume?.actionnables)
        assertEquals(7, resume?.executees)
        assertNull(resume?.aJour)
    }
}
