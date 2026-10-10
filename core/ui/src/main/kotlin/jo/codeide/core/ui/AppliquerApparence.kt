package jo.codeide.core.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.material.color.DynamicColors
import jo.codeide.core.model.PaletteCouleur
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Point d'application unique de l'apparence colorée (ADR 0060) :
 * couleurs dynamiques **ou** palette statique, appliquées au thème de
 * **chaque activité, dans chaque processus**, avant le gonflement du
 * contenu (`onActivityPreCreated`).
 *
 * Pourquoi cet objet (et pas directement
 * [DynamicColors.applyToActivitiesIfAvailable]) : le callback Material
 * teste la **capacité de l'appareil** (Android 12+), pas le réglage de
 * l'utilisateur — une fois enregistré, il ré-applique l'overlay
 * dynamique à toute activité créée, **même après désactivation** dans les
 * Paramètres (retour utilisateur v0.34 : l'éditeur et le diagnostic
 * restaient sur le fond d'écran alors que le reste de l'application
 * était repassé sur le thème de marque). Ici, l'état (dynamique oui/non,
 * palette) vit dans l'objet et se met à jour à chaque émission des
 * réglages — l'application suit TOUJOURS le réglage courant.
 *
 * Cycle :
 * - [installer] — une fois par processus, depuis `Application.onCreate`
 *   (état initial lu du miroir synchrone `MiroirApparence`, avant Hilt) ;
 * - [mettreAJour] — à chaque émission des réglages (processus principal) ;
 *   si l'état change, les activités vivantes sont recréées pour lever
 *   l'overlay déjà appliqué (un `applyStyle` ne se retire pas).
 * - [rappliquer] — par une activité qui REMPLACE son propre thème après
 *   `installSplashScreen()` (v0.80.6, retour utilisateur : seul l'éditeur
 *   et le diagnostic suivaient le réglage) : le `setTheme` interne de
 *   l'API SplashScreen repart d'un thème NEUF et efface l'overlay posé
 *   avant création — l'activité hôte le repose immédiatement, avant
 *   `super.onCreate()`/`setContentView()`.
 *
 * Distinction dynamique/palette (v0.80.6) : les couleurs dynamiques ne
 * s'appliquent que si l'appareil les SUPPORTE (Android 12+,
 * [DynamicColors.isDynamicColorAvailable]) ; sinon le réglage demandé
 * retombe honnêtement sur la palette statique — un appareil sans
 * Material You n'affiche jamais « ni l'un ni l'autre », l'écran
 * Apparence reste cohérent avec ce qui s'affiche réellement.
 *
 * Le mode de thème (clair/sombre/système) reste porté par
 * `AppCompatDelegate.setDefaultNightMode` ; cet objet ne couvre QUE les
 * couleurs.
 */
public object AppliquerApparence {
    /** État coloré courant — volatile : écrit au fil des réglages, lu sur le thread principal. */
    @Volatile
    private var etat: EtatCouleur = EtatCouleur(couleursDynamiques = true, palette = PaletteCouleur.INDIGO)

    /** Vrai dès l'installation des callbacks — un seul enregistrement par processus. */
    @Volatile
    private var installe = false

    /**
     * Vrai dès [installer] (installation SYNCHRONE de l'état, avant le
     * post d'enregistrement) — [rappliquer] ne fait rien tant que le
     * point d'application n'a pas été posé : sans `CodeIdeApplication`
     * (application de test Hilt, sans le cycle applicatif), les
     * activités ne reçoivent AUCUN état par défaut au lieu d'un état
     * dynamique fantôme.
     */
    @Volatile
    private var etatInitialise = false

    /** Activités vivantes du processus (références faibles : la recréation ne fuit pas). */
    private val activites = CopyOnWriteArrayList<WeakReference<Activity>>()

    /** Relais vers le thread principal (recréations et inscriptions). */
    private val filPrincipal = Handler(Looper.getMainLooper())

    /**
     * Installe le point d'application dans le processus — appelé depuis
     * `Application.onCreate`, **avant** `super.onCreate()` (l'état sert
     * dès la première activité).
     *
     * @param application application hôte du processus.
     * @param couleursDynamiques couleurs Material You demandées.
     * @param palette palette statique (sans objet si dynamiques actives).
     */
    public fun installer(
        application: Application,
        couleursDynamiques: Boolean,
        palette: PaletteCouleur,
    ) {
        etat = EtatCouleur(couleursDynamiques, palette)
        etatInitialise = true
        filPrincipal.post {
            if (!installe) {
                application.registerActivityLifecycleCallbacks(Observateur)
                installe = true
            }
        }
    }

    /**
     * Met à jour l'état coloré — appelé à chaque émission des réglages
     * (processus principal). Un changement recrée les activités vivantes
     * : l'overlay de thème ne se retire pas autrement.
     *
     * @param couleursDynamiques couleurs Material You demandées.
     * @param palette palette statique (sans objet si dynamiques actives).
     */
    public fun mettreAJour(
        couleursDynamiques: Boolean,
        palette: PaletteCouleur,
    ) {
        val precedente = etat
        if (precedente == EtatCouleur(couleursDynamiques, palette)) return
        etat = EtatCouleur(couleursDynamiques, palette)
        filPrincipal.post { recreerLesActivitesVivantes() }
    }

    /**
     * Ré-applique l'état coloré courant au thème d'une activité qui vient
     * de REMPLACER son propre thème — cas unique de l'application :
     * `installSplashScreen()` résout `postSplashScreenTheme` puis appelle
     * `Activity.setTheme()`, qui repart d'un thème NEUF et efface l'overlay
     * posé en `onActivityPreCreated` (v0.80.6 : l'hôte de navigation et
     * tous ses fragments restaient sur le thème de base quand l'éditeur
     * et le diagnostic suivaient le réglage).
     *
     * À appeler APRÈS le remplacement de thème et AVANT
     * `super.onCreate()`/`setContentView()` — le contenu doit se gonfler
     * avec les couleurs finales. Sans effet si [installer] n'a pas été
     * appelé dans le processus.
     *
     * @param activity activité dont le thème vient d'être remplacé.
     */
    public fun rappliquer(activity: Activity) {
        if (!etatInitialise) return
        appliquerA(activity)
    }

    /** Applique l'état courant au thème d'une activité (avant gonflement). */
    private fun appliquerA(activity: Activity) {
        val courant = etat
        if (courant.couleursDynamiques && DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivityIfAvailable(activity)
        } else {
            activity.theme.applyStyle(styleOverlay(courant.palette), true)
        }
    }

    /** Style d'overlay d'une palette (vérifié par test de non-régression). */
    private fun styleOverlay(palette: PaletteCouleur): Int =
        when (palette) {
            PaletteCouleur.INDIGO -> R.style.ThemeOverlay_CodeIDE_PaletteIndigo
            PaletteCouleur.BLEU -> R.style.ThemeOverlay_CodeIDE_PaletteBleu
            PaletteCouleur.TURQUOISE -> R.style.ThemeOverlay_CodeIDE_PaletteTurquoise
            PaletteCouleur.VERT -> R.style.ThemeOverlay_CodeIDE_PaletteVert
            PaletteCouleur.AMBRE -> R.style.ThemeOverlay_CodeIDE_PaletteAmbre
            PaletteCouleur.ROUGE -> R.style.ThemeOverlay_CodeIDE_PaletteRouge
            PaletteCouleur.VIOLET -> R.style.ThemeOverlay_CodeIDE_PaletteViolet
            PaletteCouleur.ROSE -> R.style.ThemeOverlay_CodeIDE_PaletteRose
        }

    /** Recrée les activités vivantes — l'état a changé après leur création. */
    private fun recreerLesActivitesVivantes() {
        val mortes = activites.filter { it.get() == null }
        activites.removeAll(mortes.toSet())
        activites.forEach { reference ->
            val activity = reference.get() ?: return@forEach
            if (!activity.isFinishing) activity.recreate()
        }
    }

    /**
     * Callbacks d'application : application avant création + suivi des
     * vivantes.
     *
     * Exemption detekt ciblée (règle 16 du prompt maître) : l'interface
     * `ActivityLifecycleCallbacks` impose ses 13 méthodes — seules deux
     * portent du code, les autres sont vides par contrat.
     */
    @Suppress("TooManyFunctions")
    private object Observateur : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) {
            appliquerA(activity)
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) {
            activites.add(WeakReference(activity))
        }

        override fun onActivityDestroyed(activity: Activity) {
            activites.removeAll { it.get() === activity }
        }

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit

        override fun onActivityPreStarted(activity: Activity) = Unit

        override fun onActivityPreResumed(activity: Activity) = Unit

        override fun onActivityPrePaused(activity: Activity) = Unit

        override fun onActivityPreStopped(activity: Activity) = Unit

        override fun onActivityPreDestroyed(activity: Activity) = Unit
    }

    /** État coloré immuable. */
    private data class EtatCouleur(
        val couleursDynamiques: Boolean,
        val palette: PaletteCouleur,
    )
}
