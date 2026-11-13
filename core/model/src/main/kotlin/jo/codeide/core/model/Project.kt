package jo.codeide.core.model

/**
 * Projet enregistré dans CodeIDE (étape 4 du plan — couche données).
 *
 * Un projet est une entrée de **registre** : il référence un dossier réel
 * sur le stockage (décrit par [location], un contrat SAF conforme à la
 * section 5.6) et le modèle qui l'a généré ([templateId]). Il ne détient
 * ni les fichiers ni leur contenu — la persistance du contenu est le
 * travail du stockage lui-même.
 *
 * Le nom est un libellé d'affichage **indépendant du dossier** : le
 * renommage d'un projet (phase 1) modifie ce libellé en base, jamais le
 * dossier sur disque (ADR 0012).
 *
 * @property id identifiant opaque, créé par la couche de données à
 * l'ajout (voir [Identifiants.kt]).
 * @property name libellé lisible (1 à 64 caractères, trimé par l'appelant).
 * @property description description libre, possiblement vide.
 * @property location emplacement SAF du dossier du projet.
 * @property templateId modèle qui a généré le projet (`kotlin-jvm`, `java`).
 * @property createdAtMillis horodatage de création (epoch millis, UTC).
 * @property lastOpenedAtMillis horodatage du dernier marquage « ouvert »,
 * ou `null` si le projet n'a jamais été ouvert.
 * @property isPinned épingle de l'accueil (les projets épinglés flottent
 * en tête de liste).
 */
public data class Project(
    public val id: ProjectId,
    public val name: String,
    public val description: String,
    public val location: StorageLocation,
    public val templateId: TemplateId,
    public val createdAtMillis: Long,
    public val lastOpenedAtMillis: Long?,
    public val isPinned: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Le nom d'un projet ne peut pas être vide." }
        require(name.length <= MAX_NAME_LENGTH) {
            "Le nom d'un projet est limité à $MAX_NAME_LENGTH caractères."
        }
        require(createdAtMillis >= 0) { "L'horodatage de création doit être positif." }
        require(lastOpenedAtMillis == null || lastOpenedAtMillis >= 0) {
            "L'horodatage d'ouverture doit être positif."
        }
    }

    /**
     * Représentation sûre pour les journaux et le débogage (règle 15 :
     * identifiants uniquement, jamais un nom de projet).
     *
     * @return l'identifiant du projet.
     */
    public override fun toString(): String = "Projet(${id.value})"

    public companion object {
        /** Taille maximale du nom, alignée sur le validateur du wizard (section 12.3). */
        public const val MAX_NAME_LENGTH: Int = 64
    }
}
