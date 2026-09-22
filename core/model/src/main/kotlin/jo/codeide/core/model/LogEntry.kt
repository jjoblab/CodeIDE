package jo.codeide.core.model

import kotlinx.serialization.Serializable

/**
 * Entrée immuable du journal applicatif (section 5.7 du prompt maître).
 *
 * Une entrée est produite par le pipeline de journalisation **après**
 * filtrage par niveau, expurgation ([jo.codeide.core.domain.LogRedactor])
 * et troncature : ce qui vit ici est déjà sûr à écrire sur disque, à
 * afficher et à exporter. Le format de persistance est JSON Lines (une
 * entrée par ligne), d'où la sérialisation.
 *
 * Convention de contenu (règle 15 du prompt) : le message ne contient que
 * des identifiants et messages techniques — jamais de nom de projet, de
 * chemin, d'auteur ou de contenu de fichier en clair.
 *
 * @property timestampMillis horodatage de l'émission, en millisecondes epoch.
 * @property sessionId identifiant du lancement (UUID) auquel appartient
 * l'entrée — permet de regrouper les entrées d'une même session.
 * @property level sévérité de l'entrée.
 * @property tag étiquette courte du producteur (ex. « Session »,
 * « Navigation ») ; identifiant, donc en anglais.
 * @property threadName nom du thread émetteur, utile au diagnostic.
 * @property message texte expurgé et tronqué (4 Kio maximum).
 * @property exception exception aplanie éventuelle associée à l'entrée.
 */
@Serializable
public data class LogEntry(
    public val timestampMillis: Long,
    public val sessionId: String,
    public val level: LogLevel,
    public val tag: String,
    public val threadName: String,
    public val message: String,
    public val exception: FlattenedException? = null,
)
