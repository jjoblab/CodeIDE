package {{packageName}};

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
{{#if avecRoom}}
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
{{/if}}
{{#if avecHilt}}
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;
{{/if}}
import {{packageName}}.databinding.FragmentBienvenueBinding;

/**
 * {{t:bienvenue.kdoc}}
 */
{{#if avecHilt}}
@AndroidEntryPoint
{{/if}}
public class BienvenueFragment extends Fragment {
{{#if avecHilt}}
    /** Salutation fournie par Hilt — démonstration d'une injection réelle. */
    @Inject
    Greeter greeter;
{{/if}}
{{#if avecRoom}}
    /** Fond d'exécution des accès Room — jamais le thread UI. */
    private final ExecutorService fond = Executors.newSingleThreadExecutor();
{{/if}}

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        FragmentBienvenueBinding liaison =
                FragmentBienvenueBinding.inflate(inflater, container, false);
{{#if sansHilt}}
        Greeter greeter = new Greeter("{{t:app.greeting}}");
{{/if}}
        liaison.texteBienvenue.setText(greeter.greet(getString(R.string.app_name)));
{{#if avecRoom}}
        // La base est instanciée puis employée (requêtes validées par
        // Room/KSP à la compilation).
        fond.execute(() -> {
            BddLocale base = BddLocale.creer(requireContext());
            TacheEntity tache = new TacheEntity();
            tache.libelle = "{{t:tache.exemple}}";
            base.tacheDao().ajouter(tache);
            int nombre = base.tacheDao().compter();
            Log.d("BienvenueFragment", "{{t:tache.comptage}} " + nombre);
        });
{{/if}}
        return liaison.getRoot();
    }
{{#if avecRoom}}

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        fond.shutdown();
    }
{{/if}}
}
