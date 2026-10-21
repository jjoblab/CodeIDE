package jo.codeide.preview.xml

/**
 * CATALOGUE DES ATTRIBUTS PAR TYPE DE VUE (PE7e, maquette
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

        /** L'identifiant de la vue (PE8a : renommage déclaration + références). */
        public data class Identifiant(
            val nom: String,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

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
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

        /** Un nombre avec unité (`"dp"`, `"sp"` ou `""` — alpha sans unité). */
        public data class Nombre(
            val nom: String,
            val etiquette: Etiquette,
            val unite: String,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

        /** Un choix segmenté — le PREMIER élément est le défaut (retrait). */
        public data class Choix(
            val nom: String,
            val etiquette: Etiquette,
            val valeurs: List<String>,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)
        }

        /** Un tri-état (défaut / true / false — défaut = retrait). */
        public data class TriEtat(
            val nom: String,
            val etiquette: Etiquette,
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
            val etiquette: Etiquette,
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
            val etiquette: Etiquette,
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
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : le contrôle porte SON attribut. */
            override val nomsAttributs: List<String> = listOf(nom)

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
            val etiquette: Etiquette,
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

        /** Les quatre côtés d'une marge ou d'un padding (miroir `.mrow`). */
        public data class Cotes(
            val base: String,
            val etiquette: Etiquette,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : les quatre côtés partagent la BASE. */
            override val nomsAttributs: List<String> = listOf(base)
        }

        /** Les dimensions wrap/match/fixe (PE7d — miroir `dimrow`). */
        public object Dimensions : ControleAttribut() {
            /** [ControleAttribut.courant] : composite sans champ, toujours courant. */
            override val courant: Boolean = true

            /** [ControleAttribut.nomsAttributs] : rien à compter. */
            override val nomsAttributs: List<String> = emptyList()
        }

        /** Les contraintes du ConstraintLayout parent (ÉDITABLES — PE8b :
         * cible parent/frère + marge par ancre). */
        public object Contraintes : ControleAttribut() {
            /** [ControleAttribut.courant] : composite sans champ, toujours courant. */
            override val courant: Boolean = true

            /** [ControleAttribut.nomsAttributs] : rien à compter. */
            override val nomsAttributs: List<String> = emptyList()
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
            val etiquette: Etiquette,
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
            val etiquette: Etiquette,
            val entrees: List<Entree>,
            override val courant: Boolean = true,
        ) : ControleAttribut() {
            /** [ControleAttribut.nomsAttributs] : chaque bascule porte SON attribut. */
            override val nomsAttributs: List<String> = entrees.map { entree -> entree.nom }

            /** UNE bascule : l'attribut [nom] et la clé de libellé [libelle]. */
            public data class Entree(
                val nom: String,
                val libelle: Etiquette,
            )
        }
    }

    /**
     * UNE section repliable (titre + contrôles — miroir `.sec2`). La
     * [note] optionnelle (PE12c — miroir `sc.note` de la maquette) se
     * rend EN TÊTE du corps, avant les contrôles.
     */
    public data class SectionAttributs(
        val titre: TitreSection,
        val controles: List<ControleAttribut>,
        val note: NoteSection? = null,
    )

    /** Les notes de section (clés sémantiques — traduites au chrome). */
    public enum class NoteSection {
        /** « Chaque règle vise une vue sœur ; une règle qui fermerait une boucle est refusée. » */
        REGLES_SOEURS,
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

    /**
     * Sur la RACINE, `fitsSystemWindows` et `clipToPadding` ne se
     * répètent pas dans Conteneur (spec § 5.1 — miroir `DUP` de la
     * maquette : la section Racine de la 2e vague les porte).
     */
    private val DOUBLES_RACINE: Set<String> = setOf("android:fitsSystemWindows", "android:clipToPadding")

    private fun List<ControleAttribut>.sansDoublesRacine(racine: Boolean): List<ControleAttribut> =
        if (!racine) {
            this
        } else {
            filterNot { controle ->
                controle is ControleAttribut.TriEtat && controle.nom in DOUBLES_RACINE
            }
        }

    /**
     * Les SECTIONS de la vue [classe] avec la [familleParent] de son
     * parent et la [familleVue] de la vue ELLE-MÊME (PE12c, spec § 5) :
     * sections PROPRES à la classe (ordre du catalogue), puis conteneur
     * (§ 5.1 — la famille de la VUE décide), puis parent (§ 5.2 — la
     * famille du PARENT décide), puis les sections GÉNÉRIQUES
     * (Disposition, Affichage). Sur la RACINE ([familleParent] =
     * [FamilleParent.AUCUN]), `fitsSystemWindows` et `clipToPadding` ne
     * se répètent pas dans Conteneur (section Racine de la 2e vague —
     * miroir `DUP` de la maquette).
     */
    public fun sections(
        classe: String,
        familleParent: FamilleParent,
        familleVue: FamilleParent,
    ): List<SectionAttributs> {
        val propres = sectionsPropres(classe)
        val conteneur = sectionsConteneur(classe, familleVue, racine = familleParent == FamilleParent.AUCUN)
        val duParent =
            when (familleParent) {
                FamilleParent.CONSTRAINT -> {
                    listOf(
                        SectionAttributs(
                            TitreSection.CONTRAINTES,
                            listOf(ControleAttribut.Contraintes),
                        ),
                    )
                }

                FamilleParent.LINEAR -> {
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
        // indépendant de la classe — la racine y a droit aussi (l'édit de
        // la racine reste borné à Identité/Disposition/Affichage).
        return listOf(sectionIdentite()) + propres + conteneur + duParent + generiques()
    }

    /**
     * Variante à DEUX paramètres (PE12c) : la famille de la vue se
     * déduit de sa CLASSE par balise — le repli des vues dégradées
     * (spec § 2.2) ; le rendu réel fournit la sienne via
     * [InfoVueRendu.famille].
     */
    public fun sections(
        classe: String,
        familleParent: FamilleParent,
    ): List<SectionAttributs> = sections(classe, familleParent, FamilleParent.deBalise(classe))

    /** La section IDENTITÉ (PE8a : le seul contrôle de renommage). */
    private fun sectionIdentite(): SectionAttributs =
        SectionAttributs(
            TitreSection.IDENTITE,
            listOf(ControleAttribut.Identifiant("android:id")),
        )

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
            sectionBarreOutils(classe),
            sectionBoutonAction(classe),
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

    // ── PE12c — § 5.1 : sections du conteneur par famille ────────────

    /**
     * Les sections du CONTENEUR (spec § 5.1, miroir `containerSecs`) :
     * la vue sélectionnée EST un parent — SA famille décide de la
     * section. MaterialCardView garde SA section Carte, inchangée
     * (§ 5.1 : « + Carte (existant) »).
     */
    private fun sectionsConteneur(
        classe: String,
        familleVue: FamilleParent,
        racine: Boolean,
    ): List<SectionAttributs> {
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
    private fun sectionConteneurLineaire(racine: Boolean): List<SectionAttributs> =
        listOf(
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
    private fun sectionConteneurFrame(racine: Boolean): List<SectionAttributs> =
        listOf(
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
    private fun sectionConteneurRelatif(racine: Boolean): List<SectionAttributs> =
        listOf(
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
    private fun sectionConteneurCoordonnateur(racine: Boolean): List<SectionAttributs> =
        listOf(
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
    private fun sectionBarreApplication(racine: Boolean): SectionAttributs =
        SectionAttributs(
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

    /** La section CARTE de MaterialCardView (§ 5.1 : « + Carte (existant), inchangé »). */
    private fun sectionCarte(classe: String): List<SectionAttributs> =
        if (classe != "MaterialCardView") {
            emptyList()
        } else {
            listOf(
                SectionAttributs(
                    TitreSection.CARTE,
                    listOf(
                        ControleAttribut.Nombre("app:cardCornerRadius", Etiquette.ARRONDI, "dp"),
                        ControleAttribut.Nombre("app:cardElevation", Etiquette.ELEVATION, "dp"),
                    ),
                ),
            )
        }

    // ── PE12c — § 5.2 : sections « Dans le parent » par famille ───────

    /** FRAME (§ 5.2) : la gravité seule. */
    private fun sectionDansParentFrame(): SectionAttributs =
        SectionAttributs(
            TitreSection.DANS_PARENT,
            listOf(
                ControleAttribut.Drapeaux("android:layout_gravity", Etiquette.GRAVITE_PARENT, GRAVITES),
            ),
        )

    /** RELATIVE (§ 5.2) : l'ANCRAGE AU PARENT (7 bascules) + alignWithParentIfMissing. */
    private fun sectionDansParentRelatif(): SectionAttributs =
        SectionAttributs(
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
    private fun sectionPositionsRelatives(): SectionAttributs =
        SectionAttributs(
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
    private fun sectionDansParentCoordonnateur(): SectionAttributs =
        SectionAttributs(
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
    private fun sectionDansParentBarre(): SectionAttributs =
        SectionAttributs(
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

    /** La section BARRE D'OUTILS de MaterialToolbar (§ 5.4). */
    private fun sectionBarreOutils(classe: String): SectionAttributs? =
        if (classe != "MaterialToolbar") {
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
    private fun sectionBoutonAction(classe: String): SectionAttributs? =
        if (classe != "FloatingActionButton") {
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
