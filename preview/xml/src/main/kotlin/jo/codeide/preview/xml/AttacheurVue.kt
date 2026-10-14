package jo.codeide.preview.xml

import android.view.View
import android.view.ViewParent
import java.lang.reflect.Proxy

/**
 * Injection d'un faux [android.view.View.AttachInfo] sur l'arbre de vues
 * rendues — c'est l'approche d'Android Studio (RenderSessionImpl) pour
 * activer le pipeline de dessin COMPLET de `View.draw(Canvas)` sur des vues
 * non attachées à une fenêtre.
 *
 * **Problème résolu** : en preview hors-processus, les vues sont créées par
 * réflexion (BridgeInflater) mais jamais attachées à un `WindowManager`.
 * Sans `AttachInfo`, `View.draw()` présente deux défaillances :
 *
 * 1. **`drawBackground()` non appelé** sur les vues opaques — le flag
 *    `PFLAG_DIRTY_OPAQUE` est posé quand la vue a un background opaque,
 *    et la condition `(mAttachInfo == null || !mAttachInfo.mIgnoreDirtyState)`
 *    reste vraie → `dirtyOpaque = true` → `drawBackground()` est skippé.
 *
 * 2. **`dispatchDraw()` partiellement no-op** — `ViewGroup.dispatchDraw()`
 *    appelle `drawChild()` → `View.draw(Canvas, ViewGroup, long)` (3-arg)
 *    qui vérifie `mAttachInfo` et divers flags de cache. Sans `AttachInfo`,
 *    le chemin de cache est désactivé et certains enfants ne sont pas dessinés.
 *
 * **Solution** :
 * - Créer un `AttachInfo` minimal via réflexion (constructeur avec nulls).
 * - Mettre `mIgnoreDirtyState = true` → `dirtyOpaque = false` pour toutes
 *   les vues → `drawBackground()` est appelé.
 * - Créer un `ViewParent` no-op (Proxy dynamique) et le poser sur la racine
 *   → évite les NPE dans `onAttachedToWindow()` (ex. `requestTransparentRegion`).
 * - Appeler `dispatchAttachedToWindow(attachInfo, VISIBLE)` sur la racine —
 *   cette méthode propage l'AttachInfo à TOUT l'arbre (récursif) ET appelle
 *   `onAttachedToWindow()` sur chaque vue (initialisation : BoringLayout
 *   pour TextView, callbacks drawable pour ImageView, etc.).
 *
 * Après injection, `racine.draw(canvas)` dessine TOUT correctement :
 * background, contenu (onDraw), et enfants (dispatchDraw). Plus besoin de
 * récursion manuelle.
 *
 * **Robustesse** : si la réflexion échoue (signature différente sur une
 * future version d'Android), `injecter()` retourne `false` et l'appelant
 * retombe sur le dessin manuel [RenduLayoutLib.dessinerVue].
 *
 * **Lint PrivateApi** : l'accès à `View.AttachInfo` (package-private) via
 * réflexion est flagué par lint `PrivateApi`. C'est DELIBÉRÉ — c'est
 * l'approche d'Android Studio lui-même (RenderSessionImpl fait exactement
 * la même chose). Supprimé au niveau de la classe.
 */
@Suppress("PrivateApi") // Reflection sur View.AttachInfo — approche Android Studio RenderSessionImpl.
internal object AttacheurVue {
    /**
     * Cache de l'instance AttachInfo — créée UNE FOIS (réflexion coûteuse :
     * Class.forName + getDeclaredConstructors + newInstance). Réutilisée
     * pour TOUS les rendus. L'AttachInfo est immutable après configuration
     * (mIgnoreDirtyState=true), donc thread-safe pour réutilisation.
     *
     * v0.133.2 — avant, creerAttachInfo() était appelé à chaque rendu,
     * causant ~100-200ms de overhead de réflexion. Maintenant ~0ms après
     * le premier rendu.
     */
    @Volatile
    private var attachInfoCache: Any? = null
    private val attachInfoVerrou = Any()

    /**
     * Tente d'injecter un faux [AttachInfo] sur l'arbre [racine].
     *
     * @param journal callback optionnel pour logger l'échec de réflexion
     *        (utile pour diagnostiquer les Android versions où la signature
     *        de `AttachInfo` diffère).
     * @return `true` si l'injection a réussi (racine.draw() peut être
     *         appelé directement), `false` si la réflexion a échoué
     *         (retomber sur le dessin manuel [RenduLayoutLib.dessinerVue]).
     */
    fun injecter(
        racine: View,
        journal: ((String) -> Unit)? = null,
    ): Boolean =
        try {
            val attachInfo = obtenirAttachInfo()
            poserFauxParent(racine)
            propager(racine, attachInfo)
            true
        } catch (t: Throwable) {
            // Réflexion cassée (Android version différente) — pas fatal,
            // l'appelant retombera sur le dessin manuel. On journalise la
            // cause pour permettre le diagnostic (champ renommé, signature
            // changée, etc.).
            journal?.invoke("AttachInfo injection échouée : ${t.javaClass.simpleName}: ${t.message}")
            false
        }

    /**
     * Obtient l'AttachInfo mis en cache, ou le crée + configure si premier appel.
     * v0.133.2 — cache pour éviter la réflexion coûteuse à chaque rendu.
     */
    @Suppress("ReturnCount") // Cache + 3 issues naturelles
    private fun obtenirAttachInfo(): Any {
        attachInfoCache?.let { return it }
        synchronized(attachInfoVerrou) {
            attachInfoCache?.let { return it }
            val info = creerAttachInfo() ?: error("AttachInfo non créable")
            activerIgnoreDirtyState(info)
            attachInfoCache = info
            return info
        }
    }

    /**
     * Crée une instance minimale de `View.AttachInfo` via réflexion.
     *
     * `AttachInfo` est package-private (`android.view.View.AttachInfo`), son
     * constructeur prend de nombreux paramètres (`IWindowSession`,
     * `IWindow`, `ViewRootImpl`, `Handler`…). On passe `null` pour tous.
     */
    @Suppress("SpreadOperator") // newInstance exige un varargs ; pas d'alternative sans copie.
    private fun creerAttachInfo(): Any? {
        val classe = Class.forName("android.view.View\$AttachInfo")
        val constructeur = classe.declaredConstructors.minByOrNull { it.parameterCount } ?: return null
        constructeur.isAccessible = true
        val params = Array<Any?>(constructeur.parameterCount) { null }
        return constructeur.newInstance(*params)
    }

    /**
     * Met `mIgnoreDirtyState = true` sur l'AttachInfo — c'est le flag clé :
     * il force `dirtyOpaque = false` dans `View.draw()`, ce qui fait que
     * `drawBackground()` est appelé même pour les vues opaques.
     */
    private fun activerIgnoreDirtyState(attachInfo: Any) {
        val classe = Class.forName("android.view.View\$AttachInfo")
        val champ = classe.getDeclaredField("mIgnoreDirtyState")
        champ.isAccessible = true
        champ.setBoolean(attachInfo, true)
    }

    /**
     * Pose un `ViewParent` no-op (Proxy dynamique) sur la racine — évite
     * les NPE dans `onAttachedToWindow()` quand une vue appelle
     * `mParent.requestTransparentRegion()` ou `mParent.requestLayout()`.
     *
     * Le Proxy répond `false`/`null`/`0` à toutes les méthodes de
     * `ViewParent` — aucune action n'a de sens en preview hors-processus.
     *
     * Lint `SoonBlockedPrivateApi` : l'accès réflexif à `mParent` lèvera en
     * API 37+ — mais l'app cible `targetSdk=28` (ADR 0045), donc la réflection
     * fonctionne sur tous les appareils compatibles. Si l'app monte un jour à
     * `targetSdk≥37`, il faudra passer par un `FrameLayout` conteneur
     * (`addView` pose `mParent` naturellement sans réflexion).
     */
    @Suppress("SoonBlockedPrivateApi") // targetSdk=28 — réflexion safe (ADR 0045).
    private fun poserFauxParent(racine: View) {
        val fauxParent =
            Proxy.newProxyInstance(
                ViewParent::class.java.classLoader,
                arrayOf(ViewParent::class.java),
            ) { _, method, _ ->
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            } as ViewParent

        val champParent = View::class.java.getDeclaredField("mParent")
        champParent.isAccessible = true
        champParent.set(racine, fauxParent)
    }

    /**
     * Appelle `View.dispatchAttachedToWindow(attachInfo, VISIBLE)` sur la
     * racine — cette méthode est `protected` et prend un `AttachInfo`
     * package-private, donc on passe par la réflexion.
     *
     * Elle propage l'AttachInfo à TOUT l'arbre (récursif) ET appelle
     * `onAttachedToWindow()` sur chaque vue — l'initialisation propre
     * (BoringLayout pour TextView, drawable callbacks pour ImageView, etc.).
     */
    private fun propager(
        racine: View,
        attachInfo: Any,
    ) {
        val typeAttachInfo = Class.forName("android.view.View\$AttachInfo")
        val methode =
            View::class.java.getDeclaredMethod(
                "dispatchAttachedToWindow",
                typeAttachInfo,
                Int::class.javaPrimitiveType,
            )
        methode.isAccessible = true
        methode.invoke(racine, attachInfo, View.VISIBLE)
    }
}
