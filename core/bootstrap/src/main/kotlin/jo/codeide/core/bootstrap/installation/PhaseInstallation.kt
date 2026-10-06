package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.StepContext

/**
 * Une phase du parcours d'installation, vue de l'orchestrateur
 * (ADR 0087 § 1) : la liste **ordonnée** de ses étapes et le recensement
 * des versions installées (pour `PhaseState.Succeeded.versions`).
 *
 * E2 livre `BOOTSTRAP` et `PACKAGE_TOOLS` ; E3 ajoutera `JAVA`, E4
 * `ANDROID_SDK` — l'orchestrateur s'arrête à la première phase absente
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
