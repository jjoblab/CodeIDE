package jo.codeide.preview.xml

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import jo.layoutlib.inflater.bridge.bars.ChromeSysteme
import jo.layoutlib.inflater.bridge.bars.ConfigChrome
import jo.layoutlib.inflater.bridge.bars.ModeNavigation
import jo.layoutlib.inflater.bridge.bars.ModePlacementDecor
import jo.layoutlib.inflater.bridge.bars.NavigationBar
import jo.layoutlib.inflater.bridge.bars.NavigationHandle
import jo.layoutlib.inflater.bridge.bars.StatusBar
import jo.layoutlib.resources.api.ActionBarCallback
import jo.layoutlib.themes.ThemeResolverImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le CHROME SYSTÈME possédé par le MOTEUR de rendu (PE13, ADR 0137) : les
 * vues maquette transportées telles quelles dans le frère
 * (LayoutLib `bridge/bars` — v3.5.0), pilotées par la CONFIG DE SESSION
 * [ConfigChrome] (titre résolu, surcharge « Barre de navigation » du menu
 * Calques, capacités étape 5). Ce module n'en construit plus AUCUNE — le
 * test consomme l'API du frère (Robolectric ; le frère ne teste que la
 * partie PURE en JVM, philosophie inchangée).
 *
 * Historique des assertions (transportées de v0.166.5, garde-fou G2 —
 * zéro changement visuel) : le chrome pose status bar + action bar +
 * contenu, puis la barre de navigation OPTIONNELLE en 4e pièce ; la status
 * bar porte ses glyphes maquette (9:41 / 5G ▮▮▮ 100%) ; le correctif C1
 * (ADR 0135) retire l'action bar des thèmes `windowNoTitle` ; la nuit —
 * désormais RÉGIME DES RÉSOLVEURS (ADR 0137 D2) — pilote les couleurs par
 * défaut de la planche par `ThemeResolver.isNightMode()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ChromeSystemeTest {
    /** Un contenu témoin (une vue sans enfant). */
    private fun contenu(): View = View(ApplicationProvider.getApplicationContext())

    /** Le chrome moteur à tester — config de session par défaut + titre. */
    private fun chrome(
        config: ConfigChrome = configDefaut(),
        themeResolver: ThemeResolverImpl? = null,
    ): ChromeSysteme = ChromeSysteme(
        ApplicationProvider.getApplicationContext(),
        themeResolver,
        config,
    )

    /** La config nominale de session (miroir de RenduLayoutLib). */
    private fun configDefaut(
        barreNavigation: Boolean? = true,
        modePlacement: ModePlacementDecor = ModePlacementDecor.PADDE_PAR_DEFAUT,
        modeNavigation: ModeNavigation = ModeNavigation.TROIS_BOUTONS,
        callback: ActionBarCallback = ActionBarCallback.parDefaut(),
    ): ConfigChrome = ConfigChrome
        .Builder()
        .appLabel("Titre")
        .surchargeNavigationBar(barreNavigation)
        .modePlacement(modePlacement)
        .modeNavigation(modeNavigation)
        .actionBarCallback(callback)
        .build()

    /** Un résolveur réel nourri d'un themes.xml inline, thème courant posé. */
    private fun resolver(themesXml: String, theme: String): ThemeResolverImpl = ThemeResolverImpl().apply {
        registerThemesFile(themesXml)
        setTheme(theme)
    }

    /** themes.xml de test : les trois régimes de fenêtre du fil historique. */
    private val themesFenetres: String =
        """
        <resources>
            <style name="Theme.Test.SansBarre">
                <item name="android:windowNoTitle">true</item>
                <item name="android:windowActionBar">false</item>
            </style>
            <style name="Theme.Test.AvecBarre">
                <item name="android:windowNoTitle">false</item>
                <item name="android:windowActionBar">true</item>
            </style>
            <style name="Theme.Test.PleinEcran">
                <item name="android:windowNoTitle">true</item>
                <item name="android:windowFullscreen">true</item>
            </style>
        </resources>
        """.trimIndent()

    @Test
    fun `la barre de navigation est rendue en quatrieme piece quand elle est active`() {
        val conteneur = chrome().envelopper(contenu(), 1f) as ViewGroup
        // status bar + action bar + contenu + barre de navigation.
        assertEquals(4, conteneur.childCount)
        val barre = conteneur.getChildAt(3)
        assertEquals(48, barre.layoutParams.height)
        assertTrue("la barre porte les trois glyphes", barre is NavigationBar)
    }

    @Test
    fun `la barre de navigation disparait quand l interrupteur est eteint`() {
        val conteneur =
            chrome(configDefaut(barreNavigation = false)).envelopper(contenu(), 1f) as ViewGroup
        // status bar + action bar + contenu — pas de quatrième pièce.
        assertEquals(3, conteneur.childCount)
    }

    @Test
    fun `le contenu garde la part restante du chrome`() {
        val conteneur = chrome().envelopper(contenu(), 1f) as LinearLayout
        val blocContenu = conteneur.getChildAt(2) as FrameLayout
        assertEquals(1f, (blocContenu.layoutParams as LinearLayout.LayoutParams).weight)
    }

    @Test
    fun `la status bar porte l heure et les icones de la maquette`() {
        // Miroir `.sbar` (ligne 1080) : « 9:41 » à gauche, « 5G ▮▮▮ 100% »
        // à droite — la vue du MOTEUR dessine le contenu maquette.
        val conteneur =
            chrome(configDefaut(barreNavigation = false)).envelopper(contenu(), 1f) as ViewGroup
        val barre = conteneur.getChildAt(0)
        assertEquals(24, barre.layoutParams.height)
        assertTrue("la barre porte l'heure et les icônes", barre is StatusBar)
    }

    @Test
    fun `le titre de l activite est pose dans l action bar`() {
        val conteneur =
            chrome(configDefaut(barreNavigation = false)).envelopper(contenu(), 1f) as ViewGroup
        val barre = conteneur.getChildAt(1) as ViewGroup
        assertEquals("l'action bar maquette porte le titre seul", 1, barre.childCount)
        val titre = barre.getChildAt(0) as TextView
        assertEquals("Titre", titre.text.toString())
        assertEquals(20f, titre.textSize)
    }

    @Test
    fun `correctif c1 - l action bar se retire quand le theme declare windowNoTitle`() {
        // Le fil « noBar » de la maquette : `windowNoTitle=true` +
        // `windowActionBar=false` — l'abar du chrome n'incruste plus le
        // document (constat C1 de l'ADR 0135, transporté côté moteur).
        val resolver = resolver(themesFenetres, "Theme.Test.SansBarre")
        val conteneur =
            chrome(configDefaut(barreNavigation = false), resolver).envelopper(contenu(), 1f) as ViewGroup
        // status bar + contenu, PAS d'action bar.
        assertEquals(2, conteneur.childCount)
    }

    @Test
    fun `l action bar reste posee quand le theme garde windowActionBar`() {
        val resolver = resolver(themesFenetres, "Theme.Test.AvecBarre")
        val conteneur =
            chrome(configDefaut(barreNavigation = false), resolver).envelopper(contenu(), 1f) as ViewGroup
        // status bar + action bar + contenu.
        assertEquals(3, conteneur.childCount)
    }

    @Test
    fun `windowFullscreen retire aussi la status bar`() {
        // Plein écran : ni status bar ni action bar — le chrome rend le
        // CONTENU TEL QUEL (retour anticipé, miroir v0.133.2).
        val resolver = resolver(themesFenetres, "Theme.Test.PleinEcran")
        val temoin = contenu()
        val enveloppe =
            chrome(configDefaut(barreNavigation = false), resolver).envelopper(temoin, 1f)
        assertTrue("sans barres le contenu se rend tel quel", enveloppe === temoin)
    }

    @Test
    fun `les fonds par defaut suivent la maquette jour et nuit - regime des resolveurs`() {
        // ADR 0137 D2 : PLUS de drapeau nuit — le régime vit dans le
        // RÉSOLVEUR (isNightMode). Resolver absent : couleurs jour de la
        // planche ; résolveur minimal en régime nuit : couleurs nuit.
        val clair = chrome(configDefaut(barreNavigation = false)).envelopper(contenu(), 1f) as ViewGroup
        assertEquals(
            android.graphics.Color.parseColor("#2B2F7A"),
            (clair.getChildAt(0).background as android.graphics.drawable.ColorDrawable).color,
        )
        assertEquals(
            android.graphics.Color.parseColor("#43559E"),
            (clair.getChildAt(1).background as android.graphics.drawable.ColorDrawable).color,
        )
        val resolverNuit =
            ThemeResolverImpl().apply {
                registerThemesFile(themesFenetres)
                setTheme("Theme.Test.AvecBarre")
                setNightMode(true)
            }
        val nuit =
            chrome(configDefaut(barreNavigation = false), resolverNuit).envelopper(contenu(), 1f) as ViewGroup
        assertEquals(
            android.graphics.Color.parseColor("#0C1030"),
            (nuit.getChildAt(0).background as android.graphics.drawable.ColorDrawable).color,
        )
        assertEquals(
            android.graphics.Color.parseColor("#BAC4FF"),
            (nuit.getChildAt(1).background as android.graphics.drawable.ColorDrawable).color,
        )
    }

    @Test
    fun `la barre de navigation suit aussi le regime nuit`() {
        // Miroir `.nbar` nuit : fond #000 (--s-sys) — même sans thème posé,
        // le résolveur porte le régime (PE13, ADR 0137 D2).
        val resolverNuit = ThemeResolverImpl().apply { setNightMode(true) }
        val conteneur =
            chrome(configDefaut(), resolverNuit).envelopper(contenu(), 1f) as ViewGroup
        assertEquals(
            android.graphics.Color.parseColor("#000000"),
            (conteneur.getChildAt(3).background as android.graphics.drawable.ColorDrawable).color,
        )
    }

    @Test
    fun `surimpression - le contenu reste plein cadre, les insets restent exposes`() {
        // ADR 0137 D3 : en SURIMPRESSION le contenu est mesuré PLEIN CADRE
        // (FrameLayout plein cadre, barres PAR-DESSUS) et les insets restent
        // la vérité à consommer en aval.
        val chromeSurimpression = chrome(configDefaut(modePlacement = ModePlacementDecor.SURIMPRESSION))
        val decor = chromeSurimpression.envelopper(contenu(), 1f) as ViewGroup
        assertTrue("le décor surimpression est un FrameLayout", decor is FrameLayout)
        val insets = chromeSurimpression.insets()
        assertEquals("inset haut = status bar + action bar", 24 + 56, insets.haut)
        assertEquals("inset bas = barre de navigation", 48, insets.bas)
        // Le contenu est PREMIER enfant (dessiné SOUS les barres).
        assertTrue(decor.getChildAt(0) is FrameLayout)
    }

    @Test
    fun `mode padde - les insets valent les barres posees`() {
        // PADDE : même valeurs d'insets — le contenu est déjà posé sous les
        // barres (comportement nominal, garde-fou G2).
        val chromePadde = chrome(configDefaut())
        chromePadde.envelopper(contenu(), 1f)
        val insets = chromePadde.insets()
        assertEquals(24 + 56, insets.haut)
        assertEquals(48, insets.bas)
        assertEquals(0, insets.gauche)
        assertEquals(0, insets.droite)
    }

    @Test
    fun `mode gestuel - la pilule remplace les trois boutons`() {
        // ADR 0137 D6 : ModeNavigation.GESTUELLE — la pilule 48×4 remplace
        // les trois glyphes dans la zone 48 dp (capacité moteur, sans UI).
        val conteneur =
            chrome(configDefaut(modeNavigation = ModeNavigation.GESTUELLE))
                .envelopper(contenu(), 1f) as ViewGroup
        val barre = conteneur.getChildAt(3)
        assertTrue("la zone gestuelle porte la pilule", barre is NavigationHandle)
        assertEquals(48, barre.layoutParams.height)
    }

    @Test
    fun `fleche retour et debordement suivent le callback de l action bar`() {
        // ADR 0137 D6 : la flèche retour (homeAsUp) et le bouton ⋮
        // (popupDebordement) sont pilotés par ActionBarCallback — défauts
        // MAQUETTE : titre seul.
        val avecTout =
            chrome(
                configDefaut(
                    barreNavigation = false,
                    callback = ActionBarCallback(true, true, null),
                ),
            ).envelopper(contenu(), 1f) as ViewGroup
        val barreTout = avecTout.getChildAt(1) as ViewGroup
        // flèche + titre + espace (poids) + débordement.
        assertEquals(4, barreTout.childCount)
        assertEquals("la flèche retour ouvre la barre", "FlecheRetour", barreTout.getChildAt(0).javaClass.simpleName)
        assertEquals(
            "le bouton de débordement clôt la barre",
            "Debordement",
            barreTout.getChildAt(3).javaClass.simpleName,
        )
        val sansRien = chrome(configDefaut(barreNavigation = false)).envelopper(contenu(), 1f) as ViewGroup
        assertEquals(1, (sansRien.getChildAt(1) as ViewGroup).childCount)
    }

    @Test
    fun `surcharge status bar - force la barre d un theme plein ecran`() {
        // ADR 0137 D1 : la SURCHARGE utilisateur gagne sur le thème (le
        // menu Calques force la présence même si windowFullscreen retire).
        val resolver = resolver(themesFenetres, "Theme.Test.PleinEcran")
        val config =
            ConfigChrome
                .Builder()
                .appLabel("Titre")
                .surchargeStatusBar(true)
                .surchargeNavigationBar(false)
                .build()
        val conteneur = chrome(config, resolver).envelopper(contenu(), 1f) as ViewGroup
        // La status bar est revenue (surcharge) ; l'action bar reste
        // retirée (windowNoTitle, pas de surcharge) ; pas de barre nav.
        assertEquals("status bar + contenu", 2, conteneur.childCount)
        assertTrue(conteneur.getChildAt(0) is StatusBar)
    }
}
