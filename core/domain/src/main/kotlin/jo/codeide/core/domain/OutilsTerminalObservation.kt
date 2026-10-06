package jo.codeide.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Instantané **observable** des outils du terminal (v0.37.3).
 *
 * Retour d'appareil réel : les points d'UI du tooling (carte terminal du
 * tiroir, bandeau de l'accueil, page terminal de l'onboarding, garde JDK
 * de l'éditeur) lisaient [ToolchainLocator] en **instantané pull** au
 * moment de leur construction — une installation de outils terminée
 * pendant que l'écran restait ouvert n'y était jamais visible, et la
 * plupart des points restaient bloqués sur leur valeur initiale.
 *
 * Cet état est la version **poussée** du même diagnostic : chaque
 * consommateur s'y abonne une fois et suit les transitions (bootstrap
 * posé, JDK installé, distribution Gradle téléchargée par le tooling,
 * SDK Android posé par la commande `android-sdk`, `aapt2` déployé).
 *
 * @property bootstrapInstalle le bootstrap natif est installé jusqu'au
 * bout (double marqueur — même sémantique que
 * [ToolchainLocator.isBootstrapInstalled]).
 * @property jdkInstalle un JDK complet (java + javac) est détecté.
 * @property gradleInstalle une distribution Gradle complète est détectée
 * — y compris celle du cache wrapper sous le HOME du shell.
 * @property sdkAndroidInstalle un SDK Android (au moins une plateforme)
 * est détecté — y compris sous le HOME du shell.
 * @property aapt2Installe le binaire `aapt2` cross-compilé est déployé
 * et exécutable sous `$PREFIX/bin`.
 */
public data class EtatOutilsTerminal(
    public val bootstrapInstalle: Boolean = false,
    public val jdkInstalle: Boolean = false,
    public val gradleInstalle: Boolean = false,
    public val sdkAndroidInstalle: Boolean = false,
    public val aapt2Installe: Boolean = false,
    /**
     * `true` dès qu'au moins UN scan du disque a eu lieu (v0.39.1 —
     * correctif race JDK : la sync d'ouverture attend ce premier scan
     * avant de lire `jdkInstalle`, sinon l'état par défaut `false` ment
     * sur un JDK pourtant installé). Les transitions suivantes restent
     * `true` ; seul l'état NON scanné (avant le premier cycle de
     * l'horloge de ballotage) reste `false`.
     */
    public val initialise: Boolean = false,
)

/**
 * Cas d'usage « observer l'état des outils du terminal » (v0.37.3) :
 * flot **chaud** des instantanés [EtatOutilsTerminal], réémis à chaque
 * transition observable.
 *
 * Source des réémissions (implémentation de référence
 * `core:bootstrap`) : les transitions du parcours d'installation
 * ([EnvironmentSetupOrchestrator] — E6 : l'ancien `BootstrapInstaller`
 * a été retiré, ADR 0091) **et** un ballotage périodique
 * léger tant qu'un écran collecte — les outils peuvent aussi apparaître
 * SANS passer par le parcours (distribution Gradle téléchargée par
 * l'orchestrateur du tooling, `aapt2` déployé à la première build) : le
 * disque reste la seule source de vérité, il est réinterrogé.
 *
 * Contexte d'exécution attendu : collecte depuis le cycle de vie de
 * l'UI (`stateIn`/`WhileSubscribed`) ; le flot est **froid par écran**
 * mais bon marché (quelques tests de fichiers par réémission), et se
 * met en pause tout seul quand plus personne ne collecte.
 */
public fun interface ObserveToolchainStateUseCase {
    /**
     * @return le flot des états des outils du terminal, démarre sur la
     * valeur courante du disque puis suit les transitions.
     */
    public operator fun invoke(): Flow<EtatOutilsTerminal>
}
