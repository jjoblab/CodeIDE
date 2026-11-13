package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrat des CANDIDATS du contrôle RÉFÉRENCE D'ID (PE12c — spec
 * `docs/PREVIEW_PARENTS.md` § 5.3, miroir `refCands` de la maquette) :
 * les portées TOUT / FRÈRES / SOUS_ARBRE, le GRISAGE des options qui
 * fermeraient une boucle ([RelationsParents.fermeraitBoucle]) et la
 * valeur INTROUVABLE — sur des arbres lus par [ArbreAttributsXml].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class ReferencesIdTest {
    /** Un RelativeLayout : bio (sans règle), stats (below bio), autre (sans règle). */
    private val relatif =
        """
        <RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content">

            <TextView
                android:id="@+id/bio"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />

            <TextView
                android:id="@+id/stats"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_below="@id/bio" />

            <Button
                android:id="@+id/autre"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content" />

        </RelativeLayout>
        """.trimIndent()

    /** Un CoordinatorLayout : barre (avec MaterialToolbar imbriquée), liste, fab (avec badge imbriqué). */
    private val coordonnateur =
        """
        <CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <AppBarLayout
                android:id="@+id/barre"
                android:layout_width="match_parent"
                android:layout_height="wrap_content">

                <MaterialToolbar
                    android:id="@+id/titre"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content" />

            </AppBarLayout>

            <RecyclerView
                android:id="@+id/liste"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />

            <FloatingActionButton
                android:id="@+id/fab"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content">

                <TextView
                    android:id="@+id/badge"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content" />

            </FloatingActionButton>

        </CoordinatorLayout>
        """.trimIndent()

    private fun reference(
        nom: String,
        portee: CatalogueAttributs.ControleAttribut.ReferenceId.Portee,
    ): CatalogueAttributs.ControleAttribut.ReferenceId =
        CatalogueAttributs.ControleAttribut.ReferenceId(nom, CatalogueAttributs.Etiquette.ANCRE, portee)

    @Test
    fun `la portee freres liste les freres directs - la boucle est grisee`() {
        val arbre = ArbreAttributsXml.lire(relatif)
        // indices pré-ordre : 0 = RelativeLayout, 1 = bio, 2 = stats, 3 = autre.
        val options =
            ReferencesId.options(
                arbre,
                1,
                reference("android:layout_below", CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES),
                null,
            )
        val ids = options.entrees.map { entree -> entree.id }
        assertEquals(listOf("stats", "autre"), ids)
        // stats a une règle vers bio : below = stats depuis bio FERMERAIT la
        // boucle bio → stats → bio (miroir l'exemple « À essayer » § 12).
        assertTrue(options.entrees.first { entree -> entree.id == "stats" }.boucle)
        // autre ne mène jamais à bio : l'option reste choisissable.
        assertFalse(options.entrees.first { entree -> entree.id == "autre" }.boucle)
        // La note FRÈRES accompagne le champ (spec § 5.3).
        assertEquals(ReferencesId.Note.FRERES, options.note)
    }

    @Test
    fun `la portee freres sans boucle possible ne grisent rien`() {
        val arbre = ArbreAttributsXml.lire(relatif)
        // Depuis autre (aucune règle vers lui) : aucun candidat ne fermerait de boucle.
        val options =
            ReferencesId.options(
                arbre,
                3,
                reference("android:layout_below", CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES),
                null,
            )
        assertEquals(listOf("bio", "stats"), options.entrees.map { entree -> entree.id })
        assertTrue(options.entrees.none { entree -> entree.boucle })
    }

    @Test
    fun `la portee tout couvre l arbre - la portee sous arbre de la vue ses descendants`() {
        val arbre = ArbreAttributsXml.lire(relatif)
        // TOUT depuis stats : tous les @id/ sauf lui (la racine n'a pas d'id).
        val tout =
            ReferencesId.options(
                arbre,
                2,
                reference("android:labelFor", CatalogueAttributs.ControleAttribut.ReferenceId.Portee.TOUT),
                null,
            )
        assertEquals(listOf("bio", "autre"), tout.entrees.map { entree -> entree.id })
        assertEquals(ReferencesId.Note.SANS, tout.note)
        // SOUS_ARBRE de la VUE (ignoreGravity sur le RelativeLayout) : ses
        // descendants — écart maquette consigné (spec § 5.3 fait foi).
        val sousArbre =
            ReferencesId.options(
                arbre,
                0,
                reference("android:ignoreGravity", CatalogueAttributs.ControleAttribut.ReferenceId.Portee.SOUS_ARBRE),
                null,
            )
        assertEquals(listOf("bio", "stats", "autre"), sousArbre.entrees.map { entree -> entree.id })
        assertEquals(ReferencesId.Note.SANS, sousArbre.note)
    }

    @Test
    fun `l ancre coordinateur vise le sous arbre du parent hors la vue et les siens`() {
        val arbre = ArbreAttributsXml.lire(coordonnateur)
        // indices : 0 = CoordinatorLayout, 1 = barre, 2 = titre, 3 = liste, 4 = fab, 5 = badge.
        val options =
            ReferencesId.options(
                arbre,
                4,
                reference("app:layout_anchor", CatalogueAttributs.ControleAttribut.ReferenceId.Portee.SOUS_ARBRE),
                null,
            )
        // Le sous-arbre du parent : barre + titre (imbriqué) + liste —
        // ni le parent lui-même, ni fab, ni badge (descendant de fab).
        assertEquals(listOf("barre", "titre", "liste"), options.entrees.map { entree -> entree.id })
        assertEquals(ReferencesId.Note.ANCRE, options.note)
    }

    @Test
    fun `la valeur introuvable se degage et le plus id se normalise`() {
        val arbre = ArbreAttributsXml.lire(relatif)
        // Une valeur absente de l'arbre devient l'option « (introuvable) ».
        val absente =
            ReferencesId.options(
                arbre,
                1,
                reference("android:layout_below", CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES),
                "@id/absent",
            )
        assertEquals("@id/absent", absente.introuvable)
        // Un @+id/ posé à la main se NORMALISE en @id/ : candidat trouvé.
        val normalisee =
            ReferencesId.options(
                arbre,
                1,
                reference("android:layout_below", CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES),
                "@+id/autre",
            )
        assertNull(normalisee.introuvable)
        // Une valeur vide ne produit RIEN (l'option « aucun » reste au liage).
        val vide =
            ReferencesId.options(
                arbre,
                1,
                reference("android:layout_below", CatalogueAttributs.ControleAttribut.ReferenceId.Portee.FRERES),
                null,
            )
        assertNull(vide.introuvable)
    }

    @Test
    fun `l indice du noeud suit le span exact puis l englobant`() {
        val arbre = ArbreAttributsXml.lire(coordonnateur)
        // Span EXACT du FloatingActionButton (indice 4).
        assertEquals(4, ReferencesId.indiceDe(arbre, arbre[4].debut, arbre[4].fin))
        // Un span dans la racine retombe sur le nœud ENGLOBANT (repli).
        assertEquals(0, ReferencesId.indiceDe(arbre, arbre[0].debut + 1, arbre[0].debut + 2))
        // Hors document : null (le contrôle dégrade sans candidats).
        assertNull(ReferencesId.indiceDe(arbre, coordonnateur.length + 100, coordonnateur.length + 101))
    }

    @Test
    fun `sans arbre les options se degradent - note et introuvable conservees`() {
        val options =
            ReferencesId.options(
                emptyList(),
                -1,
                reference("app:layout_anchor", CatalogueAttributs.ControleAttribut.ReferenceId.Portee.SOUS_ARBRE),
                "@id/parti",
            )
        assertTrue(options.entrees.isEmpty())
        assertEquals(ReferencesId.Note.ANCRE, options.note)
        assertEquals("@id/parti", options.introuvable)
    }
}
