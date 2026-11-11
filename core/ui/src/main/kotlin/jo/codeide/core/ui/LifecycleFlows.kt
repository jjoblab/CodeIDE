package jo.codeide.core.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collecte un [Flow] uniquement pendant que [owner] est au moins dans
 * l'état [minActiveState] (patron UDF, section 5.3 : le fragment rend
 * l'état en `repeatOnLifecycle(STARTED)`).
 *
 * La collecte redémarre à zéro à chaque retour dans l'état actif : les
 * `StateFlow` renvoient leur valeur courante, les flux froids rejouent
 * depuis le début. Ne collectez jamais dans `onViewCreated` sans cette
 * garde, sous peine de fuites et de rendus après `onStop`.
 *
 * Exemple dans un fragment :
 * ```kotlin
 * viewModel.uiState.collectWithLifecycle(viewLifecycleOwner) { etat ->
 *     rendre(etat)
 * }
 * ```
 *
 * @param owner propriétaire du cycle de vie qui pilote la collecte
 * (`viewLifecycleOwner` dans un fragment).
 * @param minActiveState état minimal pour collecter — [Lifecycle.State.STARTED]
 * par défaut, conformément à la recommandation AndroidX.
 * @param collector action exécutée pour chaque valeur émise.
 * @param T type des valeurs du flux.
 */
public fun <T> Flow<T>.collectWithLifecycle(
    owner: LifecycleOwner,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    collector: (T) -> Unit,
) {
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(minActiveState) {
            this@collectWithLifecycle.collect { collector(it) }
        }
    }
}
