package jo.codeide.core.model

/**
 * Super-type commun des identifiants d'entités de CodeIDE.
 *
 * Les identifiants sont des chaînes opaques échangées entre couches ;
 * l'interface évite de les confondre entre eux à la compilation (un
 * `ProjectId` ne se passe pas là où un `TemplateId` est attendu) sans
 * ajouter de dépendance à Android — le module reste Kotlin JVM pur.
 *
 * Convention de journalisation (section 5.7) : les identifiants sont la
 * **seule** donnée métier autorisée dans les journaux ; leur valeur peut
 * donc apparaître telle quelle.
 */
public interface EntityId {
    /** Valeur opaque et stable de l'identifiant (jamais vide). */
    public val value: String
}

/**
 * Identifiant d'un projet enregistré dans CodeIDE.
 *
 * Créé par la couche de données à l'ajout d'un projet (étape 4) ; utilisé
 * par le domaine, l'accueil et le wizard sans jamais exposer son origine
 * (UUID en base, à ce jour).
 */
@JvmInline
public value class ProjectId(
    public override val value: String,
) : EntityId {
    init {
        require(value.isNotBlank()) { "Un identifiant de projet ne peut pas être vide." }
    }
}

/**
 * Identifiant d'un modèle de projet embarqué (`kotlin-jvm`, `java`…).
 *
 * Contrairement à [ProjectId], il est **stable et déclaré** par le
 * catalogue de templates ; il sert de clé de persistance dans le modèle
 * `Project` et dans `.codeide/project.json`.
 */
@JvmInline
public value class TemplateId(
    public override val value: String,
) : EntityId {
    init {
        require(value.isNotBlank()) { "Un identifiant de modèle ne peut pas être vide." }
    }

    public companion object {
        /**
         * Sentinelle des projets ajoutés par « Ouvrir un dossier
         * existant » (étape 7) : le dossier n'a été généré par aucun
         * modèle connu, et `.codeide/project.json` — qui permettra à
         * l'ouverture de reconnaître le vrai modèle (étape 13) — n'y est
         * pas encore lu.
         *
         * L'accueil affiche alors l'icône générique « dossier » au lieu
         * d'une pastille de type (ADR 0015).
         */
        public val IMPORTED: TemplateId = TemplateId("imported")
    }
}

/**
 * Identifiant d'un rapport de plantage conservé sur disque.
 *
 * Produit par `core:crash` (étape 3) au moment de la capture, il sert de
 * nom de fichier et de clé de consultation depuis l'écran Diagnostic.
 */
@JvmInline
public value class CrashReportId(
    public override val value: String,
) : EntityId {
    init {
        require(value.isNotBlank()) { "Un identifiant de rapport de plantage ne peut pas être vide." }
    }
}
