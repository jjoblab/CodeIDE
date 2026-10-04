package jo.codeide.core.testing

import jo.codeide.core.domain.ConfigurationEnvTerminal

/**
 * [ConfigurationEnvTerminal](jo.codeide.core.domain.ConfigurationEnvTerminal)
 * pilotable pour les tests : le test décide de l'état de complétude et de
 * l'identifiant retourné par [lancer], et observe les lancements demandés.
 *
 * @param completInitial valeur initiale de [estComplet] (faux par défaut :
 *        l'écran propose la configuration tant que le JDK ou le SDK manque).
 */
public class FakeConfigurationEnvTerminal(
    completInitial: Boolean = false,
) : ConfigurationEnvTerminal {
    /** Complétude courante — pilotable via [simulerComplet]. */
    private var complet = completInitial

    /** Identifiant retourné par le prochain [lancer] (null = rien à faire). */
    public var sessionIdSimulation: String? = "session-config"

    /** Identifiant retourné par le DERNIER [lancer] effectif, ou null. */
    public var dernierLancement: String? = null
        private set

    /** Nombre d'appels à [lancer] (garde anti-doublon à tester). */
    public var lancements: Int = 0
        private set

    override fun estComplet(): Boolean = complet

    override suspend fun lancer(): String? {
        lancements++
        return sessionIdSimulation.also { dernierLancement = it }
    }

    /** Pilote la complétude (le test simule la fin de la configuration). */
    public fun simulerComplet(complet: Boolean) {
        this.complet = complet
    }
}
