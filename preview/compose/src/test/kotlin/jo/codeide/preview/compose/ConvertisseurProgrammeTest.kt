package jo.codeide.preview.compose

import jo.lspkotlin.api.AppelFonctionKotlin
import jo.lspkotlin.api.ArgumentAppelKotlin
import jo.lspkotlin.api.ExpressionNonInterpreteeKotlin
import jo.lspkotlin.api.GenreArgumentAppelKotlin
import jo.lspkotlin.api.GenreInlineKotlin
import jo.lspkotlin.api.PreviewKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conversion de l'arbre d'appels neutre du LSP Kotlin en PROGRAMME
 * renderable (C1) — l'interprétation APP-SIDE : littéraux, dp, splicing
 * des inlines du projet, pastilles d'erreur typées, budget de défense
 * en profondeur, digeste déterministe.
 */
class ConvertisseurProgrammeTest {
    /** Une preview au corps composé des appels posés. */
    private fun previewTexte(vararg appels: AppelFonctionKotlin) = PreviewKotlin(
        nom = "ApercuCarte",
        paquet = "com.exemple",
        debutNom = 0,
        finNom = 0,
        parametres = emptyList(),
        corps = appels.toList(),
    )

    private fun texte(chaine: String): AppelFonctionKotlin = AppelFonctionKotlin(
        nom = "Text",
        recepteur = null,
        ligne = 7,
        arguments =
        listOf(
            ArgumentAppelKotlin(nom = "text", genre = GenreArgumentAppelKotlin.LITTERAL, texte = chaine),
        ),
        contenu = emptyList(),
    )

    @Test
    fun `registre - Text en genre TEXTE, littéral sans guillemets`() {
        val (programme, erreurs) = ConvertisseurProgramme.convertir(previewTexte(texte("\"Bonjour\"")))
        assertEquals(1, programme.racine.size)
        assertEquals(GenreNoeudCompose.TEXTE, programme.racine.single().genre)
        assertEquals(
            "Bonjour",
            programme.racine
                .single()
                .arguments
                .single()
                .valeur,
        )
        assertEquals(
            GenreArgumentCompose.LITTERAL_CHAINE,
            programme.racine
                .single()
                .arguments
                .single()
                .genre,
        )
        assertTrue("aucune erreur en nominal", erreurs.isEmpty())
        assertTrue("pas de coupure", !programme.coupureBudget)
    }

    @Test
    fun `C3 registre - les indicateurs de progression M3 traversent (animation du registre)`() {
        val (programme, erreurs) = ConvertisseurProgramme.convertir(
            previewTexte(
                AppelFonctionKotlin(
                    nom = "CircularProgressIndicator",
                    recepteur = null,
                    ligne = 7,
                    arguments = emptyList(),
                    contenu = emptyList(),
                ),
                AppelFonctionKotlin(
                    nom = "LinearProgressIndicator",
                    recepteur = null,
                    ligne = 8,
                    arguments = emptyList(),
                    contenu = emptyList(),
                ),
            ),
        )
        assertEquals(2, programme.racine.size)
        assertEquals(GenreNoeudCompose.PROGRES_CIRCULAIRE, programme.racine[0].genre)
        assertEquals(GenreNoeudCompose.PROGRES_LINEAIRE, programme.racine[1].genre)
        assertEquals(8, programme.racine[1].ligne)
        assertTrue("aucune erreur : indéterminés sans arguments", erreurs.isEmpty())
    }

    @Test
    fun `registre - Column imbriquant des Text`() {
        val colonne =
            AppelFonctionKotlin(
                nom = "Column",
                recepteur = null,
                ligne = 10,
                arguments = emptyList(),
                contenu = listOf(texte("\"A\""), texte("\"B\"")),
            )
        val (programme, erreurs) = ConvertisseurProgramme.convertir(previewTexte(colonne))
        assertEquals(GenreNoeudCompose.COLONNE, programme.racine.single().genre)
        assertEquals(
            2,
            programme.racine
                .single()
                .contenu.size,
        )
        assertTrue(erreurs.isEmpty())
    }

    @Test
    fun `composable inconnu - pastille typée COMPOSABLE_INCONNU`() {
        val (programme, erreurs) =
            ConvertisseurProgramme.convertir(previewTexte(texte("\"A\"").copy(nom = "CartePersonnalisee")))
        assertEquals(GenreNoeudCompose.INCONNU, programme.racine.single().genre)
        assertEquals(1, erreurs.size)
        assertEquals(GenreErreurCompose.COMPOSABLE_INCONNU, erreurs.single().genre)
        assertEquals("CartePersonnalisee", erreurs.single().nom)
        assertEquals(7, erreurs.single().ligne)
    }

    @Test
    fun `inline du projet - splicing transparent (le wrapper disparaît)`() {
        val inline =
            AppelFonctionKotlin(
                nom = "MaCarte",
                recepteur = null,
                ligne = 12,
                arguments = emptyList(),
                contenu = listOf(texte("\"Dans la carte\"")),
                genreInline = GenreInlineKotlin.PROJET_INLINE,
            )
        val (programme, erreurs) = ConvertisseurProgramme.convertir(previewTexte(inline))
        // Le wrapper N'EST PAS un nœud : son corps prend sa place.
        assertEquals(1, programme.racine.size)
        assertEquals(GenreNoeudCompose.TEXTE, programme.racine.single().genre)
        assertTrue(erreurs.isEmpty())
    }

    @Test
    fun `inline en cycle - pastille typée COMPOSABLE_PROJET_CYCLE`() {
        val cycle =
            AppelFonctionKotlin(
                nom = "MaCarte",
                recepteur = null,
                ligne = 12,
                arguments = emptyList(),
                contenu = emptyList(),
                genreInline = GenreInlineKotlin.PROJET_CYCLE,
            )
        val (programme, erreurs) = ConvertisseurProgramme.convertir(previewTexte(cycle))
        assertEquals(GenreNoeudCompose.ERREUR_INLINE, programme.racine.single().genre)
        assertEquals(GenreErreurCompose.COMPOSABLE_PROJET_CYCLE, erreurs.single().genre)
    }

    @Test
    fun `expression non interprétée - pastille EXPRESSION_NON_INTERPRETEE`() {
        val preview =
            PreviewKotlin(
                nom = "ApercuListe",
                paquet = null,
                debutNom = 0,
                finNom = 0,
                parametres = emptyList(),
                corps = listOf(ExpressionNonInterpreteeKotlin(texte = "val x = 1 + 2", ligne = 3)),
            )
        val (programme, erreurs) = ConvertisseurProgramme.convertir(preview)
        assertEquals(GenreNoeudCompose.ERREUR_EXPRESSION, programme.racine.single().genre)
        assertEquals(GenreErreurCompose.EXPRESSION_NON_INTERPRETEE, erreurs.single().genre)
        assertEquals(3, erreurs.single().ligne)
    }

    @Test
    fun `arguments - entier, booléen et dp traversent`() {
        val espace =
            AppelFonctionKotlin(
                nom = "Spacer",
                recepteur = null,
                ligne = 4,
                arguments =
                listOf(
                    ArgumentAppelKotlin(
                        nom = "height",
                        genre = GenreArgumentAppelKotlin.REFERENCE,
                        texte = "16.dp",
                    ),
                    ArgumentAppelKotlin(nom = null, genre = GenreArgumentAppelKotlin.LITTERAL, texte = "8"),
                    ArgumentAppelKotlin(nom = "visible", genre = GenreArgumentAppelKotlin.LITTERAL, texte = "true"),
                ),
                contenu = emptyList(),
            )
        val (programme, erreurs) = ConvertisseurProgramme.convertir(previewTexte(espace))
        val arguments = programme.racine.single().arguments
        assertEquals(GenreArgumentCompose.VALEUR_DP, arguments[0].genre)
        assertEquals("16", arguments[0].valeur)
        assertEquals(GenreArgumentCompose.LITTERAL_ENTIER, arguments[1].genre)
        assertEquals(GenreArgumentCompose.LITTERAL_BOOLEEN, arguments[2].genre)
        assertTrue(erreurs.isEmpty())
    }

    @Test
    fun `dp flottant - 12_5 dp traverse sans unité`() {
        val paire = ConvertisseurProgramme.dpDe("12.5.dp")
        assertEquals(GenreArgumentCompose.VALEUR_DP, paire?.first)
        assertEquals("12.5", paire?.second)
    }

    @Test
    fun `argument non interprétable - erreur typée, argument par défaut`() {
        val bouton =
            AppelFonctionKotlin(
                nom = "Text",
                recepteur = null,
                ligne = 9,
                arguments =
                listOf(
                    ArgumentAppelKotlin(
                        nom = "color",
                        genre = GenreArgumentAppelKotlin.REFERENCE,
                        texte = "Color.Red",
                    ),
                ),
                contenu = emptyList(),
            )
        val (programme, erreurs) = ConvertisseurProgramme.convertir(previewTexte(bouton))
        assertEquals(
            GenreArgumentCompose.NON_INTERPRETE,
            programme.racine
                .single()
                .arguments
                .single()
                .genre,
        )
        assertEquals(GenreErreurCompose.ARGUMENT_NON_INTERPRETABLE, erreurs.single().genre)
    }

    @Test
    fun `budget - troncature signalée (coupureBudget + erreur typée)`() {
        // 501 appel du registre : le budget (500) coupe la marche.
        val appels = Array(501) { texte("\"x\"") }
        val (programme, erreurs) = ConvertisseurProgramme.convertir(previewTexte(*appels))
        assertTrue("programme tronqué", programme.coupureBudget)
        assertTrue(programme.racine.size < 501)
        assertTrue(erreurs.any { it.genre == GenreErreurCompose.BUDGET_INTERPRETATION })
    }

    @Test
    fun `coupure amont du LSP - propagée`() {
        val preview = previewTexte(texte("\"A\"")).copy(coupureBudget = true)
        val (programme, erreurs) = ConvertisseurProgramme.convertir(preview)
        assertTrue(programme.coupureBudget)
        assertTrue(erreurs.any { it.genre == GenreErreurCompose.BUDGET_INTERPRETATION })
    }

    @Test
    fun `digeste - déterministe et sensible au contenu`() {
        val programme1 = ConvertisseurProgramme.convertir(previewTexte(texte("\"A\""))).first
        val programme2 = ConvertisseurProgramme.convertir(previewTexte(texte("\"A\""))).first
        val programme3 = ConvertisseurProgramme.convertir(previewTexte(texte("\"B\""))).first
        assertEquals("même arbre, même digeste", programme1.digeste, programme2.digeste)
        assertNotEquals("contenu différent, digeste différent", programme1.digeste, programme3.digeste)
        assertEquals(64, programme1.digeste.length)
    }

    // ── C2 : les RÉFÉRENCES de ressources et de thème ───────────────

    /** Un appel du registre avec UN argument RÉFÉRENCE. */
    private fun appelReference(nomAppel: String, nomArgument: String?, texteReference: String): AppelFonctionKotlin =
        AppelFonctionKotlin(
            nom = nomAppel,
            recepteur = null,
            ligne = 7,
            arguments =
            listOf(
                ArgumentAppelKotlin(
                    nom = nomArgument,
                    genre = GenreArgumentAppelKotlin.REFERENCE,
                    texte = texteReference,
                ),
            ),
            contenu = emptyList(),
        )

    @Test
    fun `référence R string - genre REFERENCE_CHAINE, la référence voyage par SON NOM`() {
        val (programme, erreurs) =
            ConvertisseurProgramme.convertir(previewTexte(appelReference("Text", "text", "R.string.bonjour")))
        assertEquals(GenreArgumentCompose.REFERENCE_CHAINE, programme.racine.single().arguments.single().genre)
        assertEquals("bonjour", programme.racine.single().arguments.single().valeur)
        assertTrue("une référence VALIDE ne produit AUCUNE erreur (la résolution vit au rendu)", erreurs.isEmpty())
    }

    @Test
    fun `référence R color - genre REFERENCE_COULEUR`() {
        val (programme, erreurs) =
            ConvertisseurProgramme.convertir(previewTexte(appelReference("Text", "color", "R.color.accent")))
        assertEquals(GenreArgumentCompose.REFERENCE_COULEUR, programme.racine.single().arguments.single().genre)
        assertEquals("accent", programme.racine.single().arguments.single().valeur)
        assertTrue(erreurs.isEmpty())
    }

    @Test
    fun `référence R dimen - genre REFERENCE_DIMENSION`() {
        val (programme, erreurs) =
            ConvertisseurProgramme.convertir(previewTexte(appelReference("Spacer", "height", "R.dimen.carton")))
        assertEquals(GenreArgumentCompose.REFERENCE_DIMENSION, programme.racine.single().arguments.single().genre)
        assertEquals("carton", programme.racine.single().arguments.single().valeur)
        assertTrue(erreurs.isEmpty())
    }

    @Test
    fun `référence MaterialTheme colorScheme primary - genre REFERENCE_THEME, valeur = le SLOT`() {
        val (programme, erreurs) =
            ConvertisseurProgramme.convertir(
                previewTexte(appelReference("Text", "color", "MaterialTheme.colorScheme.primary")),
            )
        assertEquals(GenreArgumentCompose.REFERENCE_THEME, programme.racine.single().arguments.single().genre)
        assertEquals("primary", programme.racine.single().arguments.single().valeur)
        assertTrue(erreurs.isEmpty())
    }

    @Test
    fun `référence thème hors table - NON interprétée, erreur typée honnête`() {
        val (programme, erreurs) =
            ConvertisseurProgramme.convertir(
                previewTexte(appelReference("Text", "color", "MaterialTheme.colorScheme.surfaceBright")),
            )
        assertEquals(GenreArgumentCompose.NON_INTERPRETE, programme.racine.single().arguments.single().genre)
        assertTrue(erreurs.any { it.genre == GenreErreurCompose.ARGUMENT_NON_INTERPRETABLE })
    }

    @Test
    fun `référence malformée ou famille hors C2 - NON interprétée`() {
        listOf(
            "R.string.",
            "R.bool.ok",
            "R.layout.carte",
            "R.string.1x",
            "Color.Red",
            "MaterialTheme.typography.titleLarge",
        ).forEach { brut ->
            val (programme, erreurs) = ConvertisseurProgramme.convertir(
                previewTexte(appelReference("Text", "color", brut)),
            )
            assertEquals(
                "hors C2 : $brut",
                GenreArgumentCompose.NON_INTERPRETE,
                programme.racine.single().arguments.single().genre,
            )
            assertTrue(erreurs.any { it.genre == GenreErreurCompose.ARGUMENT_NON_INTERPRETABLE })
        }
    }

    @Test
    fun `digeste - sensible au NOM de la référence (pas à la valeur résolue)`() {
        val programme1 = ConvertisseurProgramme.convertir(
            previewTexte(appelReference("Text", "text", "R.string.bonjour")),
        ).first
        val programme2 = ConvertisseurProgramme.convertir(
            previewTexte(appelReference("Text", "text", "R.string.auRevoir")),
        ).first
        assertNotEquals(
            "deux noms de références différents, deux programmes différents",
            programme1.digeste,
            programme2.digeste,
        )
    }
}
