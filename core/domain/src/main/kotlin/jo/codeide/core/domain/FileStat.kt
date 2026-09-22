package jo.codeide.core.domain

/**
 * Photographie d'un document tel que vu par le [FileSystem] (étape 4).
 *
 * « Document » au sens SAF : dossier ou fichier désigné par une URI
 * `content://` — il n'existe pas de notion de chemin `File` ici (ADR 0003).
 * Les champs absents du fournisseur (taille ou date inconnue) valent
 * `-1` plutôt que de lever : un listing ne doit jamais échouer sur une
 * colonne manquante.
 *
 * @property uri URI du document (clé stable pour les opérations suivantes).
 * @property name nom d'affichage du document (jamais un chemin complet).
 * @property isDirectory `true` pour un dossier, `false` pour un fichier.
 * @property sizeBytes taille en octets, `-1` si inconnue.
 * @property lastModifiedMillis date de dernière modification (epoch
 * millis), `-1` si inconnue.
 */
public data class FileStat(
    public val uri: String,
    public val name: String,
    public val isDirectory: Boolean,
    public val sizeBytes: Long,
    public val lastModifiedMillis: Long,
)
