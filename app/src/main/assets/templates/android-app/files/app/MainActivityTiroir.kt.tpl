package {{packageName}}

import android.os.Bundle
{{#if avecNavigation}}
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
{{#else}}
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.GravityCompat
{{/if}}
import androidx.appcompat.app.AppCompatActivity
import {{packageName}}.databinding.ActivityMainTiroirBinding

/**
 * {{t:main.tiroir.kdoc}}
 */
class MainActivity : AppCompatActivity() {
    private lateinit var liaison: ActivityMainTiroirBinding
{{#if avecNavigation}}
{{else}}
    private lateinit var basculeTiroir: ActionBarDrawerToggle
{{/if}}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liaison = ActivityMainTiroirBinding.inflate(layoutInflater)
        setContentView(liaison.root)
{{#if avecNavigation}}
        // Navigation : les identifiants du menu du tiroir (tiroir_accueil,
        // tiroir_apropos) correspondent aux destinations du graphe
        // res/navigation/graphe_principal.xml — NavigationUI câble le tiroir
        // et la barre d'outils (bouton hamburger) automatiquement.
        val configuration = AppBarConfiguration(
            setOf(R.id.tiroir_accueil),
            liaison.tiroirRacine,
        )
        val controleur = findNavController(R.id.conteneur_fragments)
        NavigationUI.setupActionBarWithNavController(this, controleur, configuration)
        NavigationUI.setupWithNavController(liaison.vueNavigation, controleur)
{{#else}}
        basculeTiroir =
            ActionBarDrawerToggle(
                this,
                liaison.tiroirRacine,
                liaison.barreOutils,
                R.string.tiroir_ouvrir,
                R.string.tiroir_fermer,
            )
        liaison.tiroirRacine.addDrawerListener(basculeTiroir)

        liaison.vueNavigation.setNavigationItemSelectedListener { element ->
            when (element.itemId) {
                R.id.tiroir_apropos -> afficherEspace(FragmentAccueil.ESPACE_APROPOS)
                else -> afficherEspace(FragmentAccueil.ESPACE_ACCUEIL)
            }
            liaison.tiroirRacine.closeDrawer(GravityCompat.START)
            true
        }

        if (savedInstanceState == null) {
            liaison.vueNavigation.setCheckedItem(R.id.tiroir_accueil)
            afficherEspace(FragmentAccueil.ESPACE_ACCUEIL)
        }
{{/if}}
    }
{{#if avecNavigation}}

    override fun onSupportNavigateUp(): Boolean =
        findNavController(R.id.conteneur_fragments).navigateUp() || super.onSupportNavigateUp()
{{#else}}

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        basculeTiroir.syncState()
    }

    /** Affiche l'espace demandé dans le conteneur de fragments. */
    private fun afficherEspace(espace: String) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.conteneur_fragments, FragmentAccueil.nouveau(espace))
            .commit()
    }
{{/if}}
}
