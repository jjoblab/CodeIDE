package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests de la dérivation regroupée (spec SORTIES § 4.1–4.3, lot L3) —
 * frontières de groupes (champ `tache` posé à l'ingestion, ancre texte en
 * dégradation), verdict et résumé (l'événement fait foi sur le texte),
 * bloc d'échec épinglé HORS des groupes, couverture stricte « chaque
 * ligne une fois », non-régression sur le corpus réel.
 */
class RegroupementSortiesTest {

    /** Une ligne de build avec le champ tâche déjà posé (ingestion). */
    private fun ligne(
        message: String,
        tache: String? = null,
        niveau: NiveauSortie = NiveauSortie.INDETERMINE,
        gravite: GravitePosition? = null,
    ) = LigneSortie(
        source = SourceSortie.BUILD,
        message = message,
        horodatageMs = 1L,
        tache = tache,
        niveau = niveau,
        positionsSource =
        gravite?.let {
            listOf(PositionSource("app/MainActivity.kt", 12, colonne = 5, gravite = it))
        } ?: emptyList(),
    )

    /**
     * La couverture stricte : groupes + bloc + hors-groupes partagent
     * chaque position EXACTEMENT une fois (critère § 10.1.1).
     */
    private fun assertCouverture(etat: EtatRegroupement, total: Int) {
        val couverts = ArrayList<Int>()
        etat.groupes.forEach { groupe ->
            assertTrue(
                "groupe ${groupe.chemin} bornes incohérentes",
                groupe.debut <= groupe.fin,
            )
            for (position in groupe.debut..groupe.fin) couverts.add(position)
        }
        couverts.addAll(etat.blocEchec)
        couverts.addAll(etat.horsGroupes)
        assertEquals("chaque ligne une fois", (0 until total).toList(), couverts.sorted())
    }

    @Test
    fun `l en tete texte ouvre le groupe et le contenu s y rattache`() {
        val lignes = listOf(
            ligne("Configuration du build"),
            ligne("> Task :app:compileDebugKotlin"),
            ligne("w: avertissement du compilateur"),
            ligne("sortie de la tâche"),
            ligne("> Task :app:assembleDebug UP-TO-DATE"),
            ligne("BUILD SUCCESSFUL in 3s"),
        )
        val etat = RegroupementSorties.deriver(lignes, execution = null)

        assertEquals(2, etat.groupes.size)
        assertEquals(":app:compileDebugKotlin", etat.groupes[0].chemin)
        assertEquals(StatutTacheGradle.EXECUTEE, etat.groupes[0].statutTexte)
        assertEquals(1, etat.groupes[0].debut)
        assertEquals(3, etat.groupes[0].fin)
        assertEquals(":app:assembleDebug", etat.groupes[1].chemin)
        assertEquals(StatutTacheGradle.A_JOUR, etat.groupes[1].statutTexte)
        // La configuration est hors groupe, le verdict aussi — l'en-tête
        // du second groupe EST sa première ligne (groupe 4..4).
        assertEquals(listOf(0, 5), etat.horsGroupes)
        assertTrue(etat.blocEchec.isEmpty())
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `le champ tache pose par l evenement fait foi sans ancre texte`() {
        val lignes = listOf(
            ligne("compilation…", tache = ":app:compileDebugKotlin"),
            ligne("liens…", tache = ":app:compileDebugKotlin"),
            ligne("dex…", tache = ":app:dexBuilderDebug"),
        )
        val etat = RegroupementSorties.deriver(lignes, execution = null)

        assertEquals(2, etat.groupes.size)
        assertEquals(":app:compileDebugKotlin", etat.groupes[0].chemin)
        assertEquals(0, etat.groupes[0].debut)
        assertEquals(1, etat.groupes[0].fin)
        assertEquals(":app:dexBuilderDebug", etat.groupes[1].chemin)
        assertNull(etat.groupes[1].statutTexte)
        assertTrue(etat.horsGroupes.isEmpty())
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `le contenu sans tache reste au groupe courant jamais devine`() {
        // Aucun en-tête, aucun champ tache : rien ne s'invente (Q6).
        val sansGroupe = RegroupementSorties.deriver(
            listOf(ligne("ligne orpheline"), ligne("autre orpheline")),
            execution = null,
        )
        assertTrue(sansGroupe.groupes.isEmpty())
        assertEquals(listOf(0, 1), sansGroupe.horsGroupes)

        // Une ligne sans champ tache après un groupe ouvert s'y rattache.
        val rattachee = RegroupementSorties.deriver(
            listOf(
                ligne("> Task :app:assembleDebug"),
                ligne("sortie libre"),
            ),
            execution = null,
        )
        assertEquals(1, rattachee.groupes.size)
        assertEquals(1, rattachee.groupes[0].fin)
        assertCouverture(rattachee, 2)
    }

    @Test
    fun `le bloc d echec est epingle hors des groupes`() {
        val lignes = listOf(
            ligne("> Task :app:compileDebugKotlin"),
            ligne(
                "e: file:///projet/app/MainActivity.kt:12:5 erreur",
                tache = ":app:compileDebugKotlin",
                gravite = GravitePosition.ERREUR,
            ),
            ligne("FAILURE: Build failed with an exception."),
            ligne("* What went wrong:"),
            ligne("Execution failed for task ':app:compileDebugKotlin'."),
            ligne("BUILD FAILED in 2m 3s"),
        )
        val etat = RegroupementSorties.deriver(lignes, execution = null)

        // Le bloc épinglé : intitulé + section cause + son contenu. La
        // ligne `e:` reste dans le groupe (elle est du flux, pas du bloc).
        assertEquals(listOf(2, 3, 4), etat.blocEchec)
        assertEquals(1, etat.groupes.size)
        assertEquals(1, etat.groupes[0].fin)
        assertEquals(1, etat.groupes[0].erreurs)
        assertEquals(listOf(5), etat.horsGroupes)
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `l evenement fait foi sur le verdict et les compteurs`() {
        val execution = EtatExecutionGradle(
            verdict = VerdictExecution.ECHOUE,
            dureeMs = 123_000L,
            compteurs = CompteursTaches(actionnables = 4, executees = 1, aJour = 3),
            statutsParTache = mapOf(":app:compileDebugKotlin" to StatutTache.ECHOUEE),
            dureesParTache = mapOf(":app:compileDebugKotlin" to 1_250L),
        )
        val lignes = listOf(
            ligne("> Task :app:compileDebugKotlin"),
            ligne("sortie"),
            ligne("BUILD FAILED in 5s"),
        )
        val etat = RegroupementSorties.deriver(lignes, execution)

        assertEquals(VerdictExecution.ECHOUE, etat.resume.verdict)
        assertEquals(123_000L, etat.resume.dureeMs)
        assertEquals(4, etat.resume.compteurs?.actionnables)
        assertEquals(StatutTache.ECHOUEE, etat.groupes[0].statutEvenement)
        assertEquals(1_250L, etat.groupes[0].dureeMs)
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `sans evenement le texte porte le verdict et le resume`() {
        val lignes = listOf(
            ligne("> Task :app:preBuild UP-TO-DATE"),
            ligne("> Task :app:compileDebugKotlin"),
            ligne("BUILD SUCCESSFUL in 3.4s"),
            ligne("2 actionable tasks: 1 executed, 1 up-to-date"),
        )
        val etat = RegroupementSorties.deriver(lignes, execution = null)

        assertEquals(VerdictExecution.REUSSI, etat.resume.verdict)
        assertEquals(3_400L, etat.resume.dureeMs)
        assertEquals(2, etat.resume.compteurs?.actionnables)
        assertEquals(1, etat.resume.compteurs?.executees)
        assertEquals(1, etat.resume.compteurs?.aJour)
        assertEquals(listOf(2, 3), etat.horsGroupes)
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `les compteurs de gravite viennent des evenements jamais des lignes en double`() {
        val execution = EtatExecutionGradle(erreurs = 7, avertissements = 2)
        val lignes = listOf(
            ligne("> Task :app:compileDebugKotlin"),
            ligne(
                "e: file:///projet/a.kt:1:1 erreur",
                tache = ":app:compileDebugKotlin",
                gravite = GravitePosition.ERREUR,
            ),
            ligne(
                "w: file:///projet/b.kt:2:2 avert",
                tache = ":app:compileDebugKotlin",
                gravite = GravitePosition.AVERTISSEMENT,
            ),
        )
        val etat = RegroupementSorties.deriver(lignes, execution)

        // L'événement fait foi : les lignes ne comptent PAS en plus.
        assertEquals(7, etat.resume.erreurs)
        assertEquals(2, etat.resume.avertissements)
        // …mais le COMPTE DU GROUPE reste mesuré sur son contenu.
        assertEquals(1, etat.groupes[0].erreurs)
        assertEquals(1, etat.groupes[0].avertissements)
    }

    @Test
    fun `les compteurs de gravite tombent en secours sur les lignes`() {
        val lignes = listOf(
            ligne("> Task :app:compileDebugKotlin"),
            ligne(
                "e: file:///projet/a.kt:1:1 erreur",
                tache = ":app:compileDebugKotlin",
                gravite = GravitePosition.ERREUR,
            ),
            ligne(
                "w: file:///projet/b.kt:2:2 avert",
                tache = ":app:compileDebugKotlin",
                gravite = GravitePosition.AVERTISSEMENT,
            ),
            ligne("banal", tache = ":app:compileDebugKotlin"),
        )
        val etat = RegroupementSorties.deriver(lignes, execution = null)
        assertEquals(1, etat.resume.erreurs)
        assertEquals(1, etat.resume.avertissements)
    }

    @Test
    fun `une nouvelle tache apres un bloc d echec reouvre un groupe`() {
        val lignes = listOf(
            ligne("> Task :app:compileDebugKotlin"),
            ligne("FAILURE: Build failed with an exception."),
            ligne("* Try:"),
            ligne("> Task :app:compileReleaseKotlin FAILED"),
            ligne("BUILD FAILED in 9s"),
        )
        val etat = RegroupementSorties.deriver(lignes, execution = null)

        assertEquals(2, etat.groupes.size)
        assertEquals(":app:compileReleaseKotlin", etat.groupes[1].chemin)
        assertEquals(StatutTacheGradle.ECHOUEE, etat.groupes[1].statutTexte)
        assertEquals(3, etat.groupes[1].debut)
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `aucun contenu ne reetend un groupe ferme par le verdict`() {
        // Contenu post-verdict (flux coupé, émissions retardées) : hors
        // groupe — jamais de chevauchement avec la place du verdict.
        val lignes = listOf(
            ligne("> Task :app:preBuild"),
            ligne("BUILD SUCCESSFUL in 1s"),
            ligne("retardataire", tache = ":app:preBuild"),
        )
        val etat = RegroupementSorties.deriver(lignes, execution = null)
        assertEquals(0, etat.groupes[0].fin)
        assertEquals(listOf(1, 2), etat.horsGroupes)
        assertCouverture(etat, lignes.size)
    }

    @Test
    fun `le groupe porte sa duree evenement et son statut texte en degrade`() {
        val execution = EtatExecutionGradle(
            statutsParTache = mapOf(":lib:x" to StatutTache.REUSSIE),
            dureesParTache = mapOf(":lib:x" to 800L),
        )
        val etat = RegroupementSorties.deriver(
            listOf(ligne("> Task :lib:x FROM-CACHE"), ligne("prise du cache")),
            execution,
        )
        assertEquals(StatutTacheGradle.DEPUIS_CACHE, etat.groupes[0].statutTexte)
        assertEquals(StatutTache.REUSSIE, etat.groupes[0].statutEvenement)
        assertEquals(800L, etat.groupes[0].dureeMs)
    }

    @Test
    fun `la fenetre tronquee ne devine pas ce qui a tombe`() {
        // Tête tronquée : le groupe s'ouvre sur la première ligne visible,
        // sans statut texte ni durée (ce qui a tombé ne s'invente pas).
        val etat = RegroupementSorties.deriver(
            listOf(ligne("milieu de sortie", tache = ":app:compileDebugKotlin")),
            execution = null,
        )
        assertEquals(":app:compileDebugKotlin", etat.groupes[0].chemin)
        assertNull(etat.groupes[0].statutTexte)
        assertNull(etat.groupes[0].dureeMs)
    }

    @Test
    fun `non-regression — chaque log du corpus se regroupe sans chevauchement`() {
        val corpus = File("../../docs/archive/tooling-scenarios/sorties")
            .listFiles { fichier -> fichier.extension == "log" }
            ?.sortedBy { it.name }
            ?: emptyList()
        assertEquals(11, corpus.size)
        val parseur = ParseurFluxGradle()
        for (fichier in corpus) {
            parseur.reinitialiser()
            val lignes = fichier.readText().split("\n").dropLastWhile { it.isEmpty() }
                .map { texte ->
                    val analyse = parseur.traiterLigne(texte)
                    // Le même rattachement que le collecteur réel (L3) :
                    // le champ tache porte le groupe courant du parseur.
                    LigneSortie(
                        source = SourceSortie.BUILD,
                        message = texte,
                        tache = analyse.tacheAnnoncee ?: analyse.tacheCourante,
                    )
                }
            val etat = RegroupementSorties.deriver(lignes, execution = null)
            assertCouverture(etat, lignes.size)
            // Zéro exception, résumé toujours présent et honnête.
            assertEquals(lignes.size, etat.resume.totalLignes)
        }
    }

    @Test
    fun `le log d erreur de configuration epingle son bloc d echec`() {
        val lignes = chargerCorpus("08-erreur-config.log")
        val parseur = ParseurFluxGradle()
        val modeles = lignes.map { texte ->
            val analyse = parseur.traiterLigne(texte)
            LigneSortie(
                source = SourceSortie.BUILD,
                message = texte,
                tache = analyse.tacheAnnoncee ?: analyse.tacheCourante,
            )
        }
        val etat = RegroupementSorties.deriver(modeles, execution = null)
        assertTrue("bloc d'échec attendu", etat.blocEchec.isNotEmpty())
        assertCouverture(etat, modeles.size)
        // Le résumé dit l'échec (texte, aucun événement).
        assertEquals(VerdictExecution.ECHOUE, etat.resume.verdict)
    }

    private fun chargerCorpus(nom: String): List<String> = File("../../docs/archive/tooling-scenarios/sorties/$nom")
        .readText()
        .split("\n")
        .dropLastWhile { it.isEmpty() }
}
