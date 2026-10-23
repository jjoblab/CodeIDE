package jo.codeide.preview.xml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Rendu d'un fichier `res/values*.xml` en bitmap-tableau (PE3, PREVIEW.md
 * §3.4 « drawables/values previewables ») : une LIGNE par entrée — nuancier
 * pour les couleurs, nom et valeur pour le reste. Le fichier values n'a pas
 * de mise en page à prévisualiser : la preview honnête est l'INVENTAIRE
 * lisible de ses entrées (même esprit que l'onglet « Resource Manager »
 * d'Android Studio, aplati).
 *
 * Jour/nuit : le fond et les textes basculent (le régime est porté par la
 * [RequeteRendu]).
 *
 * Limites documentées (honnêtes, jamais de rendu inventé) : les `<style>`
 * s'affichent par leur nom et parent (pas leurs items) ; au-delà de
 * [LIMITE_ENTREES] entrées, la liste est tronquée avec un pied explicite.
 *
 * Exemption detekt ciblée (règle 16) : LongParameterList — [dessiner]
 * porte les paramètres de STYLE du tableau (fond, textes, tailles), le
 * regroupement en classe de style ajouterait une indirection sans
 * réduire la donnée ; TooManyFunctions — le rendu du tableau est une
 * suite d'étapes NOMMÉES (en-tête, lignes, nuancier, pied — chacune une
 * fonction), l'éclater en classes disperserait la palette.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class RenduValeursXml(
    /** Densité de l'écran (dp → px). */
    private val densite: Float,
) {
    /** Une entrée de ressource extraite du XML. */
    internal class Entree(val genre: String, val nom: String, val valeur: String) {
        /** La valeur est-elle une couleur hexadécimale (#RRGGBB…) ? */
        internal val estCouleur: Boolean =
            valeur.length in LONGUEUR_COULEUR_MIN..LONGUEUR_COULEUR_MAX &&
                valeur.startsWith(PREFIXE_COULEUR) &&
                valeur.substring(1).all { it in HEXADECIMAL }

        private companion object {
            private const val HEXADECIMAL = "0123456789abcdefABCDEF"
            private const val LONGUEUR_COULEUR_MIN = 4
            private const val LONGUEUR_COULEUR_MAX = 9
            private const val PREFIXE_COULEUR = "#"
        }
    }

    /**
     * Extrait les entrées du XML (racine `<resources>`) — espaces de nom
     * ignorés, `name` en attribut, contenu texte réduit à une ligne.
     * Renvoie null si le document n'est pas une racine `resources`
     * (classification erronée : l'appelant affichera une erreur honnête).
     */
    internal fun extraire(texte: String): List<Entree>? = try {
        LecteurValeurs(texte).lire()
    } catch (_: IllegalArgumentException) {
        // Parseur inconstructible : document illisible → erreur honnête.
        null
    }

    /**
     * Dessine le tableau des entrées : bitmap de la largeur du profil, une
     * ligne par entrée, nuancier pour les couleurs.
     */
    internal fun dessiner(entrees: List<Entree>, largeurPx: Int, nuit: Boolean): Bitmap {
        val palette = if (nuit) Palette.NUITS else Palette.JOURS
        val lignes = (affichees(entrees).size + 1 + if (estTronque(entrees)) 1 else 0)
        val hauteur = lignes * hauteurLigne() + 2 * marge()
        val bitmap =
            Bitmap.createBitmap(
                largeurPx.coerceAtLeast(1),
                hauteur.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val canevas = Canvas(bitmap)
        canevas.drawColor(palette.fond)
        dessinerEntete(canevas, entrees, palette)
        dessinerEntrees(canevas, entrees, palette, largeurPx)
        dessinerPiedSiTronque(canevas, entrees, palette)
        return bitmap
    }

    /** En-tête : nombre d'entrées (et mention de troncature). */
    private fun dessinerEntete(canevas: Canvas, entrees: List<Entree>, palette: Palette) {
        val y = marge() + hauteurLigne() - (hauteurLigne() / QUART)
        val libelle =
            "${affichees(entrees).size} entrée(s)${if (estTronque(entrees)) " (tronqué)" else ""}"
        canevas.drawText(libelle, marge().toFloat(), y.toFloat(), peintValeur(palette))
    }

    /** Les lignes des entrées (nuancier, nom, valeur). */
    private fun dessinerEntrees(canevas: Canvas, entrees: List<Entree>, palette: Palette, largeurPx: Int) {
        var y = marge() + 2 * hauteurLigne() - (hauteurLigne() / QUART)
        for (entree in affichees(entrees)) {
            dessinerEntree(canevas, entree, palette, largeurPx, y)
            y += hauteurLigne()
        }
    }

    /** UNE ligne : nuancier + nom (tiers gauche), valeur (tiers droit). */
    private fun dessinerEntree(canevas: Canvas, entree: Entree, palette: Palette, largeurPx: Int, y: Int) {
        var x = marge().toFloat()
        if (entree.estCouleur) {
            dessinerNuancier(canevas, entree, palette, x, y)
            x += coteNuancier() + marge()
        }
        val libelle = "[${entree.genre}] ${entree.nom}"
        val nom = tronquerTexte(peintNom(palette), libelle, largeurPx * FRACTION_LARGEUR_NOM - x)
        canevas.drawText(nom, x, y.toFloat(), peintNom(palette))
        if (entree.valeur.isNotEmpty() && !entree.estCouleur) {
            val valeur =
                tronquerTexte(
                    peintValeur(palette),
                    entree.valeur,
                    largeurPx * (1f - FRACTION_LARGEUR_NOM) - 2 * marge(),
                )
            canevas.drawText(valeur, largeurPx * FRACTION_LARGEUR_NOM, y.toFloat(), peintValeur(palette))
        }
    }

    /** Nuancier carré + liseré (couleur illisible → magenta honnête). */
    private fun dessinerNuancier(canevas: Canvas, entree: Entree, palette: Palette, x: Float, y: Int) {
        val couleur =
            try {
                Color.parseColor(entree.valeur)
            } catch (_: IllegalArgumentException) {
                Color.MAGENTA
            }
        val peinture = Paint().apply { style = Paint.Style.FILL }
        peinture.color = couleur
        canevas.drawRect(x, (y - coteNuancier()).toFloat(), (x + coteNuancier()), y.toFloat(), peinture)
        val lisiere =
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = palette.texteSecondaire
            }
        canevas.drawRect(x, (y - coteNuancier()).toFloat(), (x + coteNuancier()), y.toFloat(), lisiere)
    }

    /** Pied « … +N entrée(s) » quand la liste est tronquée (jamais silencieux). */
    private fun dessinerPiedSiTronque(canevas: Canvas, entrees: List<Entree>, palette: Palette) {
        if (!estTronque(entrees)) return
        val y = marge() + (affichees(entrees).size + 2) * hauteurLigne() - (hauteurLigne() / QUART)
        canevas.drawText(
            "… +${entrees.size - LIMITE_ENTREES} entrée(s)",
            marge().toFloat(),
            y.toFloat(),
            peintValeur(palette),
        )
    }

    private fun affichees(entrees: List<Entree>): List<Entree> = entrees.take(LIMITE_ENTREES)

    private fun estTronque(entrees: List<Entree>): Boolean = entrees.size > LIMITE_ENTREES

    private fun hauteurLigne(): Int = (HAUTEUR_LIGNE_DP * densite).toInt().coerceAtLeast(1)

    private fun marge(): Int = (MARGE_DP * densite).toInt().coerceAtLeast(1)

    private fun coteNuancier(): Int = (COTE_NUANCIER_DP * densite).toInt().coerceAtLeast(2)

    private fun peintNom(palette: Palette): Paint = Paint().apply {
        color = palette.texte
        isAntiAlias = true
        textSize = TAILLE_NOM_DP * densite
        isFakeBoldText = true
    }

    private fun peintValeur(palette: Palette): Paint = Paint().apply {
        color = palette.texteSecondaire
        isAntiAlias = true
        textSize = TAILLE_VALEUR_DP * densite
    }

    /** Tronque avec ellipse si le texte dépasse [largeurMax] (px). */
    private fun tronquerTexte(peinture: Paint, texte: String, largeurMax: Float): String {
        if (largeurMax <= 0f || peinture.measureText(texte) <= largeurMax) return texte
        var coupure = texte.length
        while (coupure > 1 && peinture.measureText(texte.substring(0, coupure) + "…") > largeurMax) {
            coupure -= 1
        }
        return texte.substring(0, coupure) + "…"
    }

    /** Palette du tableau selon le régime jour/nuit. */
    private class Palette(val fond: Int, val texte: Int, val texteSecondaire: Int) {
        companion object {
            val JOURS = Palette(0xFFFBFBFE.toInt(), 0xFF1D1B20.toInt(), 0xFF49454F.toInt())
            val NUITS = Palette(0xFF1B1B1F.toInt(), 0xFFE6E1E5.toInt(), 0xFFCAC4D0.toInt())
        }
    }

    /**
     * Lecteur d'un document values : machine à états MINIMALE (racine →
     * entrées → texte), séparée de l'extraction pour rester lisible.
     * Retour null : document non `resources` (erreur de classification).
     */
    private class LecteurValeurs(texte: String) {
        private val parseur: XmlPullParser =
            try {
                val fabrique = XmlPullParserFactory.newInstance()
                fabrique.isNamespaceAware = true
                val p = fabrique.newPullParser()
                p.setInput(StringReader(texte))
                p
            } catch (_: Exception) {
                throw IllegalArgumentException("parseur values illisible")
            }

        /**
         * Lit le document ; null si la racine n'est pas `<resources>`.
         * Exemption detekt ciblée (règle 16) : ReturnCount — racine
         * invalide, malformation et succès sont les TROIS issues du
         * contrat (même patron que LireSyncStateUseCase).
         */
        @Suppress("ReturnCount")
        fun lire(): List<Entree>? {
            val entrees = mutableListOf<Entree>()
            try {
                if (!avancerALaRacine()) return null
                lireEntrees(entrees)
            } catch (_: Exception) {
                // Malformation couverte par la validation amont : defense.
                return null
            }
            return entrees
        }

        /** Avance à la racine ; false si elle n'est pas `resources`. */
        private fun avancerALaRacine(): Boolean {
            var evenement = parseur.eventType
            while (evenement != XmlPullParser.START_TAG && evenement != XmlPullParser.END_DOCUMENT) {
                evenement = parseur.next()
            }
            return evenement == XmlPullParser.START_TAG && parseur.name == "resources"
        }

        /** Boucle des enfants de la racine : une [Entree] par balise. */
        private fun lireEntrees(entrees: MutableList<Entree>) {
            var genre: String? = null
            var nom: String? = null
            val texte = StringBuilder()
            var evenement = parseur.next()
            while (evenement != XmlPullParser.END_DOCUMENT) {
                when (evenement) {
                    XmlPullParser.START_TAG -> {
                        if (genre == null) {
                            genre = parseur.name
                            nom = parseur.getAttributeValue(null, "name")
                        }
                    }

                    XmlPullParser.TEXT -> {
                        texte.append(parseur.text)
                    }

                    XmlPullParser.END_TAG -> {
                        if (genre != null && parseur.name == genre) {
                            entrees.add(Entree(genre, nom ?: "(sans nom)", texte.toString().trim().replace('\n', ' ')))
                            genre = null
                            nom = null
                            texte.setLength(0)
                        }
                    }
                }
                evenement = parseur.next()
            }
        }
    }

    private companion object {
        private const val HAUTEUR_LIGNE_DP = 30f
        private const val MARGE_DP = 10f
        private const val TAILLE_NOM_DP = 13f
        private const val TAILLE_VALEUR_DP = 12f
        private const val COTE_NUANCIER_DP = 16f
        private const val FRACTION_LARGEUR_NOM = 0.55f
        private const val QUART = 4

        /** Entrées au-delà : liste tronquée (pied explicite, jamais silencieux). */
        private const val LIMITE_ENTREES = 96
    }
}
