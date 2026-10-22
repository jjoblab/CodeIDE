package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import jo.codeide.preview.xml.ArbreAttributsXml
import jo.codeide.preview.xml.EntreeClasspath
import jo.codeide.preview.xml.GenreEntreeClasspath
import jo.codeide.preview.xml.GenrePreview
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.ProfilPeripherique
import jo.codeide.preview.xml.RenduLayoutLib
import jo.codeide.preview.xml.RequeteRendu
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode
import java.io.File
import kotlin.math.abs

/**
 * MESURE LayoutLib des trois fixtures « familles de parents » (PE12h —
 * spec PREVIEW_PARENTS § 15.3 : « rendre activity_profil.xml,
 * activity_carte.xml, activity_fil.xml dans LayoutLib, relever les
 * bornes des vues, comparer à meas_* de la maquette (cible ≤ 1 dp) »).
 * La séance sur VRAI appareil (`PROTOCOLE_MESURE_APPAREIL.md`) reste à
 * la charge du propriétaire ; CE TEST en garantit l'attendu avec la
 * même LayoutLib sous Robolectric NATIVE (mêmes classes, mêmes métriques
 * de police réelles — la bibliothèque de rendu est identique à celle de
 * l'appareil).
 *
 * Cadre de la comparaison : profil Pixel 4 (393 × 851 dp, 440 dpi —
 * device 0 de la maquette, miroir `DEVICES[0]`), français, police ×1,
 * chrome système complet (barre d'état 24 + action bar 56 + barre de
 * navigation 48). Les bornes sont comparées **relativement à la RACINE**
 * du document (la vue racine porte les coordonnées absolues de la
 * planche) : les relevés maquette `meas_rendu_parents.json` sont lus en
 * retranchant `S.b.root`, les vues rendues en retranchant les bornes de
 * la racine — l'écart « hauteur de la barre d'état » attendu par § 15.3
 * s'élimine ainsi de la comparaison.
 *
 * ## CE QUE LA MESURE A RÉVÉLÉ (consigné en annexe de l'ADR 0135)
 *
 * La mesure a été menée AVANT de figer les rendus (§ 15.3) ; elle
 * révèle des DÉFAUTS du moteur — le frère LayoutLib v2.2.0 et le chrome
 * du pipeline — qui LIMITENT les champs comparables. Chaque exclusion
 * de la table ci-dessous porte son numéro d'constat :
 *
 * - **F1 — références d'id inertes** (`BridgeInflater.resolveIdReference`
 *   retourne un id aléatoire quand l'id n'est pas déclaré dans les
 *   Resources du contexte) : les règles `RelativeLayout` vers un frère
 *   (`below`, `toEndOf`, `alignTop`, `alignStart`…) et
 *   `app:layout_anchor` ne s'appliquent pas — la vue tombe au coin.
 *   Touchés : profil `nom`, `role`, `separateur` (y), `bio`, `stats`,
 *   `n_projets`, `btn_suivre` (y) ; fil `fab_nouveau` (x, y).
 * - **F2 — comportement non résolu**
 *   (`createCoordinatorLayoutParams` fait `Class.forName` de la VALEUR
 *   littérale : `@string/appbar_scrolling_view_behavior` n'est pas
 *   résolu) : la `liste` du fil n'est pas posée sous la barre.
 * - **F3 — attributs agrégés non lus** (`layout_marginHorizontal`,
 *   `paddingHorizontal`… API 26) : marges horizontales et padding des
 *   `stats`/`separateur`/`bio` du profil et de la `legende` de la carte
 *   sont perdus.
 * - **C1 — action bar du chrome incruste** (`ChromeSysteme.attrBool`
 *   interroge `getStyleAttribute(null, …)` qui rend TOUJOURS null) : le
 *   thème ne peut pas retirer l'action bar ; le fil est donc rendu avec
 *   elle et sa racine garde 723 dp au lieu des 779 dp de la planche
 *   `noBar` — la HAUTEUR de la racine du fil n'est pas comparée.
 * - **F4 — métriques de texte gonflées** : les `TextView` wrap_content
 *   mesurent ~2,7× l'attendu (clampés au parent) — sans effet sur les
 *   bornes comparées (fixes) ; police maquette ≠ police appareil (§ 12.1
 *   : largeurs et hauteurs de texte jamais comparées).
 *
 * Après correctif du frère (bump LayoutLib), la table s'ÉTEND aux vues
 * exclues — voir l'annexe « mesure PE12h » de l'ADR 0135 (le PV complet
 * des relevés).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
// Métriques NATIVE : vraies polices, vraies mesures de texte.
@GraphicsMode(Mode.NATIVE)
public class MesureRenduParentsTest {
    /** Le dossier `res/` du projet de mesure (copie des ressources de test). */
    @get:Rule
    public val dossier: TemporaryFolder = TemporaryFolder()

    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Le relevé maquette (bornes des vues des trois documents). */
    private val mesures: JSONObject =
        JSONObject(File(racineDepot, CHEMIN_MESURES).readText())

    /** Le contexte du rendu : français, 440 dpi, thème Material de l'aperçu. */
    private val contexte: Context by lazy { contexteDeRendu() }

    // ── Les trois documents (un test par famille) ─────────────────────

    @Test
    public fun `profil - gravite du parent et ancrages au bord mesures`() {
        mesurer(
            fichier = "activity_profil.xml",
            nom = "profil",
            theme = THEME_AVEC_BARRE,
            champs = CHAMPS_PROFIL,
        )
    }

    @Test
    public fun `carte - la gravite du FrameLayout place badge centre et legende`() {
        mesurer(
            fichier = "activity_carte.xml",
            nom = "carte",
            theme = THEME_AVEC_BARRE,
            champs = CHAMPS_CARTE,
        )
    }

    @Test
    public fun `fil - la barre empilee et la racine du CoordinatorLayout`() {
        mesurer(
            fichier = "activity_fil.xml",
            nom = "fil",
            theme = THEME_SANS_BARRE,
            champs = CHAMPS_FIL,
        )
    }

    // ── La machine de mesure ──────────────────────────────────────────

    /** Un champ de borne comparé (la DROITE sert aux vues collées au bord fin). */
    private enum class Champ {
        X,
        Y,
        LARGEUR,
        HAUTEUR,
        DROITE,
    }

    /**
     * Rend la fixture puis compare chaque borne déclarée à ±1 dp,
     * RELATIVEMENT à la racine du document (des deux côtés).
     */
    private fun mesurer(
        fichier: String,
        nom: String,
        theme: String?,
        champs: Map<String, List<Champ>>,
    ) {
        val texte = lireFixture(fichier)
        val requete =
            RequeteRendu(
                texte = texte,
                dossierRes = dossierRes().absolutePath,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                langue = "fr",
                themeName = theme,
                // Le classpath projet (G1) : SANS entrée, la DexViewFactory
                // dégrade les balises FULLEMENT QUALIFIÉES en MockView —
                // le fil exige les vraies AppBarLayout / MaterialToolbar /
                // FloatingActionButton. Une entrée suffit : le chargeur est
                // parenté au classloader de la plateforme, qui voit les
                // classes Material du classpath de test.
                classpath = listOf(entreeClasspath()),
            )
        val pipeline = RenduLayoutLib(contexte, journal = null)
        val resultat = pipeline.rendre(requete, compresser = false)
        assertTrue(
            "le rendu de $nom a échoué : ${resultat.rendu.message}",
            resultat.rendu.ok,
        )
        val vues = resultat.rendu.vues
        val arbre = ArbreAttributsXml.lire(texte)
        assertEquals(
            "$nom : la collecte ($vues.size vues) et l'arbre ($arbre.size éléments) divergent",
            arbre.size,
            vues.size,
        )
        // La racine du document : référence des coordonnées relatives
        // (l'écran maquette et l'écran rendu portent des chromes différents).
        val racineRendu = vues.first()
        val rectRacine = mesures.getJSONObject(nom).getJSONArray(RACINE)
        val parRid = vues.mapIndexed { indice, vue -> (arbre[indice].id ?: "") to vue }.toMap()
        val reference = ReferenceRacine(racineRendu, rectRacine)
        for ((rid, champsVus) in champs) {
            val vue =
                parRid[rid] ?: error("$nom : la vue @$rid est absente du rendu (${parRid.keys.joinToString()})")
            comparerVue("$nom @$rid", vue, mesures.getJSONObject(nom).getJSONArray(rid), reference, champsVus)
        }
    }

    /** La référence des coordonnées relatives : racine rendue + rect maquette. */
    private class ReferenceRacine(
        val rendu: InfoVueRendu,
        val rect: JSONArray,
    )

    /** Compare les champs déclarés d'UNE vue (libellé des messages), relativement à la racine (±1 dp). */
    private fun comparerVue(
        libelle: String,
        vue: InfoVueRendu,
        rect: JSONArray,
        reference: ReferenceRacine,
        champsVus: List<Champ>,
    ) {
        val rectRacine = reference.rect
        val racineRendu = reference.rendu
        for (champ in champsVus) {
            val (mesure, attendu) =
                when (champ) {
                    Champ.X -> {
                        (vue.gauche - racineRendu.gauche).enDp() to
                            rect.getDouble(0) - rectRacine.getDouble(0)
                    }

                    Champ.Y -> {
                        (vue.haut - racineRendu.haut).enDp() to
                            rect.getDouble(1) - rectRacine.getDouble(1)
                    }

                    Champ.LARGEUR -> {
                        vue.largeurPx.enDp() to rect.getDouble(2)
                    }

                    Champ.HAUTEUR -> {
                        vue.hauteurPx.enDp() to rect.getDouble(3)
                    }

                    Champ.DROITE -> {
                        (vue.droite - racineRendu.gauche).enDp() to
                            rect.getDouble(0) + rect.getDouble(2) - rectRacine.getDouble(0)
                    }
                }
            val ecart = abs(mesure - attendu)
            assertTrue(
                "$libelle ${champ.name.lowercase()} : $mesure dp mesuré, " +
                    "$attendu dp attendu (écart $ecart dp > $TOLERANCE_DP)",
                ecart <= TOLERANCE_DP,
            )
        }
    }

    /** Le texte d'une fixture (copie de test des documents d'exemple). */
    private fun lireFixture(fichier: String): String {
        val flux = chargeur.getResourceAsStream("parents/$fichier")
        if (flux != null) return flux.readBytes().decodeToString()
        return File(racineDepot, "feature/preview/src/test/resources/parents/$fichier").readText()
    }

    /** Le dossier `res/` du projet de mesure : copie des ressources de test. */
    private fun dossierRes(): File {
        val cible = dossier.newFolder("res")
        val source =
            chargeur.getResource("parents/res")?.let { racine ->
                runCatching { File(racine.toURI()) }.getOrNull()
            } ?: File(racineDepot, "feature/preview/src/test/resources/parents/res")
        source.copyRecursively(cible, overwrite = true)
        return cible
    }

    /**
     * L'entrée de classpath de la requête : un jar RÉEL du classpath de
     * test (la première du chemin Java) — son contenu ne compte pas,
     * seule la NON-NULLITÉ du chargeur débloque la factory des balises
     * qualifiées (le parent voit les classes Material).
     */
    private fun entreeClasspath(): EntreeClasspath {
        val jar =
            System
                .getProperty("java.class.path")
                .orEmpty()
                .split(File.pathSeparator)
                .firstOrNull { it.endsWith(".jar") && File(it).isFile }
                ?: error("aucun jar sur le classpath de test")
        return EntreeClasspath(jar, GenreEntreeClasspath.JAR)
    }

    /**
     * Le contexte du rendu : locale FRANÇAISE (les chaînes des fixtures
     * vivent dans les `values/` fr), densité 440 dpi (miroir du profil —
     * les `LayoutParams` convertissent les dp avec la densité du
     * CONTEXTE), habillé du thème Material 3 de l'aperçu (les classes
     * des fixtures s'instancient).
     */
    private fun contexteDeRendu(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = android.content.res.Configuration(base.resources.configuration)
        configuration.setLocale(java.util.Locale.FRENCH)
        configuration.densityDpi = DENSITE_DPI
        return ContextThemeWrapper(
            base.createConfigurationContext(configuration),
            R.style.Theme_CodeIDE_Apercu,
        )
    }

    /** Le chargeur de CE test (les ressources de test y vivent en dossier). */
    private val chargeur: ClassLoader
        get() = requireNotNull(javaClass.classLoader) { "chargeur de classes absent" }

    /** Une coordonnée px du rendu, en dp du profil (440 dpi). */
    private fun Int.enDp(): Double = toDouble() / DENSITE

    /** La racine du dépôt (le module de test peut tourner depuis un sous-dossier). */
    private fun trouverRacineDepot(): File {
        var repertoire: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(PROFONDEUR_RECHERCHE) {
            val courant = repertoire ?: return@repeat
            if (File(courant, CHEMIN_MESURES).isFile) return courant
            repertoire = courant.parentFile
        }
        error("$CHEMIN_MESURES introuvable au-dessus de ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** Chemin du relevé maquette (§ 15.3 : comparer à `meas_*`). */
        private const val CHEMIN_MESURES: String = "docs/mockups/ref/parents/meas_rendu_parents.json"

        /** L'identifiant de la racine dans les fixtures et le relevé. */
        private const val RACINE: String = "root"

        /** Densité du Pixel 4 (440 dpi → 2,75 px/dp). */
        private const val DENSITE: Float = 2.75f

        /** Densité DPI posée au contexte de rendu (miroir du profil). */
        private const val DENSITE_DPI: Int = 440

        /** Tolérance de la recette § 15.3 : bornes à 1 dp près. */
        private const val TOLERANCE_DP: Double = 1.0

        private const val PROFONDEUR_RECHERCHE: Int = 12

        /** Thème des documents À barre d'action (profil, carte — miroir des planches). */
        private const val THEME_AVEC_BARRE: String = "Theme.Mesure"

        /** Thème du fil (planche noBar — l'action bar reste posée, constat C1). */
        private const val THEME_SANS_BARRE: String = "Theme.Mesure.SansBarre"

        // ── Les champs comparés par vue, RELATIFS à la racine. Les ─────
        //    exclusions portent le numéro de leur constat (KDoc).

        /** RelativeLayout : ancrages au PARENT (sans référence) et tailles fixes. */
        private val CHAMPS_PROFIL: Map<String, List<Champ>> =
            mapOf(
                // Racine sous le chrome : 393 × 723 dp (24 + 56 au-dessus, 48 dessous).
                "root" to listOf(Champ.X, Champ.Y, Champ.LARGEUR, Champ.HAUTEUR),
                // alignParentStart/Top + marges 16 : coin (16, 16) de la racine, 80 × 80 fixe.
                "avatar" to listOf(Champ.X, Champ.Y, Champ.LARGEUR, Champ.HAUTEUR),
                // alignParentEnd (droite = 393 − 16) et hauteur fixe 40 ;
                // y suit alignTop (F1), la largeur le texte (police).
                "btn_suivre" to listOf(Champ.DROITE, Champ.HAUTEUR),
                // Hauteur fixe 1 dp ; x et largeur suivent layout_marginHorizontal (F3).
                "separateur" to listOf(Champ.HAUTEUR),
                // alignParentBottom + marge 24 : bas de la racine − 24 − 48, hauteur fixe 48.
                "btn_message" to listOf(Champ.Y, Champ.HAUTEUR),
            )

        /** FrameLayout : gravités natives — la famille intégralement mesurée. */
        private val CHAMPS_CARTE: Map<String, List<Champ>> =
            mapOf(
                "root" to listOf(Champ.X, Champ.Y, Champ.LARGEUR, Champ.HAUTEUR),
                // match_parent × match_parent : fond plein.
                "fond" to listOf(Champ.X, Champ.Y, Champ.LARGEUR, Champ.HAUTEUR),
                // layout_gravity top|end + marges 16 : coin haut-droit fixe.
                "badge" to listOf(Champ.Y, Champ.DROITE),
                // layout_gravity center, 64 × 64 fixe : LES quatre bornes.
                "btn_lecture" to listOf(Champ.X, Champ.Y, Champ.LARGEUR, Champ.HAUTEUR),
                // layout_gravity bottom : pleine largeur ; hauteur = deux
                // lignes de texte (police).
                "legende" to listOf(Champ.X, Champ.LARGEUR),
            )

        /** CoordinatorLayout + AppBarLayout : la barre empilée, la racine. */
        private val CHAMPS_FIL: Map<String, List<Champ>> =
            mapOf(
                // La HAUTEUR n'est pas comparée : l'action bar du chrome
                // reste posée (C1) — 723 dp mesurés contre 779 attendus.
                "root" to listOf(Champ.X, Champ.Y, Champ.LARGEUR),
                // wrap_content vertical : toolbar 56 + filtres 48 = 104.
                "appbar" to listOf(Champ.X, Champ.Y, Champ.LARGEUR, Champ.HAUTEUR),
                // 56 dp fixe, premier enfant de la barre.
                "toolbar" to listOf(Champ.X, Champ.Y, Champ.LARGEUR, Champ.HAUTEUR),
                // 48 dp fixe, second enfant.
                "filtres" to listOf(Champ.X, Champ.Y, Champ.LARGEUR, Champ.HAUTEUR),
                // x suit le paddingHorizontal de la barre (F3).
                "f_tout" to listOf(),
                // match_parent : x et largeur ; le y suit le comportement
                // non résolu (F2).
                "liste" to listOf(Champ.X, Champ.LARGEUR),
                // 56 × 56 fixe ; la position suit l'ancre inerte (F1).
                "fab_nouveau" to listOf(Champ.LARGEUR, Champ.HAUTEUR),
            )
    }
}
