package {{packageName}}

import android.os.Bundle
{{#if avecRoom}}
import android.util.Log
import java.util.concurrent.Executors
{{/if}}
{{#if avecHilt}}
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
{{/if}}
{{#if sansHilt}}
import androidx.appcompat.app.AppCompatActivity
{{/if}}
{{#if avecCoroutines}}
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
{{/if}}
import {{packageName}}.databinding.ActivityMainBinding

/**
 * {{t:main.kdoc}}
 */
{{#if avecHilt}}
@AndroidEntryPoint
{{/if}}
class MainActivity : AppCompatActivity() {
{{#if avecHilt}}
    /** {{t:greeter.kdoc}} */
    @Inject
    lateinit var greeter: Greeter
{{/if}}
{{#if avecModuleBdd}}
    /** Base Room fournie par Hilt (voir ModuleDonnees). */
    @Inject
    lateinit var bdd: BddLocale
{{/if}}
{{#if avecRoom}}
    /** Fond d'exécution des accès Room — jamais le thread UI. */
    private val fond = Executors.newSingleThreadExecutor()
{{/if}}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val liaison = ActivityMainBinding.inflate(layoutInflater)
        setContentView(liaison.root)
{{#if sansHilt}}
        val greeter = Greeter("{{t:app.greeting}}")
{{/if}}
{{#if avecCoroutines}}
        // Usage réel de Coroutines : le message est calculé hors du thread
        // principal puis affiché sur Dispatchers.Main.
        CoroutineScope(Dispatchers.Main).launch {
            val message = withContext(Dispatchers.Default) {
                greeter.greet("{{appName|kotlinString}}")
            }
            liaison.texteBienvenue.text = message
        }
{{#else}}
        liaison.texteBienvenue.text = greeter.greet("{{appName|kotlinString}}")
{{/if}}
{{#if avecRoom}}
        // La base est instanciée puis employée : insertion et comptage hors
        // du thread UI ; les requêtes sont validées par Room (KSP) à la
        // compilation.
        fond.execute {
{{#if avecModuleBdd}}
            bdd.tacheDao().ajouter(TacheEntity(libelle = "{{t:tache.exemple}}"))
            val nombre = bdd.tacheDao().compter()
{{#else}}
            val base = BddLocale.creer(this@MainActivity)
            base.tacheDao().ajouter(TacheEntity(libelle = "{{t:tache.exemple}}"))
            val nombre = base.tacheDao().compter()
{{/if}}
            Log.d("MainActivity", "{{t:tache.comptage}} $nombre")
        }
{{/if}}
    }
{{#if avecRoom}}

    override fun onDestroy() {
        super.onDestroy()
        fond.shutdown()
    }
{{/if}}
}
