package jo.codeide

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Activité principale de CodeIDE.
 *
 * Étape 0 : point d'entrée minimal qui vérifie que la chaîne de build
 * complète (convention plugins, thème Material 3, ressources localisées)
 * produit un APK fonctionnel. L'écran de démarrage, le graphe de
 * navigation et l'injection arrivent à l'étape 1.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
