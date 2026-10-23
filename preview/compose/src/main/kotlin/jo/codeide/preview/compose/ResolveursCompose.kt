package jo.codeide.preview.compose

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import jo.layoutlib.resources.DimensionConverter
import jo.layoutlib.resources.ResourceResolverImpl
import jo.layoutlib.themes.ThemeResolverImpl
import java.io.File

/**
 * C2 — Les résolveurs du PROJET au service de la composition
 * (PREVIEW.md §4.4 : « ressources et thème du projet dans la
 * composition distante (réutilise les résolveurs PE1) »).
 *
 * **RÉUTILISATION, pas réinvention** : ce sont les MÊMES classes que le
 * moteur XML du MÊME processus `:preview` — [ResourceResolverImpl]
 * (qualifiers `values-night/`/`values-<lang>/`, chaînage des
 * références) et [ThemeResolverImpl] (chaîne d'héritage des styles) —
 * « une seule machinerie, deux dialectes » (étude PE14 §5.3-1). La
 * composition ne PARSE RIEN elle-même : elle CONSULTE.
 *
 * CACHE : un scan disque (res/) par CHANGEMENT de combinaison
 * (dossier, nuit, langue, thème, densité) — pas par rendu ; les
 * bascules jour/nuit fréquentes de la carte ne re-scannent rien tant
 * que la combinaison vit dans le cache (miroir du moteur XML qui
 * garde ses résolveurs entre les rendus d'une même session).
 *
 * Le [dossierRes] absent/invalide rend `null` — la composition
 * retombe sur les DÉFAUTS DU RUNTIME (comportement C1 exact, jamais
 * d'échec de rendu pour un projet sans res/).
 *
 * @param contexte contexte du processus `:preview` (xdpi de la
 *        conversion des dimensions).
 */
internal class ResolveursCompose(private val contexte: Context) {
    /** Résolveurs vivants — clé = combinaison complète (LRU borné). */
    private val vivants = LinkedHashMap<Cle, ResolveurCompose>()

    /**
     * Les résolveurs d'UNE combinaison — `null` si le dossier est
     * absent/invalide (repli honnête C1). La DENSITÉ vit dans la CLÉ du
     * cache (elle ne participe PAS à la construction — le convertisseur
     * est posé à 160 dpi : les dimensions sortent en DP, la densité
     * du profil s'applique à la composition, pas à la lecture).
     */
    internal fun pour(
        dossierRes: String?,
        nuit: Boolean,
        langue: String?,
        themeName: String?,
        densiteDpi: Int,
    ): ResolveurCompose? {
        val dossier = dossierRes ?: return null
        val cle = Cle(dossier, nuit, langue, themeName, densiteDpi)
        return synchronized(vivants) {
            vivants.remove(cle)?.let { present ->
                vivants[cle] = present
                present
            } ?: construire(dossier, nuit, langue, themeName)?.let { neuf ->
                vivants[cle] = neuf
                while (vivants.size > TAILLE_CACHE) {
                    vivants.remove(vivants.keys.first())
                }
                neuf
            }
        }
    }

    /**
     * Construit les résolveurs — MIROIR EXACT du branchement du moteur
     * XML (`RenduLayoutLib` : convertisseur de dimensions, night/langue,
     * enregistrement themes.xml jour/nuit + styles.xml, thème
     * d'activité strippé de ses préfixes). Échec de scan → `null`
     * (repli natif documenté, jamais d'exception au rendu).
     */
    private fun construire(dossierRes: String, nuit: Boolean, langue: String?, themeName: String?): ResolveurCompose? {
        return try {
            val dossier = File(dossierRes)
            if (!dossier.isDirectory) return null
            val xdpi = contexte.resources.displayMetrics.xdpi
            // Densité ×1 (160/160) : getDimension rend des PX qui VALENT
            // des dp — la valeur du résolveur est DÉJÀ ce que Compose
            // attend en dp (la densité du PROFIL s'applique à la
            // composition, jamais à la lecture).
            val convertisseur = DimensionConverter(DENSITE_ECHELLE, DENSITE_ECHELLE, xdpi)
            val ressources = ResourceResolverImpl(dossierRes)
            ressources.setDimensionConverter(convertisseur)
            ressources.setNightMode(nuit)
            if (langue != null) {
                ressources.setLanguage(langue)
            }
            val theme = ThemeResolverImpl(ressources, convertisseur)
            enregistrerThemes(theme, dossierRes)
            theme.setNightMode(nuit)
            if (themeName != null) {
                theme.setTheme(themeName.removePrefix("@style/").removePrefix("@android:style/"))
            }
            ResolveurCompose(ressources, theme)
        } catch (_: Throwable) {
            null
        }
    }

    /** Enregistre themes.xml (jour/nuit) et styles.xml du projet — miroir `enregistrerThemes` du moteur XML. */
    private fun enregistrerThemes(resolver: ThemeResolverImpl, cheminRes: String) {
        File(cheminRes, "values/themes.xml").takeIf(File::isFile)?.let { fichier ->
            fichier.readText().let { xml -> resolver.registerThemesFile(xml, false) }
        }
        File(cheminRes, "values-night/themes.xml").takeIf(File::isFile)?.let { fichier ->
            fichier.readText().let { xml -> resolver.registerThemesFile(xml, true) }
        }
        File(cheminRes, "values/styles.xml").takeIf(File::isFile)?.let { fichier ->
            fichier.readText().let { xml -> resolver.registerThemesFile(xml, false) }
        }
    }

    /** Une combinaison de paramètres de résolution (clé du cache). */
    private data class Cle(
        val dossier: String,
        val nuit: Boolean,
        val langue: String?,
        val theme: String?,
        val densite: Int,
    )

    private companion object {
        /**
         * Taille du cache : les deux régimes jour/nuit des deux derniers
         * (dossier, langue, thème, densité) — les bascules de la carte
         * ne re-scannent JAMAIS le dossier entre deux rendus.
         */
        internal const val TAILLE_CACHE: Int = 4

        /** Échelle de densité du convertisseur : 160/160 = ×1 (les dp sortent nus). */
        internal const val DENSITE_ECHELLE: Float = 1f
    }
}

/**
 * Les résolveurs D'UNE combinaison (dossier + nuit + langue + thème
 * posés à la construction) — les consultations du rendu : chaîne,
 * couleur, dimension dp, et les SLOTS du thème Material par leur
 * attribut XML (l'unique table [SlotsThemeCompose] fait la traduction).
 *
 * Pur lecture : thread-safe par construction une fois construit (le
 * résolveur LayoutLib cache ses valeurs en interne — même contrat que
 * le moteur XML, consulté sur le fil de composition sérialisé).
 */
internal class ResolveurCompose(private val ressources: ResourceResolverImpl, private val theme: ThemeResolverImpl) {
    /** `R.string.NOM` → la chaîne du projet (`null` si absente). */
    internal fun chaine(nom: String): String? = ressources.getString("@string/$nom")

    /** `R.color.NOM` → la couleur ARGB du projet (`null` si absente). */
    internal fun couleur(nom: String): Int? = ressources.getColor("@color/$nom")

    /** `R.dimen.NOM` → la valeur en DP du projet (`null` si absente) — convertisseur 160 dpi. */
    internal fun dimensionDp(nom: String): Float? = ressources.getDimension("@dimen/$nom")

    /**
     * `MaterialTheme.colorScheme.SLOT` → la couleur du THÈME du projet
     * (`null` si l'attribut/le slot sont absents) : la référence de
     * l'utilisateur désigne sémantiquement la couleur du THÈME DE SON
     * PROJET — exactement ce que le schéma de la composition applique
     * par [SlotsThemeCompose.ATTR_PAR_SLOT].
     */
    internal fun couleurDuSlot(slot: String): Int? =
        SlotsThemeCompose.ATTR_PAR_SLOT[slot]?.let { attr -> theme.getColorAttr(attr) }

    /**
     * Le schéma Material de la composition (C2) : les défauts du
     * RUNTIME (clair/sombre selon [nuit]) OÙ CHAQUE slot résolu du
     * thème projet REMPLACE son défaut — miroir S7 du frère XML (« le
     * thème prévisualisé devient le thème des vues »). Thème absent →
     * les couleurs/material par défaut du runtime se rendent
     * (comportement C1 exact).
     */
    internal fun schema(nuit: Boolean): ColorScheme {
        var schema = if (nuit) darkColorScheme() else lightColorScheme()
        SlotsThemeCompose.ATTR_PAR_SLOT.forEach { (slot, attr) ->
            theme.getColorAttr(attr)?.let { argb ->
                SlotsThemeCompose.poseurDe(slot)?.let { poseur -> schema = poseur(schema, Color(argb)) }
            }
        }
        return schema
    }
}

/**
 * L'unique table de traduction slot Material 3 ↔ attribut de thème
 * XML — les VINGT slots principaux du registre C2 (les slots exotiques
 * surfaceBright/Dim/Container* restent aux défauts du runtime, plage
 * documentée à l'ADR 0141). Consommée par :
 *  - le [ConvertisseurProgramme] (validation des slots cités — un slot
 *    hors table est NON interprété honnête) ;
 *  - le [ResolveurCompose] (résolution d'UN slot cité, et le schéma
 *    COMPLET de la composition).
 */
internal object SlotsThemeCompose {
    /** slot Material 3 → attribut de thème XML. */
    internal val ATTR_PAR_SLOT: Map<String, String> =
        linkedMapOf(
            "primary" to "colorPrimary",
            "onPrimary" to "colorOnPrimary",
            "primaryContainer" to "colorPrimaryContainer",
            "onPrimaryContainer" to "colorOnPrimaryContainer",
            "secondary" to "colorSecondary",
            "onSecondary" to "colorOnSecondary",
            "secondaryContainer" to "colorSecondaryContainer",
            "onSecondaryContainer" to "colorOnSecondaryContainer",
            "tertiary" to "colorTertiary",
            "onTertiary" to "colorOnTertiary",
            "background" to "android:colorBackground",
            "onBackground" to "colorOnBackground",
            "surface" to "colorSurface",
            "onSurface" to "colorOnSurface",
            "surfaceVariant" to "colorSurfaceVariant",
            "onSurfaceVariant" to "colorOnSurfaceVariant",
            "error" to "colorError",
            "onError" to "colorOnError",
            "errorContainer" to "colorErrorContainer",
            "onErrorContainer" to "colorOnErrorContainer",
            "outline" to "colorOutline",
        )

    /** Le poseur d'un slot (`null` = slot hors table). */
    internal fun poseurDe(slot: String): ((ColorScheme, Color) -> ColorScheme)? = POSEURS[slot]

    /** Les poseurs par slot — [ColorScheme.copy] à un argument nommé. */
    private val POSEURS: Map<String, (ColorScheme, Color) -> ColorScheme> =
        mapOf(
            "primary" to { schema, c -> schema.copy(primary = c) },
            "onPrimary" to { schema, c -> schema.copy(onPrimary = c) },
            "primaryContainer" to { schema, c -> schema.copy(primaryContainer = c) },
            "onPrimaryContainer" to { schema, c -> schema.copy(onPrimaryContainer = c) },
            "secondary" to { schema, c -> schema.copy(secondary = c) },
            "onSecondary" to { schema, c -> schema.copy(onSecondary = c) },
            "secondaryContainer" to { schema, c -> schema.copy(secondaryContainer = c) },
            "onSecondaryContainer" to { schema, c -> schema.copy(onSecondaryContainer = c) },
            "tertiary" to { schema, c -> schema.copy(tertiary = c) },
            "onTertiary" to { schema, c -> schema.copy(onTertiary = c) },
            "background" to { schema, c -> schema.copy(background = c) },
            "onBackground" to { schema, c -> schema.copy(onBackground = c) },
            "surface" to { schema, c -> schema.copy(surface = c) },
            "onSurface" to { schema, c -> schema.copy(onSurface = c) },
            "surfaceVariant" to { schema, c -> schema.copy(surfaceVariant = c) },
            "onSurfaceVariant" to { schema, c -> schema.copy(onSurfaceVariant = c) },
            "error" to { schema, c -> schema.copy(error = c) },
            "onError" to { schema, c -> schema.copy(onError = c) },
            "errorContainer" to { schema, c -> schema.copy(errorContainer = c) },
            "onErrorContainer" to { schema, c -> schema.copy(onErrorContainer = c) },
            "outline" to { schema, c -> schema.copy(outline = c) },
        )
}
