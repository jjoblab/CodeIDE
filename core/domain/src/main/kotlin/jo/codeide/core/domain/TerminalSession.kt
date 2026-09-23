package jo.codeide.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Instant

/**
 * Métadonnées d'une session de terminal (prompt compagnon Terminal-1,
 * section 2.2).
 *
 * Délibérément **sans type Termux** : consommable par des features qui
 * ne dépendent d'aucune bibliothèque Termux (la carte d'aperçu du tiroir
 * de l'éditeur, section 8). L'implémentation de référence
 * (`core:terminal-runtime`) traduit l'état réel des sessions.
 *
 * @property id identifiant stable de la session.
 * @property label libellé affiché (« Session 1 », renommable).
 * @property workingDirectoryPath répertoire de travail au démarrage.
 * @property isAlive le processus shell vit-il encore ?
 * @property lastOutputPreview derniers caractères de sortie (aperçu borné,
 * replats sur une ligne).
 * @property createdAt horodatage de création.
 */
public data class TerminalSessionSummary(
    public val id: String,
    public val label: String,
    public val workingDirectoryPath: String,
    public val isAlive: Boolean,
    public val lastOutputPreview: String,
    public val createdAt: Instant,
)

/**
 * Registre **global** des sessions de terminal (prompt compagnon
 * Terminal-1, sections 1.5 et 2.2) : une seule liste, partagée par tous
 * les points d'entrée (accueil, tiroir de l'espace de travail).
 *
 * Une session dont le shell se termine **naturellement** (commande
 * `exit`) reste visible avec `isAlive = false` — c'est la fermeture
 * explicite ([closeSession]) qui la retire de la liste.
 *
 * Les mises à jour de sortie sont **throttlées** : la carte d'aperçu ne
 * doit pas être réveillée à chaque caractère (section 4.3).
 */
public interface TerminalSessionRepository {
    /** Liste observable des sessions, dans l'ordre de création. */
    public fun observeSessions(): Flow<List<TerminalSessionSummary>>

    /** Identifiant de la session active (rendue), ou `null`. */
    public fun observeActiveSessionId(): Flow<String?>

    /**
     * Crée une session shell interactive.
     *
     * L'environnement provient de [ProcessEnvironmentProvider] (source
     * unique de vérité) et le shell de [ToolchainLocator.defaultShell].
     *
     * @param workingDirectory répertoire de travail initial (dossier
     * général de l'app ou dossier du projet courant).
     * @param label libellé ; `null` pour la numérotation automatique.
     * @return l'identifiant de la session créée.
     */
    public suspend fun createSession(
        workingDirectory: File,
        label: String? = null,
    ): String

    /** Définit la session rendue à l'écran. */
    public fun setActiveSession(sessionId: String)

    /** Renomme une session (appui long sur l'onglet). */
    public suspend fun renameSession(
        sessionId: String,
        label: String,
    )

    /**
     * Ferme une session : termine son shell **réellement** (pas seulement
     * masquée) et la retire de la liste.
     */
    public suspend fun closeSession(sessionId: String)
}
