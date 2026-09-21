package jo.codeide.core.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Fournit les dispatchers de coroutines à toute l'application (règle 5 du
 * prompt maître : les dispatchers sont **injectés**, jamais codés en dur).
 *
 * Toute classe qui lance des coroutines reçoit un `DispatcherProvider` par
 * constructeur ; en test, [jo.codeide.core.testing.TestDispatcherProvider]
 * fournit un dispatcher de test unique, ce qui rend l'ordonnancement des
 * coroutines déterministe et contrôlable.
 *
 * Contexte d'exécution attendu : aucune des propriétés ne bloque ni ne
 * doit être appelée depuis un thread particulier — ce sont des accesseurs
 * de dispatchers, utilisables comme `withContext(provider.io) { … }`.
 */
public interface DispatcherProvider {
    /** Dispatcher dédié aux entrées/sorties (disque, réseau, SAF). */
    public val io: CoroutineDispatcher

    /** Dispatcher dédié aux calculs lourds (moteur de templates, hachage). */
    public val default: CoroutineDispatcher

    /** Dispatcher de l'interface utilisateur (thread principal sur Android). */
    public val main: CoroutineDispatcher
}

/**
 * Implémentation de référence de [DispatcherProvider], branchée sur les
 * dispatchers réels de `kotlinx.coroutines`.
 *
 * Sur Android, `main` est fourni par `kotlinx-coroutines-android`
 * (dépendance d'`app`) ; dans un module JVM pur, l'accès à `main` sans
 * bibliothèque dédiée échoue — les tests l'installent via
 * `Dispatchers.setMain`.
 */
public class DefaultDispatcherProvider
    @Inject
    constructor() : DispatcherProvider {
        public override val io: CoroutineDispatcher
            get() = Dispatchers.IO

        public override val default: CoroutineDispatcher
            get() = Dispatchers.Default

        public override val main: CoroutineDispatcher
            get() = Dispatchers.Main
    }
