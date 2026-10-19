package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contrat des COULEURS DU PROJET (PE11e — lecture des `colors.xml` +
 * résolution de pastille, miroir `RES.color` + `colorCss` de la
 * maquette) : un test par règle, cas limites inclus. Module PUR.
 */
public class CouleursProjetXmlTest {
    /** Un fichier de valeurs de test (miroir `RES.color` du faux projet). */
    private val clair =
        """
        <resources>
            <color name="primary">#43559E</color>
            <color name="surface">#FFFFFF</color>
            <color name="on_surface">#1A1B21</color>
            <color name="accent">#1E6EC8</color>
        </resources>
        """.trimIndent()

    /** Le même fichier en qualifié nuit : UNE nouvelle, UNE redéclaration. */
    private val nuit =
        """
        <resources>
            <color name="primary">#33447D</color>
            <color name="nuit_seule">#121317</color>
        </resources>
        """.trimIndent()

    @Test
    public fun `lire suit l ordre du document et le premier nom vu gagne`() {
        val couleurs = CouleursProjetXml.lire(listOf(clair, nuit))
        // Ordre du document de `values/` PUIS les qualifiés ne rabattent
        // que les NOMS manquants (miroir `Object.keys(RES.color)`).
        assertEquals(
            listOf("primary", "surface", "on_surface", "accent", "nuit_seule"),
            couleurs.map { it.nom },
        )
        // La valeur CLAIRE prime (values/ d'abord) : la pastille du
        // contrôle couleur montre la valeur de BASE, pas celle de nuit.
        assertEquals("#43559E", couleurs.first { it.nom == "primary" }.valeur)
        assertEquals("#121317", couleurs.first { it.nom == "nuit_seule" }.valeur)
    }

    @Test
    public fun `lire ignore les doublons d un meme fichier`() {
        val fichier = "<resources><color name=\"x\">#111111</color><color name=\"x\">#222222</color></resources>"
        assertEquals(
            listOf(CouleursProjetXml.CouleurProjet("x", "#111111")),
            CouleursProjetXml.lire(listOf(fichier)),
        )
    }

    @Test
    public fun `resoudre traite les formes hexa comme colorCss`() {
        val aucune = emptyMap<String, String>()
        // #RGB → chiffres doublés, alpha opaque (miroir `c + c`).
        assertEquals(0xFFFF0000L, CouleursProjetXml.resoudre("#F00", aucune))
        // #ARGB (4 chiffres) → chaque chiffre doublé : 8→88, F→FF, A→AA, 2→22.
        assertEquals(0x88FFAA22L, CouleursProjetXml.resoudre("#8FA2", aucune))
        // #RRGGBB → alpha opaque posé.
        assertEquals(0xFF43559EL, CouleursProjetXml.resoudre("#43559E", aucune))
        // #AARRGGBB → tel quel.
        assertEquals(0x8043559EL, CouleursProjetXml.resoudre("#8043559E", aucune))
        assertNull("forme illégale (5 chiffres)", CouleursProjetXml.resoudre("#FFFFF", aucune))
    }

    @Test
    public fun `resoudre suit un color du projet a un seul niveau`() {
        val projet = mapOf("primary" to "#43559E", "ref" to "@color/primary")
        assertEquals(0xFF43559EL, CouleursProjetXml.resoudre("@color/primary", projet))
        // La maquette ne récursive PAS : une référence imbriquée reste
        // NON résoluble (la pastille montre le damier seul).
        assertNull("référence imbriquée", CouleursProjetXml.resoudre("@color/ref", projet))
        assertNull("nom absent", CouleursProjetXml.resoudre("@color/absente", projet))
    }

    @Test
    public fun `resoudre connait le trio android de la maquette`() {
        val aucune = emptyMap<String, String>()
        assertEquals(0xFFFFFFFFL, CouleursProjetXml.resoudre("@android:color/white", aucune))
        assertEquals(0xFF000000L, CouleursProjetXml.resoudre("@android:color/black", aucune))
        assertEquals(0x00000000L, CouleursProjetXml.resoudre("@android:color/transparent", aucune))
        // Miroir `ANDROID_COL` : un autre nom système est VALIDE mais
        // SANS pastille.
        assertNull("hors trio", CouleursProjetXml.resoudre("@android:color/holo_red_dark", aucune))
    }

    @Test
    public fun `resoudre laisse les attr au theme`() {
        // ?attr/… se résout côté UI (miroir `ATTR_CSS`) : le module pur
        // ne sait pas — il rend la main.
        assertNull(CouleursProjetXml.resoudre("?attr/colorPrimary", emptyMap()))
        assertNull(CouleursProjetXml.resoudre(null, emptyMap()))
    }

    @Test
    public fun `les constants de palette sont ceux de la maquette`() {
        // Miroir `ATTR_CSS` : les SIX clés, dans l'ordre.
        assertEquals(
            listOf("colorPrimary", "colorOnPrimary", "colorSurface", "colorOnSurface", "colorOutline", "colorError"),
            CouleursProjetXml.ATTRS_THEME,
        )
        // Miroir le `.concat(...)` du bouton Palette : le trio système +
        // les DEUX attributs de thème.
        assertEquals(
            listOf(
                "@android:color/white",
                "@android:color/black",
                "@android:color/transparent",
                "?attr/colorPrimary",
                "?attr/colorError",
            ),
            CouleursProjetXml.PALETTE_FIXE,
        )
        // Miroir `ANDROID_COL` : le trio.
        assertEquals(setOf("white", "black", "transparent"), CouleursProjetXml.ANDROID.keys)
    }
}
