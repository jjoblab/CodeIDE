package {{packageName}}

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
{{#if avecHilt}}
import dagger.hilt.android.AndroidEntryPoint
{{/if}}
import {{packageName}}.databinding.ActivityMainBinding

/**
 * {{t:main.navigation.kdoc}}
 */
{{#if avecHilt}}
@AndroidEntryPoint
{{/if}}
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val liaison = ActivityMainBinding.inflate(layoutInflater)
        setContentView(liaison.root)
        // Le NavHostFragment de activity_main.xml affiche la première
        // destination du graphe (voir res/navigation/graphe_principal.xml) ;
        // l'activité n'orchestre rien — c'est le contrôleur de navigation.
        // La démonstration d'injection Hilt (si cochée) vit dans
        // BienvenueFragment, écran d'accueil du graphe.
    }
}
