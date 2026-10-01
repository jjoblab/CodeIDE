package {{packageName}};

import android.os.Bundle;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import {{packageName}}.databinding.ActivityMainTiroirBinding;

/**
 * {{t:main.tiroir.kdoc}}
 */
public class MainActivity extends AppCompatActivity {
    private ActivityMainTiroirBinding liaison;
    private ActionBarDrawerToggle basculeTiroir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        liaison = ActivityMainTiroirBinding.inflate(getLayoutInflater());
        setContentView(liaison.getRoot());

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
    }

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
}
