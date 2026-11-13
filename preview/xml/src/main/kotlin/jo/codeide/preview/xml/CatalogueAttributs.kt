package jo.codeide.preview.xml

/**
 * CATALOGUE DES ATTRIBUTS PAR TYPE DE VUE (PE7e puis PE11 § 7, maquette
 * `docs/mockups/apercu-xml.html` § « Attributs en sections selon le type
 * de vue » — miroir `sectionsFor`) — FONCTIONS PURES, SANS ANDROID : la
 * classe de la vue sélectionnée + la famille de son parent + la sienne
 * (PE12c, spec `docs/PREVIEW_PARENTS.md` § 5 : sections du CONTENEUR par
 * famille de la VUE, sections « Dans le parent » / « Positions relatives »
 * par famille du PARENT) → les SECTIONS
 * repliables de l'onglet Attributs, chacune portant ses CONTRÔLES.
 *
 * Familles par HÉRITAGE (miroir § 4.1 du prompt PE7e) : `View` →
 * `TextView` → (`Button` → `MaterialButton`, `EditText` →
 * `TextInputEditText`, `CompoundButton` → `CheckBox`, `Switch`,
 * `SwitchMaterial`) ; `ImageView` → `ImageButton` ; `ViewGroup` →
 * `LinearLayout`, `MaterialCardView`. Une classe INCONNUE ou dégradée
 * (`MockView`, vue personnalisée) ne reçoit que l'IDENTITÉ et les sections
 * GÉNÉRIQUES (Disposition, Apparence, Affichage, Transformation,
 * Interaction, Accessibilité, Aperçu uniquement — miroir v6) — jamais un
 * contrôle qui pourrait être faux.
 *
 * La RACINE reçoit Identité + Racine + Conteneur (famille) puis S'ARRÊTE
 * (miroir le retour anticipé de `sectionsFor` v6) : pas de sections
 * génériques, pas d'Autres attributs.
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
 * - `android:alpha` (PE11g-contrôles) : absence = `1.0` = le DÉFAUT du
 *   CURSEUR — une valeur égale au défaut RETIRE l'attribut (miroir
 *   `sld` : `num === c.def ? '' : num + c.u`).
 *
 * Exemption detekt ciblée (règle 8 — exceptions minimales et commentées) :
 * TooManyFunctions — chaque section du catalogue a SA fonction
 * descriptrice ([sectionTexte], [sectionSaisie]…) : le miroir direct
 * des `if (k === …)` de la maquette, lisible attribut par attribut ;
 * LargeClass — le catalogue EST une longue liste par conception
 * (l'annexe § 7 du brief y ajoute encore ~130 attributs), même motif
 * que le volet d'attributs côté UI : la taille est le miroir, pas la
 * dette.
 */
@Suppress("TooManyFunctions", "LargeClass")
public object CatalogueAttributs {
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

        /** PE12c — § 5.1 : la vue sélectionnée EST un AppBarLayout. */
        BARRE_APPLICATION,

        /** PE12c — § 5.2 : les règles d'un enfant de RelativeLayout vers un frère. */
        POSITIONS_RELATIVES,

        /** PE12c — § 5.4 : section propre de MaterialToolbar. */
        BARRE_OUTILS,

        /** PE12c — § 5.4 : section propre du FloatingActionButton. */
        BOUTON_ACTION,

        /** PE12c — § 5.1 : la section Carte de MaterialCardView (ex-Conteneur). */
        CARTE,

        /** PE11 § 7 — la TYPOGRAPHIE de la famille texte (fontFamily, graisse…). */
        TYPOGRAPHIE,

        /** PE11 § 7 — MAXLINES, ellipsize, autoLink… (famille texte et saisie). */
        LIGNES,

        /** PE11 § 7 — drawableStart/End/Top/Bottom (texte, saisie — pas Switch). */
        ICONES_TEXTE,

        /** PE11 § 7 — Aides et compteur du TextInputLayout (helperText…). */
        AIDES_CHAMP,

        /** PE11 § 7 — Icônes du TextInputLayout (startIconDrawable, endIconMode). */
        ICONES,

        /** PE11 § 7 — Contour du TextInputLayout (trait, fond, coins). */
        CONTOUR,

        /** PE11 § 7 — Apparence commune (background, elevation, theme…). */
        APPARENCE,

        /** PE11 § 7 — Transformation commune (rotation, échelles, translations). */
        TRANSFORMATION,

        /** PE11 § 7 — Interaction commune (enabled, clickable, onClick…). */
        INTERACTION,

        /** PE11 § 7 — Accessibilité commune (contentDescription, labelFor…). */
        ACCESSIBILITE,

        /** PE11 § 7 — Aperçu uniquement (attributs `tools:`, note dédiée). */
        APERCU,

        /** PE11 § 7 — la section RACINE (tools:context, background, padding…). */
        RACINE,

        /** PE11h — la section « Autres attributs » (miroir `{other:true}`) :
         * les attributs du document hors catalogue + l'ajout libre. */
        AUTRES,
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

        // ── PE12c — § 5.1 : sections du conteneur par famille ───────────
        BASELINE_ALIGNE,
        SOMME_POIDS,
        SEPARATEUR,
        SEPARATEUR_PADDING,
        MESURER_PLUS_GRAND,
        CLIP_ENFANTS,
        CLIP_PADDING,
        ANIMER_MISE_EN_PAGE,
        GRAVITE_AVANT_PLAN,
        MESURER_TOUS_ENFANTS,
        IGNORER_GRAVITE,
        AJUSTER_SYSTEME,
        FOND_BARRE_ETAT,
        ELEVER_AU_DEFILEMENT,
        CIBLE_ELEVEE,
        ETENDU,

        // ── PE12c — § 5.2 : dans le parent / positions relatives ───────
        ANCRAGE_PARENT,
        ANCRAGE_DEBUT,
        ANCRAGE_FIN,
        ANCRAGE_HAUT,
        ANCRAGE_BAS,
        ANCRAGE_CENTRE_H,
        ANCRAGE_CENTRE_V,
        ANCRAGE_CENTRE,
        ALIGNER_SI_ABSENT,
        DESSOUS_DE,
        DESSUS_DE,
        ALIGNE_HAUT_AVEC,
        ALIGNE_BAS_AVEC,
        LIGNE_BASE_AVEC,
        A_DROITE_DE,
        A_GAUCHE_DE,
        ALIGNE_DEBUT_AVEC,
        ALIGNE_FIN_AVEC,
        ANCRE,
        GRAVITE_ANCRE,
        COMPORTEMENT,
        BORD_INSET,
        ESQUIVER_BORDS,
        KEYLINE,
        DRAPEAUX_DEFILEMENT,
        INTERPOLATEUR_DEFILEMENT,

        // ── PE12c — § 5.4 : barre d'outils / bouton d'action ────────────
        TITRE,
        SOUS_TITRE,
        ICONE_NAVIGATION,
        TITRE_CENTRE,
        COULEUR_TITRE,
        MENU,
        SRC_COMPAT,
        TEINTE_FOND,
        TAILLE_FAB,
        TEINTE,
        LARGEUR_BORDURE,
        COULEUR_RIPPLE,
        PADDING_COMPAT,

        // ── PE11h — action rapide, contraintes en lecture, attribut libre ─

        /** Le LIBELLÉ d'une action rapide (miroir `l:'Actions'` — cherchable, jamais rendue). */
        ACTIONS,

        /** L'étiquette du bloc de contraintes LUES (miroir `l:'dans le ConstraintLayout'`). */
        CONTRAINTES_LUES,

        /** Le libellé de l'action « Remplir l'espace restant » (miroir `.qa .linkbtn`). */
        REMPLIR_ESPACE,

        // ── PE11 § 7 — Identité, Texte, Typographie, Lignes ──────────────

        /** `android:tag` (Identité). */
        TAG,

        /** `android:textAlignment` (Texte — liste). */
        ALIGNEMENT_TEXTE,

        /** `android:textAppearance` (Texte — liste). */
        APPARENCE_TEXTE,

        /** `android:fontFamily` (Typographie — liste). */
        FAMILLE_POLICE,

        /** `android:textFontWeight` (Typographie — liste). */
        GRAISSE_POLICE,

        /** `android:textAllCaps` (Typographie — tri-état). */
        MAJUSCULES,

        /** `android:letterSpacing` en em (Typographie — curseur). */
        ESPACEMENT_LETTRES,

        /** `android:lineSpacingExtra` (Typographie — nombre dp). */
        INTERLIGNE,

        /** `android:lineSpacingMultiplier` (Typographie — nombre). */
        INTERLIGNE_MULT,

        /** `android:includeFontPadding` (Typographie — tri-état). */
        PADDING_POLICE,

        /** `android:minLines` (Lignes — nombre). */
        LIGNES_MIN,

        /** `android:ellipsize` (Lignes — liste). */
        ELLIPSIZE,

        /** `android:autoLink` (Lignes — drapeaux). */
        LIENS_AUTO,

        /** `android:textIsSelectable` (Lignes — tri-état). */
        TEXTE_SELECTIONNABLE,

        /** `android:autoSizeTextType` (Lignes — choix). */
        TAILLE_AUTO,

        /** `android:autoSizeMinTextSize` (Lignes — nombre sp). */
        TAILLE_AUTO_MIN,

        /** `android:autoSizeMaxTextSize` (Lignes — nombre sp). */
        TAILLE_AUTO_MAX,

        // ── PE11 § 7 — Icônes du texte, Saisie ───────────────────────────

        /** `android:drawableStart` (Icônes du texte — ressource). */
        DRAWABLE_DEBUT,

        /** `android:drawableEnd` (Icônes du texte — ressource). */
        DRAWABLE_FIN,

        /** `android:drawableTop` (Icônes du texte — ressource). */
        DRAWABLE_HAUT,

        /** `android:drawableBottom` (Icônes du texte — ressource). */
        DRAWABLE_BAS,

        /** `android:drawablePadding` (Icônes du texte — nombre dp). */
        DRAWABLE_PADDING,

        /** `android:drawableTint` (Icônes du texte — couleur). */
        DRAWABLE_TEINTE,

        /** `android:imeOptions` (Saisie — liste). */
        OPTIONS_IME,

        /** `android:maxLength` (Saisie — nombre). */
        LONGUEUR_MAX,

        /** `android:digits` (Saisie — texte). */
        CHIFFRES,

        /** `android:autofillHints` (Saisie — liste). */
        SUGGESTIONS_AUTOFILL,

        /** `android:importantForAutofill` (Saisie — liste). */
        IMPORTANCE_AUTOFILL,

        /** `android:selectAllOnFocus` (Saisie — tri-état). */
        TOUT_SELECTIONNER,

        /** `android:cursorVisible` (Saisie — tri-état). */
        CURSEUR_VISIBLE,

        /** `android:textColorHint` (Saisie — couleur). */
        COULEUR_INDICATION,

        // ── PE11 § 7 — Champ, Aides et compteur, Icônes, Contour ─────────

        /** `app:hintEnabled` (Champ — tri-état). */
        INDICATION_ACTIVEE,

        /** `app:helperText` (Aides et compteur — texte). */
        TEXTE_AIDE,

        /** `app:errorEnabled` (Aides et compteur — tri-état). */
        ERREUR_ACTIVEE,

        /** `app:counterEnabled` (Aides et compteur — tri-état). */
        COMPTEUR_ACTIVE,

        /** `app:counterMaxLength` (Aides et compteur — nombre). */
        COMPTEUR_LONGUEUR,

        /** `app:placeholderText` (Aides et compteur — texte). */
        TEXTE_SUBSTITUTION,

        /** `app:prefixText` (Aides et compteur — texte). */
        PREFIXE,

        /** `app:suffixText` (Aides et compteur — texte). */
        SUFFIXE,

        /** `app:startIconDrawable` (Icônes — ressource). */
        ICONE_DEBUT,

        /** `app:endIconTint` (Icônes — couleur). */
        TEINTE_ICONE_FIN,

        /** `app:boxStrokeColor` (Contour — couleur). */
        TRAIT_FOND,

        /** `app:boxStrokeWidth` (Contour — nombre dp). */
        EPAISSEUR_TRAIT,

        /** `app:boxBackgroundColor` (Contour — couleur). */
        FOND_BOITE,

        /** Les QUATRE coins `app:boxCornerRadius*` (Contour — coins). */
        COINS,

        // ── PE11 § 7 — Bouton, État, Image, Carte ─────────────────────────

        /** `style` du Button — « variante » (liste). */
        VARIANTE,

        /** `app:iconGravity` (Bouton — liste). */
        GRAVITE_ICONE,

        /** `app:iconPadding` (Bouton — nombre dp). */
        PADDING_ICONE,

        /** `app:iconTint` (Bouton — couleur). */
        TEINTE_ICONE,

        /** `app:iconSize` (Bouton — nombre dp). */
        TAILLE_ICONE,

        /** `app:strokeColor` (Bouton, Carte — couleurs). */
        COULEUR_BORDURE,

        /** `app:strokeWidth` (Bouton, Carte — nombres dp). */
        EPAISSEUR_BORDURE,

        /** `android:insetTop` (Bouton — nombre dp). */
        INSET_HAUT,

        /** `android:insetBottom` (Bouton — nombre dp). */
        INSET_BAS,

        /** `app:buttonTint` (État CheckBox — couleur). */
        TEINTE_CASE,

        /** `android:button` (État CheckBox — ressource). */
        CASE,

        /** `android:thumbTint` (État Switch, Progression SeekBar — couleurs). */
        TEINTE_POUCE,

        /** `android:trackTint` (État Switch — couleur). */
        TEINTE_PISTE,

        /** `android:showText` (État Switch — tri-état). */
        AFFICHER_TEXTE,

        /** `android:textOn` (État Switch — texte). */
        TEXTE_ACTIF,

        /** `android:textOff` (État Switch — texte). */
        TEXTE_INACTIF,

        /** `android:adjustViewBounds` (Image — tri-état). */
        AJUSTER_BORNES,

        /** `android:tintMode` (Image — liste). */
        MODE_TEINTE,

        /** `android:cropToPadding` (Image — tri-état). */
        ROGNER_PADDING,

        /** `android:maxWidth` (Image, Disposition — nombres dp). */
        LARGEUR_MAX,

        /** `android:maxHeight` (Image, Disposition — nombres dp). */
        HAUTEUR_MAX,

        /** `app:cardBackgroundColor` (Carte — couleur). */
        FOND_CARTE,

        /** `app:contentPadding` (Carte — nombre dp). */
        PADDING_CONTENU,

        /** `app:cardPreventCornerOverlap` (Carte — tri-état). */
        EMPECHER_CHEVAUCHEMENT,

        /** `android:checkable` (Carte — tri-état). */
        COCHABLE,

        // ── PE11 § 7 — Progression, Liste, Disposition, Contraintes ──────

        /** `android:min` (Progression — nombre). */
        MINIMUM,

        /** `android:secondaryProgress` (Progression — nombre). */
        PROGRESSION_SECONDAIRE,

        /** `android:progressTint` (ProgressBar, SeekBar — couleurs). */
        TEINTE_PROGRESSION,

        /** `android:progressBackgroundTint` (ProgressBar — couleur). */
        TEINTE_FOND_PROGRESSION,

        /** `android:indeterminateTint` (ProgressBar — couleur). */
        TEINTE_INDETERMINEE,

        /** `android:thumb` (SeekBar — ressource). */
        POUCE,

        /** `android:splitTrack` (SeekBar — tri-état). */
        PISTE_FENDUE,

        /** `android:tickMark` (SeekBar — ressource). */
        REPERE,

        /** `app:spanCount` (Liste — nombre). */
        NOMBRE_COLONNES,

        /** `app:reverseLayout` (Liste — tri-état). */
        INVERSER,

        /** `app:stackFromEnd` (Liste — tri-état). */
        EMPILER_FIN,

        /** `android:overScrollMode` (Liste — liste). */
        DEBORDEMENT,

        /** `android:nestedScrollingEnabled` (Liste — tri-état). */
        DEFILEMENT_IMBRIQUE,

        /** `tools:itemCount` (Aperçu uniquement de RecyclerView — nombre). */
        NOMBRE_ITEMS,

        /** `android:minHeight` (Disposition, Racine — nombres dp). */
        HAUTEUR_MIN,

        /** `android:minWidth` (Disposition — nombre dp). */
        LARGEUR_MIN,

        /** `app:layout_constraintHorizontal_bias` — « bias horizontal ». */
        BIAIS_HORIZONTAL,

        /** `app:layout_constraintVertical_bias` — « bias vertical ». */
        BIAIS_VERTICAL,

        /** `app:layout_constraintWidth_percent` — « largeur en pourcentage ». */
        POURCENTAGE_LARGEUR,

        /** `app:layout_constraintDimensionRatio` — « ratio ». */
        RATIO,

        /** `app:layout_constraintWidth_min` — « largeur min ». */
        LARGEUR_MIN_CONTRAINTE,

        /** `app:layout_constraintWidth_max` — « largeur max ». */
        LARGEUR_MAX_CONTRAINTE,

        // ── PE11 § 7 — Apparence, Transformation, Interaction,
        //    Accessibilité, Aperçu uniquement, Racine ─────────────────────

        /** `android:background` brut (Apparence, Racine — couleurs). */
        FOND_BRUT,

        /** `android:foreground` (Apparence — ressource). */
        AVANT_PLAN,

        /** `android:elevation` brut (Apparence — curseur dp). */
        ELEVATION_BRUTE,

        /** `android:clipToOutline` (Apparence — tri-état). */
        DECOUPE_CONTOUR,

        /** `android:theme` (Apparence — ressource @style). */
        THEME,

        /** `android:rotation` (Transformation — curseur). */
        ROTATION,

        /** `android:scaleX` (Transformation — curseur). */
        ECHELLE_X,

        /** `android:scaleY` (Transformation — curseur). */
        ECHELLE_Y,

        /** `android:translationX` (Transformation — curseur dp). */
        TRANSLATION_X,

        /** `android:translationY` (Transformation — curseur dp). */
        TRANSLATION_Y,

        /** `android:translationZ` (Transformation — curseur dp). */
        TRANSLATION_Z,

        /** `android:clickable` (Interaction — tri-état). */
        CLIQUABLE,

        /** `android:focusable` (Interaction — tri-état). */
        FOCUSABLE,

        /** `android:focusableInTouchMode` (Interaction — tri-état). */
        FOCUSABLE_TACTILE,

        /** `android:longClickable` (Interaction — tri-état). */
        CLIQUABLE_LONG,

        /** `android:onClick` (Interaction — texte, nom de méthode Java). */
        AU_CLIC,

        /** `android:importantForAccessibility` (Accessibilité — liste). */
        IMPORTANCE_ACCESSIBILITE,

        /** `android:accessibilityHeading` (Accessibilité — tri-état). */
        TITRE_ACCESSIBILITE,

        /** `android:labelFor` (Accessibilité — référence d'id, portée TOUT). */
        LIBELLE_POUR,

        /** `android:tooltipText` (Accessibilité — texte). */
        INFOBULLE,

        /** `android:screenReaderFocusable` (Accessibilité — tri-état). */
        LECTEUR_ECRAN,

        /** `tools:text` (Aperçu uniquement — texte). */
        TOOLS_TEXTE,

        /** `tools:src` (Aperçu uniquement — ressource). */
        TOOLS_SRC,

        /** `tools:visibility` (Aperçu uniquement — choix). */
        TOOLS_VISIBILITE,

        /** `tools:ignore` (Aperçu uniquement — texte). */
        TOOLS_IGNORE,

        /** `tools:context` (Racine — texte, nom d'activité). */
        CONTEXTE,
    }

    /**
     * Les BORNES d'un [ControleAttribut.Nombre] (PE11 § 7 — miroir les
     * `min`/`max` du `C.num` de la maquette) : `null` = pas de plancher
     * (ni de plafond) pour ce côté.
     */
    public data class Bornes(val minimum: Float? = null, val maximum: Float? = null)

    /**
     * Le GENRE de VALIDATION d'un champ TEXTE (PE11 § 7 — miroir les
     * `pat`/`msg` du `C.str` de la maquette) : la valeur est vérifiée
     * AVANT écriture, une ERREUR n'écrit RIEN ([ValidationAttribut]).
     */
    public enum class GenreValidationTexte {
        /** `tools:context` : un nom d'activité, par exemple `.MainActivity`. */
        NOM_ACTIVITE,

        /** `android:onClick` : un nom de méthode Java, par exemple `onConnexion`. */
        NOM_METHODE_JAVA,
    }

    /** UN contrôle de l'éditeur (variante scellée — miroir § 4.1). */
    public sealed class ControleAttribut {
        /**
         * Le drapeau « Courants » du contrôle (PE11f) — les composites
         * sans champ ([Dimensions], [Contraintes]) sont TOUJOURS
         * courants. Le miroir du `c.cur` de la maquette vit AVEC le
         * type : plus de `when` de dispatch dans le volet ni dans les
         * tests (les trois copies dépassaient le seuil de complexité
         * detekt — CI de 175ce7a7).
         */
        public abstract val courant: Boolean

        /**
         * Les NOMS d'attributs portés par le contrôle (miroir `some` de
         * la maquette : le groupe compte dès qu'UN nom est défini) —
         * les composites sans champ n'en portent AUCUN.
         */
        public abstract val nomsAttributs: List<String>

        /**
         * L'ÉTIQUETTE du contrôle (miroir `c.l` de la maquette) — portée
         * par le TYPE comme [courant] (même médecine que la CI de
         * 175ce7a7 : plus de `when` de dispatch dans le volet), `null`
         * pour les composites sans libellé propre (identifiant,
         * composite de saisie, dimensions, contraintes).
         */
        public abstract val etiquette: Etiquette?

        /** L'identifiant de la vue (PE8a : renommage déclaration + références). */
        public data class Identifiant(val nom: String, override val courant: Boolean = true) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)

            /** [ControleAttribut.etiquette] : l'identifiant n'a pas de libellé propre. */
            override val etiquette: Etiquette? = null
        }

        /**
         * Un texte libre (`android:text`, `contentDescription`…) —
         * [extrayable] : le littéral peut être EXTRAIT vers `@string/…`
         * (PE8c — réservé aux libellés, jamais aux références de
         * drawable/enum).
         */
        public data class Texte(
            val nom: String,
            override val etiquette: Etiquette,
            val extrayable: Boolean = false,
            val validation: GenreValidationTexte? = null,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

        /**
         * Un nombre avec unité (`"dp"`, `"sp"` ou `""` — alpha sans unité) —
         * [bornes] optionnelles (PE11 § 7, miroir `min`/`max` du `C.num` :
         * une saisie hors bornes ne s'écrit pas, repli visuel au champ) ;
         * sans bornes, la 1re vague rejette les NÉGATIFS (comportement
         * historique conservé).
         */
        public data class Nombre(
            val nom: String,
            override val etiquette: Etiquette,
            val unite: String,
            val bornes: Bornes? = null,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

        /** Un choix segmenté — le PREMIER élément est le défaut (retrait). */
        public data class Choix(
            val nom: String,
            override val etiquette: Etiquette,
            val valeurs: List<String>,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

        /** Un tri-état (défaut / true / false — défaut = retrait). */
        public data class TriEtat(
            val nom: String,
            override val etiquette: Etiquette,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

        /**
         * Une liste déroulante (PE11c — miroir `list` de la maquette, § 9 :
         * tout enum de PLUS de 4 valeurs ; 4 ou moins reste un [Choix]) :
         * l'UI ajoute une première entrée « par défaut » qui RETIRE
         * l'attribut, et une valeur courante hors catalogue est AJOUTÉE à
         * la fin (miroir `if (cur && !opts.includes(cur)) opts.push(cur)`).
         */
        public data class Liste(
            val nom: String,
            override val etiquette: Etiquette,
            val valeurs: List<String>,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

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
            override val etiquette: Etiquette,
            val valeurs: List<String>,
            val requiert: String? = null,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)

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
            public fun basculer(valeur: String?, drapeau: String): String {
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
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)

            /** [ControleAttribut.etiquette] : le composite de saisie a UNE étiquette de section. */
            override val etiquette: Etiquette? = null

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
            public fun composer(base: String, modificateurs: List<String>): String =
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
            override val etiquette: Etiquette,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

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
            override val etiquette: Etiquette,
            val types: List<String>,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)

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

        /**
         * Le contrôle CURSEUR (PE11g-contrôles — miroir `sld` de la
         * maquette, § 9 `controle_curseur`) : glissière violette + champ
         * numérique LIÉS ; le glissement suit le champ SANS écrire
         * (miroir `r.oninput`), l'écriture ne se fait qu'au RELÂCHEMENT
         * ou au commit du champ (miroir `r.onchange`/`n.onchange`). La
         * valeur écrite est `nombre + unité` et une valeur ÉGALE au
         * [defaut] RETIRE l'attribut (miroir
         * `num === c.def ? '' : num + c.u`) ; une saisie hors
         * [min]…[max] ne s'écrit pas ([ValidationAttribut.curseur]).
         * [maxDe] : la borne HAUTE suit la valeur numérique de CET
         * attribut du document quand il existe (miroir
         * `Math.max(1, dpv(ex(v, 'android:max')) || 100)` — la
         * progression borne sa glissière au max du ProgressBar) ;
         * attribut absent, vide ou non positif → [max].
         * [requiertContraintesVerticales] (PE11 § 7 — miroir `en:!both` +
         * `why` du `Vertical_bias`) : la glissière et son champ sont
         * DÉSACTIVÉS, explication sous le champ, tant que la vue n'a pas
         * une contrainte en haut ET en bas ([EditionAttributsXml.lireContraintes]).
         */
        public data class Curseur(
            val nom: String,
            override val etiquette: Etiquette,
            val min: Float,
            val max: Float,
            val pas: Float,
            val unite: String,
            val defaut: Float,
            val maxDe: String? = null,
            val requiertContraintesVerticales: Boolean = false,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)

            /**
             * La valeur AFFICHÉE (miroir `cur === '' ? c.def : (dpv(cur)
             * ?? c.def)`) : l'attribut absent, vide ou non numérique se
             * montre à son DÉFAUT.
             */
            public fun valeurAffichee(valeur: String?): Float = nombreDe(valeur) ?: defaut

            /**
             * La valeur ÉCRITE (miroir `num + c.u`) : égale au [defaut]
             * → VIDE (l'écriture retirera l'attribut).
             */
            public fun ecrire(nombre: Float): String = if (nombre == defaut) "" else formaterNombre(nombre) + unite

            /**
             * La borne HAUTE VÉCUE (miroir
             * `Math.max(1, dpv(ex(v, 'android:max')) || 100)`) : la
             * valeur de l'attribut [maxDe] quand elle est POSITIVE (le
             * zéro est FAUX côté maquette — il retombe sur [max]), le
             * plancher 1 sinon pour les négatifs.
             */
            public fun borneMax(valeurMax: String?): Float {
                val nombre = nombreDe(valeurMax) ?: return max
                return when {
                    nombre == 0f -> max
                    else -> maxOf(1f, nombre)
                }
            }

            /**
             * Le NOMBRE en tête d'une valeur (miroir `dpv` :
             * `parseFloat` du préfixe numérique — « 12dp » donne 12),
             * `null` quand il n'y en a pas.
             */
            private fun nombreDe(valeur: String?): Float? = nombreEnTete
                .find(valeur.orEmpty())
                ?.value
                ?.toFloatOrNull()

            /** Un préfixe numérique (« 12 », « -0.5 », « 12dp »). */
            private val nombreEnTete: Regex = Regex("""^[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
        }

        /**
         * Le contrôle RATIO (PE11g-contrôles — miroir `ratio` de la
         * maquette, § 9 `controle_ratio`) : deux nombres « a:b » avec
         * VERROU optionnel `W,`/`H,` (le côté CALCULÉ), préréglages
         * [PREDEFINIS] (1:1, 4:3, 16:9, 3:4) et la note « dimension à
         * 0dp » (posée par l'UI). Les deux champs VIDES retirent
         * l'attribut ; une valeur sans deux nombres POSITIFS ne s'écrit
         * pas ([ValidationAttribut.ratio]).
         */
        public data class Ratio(
            val nom: String,
            override val etiquette: Etiquette,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)

            /**
             * Une valeur PARSE du ratio : le [verrou] optionnel
             * (`"W"`, `"H"` ou `null`) et les deux nombres TELS QUE
             * SAISIS (miroir le regex de la maquette — l'écriture les
             * restitue verbatim).
             */
            public data class Valeur(val verrou: String?, val a: String, val b: String)

            /**
             * La valeur PARSE (miroir
             * `/^(?:([WH]), )?(\d+(?:\.\d+)?):(\d+(?:\.\d+)?)$/`) :
             * `null` si la valeur ne suit pas le format.
             */
            public fun lire(valeur: String?): Valeur? {
                val groupes = formatDuRatio.find(valeur.orEmpty()) ?: return null
                return Valeur(
                    verrou = groupes.groups[1]?.value,
                    a = groupes.groups[2]?.value.orEmpty(),
                    b = groupes.groups[3]?.value.orEmpty(),
                )
            }

            /**
             * La valeur COMPOSÉE (miroir `(lk ? lk + ',' : '') + x + ':' + y`).
             */
            public fun ecrire(verrou: String?, a: String, b: String): String =
                (verrou?.let { "$it," }.orEmpty()) + "$a:$b"

            /** Le format du ratio, verrou en option (miroir la maquette). */
            private val formatDuRatio: Regex = Regex("""^(?:([WH]),)?(\d+(?:\.\d+)?):(\d+(?:\.\d+)?)$""")

            /**
             * UN préréglage : son LIBELLÉ de bouton et son couple a:b —
             * le [libelle] est la DONNÉE du bouton (miroir le
             * `x + ':' + y` calculé côté maquette : le liage ne compose
             * JAMAIS de gabarit d'affichage).
             */
            public data class Prereglage(val libelle: String, val a: String, val b: String)

            public companion object {
                /** Les PRÉRÉGLAGES de la maquette (boutons 1:1, 4:3, 16:9, 3:4). */
                public val PREDEFINIS: List<Prereglage> =
                    listOf(
                        Prereglage("1:1", "1", "1"),
                        Prereglage("4:3", "4", "3"),
                        Prereglage("16:9", "16", "9"),
                        Prereglage("3:4", "3", "4"),
                    )
            }
        }

        /** Les quatre côtés d'une marge ou d'un padding (miroir `.mrow`). */
        public data class Cotes(
            val base: String,
            override val etiquette: Etiquette,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : les quatre côtés partagent la BASE. */
            override val nomsAttributs: List<String> = listOf(base)
        }

        /**
         * Les QUATRE COINS d'un champ (PE11 § 7 — miroir `corners` de la
         * maquette, annexe « Contour ») : UN ATTRIBUT PAR COIN
         * (`app:boxCornerRadiusTopStart` + les trois `also`), chacun
         * s'écrit seul — `0` ou VIDE RETIRE l'attribut, sinon `Ndp` ; le
         * bouton ↺ de l'en-tête réinitialise les QUATRE coins (une seule
         * édition). Layout de référence : `controle_cotes.xml` (brief
         * PE11f — « controle_cotes pour Coins »).
         */
        public data class Coins(
            val base: String,
            override val etiquette: Etiquette,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte ses QUATRE coins
             * (miroir `[c.a].concat(c.also)` — départ haut, fin haut, fin bas,
             * début bas, l'ordre de la rangée `.mrow`). */
            override val nomsAttributs: List<String> =
                listOf("TopStart", "TopEnd", "BottomEnd", "BottomStart").map { base.removeSuffix("TopStart") + it }
        }

        /** Les dimensions wrap/match/fixe (PE7d — miroir `dimrow`). */
        public object Dimensions : ControleAttribut() {
            /** [ControleAttribut.courant] : composite sans champ, toujours courant. */
            override val courant: Boolean = true

            /** [ControleAttribut.nomsAttributs] : rien à compter. */
            override val nomsAttributs: List<String> = emptyList()

            /** [ControleAttribut.etiquette] : composite sans libellé propre. */
            override val etiquette: Etiquette? = null
        }

        /** Les contraintes du ConstraintLayout parent (ÉDITABLES — PE8b :
         * cible parent/frère + marge par ancre). */
        public object Contraintes : ControleAttribut() {
            /** [ControleAttribut.courant] : composite sans champ, toujours courant. */
            override val courant: Boolean = true

            /** [ControleAttribut.nomsAttributs] : rien à compter. */
            override val nomsAttributs: List<String> = emptyList()

            /** [ControleAttribut.etiquette] : composite sans libellé propre. */
            override val etiquette: Etiquette? = null
        }

        /**
         * Le contrôle RÉFÉRENCE D'ID (PE12c — miroir `idref` de la
         * maquette, spec § 5.3) : une liste déroulante de `@id/…` bornée
         * par une [Portee]. Première option « aucun » (retire
         * l'attribut) ; une option qui FERMERAIT une boucle est
         * désactivée et suffixée « (boucle) » ; une valeur absente de
         * l'arbre s'ajoute en dernière option « (introuvable) ». Les
         * CANDIDATS se calculent sur l'arbre du document
         * ([ReferencesId.options]) ; le contrôle ne porte que la DÉCLARATION.
         */
        public data class ReferenceId(
            val nom: String,
            override val etiquette: Etiquette,
            val portee: Portee,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)

            /** La portée des candidats (spec § 5.3). */
            public enum class Portee {
                /** Tous les `@id/` de l'arbre, sauf la vue (prévu pour `labelFor` — 2e vague). */
                TOUT,

                /** Les frères DIRECTS de la vue, sauf elle-même (règles de RelativeLayout). */
                FRERES,

                /** Le sous-arbre du PARENT pour `layout_anchor` ; de la VUE
                 * pour `ignoreGravity` / `liftOnScrollTargetViewId`. */
                SOUS_ARBRE,
            }
        }

        /**
         * Un groupe de BASCULES (PE12c — miroir `tgl` de la maquette,
         * spec § 5.2) : chaque bouton porte SON PROPRE attribut — un
         * appui écrit `="true"`, un second appui RETIRE l'attribut ; le
         * bouton ↺ de l'en-tête réinitialise TOUS les attributs du
         * groupe (une seule édition).
         */
        public data class Bascules(
            override val etiquette: Etiquette,
            val entrees: List<Entree>,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : chaque bascule porte SON attribut. */
            override val nomsAttributs: List<String> = entrees.map { entree -> entree.nom }

            /** UNE bascule : l'attribut [nom] et la clé de libellé [libelle]. */
            public data class Entree(val nom: String, val libelle: Etiquette)
        }

        /**
         * Les ACTIONS RAPIDES d'une section (PE11h — miroir `quick` de la
         * maquette) : une rangée de boutons « lien » SANS champ ni en-tête
         * (le layout `controle_action` ne porte PAS d'étiquette visible) ;
         * chaque action écrit UNE ÉDITION GROUPÉE. Composite sans champ :
         * [nomsAttributs] vide (l'action ne compte jamais dans le badge
         * « n définis », miroir un `quick` sans `a`), l'[Etiquette.ACTIONS]
         * ne sert qu'à la RECHERCHE.
         */
        public data class ActionRapide(val actions: List<Action>, override val courant: Boolean = true) :
            ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : composite sans champ, rien à compter. */
            override val nomsAttributs: List<String> = emptyList()

            /** [ControleAttribut.etiquette] : clé de RECHERCHE uniquement (jamais rendue). */
            override val etiquette: Etiquette? = Etiquette.ACTIONS

            /**
             * UNE action rapide : le [libelle] (clé sémantique du bouton)
             * et son [ecriture] (l'édition groupée, composée par
             * [EditionAttributsXml.remplirEspaceRestant] au port).
             */
            public data class Action(val libelle: Etiquette, val ecriture: Ecriture)

            /** L'ÉCRITURE d'une action rapide (une édition groupée — pure donnée). */
            public sealed interface Ecriture {
                /**
                 * « Remplir l'espace restant » (miroir l'`items` du `quick`
                 * linéaire) : `layout_weight=1` + la dimension de l'AXE DU
                 * PARENT passée à `0dp` — l'axe se résout au CLIC sur
                 * l'orientation du parent dans le texte vivant.
                 */
                public data object RemplirEspaceRestant : Ecriture
            }
        }

        /**
         * Les contraintes EN LECTURE (PE11h — miroir `con` de la maquette,
         * annexe § 7 « Contraintes — ancres lues ») : le bloc qui MONTRE
         * toutes les ancres `app:layout_constraint*` telles qu'écrites
         * (y compris celles que l'édition par côtés ne couvre pas — ligne
         * de base, marges de disparition…). L'ÉDITION réelle reste le
         * composite [Contraintes] (PE8b) : la section les porte À LA SUITE.
         */
        public data class ContraintesLecture(override val etiquette: Etiquette, override val courant: Boolean = true) :
            ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : composite sans champ, rien à compter. */
            override val nomsAttributs: List<String> = emptyList()
        }
    }

    /**
     * UNE section repliable (titre + contrôles — miroir `.sec2`). La
     * [note] optionnelle (PE12c — miroir `sc.note` de la maquette) se
     * rend EN TÊTE du corps, avant les contrôles ; [autres] marque la
     * section « Autres attributs » (PE11h — miroir `{other:true}`) : son
     * corps ne vient PAS du catalogue mais du DOCUMENT (attributs hors
     * catalogue + rangée d'ajout libre, [controles] vide).
     */
    public data class SectionAttributs(
        val titre: TitreSection,
        val controles: List<ControleAttribut>,
        val note: NoteSection? = null,
        val autres: Boolean = false,
    )

    /** Les notes de section (clés sémantiques — traduites au chrome). */
    public enum class NoteSection {
        /** « Chaque règle vise une vue sœur ; une règle qui fermerait une boucle est refusée. » */
        REGLES_SOEURS,

        /** PE11 § 7 — « Attributs tools: lus par l'aperçu et l'éditeur, absents de l'APK. »
         * (miroir la `note` de la section « Aperçu uniquement »). */
        OUTILS_APERCU,
    }

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

    private val FONDS: List<String> = listOf("outline", "filled", "none")
    private val ICONES_FIN: List<String> =
        listOf("none", "password_toggle", "clear_text", "custom", "dropdown_menu")

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

    // PE12c — § 5.2 (miroir BEHAVIORS / EDGES / SCROLLF de la maquette) :
    // les comportements de CoordinatorLayout, les bords d'insertion, leurs
    // drapeaux d'esquive et les drapeaux de défilement d'AppBarLayout.
    private val COMPORTEMENTS: List<String> =
        listOf(
            "@string/appbar_scrolling_view_behavior",
            "@string/bottom_sheet_behavior",
            "com.google.android.material.behavior.HideBottomViewOnScrollBehavior",
            "com.google.android.material.behavior.SwipeDismissBehavior",
        )
    private val BORDS_INSET: List<String> = listOf("none", "top", "bottom", "left", "right", "start", "end")
    private val DRAPEAUX_ESQUIVE: List<String> = listOf("top", "bottom", "left", "right", "start", "end", "all")
    private val DRAPEAUX_DEFILEMENT: List<String> =
        listOf("scroll", "exitUntilCollapsed", "enterAlways", "enterAlwaysCollapsed", "snap", "snapMargins")

    // PE12c — § 5.4 : les trois tailles du FloatingActionButton (fabSize).
    private val TAILLES_FAB: List<String> = listOf("auto", "normal", "mini")

    // Les types acceptés par le FOND DE BARRE D'ÉTAT (§ 5.1) — miroir
    // C.res('app:statusBarBackground', …, ['drawable', 'color']).
    private val TYPES_FOND_BARRE: List<String> = listOf("drawable", "color")

    // Le SEUL type accepté par android:theme (Apparence § 7 — miroir
    // C.res('android:theme', 'theme', ['style'])).
    private val TYPES_STYLE: List<String> = listOf("style")

    // PE11 § 7 — les LISTES de la 2e vague (miroir les constantes de la
    // maquette : ALIGN, APPEAR, FONTS, WEIGHTS, ELLIP, AUTOLINK, IME,
    // AUTOFILL, IMPORTANT_AUTOFILL, ICONGRAV, TINTMODE, STYLES_*, LMGR,
    // OVERSCROLL, IMPORTANT_A11Y).
    private val ALIGNEMENTS_TEXTE: List<String> =
        listOf("gravity", "textStart", "center", "textEnd", "viewStart", "viewEnd")
    private val APPARENCES_TEXTE: List<String> =
        listOf(
            "?attr/textAppearanceHeadlineLarge",
            "?attr/textAppearanceHeadlineMedium",
            "?attr/textAppearanceTitleLarge",
            "?attr/textAppearanceTitleMedium",
            "?attr/textAppearanceBodyLarge",
            "?attr/textAppearanceBodyMedium",
            "?attr/textAppearanceLabelLarge",
        )
    private val FAMILLES_POLICE: List<String> =
        listOf("sans-serif", "sans-serif-medium", "sans-serif-light", "serif", "monospace", "cursive")
    private val GRAISSES_POLICE: List<String> =
        listOf("100", "200", "300", "400", "500", "600", "700", "800", "900")
    private val MODES_ELLIPSIZE: List<String> = listOf("none", "start", "middle", "end", "marquee")
    private val DRAPEAUX_LIENS: List<String> = listOf("web", "email", "phone", "map", "all")
    private val OPTIONS_IME: List<String> =
        listOf("actionNone", "actionGo", "actionSearch", "actionSend", "actionNext", "actionDone")
    private val SUGGESTIONS_AUTOFILL: List<String> =
        listOf(
            "emailAddress",
            "username",
            "password",
            "newPassword",
            "name",
            "phone",
            "postalAddress",
            "postalCode",
            "smsOTPCode",
        )
    private val IMPORTANCE_AUTOFILL: List<String> = listOf("auto", "yes", "no", "noExcludeDescendants")
    private val STYLES_CHAMP: List<String> =
        listOf(
            "@style/Widget.Material3.TextInputLayout.OutlinedBox",
            "@style/Widget.Material3.TextInputLayout.FilledBox",
            "@style/Widget.Material3.TextInputLayout.OutlinedBox.Dense",
        )
    private val GRAVITES_ICONE: List<String> =
        listOf("start", "textStart", "end", "textEnd", "top", "textTop")
    private val STYLES_BOUTON: List<String> =
        listOf(
            "@style/Widget.Material3.Button",
            "@style/Widget.Material3.Button.OutlinedButton",
            "@style/Widget.Material3.Button.TextButton",
            "@style/Widget.Material3.Button.TonalButton",
            "@style/Widget.Material3.Button.ElevatedButton",
        )
    private val MODES_TEINTE: List<String> = listOf("src_in", "src_atop", "multiply", "screen", "add")
    private val STYLES_CARTE: List<String> =
        listOf(
            "@style/Widget.Material3.CardView.Elevated",
            "@style/Widget.Material3.CardView.Filled",
            "@style/Widget.Material3.CardView.Outlined",
        )
    private val GESTIONNAIRES_LISTE: List<String> =
        listOf(
            "androidx.recyclerview.widget.LinearLayoutManager",
            "androidx.recyclerview.widget.GridLayoutManager",
            "androidx.recyclerview.widget.StaggeredGridLayoutManager",
        )
    private val MODES_DEBORDEMENT: List<String> = listOf("always", "ifContentScrolls", "never")
    private val IMPORTANCE_ACCESSIBILITE: List<String> =
        listOf("auto", "yes", "no", "noHideDescendants")

    /**
     * Sur la RACINE, `fitsSystemWindows` et `clipToPadding` ne se
     * répètent pas dans Conteneur (spec § 5.1 — miroir `DUP` de la
     * maquette : la section Racine de la 2e vague les porte).
     */
    private val DOUBLES_RACINE: Set<String> = setOf("android:fitsSystemWindows", "android:clipToPadding")

    private fun List<ControleAttribut>.sansDoublesRacine(racine: Boolean): List<ControleAttribut> = if (!racine) {
        this
    } else {
        filterNot { controle ->
            controle is ControleAttribut.TriEtat && controle.nom in DOUBLES_RACINE
        }
    }

    /**
     * Les SECTIONS de la vue [classe] avec la [familleParent] de son
     * parent et la [familleVue] de la vue ELLE-MÊME (PE12c, spec § 5 ;
     * PE11 § 7 — catalogue curé complet) : sections PROPRES à la classe
     * (ordre du catalogue : Texte, Typographie, Lignes, Icônes du texte,
     * Saisie, Champ…), puis conteneur (§ 5.1 — la famille de la VUE
     * décide), puis parent (§ 5.2 — la famille du PARENT décide), puis
     * les sections GÉNÉRIQUES (Disposition, Apparence, Affichage,
     * Transformation, Interaction, Accessibilité, Aperçu uniquement).
     *
     * Sur la RACINE ([familleParent] = [FamilleParent.AUCUN]), la fiche
     * est IDENTITÉ + RACINE + Conteneur (famille) puis RETOUR ANTICIPÉ
     * (miroir le `if (isRoot) { …; return out }` de `sectionsFor` v6) :
     * `fitsSystemWindows` et `clipToPadding` y vivent dans Racine, pas
     * dans Conteneur (miroir `DUP`).
     */
    public fun sections(
        classe: String,
        familleParent: FamilleParent,
        familleVue: FamilleParent,
    ): List<SectionAttributs> {
        if (familleParent == FamilleParent.AUCUN) {
            return listOf(sectionIdentite(), sectionRacine()) +
                sectionsConteneur(classe, familleVue, racine = true)
        }
        val propres = sectionsPropres(classe)
        val conteneur = sectionsConteneur(classe, familleVue, racine = false)
        val duParent =
            when (familleParent) {
                FamilleParent.CONSTRAINT -> {
                    listOf(sectionContraintes())
                }

                FamilleParent.LINEAR -> {
                    listOf(sectionDansParentLineaire())
                }

                FamilleParent.FRAME -> {
                    listOf(sectionDansParentFrame())
                }

                FamilleParent.RELATIVE -> {
                    listOf(sectionDansParentRelatif(), sectionPositionsRelatives())
                }

                FamilleParent.COORDINATOR -> {
                    listOf(sectionDansParentCoordonnateur())
                }

                FamilleParent.APPBAR -> {
                    listOf(sectionDansParentBarre())
                }

                FamilleParent.AUCUN,
                FamilleParent.AUTRE,
                -> {
                    emptyList()
                }
            }
        // L'IDENTITÉ ouvre TOUTE fiche (PE8a) : le renommage est
        // indépendant de la classe — la racine y a droit aussi (elle
        // vit dans le RETOUR ANTICIPÉ ci-dessus).
        // PE11h — la section « Autres attributs » clôt TOUTE fiche NON
        // RACINE (miroir le `sec('Autres attributs', [], {other:true})` de
        // `sectionsFor`, absent du retour anticipé de la racine).
        val autres = listOf(SectionAttributs(TitreSection.AUTRES, emptyList(), autres = true))
        return listOf(sectionIdentite()) + propres + conteneur + duParent + generiques(classe) + autres
    }

    /**
     * La section CONTRAINTES d'un enfant de ConstraintLayout (§ 5.2,
     * étendue § 7) : les ancres LUES d'abord (PE11h — annexe « ancres
     * lues »), l'édition par côtés juste après (PE8b), PUIS les réglages
     * § 7 — biais (le VERTICAL se désactive sans contraintes haut et
     * bas), pourcentage de largeur, RATIO (premier usager du contrôle)
     * et bornes de largeur.
     */
    private fun sectionContraintes(): SectionAttributs = SectionAttributs(
        TitreSection.CONTRAINTES,
        listOf(
            ControleAttribut.ContraintesLecture(Etiquette.CONTRAINTES_LUES),
            ControleAttribut.Contraintes,
            ControleAttribut.Curseur(
                "app:layout_constraintHorizontal_bias",
                Etiquette.BIAIS_HORIZONTAL,
                min = 0f,
                max = 1f,
                pas = 0.05f,
                unite = "",
                defaut = 0.5f,
            ),
            ControleAttribut.Curseur(
                "app:layout_constraintVertical_bias",
                Etiquette.BIAIS_VERTICAL,
                min = 0f,
                max = 1f,
                pas = 0.05f,
                unite = "",
                defaut = 0.5f,
                courant = false,
                requiertContraintesVerticales = true,
            ),
            ControleAttribut.Curseur(
                "app:layout_constraintWidth_percent",
                Etiquette.POURCENTAGE_LARGEUR,
                min = 0.1f,
                max = 1f,
                pas = 0.05f,
                unite = "",
                defaut = 1f,
                courant = false,
            ),
            ControleAttribut.Ratio(
                "app:layout_constraintDimensionRatio",
                Etiquette.RATIO,
                courant = false,
            ),
            ControleAttribut.Nombre(
                "app:layout_constraintWidth_min",
                Etiquette.LARGEUR_MIN_CONTRAINTE,
                "dp",
                Bornes(minimum = 0f),
                courant = false,
            ),
            ControleAttribut.Nombre(
                "app:layout_constraintWidth_max",
                Etiquette.LARGEUR_MAX_CONTRAINTE,
                "dp",
                Bornes(minimum = 0f),
                courant = false,
            ),
        ),
    )

    /**
     * Variante à DEUX paramètres (PE12c) : la famille de la vue se
     * déduit de sa CLASSE par balise — le repli des vues dégradées
     * (spec § 2.2) ; le rendu réel fournit la sienne via
     * [InfoVueRendu.famille].
     */
    public fun sections(classe: String, familleParent: FamilleParent): List<SectionAttributs> =
        sections(classe, familleParent, FamilleParent.deBalise(classe))

    /**
     * La section IDENTITÉ (PE8a : le renommage ; PE11 § 7 : + `android:tag`).
     * NON COURANTE (miroir `c:0` du champ id de la maquette v6, annexe
     * « Identité — toutes les vues » sans marque Courant) : la fiche
     * Courants plafonne à 8 sections, l'Identité s'y cache — la bascule
     * Tous et la recherche la retrouvent.
     */
    private fun sectionIdentite(): SectionAttributs = SectionAttributs(
        TitreSection.IDENTITE,
        listOf(
            ControleAttribut.Identifiant("android:id", courant = false),
            ControleAttribut.Texte("android:tag", Etiquette.TAG, courant = false),
        ),
    )

    /**
     * La section RACINE (PE11 § 7 — miroir le `sec('Racine', …)` du
     * `isRoot` de `sectionsFor` v6) : contexte tools, fond, padding,
     * ajustement système, découpage au padding, hauteur min — la racine
     * n'a NI Disposition NI Affichage (retour anticipé).
     */
    private fun sectionRacine(): SectionAttributs = SectionAttributs(
        TitreSection.RACINE,
        listOf(
            ControleAttribut.Texte(
                "tools:context",
                Etiquette.CONTEXTE,
                validation = GenreValidationTexte.NOM_ACTIVITE,
            ),
            ControleAttribut.Couleur("android:background", Etiquette.FOND_BRUT),
            ControleAttribut.Cotes("android:padding", Etiquette.PADDING),
            ControleAttribut.TriEtat("android:fitsSystemWindows", Etiquette.AJUSTER_SYSTEME, courant = false),
            ControleAttribut.TriEtat("android:clipToPadding", Etiquette.CLIP_PADDING, courant = false),
            ControleAttribut.Nombre("android:minHeight", Etiquette.HAUTEUR_MIN, "dp", courant = false),
        ),
    )

    /**
     * Les sections PROPRES à la classe (miroir les `if (k === …)` de
     * `sectionsFor`, ordre v6) : chaque famille apporte SES sections —
     * texte (Texte, Typographie, Lignes, Icônes du texte), saisie,
     * champ (Champ, Aides et compteur, Icônes, Contour), bouton, état,
     * image, barre d'outils, bouton d'action, conteneur-carte,
     * progression, liste.
     */
    private fun sectionsPropres(classe: String): List<SectionAttributs> = listOfNotNull(
        sectionTexte(classe),
        sectionTypographie(classe),
        sectionLignes(classe),
        sectionIconesTexte(classe),
        sectionSaisie(classe),
        sectionChamp(classe),
        sectionAidesCompteur(classe),
        sectionIconesChamp(classe),
        sectionContour(classe),
        sectionBouton(classe),
        sectionEtat(classe),
        sectionImage(classe),
        sectionBarreOutils(classe),
        sectionBoutonAction(classe),
        sectionProgression(classe),
        sectionListe(classe),
    )

    /**
     * La section TEXTE (TextView et descendants — PAS la saisie, miroir
     * `if (T)` v6) : les cinq courants de la 1re vague puis
     * `textAlignment` et `textAppearance` (§ 7, listes non courantes).
     */
    private fun sectionTexte(classe: String): SectionAttributs? = if (!familleTexte(classe)) {
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
                ControleAttribut.Liste(
                    "android:textAlignment",
                    Etiquette.ALIGNEMENT_TEXTE,
                    ALIGNEMENTS_TEXTE,
                    courant = false,
                ),
                ControleAttribut.Liste(
                    "android:textAppearance",
                    Etiquette.APPARENCE_TEXTE,
                    APPARENCES_TEXTE,
                    courant = false,
                ),
            ),
        )
    }

    /**
     * La section TYPOGRAPHIE (PE11 § 7 — miroir `if (T || k === 'edit')`) :
     * famille de police, graisse, majuscules, espacement, interlignes et
     * padding de police — AUCUN courant (fiche Courants économe).
     */
    private fun sectionTypographie(classe: String): SectionAttributs? =
        if (!familleTexte(classe) && !familleSaisie(classe)) {
            null
        } else {
            SectionAttributs(
                TitreSection.TYPOGRAPHIE,
                listOf(
                    ControleAttribut.Liste(
                        "android:fontFamily",
                        Etiquette.FAMILLE_POLICE,
                        FAMILLES_POLICE,
                        courant = false,
                    ),
                    ControleAttribut.Liste(
                        "android:textFontWeight",
                        Etiquette.GRAISSE_POLICE,
                        GRAISSES_POLICE,
                        courant = false,
                    ),
                    ControleAttribut.TriEtat("android:textAllCaps", Etiquette.MAJUSCULES, courant = false),
                    ControleAttribut.Curseur(
                        "android:letterSpacing",
                        Etiquette.ESPACEMENT_LETTRES,
                        min = -0.1f,
                        max = 0.5f,
                        pas = 0.01f,
                        unite = "",
                        defaut = 0f,
                        courant = false,
                    ),
                    ControleAttribut.Nombre(
                        "android:lineSpacingExtra",
                        Etiquette.INTERLIGNE,
                        "dp",
                        Bornes(0f),
                        courant = false,
                    ),
                    ControleAttribut.Nombre(
                        "android:lineSpacingMultiplier",
                        Etiquette.INTERLIGNE_MULT,
                        "",
                        Bornes(minimum = 0.5f, maximum = 3f),
                        courant = false,
                    ),
                    ControleAttribut.TriEtat("android:includeFontPadding", Etiquette.PADDING_POLICE, courant = false),
                ),
            )
        }

    /**
     * La section LIGNES ET DÉBORDEMENT (PE11 § 7 — miroir
     * `if (T || k === 'edit')`) : `maxLines` y VIT désormais (il quitte
     * la Saisie, bornes [1 à 50]), avec minLines, ellipsize, liens
     * automatiques et la taille auto — AUCUN courant.
     */
    private fun sectionLignes(classe: String): SectionAttributs? =
        if (!familleTexte(classe) && !familleSaisie(classe)) {
            null
        } else {
            SectionAttributs(
                TitreSection.LIGNES,
                listOf(
                    ControleAttribut.Nombre(
                        "android:maxLines",
                        Etiquette.LIGNES_MAX,
                        "",
                        Bornes(minimum = 1f, maximum = 50f),
                        courant = false,
                    ),
                    ControleAttribut.Nombre(
                        "android:minLines",
                        Etiquette.LIGNES_MIN,
                        "",
                        Bornes(minimum = 1f, maximum = 50f),
                        courant = false,
                    ),
                    ControleAttribut.Liste("android:ellipsize", Etiquette.ELLIPSIZE, MODES_ELLIPSIZE, courant = false),
                    ControleAttribut.Drapeaux(
                        "android:autoLink",
                        Etiquette.LIENS_AUTO,
                        DRAPEAUX_LIENS,
                        courant = false,
                    ),
                    ControleAttribut.TriEtat(
                        "android:textIsSelectable",
                        Etiquette.TEXTE_SELECTIONNABLE,
                        courant = false,
                    ),
                    ControleAttribut.Choix(
                        "android:autoSizeTextType",
                        Etiquette.TAILLE_AUTO,
                        listOf("none", "uniform"),
                        courant = false,
                    ),
                    ControleAttribut.Nombre(
                        "android:autoSizeMinTextSize",
                        Etiquette.TAILLE_AUTO_MIN,
                        "sp",
                        Bornes(1f),
                        courant = false,
                    ),
                    ControleAttribut.Nombre(
                        "android:autoSizeMaxTextSize",
                        Etiquette.TAILLE_AUTO_MAX,
                        "sp",
                        Bornes(1f),
                        courant = false,
                    ),
                ),
            )
        }

    /**
     * La section ICÔNES DU TEXTE (PE11 § 7 — miroir
     * `if (T && k !== 'switch' || k === 'edit')`) : les quatre drawables
     * du texte, leur écart et leur teinte — le Switch n'en a PAS.
     * AUCUN courant.
     */
    private fun sectionIconesTexte(classe: String): SectionAttributs? =
        if ((familleTexte(classe) && !familleInterrupteur(classe)) || familleSaisie(classe)) {
            SectionAttributs(
                TitreSection.ICONES_TEXTE,
                listOf(
                    ControleAttribut.Ressource(
                        "android:drawableStart",
                        Etiquette.DRAWABLE_DEBUT,
                        TYPES_DESSINABLE,
                        courant = false,
                    ),
                    ControleAttribut.Ressource(
                        "android:drawableEnd",
                        Etiquette.DRAWABLE_FIN,
                        TYPES_DESSINABLE,
                        courant = false,
                    ),
                    ControleAttribut.Ressource(
                        "android:drawableTop",
                        Etiquette.DRAWABLE_HAUT,
                        TYPES_DESSINABLE,
                        courant = false,
                    ),
                    ControleAttribut.Ressource(
                        "android:drawableBottom",
                        Etiquette.DRAWABLE_BAS,
                        TYPES_DESSINABLE,
                        courant = false,
                    ),
                    ControleAttribut.Nombre(
                        "android:drawablePadding",
                        Etiquette.DRAWABLE_PADDING,
                        "dp",
                        Bornes(0f),
                        courant = false,
                    ),
                    ControleAttribut.Couleur("android:drawableTint", Etiquette.DRAWABLE_TEINTE, courant = false),
                ),
            )
        } else {
            null
        }

    /**
     * La section SAISIE (EditText, TextInputEditText — miroir `if (k ===
     * 'edit')` v6) : hint, type de saisie, options IME et longueur max
     * en courants ; chiffres, remplissage auto et compagnie ensuite.
     * `maxLines` a déménagé vers LIGNES ET DÉBORDEMENT (§ 7).
     */
    private fun sectionSaisie(classe: String): SectionAttributs? = if (!familleSaisie(classe)) {
        null
    } else {
        SectionAttributs(
            TitreSection.SAISIE,
            listOf(
                ControleAttribut.Texte("android:hint", Etiquette.HINT, extrayable = true),
                ControleAttribut.TypeSaisie("android:inputType", BASES_SAISIE, MODIFICATEURS_SAISIE),
                ControleAttribut.Liste("android:imeOptions", Etiquette.OPTIONS_IME, OPTIONS_IME),
                ControleAttribut.Nombre("android:maxLength", Etiquette.LONGUEUR_MAX, "", Bornes(1f)),
                ControleAttribut.Texte("android:digits", Etiquette.CHIFFRES, courant = false),
                ControleAttribut.Liste(
                    "android:autofillHints",
                    Etiquette.SUGGESTIONS_AUTOFILL,
                    SUGGESTIONS_AUTOFILL,
                    courant = false,
                ),
                ControleAttribut.Liste(
                    "android:importantForAutofill",
                    Etiquette.IMPORTANCE_AUTOFILL,
                    IMPORTANCE_AUTOFILL,
                    courant = false,
                ),
                ControleAttribut.TriEtat(
                    "android:selectAllOnFocus",
                    Etiquette.TOUT_SELECTIONNER,
                    courant = false,
                ),
                ControleAttribut.TriEtat(
                    "android:cursorVisible",
                    Etiquette.CURSEUR_VISIBLE,
                    courant = false,
                ),
                ControleAttribut.Couleur(
                    "android:textColorHint",
                    Etiquette.COULEUR_INDICATION,
                    courant = false,
                ),
            ),
        )
    }

    /**
     * La section CHAMP (TextInputLayout — miroir `if (k === 'field')` v6) :
     * libellé, mode de fond (« none » rejoint le choix § 7), variante de
     * style et activation de l'indication.
     */
    private fun sectionChamp(classe: String): SectionAttributs? = if (classe != "TextInputLayout") {
        null
    } else {
        SectionAttributs(
            TitreSection.CHAMP,
            listOf(
                ControleAttribut.Texte("android:hint", Etiquette.LIBELLE),
                ControleAttribut.Choix("app:boxBackgroundMode", Etiquette.FOND, FONDS),
                ControleAttribut.Liste("style", Etiquette.STYLE, STYLES_CHAMP, courant = false),
                ControleAttribut.TriEtat("app:hintEnabled", Etiquette.INDICATION_ACTIVEE, courant = false),
            ),
        )
    }

    /**
     * La section AIDES ET COMPTEUR (PE11 § 7 — TextInputLayout) : texte
     * d'aide en courant, erreurs, compteur, substitution, préfixe et
     * suffixe.
     */
    private fun sectionAidesCompteur(classe: String): SectionAttributs? = if (classe != "TextInputLayout") {
        null
    } else {
        SectionAttributs(
            TitreSection.AIDES_CHAMP,
            listOf(
                ControleAttribut.Texte("app:helperText", Etiquette.TEXTE_AIDE),
                ControleAttribut.TriEtat("app:errorEnabled", Etiquette.ERREUR_ACTIVEE, courant = false),
                ControleAttribut.TriEtat("app:counterEnabled", Etiquette.COMPTEUR_ACTIVE, courant = false),
                ControleAttribut.Nombre(
                    "app:counterMaxLength",
                    Etiquette.COMPTEUR_LONGUEUR,
                    "",
                    Bornes(1f),
                    courant = false,
                ),
                ControleAttribut.Texte("app:placeholderText", Etiquette.TEXTE_SUBSTITUTION, courant = false),
                ControleAttribut.Texte("app:prefixText", Etiquette.PREFIXE, courant = false),
                ControleAttribut.Texte("app:suffixText", Etiquette.SUFFIXE, courant = false),
            ),
        )
    }

    /**
     * La section ICÔNES du champ (PE11 § 7 — TextInputLayout) : icône de
     * début, mode d'icône de fin (LISTE à cinq valeurs § 7 — miroir
     * `endIconMode` v6) et sa teinte.
     */
    private fun sectionIconesChamp(classe: String): SectionAttributs? = if (classe != "TextInputLayout") {
        null
    } else {
        SectionAttributs(
            TitreSection.ICONES,
            listOf(
                ControleAttribut.Ressource("app:startIconDrawable", Etiquette.ICONE_DEBUT, TYPES_DESSINABLE),
                ControleAttribut.Liste("app:endIconMode", Etiquette.ICONE_FIN, ICONES_FIN),
                ControleAttribut.Couleur("app:endIconTint", Etiquette.TEINTE_ICONE_FIN, courant = false),
            ),
        )
    }

    /**
     * La section CONTOUR (PE11 § 7 — TextInputLayout) : couleur et
     * épaisseur du trait, fond de boîte et les QUATRE COINS (contrôle
     * [ControleAttribut.Coins], `controle_cotes` pour layout) — AUCUN
     * courant.
     */
    private fun sectionContour(classe: String): SectionAttributs? = if (classe != "TextInputLayout") {
        null
    } else {
        SectionAttributs(
            TitreSection.CONTOUR,
            listOf(
                ControleAttribut.Couleur("app:boxStrokeColor", Etiquette.TRAIT_FOND, courant = false),
                ControleAttribut.Nombre(
                    "app:boxStrokeWidth",
                    Etiquette.EPAISSEUR_TRAIT,
                    "dp",
                    Bornes(0f),
                    courant = false,
                ),
                ControleAttribut.Couleur("app:boxBackgroundColor", Etiquette.FOND_BOITE, courant = false),
                ControleAttribut.Coins("app:boxCornerRadiusTopStart", Etiquette.COINS, courant = false),
            ),
        )
    }

    /**
     * La section BOUTON (Button/MaterialButton — miroir `if (k === 'btn')`
     * v6) : variante de style, arrondi, teinte de fond et icône en
     * courants ; gravité, écart, taille et teinte de l'icône, bordure,
     * ripple et insets ensuite (§ 7).
     */
    private fun sectionBouton(classe: String): SectionAttributs? = if (!familleBouton(classe)) {
        null
    } else {
        SectionAttributs(
            TitreSection.BOUTON,
            listOf(
                ControleAttribut.Liste("style", Etiquette.VARIANTE, STYLES_BOUTON),
                ControleAttribut.Nombre("app:cornerRadius", Etiquette.ARRONDI, "dp", Bornes(0f)),
                ControleAttribut.Couleur("app:backgroundTint", Etiquette.TEINTE_FOND),
                ControleAttribut.Ressource("app:icon", Etiquette.ICONE, TYPES_DESSINABLE),
                ControleAttribut.Liste(
                    "app:iconGravity",
                    Etiquette.GRAVITE_ICONE,
                    GRAVITES_ICONE,
                    courant = false,
                ),
                ControleAttribut.Nombre(
                    "app:iconPadding",
                    Etiquette.PADDING_ICONE,
                    "dp",
                    Bornes(0f),
                    courant = false,
                ),
                ControleAttribut.Couleur("app:iconTint", Etiquette.TEINTE_ICONE, courant = false),
                ControleAttribut.Nombre(
                    "app:iconSize",
                    Etiquette.TAILLE_ICONE,
                    "dp",
                    Bornes(0f),
                    courant = false,
                ),
                ControleAttribut.Couleur("app:strokeColor", Etiquette.COULEUR_BORDURE, courant = false),
                ControleAttribut.Nombre(
                    "app:strokeWidth",
                    Etiquette.EPAISSEUR_BORDURE,
                    "dp",
                    Bornes(0f),
                    courant = false,
                ),
                ControleAttribut.Couleur("app:rippleColor", Etiquette.COULEUR_RIPPLE, courant = false),
                ControleAttribut.Nombre("android:insetTop", Etiquette.INSET_HAUT, "dp", courant = false),
                ControleAttribut.Nombre("android:insetBottom", Etiquette.INSET_BAS, "dp", courant = false),
            ),
        )
    }

    /**
     * La section ÉTAT (CompoundButton — miroir `if (k === 'check' || k ===
     * 'switch')` v6) : la coche en courant, puis la teinte et le dessin de
     * la case (CheckBox) OU les teintes du pouce et de la piste, le texte
     * affiché et ses deux libellés (Switch).
     */
    private fun sectionEtat(classe: String): SectionAttributs? = if (!familleEtat(classe)) {
        null
    } else {
        val specifiques =
            if (classe == "CheckBox") {
                listOf(
                    ControleAttribut.Couleur("app:buttonTint", Etiquette.TEINTE_CASE),
                    ControleAttribut.Ressource("android:button", Etiquette.CASE, TYPES_DESSINABLE, courant = false),
                )
            } else {
                listOf(
                    ControleAttribut.Couleur("android:thumbTint", Etiquette.TEINTE_POUCE),
                    ControleAttribut.Couleur("android:trackTint", Etiquette.TEINTE_PISTE),
                    ControleAttribut.TriEtat("android:showText", Etiquette.AFFICHER_TEXTE, courant = false),
                    ControleAttribut.Texte("android:textOn", Etiquette.TEXTE_ACTIF, courant = false),
                    ControleAttribut.Texte("android:textOff", Etiquette.TEXTE_INACTIF, courant = false),
                )
            }
        SectionAttributs(
            TitreSection.ETAT,
            listOf(ControleAttribut.TriEtat("android:checked", Etiquette.COCHE)) + specifiques,
        )
    }

    /**
     * La section IMAGE (ImageView/ImageButton — miroir
     * `if (k === 'image' || k === 'logo')` v6) : source et échelle en
     * courants ; srcCompat, ajustement des bornes, teinte et son mode,
     * découpage au padding, bornes max ensuite (§ 7 — les maxWidth/
     * maxHeight quittent la Disposition de la famille image).
     */
    private fun sectionImage(classe: String): SectionAttributs? = if (!familleImage(classe)) {
        null
    } else {
        SectionAttributs(
            TitreSection.IMAGE,
            listOf(
                ControleAttribut.Ressource("android:src", Etiquette.SOURCE, TYPES_DESSINABLE),
                ControleAttribut.Ressource(
                    "app:srcCompat",
                    Etiquette.SRC_COMPAT,
                    TYPES_DESSINABLE,
                    courant = false,
                ),
                ControleAttribut.Liste("android:scaleType", Etiquette.TYPE_ECHELLE, TYPES_ECHELLE),
                ControleAttribut.TriEtat("android:adjustViewBounds", Etiquette.AJUSTER_BORNES, courant = false),
                ControleAttribut.Couleur("app:tint", Etiquette.TEINTE, courant = false),
                ControleAttribut.Liste("android:tintMode", Etiquette.MODE_TEINTE, MODES_TEINTE, courant = false),
                ControleAttribut.TriEtat("android:cropToPadding", Etiquette.ROGNER_PADDING, courant = false),
                ControleAttribut.Nombre(
                    "android:maxWidth",
                    Etiquette.LARGEUR_MAX,
                    "dp",
                    Bornes(0f),
                    courant = false,
                ),
                ControleAttribut.Nombre(
                    "android:maxHeight",
                    Etiquette.HAUTEUR_MAX,
                    "dp",
                    Bornes(0f),
                    courant = false,
                ),
            ),
        )
    }

    // ── PE12c — § 5.1 : sections du conteneur par famille ────────────

    /**
     * Les sections du CONTENEUR (spec § 5.1, miroir `containerSecs`) :
     * la vue sélectionnée EST un parent — SA famille décide de la
     * section. MaterialCardView garde SA section Carte, inchangée
     * (§ 5.1 : « + Carte (existant) »).
     */
    private fun sectionsConteneur(classe: String, familleVue: FamilleParent, racine: Boolean): List<SectionAttributs> {
        val conteneur =
            when (familleVue) {
                FamilleParent.LINEAR -> sectionConteneurLineaire(racine)

                FamilleParent.FRAME -> sectionConteneurFrame(racine)

                FamilleParent.RELATIVE -> sectionConteneurRelatif(racine)

                FamilleParent.COORDINATOR -> sectionConteneurCoordonnateur(racine)

                FamilleParent.APPBAR -> listOf(sectionBarreApplication(racine))

                FamilleParent.AUCUN,
                FamilleParent.CONSTRAINT,
                FamilleParent.AUTRE,
                -> emptyList()
            }
        return conteneur + sectionCarte(classe)
    }

    /** LINEAR (§ 5.1, miroir le Conteneur étendu de la maquette) : 11 attributs. */
    private fun sectionConteneurLineaire(racine: Boolean): List<SectionAttributs> = listOf(
        SectionAttributs(
            TitreSection.CONTENEUR,
            listOf(
                ControleAttribut.Choix("android:orientation", Etiquette.ORIENTATION, ORIENTATIONS),
                ControleAttribut.Drapeaux("android:gravity", Etiquette.GRAVITE, GRAVITES),
                ControleAttribut.TriEtat("android:baselineAligned", Etiquette.BASELINE_ALIGNE, courant = false),
                ControleAttribut.Nombre("android:weightSum", Etiquette.SOMME_POIDS, "", courant = false),
                ControleAttribut.Drapeaux(
                    "android:showDividers",
                    Etiquette.SHOW_DIVIDERS,
                    SEPARATEURS,
                    courant = false,
                ),
                ControleAttribut.Ressource(
                    "android:divider",
                    Etiquette.SEPARATEUR,
                    TYPES_DESSINABLE,
                    courant = false,
                ),
                ControleAttribut.Nombre(
                    "android:dividerPadding",
                    Etiquette.SEPARATEUR_PADDING,
                    "dp",
                    courant = false,
                ),
                ControleAttribut.TriEtat(
                    "android:measureWithLargestChild",
                    Etiquette.MESURER_PLUS_GRAND,
                    courant = false,
                ),
                ControleAttribut.TriEtat("android:clipChildren", Etiquette.CLIP_ENFANTS, courant = false),
                ControleAttribut.TriEtat("android:clipToPadding", Etiquette.CLIP_PADDING, courant = false),
                ControleAttribut.TriEtat(
                    "android:animateLayoutChanges",
                    Etiquette.ANIMER_MISE_EN_PAGE,
                    courant = false,
                ),
            ).sansDoublesRacine(racine),
        ),
    )

    /** FRAME (§ 5.1) : gravité d'avant-plan, mesure, découpage. */
    private fun sectionConteneurFrame(racine: Boolean): List<SectionAttributs> = listOf(
        SectionAttributs(
            TitreSection.CONTENEUR,
            listOf(
                ControleAttribut.Drapeaux(
                    "android:foregroundGravity",
                    Etiquette.GRAVITE_AVANT_PLAN,
                    GRAVITES,
                    courant = false,
                ),
                ControleAttribut.TriEtat(
                    "android:measureAllChildren",
                    Etiquette.MESURER_TOUS_ENFANTS,
                    courant = false,
                ),
                ControleAttribut.TriEtat("android:clipChildren", Etiquette.CLIP_ENFANTS, courant = false),
                ControleAttribut.TriEtat("android:clipToPadding", Etiquette.CLIP_PADDING, courant = false),
            ).sansDoublesRacine(racine),
        ),
    )

    /** RELATIVE (§ 5.1) : gravité, gravité ignorée (référence, sous-arbre). */
    private fun sectionConteneurRelatif(racine: Boolean): List<SectionAttributs> = listOf(
        SectionAttributs(
            TitreSection.CONTENEUR,
            listOf(
                ControleAttribut.Drapeaux("android:gravity", Etiquette.GRAVITE, GRAVITES),
                ControleAttribut.ReferenceId(
                    "android:ignoreGravity",
                    Etiquette.IGNORER_GRAVITE,
                    ControleAttribut.ReferenceId.Portee.SOUS_ARBRE,
                    courant = false,
                ),
                ControleAttribut.TriEtat("android:clipChildren", Etiquette.CLIP_ENFANTS, courant = false),
                ControleAttribut.TriEtat("android:clipToPadding", Etiquette.CLIP_PADDING, courant = false),
            ).sansDoublesRacine(racine),
        ),
    )

    /** COORDINATOR (§ 5.1) : ajustement système, fond de barre d'état. */
    private fun sectionConteneurCoordonnateur(racine: Boolean): List<SectionAttributs> = listOf(
        SectionAttributs(
            TitreSection.CONTENEUR,
            listOf(
                ControleAttribut.TriEtat("android:fitsSystemWindows", Etiquette.AJUSTER_SYSTEME),
                ControleAttribut.Ressource(
                    "app:statusBarBackground",
                    Etiquette.FOND_BARRE_ETAT,
                    TYPES_FOND_BARRE,
                    courant = false,
                ),
                ControleAttribut.TriEtat("android:clipChildren", Etiquette.CLIP_ENFANTS, courant = false),
            ).sansDoublesRacine(racine),
        ),
    )

    /** APPBAR (§ 5.1) : la section « Barre d'application » du parent lui-même. */
    private fun sectionBarreApplication(racine: Boolean): SectionAttributs = SectionAttributs(
        TitreSection.BARRE_APPLICATION,
        listOf(
            ControleAttribut.TriEtat("app:liftOnScroll", Etiquette.ELEVER_AU_DEFILEMENT),
            ControleAttribut.ReferenceId(
                "app:liftOnScrollTargetViewId",
                Etiquette.CIBLE_ELEVEE,
                ControleAttribut.ReferenceId.Portee.SOUS_ARBRE,
                courant = false,
            ),
            ControleAttribut.TriEtat("android:fitsSystemWindows", Etiquette.AJUSTER_SYSTEME, courant = false),
            ControleAttribut.TriEtat("app:expanded", Etiquette.ETENDU, courant = false),
        ).sansDoublesRacine(racine),
    )

    /**
     * La section CARTE de MaterialCardView (§ 5.1 : « + Carte (existant) »
     * étendu § 7) : fond, arrondi et élévation en courants, puis variante
     * de style, bordure, ripple, padding de contenu et réglages de
     * compatibilité.
     */
    private fun sectionCarte(classe: String): List<SectionAttributs> = if (classe != "MaterialCardView") {
        emptyList()
    } else {
        listOf(
            SectionAttributs(
                TitreSection.CARTE,
                listOf(
                    ControleAttribut.Couleur("app:cardBackgroundColor", Etiquette.FOND_CARTE),
                    ControleAttribut.Nombre("app:cardCornerRadius", Etiquette.ARRONDI, "dp", Bornes(0f)),
                    ControleAttribut.Nombre("app:cardElevation", Etiquette.ELEVATION, "dp", Bornes(0f)),
                    ControleAttribut.Liste("style", Etiquette.STYLE, STYLES_CARTE, courant = false),
                    ControleAttribut.Couleur("app:strokeColor", Etiquette.COULEUR_BORDURE, courant = false),
                    ControleAttribut.Nombre(
                        "app:strokeWidth",
                        Etiquette.EPAISSEUR_BORDURE,
                        "dp",
                        Bornes(0f),
                        courant = false,
                    ),
                    ControleAttribut.Couleur("app:rippleColor", Etiquette.COULEUR_RIPPLE, courant = false),
                    ControleAttribut.Nombre(
                        "app:contentPadding",
                        Etiquette.PADDING_CONTENU,
                        "dp",
                        Bornes(0f),
                        courant = false,
                    ),
                    ControleAttribut.TriEtat("app:cardUseCompatPadding", Etiquette.PADDING_COMPAT, courant = false),
                    ControleAttribut.TriEtat(
                        "app:cardPreventCornerOverlap",
                        Etiquette.EMPECHER_CHEVAUCHEMENT,
                        courant = false,
                    ),
                    ControleAttribut.TriEtat("android:checkable", Etiquette.COCHABLE, courant = false),
                ),
            ),
        )
    }

    // ── PE12c — § 5.2 : sections « Dans le parent » par famille ───────

    /** FRAME (§ 5.2) : la gravité seule. */
    private fun sectionDansParentFrame(): SectionAttributs = SectionAttributs(
        TitreSection.DANS_PARENT,
        listOf(
            ControleAttribut.Drapeaux("android:layout_gravity", Etiquette.GRAVITE_PARENT, GRAVITES),
        ),
    )

    /**
     * La section DANS PARENT d'un enfant LINÉAIRE (miroir le `genre ===
     * 'lin'` de la maquette) : poids, gravité de mise en page, puis
     * l'ACTION RAPIDE groupée « Remplir l'espace restant » (PE11h —
     * annexe § 7 : `—` ActionRapide, courant).
     */
    private fun sectionDansParentLineaire(): SectionAttributs = SectionAttributs(
        TitreSection.DANS_PARENT,
        listOf(
            ControleAttribut.Nombre("android:layout_weight", Etiquette.POIDS, ""),
            ControleAttribut.Drapeaux("android:layout_gravity", Etiquette.GRAVITE_PARENT, GRAVITES),
            ControleAttribut.ActionRapide(
                listOf(
                    ControleAttribut.ActionRapide.Action(
                        Etiquette.REMPLIR_ESPACE,
                        ControleAttribut.ActionRapide.Ecriture.RemplirEspaceRestant,
                    ),
                ),
            ),
        ),
    )

    /** RELATIVE (§ 5.2) : l'ANCRAGE AU PARENT (7 bascules) + alignWithParentIfMissing. */
    private fun sectionDansParentRelatif(): SectionAttributs = SectionAttributs(
        TitreSection.DANS_PARENT,
        listOf(
            ControleAttribut.Bascules(
                Etiquette.ANCRAGE_PARENT,
                listOf(
                    ControleAttribut.Bascules.Entree("android:layout_alignParentStart", Etiquette.ANCRAGE_DEBUT),
                    ControleAttribut.Bascules.Entree("android:layout_alignParentEnd", Etiquette.ANCRAGE_FIN),
                    ControleAttribut.Bascules.Entree("android:layout_alignParentTop", Etiquette.ANCRAGE_HAUT),
                    ControleAttribut.Bascules.Entree("android:layout_alignParentBottom", Etiquette.ANCRAGE_BAS),
                    ControleAttribut.Bascules.Entree("android:layout_centerHorizontal", Etiquette.ANCRAGE_CENTRE_H),
                    ControleAttribut.Bascules.Entree("android:layout_centerVertical", Etiquette.ANCRAGE_CENTRE_V),
                    ControleAttribut.Bascules.Entree("android:layout_centerInParent", Etiquette.ANCRAGE_CENTRE),
                ),
            ),
            ControleAttribut.TriEtat(
                "android:layout_alignWithParentIfMissing",
                Etiquette.ALIGNER_SI_ABSENT,
                courant = false,
            ),
        ),
    )

    /** RELATIVE (§ 5.2) : les 9 règles vers un frère — portée FRÈRES (miroir REL_V + REL_H). */
    private fun sectionPositionsRelatives(): SectionAttributs = SectionAttributs(
        TitreSection.POSITIONS_RELATIVES,
        listOf(
            ControleAttribut.ReferenceId(
                "android:layout_below",
                Etiquette.DESSOUS_DE,
                ControleAttribut.ReferenceId.Portee.FRERES,
            ),
            ControleAttribut.ReferenceId(
                "android:layout_above",
                Etiquette.DESSUS_DE,
                ControleAttribut.ReferenceId.Portee.FRERES,
                courant = false,
            ),
            ControleAttribut.ReferenceId(
                "android:layout_alignTop",
                Etiquette.ALIGNE_HAUT_AVEC,
                ControleAttribut.ReferenceId.Portee.FRERES,
                courant = false,
            ),
            ControleAttribut.ReferenceId(
                "android:layout_alignBottom",
                Etiquette.ALIGNE_BAS_AVEC,
                ControleAttribut.ReferenceId.Portee.FRERES,
                courant = false,
            ),
            ControleAttribut.ReferenceId(
                "android:layout_alignBaseline",
                Etiquette.LIGNE_BASE_AVEC,
                ControleAttribut.ReferenceId.Portee.FRERES,
                courant = false,
            ),
            ControleAttribut.ReferenceId(
                "android:layout_toEndOf",
                Etiquette.A_DROITE_DE,
                ControleAttribut.ReferenceId.Portee.FRERES,
            ),
            ControleAttribut.ReferenceId(
                "android:layout_toStartOf",
                Etiquette.A_GAUCHE_DE,
                ControleAttribut.ReferenceId.Portee.FRERES,
                courant = false,
            ),
            ControleAttribut.ReferenceId(
                "android:layout_alignStart",
                Etiquette.ALIGNE_DEBUT_AVEC,
                ControleAttribut.ReferenceId.Portee.FRERES,
                courant = false,
            ),
            ControleAttribut.ReferenceId(
                "android:layout_alignEnd",
                Etiquette.ALIGNE_FIN_AVEC,
                ControleAttribut.ReferenceId.Portee.FRERES,
                courant = false,
            ),
        ),
        note = NoteSection.REGLES_SOEURS,
    )

    /** COORDINATOR (§ 5.2) : gravité, ancre (+ sa gravité), comportement, bords, keyline. */
    private fun sectionDansParentCoordonnateur(): SectionAttributs = SectionAttributs(
        TitreSection.DANS_PARENT,
        listOf(
            ControleAttribut.Drapeaux("android:layout_gravity", Etiquette.GRAVITE_PARENT, GRAVITES),
            ControleAttribut.ReferenceId(
                "app:layout_anchor",
                Etiquette.ANCRE,
                ControleAttribut.ReferenceId.Portee.SOUS_ARBRE,
            ),
            ControleAttribut.Drapeaux(
                "app:layout_anchorGravity",
                Etiquette.GRAVITE_ANCRE,
                GRAVITES,
                requiert = "app:layout_anchor",
                courant = false,
            ),
            ControleAttribut.Liste("app:layout_behavior", Etiquette.COMPORTEMENT, COMPORTEMENTS),
            ControleAttribut.Liste("app:layout_insetEdge", Etiquette.BORD_INSET, BORDS_INSET, courant = false),
            ControleAttribut.Drapeaux(
                "app:layout_dodgeInsetEdges",
                Etiquette.ESQUIVER_BORDS,
                DRAPEAUX_ESQUIVE,
                courant = false,
            ),
            ControleAttribut.Nombre("app:layout_keyline", Etiquette.KEYLINE, "", courant = false),
        ),
    )

    /** APPBAR (§ 5.2) : drapeaux de défilement, interpolateur, gravité. */
    private fun sectionDansParentBarre(): SectionAttributs = SectionAttributs(
        TitreSection.DANS_PARENT,
        listOf(
            ControleAttribut.Drapeaux("app:layout_scrollFlags", Etiquette.DRAPEAUX_DEFILEMENT, DRAPEAUX_DEFILEMENT),
            ControleAttribut.Texte(
                "app:layout_scrollInterpolator",
                Etiquette.INTERPOLATEUR_DEFILEMENT,
                courant = false,
            ),
            ControleAttribut.Drapeaux(
                "android:layout_gravity",
                Etiquette.GRAVITE_PARENT,
                GRAVITES,
                courant = false,
            ),
        ),
    )

    // ── PE12c — § 5.4 : sections propres des nouvelles classes ───────

    /**
     * La section BARRE D'OUTILS de MaterialToolbar (§ 5.4) PUIS de la
     * Toolbar androidx (v0.170.0, S4) : les mêmes attributs `app:` — la
     * classe androidx les lit à l'identique (elle est la base de
     * MaterialToolbar).
     */
    private fun sectionBarreOutils(classe: String): SectionAttributs? =
        if (classe != "MaterialToolbar" && classe != "Toolbar") {
            null
        } else {
            SectionAttributs(
                TitreSection.BARRE_OUTILS,
                listOf(
                    ControleAttribut.Texte("app:title", Etiquette.TITRE, extrayable = true),
                    ControleAttribut.Texte("app:subtitle", Etiquette.SOUS_TITRE, extrayable = true, courant = false),
                    ControleAttribut.Ressource("app:navigationIcon", Etiquette.ICONE_NAVIGATION, TYPES_DESSINABLE),
                    ControleAttribut.TriEtat("app:titleCentered", Etiquette.TITRE_CENTRE, courant = false),
                    ControleAttribut.Couleur("app:titleTextColor", Etiquette.COULEUR_TITRE, courant = false),
                    ControleAttribut.Ressource("app:menu", Etiquette.MENU, listOf("menu"), courant = false),
                ),
            )
        }

    /** La section BOUTON D'ACTION du FloatingActionButton (§ 5.4). */
    private fun sectionBoutonAction(classe: String): SectionAttributs? = if (classe != "FloatingActionButton") {
        null
    } else {
        SectionAttributs(
            TitreSection.BOUTON_ACTION,
            listOf(
                ControleAttribut.Ressource("app:srcCompat", Etiquette.SRC_COMPAT, TYPES_DESSINABLE),
                ControleAttribut.Couleur("app:backgroundTint", Etiquette.TEINTE_FOND),
                ControleAttribut.Choix("app:fabSize", Etiquette.TAILLE_FAB, TAILLES_FAB),
                ControleAttribut.Couleur("app:tint", Etiquette.TEINTE, courant = false),
                ControleAttribut.Nombre("app:elevation", Etiquette.ELEVATION, "dp", courant = false),
                ControleAttribut.Nombre("app:borderWidth", Etiquette.LARGEUR_BORDURE, "dp", courant = false),
                ControleAttribut.Couleur("app:rippleColor", Etiquette.COULEUR_RIPPLE, courant = false),
                ControleAttribut.TriEtat("app:useCompatPadding", Etiquette.PADDING_COMPAT, courant = false),
            ),
        )
    }

    /**
     * La section PROGRESSION (ProgressBar/SeekBar — miroir v6 § 7, PUIS
     * RatingBar v0.166.3 : la barre à étoiles porte max/progress comme
     * ses sœurs, sans les variantes indéterminées) : max et progression
     * en courants, minimum et progression secondaire ensuite ; puis les
     * teintes (ProgressBar) ou le pouce et son repère (SeekBar).
     */
    private fun sectionProgression(classe: String): SectionAttributs? {
        if (classe != "ProgressBar" && classe != "SeekBar" && classe != "RatingBar") return null
        val controles =
            mutableListOf<ControleAttribut>(
                ControleAttribut.Nombre("android:max", Etiquette.MAXIMUM, "", Bornes(1f)),
                ControleAttribut.Curseur(
                    "android:progress",
                    Etiquette.PROGRESSION,
                    min = 0f,
                    max = 100f,
                    pas = 1f,
                    unite = "",
                    defaut = 0f,
                    maxDe = "android:max",
                ),
                ControleAttribut.Nombre("android:min", Etiquette.MINIMUM, "", Bornes(0f), courant = false),
                ControleAttribut.Nombre(
                    "android:secondaryProgress",
                    Etiquette.PROGRESSION_SECONDAIRE,
                    "",
                    Bornes(0f),
                    courant = false,
                ),
            )
        if (classe == "ProgressBar") {
            controles += ControleAttribut.TriEtat("android:indeterminate", Etiquette.INDETERMINE)
            controles += ControleAttribut.Couleur("android:progressTint", Etiquette.TEINTE_PROGRESSION)
            controles +=
                ControleAttribut.Couleur(
                    "android:progressBackgroundTint",
                    Etiquette.TEINTE_FOND_PROGRESSION,
                    courant = false,
                )
            controles +=
                ControleAttribut.Couleur(
                    "android:indeterminateTint",
                    Etiquette.TEINTE_INDETERMINEE,
                    courant = false,
                )
        } else if (classe == "SeekBar") {
            controles += ControleAttribut.Couleur("android:thumbTint", Etiquette.TEINTE_POUCE)
            controles += ControleAttribut.Couleur("android:progressTint", Etiquette.TEINTE_PROGRESSION)
            controles += ControleAttribut.Ressource("android:thumb", Etiquette.POUCE, TYPES_DESSINABLE, courant = false)
            controles += ControleAttribut.TriEtat("android:splitTrack", Etiquette.PISTE_FENDUE, courant = false)
            controles +=
                ControleAttribut.Ressource("android:tickMark", Etiquette.REPERE, TYPES_DESSINABLE, courant = false)
        }
        return SectionAttributs(TitreSection.PROGRESSION, controles)
    }

    /**
     * La section LISTE (RecyclerView — miroir `if (k === 'recycler')` v6) :
     * l'élément d'aperçu devient RESSOURCE @layout et le gestionnaire
     * devient LISTE pleinement qualifiée (l'affiche retient le DERNIER
     * segment) ; orientation, colonnes, sens, empilement, défiling et
     * compagnie ensuite (§ 7).
     */
    private fun sectionListe(classe: String): SectionAttributs? = if (classe != "RecyclerView") {
        null
    } else {
        SectionAttributs(
            TitreSection.LISTE,
            listOf(
                ControleAttribut.Ressource("tools:listitem", Etiquette.ITEM_LISTE, listOf("layout")),
                ControleAttribut.Liste("app:layoutManager", Etiquette.GESTIONNAIRE, GESTIONNAIRES_LISTE),
                ControleAttribut.Choix(
                    "android:orientation",
                    Etiquette.ORIENTATION,
                    ORIENTATIONS,
                    courant = false,
                ),
                ControleAttribut.Nombre(
                    "app:spanCount",
                    Etiquette.NOMBRE_COLONNES,
                    "",
                    Bornes(minimum = 1f, maximum = 12f),
                    courant = false,
                ),
                ControleAttribut.TriEtat("app:reverseLayout", Etiquette.INVERSER, courant = false),
                ControleAttribut.TriEtat("app:stackFromEnd", Etiquette.EMPILER_FIN, courant = false),
                ControleAttribut.TriEtat("android:clipToPadding", Etiquette.CLIP_PADDING, courant = false),
                ControleAttribut.Drapeaux("android:scrollbars", Etiquette.SCROLLBARS, BARRES_DEFILEMENT),
                ControleAttribut.Liste(
                    "android:overScrollMode",
                    Etiquette.DEBORDEMENT,
                    MODES_DEBORDEMENT,
                    courant = false,
                ),
                ControleAttribut.TriEtat(
                    "android:nestedScrollingEnabled",
                    Etiquette.DEFILEMENT_IMBRIQUE,
                    courant = false,
                ),
            ),
        )
    }

    /**
     * Les sections GÉNÉRIQUES (PE11 § 7 — toutes vues non racine,
     * miroir le bloc « communs » de `sectionsFor` v6) : Disposition,
     * Apparence, Affichage (réduit à visibilité et alpha — `enabled`
     * part en Interaction, `contentDescription` en Accessibilité),
     * Transformation, Interaction, Accessibilité puis Aperçu uniquement
     * (note dédiée) — une fonction par section (règle 8).
     */
    private fun generiques(classe: String): List<SectionAttributs> = listOf(
        sectionDisposition(classe),
        sectionApparence(),
        SectionAttributs(
            TitreSection.AFFICHAGE,
            listOf(
                ControleAttribut.Choix("android:visibility", Etiquette.VISIBILITE, VISIBILITES),
                ControleAttribut.Curseur(
                    "android:alpha",
                    Etiquette.ALPHA,
                    min = 0f,
                    max = 1f,
                    pas = 0.05f,
                    unite = "",
                    defaut = 1f,
                ),
            ),
        ),
        sectionTransformation(),
        sectionInteraction(),
        sectionAccessibilite(),
        SectionAttributs(
            TitreSection.APERCU,
            controlesApercu(classe),
            note = NoteSection.OUTILS_APERCU,
        ),
    )

    /**
     * La section DISPOSITION étendue § 7 : les composites 1re vague puis
     * hauteur/largeur min — SANS les max* pour la famille image (ils
     * vivent dans sa section Image, miroir le filtre /max/ v6).
     */
    private fun sectionDisposition(classe: String): SectionAttributs = SectionAttributs(
        TitreSection.DISPOSITION,
        listOf(
            ControleAttribut.Dimensions,
            ControleAttribut.Cotes("android:layout_margin", Etiquette.MARGES),
            ControleAttribut.Cotes("android:padding", Etiquette.PADDING),
            ControleAttribut.Nombre("android:minHeight", Etiquette.HAUTEUR_MIN, "dp", courant = false),
            ControleAttribut.Nombre(
                "android:minWidth",
                Etiquette.LARGEUR_MIN,
                "dp",
                Bornes(minimum = 0f),
                courant = false,
            ),
        ) +
            if (familleImage(classe)) {
                emptyList()
            } else {
                listOf(
                    ControleAttribut.Nombre(
                        "android:maxWidth",
                        Etiquette.LARGEUR_MAX,
                        "dp",
                        Bornes(minimum = 0f),
                        courant = false,
                    ),
                    ControleAttribut.Nombre(
                        "android:maxHeight",
                        Etiquette.HAUTEUR_MAX,
                        "dp",
                        Bornes(minimum = 0f),
                        courant = false,
                    ),
                )
            },
    )

    /** La section APPARENCE § 7 : fond, teinte, avant-plan, élévation, découpe, thème — AUCUN courant. */
    private fun sectionApparence(): SectionAttributs = SectionAttributs(
        TitreSection.APPARENCE,
        listOf(
            ControleAttribut.Couleur("android:background", Etiquette.FOND_BRUT, courant = false),
            ControleAttribut.Couleur("android:backgroundTint", Etiquette.TEINTE_FOND, courant = false),
            ControleAttribut.Ressource(
                "android:foreground",
                Etiquette.AVANT_PLAN,
                TYPES_DESSINABLE,
                courant = false,
            ),
            ControleAttribut.Curseur(
                "android:elevation",
                Etiquette.ELEVATION_BRUTE,
                min = 0f,
                max = 24f,
                pas = 1f,
                unite = "dp",
                defaut = 0f,
                courant = false,
            ),
            ControleAttribut.TriEtat("android:clipToOutline", Etiquette.DECOUPE_CONTOUR, courant = false),
            ControleAttribut.Ressource("android:theme", Etiquette.THEME, TYPES_STYLE, courant = false),
        ),
    )

    /** La section TRANSFORMATION § 7 : rotation, échelles, translations — six CURSEURS, AUCUN courant. */
    private fun sectionTransformation(): SectionAttributs = SectionAttributs(
        TitreSection.TRANSFORMATION,
        listOf(
            ControleAttribut.Curseur(
                "android:rotation",
                Etiquette.ROTATION,
                min = -180f,
                max = 180f,
                pas = 1f,
                unite = "",
                defaut = 0f,
                courant = false,
            ),
            ControleAttribut.Curseur(
                "android:scaleX",
                Etiquette.ECHELLE_X,
                min = 0.1f,
                max = 3f,
                pas = 0.05f,
                unite = "",
                defaut = 1f,
                courant = false,
            ),
            ControleAttribut.Curseur(
                "android:scaleY",
                Etiquette.ECHELLE_Y,
                min = 0.1f,
                max = 3f,
                pas = 0.05f,
                unite = "",
                defaut = 1f,
                courant = false,
            ),
        ) + translations(),
    )

    /** Les trois TRANSLATIONS (§ 7) : ±100 dp sur les axes, 0 à 24 dp en profondeur — défaut 0, jamais courants. */
    private fun translations(): List<ControleAttribut> = listOf(
        ControleAttribut.Curseur(
            "android:translationX",
            Etiquette.TRANSLATION_X,
            min = -100f,
            max = 100f,
            pas = 1f,
            unite = "dp",
            defaut = 0f,
            courant = false,
        ),
        ControleAttribut.Curseur(
            "android:translationY",
            Etiquette.TRANSLATION_Y,
            min = -100f,
            max = 100f,
            pas = 1f,
            unite = "dp",
            defaut = 0f,
            courant = false,
        ),
        ControleAttribut.Curseur(
            "android:translationZ",
            Etiquette.TRANSLATION_Z,
            min = 0f,
            max = 24f,
            pas = 1f,
            unite = "dp",
            defaut = 0f,
            courant = false,
        ),
    )

    /** La section INTERACTION § 7 : enabled et onClick (validé) en courants, le reste en tri-états. */
    private fun sectionInteraction(): SectionAttributs = SectionAttributs(
        TitreSection.INTERACTION,
        listOf(
            ControleAttribut.TriEtat("android:enabled", Etiquette.ACTIF),
            ControleAttribut.TriEtat("android:clickable", Etiquette.CLIQUABLE, courant = false),
            ControleAttribut.TriEtat("android:focusable", Etiquette.FOCUSABLE, courant = false),
            ControleAttribut.TriEtat(
                "android:focusableInTouchMode",
                Etiquette.FOCUSABLE_TACTILE,
                courant = false,
            ),
            ControleAttribut.TriEtat("android:longClickable", Etiquette.CLIQUABLE_LONG, courant = false),
            ControleAttribut.Texte(
                "android:onClick",
                Etiquette.AU_CLIC,
                validation = GenreValidationTexte.NOM_METHODE_JAVA,
            ),
        ),
    )

    /**
     * La section ACCESSIBILITÉ § 7 : description courante, importance,
     * titre de section, étiquette liée (référence d'id portée TOUT) et
     * infobulle.
     */
    private fun sectionAccessibilite(): SectionAttributs = SectionAttributs(
        TitreSection.ACCESSIBILITE,
        listOf(
            ControleAttribut.Texte("android:contentDescription", Etiquette.DESCRIPTION, extrayable = true),
            ControleAttribut.Liste(
                "android:importantForAccessibility",
                Etiquette.IMPORTANCE_ACCESSIBILITE,
                IMPORTANCE_ACCESSIBILITE,
                courant = false,
            ),
            ControleAttribut.TriEtat(
                "android:accessibilityHeading",
                Etiquette.TITRE_ACCESSIBILITE,
                courant = false,
            ),
            ControleAttribut.ReferenceId(
                "android:labelFor",
                Etiquette.LIBELLE_POUR,
                ControleAttribut.ReferenceId.Portee.TOUT,
                courant = false,
            ),
            ControleAttribut.Texte("android:tooltipText", Etiquette.INFOBULLE, courant = false),
            ControleAttribut.TriEtat(
                "android:screenReaderFocusable",
                Etiquette.LECTEUR_ECRAN,
                courant = false,
            ),
        ),
    )

    /**
     * Les contrôles de la section APERÇU UNIQUEMENT (miroir le `tl` de
     * `sectionsFor` v6) : la PREMIÈRE entrée suit la classe — `tools:text`
     * pour la famille texte, `tools:src` pour l'image, `tools:itemCount`
     * pour RecyclerView — puis `tools:visibility` et `tools:ignore` pour
     * toutes. Seule la première est COURANTE.
     */
    private fun controlesApercu(classe: String): List<ControleAttribut> = listOfNotNull(
        if (familleTexte(classe)) {
            ControleAttribut.Texte("tools:text", Etiquette.TOOLS_TEXTE)
        } else {
            null
        },
        if (familleImage(classe)) {
            ControleAttribut.Ressource("tools:src", Etiquette.TOOLS_SRC, TYPES_DESSINABLE)
        } else {
            null
        },
        if (classe == "RecyclerView") {
            ControleAttribut.Nombre(
                "tools:itemCount",
                Etiquette.NOMBRE_ITEMS,
                "",
                Bornes(minimum = 1f, maximum = 12f),
            )
        } else {
            null
        },
        ControleAttribut.Choix("tools:visibility", Etiquette.TOOLS_VISIBILITE, VISIBILITES, courant = false),
        ControleAttribut.Texte("tools:ignore", Etiquette.TOOLS_IGNORE, courant = false),
    )

    // ── Familles par héritage (miroir § 4.1) ─────────────────────────────

    /**
     * Les NOMS d'attributs CONNUS du catalogue pour une vue (PE11h —
     * miroir `knownNames` de la maquette) : les noms portés par les
     * contrôles de ses sections, PLUS les cinq fixes que les composites
     * couvrent sans les porter (`id`, dimensions, marges, padding). La
     * section « Autres attributs » y oppose les attributs du DOCUMENT :
     * tout nom HORS de cet ensemble est une rangée libre.
     */
    public fun nomsConnus(classe: String, familleParent: FamilleParent, familleVue: FamilleParent): Set<String> =
        sections(classe, familleParent, familleVue)
            .flatMap { section -> section.controles.flatMap { controle -> controle.nomsAttributs } }
            .toSet() + NOMS_FIXES

    /**
     * Les cinq attributs couverts par les COMPOSITES sans noms portés
     * (miroir la liste `[…].forEach(x => s.add(x))` de `knownNames`).
     */
    private val NOMS_FIXES: Set<String> =
        setOf(
            "android:id",
            "android:layout_width",
            "android:layout_height",
            "android:layout_margin",
            "android:padding",
        )

    /**
     * Les NOMS suggérés par la complétion de l'ajout libre (PE11h —
     * miroir `FREE_NAMES` de la maquette : des attributs réels hors
     * catalogue 1re vague, en exemple pour guider la saisie).
     */
    public val NOMS_LIBRES_SUGGERES: List<String> =
        listOf(
            "android:tag",
            "android:textDirection",
            "android:layoutDirection",
            "android:fadingEdge",
            "android:saveEnabled",
            "android:soundEffectsEnabled",
            "android:hapticFeedbackEnabled",
            "android:keepScreenOn",
            "android:requiresFadingEdge",
            "android:transitionName",
            "app:layout_goneMarginTop",
            "app:layout_constraintHorizontal_chainStyle",
        )

    /** TextView et ses descendants à TEXTE éditable (Button/CheckBox/Switch). */
    private fun familleTexte(classe: String): Boolean = classe == "TextView" ||
        familleBouton(classe) ||
        familleEtat(classe)

    /** EditText, TextInputEditText et AutoCompleteTextView (v0.166.3) ; MultiAutoCompleteTextView (v0.170.0, S4). */
    private fun familleSaisie(classe: String): Boolean = classe == "EditText" || classe == "TextInputEditText" ||
        classe == "AutoCompleteTextView" || classe == "MultiAutoCompleteTextView"

    /** Button et MaterialButton. */
    private fun familleBouton(classe: String): Boolean = classe == "Button" || classe == "MaterialButton"

    /**
     * CompoundButton et ses descendants (CheckBox, Switch, SwitchMaterial)
     * PUIS les extensions v0.166.3 de la palette (RadioButton, ToggleButton,
     * Chip — sous-classe d'AppCompatCheckBox) : la section État porte
     * `android:checked`, commun aux quatre. v0.170.0 (S4) : CheckedTextView
     * (TextView à état coché — la section État porte son `android:checked`).
     */
    private fun familleEtat(classe: String): Boolean = classe == "CheckBox" || familleInterrupteur(classe) ||
        classe == "RadioButton" || classe == "ToggleButton" ||
        classe == "Chip" || classe == "CheckedTextView"

    /**
     * Les INTERRUPTEURS seuls (Switch, SwitchMaterial) — PAS d'icônes du
     * texte (§ 7). v0.170.0 (S4) : MaterialSwitch (materialswitch).
     */
    private fun familleInterrupteur(classe: String): Boolean =
        classe == "Switch" || classe == "SwitchMaterial" || classe == "MaterialSwitch"

    /** ImageView et ImageButton. */
    private fun familleImage(classe: String): Boolean = classe == "ImageView" || classe == "ImageButton"
}
