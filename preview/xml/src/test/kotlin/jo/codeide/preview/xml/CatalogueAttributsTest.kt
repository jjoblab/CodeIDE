package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrat du CATALOGUE des attributs (PE7e, maquette § « Attributs en
 * sections selon le type de vue » — miroir `sectionsFor`) : chaque widget
 * de [CataloguePalette] reçoit des sections NON VIDES et SANS DOUBLON
 * d'attribut, une classe inconnue ne reçoit que les sections GÉNÉRIQUES
 * (miroir piège § 5 du prompt PE7e), et les valeurs d'énumération
 * correspondent à la LISTE DE RÉFÉRENCE figée de la plateforme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class CatalogueAttributsTest {
    /** Les noms d'attributs d'une section (Dimensions/Contraintes : contrôle sans nom). */
    private fun noms(section: CatalogueAttributs.SectionAttributs): List<String> =
        section.controles.mapNotNull { controle ->
            when (controle) {
                is CatalogueAttributs.ControleAttribut.Texte -> controle.nom

                is CatalogueAttributs.ControleAttribut.Nombre -> controle.nom

                is CatalogueAttributs.ControleAttribut.Choix -> controle.nom

                is CatalogueAttributs.ControleAttribut.TriEtat -> controle.nom

                is CatalogueAttributs.ControleAttribut.Cotes -> controle.base

                CatalogueAttributs.ControleAttribut.Dimensions,
                CatalogueAttributs.ControleAttribut.Contraintes,
                -> null
            }
        }

    // ── Couverture des 16 widgets de la palette ──────────────────────────

    @Test
    fun `chaque widget de la palette a des sections non vides`() {
        for (entree in CataloguePalette.CATALOGUE) {
            val sections = CatalogueAttributs.sections(entree.classe, CatalogueAttributs.GenreParent.AUTRE)
            assertTrue(
                "Le widget ${entree.classe} (${entree.id}) doit avoir des sections",
                sections.isNotEmpty(),
            )
            sections.forEach { section ->
                assertTrue(
                    "La section ${section.titre} de ${entree.classe} doit avoir des contrôles",
                    section.controles.isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `aucun attribut n'apparait dans deux sections d'une meme vue`() {
        val classes =
            CataloguePalette.CATALOGUE.map { it.classe }.distinct() +
                listOf("MaterialButton", "SwitchMaterial", "TextInputEditText", "MockView")
        for (classe in classes) {
            val sections = CatalogueAttributs.sections(classe, CatalogueAttributs.GenreParent.AUTRE)
            val tous = sections.flatMap { noms(it) }
            assertEquals(
                "La classe $classe a un attribut en double",
                tous.size,
                tous.distinct().size,
            )
        }
    }

    /** Le premier titre de section pour une classe (les assertions des classes propres). */
    private fun titrePropre(
        classe: String,
        indice: Int = 0,
    ): CatalogueAttributs.TitreSection =
        CatalogueAttributs.sections(classe, CatalogueAttributs.GenreParent.AUTRE)[indice].titre

    @Test
    fun `chaque widget reconnu recoit sa section propre`() {
        // Le miroir `sectionsFor` : Texte pour TextView, Saisie pour EditText,
        // Champ pour TextInputLayout, Image pour ImageView, etc.
        assertEquals(CatalogueAttributs.TitreSection.TEXTE, titrePropre("TextView"))
        assertEquals(CatalogueAttributs.TitreSection.SAISIE, titrePropre("EditText"))
        assertEquals(CatalogueAttributs.TitreSection.CHAMP, titrePropre("TextInputLayout"))
        assertEquals(CatalogueAttributs.TitreSection.BOUTON, titrePropre("Button", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.ETAT, titrePropre("CheckBox", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.IMAGE, titrePropre("ImageView"))
        assertEquals(CatalogueAttributs.TitreSection.CONTENEUR, titrePropre("LinearLayout"))
        assertEquals(CatalogueAttributs.TitreSection.PROGRESSION, titrePropre("ProgressBar"))
        assertEquals(CatalogueAttributs.TitreSection.LISTE, titrePropre("RecyclerView"))
    }

    @Test
    fun `les familles par heritage recoivent les sections de leur famille`() {
        // MaterialButton → Texte + Bouton, SwitchMaterial → Texte + État,
        // TextInputEditText → Saisie (miroir § 4.1 du prompt PE7e).
        val titres = { classe: String ->
            CatalogueAttributs.sections(classe, CatalogueAttributs.GenreParent.AUTRE).map { it.titre }
        }
        assertTrue(
            titres("MaterialButton").containsAll(
                listOf(CatalogueAttributs.TitreSection.TEXTE, CatalogueAttributs.TitreSection.BOUTON),
            ),
        )
        assertTrue(
            titres("SwitchMaterial").containsAll(
                listOf(CatalogueAttributs.TitreSection.TEXTE, CatalogueAttributs.TitreSection.ETAT),
            ),
        )
        assertEquals(CatalogueAttributs.TitreSection.SAISIE, titres("TextInputEditText").first())
    }

    @Test
    fun `la carte recoit l'arrondi et l'elevation, la ligne l'orientation`() {
        val carte =
            CatalogueAttributs
                .sections("MaterialCardView", CatalogueAttributs.GenreParent.AUTRE)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTENEUR }
        assertTrue(noms(carte).containsAll(listOf("app:cardCornerRadius", "app:cardElevation")))
        val ligne =
            CatalogueAttributs
                .sections("LinearLayout", CatalogueAttributs.GenreParent.AUTRE)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTENEUR }
        assertTrue(noms(ligne).containsAll(listOf("android:orientation", "android:gravity")))
        assertFalse(noms(ligne).contains("app:cardCornerRadius"))
    }

    // ── Classe inconnue et parents ───────────────────────────────────────

    @Test
    fun `une classe inconnue ne recoit que les sections generiques`() {
        val sections =
            CatalogueAttributs.sections("MockView", CatalogueAttributs.GenreParent.AUTRE).map { it.titre }
        assertEquals(
            listOf(
                CatalogueAttributs.TitreSection.DISPOSITION,
                CatalogueAttributs.TitreSection.AFFICHAGE,
            ),
            sections,
        )
    }

    @Test
    fun `le parent constraint racine ajoute les contraintes en lecture seule`() {
        val sections =
            CatalogueAttributs
                .sections("TextView", CatalogueAttributs.GenreParent.CONSTRAINT_RACINE)
                .map { it.titre }
        assertTrue(sections.contains(CatalogueAttributs.TitreSection.CONTRAINTES))
        val contraintes =
            CatalogueAttributs
                .sections("TextView", CatalogueAttributs.GenreParent.CONSTRAINT_RACINE)
                .first { it.titre == CatalogueAttributs.TitreSection.CONTRAINTES }
        assertEquals(
            CatalogueAttributs.ControleAttribut.Contraintes,
            contraintes.controles.single(),
        )
    }

    @Test
    fun `le parent lineaire ajoute poids et gravite de mise en page`() {
        val dansParent =
            CatalogueAttributs
                .sections("TextView", CatalogueAttributs.GenreParent.LINEAR)
                .first { it.titre == CatalogueAttributs.TitreSection.DANS_PARENT }
        assertEquals(
            listOf("android:layout_weight", "android:layout_gravity"),
            noms(dansParent),
        )
    }

    @Test
    fun `la racine n'a ni contraintes ni dans le parent`() {
        val titres =
            CatalogueAttributs
                .sections("LinearLayout", CatalogueAttributs.GenreParent.AUCUN)
                .map { it.titre }
        assertFalse(titres.contains(CatalogueAttributs.TitreSection.CONTRAINTES))
        assertFalse(titres.contains(CatalogueAttributs.TitreSection.DANS_PARENT))
        // La racine reste éditable pour Disposition et Affichage (piège § 5).
        assertTrue(
            titres.containsAll(
                listOf(CatalogueAttributs.TitreSection.DISPOSITION, CatalogueAttributs.TitreSection.AFFICHAGE),
            ),
        )
    }

    @Test
    fun `genreParent derive le genre des faits du rendu`() {
        assertEquals(
            CatalogueAttributs.GenreParent.AUCUN,
            CatalogueAttributs.genreParent(null, parentEstRacine = false),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.CONSTRAINT_RACINE,
            CatalogueAttributs.genreParent("ConstraintLayout", parentEstRacine = true),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.AUTRE,
            CatalogueAttributs.genreParent("ConstraintLayout", parentEstRacine = false),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.LINEAR,
            CatalogueAttributs.genreParent("LinearLayout", parentEstRacine = false),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.AUTRE,
            CatalogueAttributs.genreParent("FrameLayout", parentEstRacine = true),
        )
    }

    // ── Valeurs d'énumération (listes de référence figées — Android) ─────

    /** Les CLASSES testées (palette + les descendants hérités ajoutés). */
    private val classesCouvertes: List<String> =
        CataloguePalette.CATALOGUE.map { it.classe }.distinct() +
            listOf("MaterialButton", "SwitchMaterial", "TextInputEditText")

    /** Les contrôles CHOIX d'une classe, aplatis avec leur section (helper d'itération). */
    private fun choixDe(classe: String): List<CatalogueAttributs.ControleAttribut.Choix> =
        CatalogueAttributs
            .sections(classe, CatalogueAttributs.GenreParent.AUTRE)
            .flatMap { it.controles }
            .filterIsInstance<CatalogueAttributs.ControleAttribut.Choix>()

    @Test
    fun `les valeurs d'enumeration sont valides pour la plateforme`() {
        // Listes de référence : attrs.xml d'AOSP (textStyle, scaleType,
        // visibility, gravity) + Material Components (boxBackgroundMode,
        // endIconMode) + TextView (inputType de KeyType).
        val reference =
            mapOf(
                "android:textStyle" to listOf("normal", "bold", "italic"),
                "android:gravity" to listOf("start", "center", "end"),
                "android:layout_gravity" to listOf("start", "center", "end"),
                "android:inputType" to
                    listOf("text", "textEmailAddress", "textPassword", "number"),
                "app:boxBackgroundMode" to listOf("outline", "filled"),
                "app:endIconMode" to listOf("none", "password_toggle", "clear_text"),
                "android:scaleType" to
                    listOf("fitCenter", "centerCrop", "centerInside", "fitXY"),
                "android:orientation" to listOf("vertical", "horizontal"),
                "android:visibility" to listOf("visible", "invisible", "gone"),
            )
        val classeChoix =
            classesCouvertes
                .flatMap { classe -> choixDe(classe).map { classe to it } }
                .distinctBy { it.second.nom }
        for ((classe, choix) in classeChoix) {
            assertEquals(
                "Valeurs de ${choix.nom} ($classe)",
                reference[choix.nom],
                choix.valeurs,
            )
        }
    }

    @Test
    fun `le premier choix est le defaut documente`() {
        // Miroir § 4.2 : le premier choix RETIRE l'attribut — ce n'est
        // correct que pour les attributs dont le défaut plateforme coincide.
        val defauts =
            mapOf(
                "android:textStyle" to "normal",
                "android:visibility" to "visible",
                "android:scaleType" to "fitCenter",
                "android:inputType" to "text",
                "app:boxBackgroundMode" to "outline",
                "app:endIconMode" to "none",
                "android:orientation" to "vertical",
            )
        val classeChoix =
            classesCouvertes
                .flatMap { classe -> choixDe(classe).map { classe to it } }
                .filter { it.second.nom in defauts }
                .distinctBy { it.second.nom }
        for ((classe, choix) in classeChoix) {
            assertEquals(
                "Défaut de ${choix.nom} ($classe)",
                defauts[choix.nom],
                choix.valeurs.first(),
            )
        }
    }
}
