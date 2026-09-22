package jo.codeide.core.logging

import jo.codeide.core.domain.LogConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Détenteur de la configuration de journalisation, lisible à chaud par le
 * moteur et le sink fichier sans dépendance circulaire entre eux.
 *
 * À l'étape 2, la valeur initiale vient du type de build (debug → tout
 * passer, release → INFO). À partir de l'étape 4, le réglage utilisateur
 * `AppSettings.logLevel` mettra à jour ce détenteur à l'exécution — un
 * unique point de bascule.
 */
@Singleton
internal class LogConfigHolder
    @Inject
    constructor(
        initial: LogConfig,
    ) {
        @Volatile
        private var current: LogConfig = initial

        /**
         * Lit la configuration courante.
         *
         * @return la configuration active (immuable).
         */
        fun read(): LogConfig = current

        /**
         * Remplace la configuration active — effet immédiat sur les entrées
         * suivantes, sans interruption du pipeline.
         *
         * @param config nouvelle configuration, validée par son constructeur.
         */
        fun update(config: LogConfig) {
            current = config
        }
    }
