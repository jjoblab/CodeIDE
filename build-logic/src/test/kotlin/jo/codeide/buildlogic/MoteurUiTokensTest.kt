package jo.codeide.buildlogic

import jo.codeide.buildlogic.MoteurUiTokens.AUCUN
import jo.codeide.buildlogic.MoteurUiTokens.Regle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du moteur de règles R1-R7 du garde-fou des littéraux UI
 * (ADR 0127 §5, lot UI-2) : cas PASSANTS et ÉCHOUANTS par règle,
 * puis les mécaniques de CLIQUET (comparaison à un plafond et
 * descente de la baseline).
 */
class MoteurUiTokensTest {
    // ------------------------------------------------------------------
    // R1 — marges et paddings littéraux (0dp/1dp admis).
    // ------------------------------------------------------------------

    @Test
    fun `R1 échoue sur une marge littérale et réussit par jeton`() {
        val sale = """android:layout_marginStart="16dp""""
        val propre = """android:layout_marginStart="@dimen/spacing_md""""

        assertEquals(1, MoteurUiTokens.xml(sale).size)
        assertTrue(MoteurUiTokens.xml(sale).all { it.regle == Regle.R1 })
        assertTrue(MoteurUiTokens.xml(propre).isEmpty())
    }

    @Test
    fun `R1 admet 0dp et 1dp (filets, contraintes — ADR §4)`() {
        assertTrue(MoteurUiTokens.xml("""android:padding="0dp"""").isEmpty())
        assertTrue(MoteurUiTokens.xml("""android:padding="1dp"""").isEmpty())
        assertEquals(1, MoteurUiTokens.xml("""android:padding="2dp"""").size)
    }

    @Test
    fun `R1 couvre toutes les variantes de marge et de padding`() {
        val contenu =
            """
            android:layout_margin="10dp"
            android:layout_marginTop="6dp"
            android:paddingHorizontal="12dp"
            android:paddingVertical="7dp"
            """.trimIndent()

        assertEquals(4, MoteurUiTokens.xml(contenu).count { it.regle == Regle.R1 })
    }

    // ------------------------------------------------------------------
    // R2 — tailles de texte littérales.
    // ------------------------------------------------------------------

    @Test
    fun `R2 échoue sur textSize littéral même 14sp et réussit par apparence`() {
        assertEquals(1, MoteurUiTokens.xml("""android:textSize="14sp"""").size)
        assertEquals(1, MoteurUiTokens.xml("""android:textSize="11.5sp"""").size)
        assertTrue(
            MoteurUiTokens.xml("""android:textAppearance="?attr/textAppearanceTitleSmall"""").isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // R3 — couleurs en dur (tools: excepté).
    // ------------------------------------------------------------------

    @Test
    fun `R3 échoue sur un hexadécimal et ignore les valeurs d'aperçu tools`() {
        assertEquals(1, MoteurUiTokens.xml("""android:background="#FF0000"""").size)
        assertTrue(
            MoteurUiTokens.xml("""tools:background="#38B8860B"""").isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // R4 — styles Material bruts.
    // ------------------------------------------------------------------

    @Test
    fun `R4 échoue sur les trois dialectes tiers`() {
        assertTrue(
            MoteurUiTokens.xml("""style="@style/Widget.Material3.Button.OutlinedButton"""")
                .all { it.regle == Regle.R4 },
        )
        assertTrue(
            MoteurUiTokens.xml("""style="?attr/materialButtonOutlinedStyle"""")
                .all { it.regle == Regle.R4 },
        )
        assertTrue(
            MoteurUiTokens.xml("""style="@style/Widget.MaterialComponents.CardView"""")
                .all { it.regle == Regle.R4 },
        )
        assertTrue(MoteurUiTokens.xml("""style="@style/Widget.CodeIDE.Button"""").isEmpty())
    }

    // ------------------------------------------------------------------
    // R5 — widgets nus.
    // ------------------------------------------------------------------

    @Test
    fun `R5 échoue sur Button ImageButton et CardView bruts`() {
        assertEquals(1, MoteurUiTokens.xml("""<Button android:id="@+id/x" />""").size)
        assertEquals(1, MoteurUiTokens.xml("""<ImageButton android:id="@+id/y" />""").size)
        assertEquals(
            1,
            MoteurUiTokens.xml("""<androidx.cardview.widget.CardView />""").size,
        )
        // Le Material correspondant, thématisé, est admis.
        assertTrue(MoteurUiTokens.xml("""<com.google.android.material.button.MaterialButton />""").isEmpty())
        // Un nom de classe qui COMMENCE par Button n'est pas piégé.
        assertTrue(MoteurUiTokens.xml("""<ButtonViewCustom />""").isEmpty())
    }

    @Test
    fun `R5 durci attrape ImageButton en FIN DE LIGNE (XML formaté un attribut par ligne)`() {
        val contenu =
            """
            <ImageButton
                android:id="@+id/croix"
                android:src="@drawable/ic_fermer" />
            """.trimIndent()

        assertEquals(1, MoteurUiTokens.xml(contenu).size)
        assertEquals(Regle.R5, MoteurUiTokens.xml(contenu).first().regle)
    }

    @Test
    fun `R5 durci EXONÈRE ImageButton portant un style maison`() {
        val contenu =
            """
            <ImageButton
                android:id="@+id/bouton_retour"
                style="@style/Widget.CodeIDE.Apercu.BoutonIcone"
                android:src="@drawable/apercu_ic_retour" />
            <Button
                style="@style/Widget.CodeIDE.Button.Outlined"
                android:text="@string/valider" />
            """.trimIndent()

        assertTrue(MoteurUiTokens.xml(contenu).isEmpty())
    }

    @Test
    fun `R5 durci attrape ImageButton à style BRUT même multi-lignes`() {
        val contenu =
            """
            <ImageButton
                style="?attr/borderlessButtonStyle"
                android:src="@android:drawable/ic_menu_delete" />
            """.trimIndent()

        assertEquals(1, MoteurUiTokens.xml(contenu).count { it.regle == Regle.R5 })
    }

    @Test
    fun `R5 durci n'exonère pas par le style d'une balise SUIVANTE`() {
        val contenu =
            """
            <ImageButton
                android:id="@+id/nue"
                android:src="@drawable/x" />
            <ImageButton
                style="@style/Widget.CodeIDE.Button.Icon"
                android:src="@drawable/y" />
            """.trimIndent()

        assertEquals(1, MoteurUiTokens.xml(contenu).size)
        assertEquals(1, MoteurUiTokens.xml(contenu).first().ligne)
    }

    @Test
    fun `R5 durci n'exonère pas une balise tenue sur UNE ligne suivie d'une maison`() {
        val contenu =
            """
            <Button android:id="@+id/nue" />
            <Button style="@style/Widget.CodeIDE.Button.Icon" android:id="@+id/styloe" />
            """.trimIndent()

        assertEquals(1, MoteurUiTokens.xml(contenu).size)
    }

    // ------------------------------------------------------------------
    // R6 — Toast banni (Kotlin).
    // ------------------------------------------------------------------

    @Test
    fun `R6 échoue sur Toast makeText et réussit par Retour`() {
        val sale = "Toast.makeText(context, message, Toast.LENGTH_SHORT).show()"
        val propre = "Retour.snackbar(binding.racine, message)"

        assertEquals(1, MoteurUiTokens.kotlin(sale).size)
        assertEquals(Regle.R6, MoteurUiTokens.kotlin(sale).first().regle)
        assertEquals(1, MoteurUiTokens.kotlin(sale).first().ligne)
        assertTrue(MoteurUiTokens.kotlin(propre).isEmpty())
    }

    @Test
    fun `R6 durci attrape l'appel QUALIFIÉ multi-lignes`() {
        val sale =
            """
            if (buildEnCours) {
                android.widget.Toast
                    .makeText(this, R.string.x, android.widget.Toast.LENGTH_LONG)
                    .show()
            }
            """.trimIndent()

        val occurrences = MoteurUiTokens.kotlin(sale)
        assertEquals(1, occurrences.size)
        assertEquals(Regle.R6, occurrences.first().regle)
        assertEquals(2, occurrences.first().ligne)
    }

    @Test
    fun `R6 durci attribue la ligne du JETON Toast et normalise l'extrait`() {
        val sale =
            "fun f() {\n    Toast\n        .makeText(c, m, Toast.LENGTH_SHORT).show()\n}\n"

        val occurrence = MoteurUiTokens.kotlin(sale).single()
        assertEquals(2, occurrence.ligne)
        assertEquals("Toast .makeText", occurrence.extrait)
    }

    // ------------------------------------------------------------------
    // R7 — cartes littérales.
    // ------------------------------------------------------------------

    @Test
    fun `R7 échoue sur rayon et élévation littéraux d'une carte`() {
        assertEquals(1, MoteurUiTokens.xml("""app:cardCornerRadius="12dp"""").size)
        assertEquals(1, MoteurUiTokens.xml("""app:cardElevation="0dp"""").size)
        assertTrue(MoteurUiTokens.xml("""app:cardCornerRadius="@dimen/corner_radius_medium"""").isEmpty())
    }

    // ------------------------------------------------------------------
    // Décompte et lignes.
    // ------------------------------------------------------------------

    @Test
    fun `le décompte agrège par règle et la ligne est 1-indexée`() {
        val contenu =
            """
            <LinearLayout android:padding="16dp">
                <TextView android:textSize="12sp" />
            </LinearLayout>
            """.trimIndent()

        val occurrences = MoteurUiTokens.xml(contenu)

        assertEquals(2, occurrences.size)
        assertEquals(1, occurrences.first().ligne)
        assertEquals(2, occurrences.last().ligne)
        val decompte = MoteurUiTokens.decompte(occurrences)
        assertEquals(1, decompte["R1"])
        assertEquals(1, decompte["R2"])
        assertEquals(0, decompte["R6"])
    }

    // ------------------------------------------------------------------
    // CLIQUET — comparaison au plafond.
    // ------------------------------------------------------------------

    @Test
    fun `un fichier connu sous son plafond ne dépasse pas`() {
        val depassements =
            MoteurUiTokens.comparer(
                "feature/editor/x.xml",
                mapOf("R1" to 3, "R2" to 1),
                mapOf("R1" to 5, "R2" to 2, "R3" to 0),
            )

        assertTrue(depassements.isEmpty())
    }

    @Test
    fun `un fichier connu qui dépasse UNE règle échoue sur cette règle`() {
        val depassements =
            MoteurUiTokens.comparer(
                "feature/editor/x.xml",
                mapOf("R1" to 6, "R2" to 1),
                mapOf("R1" to 5, "R2" to 2),
            )

        assertEquals(1, depassements.size)
        assertEquals("R1", depassements.first().regle)
        assertEquals(5, depassements.first().ancien)
        assertEquals(6, depassements.first().constate)
    }

    @Test
    fun `un fichier INCONNU avec la moindre occurrence échoue intégral`() {
        val depassements =
            MoteurUiTokens.comparer(
                "feature/nouveau/x.xml",
                mapOf("R1" to 1, "R2" to 0, "R3" to 2),
                null,
            )

        assertEquals(2, depassements.size)
        assertTrue(depassements.all { it.ancien == AUCUN })
    }

    @Test
    fun `un fichier inconnu PROPRE ne dépasse pas`() {
        assertTrue(MoteurUiTokens.comparer("feature/nouveau/x.xml", emptyMap(), null).isEmpty())
    }

    // ------------------------------------------------------------------
    // CLIQUET — descente de la baseline.
    // ------------------------------------------------------------------

    @Test
    fun `le cliquet prend le minimum par règle et par fichier`() {
        val futures =
            MoteurUiTokens.cliquet(
                constat = mapOf("a.xml" to mapOf("R1" to 3, "R2" to 9)),
                ancienne = mapOf("a.xml" to mapOf("R1" to 5, "R2" to 4)),
            )

        assertEquals(3, futures["a.xml"]?.get("R1"))
        assertEquals(4, futures["a.xml"]?.get("R2"))
    }

    @Test
    fun `le cliquet ignore un fichier inconnu et sort les fichiers disparus`() {
        val futures =
            MoteurUiTokens.cliquet(
                constat = mapOf(
                    // Inconnu de la baseline : JAMAIS plafonné par le cliquet.
                    "nouveau.xml" to mapOf("R1" to 2),
                ),
                ancienne = mapOf(
                    // Connu mais disparu du scan (supprimé ou devenu propre) : sort.
                    "disparu.xml" to mapOf("R1" to 4),
                ),
            )

        assertTrue(futures.isEmpty())
    }

    @Test
    fun `un fichier devenu totalement propre sort de la baseline`() {
        val futures =
            MoteurUiTokens.cliquet(
                constat = mapOf("propre.xml" to mapOf("R1" to 0)),
                ancienne = mapOf("propre.xml" to mapOf("R1" to 4)),
            )

        assertTrue(futures.isEmpty())
    }
}
