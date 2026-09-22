package jo.codeide.core.model

/**
 * Raison typée d'un échec de validation (étape 10 — section 12.3).
 *
 * Les validateurs du moteur ([TemplateValidators] côté domaine) produisent
 * des messages français destinés aux journaux et au bloc « copier les
 * détails » ; l'interface, elle, **remplace ces messages par des ressources
 * localisées** (section 11 du prompt maître). Ce type fermé est le pont :
 * chaque raison porte exactement les paramètres nécessaires à l'affichage
 * (caractère fautif, segment incriminé, motif attendu…).
 *
 * Un modèle futur peut introduire des validateurs inconnus de l'interface :
 * le rendu retombe alors sur un message générique.
 */
public sealed interface RaisonValidation {
    /** Nom de projet : longueur hors bornes (1 à 64 caractères après trim). */
    public data object LongueurNom : RaisonValidation

    /** Nom de projet : caractère interdit (`/ \ : * ? " < > |` ou contrôle). */
    public data class CaractereInterditNom(
        public val fautif: Char,
    ) : RaisonValidation

    /** Nom de projet : « . » ou « .. ». */
    public data object PointsFictifsNom : RaisonValidation

    /** Nom de projet : point ou espace final. */
    public data object FinNomInterdite : RaisonValidation

    /** Nom de projet : réservé par Windows (CON, PRN, COM1-9…). */
    public data object NomReserveWindows : RaisonValidation

    /** Package : vide ou segment vide (points en tête, fin ou doublés). */
    public data object PackageVideOuSegmentVide : RaisonValidation

    /** Package : segment ne respectant pas `^[a-z][a-z0-9_]*$`. */
    public data class SegmentPackageInvalide(
        public val segment: String,
    ) : RaisonValidation

    /** Package : segment est un mot-clé Java/Kotlin. */
    public data class MotClePackage(
        public val segment: String,
    ) : RaisonValidation

    /** Identifiant générique : ne respecte pas le motif lettre/underscore initial. */
    public data class IdentifiantInvalide(
        public val valeur: String,
    ) : RaisonValidation

    /** Identifiant générique : mot-clé Java/Kotlin réservé. */
    public data class MotCleIdentifiant(
        public val valeur: String,
    ) : RaisonValidation

    /** Version : ne respecte pas SemVer 2.0.0. */
    public data object VersionInvalide : RaisonValidation

    /** Forme paramétrée `regex:` : la valeur ne correspond pas au motif. */
    public data class RegexNonCorrespondance(
        public val motif: String,
    ) : RaisonValidation

    /** Valeur interdite pour le type du paramètre (choix hors liste, booléen malformé). */
    public data class ValeurInterdite(
        public val valeur: String,
    ) : RaisonValidation
}
