package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import jo.codeide.core.domain.AppLogger
import jo.layoutlib.drawables.DrawableResolverImpl
import jo.layoutlib.inflater.AttributeApplier
import jo.layoutlib.inflater.BridgeInflater
import jo.layoutlib.inflater.ViewInfoCollector
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
 * Pipeline de rendu LayoutLib (PE1 → PE2 → PE3, PREVIEW.md §3.2/§3.4) —
 * le « RenderService adapté » : validation XmlPullParser → dispatch PAR
 * GENRE → inflation par vraies vues (LAYOUT) / dessin drawable (DRAWABLE) /
 * tableau d'entrées (VALEURS) → **dessin bitmap off-screen** (P4).
 *
 * PE3 ajoute au pipeline PE1 :
 *  - **profil device** ([RequeteRendu.profil]) : la mesure, le layout et le
 *    DimensionConverter vivent à la densité et aux dimensions du PROFIL,
 *    pas de la machine qui exécute l'IDE ;
 *  - **jour/nuit** ([RequeteRendu.nuit]) : `values-night` et thèmes nuit
 *    priorisés (sémantique `ThemeResolverImpl`/`ResourceResolverImpl`) ;
 *  - **sélection P6** : l'arbre rendu est collecté (ViewInfoCollector —
 *    bounds ABSOLUS) et aligné aux éléments du texte ORIGINAL
 *    ([AligneurElementsXml]) : chaque vue porte le SPAN de son élément
 *    XML — le hit-test de l'UI sélectionne dans l'éditeur.
 *
 * Ce pipeline vit dans les DEUX mondes sans duplication :
 *  - le SERVICE du processus `:preview` l'exécute pour le rendu isolé
 *    (P3) puis compresse le bitmap en PNG (frontière AIDL) ;
 *  - le transport LOCAL de repli l'exécute en-process sur fil dédié.
 *
 * Comportements repris de LayoutLib/Android Studio : XML malformé →
 * [ResultatRendu.ok] = false avec message `XML_MALFORME` (le moteur
 * l'interprète « frappe en cours », signal discret) ; erreur
 * sémantique → message lisible ; ressources `res/` (P5) rebranchées
 * quand le dossier change.
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

@Suppress("TooManyFunctions") // Pipeline nommé de PE1, étendu en PE3 (profils, nuit, genres, sélection).
public class RenduLayoutLib(
    private val contexte: Context,
    private val journal: AppLogger?,
) {
    /** Inflater LayoutLib — construit une fois, réinitialisé par rendu. */
    private val inflater: BridgeInflater = BridgeInflater(contexte, ViewTagRegistry(false))

    /** Moteur de mesure/layout LayoutLib. */
    private val layoutEngine = LayoutEngineImpl()

    /** Rendu des fichiers de valeurs (PE3). */
    private val renduValeurs = RenduValeursXml(densiteCourante())

    private var dossierResConfigure: String? = null

    /** Résolveur de ressources du projet (null = repli natif) — P5. */
    private var ressources: ResourceResolverImpl? = null

    /** Résolveur de thèmes du projet (null = repli natif) — PE3 jour/nuit. */
    private var themeResolver: ThemeResolverImpl? = null

    /** Résolveur de drawables du projet (null = repli natif). */
    private var drawableResolver: DrawableResolverImpl? = null

    /** Convertisseur dp→px courant (densité du PROFIL — PE3). */
    private var convertisseurCourant: DimensionConverter? = null

    /** Régime nuit courant (les résolveurs ne le stockent pas de façon queryable). */
    private var nuitConfigure = false

    /** Langue courante (PE5a — null = défaut système). */
    private var langueConfigure: String? = null

    /** Densité du dernier rendu (reconfiguration des résolveurs au changement). */
    private var densiteConfigure = -1f

    init {
        layoutEngine.setFontScale(echellePolice())
    }

    /**
     * Pipeline complet (validation incluse — le moteur a déjà validé,
     * le service revalide : défense en profondeur, le coût est nul),
     * dispatché PAR GENRE (PE3) : LAYOUT inflaté (+ sélection P6),
     * DRAWABLE dessiné, VALEURS en tableau.
     *
     * @param requete la demande complète (texte, res/, genre, profil, nuit).
     * @param compresser vrai (service :preview) → PNG dans [ResultatRendu.png]
     *        et bitmap NON retenu ; faux (transport local) → bitmap
     *        retenu dans [bitmap].
     */
    @Suppress("LongMethod") // Le pipeline EST l'ordre des étapes de PE1 à PE3.
    public fun rendre(
        requete: RequeteRendu,
        compresser: Boolean,
    ): ResultatPipeline {
        val debut = System.nanoTime()
        return try {
            if (!xmlBienForme(requete.texte)) {
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
            when (requete.genre) {
                GenrePreview.LAYOUT -> rendreLayout(requete, compresser, debut)
                GenrePreview.DRAWABLE -> rendreDrawable(requete, compresser, debut)
                GenrePreview.VALEURS -> rendreValeurs(requete, compresser, debut)
            }
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

    /** LAYOUT : inflation → mesure/layout au profil → dessin + sélection P6. */
    private fun rendreLayout(
        requete: RequeteRendu,
        compresser: Boolean,
        debut: Long,
    ): ResultatPipeline {
        val densite = densiteProfil(requete.profil)
        reconfigurerSiNecessaire(requete.dossierRes, densite, requete.nuit, requete.langue)
        inflater.reset()
        val racine = inflater.inflate(requete.texte)
        if (racine.layoutParams == null) {
            racine.setLayoutParams(
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val largeur = requete.profil.versDeviceProfile().getWidthPx(densite)
        val hauteur = requete.profil.versDeviceProfile().getHeightPx(densite)
        layoutEngine.setDensity(densite)
        layoutEngine.render(racine, largeur, hauteur)
        val bitmap = dessiner(racine)
        val vues = collecterVues(requete, racine)
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
                    vues = vues,
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
                    vues = vues,
                )
            }
        return ResultatPipeline(rendu, if (compresser) null else bitmap)
    }

    /** DRAWABLE (PE3) : parsing LayoutLib → dessin à la taille intrinsèque. */
    private fun rendreDrawable(
        requete: RequeteRendu,
        compresser: Boolean,
        debut: Long,
    ): ResultatPipeline {
        val densite = densiteProfil(requete.profil)
        reconfigurerSiNecessaire(requete.dossierRes, densite, requete.nuit, requete.langue)
        val resolver = drawableResolver
        val drawable =
            if (resolver != null) {
                resolver.parse(requete.texte, contexte)
            } else {
                DrawableResolverImpl().parse(requete.texte, contexte)
            }
        val largeur =
            drawable.intrinsicWidth.takeIf { it > 0 }
                ?: (TAILLE_DRAWABLE_DEFAUT_DP * densite).toInt()
        val hauteur =
            drawable.intrinsicHeight.takeIf { it > 0 }
                ?: (TAILLE_DRAWABLE_DEFAUT_DP * densite).toInt()
        drawable.setBounds(0, 0, largeur, hauteur)
        val bitmap = dessinerDrawable(drawable, largeur, hauteur)
        val rendu =
            ResultatRendu(
                ok = true,
                message = null,
                largeur = largeur,
                hauteur = hauteur,
                nombreVues = 1,
                dureeMs = (System.nanoTime() - debut) / 1_000_000,
                png = if (compresser) compresserPng(bitmap) else ByteArray(0),
                vues = emptyArray(),
            )
        return ResultatPipeline(rendu, if (compresser) null else bitmap)
    }

    /** VALEURS (PE3) : extraction des entrées → tableau bitmap. */
    private fun rendreValeurs(
        requete: RequeteRendu,
        compresser: Boolean,
        debut: Long,
    ): ResultatPipeline {
        val densite = densiteProfil(requete.profil)
        val entrees =
            renduValeurs.extraire(requete.texte)
                ?: return ResultatPipeline(
                    rendu =
                        ResultatRendu(
                            ok = false,
                            message = "racine <resources> attendue pour un fichier de valeurs",
                            largeur = 0,
                            hauteur = 0,
                            nombreVues = 0,
                            dureeMs = 0,
                            png = ByteArray(0),
                        ),
                    bitmap = null,
                )
        val largeur = requete.profil.versDeviceProfile().getWidthPx(densite)
        val bitmap = renduValeurs.dessiner(entrees, largeur, requete.nuit)
        val rendu =
            ResultatRendu(
                ok = true,
                message = null,
                largeur = bitmap.width,
                hauteur = bitmap.height,
                nombreVues = entrees.size,
                dureeMs = (System.nanoTime() - debut) / 1_000_000,
                png = if (compresser) compresserPng(bitmap) else ByteArray(0),
                vues = emptyArray(),
            )
        return ResultatPipeline(rendu, if (compresser) null else bitmap)
    }

    /**
     * Collecte l'arbre rendu (ViewInfoCollector — bounds ABSOLUS) et
     * l'aligne aux éléments du texte original ([AligneurElementsXml]) :
     * chaque vue aplatie porte le span de SON élément (P6). Un écart de
     * séquence (défense : miroir imparfait de l'inflater) désarme TOUTE
     * la sélection — jamais de faux span (X4).
     */
    private fun collecterVues(
        requete: RequeteRendu,
        racine: View,
    ): Array<InfoVueRendu> {
        val infos = aplatir(ViewInfoCollector.collect(racine))
        val resolveur =
            ressources?.let { r ->
                AligneurElementsXml.ResolveurLayout { reference -> r.getLayout(reference) }
            }
        val elements = AligneurElementsXml.aligner(requete.texte, resolveur)
        if (elements.size != infos.size) {
            journal?.w(TAG) {
                "alignement sélection désarmé : ${infos.size} vues vs ${elements.size} éléments"
            }
            return Array(infos.size) { indice ->
                infos[indice].versInfoVue(InfoVueRendu.SANS_ELEMENT, InfoVueRendu.SANS_ELEMENT)
            }
        }
        return Array(infos.size) { indice ->
            val element = elements[indice]
            infos[indice].versInfoVue(element.debut, element.fin)
        }
    }

    /** Aplatit l'arbre ViewInfo en pré-ordre (parent avant enfants), avec indice de parent. */
    private fun aplatir(racine: ViewInfoCollector.ViewInfo): List<ViewInfoAplatie> {
        val resultat = mutableListOf<ViewInfoAplatie>()

        fun parcourir(
            info: ViewInfoCollector.ViewInfo,
            indiceParent: Int,
            profondeur: Int,
        ) {
            val indice = resultat.size
            resultat.add(ViewInfoAplatie(info, indiceParent, profondeur))
            for (enfant in info.children) {
                parcourir(enfant, indice, profondeur + 1)
            }
        }
        parcourir(racine, -1, 0)
        return resultat
    }

    /** Une vue aplatie (info LayoutLib + indice du parent dans le tableau plat). */
    private data class ViewInfoAplatie(
        val info: ViewInfoCollector.ViewInfo,
        val indiceParent: Int,
        val profondeur: Int,
    ) {
        fun versInfoVue(
            debutElement: Int,
            finElement: Int,
        ): InfoVueRendu =
            InfoVueRendu(
                gauche = info.left,
                haut = info.top,
                droite = info.right,
                bas = info.bottom,
                libelle = info.simpleName + (info.idName?.let { nom -> " @$nom" } ?: ""),
                debutElement = debutElement,
                finElement = finElement,
                indiceParent = indiceParent,
                profondeur = profondeur,
            )
    }

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

    /** Dessine un drawable (PE3) sur damier implicite — la vue UI le pose dessous. */
    private fun dessinerDrawable(
        drawable: Drawable,
        largeur: Int,
        hauteur: Int,
    ): Bitmap {
        val bitmap =
            Bitmap.createBitmap(largeur.coerceAtLeast(1), hauteur.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * (Re)branche les résolveurs LayoutLib sur le dossier `res/` courant :
     * ressources (values*), drawables, thèmes (values + values-night).
     * PE3 : la densité du CONVERTISSEUR est celle du PROFIL, le régime
     * NUIT s'applique aux résolveurs (thèmes + ressources qualifiées).
     * Échec (dossier absent/projet sans res/) : journal + repli natif —
     * le rendu continue, l'éditeur n'est jamais bloqué.
     */
    private fun reconfigurerSiNecessaire(
        chemin: String?,
        densiteProfil: Float,
        nuit: Boolean,
        langue: String?,
    ) {
        val dossier = chemin
        if (configurationInchangee(dossier, densiteProfil, nuit, langue)) {
            return
        }
        if (dossier != null && File(dossier).isDirectory) {
            try {
                val echellePolice = echellePolice()
                val xdpi = contexte.resources.displayMetrics.xdpi
                val convertisseur = DimensionConverter(densiteProfil, echellePolice, xdpi)

                val nouvellesRessources = ResourceResolverImpl(dossier)
                nouvellesRessources.setDimensionConverter(convertisseur)
                nouvellesRessources.setNightMode(nuit)
                // PE5a : langue (LayoutLib v3.0.0 P1 résolu).
                if (langue != null) {
                    nouvellesRessources.setLanguage(langue)
                }
                inflater.setResourceResolver(nouvellesRessources)

                val applier = AttributeApplier(contexte, convertisseur, nouvellesRessources)
                val drawableResolverNeuf = DrawableResolverImpl(nouvellesRessources, convertisseur)
                applier.setDrawableResolver(drawableResolverNeuf)
                val themeResolverNeuf = ThemeResolverImpl(nouvellesRessources, convertisseur)
                enregistrerThemes(themeResolverNeuf, dossier)
                themeResolverNeuf.setNightMode(nuit)
                applier.setThemeResolver(themeResolverNeuf)
                inflater.setAttributeApplier(applier)

                ressources = nouvellesRessources
                themeResolver = themeResolverNeuf
                drawableResolver = drawableResolverNeuf
                convertisseurCourant = convertisseur
            } catch (t: Throwable) {
                // ResourceException (res/ absent), IO… : repli natif documenté.
                journal?.w(TAG) { "résolveurs preview non branchés ($dossier) : ${t.message}" }
                repliNatif()
            }
        } else {
            repliNatif()
        }
        dossierResConfigure = dossier
        densiteConfigure = densiteProfil
        nuitConfigure = nuit
        langueConfigure = langue
    }

    /** Passe en repli natif (aucun résolveur projet). */
    private fun repliNatif() {
        ressources = null
        themeResolver = null
        drawableResolver = null
        convertisseurCourant = null
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

    /** La configuration courante des résolveurs couvre-t-elle déjà la demande ? */
    private fun configurationInchangee(
        dossier: String?,
        densiteProfil: Float,
        nuit: Boolean,
        langue: String?,
    ): Boolean =
        dossier == dossierResConfigure &&
            densiteProfil == densiteConfigure &&
            nuit == nuitConfigure &&
            langue == langueConfigure &&
            ressources != null

    /** Densité du PROFIL (dpi/DPI_PAR_UNITE) — PE3 : le rendu vit au profil, pas à la machine. */
    private fun densiteProfil(profil: ProfilPeripherique): Float = profil.densiteDpi / DPI_PAR_UNITE

    /** Densité de l'écran courant (dp → px pour les tailles par défaut). */
    private fun densiteCourante(): Float = contexte.resources.displayMetrics.density

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

        /** Taille d'un drawable sans dimension intrinsèque (dp). */
        private const val TAILLE_DRAWABLE_DEFAUT_DP: Int = 160

        /** Dpi par unité de densité (1× = 160 dpi — convention Android). */
        private const val DPI_PAR_UNITE: Float = 160f
    }
}
