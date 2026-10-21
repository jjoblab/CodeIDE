package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calculs des calques de sélection (PE6a) — fonctions pures : conversion
 * dp, étiquette « nom · dimensions », frères et parent dans l'arbre
 * aplati.
 */
class CalquesApercuTest {
    /** Arbre de référence : racine(0) → [gauche(1), droite(2)] — pré-ordre parent-avant-enfants. */
    private fun arbre(): List<InfoVueRendu> =
        listOf(
            vue("FrameLayout", indiceParent = -1, profondeur = 0),
            vue("TextView", indiceParent = 0, profondeur = 1),
            vue("Button", indiceParent = 0, profondeur = 1),
        )

    /** Une vue du rendu avec IDENTIFIANT, famille de conteneur et drapeau conteneur (PE12e). */
    private fun InfoVueRendu.avecId(
        id: String,
        famille: String = "",
        conteneur: Boolean = false,
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = gauche,
            haut = haut,
            droite = droite,
            bas = bas,
            libelle = libelle,
            debutElement = debutElement,
            finElement = finElement,
            indiceParent = indiceParent,
            profondeur = profondeur,
            classe = libelle,
            idRes = id,
            conteneur = conteneur,
            famille = famille,
        )

    private fun vue(
        nom: String,
        indiceParent: Int,
        profondeur: Int,
        largeurPx: Int = 120,
        hauteurPx: Int = 48,
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = 0,
            haut = 0,
            droite = largeurPx,
            bas = hauteurPx,
            libelle = nom,
            debutElement = 0,
            finElement = 10,
            indiceParent = indiceParent,
            profondeur = profondeur,
        )

    @Test
    fun `conversion dp - densité du profil`() {
        // 440 dpi (Pixel 4) : 220 px = 80 dp (220 × 160 / 440).
        assertEquals(80, CalquesApercu.pxVersDp(220, 440))
        assertEquals("80×17dp", CalquesApercu.dimensions(220, 48, 440))
    }

    @Test
    fun `conversion dp - densite inconnue - repli px`() {
        assertEquals("120×48px", CalquesApercu.dimensions(120, 48, 0))
        assertEquals("120×48px", CalquesApercu.dimensions(120, 48, -3))
    }

    @Test
    fun `etiquette - nom puis dimensions`() {
        assertEquals(
            "TextView @+id/titre · 120×48dp",
            CalquesApercu.libelleSelection("TextView @+id/titre", "120×48dp"),
        )
    }

    @Test
    fun `etiquette - sans dimensions - nom seul`() {
        assertEquals("TextView", CalquesApercu.libelleSelection("TextView", null))
        assertEquals("TextView", CalquesApercu.libelleSelection("TextView", ""))
    }

    @Test
    fun `freres - meme parent, selection exclue`() {
        val vues = arbre()
        // Le TextView (1) a pour frère le Button (2) — pas la racine.
        assertEquals(listOf(2), CalquesApercu.indicesFreres(vues, 1))
        assertEquals(listOf(1), CalquesApercu.indicesFreres(vues, 2))
    }

    @Test
    fun `freres - racines entre elles`() {
        val vues = arbre()
        // La racine (0) n'a pas de frère (seul élément de profondeur 0).
        assertTrue(CalquesApercu.indicesFreres(vues, 0).isEmpty())
    }

    @Test
    fun `freres - indice hors bornes - vide`() {
        val vues = arbre()
        assertTrue(CalquesApercu.indicesFreres(vues, -1).isEmpty())
        assertTrue(CalquesApercu.indicesFreres(vues, 42).isEmpty())
    }

    @Test
    fun `parent - indice du parent, racine sans parent`() {
        val vues = arbre()
        assertEquals(0, CalquesApercu.indiceParent(vues, 1))
        assertEquals(-1, CalquesApercu.indiceParent(vues, 0))
        assertEquals(-1, CalquesApercu.indiceParent(vues, 42))
    }

    // ── Lignes de contrainte (PE6c — miroir drawConstraints de la maquette) ──

    /** Enfants directs de la racine, décalés : 24 px de marge gauche, 8 px entre eux. */
    private fun arbreContraintes(): List<InfoVueRendu> =
        listOf(
            vue("FrameLayout", indiceParent = -1, profondeur = 0).copierBounds(0, 0, 400, 800),
            vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(24, 8, 376, 60),
            vue("Button", indiceParent = 0, profondeur = 1).copierBounds(24, 68, 200, 120),
        )

    private fun InfoVueRendu.copierBounds(
        gauche: Int,
        haut: Int,
        droite: Int,
        bas: Int,
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = gauche,
            haut = haut,
            droite = droite,
            bas = bas,
            libelle = libelle,
            debutElement = debutElement,
            finElement = finElement,
            indiceParent = indiceParent,
            profondeur = profondeur,
            classe = classe,
        )

    @Test
    fun `contraintes - ecart au frere precedent en vertical`() {
        val lignes = CalquesApercu.lignesContraintes(arbreContraintes(), densiteDpi = 160)
        // Le Button (68) suit le TextView (bas à 60) : écart 8 px = 8 dp à 160 dpi.
        val verticale = lignes.filter { it.verticale }
        assertEquals(2, verticale.size)
        assertEquals(8, verticale[0].valeurDp) // TextView : haut 8 − racine 0.
        assertEquals(8, verticale[1].valeurDp) // Button : 68 − 60.
        // Segment vertical au centre X de la vue, du bord du précédent au haut de la vue.
        assertEquals(60, verticale[1].y1)
        assertEquals(68, verticale[1].y2)
        assertEquals((24 + 200) / 2, verticale[1].x1)
    }

    @Test
    fun `contraintes - ecarts aux bords de la racine en horizontal`() {
        val lignes = CalquesApercu.lignesContraintes(arbreContraintes(), densiteDpi = 160)
        val horizontales = lignes.filter { !it.verticale }
        // TextView : 24 px à gauche, 24 px à droite (376 → 400).
        // Button : 24 px à gauche, 200 px à droite (200 → 400).
        assertEquals(4, horizontales.size)
        assertEquals(listOf(24, 24, 24, 200), horizontales.map { it.valeurDp })
        // La ligne gauche va du bord racine au bord vue, au centre Y.
        val gaucheVue = horizontales.first { it.x1 == 0 }
        assertEquals(24, gaucheVue.x2)
        assertEquals((8 + 60) / 2, gaucheVue.y1)
    }

    @Test
    fun `contraintes - vue collee au bord - pas de ligne (seuil)`() {
        val vues =
            listOf(
                vue("FrameLayout", indiceParent = -1, profondeur = 0).copierBounds(0, 0, 200, 200),
                vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(0, 0, 200, 60),
            )
        assertTrue(CalquesApercu.lignesContraintes(vues, densiteDpi = 160).isEmpty())
    }

    @Test
    fun `contraintes - conversion par la densite du profil`() {
        // 48 px à 440 dpi (Pixel 4) = 17 dp (arrondi de 17,45).
        val vues =
            listOf(
                vue("FrameLayout", indiceParent = -1, profondeur = 0).copierBounds(0, 0, 400, 800),
                vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(48, 48, 352, 100),
            )
        val lignes = CalquesApercu.lignesContraintes(vues, densiteDpi = 440)
        assertEquals(17, lignes.first { it.verticale }.valeurDp)
    }

    @Test
    fun `contraintes - densite inconnue ou sans racine - vide`() {
        assertTrue(CalquesApercu.lignesContraintes(arbreContraintes(), densiteDpi = 0).isEmpty())
        assertTrue(CalquesApercu.lignesContraintes(emptyList(), densiteDpi = 440).isEmpty())
    }

    // ── PE12e : lignesRelations (spec § 8, miroir drawRelations) ───────

    /**
     * L'arbre PROFIL (nœuds construits à la main — le pont texte → nœud
     * est couvert par [ArbreAttributsXmlTest] ; XmlPullParser, lui,
     * exige Robolectric).
     */
    private fun arbreRelatif(): List<ArbreAttributsXml.Noeud> {
        fun noeud(
            balise: String,
            id: String?,
            attributs: Map<String, String> = emptyMap(),
        ): ArbreAttributsXml.Noeud = ArbreAttributsXml.Noeud(balise, id, attributs, 0, 1, 0, emptyList())
        val racine =
            ArbreAttributsXml.Noeud(
                "RelativeLayout",
                "root",
                emptyMap(),
                0,
                1,
                -1,
                listOf(
                    noeud(
                        "ImageView",
                        "avatar",
                        mapOf(
                            "android:layout_alignParentStart" to "true",
                            "android:layout_alignParentTop" to "true",
                        ),
                    ),
                    noeud(
                        "TextView",
                        "nom",
                        mapOf(
                            "android:layout_toEndOf" to "@id/avatar",
                            "android:layout_alignTop" to "@id/avatar",
                        ),
                    ),
                    noeud(
                        "TextView",
                        "role",
                        mapOf(
                            "android:layout_below" to "@id/nom",
                            "android:layout_alignStart" to "@id/nom",
                        ),
                    ),
                    noeud("Button", "bouton", mapOf("android:layout_centerInParent" to "true")),
                ),
            )
        return listOf(racine) + racine.enfants
    }

    /** Le rendu correspondant (bornes calquées sur la maquette profil). */
    private fun renduRelatif(): List<InfoVueRendu> =
        listOf(
            vue(
                "RelativeLayout",
                indiceParent = -1,
                profondeur = 0,
            ).copierBounds(0, 0, 360, 640).avecId("root", famille = "RELATIVE", conteneur = true),
            vue("ImageView", indiceParent = 0, profondeur = 1).copierBounds(16, 16, 96, 96).avecId("avatar"),
            vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(112, 16, 250, 40).avecId("nom"),
            vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(112, 44, 240, 64).avecId("role"),
            vue("Button", indiceParent = 0, profondeur = 1).copierBounds(108, 296, 252, 344).avecId("bouton"),
        )

    /** L'arbre FIL (CoordinatorLayout + ancre, nœuds construits à la main). */
    private fun arbreCoordonnateur(): List<ArbreAttributsXml.Noeud> {
        fun noeud(
            balise: String,
            id: String?,
            attributs: Map<String, String> = emptyMap(),
        ): ArbreAttributsXml.Noeud = ArbreAttributsXml.Noeud(balise, id, attributs, 0, 1, 0, emptyList())
        val racine =
            ArbreAttributsXml.Noeud(
                "CoordinatorLayout",
                "root",
                emptyMap(),
                0,
                1,
                -1,
                listOf(
                    noeud("RecyclerView", "liste"),
                    noeud(
                        "FloatingActionButton",
                        "fab",
                        mapOf("app:layout_anchor" to "@id/liste", "app:layout_anchorGravity" to "bottom|end"),
                    ),
                ),
            )
        return listOf(racine) + racine.enfants
    }

    /** Le rendu correspondant. */
    private fun renduCoordonnateur(): List<InfoVueRendu> =
        listOf(
            vue(
                "CoordinatorLayout",
                indiceParent = -1,
                profondeur = 0,
            ).copierBounds(0, 0, 360, 640).avecId("root", famille = "COORDINATOR", conteneur = true),
            vue("RecyclerView", indiceParent = 0, profondeur = 1).copierBounds(0, 104, 360, 640).avecId("liste"),
            vue(
                "FloatingActionButton",
                indiceParent = 0,
                profondeur = 1,
            ).copierBounds(288, 568, 344, 624).avecId("fab"),
        )

    @Test
    fun `relations - regle de cote en trait plein, alignement en pointille`() {
        // nom : toEndOf (PLEIN, du bord gauche de nom au bord droit
        // d'avatar) + alignTop (POINTILLÉ le long du bord haut partagé).
        val lignes = CalquesApercu.lignesRelations(renduRelatif(), arbreRelatif())
        // toEndOf : du bord DÉBUT de nom (112) au bord FIN d'avatar (96),
        // au CENTRE vertical de nom (28) — trait PLEIN.
        val toEndOf = lignes.first { it.etiquette == "toEndOf" }
        assertFalse(toEndOf.pointillee)
        assertEquals(112, toEndOf.x1)
        assertEquals(28, toEndOf.y1)
        assertEquals(96, toEndOf.x2)
        // alignTop : le long du bord HAUT partagé (y = 16), pointillé.
        val alignTop = lignes.first { it.etiquette == "alignTop" }
        assertTrue(alignTop.pointillee)
        assertEquals(16, alignTop.y1)
        assertEquals(16, alignTop.x1)
        assertEquals(250, alignTop.x2)
        // role : below (PLEIN du haut de role au bas de nom, au centre X
        // de role : 176).
        val below = lignes.first { it.etiquette == "below" }
        assertFalse(below.pointillee)
        assertEquals(176, below.x1)
        assertEquals(44, below.y1)
        assertEquals(40, below.y2)
    }

    @Test
    fun `relations - bords du parent en plein, centres en pointille`() {
        val lignes = CalquesApercu.lignesRelations(renduRelatif(), arbreRelatif())
        // avatar : alignParentStart + alignParentTop → « parent » PLEIN
        // vers les bords (padding nul) ; bouton : centerInParent → les
        // DEUX traits POINTILLÉS aux centres du parent.
        val parents = lignes.filter { it.etiquette == "parent" }
        assertEquals(2, parents.size)
        assertTrue(parents.all { !it.pointillee })
        // centerInParent : les DEUX traits POINTILLÉS aux centres du
        // parent (180, 320), SANS étiquette (miroir `labels.push` absent).
        val verticals = lignes.filter { it.pointillee && it.x1 == it.x2 && it.indiceVue == 4 }
        val horizontals = lignes.filter { it.pointillee && it.y1 == it.y2 && it.indiceVue == 4 }
        assertEquals(1, verticals.size)
        assertEquals(180, verticals.first().x1)
        assertTrue(verticals.all { it.etiquette == null })
        assertEquals(1, horizontals.size)
        assertEquals(320, horizontals.first().y1)
        assertTrue(horizontals.all { it.etiquette == null })
    }

    @Test
    fun `relations - l ancre du coordinator en pointille du point d ancrage au centre`() {
        val lignes = CalquesApercu.lignesRelations(renduCoordonnateur(), arbreCoordonnateur())
        val ancre = lignes.first { it.etiquette?.startsWith("anchor ·") == true }
        assertTrue(ancre.pointillee)
        // anchorGravity bottom|end : point d'ancrage au coin bas-fin de la
        // liste (360, 640) → centre du FAB (316, 596).
        assertEquals(360, ancre.x1)
        assertEquals(640, ancre.y1)
        assertEquals(316, ancre.x2)
        assertEquals(596, ancre.y2)
        assertEquals(2, ancre.indiceVue)
    }

    @Test
    fun `relations - un parent frame ne produit aucune ligne`() {
        // Une pile `FrameLayout` n'a NI règles NI ancres : le calque
        // Relations ne dessine rien pour elle (§ 8).
        val racine =
            ArbreAttributsXml.Noeud(
                "FrameLayout",
                "root",
                emptyMap(),
                0,
                1,
                -1,
                listOf(
                    ArbreAttributsXml.Noeud(
                        "TextView",
                        "badge",
                        mapOf("android:layout_gravity" to "top|end"),
                        0,
                        1,
                        0,
                        emptyList(),
                    ),
                ),
            )
        val vues =
            listOf(
                vue(
                    "FrameLayout",
                    indiceParent = -1,
                    profondeur = 0,
                ).copierBounds(0, 0, 360, 220).avecId("root", famille = "FRAME", conteneur = true),
                vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(264, 16, 344, 40).avecId("badge"),
            )
        assertTrue(CalquesApercu.lignesRelations(vues, listOf(racine) + racine.enfants).isEmpty())
    }

    @Test
    fun `relations - une regle vers un frere introuvable est ignoree`() {
        // La cible ne résout AUCUN frère : PAS d'arête (spec § 9 — les
        // règles mortes sont des PROBLÈMES, pas des lignes).
        val racine =
            ArbreAttributsXml.Noeud(
                "RelativeLayout",
                "root",
                emptyMap(),
                0,
                1,
                -1,
                listOf(
                    ArbreAttributsXml.Noeud(
                        "TextView",
                        "a",
                        mapOf("android:layout_below" to "@id/absent"),
                        0,
                        1,
                        0,
                        emptyList(),
                    ),
                ),
            )
        val vues =
            listOf(
                vue(
                    "RelativeLayout",
                    indiceParent = -1,
                    profondeur = 0,
                ).copierBounds(0, 0, 360, 640).avecId("root", famille = "RELATIVE", conteneur = true),
                vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(16, 16, 100, 40).avecId("a"),
            )
        assertTrue(CalquesApercu.lignesRelations(vues, listOf(racine) + racine.enfants).isEmpty())
    }
}
