package jo.codeide.preview.xml

/**
 * CATALOGUE DES ATTRIBUTS PAR TYPE DE VUE (PE7e, maquette
 * `docs/mockups/apercu-xml.html` § « Attributs en sections selon le type
 * de vue » — miroir `sectionsFor`) — FONCTIONS PURES, SANS ANDROID : la
 * classe de la vue sélectionnée + le genre de son parent → les SECTIONS
 * repliables de l'onglet Attributs, chacune portant ses CONTRÔLES.
 *
 * Familles par HÉRITAGE (miroir § 4.1 du prompt PE7e) : `View` →
 * `TextView` → (`Button` → `MaterialButton`, `EditText` →
 * `TextInputEditText`, `CompoundButton` → `CheckBox`, `Switch`,
 * `SwitchMaterial`) ; `ImageView` → `ImageButton` ; `ViewGroup` →
 * `LinearLayout`, `MaterialCardView`. Une classe INCONNUE ou dégradée
 * (`MockView`, vue personnalisée) ne reçoit que les sections GÉNÉRIQUES
 * (Disposition, Affichage) — jamais un contrôle qui pourrait être faux.
 *
 * Les TEXTES (titres, étiquettes) restent des RESSOURCES du chrome :
 * le catalogue porte des clés sémantiques ([TitreSection]/[Etiquette]),
 * traduites fr/en dans `FeuilleApercuFragment` (règle 3 — aucune chaîne
 * en dur). Les NOMS d'attributs et les VALEURS d'énumération sont des
 * identifiants de plateforme : ils restent littéraux (code, pas UI).
 *
 * Décisions « valeur par défaut » (miroir § 4.2 — le PREMIER choix d'un
 * contrôle retire l'attribut, ce qui n'est correct que si la valeur par
 * défaut de la plateforme coïncide) :
 * - `android:textStyle` : absence = `normal` (décohérent : retrait OK) ;
 * - `android:gravity` : absence = `start` (horizontal, défaut TextView
 *   et LinearLayout) ;
 * - `android:visibility` : absence = `visible` ; `android:enabled` :
 *   absence = `true` ; `android:checked` : absence = `false` ;
 * - `android:scaleType` : absence = `fitCenter` ; `android:inputType` :
 *   absence = texte simple ; `app:boxBackgroundMode` : absence =
 *   `outline` ; `app:endIconMode` : absence = `none` ;
 * - `android:indeterminate` (ProgressBar) : absence = `true` — le
 *   « défaut » du tri-état EST l'absence, le rendu suit la plateforme ;
 * - `android:alpha` : absence = `1.0` — SANS retrait : le champ nombre
 *   n'écrit que des valeurs explicites (une saisie vide ne fait rien).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque section
 * du catalogue a SA fonction descriptrice ([sectionTexte],
 * [sectionSaisie]…) : le miroir direct des `if (k === …)` de la maquette,
 * lisible attribut par attribut.
 */
@Suppress("TooManyFunctions")
public object CatalogueAttributs {
    /** Le GENRE du parent de la vue éditée (détermine les sections parent). */
    public enum class GenreParent {
        /** La vue EST la racine : ni contraintes, ni « dans le parent ». */
        AUCUN,

        /** Le parent direct est le ConstraintLayout RACINE : contraintes éditables (PE8b). */
        CONSTRAINT_RACINE,

        /** Le parent direct est un ConstraintLayout NON racine : mêmes contraintes (PE8b). */
        CONSTRAINT,

        /** Le parent est un LinearLayout : layout_weight/layout_gravity. */
        LINEAR,

        /** Tout autre parent (FrameLayout…). */
        AUTRE,
    }

    /** Les TITRES de sections (clés sémantiques — traduites au chrome). */
    public enum class TitreSection {
        IDENTITE,
        TEXTE,
        SAISIE,
        CHAMP,
        BOUTON,
        ETAT,
        IMAGE,
        CONTENEUR,
        PROGRESSION,
        LISTE,
        CONTRAINTES,
        DANS_PARENT,
        DISPOSITION,
        AFFICHAGE,
    }

    /** Les ÉTIQUETTES de contrôles (clés sémantiques — traduites au chrome). */
    public enum class Etiquette {
        IDENTIFIANT,
        TEXTE,
        TAILLE_SP,
        STYLE,
        GRAVITE,
        COULEUR,
        HINT,
        TYPE_SAISIE,
        LIGNES_MAX,
        LIBELLE,
        FOND,
        ICONE_FIN,
        ARRONDI,
        ELEVATION,
        ICONE,
        COCHE,
        SOURCE,
        TYPE_ECHELLE,
        ORIENTATION,
        MAXIMUM,
        PROGRESSION,
        INDETERMINE,
        ITEM_LISTE,
        GESTIONNAIRE,
        POIDS,
        GRAVITE_PARENT,
        MARGES,
        PADDING,
        VISIBILITE,
        ALPHA,
        ACTIF,
        DESCRIPTION,
    }

    /** UN contrôle de l'éditeur (variante scellée — miroir § 4.1). */
    public sealed class ControleAttribut {
        /** L'identifiant de la vue (PE8a : renommage déclaration + références). */
        public data class Identifiant(
            val nom: String,
        ) : ControleAttribut()

        /** Un texte libre (`android:text`, `contentDescription`…). */
        public data class Texte(
            val nom: String,
            val etiquette: Etiquette,
        ) : ControleAttribut()

        /** Un nombre avec unité (`"dp"`, `"sp"` ou `""` — alpha sans unité). */
        public data class Nombre(
            val nom: String,
            val etiquette: Etiquette,
            val unite: String,
        ) : ControleAttribut()

        /** Un choix segmenté — le PREMIER élément est le défaut (retrait). */
        public data class Choix(
            val nom: String,
            val etiquette: Etiquette,
            val valeurs: List<String>,
        ) : ControleAttribut()

        /** Un tri-état (défaut / true / false — défaut = retrait). */
        public data class TriEtat(
            val nom: String,
            val etiquette: Etiquette,
        ) : ControleAttribut()

        /** Les quatre côtés d'une marge ou d'un padding (miroir `.mrow`). */
        public data class Cotes(
            val base: String,
            val etiquette: Etiquette,
        ) : ControleAttribut()

        /** Les dimensions wrap/match/fixe (PE7d — miroir `dimrow`). */
        public object Dimensions : ControleAttribut()

        /** Les contraintes du ConstraintLayout parent (ÉDITABLES — PE8b :
         * cible parent/frère + marge par ancre). */
        public object Contraintes : ControleAttribut()
    }

    /** UNE section repliable (titre + contrôles — miroir `.sec2`). */
    public data class SectionAttributs(
        val titre: TitreSection,
        val controles: List<ControleAttribut>,
    )

    /** Les valeurs des choix normal/… (miroir `TRI`/`GR` de la maquette). */
    private val STYLES: List<String> = listOf("normal", "bold", "italic")
    private val GRAVITES: List<String> = listOf("start", "center", "end")
    private val ORIENTATIONS: List<String> = listOf("vertical", "horizontal")
    private val TYPES_SAISIE: List<String> = listOf("text", "textEmailAddress", "textPassword", "number")
    private val FONDS: List<String> = listOf("outline", "filled")
    private val ICONES_FIN: List<String> = listOf("none", "password_toggle", "clear_text")
    private val TYPES_ECHELLE: List<String> = listOf("fitCenter", "centerCrop", "centerInside", "fitXY")
    private val VISIBILITES: List<String> = listOf("visible", "invisible", "gone")

    /**
     * Les SECTIONS de la vue [classe] avec le [genreParent] de son parent
     * (miroir `sectionsFor`) : sections PROPRES à la classe (ordre du
     * catalogue), puis parent (contraintes / dans le parent), puis les
     * sections GÉNÉRIQUES (Disposition, Affichage).
     */
    public fun sections(
        classe: String,
        genreParent: GenreParent,
    ): List<SectionAttributs> {
        val propres = sectionsPropres(classe)
        val duParent =
            when (genreParent) {
                GenreParent.CONSTRAINT_RACINE, GenreParent.CONSTRAINT -> {
                    listOf(
                        SectionAttributs(
                            TitreSection.CONTRAINTES,
                            listOf(ControleAttribut.Contraintes),
                        ),
                    )
                }

                GenreParent.LINEAR -> {
                    listOf(
                        SectionAttributs(
                            TitreSection.DANS_PARENT,
                            listOf(
                                ControleAttribut.Nombre("android:layout_weight", Etiquette.POIDS, ""),
                                ControleAttribut.Choix("android:layout_gravity", Etiquette.GRAVITE_PARENT, GRAVITES),
                            ),
                        ),
                    )
                }

                GenreParent.AUCUN, GenreParent.AUTRE -> {
                    emptyList()
                }
            }
        // L'IDENTITÉ ouvre TOUTE fiche (PE8a) : le renommage est
        // indépendant de la classe — la racine y a droit aussi (l'édit de
        // la racine reste borné à Identité/Disposition/Affichage).
        return listOf(sectionIdentite()) + propres + duParent + generiques()
    }

    /** La section IDENTITÉ (PE8a : le seul contrôle de renommage). */
    private fun sectionIdentite(): SectionAttributs =
        SectionAttributs(
            TitreSection.IDENTITE,
            listOf(ControleAttribut.Identifiant("android:id")),
        )

    /** Le GENRE de parent dérivé du nom de classe (purs — ViewModel fournit les faits). */
    public fun genreParent(
        classeParente: String?,
        parentEstRacine: Boolean,
    ): GenreParent =
        when {
            classeParente == null -> GenreParent.AUCUN
            classeParente == "ConstraintLayout" && parentEstRacine -> GenreParent.CONSTRAINT_RACINE
            classeParente == "ConstraintLayout" -> GenreParent.CONSTRAINT
            classeParente == "LinearLayout" -> GenreParent.LINEAR
            else -> GenreParent.AUTRE
        }

    /**
     * Les sections PROPRES à la classe (miroir les `if (k === …)` de
     * `sectionsFor`) : chaque famille apporte SA section, dans l'ordre du
     * catalogue (texte, saisie, champ, bouton, état, image, conteneur,
     * progression, liste).
     */
    private fun sectionsPropres(classe: String): List<SectionAttributs> =
        listOfNotNull(
            sectionTexte(classe),
            sectionSaisie(classe),
            sectionChamp(classe),
            sectionBouton(classe),
            sectionEtat(classe),
            sectionImage(classe),
            sectionConteneur(classe),
            sectionProgression(classe),
            sectionListe(classe),
        )

    /** La section TEXTE (TextView et descendants à texte éditable). */
    private fun sectionTexte(classe: String): SectionAttributs? =
        if (!familleTexte(classe)) {
            null
        } else {
            SectionAttributs(
                TitreSection.TEXTE,
                listOf(
                    ControleAttribut.Texte("android:text", Etiquette.TEXTE),
                    ControleAttribut.Nombre("android:textSize", Etiquette.TAILLE_SP, "sp"),
                    ControleAttribut.Choix("android:textStyle", Etiquette.STYLE, STYLES),
                    ControleAttribut.Choix("android:gravity", Etiquette.GRAVITE, GRAVITES),
                    ControleAttribut.Texte("android:textColor", Etiquette.COULEUR),
                ),
            )
        }

    /** La section SAISIE (EditText, TextInputEditText). */
    private fun sectionSaisie(classe: String): SectionAttributs? =
        if (!familleSaisie(classe)) {
            null
        } else {
            SectionAttributs(
                TitreSection.SAISIE,
                listOf(
                    ControleAttribut.Texte("android:hint", Etiquette.HINT),
                    ControleAttribut.Choix("android:inputType", Etiquette.TYPE_SAISIE, TYPES_SAISIE),
                    ControleAttribut.Nombre("android:maxLines", Etiquette.LIGNES_MAX, ""),
                ),
            )
        }

    /** La section CHAMP (TextInputLayout : libellé, fond, icône de fin). */
    private fun sectionChamp(classe: String): SectionAttributs? =
        if (classe != "TextInputLayout") {
            null
        } else {
            SectionAttributs(
                TitreSection.CHAMP,
                listOf(
                    ControleAttribut.Texte("android:hint", Etiquette.LIBELLE),
                    ControleAttribut.Choix("app:boxBackgroundMode", Etiquette.FOND, FONDS),
                    ControleAttribut.Choix("app:endIconMode", Etiquette.ICONE_FIN, ICONES_FIN),
                ),
            )
        }

    /** La section BOUTON (Button/MaterialButton : arrondi, icône). */
    private fun sectionBouton(classe: String): SectionAttributs? =
        if (!familleBouton(classe)) {
            null
        } else {
            SectionAttributs(
                TitreSection.BOUTON,
                listOf(
                    ControleAttribut.Nombre("app:cornerRadius", Etiquette.ARRONDI, "dp"),
                    ControleAttribut.Texte("app:icon", Etiquette.ICONE),
                ),
            )
        }

    /** La section ÉTAT (CompoundButton : checked tri-état). */
    private fun sectionEtat(classe: String): SectionAttributs? =
        if (!familleEtat(classe)) {
            null
        } else {
            SectionAttributs(
                TitreSection.ETAT,
                listOf(ControleAttribut.TriEtat("android:checked", Etiquette.COCHE)),
            )
        }

    /** La section IMAGE (ImageView/ImageButton : src, scaleType). */
    private fun sectionImage(classe: String): SectionAttributs? =
        if (!familleImage(classe)) {
            null
        } else {
            SectionAttributs(
                TitreSection.IMAGE,
                listOf(
                    ControleAttribut.Texte("android:src", Etiquette.SOURCE),
                    ControleAttribut.Choix("android:scaleType", Etiquette.TYPE_ECHELLE, TYPES_ECHELLE),
                ),
            )
        }

    /** La section CONTENEUR (LinearLayout : orientation/gravity ; carte : arrondi/élévation). */
    private fun sectionConteneur(classe: String): SectionAttributs? {
        val controles =
            when (classe) {
                "LinearLayout" -> {
                    listOf(
                        ControleAttribut.Choix(
                            "android:orientation",
                            Etiquette.ORIENTATION,
                            ORIENTATIONS,
                        ),
                        ControleAttribut.Choix("android:gravity", Etiquette.GRAVITE, GRAVITES),
                    )
                }

                "MaterialCardView" -> {
                    listOf(
                        ControleAttribut.Nombre("app:cardCornerRadius", Etiquette.ARRONDI, "dp"),
                        ControleAttribut.Nombre("app:cardElevation", Etiquette.ELEVATION, "dp"),
                    )
                }

                else -> {
                    return null
                }
            }
        return SectionAttributs(TitreSection.CONTENEUR, controles)
    }

    /** La section PROGRESSION (ProgressBar : + indeterminate ; SeekBar : max/progress). */
    private fun sectionProgression(classe: String): SectionAttributs? {
        if (classe != "ProgressBar" && classe != "SeekBar") return null
        val controles =
            mutableListOf<ControleAttribut>(
                ControleAttribut.Nombre("android:max", Etiquette.MAXIMUM, ""),
                ControleAttribut.Nombre("android:progress", Etiquette.PROGRESSION, ""),
            )
        if (classe == "ProgressBar") {
            controles += ControleAttribut.TriEtat("android:indeterminate", Etiquette.INDETERMINE)
        }
        return SectionAttributs(TitreSection.PROGRESSION, controles)
    }

    /** La section LISTE (RecyclerView : tools:listitem, layoutManager). */
    private fun sectionListe(classe: String): SectionAttributs? =
        if (classe != "RecyclerView") {
            null
        } else {
            SectionAttributs(
                TitreSection.LISTE,
                listOf(
                    ControleAttribut.Texte("tools:listitem", Etiquette.ITEM_LISTE),
                    ControleAttribut.Texte("app:layoutManager", Etiquette.GESTIONNAIRE),
                ),
            )
        }

    /** Les sections GÉNÉRIQUES (Disposition + Affichage — toutes vues, racine comprise). */
    private fun generiques(): List<SectionAttributs> =
        listOf(
            SectionAttributs(
                TitreSection.DISPOSITION,
                listOf(
                    ControleAttribut.Dimensions,
                    ControleAttribut.Cotes("android:layout_margin", Etiquette.MARGES),
                    ControleAttribut.Cotes("android:padding", Etiquette.PADDING),
                ),
            ),
            SectionAttributs(
                TitreSection.AFFICHAGE,
                listOf(
                    ControleAttribut.Choix("android:visibility", Etiquette.VISIBILITE, VISIBILITES),
                    ControleAttribut.Nombre("android:alpha", Etiquette.ALPHA, ""),
                    ControleAttribut.TriEtat("android:enabled", Etiquette.ACTIF),
                    ControleAttribut.Texte("android:contentDescription", Etiquette.DESCRIPTION),
                ),
            ),
        )

    // ── Familles par héritage (miroir § 4.1) ─────────────────────────────

    /** TextView et ses descendants à TEXTE éditable (Button/CheckBox/Switch). */
    private fun familleTexte(classe: String): Boolean =
        classe == "TextView" ||
            familleBouton(classe) ||
            familleEtat(classe)

    /** EditText et TextInputEditText. */
    private fun familleSaisie(classe: String): Boolean = classe == "EditText" || classe == "TextInputEditText"

    /** Button et MaterialButton. */
    private fun familleBouton(classe: String): Boolean = classe == "Button" || classe == "MaterialButton"

    /** CompoundButton et ses descendants (CheckBox, Switch, SwitchMaterial). */
    private fun familleEtat(classe: String): Boolean =
        classe == "CheckBox" || classe == "Switch" || classe == "SwitchMaterial"

    /** ImageView et ImageButton. */
    private fun familleImage(classe: String): Boolean = classe == "ImageView" || classe == "ImageButton"
}
