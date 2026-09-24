package jo.codeide.feature.terminal

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import androidx.appcompat.widget.LinearLayoutCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import jo.codeide.core.ui.R as RUi
import jo.codeide.feature.terminal.R as RTerminal

/**
 * Rangée de touches étendues du terminal (prompt Terminal-1, section 5 :
 * Tab, Ctrl, Alt, flèches, Échap « au minimum »).
 *
 * **Implémentation interne** : `ExtraKeysView` de `termux-shared` est
 * refusé pour licence (exceptions MIT ne couvrant pas
 * `terminal/io/extrakeys` — ADR 0035/0036). La rangée est **déclarative**
 * (liste [TOUCHES] de définitions, boutons Material simples), pas une
 * réimplémentation du moteur de rendu de touches de Termux.
 *
 * Deux familles :
 * - [Touche.Directe] — écrit une séquence (`"\t"`, `"\u001b"`,
 *   `"\u001b[A"`…) dans la session active, via [ecouteur] ;
 * - [Touche.Bascule] — modificateur **persistant** (Ctrl, Alt) lu par le
 *   client de la vue à chaque frappe (`readControlKey`/`readAltKey`) :
 *   c'est le mécanisme même de Termux, appliqué par `TerminalView` à
 *   l'entrée du clavier.
 */
class ClavierEtenduView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayoutCompat(context, attrs) {
        /** Récepteur des touches directes (l'activité écrit en session). */
        var ecouteur: EcouteurClavier? = null

        /** Modificateur Ctrl actif (lu par le client de la vue). */
        var ctrlActif: Boolean = false
            private set

        /** Modificateur Alt actif (lu par le client de la vue). */
        var altActif: Boolean = false
            private set

        /**
         * La rangée déclarative (section 5 : « au minimum »).
         *
         * ⚠️ **Doit précéder le bloc `init`** : Kotlin exécute les
         * initialisateurs et blocs `init` dans l'ordre de déclaration, et
         * ce bloc appelle [construire] qui itère cette liste. Déclarée
         * après lui, elle vaut encore `null` pendant la construction —
         * plantage déterministe à l'inflation du layout (rapport
         * d'appareil réel 7842f130, v0.29.0 : l'écran Terminal plantait
         * à chaque ouverture, `NullPointerException` sur
         * `List.iterator()` dans `construire`).
         */
        private val touches: List<Touche> =
            listOf(
                Touche.Directe("Tab", "\t"),
                Touche.Bascule("Ctrl", Modificateur.CTRL),
                Touche.Bascule("Alt", Modificateur.ALT),
                Touche.Directe("Échap", "\u001b"),
                Touche.Directe("←", "\u001b[D"),
                Touche.Directe("↑", "\u001b[A"),
                Touche.Directe("↓", "\u001b[B"),
                Touche.Directe("→", "\u001b[C"),
            )

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            construire()
        }

        /** Une touche directe est pressée : séquence à écrire. */
        fun interface EcouteurClavier {
            fun surSequence(sequence: String)
        }

        /** Définition déclarative d'une touche de la rangée. */
        private sealed interface Touche {
            /** Libellé affiché. */
            val libelle: String

            /** Touche qui écrit directement une séquence de contrôle. */
            data class Directe(
                override val libelle: String,
                val sequence: String,
            ) : Touche

            /** Modificateur persistant (Ctrl, Alt). */
            data class Bascule(
                override val libelle: String,
                val modificateur: Modificateur,
            ) : Touche
        }

        /** Modificateurs persistants gérés. */
        private enum class Modificateur {
            CTRL,
            ALT,
        }

        private fun construire() {
            for (touche in touches) {
                val bouton = creerBouton(touche.libelle)
                when (touche) {
                    is Touche.Directe -> {
                        bouton.setOnClickListener {
                            ecouteur?.surSequence(touche.sequence)
                        }
                    }

                    is Touche.Bascule -> {
                        bouton.setOnClickListener {
                            basculer(touche.modificateur, bouton)
                        }
                    }
                }
                addView(bouton)
            }
        }

        /** Bascule un modificateur : état + couleur du libellé. */
        private fun basculer(
            modificateur: Modificateur,
            bouton: MaterialButton,
        ) {
            when (modificateur) {
                Modificateur.CTRL -> ctrlActif = !ctrlActif
                Modificateur.ALT -> altActif = !altActif
            }
            appliquerCouleur(bouton, modificateurActif(modificateur))
        }

        private fun modificateurActif(modificateur: Modificateur): Boolean =
            when (modificateur) {
                Modificateur.CTRL -> ctrlActif
                Modificateur.ALT -> altActif
            }

        /** Couleur du libellé selon l'état du modificateur. */
        private fun appliquerCouleur(
            bouton: MaterialButton,
            actif: Boolean,
        ) {
            // colorPrimary vit dans le R d'appcompat (le thème Material 3
            // l'y résout) ; colorOnSurface est propre à la bibliothèque.
            val attribut =
                if (actif) {
                    androidx.appcompat.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                }
            bouton.setTextColor(MaterialColors.getColor(bouton, attribut))
        }

        /** Bouton Material compact, gonflé depuis sa déclaration XML. */
        private fun creerBouton(libelle: String): MaterialButton {
            val bouton =
                LayoutInflater
                    .from(context)
                    .inflate(RTerminal.layout.touche_clavier, this, false) as MaterialButton
            bouton.text = libelle
            bouton.isAllCaps = false
            bouton.setTextSize(TypedValue.COMPLEX_UNIT_SP, TAILLE_TEXTE_SP)
            appliquerCouleur(bouton, false)
            return bouton
        }

        private companion object {
            /** Taille du libellé des touches (sp). */
            const val TAILLE_TEXTE_SP = 13f
        }
    }
