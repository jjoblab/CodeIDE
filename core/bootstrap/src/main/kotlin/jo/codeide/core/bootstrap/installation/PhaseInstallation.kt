package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.ComponentIssue
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.InstalledComponent
import jo.codeide.core.domain.StepContext

/**
 * Une phase du parcours d'installation, vue de l'orchestrateur
 * (ADR 0087 § 1) : la liste **ordonnée** de ses étapes et le recensement
 * des versions installées (pour `PhaseState.Succeeded.versions`).
 *
 * E2 livre `BOOTSTRAP` et `PACKAGE_TOOLS` ; E3 `JAVA` ; E4 `ANDROID_SDK`
 * — l'orchestrateur s'arrête à la première phase absente
 * (séquentialité stricte, § 3.6 du cahier).
 */
internal interface PhaseInstallation {
    /** La phase du parcours portée. */
    val phase: InstallPhase

    /** Étapes de la phase, dans l'ordre d'exécution. */
    fun etapes(): List<InstallStep>

    /**
     * Recense les versions installées par la phase — exécuté une fois
     * toutes les étapes vérifiées, chaque valeur vient d'une exécution
     * réelle (§ 3.2).
     *
     * @param contexte contexte d'exécution (commandes, journal).
     * @return composant → version (ex. `"apt"` → `"2.7.14"`).
     */
    suspend fun recenserVersions(contexte: StepContext): Map<String, String>

    /**
     * Composants **non critiques** en échec après les étapes (ADR 0089) :
     * une liste non vide fait passer la phase en `Degraded` au lieu de
     * `Succeeded` (ADR 0085 — § 5.4 : `cmdline-tools` seul concerné à ce
     * jour). Défaut : aucune (phases 1 à 3).
     *
     * @param contexte contexte d'exécution (commandes, journal).
     * @return les avertissements de composants, diagnostic complet.
     */
    suspend fun avertissements(contexte: StepContext): List<ComponentIssue> = emptyList()

    /**
     * Composants du manifeste installés et vérifiés par la phase (ADR
     * 0089) — l'orchestrateur les journalise dans `install-state.json`
     * (quadruplet d'immutabilité § 12.2.5 + `installPath` pour résoudre
     * `aapt2` depuis le plan). Défaut : aucun (phases 1 à 3).
     *
     * @param contexte contexte d'exécution (commandes, journal).
     * @return les composants installés, dans l'ordre du plan.
     */
    suspend fun composantsInstalles(contexte: StepContext): List<InstalledComponent> = emptyList()
}

/**
 * Assemblage des phases livrées (ADR 0087 § 1) : la carte de phases
 * porte des types `internal` du module — elle ne transite donc **jamais
 * par le graphe Hilt** (un `Map` générique y serait traité comme un
 * multibinding) ; l'orchestrateur la reçoit de cette fabrique, doublable
 * en test (§ 10 : machine d'états contre des phases scriptées).
 */
internal interface FabriquePhasesInstallation {
    /**
     * Assemble les phases livrées — l'implémentation de production
     * dérive la racine `filesDir` elle-même.
     *
     * @return phase → implémentation, dans l'ordre du parcours.
     */
    fun assembler(): Map<InstallPhase, PhaseInstallation>
}
