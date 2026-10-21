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
        SCROLLBARS,
        SHOW_DIVIDERS,
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
            val courant: Boolean = true,
        ) : ControleAttribut()

        /**
         * Un texte libre (`android:text`, `contentDescription`…) —
         * [extrayable] : le littéral peut être EXTRAIT vers `@string/…`
         * (PE8c — réservé aux libellés, jamais aux références de
         * drawable/enum).
         */
        public data class Texte(
            val nom: String,
            val etiquette: Etiquette,
            val extrayable: Boolean = false,
            val courant: Boolean = true,
        ) : ControleAttribut()

        /** Un nombre avec unité (`"dp"`, `"sp"` ou `""` — alpha sans unité). */
        public data class Nombre(
            val nom: String,
            val etiquette: Etiquette,
            val unite: String,
            val courant: Boolean = true,
        ) : ControleAttribut()

        /** Un choix segmenté — le PREMIER élément est le défaut (retrait). */
        public data class Choix(
            val nom: String,
            val etiquette: Etiquette,
            val valeurs: List<String>,
            val courant: Boolean = true,
        ) : ControleAttribut()

        /** Un tri-état (défaut / true / false — défaut = retrait). */
        public data class TriEtat(
            val nom: String,
            val etiquette: Etiquette,
            val courant: Boolean = true,
        ) : ControleAttribut()

        /**
         * Une liste déroulante (PE11c — miroir `list` de la maquette, § 9 :
         * tout enum de PLUS de 4 valeurs ; 4 ou moins reste un [Choix]) :
         * l'UI ajoute une première entrée « par défaut » qui RETIRE
         * l'attribut, et une valeur courante hors catalogue est AJOUTÉE à
         * la fin (miroir `if (cur && !opts.includes(cur)) opts.push(cur)`).
         */
        public data class Liste(
            val nom: String,
            val etiquette: Etiquette,
            val valeurs: List<String>,
            val courant: Boolean = true,
        ) : ControleAttribut()

        /**
         * Un contrôle à DRAPEAUX (PE11d — miroir `flags` de la maquette, § 9 :
         * la valeur est un MASQUE, des drapeaux joints par « | », plusieurs
         * choix simultanés). Les drapeaux cochés suivent les parties de la
         * valeur courante ; un drapeau posé HORS catalogue est ajouté à la
         * fin (miroir `opts.concat(parts…)`), et l'écriture ordonne les
         * parties selon le CATALOGUE (miroir `opts.filter(o =>
         * n.includes(o)).join('|')`) ; tout décoché retire l'attribut.
         */
        public data class Drapeaux(
            val nom: String,
            val etiquette: Etiquette,
            val valeurs: List<String>,
            val courant: Boolean = true,
        ) : ControleAttribut() {
            /**
             * Les PARTIES posées de la valeur (miroir
             * `cur.split('|').filter(Boolean)`).
             */
            public fun parties(valeur: String?): List<String> =
                valeur?.split('|')?.filter { it.isNotBlank() } ?: emptyList()

            /**
             * Les options AFFICHÉES : le catalogue, puis les parties hors
             * catalogue de la valeur courante (miroir `opts = c.o.concat(…)`).
             */
            public fun options(valeur: String?): List<String> = valeurs + parties(valeur).filter { it !in valeurs }

            /**
             * La valeur APRÈS bascule d'un drapeau : les parties retenues,
             * ordonnées selon les options (miroir `join('|')` de la maquette).
             */
            public fun basculer(
                valeur: String?,
                drapeau: String,
            ): String {
                val posees = parties(valeur).toMutableSet()
                if (!posees.remove(drapeau)) posees.add(drapeau)
                return options(valeur).filter { it in posees }.joinToString("|")
            }
        }

        /**
         * Le TYPE DE SAISIE composite (PE11d — miroir `input` de la
         * maquette, § 9 : `controle_liste` + `controle_drapeaux`) : une
         * BASE en liste déroulante et des MODIFICATEURS en drapeaux,
         * désactivés quand la base n'est pas textuelle (miroir
         * `b.disabled = !base.startsWith('text')`). La valeur écrite est
         * `base|modificateur|…` ; « text » SEUL (aucun modificateur)
         * RETIRE l'attribut (défaut plateforme).
         */
        public data class TypeSaisie(
            val nom: String,
            val bases: List<String>,
            val modificateurs: List<String>,
            val courant: Boolean = true,
        ) : ControleAttribut() {
            /**
             * La BASE courante : première partie de la valeur dans le
             * catalogue, sinon « text » (miroir
             * `parts.find(p => IN_BASE.includes(p)) || 'text'`).
             */
            public fun base(valeur: String?): String = valeur?.split('|')?.firstOrNull { it in bases } ?: "text"

            /**
             * Les MODIFICATEURS posés (les parties hors base et hors
             * modificateurs sont ignorées — miroir
             * `parts.filter(p => IN_MOD.includes(p))`).
             */
            public fun modificateursPoses(valeur: String?): List<String> =
                valeur?.split('|')?.filter { it in modificateurs } ?: emptyList()

            /**
             * La valeur composée (miroir `write` : `text` sans modificateur
             * retire l'attribut).
             */
            public fun composer(
                base: String,
                modificateurs: List<String>,
            ): String =
                if (base == "text" && modificateurs.isEmpty()) {
                    ""
                } else {
                    (listOf(base) + modificateurs).joinToString("|")
                }
        }

        /**
         * Le contrôle COULEUR (PE11e — miroir `col` de la maquette,
         * § 9 `controle_couleur`) : pastille à TROIS couches (damier,
         * teinte, cadre), champ hex et bouton « Palette » qui montre
         * les `@color` du projet. La valeur est VALIDÉE avant
         * écriture (miroir `vCol` — [ValidationAttribut]) : une
         * ERREUR n'écrit RIEN, un AVERTISSEMENT écrit quand même,
         * le vide RETIRE l'attribut (miroir `setEx` à valeur vide).
         */
        public data class Couleur(
            val nom: String,
            val etiquette: Etiquette,
            val courant: Boolean = true,
        ) : ControleAttribut()

        /**
         * Le contrôle RESSOURCE (PE11f — miroir `res` de la maquette,
         * § 9 `controle_ressource`) : champ à SUGGESTIONS `@type/nom`
         * (les types viennent de l'attribut), un NOM NU saisi est
         * COMPLÉTÉ `@premierType/nom` (miroir `x = '@' + c.ty[0] +
         * '/' + x`), une ressource absente du projet est un
         * AVERTISSEMENT qui s'écrit quand même, un TYPE refusé ou une
         * forme cassée est une ERREUR qui n'écrit RIEN, le vide
         * RETIRE l'attribut.
         */
        public data class Ressource(
            val nom: String,
            val etiquette: Etiquette,
            val types: List<String>,
            val courant: Boolean = true,
        ) : ControleAttribut() {
            /**
             * La valeur COMPLÉTÉE : un NOM NU (lettres, chiffres, « _ »
             * et « . ») reçoit le PREMIER type (miroir
             * `x = '@' + c.ty[0] + '/' + x` — `ic_logo` devient
             * `@drawable/ic_logo`) ; tout le reste passe TEL QUEL (une
             * référence, un `?attr/…` : la validation tranchera).
             */
            public fun completer(valeur: String): String =
                if (nomNu.matches(valeur) && types.isNotEmpty()) "@${types.first()}/$valeur" else valeur

            /** Un nom de ressource nu (miroir `/^[\w.]+$/` du `inp`). */
            private val nomNu: Regex = Regex("""^[\w.]+$""")
        }

        /** Les quatre côtés d'une marge ou d'un padding (miroir `.mrow`). */
        public data class Cotes(
            val base: String,
            val etiquette: Etiquette,
            val courant: Boolean = true,
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

    /**
     * Les 8 drapeaux du masque gravity (GRAV de la maquette — attrs.xml
     * d'AOSP) : plusieurs drapeaux simultanés, « start » seul = défaut.
     */
    private val GRAVITES: List<String> =
        listOf(
            "start",
            "end",
            "top",
            "bottom",
            "center_horizontal",
            "center_vertical",
            "center",
            "fill",
        )
    private val ORIENTATIONS: List<String> = listOf("vertical", "horizontal")

    /**
     * Les drapeaux du masque textStyle (miroir `['bold', 'italic']` de la
     * maquette) : « normal » est l'état VIDE (tout décoché retire
     * l'attribut), pas un drapeau.
     */
    private val STYLES_DRAPEAUX: List<String> = listOf("bold", "italic")

    /**
     * Les drapeaux de showDividers (miroir de la maquette — LinearLayout).
     */
    private val SEPARATEURS: List<String> = listOf("beginning", "middle", "end")

    /** Les drapeaux de scrollbars (miroir de la maquette — View). */
    private val BARRES_DEFILEMENT: List<String> = listOf("vertical", "horizontal")

    /**
     * Les BASES du type de saisie (IN_BASE de la maquette — KeyType de
     * AOSP) : « text » est le défaut (retrait de l'attribut).
     */
    private val BASES_SAISIE: List<String> =
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
        )

    /**
     * Les MODIFICATEURS du type de saisie (IN_MOD de la maquette) : posés
     * sur une base textuelle, ils se combinent en `base|modificateur|…`.
     */
    private val MODIFICATEURS_SAISIE: List<String> =
        listOf(
            "textCapSentences",
            "textCapWords",
            "textCapCharacters",
            "textAutoCorrect",
            "textNoSuggestions",
        )

    private val FONDS: List<String> = listOf("outline", "filled")
    private val ICONES_FIN: List<String> = listOf("none", "password_toggle", "clear_text")

    // Les types acceptés par un contrôle RESSOURCE d'icône/image — miroir
    // `C.res('…', '…', ['drawable'])` de la maquette (les attr `string`,
    // `layout`, `style` suivent avec leurs usagers de la 2e vague).
    private val TYPES_DESSINABLE: List<String> = listOf("drawable")

    // scaleType : les 8 valeurs plateforme (SCALE de la maquette) — le
    // catalogue PE7e n'en portait que 4 (rétrécies pour tenir en segments) ;
    // plus de 4 valeurs ⇒ liste déroulante (règle § 9 / maquette « Liste »).
    private val TYPES_ECHELLE: List<String> =
        listOf(
            "matrix",
            "fitXY",
            "fitStart",
            "fitCenter",
            "fitEnd",
            "center",
            "centerCrop",
            "centerInside",
        )
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
                                ControleAttribut.Drapeaux("android:layout_gravity", Etiquette.GRAVITE_PARENT, GRAVITES),
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

    /**
     * Le GENRE du parent (alias interne PE12a, spec § 2.3 — GenreParent
     * disparaît en PE12b) depuis la FAMILLE du parent rendu :
     * CONSTRAINT garde sa distinction racine/non-racine (la seule qui
     * change les sections), LINEAR est inchangé, et toute famille sans
     * section aujourd'hui (FRAME, RELATIVE, COORDINATOR, APPBAR, AUTRE)
     * reste AUTRE — leurs sections arrivent en PE12c.
     */
    public fun genreParent(
        famille: FamilleParent?,
        parentEstRacine: Boolean,
    ): GenreParent =
        when (famille) {
            null, FamilleParent.AUCUN -> {
                GenreParent.AUCUN
            }

            FamilleParent.CONSTRAINT -> {
                if (parentEstRacine) {
                    GenreParent.CONSTRAINT_RACINE
                } else {
                    GenreParent.CONSTRAINT
                }
            }

            FamilleParent.LINEAR -> {
                GenreParent.LINEAR
            }

            else -> {
                GenreParent.AUTRE
            }
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
                    ControleAttribut.Texte("android:text", Etiquette.TEXTE, extrayable = true),
                    ControleAttribut.Nombre("android:textSize", Etiquette.TAILLE_SP, "sp"),
                    ControleAttribut.Drapeaux("android:textStyle", Etiquette.STYLE, STYLES_DRAPEAUX),
                    ControleAttribut.Drapeaux("android:gravity", Etiquette.GRAVITE, GRAVITES),
                    ControleAttribut.Couleur("android:textColor", Etiquette.COULEUR),
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
                    ControleAttribut.Texte("android:hint", Etiquette.HINT, extrayable = true),
                    ControleAttribut.TypeSaisie("android:inputType", BASES_SAISIE, MODIFICATEURS_SAISIE),
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
                    ControleAttribut.Ressource("app:icon", Etiquette.ICONE, TYPES_DESSINABLE),
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
                    ControleAttribut.Ressource("android:src", Etiquette.SOURCE, TYPES_DESSINABLE),
                    ControleAttribut.Liste("android:scaleType", Etiquette.TYPE_ECHELLE, TYPES_ECHELLE),
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
                        ControleAttribut.Drapeaux("android:gravity", Etiquette.GRAVITE, GRAVITES),
                        ControleAttribut.Drapeaux("android:showDividers", Etiquette.SHOW_DIVIDERS, SEPARATEURS),
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
                    ControleAttribut.Drapeaux("android:scrollbars", Etiquette.SCROLLBARS, BARRES_DEFILEMENT),
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
                    ControleAttribut.Texte("android:contentDescription", Etiquette.DESCRIPTION, extrayable = true),
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
