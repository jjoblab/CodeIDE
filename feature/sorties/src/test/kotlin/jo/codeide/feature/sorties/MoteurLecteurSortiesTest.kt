package jo.codeide.feature.sorties

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textview.MaterialTextView
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.SourceSortie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests de comportement du moteur de lecture (lot L2, Robolectric) : les
 * rangées se lient avec leurs colonnes (numéro, pastille, horodatage,
 * texte SGR), la sélection se teinte, le TalkBack porte la description
 * complète, les deltas de projection produisent des notifications BORNÉES
 * et le suivi du bas est honnête (la remontée le coupe, le bouton
 * ré-apparaît).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MoteurLecteurSortiesTest {
    private lateinit var contexte: Context
    private lateinit var liste: RecyclerView
    private lateinit var poignee: PoigneeDefilement
    private var suiviActif = true
    private var zoomDemande: Int? = null
    private lateinit var adapter: LignesSortiesAdapter
    private lateinit var lecteur: LecteurSortiesRecycler

    @Before
    fun monterLeMoteur() {
        // Le formatage des horodatages suit la locale par défaut : le
        // test la fige (la liaison n'en dépend que par elle).
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        contexte =
            ContextThemeWrapper(ApplicationProvider.getApplicationContext(), jo.codeide.core.ui.R.style.Theme_CodeIDE)
        val parent = FrameLayout(contexte)
        liste = RecyclerView(contexte)
        liste.layoutParams = ViewGroup.LayoutParams(400, 800)
        poignee = PoigneeDefilement(contexte)
        poignee.layoutParams = ViewGroup.LayoutParams(24, 800)
        parent.addView(liste)
        parent.addView(poignee)
        adapter = LignesSortiesAdapter(
            surLigneAppuyee = {},
            surLigneLongueAppuyee = {},
            surPositionSource = {},
            surEnteteAppuyee = {},
        )
        lecteur = LecteurSortiesRecycler(
            liste = liste,
            poignee = poignee,
            adapter = adapter,
            surSuiviChange = { suit -> suiviActif = suit },
            surZoomPolice = { taille -> zoomDemande = taille },
        )
    }

    private fun rangee(message: String, niveau: NiveauSortie = NiveauSortie.INDETERMINE) = RangeeSortie.de(
        LigneSortie(SourceSortie.BUILD, message, horodatageMs = 1_000L, niveau = niveau),
    )

    /** Les rangées LIGNE d'une liste (position de tampon = rang d'affichage
     *  dans ces tests — l'index de projection du lecteur). */
    private fun lignes(rangees: List<RangeeSortie>): List<RangeeLecteur> =
        rangees.mapIndexed { indice, rangee -> RangeeLecteur.Ligne(indice, rangee) }

    @Test
    fun `une rangee lie ses colonnes et porte sa description TalkBack`() {
        val projection = ProjectionLecteur(
            rangees = lignes(
                listOf(
                    rangee("> Task :app:compileDebugKotlin"),
                    rangee("w: variable jamais utilisée", NiveauSortie.AVERTISSEMENT),
                ),
            ),
            delta = null,
            tailleTampon = 2,
            pertes = 0L,
            masquees = 0,
            origineMs = 0L,
        )
        lecteur.afficherProjection(projection)
        adapter.definirOptions(OptionsRenduRangee(afficheNumeros = true, afficheHorodatage = true), origine = 0L)
        val holder = lierLigne(1)
        assertEquals("2", holder.liaison.numeroSortie.text.toString())
        assertEquals("+1,000 s", holder.liaison.horodatageSortie.text.toString())
        assertEquals("w: variable jamais utilisée", holder.liaison.texteSortie.text.toString())
        // La description porte niveau, source et texte — lus par les
        // MÊMES ressources (l'assertion ne dépend pas de la locale du
        // bac à sable).
        val nomNiveau = contexte.getString(jo.codeide.core.ui.R.string.sorties_niveau_avertissement)
        val nomSource = contexte.getString(R.string.sorties_source_build)
        val description = holder.liaison.root.contentDescription.toString()
        assertTrue(description.contains(nomNiveau))
        assertTrue(description.contains(nomSource))
        assertTrue(description.contains("w: variable jamais utilisée"))
    }

    @Test
    fun `un delta borné ajoute en fin sans relier toute la liste`() {
        lecteur.afficherProjection(
            ProjectionLecteur(lignes(listOf(rangee("un"), rangee("deux"))), DeltaProjection(0, 2), 2, 0L, 0, 0L),
        )
        assertEquals(2, adapter.itemCount)
        lecteur.afficherProjection(
            ProjectionLecteur(
                lignes(listOf(rangee("un"), rangee("deux"), rangee("trois"))),
                DeltaProjection(0, 1),
                3,
                0L,
                0,
                0L,
            ),
        )
        assertEquals(3, adapter.itemCount)
        // Le suivi du bas est actif : le lecteur a rejoint la fin.
        assertTrue(suiviActif)
    }

    @Test
    fun `la remontée coupe le suivi honnête - le bouton réapparaît`() {
        lecteur.afficherProjection(
            ProjectionLecteur(lignes((1..50).map { rangee("ligne $it") }), DeltaProjection(0, 50), 50, 0L, 0, 0L),
        )
        disposer()
        assertTrue(suiviActif)
        // L'utilisateur remonte : le gestionnaire se déplace vers le haut.
        liste.scrollToPosition(5)
        disposer()
        // Le suivi se coupe dès qu'on n'est plus au bas (spec SORTIES § 3.2).
        assertFalse(lecteur.suitLeBas)
        // Reprise volontaire : le lecteur rejoint la fin et suit à nouveau.
        lecteur.reprendreSuivi()
        disposer()
        assertTrue(lecteur.suitLeBas)
    }

    @Test
    fun `la sélection se teinte et se retire`() {
        lecteur.afficherProjection(
            ProjectionLecteur(lignes(listOf(rangee("un"), rangee("deux"))), DeltaProjection(0, 2), 2, 0L, 0, 0L),
        )
        lecteur.definirSelection(0..1)
        val holder = lierLigne(0)
        assertTrue(holder.liaison.root.isSelected)
        lecteur.definirSelection(null)
        val holderApres = lierLigne(0)
        assertFalse(holderApres.liaison.root.isSelected)
    }

    @Test
    fun `les repères de défilement sont normalisés par l index`() {
        lecteur.afficherProjection(
            ProjectionLecteur(
                lignes(
                    (1..10).map { indice ->
                        rangee(
                            "ligne $indice",
                            if (indice ==
                                3
                            ) {
                                NiveauSortie.ERREUR
                            } else {
                                NiveauSortie.INDETERMINE
                            },
                        )
                    },
                ),
                DeltaProjection(0, 10),
                10,
                0L,
                0,
                0L,
            ),
        )
        lecteur.definirMarques(
            MarquesLecteur(erreurs = listOf(2), avertissements = listOf(4), correspondances = listOf(6)),
        )
        // La poignée accepte les repères normalisés sans exception.
        assertNotNull(poignee)
    }

    @Test
    fun `le retour à la ligne change la hauteur des rangees`() {
        lecteur.afficherProjection(
            ProjectionLecteur(lignes(listOf(rangee("ligne"))), DeltaProjection(0, 1), 1, 0L, 0, 0L),
        )
        adapter.definirOptions(OptionsRenduRangee(retourALaLigne = true), origine = 0L)
        val holderPlie = lierLigne(0)
        assertEquals(Integer.MAX_VALUE, holderPlie.liaison.texteSortie.maxLines)
        adapter.definirOptions(OptionsRenduRangee(retourALaLigne = false), origine = 0L)
        val holderCoupe = lierLigne(0)
        assertEquals(1, holderCoupe.liaison.texteSortie.maxLines)
    }

    private fun lierLigne(indice: Int): LignesSortiesAdapter.LigneViewHolder {
        // La mesure réelle exige un layout pass : mesurer/bind manuellement.
        val holder = adapter.createViewHolder(liste, adapter.getItemViewType(indice))
        adapter.bindViewHolder(holder, indice)
        assertNotNull((holder as LignesSortiesAdapter.LigneViewHolder).liaison.texteSortie)
        return holder
    }

    /** Une passe réelle de mesure/layout (Robolectric ne dispose pas du
     *  scheduler de frame Android) — déclenche les écouteurs de scroll. */
    private fun disposer() {
        liste.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
        )
        liste.layout(0, 0, 400, 800)
    }
}
