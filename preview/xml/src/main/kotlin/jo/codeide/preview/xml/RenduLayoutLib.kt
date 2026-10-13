package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import jo.codeide.core.domain.AppLogger
import jo.layoutlib.drawables.DrawableResolverImpl
import jo.layoutlib.inflater.AttributeApplier
import jo.layoutlib.inflater.BridgeInflater
import jo.layoutlib.inflater.DeviceProfile
import jo.layoutlib.inflater.ViewTagRegistry
import jo.layoutlib.layout.LayoutEngineImpl
import jo.layoutlib.resources.DimensionConverter
import jo.layoutlib.resources.ResourceResolverImpl
import jo.layoutlib.themes.ThemeResolverImpl
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.StringReader

/**
 * Pipeline de rendu LayoutLib (PE1 → PE2, PREVIEW.md §3.2) — le «
 * RenderService adapté » EXTRAIT du moteur : validation XmlPullParser →
 * inflation par vraies vues (réflexion, ViewTagRegistry) →
 * mesure/layout (LayoutEngineImpl) → **dessin bitmap off-screen** (P4).
 *
 * Ce pipeline vit DÉSORMAIS dans les DEUX mondes sans duplication :
 *  - le SERVICE du processus `:preview` l'exécute pour le rendu isolé
 *    (P3) puis compresse le bitmap en PNG (frontière AIDL) ;
 *  - le transport LOCAL de repli l'exécute en-process sur fil dédié.
 *
 * Comportements repris de LayoutLib/Android Studio : XML malformé →
 * [ResultatRendu.ok] = false avec message `XML_MALFORME` (le moteur
 * l'interprète « frappe en cours », signal discret) ; erreur
 * sémantique → message lisible ; ressources `res/` (P5) rebranchées
 * quand le dossier change (thèmes jour/nuit/styles au mieux, repli
 * natif documenté).
 *
 * NON thread-safe (l'inflater ne l'est pas) : chaque appelant sérialise
 * ses rendus (mutex côté moteur, verrou côté service).
 *
 * Sentinelle « XML malformé » (frappe en cours — signal discret, PAS une erreur). */
public const val XML_MALFORME: String = "XML_MALFORME"

/**
 * Validation XML complète (même stratégie que RenderService :
 * XmlPullParser — traversée intégrale, toute malformation lève).
 * Partagée moteur (feedback AVANT IPC) et service (défense en profondeur).
 */
public fun xmlBienForme(texte: String): Boolean =
    try {
        val fabrique = XmlPullParserFactory.newInstance()
        val parseur = fabrique.newPullParser()
        parseur.setInput(StringReader(texte))
        while (parseur.next() != XmlPullParser.END_DOCUMENT) {
            // La traversée complète suffit : toute malformation lève.
        }
        true
    } catch (_: XmlPullParserException) {
        false
    } catch (_: IOException) {
        false
    }

@Suppress("TooManyFunctions") // Pipeline nommé de PE1, inchangé dans son périmètre.
public class RenduLayoutLib(
    private val contexte: Context,
    private val journal: AppLogger?,
) {
    /** Inflater LayoutLib — construit une fois, réinitialisé par rendu. */
    private val inflater: BridgeInflater = BridgeInflater(contexte, ViewTagRegistry(false))

    /** Moteur de mesure/layout LayoutLib. */
    private val layoutEngine = LayoutEngineImpl()

    /** Profil device par défaut (PE3 ajoutera le sélecteur). */
    private val profil = DeviceProfile.PIXEL_4

    private var dossierResConfigure: String? = null

    init {
        val densite = contexte.resources.displayMetrics.density
        layoutEngine.setDensity(densite)
        layoutEngine.setFontScale(echellePolice())
    }

    /**
     * Pipeline complet (validation incluse — le moteur a déjà validé,
     * le service revalide : défense en profondeur, le coût est nul).
     *
     * @param texte layout XML (aucune hypothèse de validité).
     * @param dossierRes dossier `res/` du projet (null = repli natif).
     * @param compresser vrai (service :preview) → PNG dans [ResultatRendu.png]
     *        et bitmap NON retenu ; faux (transport local) → bitmap
     *        retenu dans [bitmap].
     */
    @Suppress("LongMethod") // Le pipeline EST l'ordre des étapes de PE1.
    public fun rendre(
        texte: String,
        dossierRes: String?,
        compresser: Boolean,
    ): ResultatPipeline {
        val debut = System.nanoTime()
        return try {
            if (!xmlBienForme(texte)) {
                return ResultatPipeline(
                    rendu =
                        ResultatRendu(
                            ok = false,
                            message = XML_MALFORME,
                            largeur = 0,
                            hauteur = 0,
                            nombreVues = 0,
                            dureeMs = 0,
                            png = ByteArray(0),
                        ),
                    bitmap = null,
                )
            }
            reconfigurerSiNecessaire(dossierRes)
            inflater.reset()
            val racine = inflater.inflate(texte)
            if (racine.layoutParams == null) {
                racine.setLayoutParams(
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            val densite = contexte.resources.displayMetrics.density
            val largeur = profil.getWidthPx(densite)
            val hauteur = profil.getHeightPx(densite)
            layoutEngine.render(racine, largeur, hauteur)
            val bitmap = dessiner(racine)
            val rendu =
                if (compresser) {
                    ResultatRendu(
                        ok = true,
                        message = null,
                        largeur = racine.measuredWidth,
                        hauteur = racine.measuredHeight,
                        nombreVues = compterVues(racine),
                        dureeMs = (System.nanoTime() - debut) / 1_000_000,
                        png = compresserPng(bitmap),
                    )
                } else {
                    ResultatRendu(
                        ok = true,
                        message = null,
                        largeur = racine.measuredWidth,
                        hauteur = racine.measuredHeight,
                        nombreVues = compterVues(racine),
                        dureeMs = (System.nanoTime() - debut) / 1_000_000,
                        png = ByteArray(0),
                    )
                }
            ResultatPipeline(rendu, if (compresser) null else bitmap)
        } catch (t: Throwable) {
            journal?.w(TAG) { "rendu preview échoué : ${t.message}" }
            ResultatPipeline(
                rendu =
                    ResultatRendu(
                        ok = false,
                        message = messageLisible(t),
                        largeur = 0,
                        hauteur = 0,
                        nombreVues = 0,
                        dureeMs = 0,
                        png = ByteArray(0),
                    ),
                bitmap = null,
            )
        }
    }

    /** Un rendu : le résultat AIDL + le bitmap (transport local seul). */
    public class ResultatPipeline(
        public val rendu: ResultatRendu,
        public val bitmap: Bitmap?,
    )

    /** Compresse le bitmap en PNG (frontière AIDL — ~1 Mo max). */
    private fun compresserPng(bitmap: Bitmap): ByteArray {
        val tampon = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, QUALITE_PNG, tampon)
        return tampon.toByteArray()
    }

    /** Dessine la racine mesurée dans un bitmap off-screen (P4). */
    private fun dessiner(racine: View): Bitmap {
        val largeur = racine.measuredWidth.coerceAtLeast(1)
        val hauteur = racine.measuredHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(largeur, hauteur, Bitmap.Config.ARGB_8888)
        racine.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * (Re)branche les résolveurs LayoutLib sur le dossier `res/` courant :
     * ressources (values*), drawables, thèmes (values + values-night).
     * Échec (dossier absent/projet sans res/) : journal + repli natif —
     * le rendu continue, l'éditeur n'est jamais bloqué.
     */
    private fun reconfigurerSiNecessaire(chemin: String?) {
        val dossier = chemin ?: return
        if (dossier == dossierResConfigure) return
        try {
            val densite = contexte.resources.displayMetrics.density
            val echellePolice = echellePolice()
            val xdpi = contexte.resources.displayMetrics.xdpi
            val convertisseur = DimensionConverter(densite, echellePolice, xdpi)

            val ressources = ResourceResolverImpl(dossier)
            ressources.setDimensionConverter(convertisseur)
            inflater.setResourceResolver(ressources)

            val applier = AttributeApplier(contexte, convertisseur, ressources)
            applier.setDrawableResolver(DrawableResolverImpl(ressources, convertisseur))
            val themeResolver = ThemeResolverImpl(ressources, convertisseur)
            enregistrerThemes(themeResolver, dossier)
            applier.setThemeResolver(themeResolver)
            inflater.setAttributeApplier(applier)
            dossierResConfigure = dossier
        } catch (t: Throwable) {
            // ResourceException (res/ absent), IO… : repli natif documenté.
            journal?.w(TAG) { "résolveurs preview non branchés ($dossier) : ${t.message}" }
            dossierResConfigure = null
        }
    }

    /** Enregistre themes.xml (jour/nuit) et styles.xml du projet, au mieux. */
    private fun enregistrerThemes(
        resolver: ThemeResolverImpl,
        cheminRes: String,
    ) {
        val themesJour = File(cheminRes, "values/themes.xml")
        if (themesJour.isFile) {
            themesJour.readText().let { xml -> resolver.registerThemesFile(xml, false) }
        }
        val themesNuit = File(cheminRes, "values-night/themes.xml")
        if (themesNuit.isFile) {
            themesNuit.readText().let { xml -> resolver.registerThemesFile(xml, true) }
        }
        val styles = File(cheminRes, "values/styles.xml")
        if (styles.isFile) {
            styles.readText().let { xml -> resolver.registerThemesFile(xml, false) }
        }
    }

    /** Compte les vues de l'arbre (récursif — ViewGroup compté avec ses enfants). */
    private fun compterVues(vue: View): Int {
        if (vue !is ViewGroup) return 1
        var total = 1
        for (index in 0 until vue.childCount) {
            val enfant = vue.getChildAt(index) ?: continue
            total += compterVues(enfant)
        }
        return total
    }

    /** Message d'erreur lisible (jamais la stacktrace brute au panneau). */
    private fun messageLisible(t: Throwable): String {
        val message = t.message?.trim().orEmpty()
        return if (message.isNotEmpty()) message else t.javaClass.simpleName
    }

    /**
     * Échelle de police équivalente à l'ancien `scaledDensity` (déprécié
     * API 34) : densité × fontScale de la configuration.
     */
    private fun echellePolice(): Float =
        contexte.resources.displayMetrics.density * contexte.resources.configuration.fontScale

    private companion object {
        private const val TAG = "RenduLayoutLib"

        /** Qualité PNG (sans perte — le paramètre ne touche que le filtre). */
        private const val QUALITE_PNG: Int = 100
    }
}
