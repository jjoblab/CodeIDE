package jo.codeide.feature.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import jo.codeide.preview.xml.ArbreAttributsXml
import jo.codeide.preview.xml.CalquesApercu
import jo.codeide.preview.xml.EntreeClasspath
import jo.codeide.preview.xml.GenreEntreeClasspath
import jo.codeide.preview.xml.GenrePreview
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.PlacementPopup
import jo.codeide.preview.xml.PlanInsertionFamille
import jo.codeide.preview.xml.ProfilPeripherique
import jo.codeide.preview.xml.RenduLayoutLib
import jo.codeide.preview.xml.RequeteRendu
import jo.codeide.preview.xml.VuePreviewXml
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode
import java.io.File

/**
 * CAPTURES de recette PE12h (spec PREVIEW_PARENTS § 15.2 : « Paire de
 * captures maquette | application, clair et nuit, pour les trois états
 * de popup et le calque Relations sur activity_profil.xml et
 * activity_fil.xml »). Les captures MAQUETTE vivent dans
 * `docs/mockups/ref/parents/` (popup_relatif_cote.png,
 * popup_cadre_devant.png, popup_coordinateur_devant.png, profil.png,
 * fil.png, carte.png) ; CE TEST produit leurs paires APPLICATION : les
 * PNG sont écrits dans `build/captures-pe12h/` du module (hors dépôt —
 * artefacts de séance, miroir du protocole de mesure) puis archivés
 * avec les paires pour la séance de recette du propriétaire.
 *
 * Chaque capture est PRODUITE par le VRAI moteur : les documents sont
 * rendus par le pipeline LayoutLib (Robolectric NATIVE), le popup est
 * le VRAI [PopupPlacement] sur les vues du rendu, le calque Relations
 * est le VRAI dessin de [VuePreviewXml] ([CalquesApercu.lignesRelations]
 * lues sur le texte). Les défauts du moteur constatés à la mesure
 * (voir [MesureRenduParentsTest] : F1/F2/F3/F4, annexe ADR 0135 — C1 est
 * CORRIGÉ en v0.166.5 : l'action bar du chrome se retire enfin pour la
 * planche noBar du fil) restent VISIBLES sur les captures — c'est le
 * RÔLE d'une paire de recette : l'œil du propriétaire juge sur l'image,
 * pas sur l'intention.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(Mode.NATIVE)
public class CapturesRecetteParentsTest {
    /** Le dossier `res/` du projet de mesure (copie des ressources de test). */
    @get:Rule
    public val dossier: TemporaryFolder = TemporaryFolder()

    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Le contexte du rendu : français, 440 dpi, thème Material de l'aperçu. */
    private val contexte: Context by lazy { contexteDeRendu() }

    // ── Les captures, clair puis nuit ─────────────────────────────────

    @Test
    public fun `captures clair - popups, calque relations et rendus`() {
        capturerPopup("app_popup_relatif_cote.png", "activity_profil.xml", THEME_AVEC_BARRE, reference = BIO, DESSOUS)
        capturerPopup("app_popup_cadre_devant.png", "activity_carte.xml", THEME_AVEC_BARRE, reference = FOND, APRES)
        capturerPopup(
            "app_popup_coordinateur_devant.png",
            "activity_fil.xml",
            THEME_SANS_BARRE,
            reference = APPBAR,
            APRES,
        )
        capturerCalque("app_profil_calque.png", "activity_profil.xml", THEME_AVEC_BARRE)
        capturerCalque("app_fil_calque.png", "activity_fil.xml", THEME_SANS_BARRE)
        capturerRendu("app_carte.png", "activity_carte.xml", THEME_AVEC_BARRE)
    }

    @Test
    @Config(sdk = [33], qualifiers = "night")
    public fun `captures nuit - popups, calque relations et rendus`() {
        capturerPopup(
            "app_popup_relatif_cote_nuit.png",
            "activity_profil.xml",
            THEME_AVEC_BARRE,
            reference = BIO,
            DESSOUS,
        )
        capturerPopup(
            "app_popup_cadre_devant_nuit.png",
            "activity_carte.xml",
            THEME_AVEC_BARRE,
            reference = FOND,
            APRES,
        )
        capturerPopup(
            "app_popup_coordinateur_devant_nuit.png",
            "activity_fil.xml",
            THEME_SANS_BARRE,
            reference = APPBAR,
            APRES,
        )
        capturerCalque("app_profil_calque_nuit.png", "activity_profil.xml", THEME_AVEC_BARRE)
        capturerCalque("app_fil_calque_nuit.png", "activity_fil.xml", THEME_SANS_BARRE)
        capturerRendu("app_carte_nuit.png", "activity_carte.xml", THEME_AVEC_BARRE)
    }

    // ── La machine de capture ─────────────────────────────────────────

    /** Rend la fixture et retourne le résultat du pipeline (bitmap + vues). */
    private fun rendre(
        fichier: String,
        theme: String,
    ): RenduLayoutLib.ResultatPipeline {
        val texte = lireFixture(fichier)
        val requete =
            RequeteRendu(
                texte = texte,
                dossierRes = dossierRes.absolutePath,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = nuitCourant,
                langue = "fr",
                themeName = theme,
                // Voir MesureRenduParentsTest : sans entrée, les balises
                // qualifiées du fil dégradent en MockView.
                classpath = listOf(entreeClasspath()),
            )
        return RenduLayoutLib(contexte, journal = null).rendre(requete)
    }

    /** Le régime courant (posé par la méthode de test, jour par défaut). */
    private val nuitCourant: Boolean
        get() =
            contexte.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * Capture du POPUP « Où placer ? » sur les vues du VRAI rendu :
     * l'état de chaque famille (miroir des captures maquette
     * popup_relatif_cote / popup_cadre_devant / popup_coordinateur_devant).
     */
    private fun capturerPopup(
        nomFichier: String,
        fixture: String,
        theme: String,
        reference: Int,
        position: PlacementPopup.Position,
    ) {
        val rendu = rendre(fixture, theme)
        assertTrue("rendu de $fixture a échoué : ${rendu.rendu.message}", rendu.rendu.ok)
        val popup = PopupPlacement(contexte)
        val hote = FrameLayout(contexte)
        hote.setBackgroundColor(ContextCompat.getColor(contexte, R.color.apercu_fond))
        hote.addView(popup)
        popup.definirTitre("Bouton")
        popup.afficher(
            rendu.rendu.vues.toList(),
            reference,
            PopupPlacement.ChoixPlacement(position),
            PlacementPopup.cible(
                rendu.rendu.vues.toList(),
                reference,
                position,
                depose = PlanInsertionFamille.Depose(densiteDpi = DENSITE_DPI),
            ),
        )
        // Les bornes posées par le code partent au looper (post).
        shadowOf(Looper.getMainLooper()).idle()
        capturerVue(hote, nomFichier, LARGEUR_POPUP_DP, HAUTEUR_POPUP_DP)
    }

    /**
     * Capture du RENDU avec le CALQUE « Relations » actif : règles
     * `RelativeLayout` (lues sur le TEXTE — elles restent justes) et
     * ancres `CoordinatorLayout`, dessinées par le vrai [VuePreviewXml].
     */
    private fun capturerCalque(
        nomFichier: String,
        fixture: String,
        theme: String,
    ) {
        val texte = lireFixture(fixture)
        val rendu = rendre(fixture, theme)
        assertTrue("rendu de $fixture a échoué : ${rendu.rendu.message}", rendu.rendu.ok)
        val vues = rendu.rendu.vues.toList()
        val bitmap = rendu.bitmap
        val vue =
            VuePreviewXml(contexte).apply {
                appliquerJetons(StyleVolet.jetonsCalques(contexte))
                libelleProfil = "Pixel 4"
                afficherRendu(bitmap!!, vues, DENSITE_DPI)
                lignesRelations = CalquesApercu.lignesRelations(vues, ArbreAttributsXml.lire(texte))
                calquesContraintes = true
            }
        capturerVue(vue, nomFichier, bitmap!!.width / DENSITE, bitmap.height / DENSITE)
    }

    /** Capture du rendu BRUT (la paire des planches maquette profil/fil/carte). */
    private fun capturerRendu(
        nomFichier: String,
        fixture: String,
        theme: String,
    ) {
        val rendu = rendre(fixture, theme)
        assertTrue("rendu de $fixture a échoué : ${rendu.rendu.message}", rendu.rendu.ok)
        val bitmap = rendu.bitmap
        val vue =
            VuePreviewXml(contexte).apply {
                appliquerJetons(StyleVolet.jetonsCalques(contexte))
                libelleProfil = "Pixel 4"
                afficherRendu(bitmap!!, rendu.rendu.vues.toList(), DENSITE_DPI)
            }
        capturerVue(vue, nomFichier, bitmap!!.width / DENSITE, bitmap.height / DENSITE)
    }

    /** Mesure, dispose et dessine une vue en PNG (les dp sont convertis par la densité du contexte). */
    private fun capturerVue(
        vue: View,
        nomFichier: String,
        largeurDp: Int,
        hauteurDp: Int,
    ) {
        val densite = contexte.resources.displayMetrics.density
        val largeurPx = (largeurDp * densite).toInt().coerceAtLeast(1)
        val hauteurPx = (hauteurDp * densite).toInt().coerceAtLeast(1)
        vue.measure(
            View.MeasureSpec.makeMeasureSpec(largeurPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hauteurPx, View.MeasureSpec.EXACTLY),
        )
        vue.layout(0, 0, vue.measuredWidth, vue.measuredHeight)
        val bitmap = Bitmap.createBitmap(vue.measuredWidth, vue.measuredHeight, Bitmap.Config.ARGB_8888)
        vue.draw(Canvas(bitmap))
        val sortie = File(dossierCaptures, nomFichier)
        sortie.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(
            "la capture ${sortie.name} est vide (${sortie.length()} octets)",
            sortie.length() > 0L,
        )
    }

    // ── Ressources et contexte (patron MesureRenduParentsTest) ────────

    /** Le texte d'une fixture (copie de test des documents d'exemple). */
    private fun lireFixture(fichier: String): String {
        val flux = javaClass.classLoader?.getResourceAsStream("parents/$fichier")
        if (flux != null) return flux.readBytes().decodeToString()
        return File(racineDepot, "feature/preview/src/test/resources/parents/$fichier").readText()
    }

    /** Le dossier `res/` du projet de mesure : copie (unique) des ressources de test. */
    private val dossierRes: File by lazy {
        val cible = dossier.newFolder("res")
        val source =
            javaClass.classLoader?.getResource("parents/res")?.let { racine ->
                runCatching { File(racine.toURI()) }.getOrNull()
            } ?: File(racineDepot, "feature/preview/src/test/resources/parents/res")
        source.copyRecursively(cible, overwrite = true)
        cible
    }

    /** L'entrée de classpath qui débloque la factory des balises qualifiées. */
    private fun entreeClasspath(): EntreeClasspath {
        val jar =
            System
                .getProperty("java.class.path")
                .orEmpty()
                .split(File.pathSeparator)
                .firstOrNull { it.endsWith(".jar") && File(it).isFile }
                ?: error("aucun jar sur le classpath de test")
        return EntreeClasspath(jar, GenreEntreeClasspath.JAR)
    }

    /** Le contexte du rendu : français, 440 dpi, thème Material de l'aperçu. */
    private fun contexteDeRendu(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = android.content.res.Configuration(base.resources.configuration)
        configuration.setLocale(java.util.Locale.FRENCH)
        configuration.densityDpi = DENSITE_DPI
        return ContextThemeWrapper(
            base.createConfigurationContext(configuration),
            R.style.Theme_CodeIDE_Apercu,
        )
    }

    /** La racine du dépôt (le module de test peut tourner depuis un sous-dossier). */
    private fun trouverRacineDepot(): File {
        var repertoire: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(PROFONDEUR_RECHERCHE) {
            val courant = repertoire ?: return@repeat
            if (File(courant, CHEMIN_TEMOIN).isFile) return courant
            repertoire = courant.parentFile
        }
        error("$CHEMIN_TEMOIN introuvable au-dessus de ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** Témoin de la racine du dépôt. */
        private const val CHEMIN_TEMOIN: String = "docs/mockups/ref/parents/meas_rendu_parents.json"

        /** Densité DPI du profil Pixel 4. */
        private const val DENSITE_DPI: Int = 440

        /** Densité px/dp du profil. */
        private const val DENSITE: Int = 440 / 160

        private const val PROFONDEUR_RECHERCHE: Int = 12

        /** Thème des documents À barre d'action (profil, carte). */
        private const val THEME_AVEC_BARRE: String = "Theme.Mesure"

        /** Thème du fil (planche noBar — l'action bar reste posée, constat C1). */
        private const val THEME_SANS_BARRE: String = "Theme.Mesure.SansBarre"

        // Indices PRÉ-ORDRE des références du popup dans les rendus :
        // profil → bio (6), carte → fond (1), fil → appbar (1).
        private const val BIO: Int = 6
        private const val FOND: Int = 1
        private const val APPBAR: Int = 1

        /** L'hôte du popup (miroir de CotesPopupsParentsTest : 418 × 812 dp). */
        private const val LARGEUR_POPUP_DP: Int = 418
        private const val HAUTEUR_POPUP_DP: Int = 812

        /** La position « à côté d'un frère » du RelativeLayout. */
        private val DESSOUS: PlacementPopup.Position = PlacementPopup.Position.DESSOUS

        /** La position « devant » (après) des familles empilées. */
        private val APRES: PlacementPopup.Position = PlacementPopup.Position.APRES

        /** Le dossier de sortie des captures (build du module, hors dépôt). */
        private val dossierCaptures: File =
            File(System.getProperty("user.dir") ?: ".", "build/captures-pe12h").apply {
                mkdirs()
            }
    }
}
