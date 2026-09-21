package jo.codeide

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.applyDynamicColorsIfAvailable

/**
 * Activité hôte unique de CodeIDE (section 5.4).
 *
 * Porte le [androidx.navigation.fragment.NavHostFragment] et ne fait rien
 * d'autre : chaque écran est un fragment (Onboarding, Home, NewProject,
 * Settings) et toute logique vit dans les ViewModels et le domaine.
 *
 * Cycle de démarrage :
 * 1. [installSplashScreen] **avant** `super.onCreate` (API SplashScreen,
 *    thème `Theme.CodeIDE.Splash` défini dans core:ui) ;
 * 2. couleurs dynamiques optionnelles (Android 12+, ADR 0008) ;
 * 3. edge-to-edge : la fenêtre s'étend sous les barres système, chaque
 *    fragment absorbe ses insets via `applySystemBarsInsets`.
 *
 * La destination initiale (onboarding ou accueil) dépendra de
 * `isSetupCompleted` à partir de l'étape 5.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        applyDynamicColorsIfAvailable()
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)
    }
}
