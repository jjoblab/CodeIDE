package jo.codeide.preview.xml

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException

/**
 * Factory2 adapter pour vues custom du projet — G1 / Phase 2 (v0.132.0),
 * corrigé v0.166.4 (retour propriétaire du 2026-10-22 : « j'ai ajouté un
 * chip, ça a replié sur MockView, bien d'autres vues aussi »).
 *
 * **Problème résolu (v0.132.0)** : `ViewFactory.createView(tag)` fait
 * `Class.forName(className, false, context.getClass().getClassLoader())`
 * — c'est le classloader de l'IDE, qui contient les AndroidX/Material
 * bundlés par l'app mais JAMAIS les classes du projet (`com.example.FooView`)
 * ni les versions de bibliothèques déclarées par le projet.
 *
 * **Régression corrigée (v0.166.4)** : depuis le branchement de l'APK
 * debug dexé du projet (v0.166.3), cette factory dérobait TOUS les tags
 * dotted au classloader projet — y compris `com.google.android.material.*`
 * et `androidx.*`. Les classes Material chargées depuis l'APK projet
 * résolvent leurs ressources par les IDS DE L'APK, qui ne sont PAS ceux
 * de l'app hôte → theme-enforcement échoue → MockView. Les 19 nouvelles
 * tuiles de la palette insèrent JUSTEMENT des balises FQN (Chip,
 * MaterialCardView, CoordinatorLayout, AppBarLayout, TabLayout,
 * ViewPager2…). La règle est désormais : un tag dotted résolvable par
 * le classloader de l'HÔTE n'est PAS une vue projet — le chemin nominal
 * (ViewTagRegistry/ViewFactory) le rend en VRAIE vue, avec les bons ids
 * de ressources et le bon thème. Le classloader projet ne sert QUE les
 * vues que l'hôte ne connaît PAS (les vraies vues custom).
 *
 * **Contract AOSP `LayoutInflater.Factory2`** :
 * - `onCreateView(parent, name, ctx, attrs)` retourne la vue ou `null`.
 * - `null` → l'inflater fait appel à la création par défaut (framework widgets).
 * - Lève → l'inflation aborte (mais G15 dans `BridgeInflater.createViewSafely`
 *   catch et dégrade vers MockView).
 *
 * **Stratégie** :
 * - Tag court (`TextView`, `Button` — sans `.`) → retourne `null` (chemin
 *   nominal du ViewTagRegistry).
 * - Tag dotted boot-loadable (`android.view.View`, `android.widget.Button`)
 *   → retourne `null` (le ViewFactory par défaut les gère déjà).
 * - Tag kotlin.* / kotlinx.* → retourne `null` (stdlib de l'hôte).
 * - Tag dotted RÉSOLVABLE PAR L'HÔTE (`androidx.*`,
 *   `com.google.android.material.*`, toute bibliothèque bundlée) →
 *   retourne `null` : chemin nominal, ids et thème de l'hôte (v0.166.4).
 * - Tag dotted INCONNU de l'hôte (`com.example.FooView` — vue custom
 *   projet) → essaie `Class.forName` sur le classloader projet. Si
 *   trouvé et `View.isAssignableFrom`, instancie via `(Context,
 *   AttributeSet)` puis `(Context)` puis `()`.
 * - Si non trouvé ou pas une View → retourne `null` (ViewFactory par défaut
 *   lèvera InflateException → G15 MockView fallback).
 *
 * **Classloader mutable** : `BridgeInflater.setFactory2` est ONE-SHOT (lève
 * `IllegalStateException` au second appel — AOSP contract). On ne peut pas
 * ré-installer la factory à chaque changement de classpath. À la place,
 * la factory tient un `@Volatile var loader` qui est mis à jour par
 * `RenduLayoutLib.reconfigurerClasspathSiNecessaire`. Une factory sans
 * loader (au démarrage) retourne `null` pour tous les tags dotted —
 * dégradation propre vers le ViewFactory par défaut.
 *
 * **Thread-safety** : `loader` est `@Volatile` — le rendu est sérialisé
 * par `ServicePreview` (`synchronized(verrou)`) ou le HandlerThread de
 * `TransportRenduLocal`, donc pas de concurrence sur la factory.
 */
@Suppress("ReturnCount") // Garde-fou contract AOSP : chaque garde est une issue DISTINCTE
class DexViewFactory : LayoutInflater.Factory2 {
    /**
     * Classloader projet courant — mis à jour par
     * `RenduLayoutLib.reconfigurerClasspathSiNecessaire` quand le classpath
     * change. `null` au démarrage → la factory dégrade vers `null` (retour
     * par défaut de l'inflater).
     *
     * Parenté : doit être parenté au BOOT classloader
     * (`View::class.java.classLoader`), PAS au classloader de l'IDE —
     * sinon les `androidx.*` du projet retomberaient sur ceux de l'IDE.
     */
    @Volatile
    public var loader: ClassLoader? = null

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
        essayerCreation(name, context, attrs)

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
        essayerCreation(name, context, attrs)

    /**
     * Tente de créer la vue `name` via le classloader projet.
     *
     * @return la vue instanciée, ou `null` si le tag est court, boot-loadable,
     *         connu de l'hôte, non trouvé, ou pas une sous-classe de View.
     */
    private fun essayerCreation(
        name: String,
        context: Context,
        @Suppress("UNUSED_PARAMETER") attrs: AttributeSet,
    ): View? {
        // Tag court (pas de `.`) → pas pour nous : ViewTagRegistry gère.
        if (!name.contains('.')) return null

        // Tag boot-loadable (android.*, java.*) → on laisse ViewFactory s'en
        // charger (il utilise context.getClass().getClassLoader() qui
        // arrive à charger ces classes framework). Évite de dupliquer
        // l'inflation des classes framework et préserve le bon parentage
        // du contexte.
        if (name.startsWith("android.") || name.startsWith("java.")) return null

        // Tag kotlin.* / kotlinx.* : si non trouvé dans le classloader IDE,
        // c'est probablement la stdlib Kotlin bundlée — laisser ViewFactory.
        if (name.startsWith("kotlin.") || name.startsWith("kotlinx.")) return null

        // v0.166.4 — L'HÔTE D'ABORD (correctif Chip → MockView) : une classe
        // que l'app hôte SAIT charger n'est pas une vue « projet ». Les vues
        // Material/AndroidX chargées depuis l'APK projet résolvent leurs
        // ressources (styles, attrs) par des IDS qui n'existent pas dans
        // l'app hôte — le theme-enforcement échoue et BridgeInflater dégrade
        // en MockView. En laissant le chemin nominal faire (ViewFactory sur
        // le classloader hôte), Chip, MaterialCardView, CoordinatorLayout,
        // TabLayout, ViewPager2… rendent en VRAIES vues. Le classloader
        // projet ne prend QUE les vues que l'hôte ne connaît pas.
        if (chargableParLhote(context, name)) return null

        // Pas de classloader projet configuré → on dégrade (retour null).
        val classLoader = loader ?: return null

        // Note : `attrs` est passé null par BridgeInflater (ligne 552) —
        // on ne le transmet PAS au constructeur de la vue (on utilise
        // le constructeur (Context) par défaut). Les attributs sont
        // appliqués APRÈS par AttributeApplier (le pattern LayoutLib).
        return creerVueParNom(name, context, classLoader)
    }

    /**
     * La classe est-elle résolvable par le classloader de l'HÔTE (l'app
     * qui embarque la preview) ? Probe SANS initialisation (aucun effet
     * de bord de static initializer), résultat mis en cache par ART.
     */
    private fun chargableParLhote(context: Context, className: String): Boolean = try {
        Class.forName(className, false, context.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: NoClassDefFoundError) {
        false
    }

    /**
     * Charge la classe `className` via `classLoader` et instancie la vue
     * via le constructeur `(Context)` (les attributs sont appliqués
     * APRÈS par AttributeApplier — pattern LayoutLib).
     *
     * @return la vue, ou `null` si classe introuvable / pas une View /
     *         instanciation échouée.
     */
    @Suppress("ReturnCount") // Garde-fou : chaque garde est une issue DISTINCTE
    private fun creerVueParNom(
        className: String,
        context: Context,
        classLoader: ClassLoader,
    ): View? {
        val classe: Class<*> =
            try {
                Class.forName(className, false, classLoader)
            } catch (_: ClassNotFoundException) {
                return null
            } catch (_: NoClassDefFoundError) {
                return null
            }

        // Pas une View → pas pour nous.
        if (!View::class.java.isAssignableFrom(classe)) return null

        // Résoudre le constructeur (Context) — les attributs sont appliqués
        // après par AttributeApplier, pas par le constructeur.
        val constructeur = resoudreConstructeur(classe) ?: return null

        return try {
            instancier(constructeur, context)
        } catch (_: InvocationTargetException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Résout le constructeur à utiliser — priorité `(Context)`.
     * Les attributs sont appliqués APRÈS par AttributeApplier (pattern
     * LayoutLib), donc on n'a pas besoin du constructeur `(Context, AttributeSet)`.
     */
    @Suppress("ReturnCount") // 3 tentatives = 3 issues naturelles
    private fun resoudreConstructeur(classe: Class<*>): Constructor<*>? {
        classe.getConstructor(Context::class.java)?.let { return it }
        classe.getConstructor(Context::class.java, AttributeSet::class.java)?.let { return it }
        classe.getConstructor()?.let { return it }
        return null
    }

    /**
     * Instancie la vue via le constructeur résolu.
     */
    private fun instancier(constructeur: Constructor<*>, context: Context): View? {
        val nbParams = constructeur.parameterTypes.size
        val instance =
            when (nbParams) {
                2 -> constructeur.newInstance(context, null)
                1 -> constructeur.newInstance(context)
                0 -> constructeur.newInstance()
                else -> return null
            }
        return instance as? View
    }
}
