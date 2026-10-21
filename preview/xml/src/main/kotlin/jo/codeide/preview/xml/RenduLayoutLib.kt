package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
 * LIGNE de la première malformation XML (null = bien formé) — PE11i,
 * bandeau « Ligne n » (ADR 0134 D6) : la même traversée intégrale que
 * la validation, mais l'exception du parseur porte son numéro de ligne.
 */
public fun ligneDeMalformation(texte: String): Int? =
    try {
        val fabrique = XmlPullParserFactory.newInstance()
        val parseur = fabrique.newPullParser()
        parseur.setInput(StringReader(texte))
        while (parseur.next() != XmlPullParser.END_DOCUMENT) {
            // La traversée complète suffit : toute malformation lève.
        }
        null
    } catch (e: XmlPullParserException) {
        e.lineNumber.takeIf { it > 0 }
    } catch (_: IOException) {
        null
    }

/**
 * Validation XML complète (même stratégie que RenderService :
 * XmlPullParser — traversée intégrale, toute malformation lève).
 * Partagée moteur (feedback AVANT IPC) et service (défense en profondeur).
 */
public fun xmlBienForme(texte: String): Boolean = ligneDeMalformation(texte) == null

/**
 * La ligne portée par un message d'exception LayoutLib (« Binary XML
 * file line #12 » — la position du TAG fautif), null sinon. PURE (PE11i,
 * bandeau « Ligne n ») : les erreurs SÉMANTIQUES (classe introuvable)
 * traversent l'inflater qui rapporte la ligne du tag dans son message.
 */
internal fun ligneDeMessageRendu(message: String): Int? =
    LIGNE_DANS_MESSAGE
        .find(message)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

private val LIGNE_DANS_MESSAGE = Regex("line #(\\d+)")

@Suppress("TooManyFunctions", "LargeClass") // Pipeline nommé de PE1, étendu en PE3 (profils, nuit,
// genres, sélection) puis PE6c/PE11g (collecte enrichie, détections problèmes) : l'éclater disperserait
// l'ordre des étapes — même exemption motivée qu'[EditionAttributsXml] (règle 16).
public class RenduLayoutLib(
    private val contexte: Context,
    private val journal: AppLogger?,
) {
    /** Inflater LayoutLib — construit une fois, réinitialisé par rendu. */
    private val inflater: BridgeInflater = BridgeInflater(contexte, ViewTagRegistry(false))

    /**
     * Factory2 pour vues custom du projet — G1 / Phase 2 (v0.132.0).
     *
     * Consultée par `BridgeInflater.onCreateViewFromFactory` pour chaque
     * tag. Les tags dotted (`com.example.FooView`) sont chargés via
     * `loader` (un classloader projet mis à jour par
     * `reconfigurerClasspathSiNecessaire`).
     *
     * Factory2 est ONE-SHOT (contract AOSP) — on l'installe UNE FOIS à la
     * construction. Le `loader` à l'intérieur est mutable (`@Volatile`).
     */
    private val dexViewFactory = DexViewFactory()

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

    /** Orientation paysage courante (PE6e-f). */
    private var paysageConfigure: Boolean = false

    /** Échelle de police courante — FACTEUR utilisateur (PE6e-f). */
    private var echellePoliceConfigure: Float = RequeteRendu.ECHELLE_POLICE_DEFAUT

    /** Nom du thème activité courant (v0.131.0 / G2 — null = repli système). */
    private var themeNameConfigure: String? = null

    /** Densité du dernier rendu (reconfiguration des résolveurs au changement). */
    private var densiteConfigure = -1f

    /** Cache de classloader projet (G1 / Phase 2 — v0.132.0). */
    private val cacheClasspath = CacheClasspath()

    /** Signature du classpath courant (null = jamais configuré). */
    private var classpathConfigure: String? = null

    init {
        layoutEngine.setFontScale(echellePolice())
        // v0.130.1 — TOUJOURS installer un AttributeApplier, même sans
        // dossier res/ (sinon BridgeInflater.applyBaseAttributes est
        // utilisé — repli qui ne gère QUE id/visibility/padding, donc
        // background et orientation sont perdus). Le DimensionConverter
        // natif (densité courante) suffit : les attributs #hex, dp, sp
        // passent sans résolveur projet.
        installerAttributeApplierParDefaut()
        // v0.132.0 — G1 : installer DexViewFactory (Factory2 hook).
        // ONE-SHOT selon le contract AOSP — appelé UNE FOIS à la
        // construction. Le `loader` à l'intérieur est mis à jour par
        // reconfigurerClasspathSiNecessaire quand le classpath change.
        // Sans classpath (loader=null), la factory retourne null pour
        // tous les tags dotted → ViewFactory par défaut → G15 MockView
        // fallback si classe introuvable.
        // NOTE : BridgeInflater appelle factory2.onCreateView avec
        // attrs=null (LayoutLib pattern — les attributs sont appliqués
        // APRÈS par AttributeApplier). La factory doit donc accepter
        // un attrs null sans planter — l'implémentation Kotlin gère ça
        // via le paramètre platform type.
        try {
            inflater.setFactory2(dexViewFactory)
        } catch (_: IllegalStateException) {
            // Déjà installée — ignore (peut arriver si l'inflater est
            // réutilisé, bien que le pattern soit de le recréer).
        }
    }

    /**
     * Installe un AttributeApplier par défaut (densité courante, pas de
     * résolveur projet). Permet à `background`, `orientation`, `textColor`,
     * `text`, `gravity`… d'être appliqués MÊME quand l'utilisateur teste
     * un layout sans projet/res/ attaché. Sera remplacé par un applier
     * plus riche quand `reconfigurerSiNecessaire` trouve un dossier res/.
     */
    private fun installerAttributeApplierParDefaut() {
        val echellePolice = echellePolice()
        val xdpi = contexte.resources.displayMetrics.xdpi
        val densite = densiteCourante()
        val convertisseur = DimensionConverter(densite, echellePolice, xdpi)
        val applier = AttributeApplier(contexte, convertisseur)
        inflater.setAttributeApplier(applier)
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
            ligneDeMalformation(requete.texte)?.let { ligne ->
                return ResultatPipeline(
                    rendu =
                        ResultatRendu(
                            ok = false,
                            message = XML_MALFORME,
                            ligne = ligne,
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
            val message = messageLisible(t)
            ResultatPipeline(
                rendu =
                    ResultatRendu(
                        ok = false,
                        message = message,
                        ligne = ligneDeMessageRendu(message) ?: -1,
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
        reconfigurerSiNecessaire(
            requete.dossierRes,
            densite,
            requete.nuit,
            requete.langue,
            requete.themeName,
            requete.paysage,
            requete.echellePolice,
        )
        // G1 / v0.132.0 : reconfigurer le classloader de DexViewFactory
        // si le classpath a changé. Le cache (CacheClasspath) survit entre
        // rendus — signature inchangée → pas de reconstruction.
        reconfigurerClasspathSiNecessaire(requete.classpath)
        inflater.reset()
        val racine = inflater.inflate(requete.texte)
        // v0.130.1 : l'AttributeApplier (installé par défaut dès init{})
        // gère android:background, android:orientation, android:textColor,
        // android:text, android:gravity… directement via les setters natifs
        // Android — plus besoin du hack appliquerBackgroundRacine pour le
        // chemin nominal.
        // CAS DE REPLI : si l'applier n'a pas posé le background (par ex.
        // valeur non reconnue, frappe en cours), on l'applique manuellement
        // depuis le XML brut — le hack reste en filet de sécurité.
        appliquerBackgroundRacine(racine, requete.texte)
        // Garde défensive (v0.134.3) : l'inflater LayoutLib pose
        // DÉSORMAIS les dimensions DÉCLARÉES sur la racine (v3.4.6 —
        // match_parent/wrap_content/taille fixe lus sur le tag racine) ;
        // ce repli ne sert que si un futur inflater oubliait d'en poser.
        if (racine.layoutParams == null) {
            racine.setLayoutParams(
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        // v0.133.0 — G5 : envelopper dans le chrome système (status bar +
        // action bar) si un thème est configuré. La vue racine devient le
        // conteneur chrome (LinearLayout vertical). Si pas de thème, on
        // garde la racine telle quelle (comportement v0.132.x).
        val vueRendue = envelopperChromeSiNecessaire(racine, densite, requete)
        // PE6e-f : l'échelle de police de la REQUÊTE pilote le moteur de
        // mesure (LayoutLib v3 `setFontScale` — `scaledDensity = density ×
        // échelle`) ; les dimensions du profil sont PERMUTÉES en paysage
        // (miroir `dim()` de la maquette : `{w: d.h, h: d.w}`).
        layoutEngine.setFontScale(requete.echellePolice)
        val largeurPortrait = requete.profil.versDeviceProfile().getWidthPx(densite)
        val hauteurPortrait = requete.profil.versDeviceProfile().getHeightPx(densite)
        val largeur = if (requete.paysage) hauteurPortrait else largeurPortrait
        val hauteur = if (requete.paysage) largeurPortrait else hauteurPortrait
        layoutEngine.render(vueRendue, largeur, hauteur)
        val bitmap = dessiner(vueRendue)
        // v0.134.2 — offset du CONTENU dans l'arbre rendu, calculé APRÈS le
        // layout (les positions left/top ne valent rien avant). Les bounds
        // ViewInfoCollector sont relatives à la racine passée ; on les
        // translate dans l'espace du BITMAP dès la collecte — invariant :
        // draw, hit-test et surlignage partagent LE MÊME espace de
        // coordonnées, aucune compensation en aval (le décalage chrome
        // v0.133.5 compensait en aval, une direction sur deux, et ne
        // traversait pas la frontière AIDL).
        val (decalageGauche, decalageHaut) = decalageContenuDansArbreRendu(racine)
        val vues = collecterVues(requete, racine, decalageGauche, decalageHaut)
        // v0.133.4 — utiliser vueRendue (chrome) pour les dimensions, pas
        // racine. Si le chrome enveloppe la racine, c'est le chrome qui est
        // mesuré par layoutEngine.render — racine n'est qu'un enfant.
        val rendu =
            if (compresser) {
                ResultatRendu(
                    ok = true,
                    message = null,
                    largeur = vueRendue.measuredWidth,
                    hauteur = vueRendue.measuredHeight,
                    nombreVues = compterVues(vueRendue),
                    dureeMs = (System.nanoTime() - debut) / 1_000_000,
                    png = compresserPng(bitmap),
                    vues = vues,
                )
            } else {
                ResultatRendu(
                    ok = true,
                    message = null,
                    largeur = vueRendue.measuredWidth,
                    hauteur = vueRendue.measuredHeight,
                    nombreVues = compterVues(vueRendue),
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
        reconfigurerSiNecessaire(
            requete.dossierRes,
            densite,
            requete.nuit,
            requete.langue,
            requete.themeName,
            requete.paysage,
            requete.echellePolice,
        )
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
        // PE6e-f : le tableau de valeurs suit l'orientation (largeur
        // permutée en paysage — cohérence avec le rendu LAYOUT).
        val largeurPortrait = requete.profil.versDeviceProfile().getWidthPx(densite)
        val hauteurPortrait = requete.profil.versDeviceProfile().getHeightPx(densite)
        val largeur = if (requete.paysage) hauteurPortrait else largeurPortrait
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
     * Collecte l'arbre rendu (ViewInfoCollector — bounds relatives à la
     * racine passée) et l'aligne aux éléments du texte original
     * ([AligneurElementsXml]) : chaque vue aplatie porte le span de SON
     * élément (P6). Un écart de séquence (défense : miroir imparfait de
     * l'inflater) désarme TOUTE la sélection — jamais de faux span (X4).
     *
     * v0.134.2 — les bounds sont TRANSLATÉES par
     * ([decalageGauche], [decalageHaut]) : l'offset du contenu dans l'arbre
     * rendu (le chrome G5 le décale vers le bas). Les `InfoVueRendu`
     * produites sont donc ABSOLUES dans le BITMAP — le contrat documenté
     * de [InfoVueRendu] — quel que soit le régime de transport (local OU
     * processus :preview, la translation vit dans les données, pas dans un
     * champ parallèle à faire voyager).
     */
    private fun collecterVues(
        requete: RequeteRendu,
        racine: View,
        decalageGauche: Int = 0,
        decalageHaut: Int = 0,
    ): Array<InfoVueRendu> {
        val infos = aplatir(ViewInfoCollector.collect(racine))
        // PE6c : attributs VIVANTS que ViewInfoCollector ne porte pas
        // (cliquable, orientation) — marche PARALLÈLE de l'arbre réel,
        // MÊME ordre pré-ordre que le collecteur (enfants ViewGroup,
        // getChildAt dans l'ordre, null sautés) ; la fermeture par
        // INDICE reste valable tant que les deux arbres coïncident.
        val attributs = collecterAttributsVues(racine, infos.size)
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
                infos[indice].versInfoVue(
                    InfoVueRendu.SANS_ELEMENT,
                    InfoVueRendu.SANS_ELEMENT,
                    decalageGauche,
                    decalageHaut,
                    attributs[indice],
                    famille = familleDefinitive(attributs[indice], ""),
                )
            }
        }
        return Array(infos.size) { indice ->
            val element = elements[indice]
            infos[indice].versInfoVue(
                element.debut,
                element.fin,
                decalageGauche,
                decalageHaut,
                attributs[indice],
                texteCodeDur = ProblemesRendu.texteCodeDur(requete.texte, element.debut, element.fin),
                famille = familleDefinitive(attributs[indice], element.nomBalise),
            )
        }
    }

    /**
     * Attributs vivants d'une vue rendue que ViewInfoCollector ne
     * porte pas (PE6c, PE11g) : cliquable, orientation LinearLayout,
     * texte tronqué (mesure LayoutLib) et image sans contentDescription
     * (lecture de la vue — les règles de [ProblemesPreview]) ; famille
     * de CONTENEUR par la chaîne de superclasses et drapeau de classe
     * de remplacement (PE12a — spec § 2.2).
     */
    private data class AttributsVue(
        val cliquable: Boolean,
        val orientation: Int,
        val texteTronque: Boolean,
        val sansContentDescription: Boolean,
        val famille: FamilleParent?,
        val remplacement: Boolean,
    ) {
        companion object {
            /** Repli défensif (écart de marche — jamais de faux attribut).
             * Famille INCONNUE (null) : le repli par balise tranche. */
            val PAR_DEFAUT: AttributsVue =
                AttributsVue(false, InfoVueRendu.ORIENTATION_SANS_OBJET, false, false, null, false)
        }
    }

    /**
     * Marche parallèle de l'arbre RÉEL (PE6c) — pré-ordre STRICTEMENT
     * identique à `ViewInfoCollector.collectInternal` (la vue, puis les
     * enfants du ViewGroup via `getChildAt` dans l'ordre, null sautés) :
     * l'indice dans le résultat correspond à l'indice dans la liste
     * aplatie du collecteur. Un écart (impossible en synchrone, défense
     * X4) dégrade TOUT au repli — jamais de faux attribut.
     */
    private fun collecterAttributsVues(
        racine: View,
        attendu: Int,
    ): List<AttributsVue> {
        val resultat = mutableListOf<AttributsVue>()

        fun parcourir(vue: View) {
            val orientation =
                if (vue is LinearLayout) {
                    vue.orientation
                } else {
                    InfoVueRendu.ORIENTATION_SANS_OBJET
                }
            resultat.add(
                AttributsVue(
                    cliquable = vue.isClickable,
                    orientation = orientation,
                    texteTronque = vue is TextView && ProblemesRendu.texteDepasse(vue),
                    sansContentDescription = vue is ImageView && vue.contentDescription == null,
                    // PE12a — famille PAR NOM de classe (spec § 2.2 : jamais
                    // instanceof, pas de dépendance Material/AndroidX ici).
                    famille = FamilleParent.deClasses(chaineSuperclasses(vue)),
                    remplacement = vue.javaClass.simpleName == InfoVueRendu.CLASSE_REMPLACEMENT,
                ),
            )
            if (vue is ViewGroup) {
                for (i in 0 until vue.childCount) {
                    val enfant = vue.getChildAt(i) ?: continue
                    parcourir(enfant)
                }
            }
        }
        parcourir(racine)
        if (resultat.size != attendu) {
            journal?.w(TAG) {
                "marche attributs désalignée : ${resultat.size} nœuds vs $attendu vues — repli"
            }
            return List(attendu) { AttributsVue.PAR_DEFAUT }
        }
        return resultat
    }

    /**
     * La chaîne des noms QUALIFIÉS des classes de [vue], de la classe
     * réelle jusqu'à `Object` incluse (spec § 2.2) — les faits bruts du
     * rendu, que [FamilleParent.deClasses] interprète PAR NOM (jamais
     * d'instanceof : les classes Material/AndroidX ne sont pas sur le
     * classpath de compilation de ce module).
     */
    private fun chaineSuperclasses(vue: View): List<String> {
        val noms = mutableListOf<String>()
        var classe: Class<*>? = vue.javaClass
        while (classe != null) {
            noms.add(classe.name)
            classe = classe.superclass
        }
        return noms
    }

    /**
     * La famille DÉFINITIVE d'une vue (spec § 2.2) : celle du rendu
     * (chaîne de superclasses), REMPLACÉE par la table des balises
     * pour un `MockView` (classe introuvable à l'inflation) ou quand
     * l'information manque (repli défensif) — jamais pour une classe
     * réellement inconnue, qui reste [FamilleParent.AUTRE].
     */
    private fun familleDefinitive(
        attributs: AttributsVue,
        balise: String,
    ): FamilleParent =
        if (attributs.remplacement || attributs.famille == null) {
            FamilleParent.deBalise(balise)
        } else {
            attributs.famille
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
        // Exemption detekt ciblée : un CHAMP d'[InfoVueRendu] = un
        // paramètre de la fabrique (même justification que le miroir
        // PARCELABLE — le format est le contrat, pas une accumulation).
        @Suppress("LongParameterList")
        fun versInfoVue(
            debutElement: Int,
            finElement: Int,
            decalageGauche: Int = 0,
            decalageHaut: Int = 0,
            attributs: AttributsVue,
            texteCodeDur: Boolean = false,
            famille: FamilleParent = FamilleParent.AUTRE,
        ): InfoVueRendu =
            InfoVueRendu(
                gauche = info.left + decalageGauche,
                haut = info.top + decalageHaut,
                droite = info.right + decalageGauche,
                bas = info.bottom + decalageHaut,
                libelle = info.simpleName + (info.idName?.let { nom -> " @$nom" } ?: ""),
                debutElement = debutElement,
                finElement = finElement,
                indiceParent = indiceParent,
                profondeur = profondeur,
                // PE6c — données enrichies (maquette) : classe (simpleName,
                // « MockView » quand G15 a dégradé), id de ressource,
                // marges/padding du rendu (px), cliquable/conteneur,
                // orientation LinearLayout.
                classe = info.simpleName,
                idRes = info.idName ?: "",
                margesGauche = info.marginLeft,
                margesHaut = info.marginTop,
                margesDroite = info.marginRight,
                margesBas = info.marginBottom,
                paddingGauche = info.paddingLeft,
                paddingHaut = info.paddingTop,
                paddingDroite = info.paddingRight,
                paddingBas = info.paddingBottom,
                cliquable = attributs.cliquable,
                conteneur = info.isViewGroup,
                orientation = attributs.orientation,
                // PE11g — détections moteur + source (règles de ProblemesPreview).
                texteTronque = attributs.texteTronque,
                sansContentDescription = attributs.sansContentDescription,
                texteCodeDur = texteCodeDur,
                // PE12a — famille de CONTENEUR (spec § 2.2), DERNIER champ.
                famille = famille.name,
            )
    }

    /** Compresse le bitmap en PNG (frontière AIDL — ~1 Mo max). */
    private fun compresserPng(bitmap: Bitmap): ByteArray {
        val tampon = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, QUALITE_PNG, tampon)
        return tampon.toByteArray()
    }

    /** Applique le background de la racine depuis le XML brut — LayoutLib
     *  ne le pose pas toujours (AttributeApplier ne gère pas android:background
     *  sur toutes les vues). On parse le XML pour extraire android:background
     *  et le poser manuellement. */
    private fun appliquerBackgroundRacine(
        racine: View,
        xml: String,
    ) {
        // Si la vue a déjà un background, ne rien faire.
        if (racine.background != null) return
        // Extraire android:background="..." du premier tag (la racine).
        val regex = Regex("""android:background\s*=\s*"([^"]+)"""")
        val match = regex.find(xml) ?: return
        val valeur = match.groupValues[1]
        try {
            val drawable = parseBackground(valeur)
            if (drawable != null) {
                racine.background = drawable
            }
        } catch (_: Throwable) {
            // Background illisible — la vue reste sans fond (blanc par défaut).
        }
    }

    /** Parse une valeur android:background en Drawable. */
    @Suppress("CyclomaticComplexMethod") // parseBackground = un when exhaustif par type de valeur
    private fun parseBackground(valeur: String): Drawable? =
        when {
            // Couleur hex (#RRGGBB, #AARRGGBB, #RGB)
            valeur.startsWith("#") -> {
                try {
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor(valeur))
                } catch (_: Throwable) {
                    null
                }
            }

            // @color/... → résolveur projet si branché
            valeur.startsWith("@color/") && ressources != null -> {
                try {
                    val nom = valeur.removePrefix("@color/")
                    val couleurInt = ressources?.getColor(nom) ?: return null
                    android.graphics.drawable.ColorDrawable(couleurInt)
                } catch (_: Throwable) {
                    null
                }
            }

            // @android:color/... → ressources système
            valeur.startsWith("@android:color/") -> {
                try {
                    val nom = valeur.removePrefix("@android:color/")
                    val id = android.R.color::class.java.getField(nom).getInt(null)
                    android.graphics.drawable.ColorDrawable(
                        contexte.getColor(id),
                    )
                } catch (_: Throwable) {
                    null
                }
            }

            // @drawable/... → v0.131.0 (G3) : résolution via DrawableResolverImpl.
            // Le résolveur gère les shapes, selectors, vectors, bitmaps, etc.
            // depuis le dossier res/drawable*/ du projet.
            valeur.startsWith("@drawable/") && drawableResolver != null -> {
                try {
                    drawableResolver?.resolve(valeur, contexte)
                } catch (_: Throwable) {
                    null
                }
            }

            // @android:drawable/... → ressources système framework
            valeur.startsWith("@android:drawable/") -> {
                try {
                    val nom = valeur.removePrefix("@android:drawable/")
                    val id = android.R.drawable::class.java.getField(nom).getInt(null)
                    contexte.getDrawable(id)
                } catch (_: Throwable) {
                    null
                }
            }

            else -> {
                null
            }
        }

    /**
     * Dessine la racine mesurée dans un bitmap off-screen (P4).
     *
     * v0.130.2 — Approche propre (Android Studio) : on injecte un faux
     * `AttachInfo` sur l'arbre via [AttacheurVue.injecter]. Si ça réussit,
     * `racine.draw(canvas)` dessine TOUT correctement (background + contenu
     * + enfants via `dispatchDraw`). C'est le pipeline standard d'Android.
     *
     * Fallback : si la réflexion échoue (Android version différente), on
     * retombe sur [dessinerVue] (récursion manuelle + bg.draw manuel) —
     * c'est le workaround historique de v0.127.2.
     *
     * Le bitmap est pré-rempli avec blanc car `Bitmap.createBitmap` crée
     * un bitmap transparent — si la racine n'a pas de background, le
     * fond reste blanc (pas transparent).
     */
    private fun dessiner(racine: View): Bitmap {
        val largeur = racine.measuredWidth.coerceAtLeast(1)
        val hauteur = racine.measuredHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(largeur, hauteur, Bitmap.Config.ARGB_8888)
        val canevas = Canvas(bitmap)
        canevas.drawColor(android.graphics.Color.WHITE)

        // Chemin nominal (v0.130.2) : injection AttachInfo → draw() standard.
        if (AttacheurVue.injecter(racine) { msg -> journal?.w(TAG) { msg } }) {
            // v0.133.2 — NE PAS catcher les erreurs de draw() ici. Si le
            // draw échoue, l'erreur se propage au rendu() qui retourne
            // ok=false avec le message — l'utilisateur voit l'erreur au
            // lieu d'un bitmap blanc silencieux. Le fallback dessinerVue
            // reste utilisé seulement si AttacheurVue.injecter échoue.
            racine.draw(canevas)
        } else {
            // Réflexion échouée — dessin manuel (récursion + bg.draw explicite).
            dessinerVue(racine, canevas)
        }
        return bitmap
    }

    /** Fallback de dessin (v0.127.2) — utilisé quand [AttacheurVue.injecter]
     *  échoue (réflexion cassée sur une version d'Android différente).
     *
     *  Dessine manuellement : background d'abord (bg.draw explicite car
     *  View.draw() skip drawBackground() sur vues opaques sans AttachInfo),
     *  puis contenu (vue.draw), puis récursion sur les enfants
     *  (dispatchDraw() est partiellement no-op sans AttachInfo). */
    private fun dessinerVue(
        vue: View,
        canevas: Canvas,
    ) {
        canevas.save()
        canevas.translate(vue.left.toFloat(), vue.top.toFloat())
        // 1. Dessiner le background (s'il existe).
        val bg = vue.background
        if (bg != null) {
            bg.setBounds(0, 0, vue.width, vue.height)
            bg.draw(canevas)
        }
        // 2. Dessiner le contenu de la vue elle-même (texte, etc.).
        //    On appelle draw() directement — sous Robolectric et sur
        //    appareil, certaines vues dessinent même sans fenêtre.
        try {
            vue.draw(canevas)
        } catch (_: Throwable) {
            // Une vue qui crash au draw ne doit pas casser le rendu.
        }
        // 3. Dessiner les enfants récursivement.
        if (vue is ViewGroup) {
            for (i in 0 until vue.childCount) {
                dessinerVue(vue.getChildAt(i), canevas)
            }
        }
        canevas.restore()
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
     * PE6e-f : l'échelle de police du PROFIL utilisateur pilote le
     * `scaledDensity` du convertisseur, et le qualifieur
     * `values-land`/`values-port` suit [paysage].
     * Échec (dossier absent/projet sans res/) : journal + repli natif —
     * le rendu continue, l'éditeur n'est jamais bloqué.
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList — les 7
     * dimensions SONT la configuration du rendu (miroir des champs de
     * [RequeteRendu], PE6e-f : paysage + échelle de police en plus) ;
     * les regrouper dans une data class dupliquerait la requête.
     */
    @Suppress("LongParameterList")
    private fun reconfigurerSiNecessaire(
        chemin: String?,
        densiteProfil: Float,
        nuit: Boolean,
        langue: String?,
        themeName: String?,
        paysage: Boolean,
        echellePolice: Float,
    ) {
        val dossier = chemin
        if (configurationInchangee(dossier, densiteProfil, nuit, langue, themeName, paysage, echellePolice)) {
            return
        }
        if (dossier != null && File(dossier).isDirectory) {
            try {
                // PE6e-f : scaledDensity = densité du PROFIL × échelle de
                // police de l'utilisateur (miroir de l'ancien scaledDensity,
                // la requête porte le FACTEUR, jamais une densité).
                val echelleAbsolute = densiteProfil * echellePolice
                val xdpi = contexte.resources.displayMetrics.xdpi
                val convertisseur = DimensionConverter(densiteProfil, echelleAbsolute, xdpi)

                val nouvellesRessources = ResourceResolverImpl(dossier)
                nouvellesRessources.setDimensionConverter(convertisseur)
                nouvellesRessources.setNightMode(nuit)
                // PE5a : langue (LayoutLib v3.0.0 P1 résolu).
                if (langue != null) {
                    nouvellesRessources.setLanguage(langue)
                }
                // PE6e-f : qualifieur d'orientation (LayoutLib v3 —
                // `values-land` / `values-port` priorisés).
                nouvellesRessources.setLandscape(paysage)
                inflater.setResourceResolver(nouvellesRessources)

                val applier = AttributeApplier(contexte, convertisseur, nouvellesRessources)
                val drawableResolverNeuf = DrawableResolverImpl(nouvellesRessources, convertisseur)
                applier.setDrawableResolver(drawableResolverNeuf)
                val themeResolverNeuf = ThemeResolverImpl(nouvellesRessources, convertisseur)
                enregistrerThemes(themeResolverNeuf, dossier)
                themeResolverNeuf.setNightMode(nuit)
                // v0.131.0 (G2) : poser le thème ACTIVITY sur le ThemeResolver.
                // Sans cette ligne, ?attr/colorPrimary retombe sur le thème IDE
                // (mauvaises couleurs Material). Le thème vient de RequeteRendu
                // (parse depuis AndroidManifest.xml côté CodeIDE).
                if (themeName != null) {
                    val nomSimple = themeName.removePrefix("@style/").removePrefix("@android:style/")
                    themeResolverNeuf.setTheme(nomSimple)
                }
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
        themeNameConfigure = themeName
        paysageConfigure = paysage
        echellePoliceConfigure = echellePolice
    }

    /**
     * Reconfigure le classloader de `DexViewFactory` si le classpath a
     * changé — G1 / Phase 2 (v0.132.0).
     *
     * Le `CacheClasspath` calcule une signature content-hash des entrées
     * (jars hashés par contenu, dossiers par chemin+t Réellement+taille).
     * Si la signature est inchangée, on ne reconstruit PAS le classloader
     * (économise ~500ms-2s sur un grand classpath).
     *
     * Si [entries] est vide : on met `dexViewFactory.loader = null` (la
     * factory dégrade vers `null` → ViewFactory par défaut + G15 MockView).
     */
    private fun reconfigurerClasspathSiNecessaire(entries: List<EntreeClasspath>) {
        val loader = cacheClasspath.classloader(entries)
        dexViewFactory.loader = loader
        classpathConfigure = entries.takeIf { it.isNotEmpty() }?.let { cacheClasspath.toString() }
    }

    /**
     * v0.134.2 — Offset de [racine] dans l'arbre RENDU (espace bitmap).
     *
     * `ViewInfoCollector.collect(racine)` produit des bounds relatives à
     * la racine passée (elles incluent `racine.left/top`). Quand le chrome
     * système enveloppe le contenu (G5), la racine vit dans un
     * FrameLayout lui-même empilé SOUS la status bar + action bar du
     * LinearLayout decor : les bounds sont alors décalées d'autant par
     * rapport au bitmap dessiné. La remontée de la chaîne des parents
     * cumule les positions (left/top) en défaquant le scroll
     * (scrollX/scrollY) — les bounds s'alignent 1:1 sur le bitmap par
     * CONSTRUCTION. Sans chrome, la racine n'a pas de parent Vue : l'offset
     * est (0, 0) et le comportement v0.132.x est inchangé.
     */
    private fun decalageContenuDansArbreRendu(racine: View): Pair<Int, Int> {
        var gauche = 0
        var haut = 0
        var parent = racine.parent as? View
        while (parent != null) {
            gauche += parent.left - parent.scrollX
            haut += parent.top - parent.scrollY
            parent = parent.parent as? View
        }
        return Pair(gauche, haut)
    }

    @Suppress("ReturnCount")
    private fun envelopperChromeSiNecessaire(
        racine: View,
        densite: Float,
        requete: RequeteRendu,
    ): View {
        // v0.133.2 — Chrome activé seulement quand themeName est présent
        // (manifest résolu par ControleurPreviewXml). Les tests sans
        // themeName → pas de chrome (comportement v0.132.x).
        if (requete.themeName == null) return racine

        val resolver = themeResolver
        // v0.134.4 — titre de l'action bar : le LABEL d'activité du
        // manifest (littéral ou @string/… résolu par le résolveur du
        // projet) au lieu du themeName brut ; repli themeName si absent
        // (comportement d'avant v0.134.4).
        val titre = titreActionBar(requete)
        // PE11j : le chrome suit la REQUÊTE — nuit (fond de la barre de
        // navigation) et barreNavigation (option du menu Calques).
        val chrome =
            ChromeSysteme(
                contexte,
                resolver,
                titreActivite = titre,
                nuit = requete.nuit,
                barreNavigation = requete.barreNavigation,
            )
        return chrome.envelopper(racine, densite)
    }

    /**
     * Titre de l'action bar du chrome (v0.134.4) — `labelActivite` de la
     * requête quand il existe : littéral tel quel, référence `@string/…`
     * résolue par le résolveur de ressources courant (projet puis natif),
     * repli final sur [RequeteRendu.themeName] (l'ancien comportement).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque sortie
     * porte une DÉCISION de repli DISTINCTE (label littéral, chaîne du
     * projet, chaîne native paquet, chaîne native framework, themeName).
     * Exemption lint DiscouragedApi : la preview résout les ressources du
     * PROJET UTILISATEUR par nom à l'exécution — R.foo.bar est impossible
     * hors de son build.
     */
    @Suppress("ReturnCount", "DiscouragedApi")
    private fun titreActionBar(requete: RequeteRendu): String? {
        val label = requete.labelActivite ?: return requete.themeName
        if (!label.startsWith("@string/")) return label
        val nom = label.substring("@string/".length)
        // Résolveur du projet d'abord (values/values-fr du projet).
        val duProjet = ressources?.getString("@string/$nom")
        if (!duProjet.isNullOrEmpty()) return duProjet
        // Repli natif (chaînes système/framework).
        val id = contexte.resources.getIdentifier(nom, "string", contexte.packageName)
        if (id != 0) return contexte.resources.getString(id)
        val idSysteme = contexte.resources.getIdentifier(nom, "string", "android")
        if (idSysteme != 0) return contexte.resources.getString(idSysteme)
        // Référence non résolue : le themeName reste plus informatif
        // qu'une référence brute.
        return requete.themeName
    }

    /** Passe en repli natif (aucun résolveur projet) — mais GARDE un
     *  AttributeApplier natif (sinon background/orientation/etc. cessent
     *  d'être appliqués — v0.130.1). */
    private fun repliNatif() {
        ressources = null
        themeResolver = null
        drawableResolver = null
        convertisseurCourant = null
        installerAttributeApplierParDefaut()
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

    /** La configuration courante des résolveurs couvre-t-elle déjà la demande ?
     * Exemption detekt ciblée (règle 16) : LongParameterList — même raison
     * que [reconfigurerSiNecessaire] (les 7 dimensions de configuration).
     */
    @Suppress("LongParameterList")
    private fun configurationInchangee(
        dossier: String?,
        densiteProfil: Float,
        nuit: Boolean,
        langue: String?,
        themeName: String?,
        paysage: Boolean,
        echellePolice: Float,
    ): Boolean =
        dossier == dossierResConfigure &&
            densiteProfil == densiteConfigure &&
            nuit == nuitConfigure &&
            langue == langueConfigure &&
            themeName == themeNameConfigure &&
            paysage == paysageConfigure &&
            echellePolice == echellePoliceConfigure &&
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
