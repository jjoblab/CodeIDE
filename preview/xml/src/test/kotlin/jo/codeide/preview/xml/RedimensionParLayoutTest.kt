package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les AJUSTEMENTS PAR-LAYOUT du relâchement (PE14-S2 C3, ADR 0138 —
 * miroir `RelativeResizeTarget`/`ConstraintResizeTarget` de la
 * référence) — fonctions PURES sans Android : les marges du bord TENU
 * pour un enfant de RelativeLayout, les positions absolues du dialecte
 * `tools:` pour un enfant de ConstraintLayout, le poids qui contredit
 * la taille fixe.
 */
public class RedimensionParLayoutTest {
    /** La vue sélectionnée de référence (px bitmap, densité 440). */
    private fun selection(): InfoVueRendu =
        InfoVueRendu(
            gauche = 20,
            haut = 40,
            droite = 220,
            bas = 136,
            libelle = "TextView @+id/titre",
            debutElement = 0,
            finElement = 10,
            indiceParent = 0,
            profondeur = 1,
        )

    /** Un parent RelativeLayout plein écran (px bitmap). */
    private fun parent(): InfoVueRendu =
        InfoVueRendu(
            gauche = 0,
            haut = 0,
            droite = 440,
            bas = 880,
            libelle = "RelativeLayout",
            debutElement = 0,
            finElement = 10,
            indiceParent = -1,
            profondeur = 0,
            famille = "RELATIVE",
        )

    /** Une vue frère rendue sous le nom d'id « ancre » (px bitmap). */
    private fun frere(): InfoVueRendu =
        InfoVueRendu(
            gauche = 0,
            haut = 60,
            droite = 200,
            bas = 140,
            libelle = "TextView",
            debutElement = 0,
            finElement = 10,
            indiceParent = 0,
            profondeur = 1,
            idRes = "ancre",
        )

    /** Le cadre complet pour [attributs] donnés. */
    private fun cadre(attributs: Map<String, String>): RedimensionParLayout.CadreRedimension =
        RedimensionParLayout.CadreRedimension(
            attributs = attributs,
            selection = selection(),
            parent = parent(),
            freres = mapOf("ancre" to frere()),
            densiteDpi = 440,
        )

    /** La cible d'une saisie HAUT-GAUCHE ramenée au coin (fantôme = la vue). */
    private fun cibleHautGauche(): RedimensionApercu.CibleRedimension =
        RedimensionApercu.CibleRedimension(
            largeurDp = 73,
            hauteurDp = 35,
            ancreX = 220,
            ancreY = 136,
            coinX = 20,
            coinY = 40,
        )

    @Test
    public fun `margesARetenir - bord gauche tenu sans regle suit le doigt`() {
        // Poignée HAUT-GAUCHE saisie → ancre BAS-DROITE (220, 136) : le
        // bord GAUCHE suit le doigt ; SANS règle, RelativeLayout ancre au
        // parent — la marge = bord du fantôme − bord du parent.
        val marges = RedimensionParLayout.margesARetenir(cadre(emptyMap()), cibleHautGauche())
        assertEquals(
            "les DEUX bords tenus suivent le doigt (gauche + haut)",
            listOf(EditionAttributsXml.Cote.DEBUT to 7, EditionAttributsXml.Cote.HAUT to 15),
            marges,
        )
    }

    @Test
    public fun `margesARetenir - bord droit derive de son ancre ne s ecrit pas`() {
        // Poignée HAUT-GAUCHE saisie mais la vue est alignée À DROITE
        // (alignParentRight) : le bord GAUCHE est DÉRIVÉ (droite −
        // largeur) — il suit le doigt PAR CONSTRUCTION, aucune marge
        // gauche à écrire ; le bord HAUT reste au parent.
        val marges =
            RedimensionParLayout.margesARetenir(
                cadre(mapOf("android:layout_alignParentRight" to "true")),
                cibleHautGauche(),
            )
        assertEquals(
            "seul le bord HAUT tenu s'écrit",
            listOf(EditionAttributsXml.Cote.HAUT to 15),
            marges,
        )
    }

    @Test
    public fun `margesARetenir - regle vers un frere mesure depuis le frere`() {
        // toRightOf d'une vue « ancre » finissant à droite=200 : la marge
        // GAUCHE du bord tenu se mesure DEPUIS le bord droit du frère.
        // Fantôme à gauche=120 : 120 − 200 = −80 → REFUS (clamp négatif),
        // le bord HAUT s'écrit seul.
        val cibleDedans =
            RedimensionApercu.CibleRedimension(
                largeurDp = 73,
                hauteurDp = 35,
                ancreX = 220,
                ancreY = 136,
                coinX = 120,
                coinY = 40,
            )
        val marges =
            RedimensionParLayout.margesARetenir(
                cadre(mapOf("android:layout_toRightOf" to "@+id/ancre")),
                cibleDedans,
            )
        assertEquals(
            "marge négative depuis le frère : REFUS, le haut seul s'écrit",
            listOf(EditionAttributsXml.Cote.HAUT to 15),
            marges,
        )
        // Fantôme à gauche=220 : 220 − 200 = 20 px → 7 dp.
        val cibleFrere =
            RedimensionApercu.CibleRedimension(
                largeurDp = 73,
                hauteurDp = 35,
                ancreX = 320,
                ancreY = 136,
                coinX = 220,
                coinY = 40,
            )
        val cadreFrere =
            RedimensionParLayout.CadreRedimension(
                attributs = mapOf("android:layout_toRightOf" to "@+id/ancre"),
                selection =
                    selection().let { vue ->
                        InfoVueRendu(120, 40, 320, 136, vue.libelle, vue.debutElement, vue.finElement, 0, 1)
                    },
                parent = parent(),
                freres = mapOf("ancre" to frere()),
                densiteDpi = 440,
            )
        assertEquals(
            "marge depuis le frère : 220 − 200 = 20 px → 7 dp",
            listOf(EditionAttributsXml.Cote.DEBUT to 7, EditionAttributsXml.Cote.HAUT to 15),
            RedimensionParLayout.margesARetenir(cadreFrere, cibleFrere),
        )
    }

    @Test
    public fun `margesARetenir - ancre introuvable ne s ecrit jamais`() {
        // La règle vise un id que le rendu ne connaît pas (texte édité
        // depuis, vue supprimée) : AUCUNE écriture fausse — le côté saute
        // (le HAUT, ancré à l'inconnue) ; le côté SANS règle suit le
        // parent comme toujours.
        val marges =
            RedimensionParLayout.margesARetenir(
                cadre(emptyMap()).let { cadre ->
                    cadre.copy(freres = emptyMap(), attributs = mapOf("android:layout_below" to "@+id/inconnu"))
                },
                cibleHautGauche(),
            )
        assertEquals(
            "le haut saute (ancre inconnue), la gauche suit le parent",
            listOf(EditionAttributsXml.Cote.DEBUT to 7),
            marges,
        )
    }

    @Test
    public fun `margesARetenir - axe centre ne s ecrit jamais`() {
        val marges =
            RedimensionParLayout.margesARetenir(
                cadre(
                    mapOf(
                        "android:layout_centerInParent" to "true",
                        "android:layout_centerVertical" to "true",
                    ),
                ),
                cibleHautGauche(),
            )
        assertTrue("les deux axes centrés ne s'écrivent pas", marges.isEmpty())
    }

    @Test
    public fun `positionsOutils - le bord deplace se pose, l ancre jamais`() {
        val cadreOutils = cadre(emptyMap())
        // Poignée HAUT-GAUCHE : les DEUX bords déplacés se posent.
        assertEquals(
            7 to 15,
            RedimensionParLayout.positionsOutils(cadreOutils, cibleHautGauche(), outilsAutorises = true),
        )
        // Poignée BAS-DROITE : AUCUN bord déplacé (l'ancre est en haut à
        // gauche), rien à poser.
        val cibleAncreHautGauche =
            RedimensionApercu.CibleRedimension(
                largeurDp = 73,
                hauteurDp = 35,
                ancreX = 20,
                ancreY = 40,
                coinX = 220,
                coinY = 136,
            )
        assertEquals(
            "seuls les bords déplacés portent une position",
            null to null,
            RedimensionParLayout.positionsOutils(cadreOutils, cibleAncreHautGauche, outilsAutorises = true),
        )
        // Dialecte `tools:` absent du document (arbitrage D3) : jamais imposé.
        assertEquals(
            null to null,
            RedimensionParLayout.positionsOutils(cadreOutils, cibleHautGauche(), outilsAutorises = false),
        )
    }

    @Test
    public fun `poidsARetirer - un poids positif contredit la taille fixe`() {
        assertTrue(
            "poids 1 : la taille écrite serait absorbée",
            RedimensionParLayout.poidsARetirer(mapOf("android:layout_weight" to "1")),
        )
        assertFalse(
            "poids 0 : rien à retirer",
            RedimensionParLayout.poidsARetirer(mapOf("android:layout_weight" to "0")),
        )
        assertFalse("pas de poids du tout", RedimensionParLayout.poidsARetirer(emptyMap()))
    }
}
