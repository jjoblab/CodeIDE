package {{packageName}};

import android.os.Bundle;
{{#if avecNavigation}}
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
{{#else}}
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.core.view.GravityCompat;
{{/if}}
import androidx.appcompat.app.AppCompatActivity;
import {{packageName}}.databinding.ActivityMainTiroirBinding;

/**
 * {{t:main.tiroir.kdoc}}
 */
public class MainActivity extends AppCompatActivity {
    private ActivityMainTiroirBinding liaison;
{{#if avecNavigation}}
{{#else}}
    private ActionBarDrawerToggle basculeTiroir;
{{/if}}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liaison = ActivityMainTiroirBinding.inflate(getLayoutInflater());
        setContentView(liaison.getRoot());
{{#if avecNavigation}}
        // Navigation : les identifiants du menu du tiroir (tiroir_accueil,
        // tiroir_apropos) correspondent aux destinations du graphe
        // res/navigation/graphe_principal.xml — NavigationUI câble le tiroir
        // et la barre d'outils (bouton hamburger) automatiquement.
        AppBarConfiguration configuration =
                new AppBarConfiguration.Builder(R.id.tiroir_accueil)
                        .setOpenableLayout(liaison.tiroirRacine)
                        .build();
        NavController controleur =
                Navigation.findNavController(this, R.id.conteneur_fragments);
        NavigationUI.setupActionBarWithNavController(this, controleur, configuration);
        NavigationUI.setupWithNavController(liaison.vueNavigation, controleur);
{{#else}}
        basculeTiroir = new ActionBarDrawerToggle(
                this,
                liaison.tiroirRacine,
                liaison.barreOutils,
                R.string.tiroir_ouvrir,
                R.string.tiroir_fermer);
        liaison.tiroirRacine.addDrawerListener(basculeTiroir);

        liaison.vueNavigation.setNavigationItemSelectedListener(item -> {
            if (item.getItemId() == R.id.tiroir_apropos) {
                afficherEspace(FragmentAccueil.ESPACE_APROPOS);
            } else {
                afficherEspace(FragmentAccueil.ESPACE_ACCUEIL);
            }
            liaison.tiroirRacine.closeDrawer(GravityCompat.START);
            return true;
        });

        if (savedInstanceState == null) {
            liaison.vueNavigation.setCheckedItem(R.id.tiroir_accueil);
            afficherEspace(FragmentAccueil.ESPACE_ACCUEIL);
        }
{{/if}}
    }
{{#if avecNavigation}}

    @Override
    public boolean onSupportNavigateUp() {
        return Navigation.findNavController(this, R.id.conteneur_fragments).navigateUp()
                || super.onSupportNavigateUp();
    }
{{#else}}

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        basculeTiroir.syncState();
    }

    /** Affiche l'espace demandé dans le conteneur de fragments. */
    private void afficherEspace(String espace) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.conteneur_fragments, FragmentAccueil.nouveau(espace))
                .commit();
    }
{{/if}}
}
