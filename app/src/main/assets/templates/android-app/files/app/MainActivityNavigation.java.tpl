package {{packageName}};

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
{{#if avecHilt}}
import dagger.hilt.android.AndroidEntryPoint;
{{/if}}
import {{packageName}}.databinding.ActivityMainBinding;

/**
 * {{t:main.navigation.kdoc}}
 */
{{#if avecHilt}}
@AndroidEntryPoint
{{/if}}
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding liaison = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(liaison.getRoot());
        // Le NavHostFragment de activity_main.xml affiche la première
        // destination du graphe (voir res/navigation/graphe_principal.xml) ;
        // l'activité n'orchestre rien — c'est le contrôleur de navigation.
        // La démonstration d'injection Hilt (si cochée) vit dans
        // BienvenueFragment, écran d'accueil du graphe.
    }
}
