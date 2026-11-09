package {{packageName}}

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
{{#if avecCoroutines}}
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
{{/if}}
{{#if avecRoom}}
import java.util.concurrent.Executors
{{/if}}
{{#if avecHilt}}
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
{{/if}}
import {{packageName}}.databinding.FragmentBienvenueBinding

/**
 * {{t:bienvenue.kdoc}}
 */
{{#if avecHilt}}
@AndroidEntryPoint
{{/if}}
class BienvenueFragment : Fragment() {
{{#if avecHilt}}
    /** Salutation fournie par Hilt — démonstration d'une injection réelle. */
    @Inject
    lateinit var greeter: Greeter
{{/if}}
{{#if avecRoom}}
    /** Fond d'exécution des accès Room — jamais le thread UI. */
    private val fond = Executors.newSingleThreadExecutor()
{{/if}}

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val liaison = FragmentBienvenueBinding.inflate(inflater, container, false)
{{#if sansHilt}}
        val greeter = Greeter("{{t:app.greeting}}")
{{/if}}
{{#if avecCoroutines}}
        // Usage réel de Coroutines : calcul hors thread principal.
        CoroutineScope(Dispatchers.Main).launch {
            val message = withContext(Dispatchers.Default) {
                greeter.greet(getString(R.string.app_name))
            }
            liaison.texteBienvenue.text = message
        }
{{#else}}
        liaison.texteBienvenue.text = greeter.greet(getString(R.string.app_name))
{{/if}}
{{#if avecRoom}}
        // La base est instanciée puis employée (requêtes validées par
        // Room/KSP à la compilation).
        fond.execute {
            val base = BddLocale.creer(requireContext())
            base.tacheDao().ajouter(TacheEntity(libelle = "{{t:tache.exemple}}"))
            val nombre = base.tacheDao().compter()
            Log.d("BienvenueFragment", "{{t:tache.comptage}} $nombre")
        }
{{/if}}
        return liaison.root
    }
{{#if avecRoom}}

    override fun onDestroyView() {
        super.onDestroyView()
        fond.shutdown()
    }
{{/if}}
}
