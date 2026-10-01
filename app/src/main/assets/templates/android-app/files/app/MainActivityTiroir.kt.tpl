package {{packageName}}

import android.os.Bundle
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import {{packageName}}.databinding.ActivityMainTiroirBinding

/**
 * {{t:main.tiroir.kdoc}}
 */
class MainActivity : AppCompatActivity() {
    private lateinit var liaison: ActivityMainTiroirBinding
    private lateinit var basculeTiroir: ActionBarDrawerToggle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liaison = ActivityMainTiroirBinding.inflate(layoutInflater)
        setContentView(liaison.root)

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
    }

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
}
