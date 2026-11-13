package jo.codeide.core.model

/**
 * Rapport de plantage complet (section 5.8 du prompt maître).
 *
 * Instantané autonome capturé au pire moment : tout ce qui aide à comprendre
 * un défaut sans dépendre d'un service réseau. Le contenu est **déjà
 * expurgé et borné à la construction** — ce qui vit ici est sûr à écrire sur
 * disque, à afficher et à exporter (règle 15 : aucune donnée personnelle).
 *
 * La persistance (JSON via `org.json` du framework) est assurée par
 * `core:crash` : le modèle reste une donnée pure, sans annotation de
 * sérialisation — le chemin critique du plantage ne doit dépendre d'aucune
 * bibliothèque.
 *
 * @property id identifiant unique (UUID) du rapport.
 * @property type nature du plantage.
 * @property timestampMillis horodatage du plantage, en millisecondes epoch.
 * @property sessionId identifiant de la session de journalisation, ou chaîne
 * vide quand il est inconnu (rapports ANR/natifs reconstruits au démarrage).
 * @property application identité du build.
 * @property device photographie non identifiante de l'appareil.
 * @property threadName nom du thread qui a planté, ou chaîne vide si inconnu.
 * @property exception chaîne d'exceptions aplatie, expurgée et bornée
 * (100 tranches, 10 causes, exceptions supprimées incluses).
 * @property breadcrumbs dernières entrées de journal (au plus 50), contexte
 * menant au plantage.
 * @property lastScreen dernier écran connu (activité puis destination de
 * navigation), ou `null` avant tout affichage.
 * @property processUptimeMs durée écoulée depuis le démarrage du processus.
 * @property isCrashLoop `true` quand ce plantage fait partie d'une boucle
 * (au moins 3 plantages en 60 s) — l'écran dédié adapte alors ses actions.
 */
public data class CrashReport(
    public val id: String,
    public val type: CrashType,
    public val timestampMillis: Long,
    public val sessionId: String,
    public val application: CrashAppInfo,
    public val device: DeviceInfo,
    public val threadName: String,
    public val exception: FlattenedException,
    public val breadcrumbs: List<LogEntry>,
    public val lastScreen: String?,
    public val processUptimeMs: Long,
    public val isCrashLoop: Boolean,
)
