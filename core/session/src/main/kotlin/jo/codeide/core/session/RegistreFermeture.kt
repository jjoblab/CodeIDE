package jo.codeide.core.session

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Registre des ressources d'une session de projet (prompt 3 §5.1, étape B ;
 * ADR 0114).
 *
 * Chaque ressource s'enregistre **au moment où elle démarre** ; l'ordre
 * d'enregistrement EST l'ordre de démarrage observé, et la fermeture
 * l'exécute dans l'**ordre inverse** (ce qui a démarré en dernier meurt en
 * premier). Les paramètres de fermeture (délai maximal, drapeau « ne pas
 * annuler ») vivent ICI, pas dans la ressource : c'est l'intégrateur qui
 * sait quel délai est acceptable pour chaque ressource.
 *
 * Garanties de [fermerTout] (I2) :
 *
 * - **idempotente** : une seconde fermeture referme un registre déjà vidé —
 *   les ressources ne sont JAMAIS libérées deux fois ;
 * - **bornée** : chaque élément dispose de son propre délai ; le dépassement
 *   abandonne l'élément (verdict `DelaiDepasse`, journal WARN) sans jamais
 *   bloquer les suivants ;
 * - **tolérante aux pannes partielles** : une exception levée par un
 *   élément est journalisée (verdict `Echec`) et la fermeture CONTINUE ;
 * - **fidèle à I7** : les éléments enregistrés `annulable = false`
 *   s'exécutent dans un contexte `NonCancellable` — même si l'appelant
 *   annule la fermeture (c'est le drapeau du flush des sauvegardes,
 *   étape C) ;
 * - **annulation relancée** : une fermeture interrompue par l'annulation de
 *   l'appelant se termine par [FermetureInterrompue] (rapport partiel
 *   embarqué) — jamais avalée (règle projet).
 *
 * Ne JAMAIS rappeler [fermerTout] depuis le `fermer()` d'un élément de CE
 * registre (interblocage : le verrou de fermeture n'est pas réentrant) —
 * [SessionProjet.fermer] détecte cette réentrance et la refuse ; le registre
 * seul est un outil de bas niveau.
 *
 * @param journal port optionnel de journalisation (seule voie autorisée,
 * règle 14) — `null` dans les tests unitaires purs.
 * @param horloge horloge injectable des durées du rapport (pilotable en
 * test, section 12.5 du prompt maître).
 */
public class RegistreFermeture(
    private val journal: AppLogger? = null,
    private val horloge: TimeProvider = TimeProvider { System.currentTimeMillis() },
) {
    /** Élément enregistré — la ressource et ses paramètres de fermeture. */
    private class ElementEnregistre(
        val fermable: Fermable,
        val delai: Duration,
        val annulable: Boolean,
    )

    /** Bilan d'une libération : verdict + interruption éventuelle de l'appelant. */
    private class Liberation(
        val bilan: RapportElementFermeture,
        val interruption: CancellationException?,
    )

    private val verrouListe = ReentrantLock()
    private val elements = mutableListOf<ElementEnregistre>()
    private val fermetureEnCours = AtomicBoolean(false)
    private val verrouFermeture = Mutex()

    /**
     * Abonne une ressource au registre.
     *
     * Accepté uniquement HORS fermeture en cours : une ressource qui
     * démarre pendant la fermeture de la session est REFUSÉE (elle devra
     * être fermée par son propre propriétaire — jamais orpheline du
     * registre, jamais fermée par une fermeture qui l'ignore).
     *
     * @param fermable la ressource qui démarre.
     * @param delai borne maximale de SA libération (strictement positif).
     * @param annulable `false` = « ne pas annuler » : libérée même si
     * l'appelant annule la fermeture (I7 — flush des sauvegardes).
     * @return la poignée de désenregistrement, ou l'échec typé si la
     * fermeture est en cours.
     */
    public fun enregistrer(
        fermable: Fermable,
        delai: Duration,
        annulable: Boolean = true,
    ): AppResult<Desenregistrement> {
        require(delai.isPositive()) { "Le délai de fermeture d'une ressource doit être strictement positif." }
        val accepte =
            sousVerrouListe {
                if (fermetureEnCours.get()) {
                    false
                } else {
                    elements += ElementEnregistre(fermable, delai, annulable)
                    true
                }
            }
        if (!accepte) {
            journal?.w(TAG) { "enregistrement de ${fermable.nom} refusé : fermeture en cours" }
            return AppResult.Failure(
                AppError.Session(
                    reason = AppError.SessionReason.FermetureEnCours,
                    details = "la fermeture du registre est en cours (${fermable.nom})",
                ),
            )
        }
        return AppResult.Success(DesenregistrementInterne(fermable))
    }

    /**
     * Ferme TOUTES les ressources enregistrées — ordre inverse du
     * démarrage, chaque élément sous son délai, pannes tolérées (I2).
     *
     * Appelable depuis n'importe quelle coroutine ; une seconde
     * fermeture concurrente attend la première puis referme un registre
     * vide (idempotence). Si l'appelant annule en cours de route, les
     * éléments non annulables restants sont quand même libérés (I7) puis
     * [FermetureInterrompue] est relancée.
     *
     * @param contexte raison de la fermeture, remise à chaque élément.
     * @return le rapport complet (durée par élément) — sauf interruption.
     */
    public suspend fun fermerTout(contexte: ContexteFermeture): RapportFermeture =
        verrouFermeture.withLock {
            val aFermer: List<ElementEnregistre> =
                sousVerrouListe {
                    fermetureEnCours.set(true)
                    val instantane = elements.toList()
                    elements.clear()
                    instantane
                }
            val debut = horloge.nowMillis()
            val bilans = mutableListOf<RapportElementFermeture>()
            var interruption: CancellationException? = null
            // Le drapeau « fermeture en cours » est TOUJOURS rendu, même sur
            // une erreur inattendue (horloge cassée) : un registre bloqué en
            // « fermeture » refuserait tout enregistrement à jamais.
            val rapport =
                try {
                    for (element in aFermer.asReversed()) {
                        if (interruption != null && element.annulable) {
                            // appelant annulé : seuls les non-annulables continuent (I7)
                            bilans +=
                                RapportElementFermeture(
                                    nom = element.fermable.nom,
                                    duree = Duration.ZERO,
                                    resultat = ResultatFermetureElement.Annule,
                                )
                            continue
                        }
                        val liberation = libererElement(element, contexte)
                        bilans += liberation.bilan
                        if (liberation.interruption != null) {
                            interruption = liberation.interruption
                        }
                    }
                    RapportFermeture(
                        raison = contexte.raison,
                        dureeTotale = (horloge.nowMillis() - debut).toDuration(DurationUnit.MILLISECONDS),
                        elements = bilans,
                    )
                } finally {
                    sousVerrouListe { fermetureEnCours.set(false) }
                }
            journaliserRapport(rapport)
            if (interruption != null) {
                throw FermetureInterrompue(rapport)
            }
            rapport
        }

    /** Libère UN élément sous son délai propre, en tolérant pannes et annulation. */
    private suspend fun libererElement(
        element: ElementEnregistre,
        contexte: ContexteFermeture,
    ): Liberation {
        val avant = horloge.nowMillis()
        var interruption: CancellationException? = null
        var verdict: ResultatFermetureElement
        try {
            val termine: Unit? =
                if (element.annulable) {
                    withTimeoutOrNull(element.delai) {
                        // la marque rend toute réentrance détectable depuis la
                        // libération (elle survit même à NonCancellable)
                        withContext(MarqueFermeture()) { element.fermable.fermer(contexte) }
                    }
                } else {
                    withContext(NonCancellable + MarqueFermeture()) {
                        withTimeoutOrNull(element.delai) { element.fermable.fermer(contexte) }
                    }
                }
            verdict =
                if (termine == null) {
                    ResultatFermetureElement.DelaiDepasse
                } else {
                    ResultatFermetureElement.Ferme
                }
        } catch (annulation: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // l'annulation naît DANS l'élément (appelant actif) : panne
                verdict =
                    ResultatFermetureElement.Echec(
                        "libération annulée en interne : ${annulation.message ?: annulation.javaClass.simpleName}",
                    )
            } else {
                // annulation de l'APPELANT : verdict Annule, on poursuit (I7)
                verdict = ResultatFermetureElement.Annule
                interruption = annulation
            }
        } catch (panne: Throwable) {
            verdict = ResultatFermetureElement.Echec(panne.message ?: panne.javaClass.simpleName)
        }
        val bilan =
            RapportElementFermeture(
                nom = element.fermable.nom,
                duree = (horloge.nowMillis() - avant).toDuration(DurationUnit.MILLISECONDS),
                resultat = verdict,
            )
        journaliserElement(bilan)
        return Liberation(bilan, interruption)
    }

    /** Journal d'un bilan d'élément : anomalie = WARN, succès = DEBUG. */
    private fun journaliserElement(bilan: RapportElementFermeture) {
        when (bilan.resultat) {
            ResultatFermetureElement.Ferme -> {
                journal?.d(TAG) { "${bilan.nom} libéré (${bilan.duree})" }
            }

            ResultatFermetureElement.DelaiDepasse -> {
                journal?.w(TAG) { "${bilan.nom} : délai dépassé, libération abandonnée (${bilan.duree})" }
            }

            ResultatFermetureElement.Annule -> {
                journal?.w(TAG) { "${bilan.nom} annulé (annulation de l'appelant)" }
            }

            is ResultatFermetureElement.Echec -> {
                journal?.w(TAG) { "${bilan.nom} en échec : ${bilan.resultat.details} (${bilan.duree})" }
            }
        }
    }

    /** Journal du résumé de fermeture (INFO — identifiants seuls, règle 15). */
    private fun journaliserRapport(rapport: RapportFermeture) {
        journal?.i(TAG) {
            "fermeture (${rapport.raison}) : ${rapport.elements.size} élément(s) — " +
                "${rapport.nbFermes} fermés, ${rapport.nbEchecs} échecs, " +
                "${rapport.nbDepassements} délais dépassés, ${rapport.nbAnnules} annulés " +
                "(${rapport.dureeTotale})"
        }
    }

    /** Section critique non suspendante sur la liste des éléments. */
    private inline fun <T> sousVerrouListe(bloc: () -> T): T {
        verrouListe.lock()
        try {
            return bloc()
        } finally {
            verrouListe.unlock()
        }
    }

    /** Poignée de désenregistrement — retire l'élément par identité. */
    private inner class DesenregistrementInterne(
        private val fermable: Fermable,
    ) : Desenregistrement {
        override fun desenregistrer() {
            sousVerrouListe { elements.removeAll { it.fermable === fermable } }
        }
    }

    private companion object {
        private const val TAG = "RegistreFermeture"
    }
}
