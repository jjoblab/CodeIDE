package {{packageName}};

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
{{#if avecHilt}}
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;
{{/if}}
{{#if avecRoom}}
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
{{/if}}
import {{packageName}}.databinding.ActivityMainBinding;

/**
 * {{t:main.kdoc}}
 */
{{#if avecHilt}}
@AndroidEntryPoint
{{/if}}
public class MainActivity extends AppCompatActivity {
{{#if avecHilt}}
    /** {{t:greeter.kdoc}} */
    @Inject
    Greeter greeter;
{{/if}}
{{#if avecModuleBdd}}
    /** Base Room fournie par Hilt (voir ModuleDonnees). */
    @Inject
    BddLocale bdd;
{{/if}}
{{#if avecRoom}}
    /** Fond d'exécution des accès Room — jamais le thread UI. */
    private final ExecutorService fond = Executors.newSingleThreadExecutor();
{{/if}}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding liaison = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(liaison.getRoot());
{{#if sansHilt}}
        Greeter greeter = new Greeter("{{t:app.greeting}}");
{{/if}}
        liaison.texteBienvenue.setText(greeter.greet("{{appName|javaString}}"));
{{#if avecRoom}}
        // La base est instanciée puis employée : insertion et comptage hors
        // du thread UI ; requêtes validées par Room (KSP) à la compilation.
        fond.execute(() -> {
{{#if avecModuleBdd}}
            TacheEntity tache = new TacheEntity();
            tache.libelle = "{{t:tache.exemple}}";
            bdd.tacheDao().ajouter(tache);
            int nombre = bdd.tacheDao().compter();
{{#else}}
            BddLocale base = BddLocale.creer(this);
            TacheEntity tache = new TacheEntity();
            tache.libelle = "{{t:tache.exemple}}";
            base.tacheDao().ajouter(tache);
            int nombre = base.tacheDao().compter();
{{/if}}
            Log.d("MainActivity", "{{t:tache.comptage}} " + nombre);
        });
{{/if}}
    }
{{#if avecRoom}}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        fond.shutdown();
    }
{{/if}}
}
