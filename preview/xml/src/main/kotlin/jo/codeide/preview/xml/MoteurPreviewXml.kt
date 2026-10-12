package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.layoutlib.drawables.DrawableResolverImpl
import jo.layoutlib.inflater.AttributeApplier
import jo.layoutlib.inflater.BridgeInflater
import jo.layoutlib.inflater.DeviceProfile
import jo.layoutlib.inflater.ViewTagRegistry
import jo.layoutlib.layout.LayoutEngineImpl
import jo.layoutlib.resources.DimensionConverter
import jo.layoutlib.resources.ResourceResolverImpl
import jo.layoutlib.themes.ThemeResolverImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.StringReader

/**
 * Moteur de preview XML — PE1 (PREVIEW.md §3, P1–P5).
 *
 * « RenderService adapté » : le pipeline est celui du `RenderService` de
 * LayoutLib (validation XmlPullParser → inflation par vraies vues →
 * mesure/layout → callback), avec DEUX divergences voulues par le
 * cadrage : le rendu s'exécute sur un **fil dédié** (jamais le thread
 * UI de l'IDE — le RenderService d'origine rend sur le principal), et le
 * produit est un **bitmap off-screen** (P4 — aucune vue n'entre dans la
 * hiérarchie de l'IDE ; le bitmap se dessine dans [VuePreviewXml]).
 *
 * Comportements repris tels quels de LayoutLib/Android Studio :
 * - debounce 400 ms (défaut) ;
 * - XML malformé (frappe en cours) → rendu annulé **silencieusement**, le
 *   dernier rendu valide reste affiché ([EtatPreviewXml.EnEdition]) ;
 * - erreur sémantique (XML valide, tag inconnu…) → [EtatPreviewXml.ErreurRendu] ;
 * - texte vide/blanc → ignoré silencieusement.
 *
 * Ressources du projet (P5) : `res/` du projet édité, résolu par l'hôte
 * (FUSE — chemin POSIX réel) via [definirDossierRes]. Les thèmes du
 * projet (values/themes.xml, values-night/themes.xml, styles.xml) y sont
 * enregistrés au mieux — absent, le moteur rend avec le repli natif.
 *
 * Cycle de vie : [close] coupe le debounce et le worker (le bitmap
 * courant n'est PAS recyclé — la vue peut encore le dessiner ; le GC
 * d'ART collecte les bitmaps du tas Java).
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Suppress("TooManyFunctions") // Pipeline nommé ; l'éclater disperserait le single-flight.
public class MoteurPreviewXml(
    private val contexte: Context,
    private val dispatchers: DispatcherProvider,
    private val journal: AppLogger?,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val etatInterne = MutableStateFlow<EtatPreviewXml>(EtatPreviewXml.Inactif)

    /** État courant du panneau (rendu, erreur, édition). */
    public val etat: StateFlow<EtatPreviewXml> = etatInterne.asStateFlow()

    /** Inflater LayoutLib — construit une fois, réinitialisé par rendu. */
    private val inflater: BridgeInflater = BridgeInflater(contexte, ViewTagRegistry(false))

    /** Moteur de mesure/layout LayoutLib. */
    private val layoutEngine = LayoutEngineImpl()

    /** Soumissions debouncées — le texte est tiré À LA DEMANDE (jamais par frappe). */
    private val soumissions =
        MutableSharedFlow<() -> String>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private var travailleur: Job? = null
    private val verrouRendu = Mutex()
    private var dossierRes: String? = null
    private var dossierResConfigure: String? = null
    private var ferme = false

    /** Profil device par défaut (PE3 ajoutera le sélecteur). */
    private val profil = DeviceProfile.PIXEL_4

    init {
        val densite = contexte.resources.displayMetrics.density
        layoutEngine.setDensity(densite)
        layoutEngine.setFontScale(echellePolice())
        travailleur =
            scope.launch {
                soumissions
                    .debounce(DEBOUNCE_MS)
                    .collect { fournisseur -> traiter(fournisseur) }
            }
    }

    /**
     * Soumet un texte à rendre (le fournisseur est invoqué APRÈS le
     * debounce, sur le fil du moteur : une frappe ne coûte aucune copie).
     */
    public fun soumettre(fournisseur: () -> String) {
        if (ferme) return
        soumissions.tryEmit(fournisseur)
    }

    /**
     * Soumet un rendu immédiat (ouverture du panneau, bascule d'onglet) :
     * PAS de debounce — mais TOUJOURS le mutex de rendu (single-flight,
     * l'inflater n'est pas thread-safe).
     */
    public fun soumettreImmediat(fournisseur: () -> String) {
        if (ferme) return
        scope.launch { traiter(fournisseur) }
    }

    /**
     * Définit le dossier `res/` du projet (P5 — chemin POSIX résolu par
     * l'hôte via FUSE). Null = plus de projet : repli ressources natives.
     * La reconfiguration n'a lieu que si le chemin CHANGE.
     */
    public fun definirDossierRes(chemin: String?) {
        dossierRes = chemin
    }

    /** Coupe debounce et worker. Idempotent. */
    public override fun close() {
        if (ferme) return
        ferme = true
        travailleur?.cancel()
        travailleur = null
    }

    /** Traite une soumission (debouncée ou immédiate) : validation puis rendu, en single-flight. */
    private suspend fun traiter(fournisseur: () -> String) {
        verrouRendu.withLock {
            val texte = withContext(dispatchers.default) { fournisseur() }
            if (texte.isBlank()) return
            if (!xmlBienForme(texte)) {
                // Frappe en cours : signal discret, le rendu précédent reste.
                if (etatInterne.value !is EtatPreviewXml.RenduValide) {
                    etatInterne.value = EtatPreviewXml.EnEdition
                }
                return
            }
            etatInterne.value = withContext(dispatchers.default) { rendre(texte) }
        }
    }

    /** Exécute le pipeline de rendu (validation déjà faite) — fil dédié. */
    private fun rendre(texte: String): EtatPreviewXml {
        val debut = System.nanoTime()
        return try {
            reconfigurerSiNecessaire()
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
            EtatPreviewXml.RenduValide(
                bitmap = bitmap,
                dureeMs = (System.nanoTime() - debut) / 1_000_000,
                nombreVues = compterVues(racine),
                largeur = racine.measuredWidth,
                hauteur = racine.measuredHeight,
            )
        } catch (annulation: kotlinx.coroutines.CancellationException) {
            throw annulation
        } catch (t: Throwable) {
            journal?.w(TAG) { "rendu preview échoué : ${t.message}" }
            EtatPreviewXml.ErreurRendu(messageLisible(t))
        }
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
    private fun reconfigurerSiNecessaire() {
        val chemin = dossierRes ?: return
        if (chemin == dossierResConfigure) return
        try {
            val densite = contexte.resources.displayMetrics.density
            val echellePolice = echellePolice()
            val xdpi = contexte.resources.displayMetrics.xdpi
            val convertisseur = DimensionConverter(densite, echellePolice, xdpi)

            val ressources = ResourceResolverImpl(chemin)
            ressources.setDimensionConverter(convertisseur)
            inflater.setResourceResolver(ressources)

            val applier = AttributeApplier(contexte, convertisseur, ressources)
            applier.setDrawableResolver(DrawableResolverImpl(ressources, convertisseur))
            val themeResolver = ThemeResolverImpl(ressources, convertisseur)
            enregistrerThemes(themeResolver, chemin)
            applier.setThemeResolver(themeResolver)
            inflater.setAttributeApplier(applier)
            dossierResConfigure = chemin
        } catch (t: Throwable) {
            // ResourceException (res/ absent), IO… : repli natif documenté.
            journal?.w(TAG) { "résolveurs preview non branchés ($chemin) : ${t.message}" }
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

    /** Validation XML complète (même stratégie que RenderService : XmlPullParser). */
    private fun xmlBienForme(texte: String): Boolean =
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
        private const val TAG = "PreviewXml"

        /** Debounce identique à RenderService (400 ms par défaut). */
        private const val DEBOUNCE_MS = 400L
    }
}
