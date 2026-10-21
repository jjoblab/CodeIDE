package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le PLACEMENT PAR POPUP PAR FAMILLE (PE12d — spec § 7, § 15.1 :
 * « jeu de positions par famille ; filtre feuille / racine ; position
 * invalide → première valide ») — miroirs `posList`, `normPos`,
 * `placeTarget` de la maquette : les positions AUTORISÉES suivent la
 * famille du PÈRE de la référence (côtés pour `RelativeLayout`, pile
 * pour `FrameLayout`/`CoordinatorLayout`, séquence sinon), DEDANS exige
 * un conteneur, la RACINE n'offre que Dedans, et une position invalide
 * retombe sur la première valide (Dessous pour un frère
 * `RelativeLayout`).
 */
public class PlacementPopupTest {
    /** Densité du profil de test (160 → px = dp). */
    private val densite = 160

    /** Racine `RelativeLayout` : avatar (feuille), stats (conteneur), racine. */
    private fun arbreRelative(): List<InfoVueRendu> =
        listOf(
            vue(0, 0, 360, 640, "RelativeLayout", "root", parent = -1, conteneur = true, famille = "RELATIVE"),
            vue(16, 16, 96, 96, "ImageView", "avatar", parent = 0),
            vue(16, 185, 344, 233, "LinearLayout", "stats", parent = 0, conteneur = true, famille = "LINEAR"),
        )

    /** Racine `FrameLayout` : badge (feuille), legende (conteneur). */
    private fun arbreFrame(): List<InfoVueRendu> =
        listOf(
            vue(0, 0, 360, 220, "FrameLayout", "root", parent = -1, conteneur = true, famille = "FRAME"),
            vue(264, 16, 344, 40, "TextView", "badge", parent = 0),
            vue(0, 158, 360, 220, "LinearLayout", "legende", parent = 0, conteneur = true, famille = "LINEAR"),
        )

    /** Racine `LinearLayout` : texte (feuille), groupe (conteneur). */
    private fun arbreLineaire(): List<InfoVueRendu> =
        listOf(
            vue(0, 0, 360, 640, "LinearLayout", "root", parent = -1, conteneur = true, famille = "LINEAR"),
            vue(16, 16, 200, 40, "TextView", "texte", parent = 0),
            vue(16, 56, 344, 104, "LinearLayout", "groupe", parent = 0, conteneur = true, famille = "LINEAR"),
        )

    @Test
    public fun `un frere d un parent relative offre les quatre cotes plus dedans si conteneur`() {
        val vues = arbreRelative()
        // avatar : FEUILLE d'un RelativeLayout → les quatre côtés, PAS Dedans.
        assertEquals(
            setOf(
                PlacementPopup.Position.DESSOUS,
                PlacementPopup.Position.DESSUS,
                PlacementPopup.Position.DROITE,
                PlacementPopup.Position.GAUCHE,
            ),
            PlacementPopup.positionsAutorisees(vues, 1),
        )
        // stats : CONTENEUR du même RelativeLayout → les côtés + Dedans.
        assertEquals(
            setOf(
                PlacementPopup.Position.DESSOUS,
                PlacementPopup.Position.DESSUS,
                PlacementPopup.Position.DROITE,
                PlacementPopup.Position.GAUCHE,
                PlacementPopup.Position.DEDANS,
            ),
            PlacementPopup.positionsAutorisees(vues, 2),
        )
        assertEquals(PlacementPopup.JeuPositions.COTES_RELATIFS, PlacementPopup.jeuPositions(vues, 1))
    }

    @Test
    public fun `un frere d une pile offre derriere dedans devant`() {
        val vues = arbreFrame()
        // badge : feuille d'un FrameLayout → Derrière / Devant (AVANT / APRES).
        assertEquals(
            setOf(PlacementPopup.Position.AVANT, PlacementPopup.Position.APRES),
            PlacementPopup.positionsAutorisees(vues, 1),
        )
        // legende : conteneur → les trois (Derrière · Dedans · Devant).
        assertEquals(
            setOf(PlacementPopup.Position.AVANT, PlacementPopup.Position.DEDANS, PlacementPopup.Position.APRES),
            PlacementPopup.positionsAutorisees(vues, 2),
        )
        assertEquals(PlacementPopup.JeuPositions.PILE, PlacementPopup.jeuPositions(vues, 1))
    }

    @Test
    public fun `la racine n offre que dedans et une feuille lineaire n offre pas dedans`() {
        val vues = arbreLineaire()
        assertEquals(setOf(PlacementPopup.Position.DEDANS), PlacementPopup.positionsAutorisees(vues, 0))
        assertEquals(PlacementPopup.JeuPositions.DEDANS_SEUL, PlacementPopup.jeuPositions(vues, 0))
        // texte : feuille d'un LinearLayout → Avant / Après (SEQUENCE).
        assertEquals(
            setOf(PlacementPopup.Position.AVANT, PlacementPopup.Position.APRES),
            PlacementPopup.positionsAutorisees(vues, 1),
        )
        assertEquals(PlacementPopup.JeuPositions.SEQUENCE, PlacementPopup.jeuPositions(vues, 1))
    }

    @Test
    public fun `la position initiale suit le conteneur sinon apres sinon la premiere`() {
        val vues = arbreRelative()
        // Conteneur (stats) → DEDANS ; feuille (avatar) → PAS Après autorisé
        // → la PREMIÈRE autorisée : DESSOUS (miroir `ok[0]`).
        assertEquals(PlacementPopup.Position.DEDANS, PlacementPopup.positionInitiale(vues, 2))
        assertEquals(PlacementPopup.Position.DESSOUS, PlacementPopup.positionInitiale(vues, 1))
        assertEquals(PlacementPopup.Position.DEDANS, PlacementPopup.positionInitiale(vues, 0))
    }

    @Test
    public fun `la cible relative ecrit la regle vers le frere au bon index`() {
        val vues = arbreRelative()
        // Dessous d'avatar (marge 8) : parent = racine, index APRÈS avatar,
        // plan = below @id/avatar + marge 8 + alignStart (croix nulle).
        val cible =
            PlacementPopup.cible(
                vues,
                1,
                PlacementPopup.Position.DESSOUS,
                margeDp = 8,
                depose = PlanInsertionFamille.Depose(densiteDpi = densite),
            )
        assertNotNull(cible)
        assertEquals(0, cible!!.indiceParent)
        assertEquals(1, cible.index)
        val plan = cible.plan
        assertNotNull(plan)
        assertEquals("android:layout_below" to "@id/avatar", plan!!.attributs.first())
        assertEquals(8, plan.marges.haut)
        assertEquals(PlanInsertionFamille.GenrePlan.REGLE, plan.genre)
        assertTrue(plan.traitRegle)
    }

    @Test
    public fun `la cible dedans relative ancre au parent par la grille`() {
        val vues = arbreRelative()
        // Dedans la RACINE RelativeLayout (cellule 1-1) : parent = racine,
        // index = fin de ses enfants, plan = centerInParent (versParent).
        val cible =
            PlacementPopup.cible(
                vues,
                0,
                PlacementPopup.Position.DEDANS,
                cellule = PlanInsertionFamille.Cellule(ligne = 1, colonne = 1),
                depose = PlanInsertionFamille.Depose(densiteDpi = densite),
            )
        assertNotNull(cible)
        assertEquals(0, cible!!.indiceParent)
        assertEquals(2, cible.index)
        assertEquals(
            listOf("android:layout_centerInParent" to "true"),
            cible.plan!!.attributs,
        )
        assertEquals(PlanInsertionFamille.GenrePlan.PARENT_RELATIF, cible.plan!!.genre)
    }

    @Test
    public fun `la cible dedans d un conteneur lineaire reste une barre sans plan`() {
        val vues = arbreRelative()
        // « Dedans stats » (LinearLayout dans le RelativeLayout) : la
        // famille de la référence est LINEAR → AUCUN plan (barre, § 6.2),
        // l'index va en FIN de ses enfants.
        val cible =
            PlacementPopup.cible(
                vues,
                2,
                PlacementPopup.Position.DEDANS,
                depose = PlanInsertionFamille.Depose(densiteDpi = densite),
            )
        assertNotNull(cible)
        assertEquals(2, cible!!.indiceParent)
        assertEquals(0, cible.index)
        assertNull(cible.plan)
    }

    @Test
    public fun `la cible d une pile porte la cellule du pere et l ordre du document`() {
        val vues = arbreFrame()
        // Derrière badge : parent = racine, index AVANT badge (0) ; le plan
        // de cellule s'applique au PÈRE (miroir `framePlan(f.p, p.cell, …)`).
        val cible =
            PlacementPopup.cible(
                vues,
                1,
                PlacementPopup.Position.AVANT,
                cellule = PlanInsertionFamille.Cellule(ligne = 2, colonne = 2),
                depose = PlanInsertionFamille.Depose(densiteDpi = densite),
            )
        assertNotNull(cible)
        assertEquals(0, cible!!.indiceParent)
        assertEquals(0, cible.index)
        val plan = cible.plan
        assertNotNull(plan)
        assertEquals(listOf("android:layout_gravity" to "end|bottom"), plan!!.attributs)
        assertEquals(16, plan.marges.fin)
        assertEquals(16, plan.marges.bas)
        assertEquals(PlanInsertionFamille.GenrePlan.PILE, plan.genre)
    }

    @Test
    public fun `une position non autorisee refuse la cible`() {
        val vues = arbreRelative()
        // APRES n'existe pas pour un frère RelativeLayout : refus (X4),
        // jamais de cible devinée.
        assertNull(PlacementPopup.cible(vues, 1, PlacementPopup.Position.APRES))
        assertNull(PlacementPopup.cible(vues, 0, PlacementPopup.Position.APRES))
    }

    @Test
    public fun `le resume porte le libelle du plan pour la regle`() {
        val vues = arbreRelative()
        val cible =
            PlacementPopup.cible(
                vues,
                1,
                PlacementPopup.Position.DESSOUS,
                margeDp = 8,
                depose = PlanInsertionFamille.Depose(densiteDpi = densite),
            )
        val resume = PlacementPopup.resume(vues, cible)
        assertNotNull(resume)
        assertEquals("RelativeLayout · @+id/root", resume!!.libelleParent)
        assertEquals("Sous @id/avatar · marge 8 dp", resume.libellePlan)
        assertEquals(2, resume.position)
        assertEquals(3, resume.total)
    }

    // ── Fixture ──────────────────────────────────────────────────────────

    /** Une vue de test compacte. */
    @Suppress("LongParameterList")
    private fun vue(
        gauche: Int,
        haut: Int,
        droite: Int,
        bas: Int,
        classe: String,
        id: String,
        parent: Int,
        conteneur: Boolean = false,
        famille: String = "",
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = gauche,
            haut = haut,
            droite = droite,
            bas = bas,
            libelle = classe,
            debutElement = 0,
            finElement = 1,
            indiceParent = parent,
            profondeur = if (parent < 0) 0 else 1,
            classe = classe,
            idRes = id,
            conteneur = conteneur,
            famille = famille,
        )
}
