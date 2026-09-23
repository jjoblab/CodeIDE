package jo.codeide.core.logging

import jo.codeide.core.domain.LogVerbosityApplier
import jo.codeide.core.model.LogVerbosity
import jo.codeide.core.model.toLogLevel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applique la verbosité de journalisation **persistée** au moteur
 * (branchement de l'étape 4 : `AppSettings.logLevel` devient la source
 * de vérité du niveau minimal, section 5.7).
 *
 * Pourquoi ce point d'entrée dédié : le détenteur de configuration est
 * interne au module (le moteur et le sink fichier le lisent à chaud) ;
 * `app` ne doit pas manipuler les rouages du pipeline. Cette façade
 * publique est l'unique point de bascule du niveau à l'exécution.
 *
 * L'effet est immédiat sur les entrées suivantes, sans interruption du
 * pipeline : les autres bornes (taille, rétention) restent celles de la
 * configuration initiale — le réglage utilisateur ne touche que le
 * niveau.
 *
 * Contexte d'exécution attendu : appelé depuis le collecteur des
 * paramètres dans le **processus principal** uniquement (le processus
 * `:crash` ne lit jamais les paramètres, section 5.8) ; jamais bloquant,
 * jamais d'I/O.
 */
@Singleton
internal class LogLevelApplier
    @Inject
    constructor(
        private val holder: LogConfigHolder,
    ) : LogVerbosityApplier {
        /**
         * Bascule le niveau minimal du moteur selon la verbosité
         * utilisateur (`NORMAL` → `INFO`, `DETAILED` → `DEBUG`).
         *
         * @param verbosity verbosité lue dans les paramètres persistés.
         */
        override fun apply(verbosity: LogVerbosity) {
            holder.update(holder.read().copy(minLevel = verbosity.toLogLevel()))
        }
    }
