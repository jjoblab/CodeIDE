package jo.codeide.preview.xml

/**
 * Jetons de COULEUR du moteur de calques (PE11i — ADR 0134 D7,
 * `docs/PREVIEW_UI_ECRANS.md` § 11) : le module `:preview:xml` reste
 * zéro-Material et ne lit AUCUN attribut de thème — il reçoit cet objet
 * PUR par [VuePreviewXml.appliquerJetons], posé par `PreviewActivity` à
 * la création et à chaque changement de thème.
 *
 * Il REMPLACE les constantes de couleur du moteur (damier, squelette
 * Plan, calques de sélection, box-model, épingles, zones de dépôt,
 * écran rendu) : « toutes les constantes de couleur disparaissent » de
 * [VuePreviewXml]. Les ALPHAS de calque (parent 75 %, frères 35 %,
 * grille 18 %) restent des règles de § 3.5 — des opacités, pas des
 * couleurs.
 *
 * Les 21 champs suivent l'ORDRE de [NOMS] (noms des jetons de la
 * maquette, `docs/mockups/ref/jetons-maquette.json`) : la parité
 * maquette ↔ ressources (clair ET nuit) et la COMPLÉTUDE du câblage
 * sont gardées par `JetonsCalquesTest` (JVM pur), comme
 * `JetonsMaquetteTest` pour les vues.
 */
public data class JetonsCalques(
    /** Fond du canvas (jeton `apercu_surface2`). */
    public val surface2: Int,
    /** Cases alternées du damier 8 dp (jeton `apercu_damier_case`, alpha incluse). */
    public val damierCase: Int,
    /** Trait de sélection, squelette Plan, contraintes (jeton `apercu_accent`). */
    public val accent: Int,
    /** Aplat de la sélection et du Plan en creux (jeton `apercu_accent_doux`, alpha incluse). */
    public val accentDoux: Int,
    /** Poignées et liserés (jeton `apercu_surface`). */
    public val surface: Int,
    /** Fond de l'étiquette des cotes (jeton `apercu_texte`). */
    public val texte: Int,
    /** Texte des étiquettes de cotes (jeton `apercu_fond`). */
    public val fond: Int,
    /** Légende de planche et accueil (jeton `apercu_texte_doux`). */
    public val texteDoux: Int,
    /** Liseré pointillé des bandes de marges (jeton `apercu_marge`). */
    public val marge: Int,
    /** Aplat des bandes de marges (jeton `apercu_marge_fond`, alpha incluse). */
    public val margeFond: Int,
    /** Réservé au symétrique du liseré de marge (jeton `apercu_remplissage`). */
    public val remplissage: Int,
    /** Aplat des bandes de padding (jeton `apercu_remplissage_fond`, alpha incluse). */
    public val remplissageFond: Int,
    /** Bornes de toutes les vues (jeton `apercu_bornes`, alpha incluse). */
    public val bornes: Int,
    /** Zones de dépôt, barre et pill d'insertion (jeton `apercu_nouveau`). */
    public val nouveau: Int,
    /** Conteneur visé et halo de la barre (jeton `apercu_nouveau_doux`, alpha incluse). */
    public val nouveauDoux: Int,
    /** Texte des pills d'insertion (jeton `apercu_sur_nouveau`). */
    public val surNouveau: Int,
    /** Épingle d'avertissement (jeton `apercu_epingle_avert`). */
    public val epingleAvert: Int,
    /** Épingle d'erreur (jeton `apercu_epingle_erreur`). */
    public val epingleErreur: Int,
    /** Texte des étiquettes, liseré des épingles (jeton `apercu_sur_calque`). */
    public val surCalque: Int,
    /** Liseré du contour de l'écran rendu (jeton `apercu_ecran_trait`, alpha incluse). */
    public val ecranTrait: Int,
    /** Ombre de l'écran rendu (jeton `apercu_ecran_ombre`, alpha incluse). */
    public val ecranOmbre: Int,
) {
    public companion object {
        /**
         * Les NOMS des 21 jetons de la maquette, DANS L'ORDRE du
         * constructeur — `JetonsCalquesTest` vérifie que chacun existe
         * dans `jetons-maquette.json` ET dans `values{,-night}/colors.xml`
         * à la valeur exacte (parité clair et nuit, ADR 0134 § 11).
         */
        public val NOMS: List<String> =
            listOf(
                "apercu_surface2",
                "apercu_damier_case",
                "apercu_accent",
                "apercu_accent_doux",
                "apercu_surface",
                "apercu_texte",
                "apercu_fond",
                "apercu_texte_doux",
                "apercu_marge",
                "apercu_marge_fond",
                "apercu_remplissage",
                "apercu_remplissage_fond",
                "apercu_bornes",
                "apercu_nouveau",
                "apercu_nouveau_doux",
                "apercu_sur_nouveau",
                "apercu_epingle_avert",
                "apercu_epingle_erreur",
                "apercu_sur_calque",
                "apercu_ecran_trait",
                "apercu_ecran_ombre",
            )
    }
}
