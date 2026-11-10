package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'expurgation à l'écriture (ADR 0161 décision 4, lot L3) —
 * relativisation des chemins du projet (les deux formes du câble),
 * expurgation générale [LogRedactor] (courriels, secrets, chemins hors
 * projet), liens source conservés et résolubles, idempotence, racine
 * inconnue (tout masqué, jamais un chemin complet sur disque).
 */
class ExpurgateurSortiesTest {
    private val racine = "/data/user/0/jo.codeide/files/projects/Demo"

    @Test
    fun `un chemin du projet sous marque kotlin devient relatif`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = "e: file://$racine/app/src/main/MainActivity.kt:12:5 name not found",
            horodatageMs = 1L,
        )
        val expurgee = ExpurgateurSorties.expurger(ligne, racine)

        assertEquals(
            "e: app/src/main/MainActivity.kt:12:5 name not found",
            expurgee.message,
        )
    }

    @Test
    fun `un chemin nu du projet devient relatif aussi`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = "compilation de $racine/app/src/main/Util.kt terminée",
            horodatageMs = 1L,
        )
        val expurgee = ExpurgateurSorties.expurger(ligne, racine)
        assertEquals("compilation de app/src/main/Util.kt terminée", expurgee.message)
    }

    @Test
    fun `un chemin hors projet est masque jamais sur disque`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = "sortie depuis /data/local/tmp/etranger/x.txt et $racine/app/src/A.kt",
            horodatageMs = 1L,
        )
        val expurgee = ExpurgateurSorties.expurger(ligne, racine)
        // Le chemin du projet : relatif. Le hors projet : masqué ENTIER
        // (le motif du journal applicatif consomme le chemin complet).
        assertEquals(
            "sortie depuis <chemin> et app/src/A.kt",
            expurgee.message,
        )
    }

    @Test
    fun `la racine prefixe d'un autre projet n est pas decoupee`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = "frère ${racine}Backup/x.log conservé",
            horodatageMs = 1L,
        )
        val expurgee = ExpurgateurSorties.expurger(ligne, racine)
        assertTrue(expurgee.message.contains("<chemin>"))
        assertFalseContientRacine(expurgee.message)
    }

    @Test
    fun `les liens source du projet survivent resolubles`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = "e: file://$racine/app/src/main/MainActivity.kt:12:5 erreur",
            horodatageMs = 1L,
            positionsSource = listOf(
                PositionSource(
                    fichier = "file://$racine/app/src/main/MainActivity.kt",
                    ligne = 12,
                    colonne = 5,
                    gravite = GravitePosition.ERREUR,
                ),
            ),
        )
        val expurgee = ExpurgateurSorties.expurger(ligne, racine)
        assertEquals(
            PositionSource("app/src/main/MainActivity.kt", 12, colonne = 5, gravite = GravitePosition.ERREUR),
            expurgee.positionsSource.single(),
        )
    }

    @Test
    fun `les secrets et courriels sont masques comme au journal`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = "publication par alain@exemple.fr avec sk-abcd1234abcd1234abcd1234abcd1234",
            horodatageMs = 1L,
        )
        val expurgee = ExpurgateurSorties.expurger(ligne, racine)
        assertEquals(
            "publication par <courriel> avec <secret>",
            expurgee.message,
        )
    }

    @Test
    fun `sans racine connue tout chemin absolu est masque`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = "e: file://$racine/app/src/main/MainActivity.kt:12:5 erreur",
            horodatageMs = 1L,
        )
        val expurgee = ExpurgateurSorties.expurger(ligne, null)
        // Le marqueur file:// est masqué ENTIER (même discipline que le
        // journal applicatif) — jamais un chemin complet sur disque.
        assertTrue(expurgee.message.contains("erreur"))
        assertFalseContientRacine(expurgee.message)
    }

    @Test
    fun `l expurgation est idempotente`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = "e: file://$racine/app/src/main/A.kt:1:1 erreur (alain@exemple.fr)",
            horodatageMs = 1L,
        )
        val uneFois = ExpurgateurSorties.expurger(ligne, racine)
        val deuxFois = ExpurgateurSorties.expurger(uneFois, racine)
        assertEquals(uneFois, deuxFois)
    }

    @Test
    fun `le texte libre passe par la meme double passe`() {
        assertEquals(
            "échec dans app/build.gradle",
            ExpurgateurSorties.expurgerTexte("échec dans $racine/app/build.gradle", racine),
        )
    }

    @Test
    fun `la troncature epargne tete et queue au dela du plafond`() {
        assertNull(TroncatureSorties.borner(total = 20_000L, plafond = 20_000))
        val bornee = TroncatureSorties.borner(total = 20_001L, plafond = 20_000)
        assertEquals(20_001L, bornee?.totalLignes)
        assertEquals(10_000, bornee?.tete)
        assertEquals(10_000, bornee?.queue)
        assertEquals(1L, bornee?.perdues)
        val impair = TroncatureSorties.borner(total = 23_000L, plafond = 20_000)
        assertEquals(10_000, impair?.tete)
        assertEquals(10_000, impair?.queue)
        assertEquals(3_000L, impair?.perdues)
    }

    /** Aucune trace de la racine absolue ne doit rester (contrat disque). */
    private fun assertFalseContientRacine(texte: String) {
        assertTrue(
            "la racine absolue ne doit jamais persister",
            !texte.contains(racine),
        )
    }
}
