package jo.codeide.feature.editor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.google.android.material.appbar.MaterialToolbar
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.sorties.FamilleSorties
import jo.codeide.feature.sorties.FragmentLecteurSorties

/**
 * L'éditeur dédié aux sorties EN PLEIN ÉCRAN (spec SORTIES § 5.7, jalon
 * M7 ; lot L5a) — la surface téléphone portrait : le lecteur du Centre
 * de sorties TEL QUEL (famille GRADLE), sous une barre d'outils
 * d'application minimale. La barre de recherche, les filtres, la pause,
 * l'effacement, le regroupé et la consultation vivent DANS le lecteur —
 * l'activité n'est PAS un second afficheur ni un second chrome : elle
 * n'ajoute que le retour et le titre.
 *
 * Ouverte depuis l'onglet central « Sorties » de l'espace de travail
 * (action « Plein écran ») — le composant est le MÊME : une exécution
 * consultée à l'endroit ou à l'autre vit sur le même flux (port
 * [jo.codeide.feature.sorties.FabriqueFluxSorties], ADR 0162).
 * NON exportée (manifest) : le seul chemin d'entrée est l'espace.
 */
@AndroidEntryPoint
public class EditeurSortiesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jo.codeide.core.ui.AppliquerApparence.rappliquer(this)
        setContentView(R.layout.activity_editeur_sorties)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.barre_editeur_sorties))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Le lecteur (famille GRADLE) : ajouté UNE fois, ses arguments et
        // son ViewModel survivent à la rotation (spéc SORTIES § 3.11 —
        // pivoter ne perd ni position, ni filtre, ni recherche).
        if (supportFragmentManager.findFragmentById(R.id.conteneur_lecteur_plein_ecran) == null) {
            supportFragmentManager.commit {
                replace(
                    R.id.conteneur_lecteur_plein_ecran,
                    FragmentLecteurSorties.nouvelle(FamilleSorties.GRADLE),
                )
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
