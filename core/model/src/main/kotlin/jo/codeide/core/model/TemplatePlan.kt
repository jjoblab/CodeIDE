package jo.codeide.core.model

/**
 * Plan de création d'un projet (dry-run, étape 8 — section 12.4).
 *
 * Le plan est produit **sans aucune écriture** par
 * `PlanProjectCreationUseCase` : l'aperçu de l'arborescence du récapitulatif
 * (section 12.3) et la création effective (`CreateProjectUseCase`) partagent
 * exactement le même plan — **ce qui est planifié est ce qui est écrit**, à
 * l'octet près.
 *
 * Le contenu est figé au moment du plan (chemins substitués, textes rendus,
 * binaires lus) : deux exécutions avec les mêmes entrées produisent les
 * mêmes octets (déterminisme, l'année venant de l'horloge injectée).
 *
 * @property fichiers fichiers planifiés, dans l'ordre d'écriture (ordre du
 * manifeste, puis `LICENSE`, puis `.codeide/project.json`).
 */
public data class TemplatePlan(
    public val fichiers: List<PlannedFile>,
)

/**
 * Fichier planifié, prêt à écrire (chemin final + contenu rendu).
 *
 * @property chemin chemin relatif à la racine du projet, déjà substitué et
 * contrôlé par la garde de sécurité des chemins.
 * @property group groupe fonctionnel du fichier.
 * @property contenu contenu final (texte rendu ou octets binaires).
 */
public data class PlannedFile(
    public val chemin: String,
    public val group: TemplateFileGroup,
    public val contenu: PlannedContent,
)

/**
 * Contenu final d'un fichier planifié.
 *
 * `Binary` implémente l'égalité par contenu (un `ByteArray` nu comparerait
 * les références) : deux plans de mêmes entrées sont ainsi égaux par valeur.
 */
public sealed interface PlannedContent {
    /** Contenu texte rendu (UTF-8, fins de ligne normalisées). */
    public data class Texte(
        public val texte: String,
    ) : PlannedContent

    /** Contenu binaire copié tel quel. */
    public class Binaire(
        public val octets: ByteArray,
    ) : PlannedContent {
        /** Représentation pour les assertions de test (jamais le contenu). */
        public override fun toString(): String = "Binaire(${octets.size} octets)"

        public override fun equals(other: Any?): Boolean = other is Binaire && other.octets.contentEquals(octets)

        public override fun hashCode(): Int = octets.contentHashCode()
    }
}
