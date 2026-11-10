package jo.codeide.feature.sorties

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.collectWithLifecycle
import javax.inject.Inject

/**
 * L'écran de diagnostic « banc sorties » (docs/SORTIES_BANC_MESURE.md,
 * lot L2) — le mode banc du composant lui-même : arme une séquence
 * calibrée de [SourceSortiesFactice] (S1 débit, S2 tampon plein, S6
 * pause/reprise) et héberge le lecteur du Centre de sorties TEL QUEL.
 * Aucun serveur de build n'est nécessaire pour la mesure d'affichage
 * (§ 2 du banc).
 *
 * Ouverte par la carte de diagnostic de la configuration tooling
 * (feature:editor — arête feature:editor → feature:sorties, ADR 0162) ;
 * NON exportée. Coût nul par défaut : sans armement, la source n'émet
 * rien et le lecteur reste sur son état vide honnête.
 *
 * Les compteurs affichés (lignes ingérées, pertes, masquées) sont la
 * vérité du banc § 4.2 : une mesure est rejetée si la source a perdu des
 * lots — le lecteur les compte.
 */
@AndroidEntryPoint
public class BancSortiesActivity : AppCompatActivity() {
    @Inject
    lateinit var source: SourceSortiesFactice

    private val viewModel: LecteurSortiesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jo.codeide.core.ui.AppliquerApparence.rappliquer(this)
        setContentView(R.layout.activity_banc_sorties)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.barre_banc))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (supportFragmentManager.findFragmentById(R.id.conteneur_lecteur) == null) {
            supportFragmentManager.commit {
                replace(R.id.conteneur_lecteur, FragmentLecteurSorties())
            }
        }

        val champSequence =
            findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.saisie_sequence)
        val libelles = SequenceBanc.entries.map { libelle -> getString(libelleDe(libelle)) }
        champSequence.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, libelles),
        )
        champSequence.setText(libelles.first(), false)
        champSequence.setOnItemClickListener { _, _, position, _ ->
            armer(SequenceBanc.entries[position])
        }

        findViewById<MaterialButton>(R.id.bouton_lancer).setOnClickListener {
            armer(sequenceCourante(champSequence.text.toString()))
        }
        findViewById<MaterialButton>(R.id.bouton_arreter).setOnClickListener {
            source.desarmer()
        }

        viewModel.projection.collectWithLifecycle(this) { projection ->
            findViewById<MaterialTextView>(R.id.compteurs_banc).text = getString(
                R.string.sorties_banc_compteurs,
                projection.tailleTampon,
                projection.pertes,
                projection.masquees,
            )
        }

        // Le banc est armé par l'EXTRA de lancement (carte de diagnostic)
        // — l'armement PRÉCÈDE la création du ViewModel, la fenêtre du
        // tampon est celle de la séquence dès le départ.
        val sequenceDemandee = intent.getStringExtra(EXTRA_SEQUENCE)
        val sequence = sequenceDemandee?.let { nom ->
            SequenceBanc.entries.firstOrNull { it.name == nom }
        }
        if (sequence != null) {
            champSequence.setText(getString(libelleDe(sequence)), false)
            armer(sequence)
        }
    }

    /** La séquence courante du champ (par libellé affiché). */
    private fun sequenceCourante(libelle: String): SequenceBanc =
        SequenceBanc.entries.firstOrNull { getString(libelleDe(it)) == libelle }
            ?: SequenceBanc.S1_DEBIT_INGESTION

    /** Arme la séquence (la source commence à émettre). */
    private fun armer(sequence: SequenceBanc) {
        source.armer(sequence)
    }

    /** Le libellé localisé d'une séquence (pas de libellé dans le modèle). */
    private fun libelleDe(sequence: SequenceBanc): Int = when (sequence) {
        SequenceBanc.S1_DEBIT_INGESTION -> R.string.sorties_banc_sequence_s1
        SequenceBanc.S2_TAMPON_PLEIN -> R.string.sorties_banc_sequence_s2
        SequenceBanc.S6_PAUSE_REPRISE -> R.string.sorties_banc_sequence_s6
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private companion object {
        /** L'extra de lancement : le nom de la séquence à armer. */
        const val EXTRA_SEQUENCE = "sorties_banc_sequence"
    }
}
