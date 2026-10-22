package jo.codeide.feature.preview

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import jo.codeide.preview.xml.CataloguePalette
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Les ICÔNES de la grille de tuiles (v0.166.3 — extension « palette plus
 * complète ») : chaque entrée du catalogue suit la CONVENTION
 * `ic_tuile_<id>` (PE7c, [TuilesPaletteAdapter]) — une icône MANQUANTE
 * laisserait la tuile recyclée porter l'icône de la tuile PRÉCÉDENTE (le
 * liage ne pose l'image QUE si getIdentifier la trouve). Le gonflage
 * Robolectric valide le RESSOURCE + le DÉBALLAGE : un `pathData` mal
 * formé lèverait à l'inflation, jamais à la compilation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class IconesTuilesPaletteTest {
    @Test
    public fun `chaque entree du catalogue a son icone ic_tuile gonflable`() {
        val contexte = ApplicationProvider.getApplicationContext<Context>()
        for (entree in CataloguePalette.CATALOGUE) {
            val identifiant =
                contexte.resources.getIdentifier(
                    "ic_tuile_" + entree.id,
                    "drawable",
                    contexte.packageName,
                )
            assertTrue("icône absente pour la tuile ${entree.id}", identifiant != 0)
            // Le déballage PARSE le pathData quand l'implémentation réelle le
            // fait : une erreur de syntaxe lève ici, pas en production.
            val icone = ContextCompat.getDrawable(contexte, identifiant)
            assertTrue("icône ingonflable pour la tuile ${entree.id}", icone != null)
        }
    }
}
