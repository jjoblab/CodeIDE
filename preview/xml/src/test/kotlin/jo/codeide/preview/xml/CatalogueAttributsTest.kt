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
 * d'attribut, une classe inconnue ne reçoit que l'IDENTITÉ (PE8a) et les
 * sections GÉNÉRIQUES (miroir piège § 5 du prompt PE7e), et les valeurs
 * d'énumération correspondent à la LISTE DE RÉFÉRENCE figée de la plateforme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class CatalogueAttributsTest {
    /** Les noms d'attributs d'une section (Dimensions/Contraintes : contrôle sans nom). */
    private fun noms(section: CatalogueAttributs.SectionAttributs): List<String> =
        section.controles.mapNotNull { controle ->
            when (controle) {
                is CatalogueAttributs.ControleAttribut.Identifiant -> controle.nom

                is CatalogueAttributs.ControleAttribut.Texte -> controle.nom

                is CatalogueAttributs.ControleAttribut.Nombre -> controle.nom

                is CatalogueAttributs.ControleAttribut.Choix -> controle.nom

                is CatalogueAttributs.ControleAttribut.Liste -> controle.nom

                is CatalogueAttributs.ControleAttribut.Drapeaux -> controle.nom

                is CatalogueAttributs.ControleAttribut.TypeSaisie -> controle.nom

                is CatalogueAttributs.ControleAttribut.TriEtat -> controle.nom

                is CatalogueAttributs.ControleAttribut.Couleur -> controle.nom

                is CatalogueAttributs.ControleAttribut.Ressource -> controle.nom

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
        assertEquals(CatalogueAttributs.TitreSection.IDENTITE, titrePropre("TextView"))
        assertEquals(CatalogueAttributs.TitreSection.TEXTE, titrePropre("TextView", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.SAISIE, titrePropre("EditText", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.CHAMP, titrePropre("TextInputLayout", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.BOUTON, titrePropre("Button", indice = 2))
        assertEquals(CatalogueAttributs.TitreSection.ETAT, titrePropre("CheckBox", indice = 2))
        assertEquals(CatalogueAttributs.TitreSection.IMAGE, titrePropre("ImageView", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.CONTENEUR, titrePropre("LinearLayout", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.PROGRESSION, titrePropre("ProgressBar", indice = 1))
        assertEquals(CatalogueAttributs.TitreSection.LISTE, titrePropre("RecyclerView", indice = 1))
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
        assertEquals(CatalogueAttributs.TitreSection.SAISIE, titres("TextInputEditText").get(1))
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
    fun `une classe inconnue ne recoit que l'identite et les sections generiques`() {
        val sections =
            CatalogueAttributs.sections("MockView", CatalogueAttributs.GenreParent.AUTRE).map { it.titre }
        assertEquals(
            listOf(
                CatalogueAttributs.TitreSection.IDENTITE,
                CatalogueAttributs.TitreSection.DISPOSITION,
                CatalogueAttributs.TitreSection.AFFICHAGE,
            ),
            sections,
        )
    }

    @Test
    fun `le parent constraint ajoute les contraintes editables`() {
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
        // PE8b : un parent ConstraintLayout NON racine donne la MÊME section.
        assertTrue(
            CatalogueAttributs
                .sections("TextView", CatalogueAttributs.GenreParent.CONSTRAINT)
                .map { it.titre }
                .contains(CatalogueAttributs.TitreSection.CONTRAINTES),
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
    fun `genreParent alimente l'alias par la famille du rendu`() {
        // PE12a (spec § 2.3) : null (aucun parent rendu) → AUCUN ;
        // CONSTRAINT garde sa distinction racine/non-racine ; LINEAR
        // inchangé ; toute famille sans section aujourd'hui reste AUTRE.
        assertEquals(
            CatalogueAttributs.GenreParent.AUCUN,
            CatalogueAttributs.genreParent(null, parentEstRacine = false),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.CONSTRAINT_RACINE,
            CatalogueAttributs.genreParent(FamilleParent.CONSTRAINT, parentEstRacine = true),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.CONSTRAINT,
            CatalogueAttributs.genreParent(FamilleParent.CONSTRAINT, parentEstRacine = false),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.LINEAR,
            CatalogueAttributs.genreParent(FamilleParent.LINEAR, parentEstRacine = false),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.AUTRE,
            CatalogueAttributs.genreParent(FamilleParent.FRAME, parentEstRacine = true),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.AUTRE,
            CatalogueAttributs.genreParent(FamilleParent.RELATIVE, parentEstRacine = false),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.AUTRE,
            CatalogueAttributs.genreParent(FamilleParent.COORDINATOR, parentEstRacine = false),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.AUTRE,
            CatalogueAttributs.genreParent(FamilleParent.APPBAR, parentEstRacine = false),
        )
        assertEquals(
            CatalogueAttributs.GenreParent.AUTRE,
            CatalogueAttributs.genreParent(FamilleParent.AUTRE, parentEstRacine = false),
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

    /** Les contrôles LISTE d'une classe (helper d'itération — PE11c). */
    private fun listesDe(classe: String): List<CatalogueAttributs.ControleAttribut.Liste> =
        CatalogueAttributs
            .sections(classe, CatalogueAttributs.GenreParent.AUTRE)
            .flatMap { it.controles }
            .filterIsInstance<CatalogueAttributs.ControleAttribut.Liste>()

    @Test
    fun `les valeurs d'enumeration sont valides pour la plateforme`() {
        // Listes de référence : attrs.xml d'AOSP (visibility) + Material
        // Components (boxBackgroundMode, endIconMode) + LinearLayout
        // (orientation). Les masques | (textStyle, gravity, layout_gravity)
        // et le type de saisie sont des DRAPEAUX/TypeSaisie — testés plus bas.
        val reference =
            mapOf(
                "app:boxBackgroundMode" to listOf("outline", "filled"),
                "app:endIconMode" to listOf("none", "password_toggle", "clear_text"),
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
                "android:visibility" to "visible",
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

    @Test
    fun `les enums de plus de 4 valeurs sont des listes deroulantes`() {
        // Miroir § 9 / maquette « Liste » : un enum de plus de 4 valeurs
        // porte le contrôle LISTE (liste déroulante) ; scaleType est le
        // premier usager (8 valeurs plateforme, SCALE de la maquette).
        val reference =
            mapOf(
                "android:scaleType" to
                    listOf(
                        "matrix",
                        "fitXY",
                        "fitStart",
                        "fitCenter",
                        "fitEnd",
                        "center",
                        "centerCrop",
                        "centerInside",
                    ),
            )
        val classeListes =
            classesCouvertes
                .flatMap { classe -> listesDe(classe).map { classe to it } }
                .distinctBy { it.second.nom }
        for ((classe, liste) in classeListes) {
            assertEquals(
                "Valeurs de ${liste.nom} ($classe)",
                reference[liste.nom],
                liste.valeurs,
            )
        }
    }

    // ── PE11d : les masques multi-valeurs (miroir `flags` de la maquette) ─

    /** Les contrôles DRAPEAUX d'une classe (helper d'itération — PE11d). */
    private fun drapeauxDe(classe: String): List<CatalogueAttributs.ControleAttribut.Drapeaux> =
        CatalogueAttributs
            .sections(classe, CatalogueAttributs.GenreParent.AUTRE)
            .flatMap { it.controles }
            .filterIsInstance<CatalogueAttributs.ControleAttribut.Drapeaux>()

    @Test
    fun `les masques multi-valeurs sont des drapeaux`() {
        // Miroir § 9 / maquette `flags` : tout attribut dont la valeur est
        // un masque « a|b|c » porte le contrôle DRAPEAUX. Références :
        // GRAV de la maquette (8 valeurs AOSP), ['bold','italic'] pour
        // textStyle (« normal » est l'état VIDE), showDividers et
        // scrollbars de la maquette.
        val reference =
            mapOf(
                "android:textStyle" to listOf("bold", "italic"),
                "android:gravity" to
                    listOf(
                        "start",
                        "end",
                        "top",
                        "bottom",
                        "center_horizontal",
                        "center_vertical",
                        "center",
                        "fill",
                    ),
                "android:showDividers" to listOf("beginning", "middle", "end"),
                "android:scrollbars" to listOf("vertical", "horizontal"),
            )
        val classeDrapeaux =
            classesCouvertes
                .flatMap { classe -> drapeauxDe(classe).map { classe to it } }
                .distinctBy { it.second.nom }
        for ((classe, drapeaux) in classeDrapeaux) {
            assertEquals(
                "Valeurs de ${drapeaux.nom} ($classe)",
                reference[drapeaux.nom],
                drapeaux.valeurs,
            )
        }
    }

    @Test
    fun `layout_gravity est un drapeaux dans le parent lineaire`() {
        // La section DANS_PARENT n'existe que pour un parent LinearLayout :
        // layout_gravity y porte le MASQUE complet de la maquette (GRAV).
        val sections =
            CatalogueAttributs.sections("TextView", CatalogueAttributs.GenreParent.LINEAR)
        val drapeaux =
            sections
                .flatMap { it.controles }
                .filterIsInstance<CatalogueAttributs.ControleAttribut.Drapeaux>()
                .first { it.nom == "android:layout_gravity" }
        assertEquals(
            "layout_gravity porte les 8 drapeaux de la maquette",
            listOf(
                "start",
                "end",
                "top",
                "bottom",
                "center_horizontal",
                "center_vertical",
                "center",
                "fill",
            ),
            drapeaux.valeurs,
        )
    }

    @Test
    fun `le type de saisie est le composite liste plus drapeaux`() {
        // Miroir § 9 / maquette `input` : inputType porte le composite
        // TypeSaisie — IN_BASE (12 bases, « text » défaut) et IN_MOD (5
        // modificateurs) de la maquette ; c'est l'usager de la SAISIE.
        val composite =
            classesCouvertes
                .flatMap { classe ->
                    CatalogueAttributs
                        .sections(classe, CatalogueAttributs.GenreParent.AUTRE)
                        .flatMap { it.controles }
                        .filterIsInstance<CatalogueAttributs.ControleAttribut.TypeSaisie>()
                }.distinctBy { it.nom }
                .single()
        assertEquals("L'usager du composite est inputType", "android:inputType", composite.nom)
        assertEquals(
            "Les bases (IN_BASE de la maquette)",
            listOf(
                "text",
                "textEmailAddress",
                "textPassword",
                "textUri",
                "textPersonName",
                "textMultiLine",
                "number",
                "numberDecimal",
                "numberSigned",
                "numberPassword",
                "phone",
                "datetime",
            ),
            composite.bases,
        )
        assertEquals(
            "Les modificateurs (IN_MOD de la maquette)",
            listOf(
                "textCapSentences",
                "textCapWords",
                "textCapCharacters",
                "textAutoCorrect",
                "textNoSuggestions",
            ),
            composite.modificateurs,
        )
    }

    @Test
    fun `la bascule d un drapeau suit la maquette`() {
        // Miroir `opts.filter(o => n.includes(o)).join('|')` : l'ordre du
        // CATALOGUE prime sur l'ordre des clics, une partie hors catalogue
        // reste visible ET conservée, tout décoché retire l'attribut.
        val style =
            CatalogueAttributs.ControleAttribut.Drapeaux(
                "android:textStyle",
                CatalogueAttributs.Etiquette.STYLE,
                listOf("bold", "italic"),
            )
        assertEquals("Poser bold", "bold", style.basculer(null, "bold"))
        assertEquals("Poser italic en second", "bold|italic", style.basculer("bold", "italic"))
        assertEquals("Retirer bold", "italic", style.basculer("bold|italic", "bold"))
        assertEquals("Retirer italic (tout décoché = retrait)", "", style.basculer("italic", "italic"))

        // L'ordre du catalogue prime : poser italic puis bold, l'écriture
        // ordonne bold PUIS italic (jamais l'ordre des clics).
        assertEquals("L'ordre du catalogue prime", "bold|italic", style.basculer("italic", "bold"))

        // Une valeur hors catalogue : visible, cochée, conservée à l'écriture.
        assertEquals("Partie hors catalogue conservée", "bold|italic|custom", style.basculer("bold|custom", "italic"))
        assertEquals(
            "Options : catalogue puis hors catalogue",
            listOf("bold", "italic", "custom"),
            style.options("bold|custom"),
        )
        assertEquals("Parties d'une valeur vide", emptyList<String>(), style.parties(null))
    }

    @Test
    fun `la composition du type de saisie suit la maquette`() {
        // Miroir `write` : « text » sans modificateur RETIRE l'attribut ;
        // la base courante est la première partie connue, sinon « text » ;
        // les parties inconnues sont ignorées.
        val composite =
            CatalogueAttributs.ControleAttribut.TypeSaisie(
                "android:inputType",
                listOf("text", "number", "phone"),
                listOf("textCapSentences", "textAutoCorrect"),
            )
        assertEquals("text seul retire l'attribut", "", composite.composer("text", emptyList()))
        assertEquals(
            "Un modificateur compose",
            "text|textCapSentences",
            composite.composer("text", listOf("textCapSentences")),
        )
        assertEquals(
            "Une base non textuelle reste",
            "number",
            composite.composer("number", emptyList()),
        )
        assertEquals(
            "Base et modificateurs composés",
            "text|textCapSentences|textAutoCorrect",
            composite.composer("text", listOf("textCapSentences", "textAutoCorrect")),
        )
        assertEquals("La base courante par défaut", "text", composite.base(null))
        assertEquals("La base courante extraite", "number", composite.base("number|xyz"))
        assertEquals(
            "La base courante avec modificateurs",
            "text",
            composite.base("text|textCapSentences"),
        )
        assertEquals("Base inconnue → défaut", "text", composite.base("xyz"))
        assertEquals(
            "Les modificateurs posés",
            listOf("textAutoCorrect"),
            composite.modificateursPoses("text|textAutoCorrect|xyz"),
        )
    }

    @Test
    fun `tout attribut de reference porte le controle Ressource - usagers icon et src`() {
        // Règle § 9 (PE11f) : une RÉFÉRENCE `@type/nom` n'est jamais un
        // champ texte libre — le contrôle `res` de la maquette
        // (suggestions + complétion du nom nu). Usagers de la 1re vague :
        // app:icon (Bouton) et android:src (Image), types ['drawable'].
        val bouton =
            CatalogueAttributs
                .sections("Button", CatalogueAttributs.GenreParent.CONSTRAINT)
                .first { it.titre == CatalogueAttributs.TitreSection.BOUTON }
        val icon =
            bouton.controles.first { it is CatalogueAttributs.ControleAttribut.Ressource }
        assertEquals(
            "app:icon est le contrôle ressource drawable",
            CatalogueAttributs.ControleAttribut.Ressource(
                "app:icon",
                CatalogueAttributs.Etiquette.ICONE,
                listOf("drawable"),
            ),
            icon,
        )
        val image =
            CatalogueAttributs
                .sections("ImageView", CatalogueAttributs.GenreParent.AUCUN)
                .first { it.titre == CatalogueAttributs.TitreSection.IMAGE }
        val src =
            image.controles.first { it is CatalogueAttributs.ControleAttribut.Ressource }
        assertEquals(
            "android:src est le contrôle ressource drawable",
            CatalogueAttributs.ControleAttribut.Ressource(
                "android:src",
                CatalogueAttributs.Etiquette.SOURCE,
                listOf("drawable"),
            ),
            src,
        )
    }

    @Test
    fun `tout controle de la premiere vague est courant - le drapeau existe`() {
        // PE10a/PE11f : le drapeau `courant` existe sur CHAQUE contrôle ;
        // la 1re vague est TOUJOURS courante (les attributs non courants
        // arrivent avec l'annexe § 7 de la 2e vague).
        for (classe in listOf("TextView", "Button", "EditText", "ImageView", "LinearLayout")) {
            for (section in CatalogueAttributs.sections(classe, CatalogueAttributs.GenreParent.CONSTRAINT)) {
                for (controle in section.controles) {
                    assertTrue(
                        "contrôle de $classe/${section.titre}",
                        controle.estCourant(),
                    )
                }
            }
        }
    }

    /** Le drapeau Courants d'un contrôle (objet = toujours courant). */
    private fun CatalogueAttributs.ControleAttribut.estCourant(): Boolean =
        when (this) {
            is CatalogueAttributs.ControleAttribut.Identifiant -> courant

            is CatalogueAttributs.ControleAttribut.Texte -> courant

            is CatalogueAttributs.ControleAttribut.Nombre -> courant

            is CatalogueAttributs.ControleAttribut.Choix -> courant

            is CatalogueAttributs.ControleAttribut.Liste -> courant

            is CatalogueAttributs.ControleAttribut.Drapeaux -> courant

            is CatalogueAttributs.ControleAttribut.TypeSaisie -> courant

            is CatalogueAttributs.ControleAttribut.TriEtat -> courant

            is CatalogueAttributs.ControleAttribut.Couleur -> courant

            is CatalogueAttributs.ControleAttribut.Ressource -> courant

            is CatalogueAttributs.ControleAttribut.Cotes -> courant

            CatalogueAttributs.ControleAttribut.Dimensions,
            CatalogueAttributs.ControleAttribut.Contraintes,
            -> true
        }

    @Test
    fun `tout attribut de couleur porte le controle Couleur - usager textColor`() {
        // Règle § 9 (PE11e) : un attribut de COULEUR n'est jamais un
        // champ texte libre — le contrôle `col` de la maquette (pastille
        // + hex + Palette). Premier usager du catalogue : android:textColor.
        val texte =
            CatalogueAttributs
                .sections("TextView", CatalogueAttributs.GenreParent.AUCUN)
                .first { it.titre == CatalogueAttributs.TitreSection.TEXTE }
        val controle =
            texte.controles.first { it is CatalogueAttributs.ControleAttribut.Couleur }
        assertEquals(
            "textColor est le contrôle couleur",
            CatalogueAttributs.ControleAttribut.Couleur(
                "android:textColor",
                CatalogueAttributs.Etiquette.COULEUR,
            ),
            controle,
        )
        assertFalse(
            "plus AUCUN champ Texte ne porte l'étiquette COULEUR",
            CatalogueAttributs
                .sections("TextView", CatalogueAttributs.GenreParent.AUCUN)
                .any { section ->
                    section.controles.any {
                        it is CatalogueAttributs.ControleAttribut.Texte &&
                            it.etiquette == CatalogueAttributs.Etiquette.COULEUR
                    }
                },
        )
    }
}
