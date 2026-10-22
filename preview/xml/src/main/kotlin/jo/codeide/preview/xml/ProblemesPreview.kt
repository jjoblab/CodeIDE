package jo.codeide.preview.xml

import kotlin.math.min

/**
 * Règles de PROBLÈMES de l'aperçu (PE6c, maquette
 * `docs/mockups/apercu-xml.html` § « Données enrichies pour marges,
 * attributs, problèmes et dépôt » : « Les règles de problèmes sont des
 * fonctions pures, testées comme [SelectionPreview] ») — évaluation
 * DÉTERMINISTE sur les données enrichies de [InfoVueRendu], SANS Android
 * (aucune vue, aucune ressource : les libellés FR/EN sont composés par
 * l'UI à partir du [GenreProbleme], les données portent le fait).
 *
 * Les genres de FAMILLE (PE12f) partagent le même [GenreProbleme] :
 * leur ÉVALUATION vit dans [ProblemesFamilles] (sur le TEXTE, spec § 9)
 * — de même que les genres de CONTENANCE (v0.170.0, S3 :
 * [ContraintesHierarchie] — miroir des vérifications lint
 * `AdapterViewChildren`/`ScrollViewChildren`).
 *
 * Règles livrées (miroir de `computeProblems` de la maquette, DANS SON
 * ORDRE — classe, description, texte codé, cible, tronc) :
 * - [classeAbsente] : l'inflater a dégradé le tag en cadre de
 *   remplacement (`MockView` — G15) → ERREUR ;
 * - [sansContentDescription] : vue IMAGE sans `contentDescription`
 *   (lecteur d'écran muet) → AVERTISSEMENT ;
 * - [texteCodeDur] : `android:text` LITTÉRAL au source (`v.lit` —
 *   values-en muet) → AVERTISSEMENT ;
 * - [cibleTactile] : vue cliquable plus petite que le minimum
 *   d'accessibilité 48×48 dp (seuil 47,5 dp — la maquette tolère
 *   l'arrondi) → AVERTISSEMENT ;
 * - [texteTronque] : le texte DÉBORDE de la vue au rendu (mesure
 *   LayoutLib posée par le moteur, miroir `trunc`) → ERREUR.
 */
public object ProblemesPreview {
    /** Sévérité affichable (miroir de la maquette : pastille ambre / rouge). */
    public enum class Severite {
        /** Ambre — à corriger, le rendu reste correct. */
        AVERTISSEMENT,

        /** Rouge — le rendu est dégradé ou faux. */
        ERREUR,
    }

    /** Genre de problème — l'UI en tire le libellé localisé. */
    public enum class GenreProbleme {
        /** La classe du tag est introuvable : cadre de remplacement affiché. */
        CLASSE_ABSENTE,

        /** Vue IMAGE sans `contentDescription` — lecteur d'écran muet (PE11g). */
        SANS_CONTENT_DESCRIPTION,

        /** `android:text` LITTÉRAL au source — values-en muet (PE11g). */
        TEXTE_CODE_DUR,

        /** Vue cliquable sous la cible tactile de 48×48 dp. */
        CIBLE_TACTILE,

        /** Le texte déborde de la vue au rendu (mesure LayoutLib — PE11g). */
        TEXTE_TRONQUE,

        /**
         * Attribut de layout que la famille du PARENT ne comprend pas
         * (PE12f, spec § 9 — calculé sur le TEXTE, miroir `FAM_ATTRS`).
         */
        ATTRIBUT_ORPHELIN,

        /** Règle `RelativeLayout` vers un `@id/` absent des frères (PE12f, spec § 9). */
        REFERENCE_INTROUVABLE,

        /** `layout_anchor` invalide — LayoutLib refuse le rendu (PE12f, spec § 9). */
        ANCRE_INVALIDE,

        /** Contenu défilant d'un `CoordinatorLayout` sans `layout_behavior` (PE12f, spec § 9). */
        COMPORTEMENT_MANQUANT,

        /** Boucle de règles dans un `RelativeLayout` (PE12f, spec § 9 et § 12). */
        DEPENDANCE_CIRCULAIRE,

        /**
         * Vue FEUILLE qui porte des enfants (v0.170.0, S3 — TextView,
         * Button, Chip… : l'inflater réel plante ou dégrade) — erreur,
         * miroir `addView` impossible de la plateforme.
         */
        ENFANTS_INTERDITS,

        /**
         * Conteneur à ADAPTATEUR qui porte des enfants XML (v0.170.0,
         * S3 — ListView, RecyclerView, Spinner, ViewPager2… : miroir lint
         * `AdapterViewChildren`, « AdapterViews cannot have children in
         * XML ») — erreur.
         */
        ENFANTS_ADAPTATEUR,

        /**
         * Conteneur à ENFANT UNIQUE qui en porte plusieurs (v0.170.0,
         * S3 — ScrollView, HorizontalScrollView, NestedScrollView,
         * SwipeRefreshLayout : miroir lint `ScrollViewChildren`, « can
         * host only one direct child ») — avertissement.
         */
        ENFANT_UNIQUE_DEPASSE,

        /**
         * Conteneur à ENFANT ATTENDU qui en porte d'un autre type
         * (v0.170.0, S3 — TabLayout attend des TabItem, TextInputLayout un
         * TextInputEditText, ChipGroup des Chip, MaterialButtonToggleGroup
         * des MaterialButton) — erreur quand la vraie classe jette
         * (TextInputLayout), avertissement sinon.
         */
        ENFANT_INATTENDU,
    }

    /** UN problème détecté, rattaché à la vue par son INDICE dans la liste. */
    public data class ProblemeApercu(
        /** Indice de la vue fautive dans le tableau aplati du rendu. */
        public val indiceVue: Int,
        /** Sévérité (couleur de la pastille, tri de la feuille PE6d). */
        public val severite: Severite,
        /** Genre — clé du libellé localisé. */
        public val genre: GenreProbleme,
    )

    /**
     * Seuil de la cible tactile en dp (maquette : `Math.min(q.w, q.h) <
     * 47.5` — un demi-point sous 48 tolère les arrondis de conversion).
     */
    public const val SEUIL_CIBLE_DP: Double = 47.5

    /** Densité de référence de la conversion px → dp (mdpi). */
    private const val DENSITE_REFERENCE: Int = 160

    /**
     * Règle « classe absente » : la vue est le cadre de remplacement
     * (G15) posé quand la classe du tag n'a pas pu être instanciée.
     */
    public fun classeAbsente(vue: InfoVueRendu): Boolean = vue.classe == InfoVueRendu.CLASSE_REMPLACEMENT

    /**
     * Règle « image sans contentDescription » (PE11g — lecture de la vue
     * rendue par le moteur, miroir `!v.cd` sur les kinds image de la
     * maquette).
     */
    public fun sansContentDescription(vue: InfoVueRendu): Boolean = vue.sansContentDescription

    /**
     * Règle « texte codé en dur » (PE11g — `android:text` LITTÉRAL au
     * source, posé par le moteur sur l'élément aligné, miroir `v.lit`).
     */
    public fun texteCodeDur(vue: InfoVueRendu): Boolean = vue.texteCodeDur

    /**
     * Règle « texte tronqué » (PE11g — la mesure LayoutLib dit que le
     * contenu déborde de la vue, miroir `trunc` de `measure`).
     */
    public fun texteTronque(vue: InfoVueRendu): Boolean = vue.texteTronque

    /**
     * Règle « cible tactile » : vue CLIQUABLE dont le plus petit côté
     * passe sous [SEUIL_CIBLE_DP] dp (conversion px → dp par la densité
     * du PROFIL de rendu — pas celle de l'appareil hôte). Densité nulle
     * ou négative : la conversion est impossible, la règle SE TAIT
     * (jamais de faux positif).
     */
    public fun cibleTactile(
        vue: InfoVueRendu,
        densiteDpi: Int,
    ): Boolean {
        // Non cliquable, ou conversion impossible (densité inconnue) :
        // la règle SE TAIT — jamais de faux positif.
        val evaluable = vue.cliquable && densiteDpi > 0
        if (!evaluable) return false
        val petitCoteDp = min(vue.largeurPx, vue.hauteurPx) * DENSITE_REFERENCE.toDouble() / densiteDpi
        return petitCoteDp < SEUIL_CIBLE_DP
    }

    /**
     * Évalue TOUTES les règles sur l'arbre aplati du dernier rendu —
     * ordre de parcours = ordre des vues (pré-ordre), une vue peut
     * porter PLUSIEURS problèmes (la pastille de l'UI compte).
     */
    public fun evaluer(
        vues: List<InfoVueRendu>,
        densiteDpi: Int,
    ): List<ProblemeApercu> {
        if (vues.isEmpty()) return emptyList()
        val problemes = mutableListOf<ProblemeApercu>()
        for (indice in vues.indices) {
            val vue = vues[indice]
            // Ordre miroir de computeProblems : mock → cd → lit → click → trunc.
            if (classeAbsente(vue)) {
                problemes.add(ProblemeApercu(indice, Severite.ERREUR, GenreProbleme.CLASSE_ABSENTE))
            }
            if (sansContentDescription(vue)) {
                problemes.add(
                    ProblemeApercu(indice, Severite.AVERTISSEMENT, GenreProbleme.SANS_CONTENT_DESCRIPTION),
                )
            }
            if (texteCodeDur(vue)) {
                problemes.add(ProblemeApercu(indice, Severite.AVERTISSEMENT, GenreProbleme.TEXTE_CODE_DUR))
            }
            if (cibleTactile(vue, densiteDpi)) {
                problemes.add(
                    ProblemeApercu(indice, Severite.AVERTISSEMENT, GenreProbleme.CIBLE_TACTILE),
                )
            }
            if (texteTronque(vue)) {
                problemes.add(ProblemeApercu(indice, Severite.ERREUR, GenreProbleme.TEXTE_TRONQUE))
            }
        }
        return problemes
    }
}
