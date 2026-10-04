package jo.codeide.core.domain

/**
 * Pilotage de la **configuration automatique de l'environnement du
 * terminal** (v0.52.0, ADR 0083) : une fois le bootstrap installé et le
 * dépôt de paquets mis à jour, Java (OpenJDK) et le SDK Android sont
 * installés **dans le terminal lui-même** — la commande `codeide-env`
 * (script versionné posé dans `$PREFIX/bin`) s'exécute dans une session
 * dédiée et son journal défile dans le TerminalView : le rendu live est
 * le terminal, pas un écran de progression parallèle.
 *
 * L'implémentation de référence vit dans `core:terminal-runtime` : elle
 * crée la session, y écrit la commande (comme si l'utilisateur la
 * tapait — [TerminalSessionRepository.envoyerTexte]) et veille à ce
 * qu'une seule session de configuration existe à la fois.
 *
 * Le découpage des responsabilités : ce port **décide et orchestre**,
 * le script `codeide-env` **fait** (mise à jour des paquets, OpenJDK,
 * délégation à `android-sdk installer`, pont
 * `ide-environment.properties`, vérifications, marqueur d'idempotence).
 */
public interface ConfigurationEnvTerminal {
    /**
     * L'environnement est-il **complet** (JDK et SDK Android en place) ?
     *
     * Lu sur le disque via [ToolchainLocator] — la source de vérité
     * reste les fichiers réels, pas un état en mémoire : une
     * installation faite à la main dans le terminal (`codeide-env`,
     * `android-sdk`) compte autant qu'une installation pilotée.
     */
    public fun estComplet(): Boolean

    /**
     * Lance (ou retrouve) la session de configuration de l'environnement.
     *
     * Garde-fous, dans l'ordre :
     * - bootstrap absent ou environnement déjà complet : **rien à faire**
     *   (`null`), jamais d'exception ;
     * - une session de configuration vit déjà : son identifiant est
     *   retourné (l'appelant y revient, aucune seconde session créée) ;
     * - sinon : création d'une session étiquetée, attente brève du
     *   démarrage du shell, puis envoi de la commande `codeide-env`.
     *
     * La commande s'exécute dans la session — l'appelant n'a **pas**
     * de coroutine à conserver : la session vit dans le registre global
     * (service foreground), l'utilisateur peut la refermer, l'interrompre
     * (Ctrl+C) ou la suivre à son rythme.
     *
     * @return l'identifiant de la session de configuration, ou `null`
     *         s'il n'y a rien à faire.
     */
    public suspend fun lancer(): String?
}
