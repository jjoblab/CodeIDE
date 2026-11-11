package jo.codeide.preview.compose

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * C2 — Les RÉSOLVEURS du projet au service de la composition
 * (réutilisation PE1 : mêmes classes que le moteur XML du même
 * processus `:preview`). Verrouille contre une FIXTURE de res/ réelle :
 *
 *  - le dossier `res/` ABSENT/inexistant rend `null` (repli défaults
 *    runtime C1, jamais d'exception) ;
 *  - les COULEURS suivent le régime nuit (`values-night/`) ;
 *  - les CHAÎNES suivent la langue (`values-fr/`) avec repli défaut ;
 *  - les DIMENSIONS arrivent en DP (convertisseur posé à 160 dpi) ;
 *  - le THÈME du manifeste résout les SLOTS Material par leurs
 *    attributs XML (`colorPrimary` → `primary`) ; un slot hors table
 *    ne résout PAS (null honnête) ;
 *  - le SCHÉMA de la composition applique les slots résolus PAR-DESSUS
 *    les défaults du runtime (jour et nuit), les slots absents
 *    RESTENT aux défaults ;
 *  - le CACHE rend le MÊME résolveur pour la même combinaison (un scan
 *    disque par changement, pas par consultation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResolveursComposeTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()

    /** Le dossier res/ de la fixture (recréé par test). */
    private lateinit var dossierRes: String

    @Before
    fun preparer() {
        val base = Files.createTempDirectory("res-c2")
        ecrire(base, "values/colors.xml", """<resources><color name="accent">#FF43559E</color></resources>""")
        ecrire(base, "values-night/colors.xml", """<resources><color name="accent">#FF101020</color></resources>""")
        ecrire(base, "values/strings.xml", """<resources><string name="bonjour">Hello</string></resources>""")
        ecrire(base, "values-fr/strings.xml", """<resources><string name="bonjour">Salut</string></resources>""")
        ecrire(base, "values/dimens.xml", """<resources><dimen name="carton">16dp</dimen></resources>""")
        ecrire(
            base,
            "values/themes.xml",
            """<resources><style name="Theme.MonApp" parent="Theme.Material3.DayNight">""" +
                """<item name="colorPrimary">@color/accent</item>""" +
                """<item name="colorOnPrimary">#FFFFFFFF</item></style></resources>""",
        )
        dossierRes = base.toString()
    }

    private fun ecrire(base: java.nio.file.Path, relatif: String, contenu: String) {
        val fichier = base.resolve(relatif)
        Files.createDirectories(fichier.parent)
        Files.write(fichier, contenu.toByteArray(Charsets.UTF_8))
    }

    private fun resolveurs() = ResolveursCompose(contexte)

    @Test
    fun `dossier absent - null honnête (repli défaults runtime C1)`() {
        assertNull(
            "dossierRes null",
            resolveurs().pour(null, nuit = false, langue = null, themeName = null, densiteDpi = 440),
        )
        assertNull(
            "dossier inexistant",
            resolveurs().pour(
                "/chemin/qui/nexiste/pas",
                nuit = false,
                langue = null,
                themeName = null,
                densiteDpi = 440,
            ),
        )
    }

    @Test
    fun `couleurs - le régime nuit suit values-night`() {
        val resolveurs = resolveurs()
        val jour = resolveurs.pour(dossierRes, nuit = false, langue = null, themeName = null, densiteDpi = 440)!!
        val nuit = resolveurs.pour(dossierRes, nuit = true, langue = null, themeName = null, densiteDpi = 440)!!
        assertEquals(0xFF43559E.toInt(), jour.couleur("accent"))
        assertEquals(0xFF101020.toInt(), nuit.couleur("accent"))
        assertNull("ressource inconnue : null, jamais d'exception", jour.couleur("inexistante"))
    }

    @Test
    fun `chaines - la langue suit values-fr avec repli defaut`() {
        val resolveurs = resolveurs()
        val defaut = resolveurs.pour(dossierRes, nuit = false, langue = null, themeName = null, densiteDpi = 440)!!
        val francais = resolveurs.pour(dossierRes, nuit = false, langue = "fr", themeName = null, densiteDpi = 440)!!
        assertEquals("Hello", defaut.chaine("bonjour"))
        assertEquals("Salut", francais.chaine("bonjour"))
    }

    @Test
    fun `dimensions - arrivees en DP (convertisseur 160 dpi)`() {
        val resolveur = resolveurs().pour(dossierRes, nuit = false, langue = null, themeName = null, densiteDpi = 440)!!
        assertEquals(16f, resolveur.dimensionDp("carton"))
    }

    @Test
    fun `theme - les slots Material resolus par leurs attributs XML`() {
        val resolveur = resolveurs().pour(
            dossierRes,
            nuit = false,
            langue = null,
            themeName = "Theme.MonApp",
            densiteDpi = 440,
        )!!
        assertEquals(0xFF43559E.toInt(), resolveur.couleurDuSlot("primary"))
        assertEquals(0xFFFFFFFF.toInt(), resolveur.couleurDuSlot("onPrimary"))
        assertNull("slot hors table : null honnête", resolveur.couleurDuSlot("surfaceBright"))
    }

    @Test
    fun `schema - les slots resolus remplacent, les absents restent aux defauts`() {
        val resolveurs = resolveurs()
        val avecTheme = resolveurs.pour(
            dossierRes,
            nuit = false,
            langue = null,
            themeName = "Theme.MonApp",
            densiteDpi = 440,
        )!!
        val schemaJour = avecTheme.schema(nuit = false)
        assertEquals(Color(0xFF43559E.toInt()), schemaJour.primary)
        assertEquals(Color(0xFFFFFFFF.toInt()), schemaJour.onPrimary)
        // colorTertiary n'est PAS dans la fixture : le défaut du RUNTIME
        // reste (comportement C1 pour les slots absents).
        assertEquals(
            "slot absent : défaut du runtime",
            androidx.compose.material3.lightColorScheme().tertiary,
            schemaJour.tertiary,
        )

        // NUIT : le schéma part de la base SOMBRE et le primary suit la
        // couleur nuit de la fixture (values-night).
        val avecThemeNuit = resolveurs.pour(
            dossierRes,
            nuit = true,
            langue = null,
            themeName = "Theme.MonApp",
            densiteDpi = 440,
        )!!
        val schemaNuit = avecThemeNuit.schema(nuit = true)
        assertEquals(Color(0xFF101020.toInt()), schemaNuit.primary)
        assertNotEquals(
            "base sombre ≠ base claire",
            androidx.compose.material3.lightColorScheme().background,
            schemaNuit.background,
        )
    }

    @Test
    fun `cache - meme combinaison, MEME resolveur (un scan par changement)`() {
        val resolveurs = resolveurs()
        val premier = resolveurs.pour(
            dossierRes,
            nuit = false,
            langue = null,
            themeName = "Theme.MonApp",
            densiteDpi = 440,
        )
        val second = resolveurs.pour(
            dossierRes,
            nuit = false,
            langue = null,
            themeName = "Theme.MonApp",
            densiteDpi = 440,
        )
        assertSame("la combinaison vit en cache", premier, second)
        val autre = resolveurs.pour(
            dossierRes,
            nuit = true,
            langue = null,
            themeName = "Theme.MonApp",
            densiteDpi = 440,
        )
        assertNotEquals("la nuit est une AUTRE combinaison (re-scan)", premier, autre)
    }

    @Test
    fun `slots - la table couvre les vingt-et-un slots principaux M3`() {
        assertEquals(21, SlotsThemeCompose.ATTR_PAR_SLOT.size)
        assertTrue("primary → colorPrimary", SlotsThemeCompose.ATTR_PAR_SLOT["primary"] == "colorPrimary")
        assertTrue(
            "background → android:colorBackground",
            SlotsThemeCompose.ATTR_PAR_SLOT["background"] == "android:colorBackground",
        )
        SlotsThemeCompose.ATTR_PAR_SLOT.keys.forEach { slot ->
            assertTrue("chaque slot a son POSEUR : $slot", SlotsThemeCompose.poseurDe(slot) != null)
        }
    }
}
