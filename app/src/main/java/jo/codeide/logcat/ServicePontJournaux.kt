package jo.codeide.logcat

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.applog.ILiaisonJournaux
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.IdentiteSessionJournal
import jo.codeide.core.domain.RegistrePontJournaux
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service du pont de journaux (mission « Exécuter » R2, ADR 0103) — le
 * point d'entrée BINDER côté IDE.
 *
 * **Pourquoi un service** : l'application exécutée SE LIE à lui
 * (composant explicite) — la connexion elle-même maintient le service
 * vivant, QUE CodeIDE soit au premier plan ou non : les journaux
 * continuent de couler pendant que l'utilisateur regarde SON application.
 * Le service meurt avec la dernière connexion (l'app s'arrête : la
 * session est close, `linkToDeath` l'a déjà vu).
 *
 * **Authenticité (ADR 0103 §2)** : l'identité déclarée dans `connecter`
 * n'est pas une preuve — n'importe qui peut forger un nom de paquet.
 * La preuve, c'est l'UID du noyau : `Binder.getCallingUid()` doit être
 * l'UID DU PAQUET DÉCLARÉ (`PackageManager.getPackageUid`). Une
 * application qui se présente sous le nom d'une autre n'a pas son UID :
 * rejet. Le récepteur non exporté à action unique de l'installation
 * (ADR 0102) interdit le scénario inverse (forger des fins d'installation) ;
 * ici, c'est le scénario « faux émetteur de journaux » qui est fermé.
 *
 * **Un lot ne s'accepte jamais sans connexion préalable** : `envoyerLot`
 * identifie l'appelant par son pid (`Binder.getCallingPid`) et ne
 * délivre que les sessions OUVERTES — un lot d'un inconnu est ignoré.
 */
@AndroidEntryPoint
public class ServicePontJournaux : Service() {
    @Inject
    internal lateinit var registre: RegistrePontJournaux

    @Inject
    internal lateinit var journal: AppLogger

    /** Décision « l'appelant est-il le paquet déclaré ? » (R5 : câblé,
     *  extrait pour le test JVM — le noyau ne ment pas, le nom si). */
    @Inject
    internal lateinit var verificateurUid: VerificateurUidPont

    /** Sessions ouvertes : pid de l'appelant → liaison (id + contrôle). */
    private val liaisons = ConcurrentHashMap<Int, Liaison>()

    /** Liaison binder de la mort de l'application. */
    private class Liaison(
        val idSession: String,
        val controle: IBinder?,
        val verificateurMort: IBinder.DeathRecipient,
    ) {
        fun couper() {
            controle?.unlinkToDeath(verificateurMort, 0)
        }
    }

    private val binder =
        object : ILiaisonJournaux.Stub() {
            // Exemption detekt ciblée (règle 16) : ReturnCount — chaque
            // `return` REFUSE une connexion pour une cause DISTINCTE
            // (arguments vides, protocole, paquet inconnu, UID usurpé,
            // binder mort) ; même justification que ServerConfig.ThrowsCount.
            @Suppress("ReturnCount")
            override fun connecter(
                nomPaquet: String?,
                pid: Int,
                nomProcessus: String?,
                controle: IBinder?,
                versionProtocole: Int,
            ) {
                if (nomPaquet.isNullOrEmpty() || nomProcessus.isNullOrEmpty()) {
                    return
                }
                if (versionProtocole != VERSION_PROTOCOLE) {
                    journal.w(
                        TAG,
                    ) { "pont $nomPaquet : protocole $versionProtocole refusé (attendu $VERSION_PROTOCOLE)" }
                    return
                }
                val uid = Binder.getCallingUid()
                val uidAttendu =
                    try {
                        packageManager.getPackageUid(nomPaquet, 0)
                    } catch (introuvable: PackageManager.NameNotFoundException) {
                        // Paquet inconnu : la cause EST le nom, journalisée.
                        journal.w(TAG, introuvable) { "pont $nomPaquet : paquet inconnu, liaison refusée" }
                        return
                    }
                if (!verificateurUid.accepte(uid, uidAttendu)) {
                    // Le noyau ne ment pas : cet appelant n'est PAS le
                    // paquet qu'il prétend être.
                    journal.w(TAG) { "pont $nomPaquet : UID $uid ≠ UID du paquet $uidAttendu, liaison REFUSÉE" }
                    return
                }
                val identite = IdentiteSessionJournal(paquet = nomPaquet, pid = pid, nomProcessus = nomProcessus)
                val idSession = registre.ouvrirSession(identite)
                // Mort de l'application → fin de session IMMÉDIATE
                // (ADR 0103 §3 — remplace tout ping périodique).
                val verificateur =
                    object : IBinder.DeathRecipient {
                        override fun binderDied() {
                            liaisons.remove(pid)?.couper()
                            registre.terminerSession(idSession, "le processus s'est arrêté")
                        }
                    }
                if (controle != null) {
                    try {
                        controle.linkToDeath(verificateur, 0)
                    } catch (liaisonMorte: Exception) {
                        // Le processus est déjà mort : session close direct,
                        // la cause accompagne l'entrée de journal.
                        journal.w(TAG, liaisonMorte) { "pont $nomPaquet : binder de contrôle mort à la connexion" }
                        registre.terminerSession(idSession, "le processus s'est arrêté")
                        return
                    }
                }
                liaisons[pid]?.couper()
                liaisons[pid] = Liaison(idSession, controle, verificateur)
                journal.i(TAG) { "pont ouvert : $nomPaquet pid=$pid ($nomProcessus)" }
            }

            override fun envoyerLot(
                trames: MutableList<String>?,
                lignesPerdues: Long,
            ) {
                val pid = Binder.getCallingPid()
                val liaison = liaisons[pid] ?: return // jamais connecté : ignoré
                if (trames != null && trames.isNotEmpty()) {
                    registre.ajouterTrames(liaison.idSession, trames.toList(), lignesPerdues)
                } else if (lignesPerdues > 0) {
                    // Un lot VIDE peut porter un compteur de pertes seul.
                    registre.ajouterTrames(liaison.idSession, emptyList(), lignesPerdues)
                }
            }

            override fun deconnecter(raison: String?) {
                val pid = Binder.getCallingPid()
                val liaison = liaisons.remove(pid) ?: return
                liaison.couper()
                registre.terminerSession(liaison.idSession, raison ?: "fermeture du pont")
            }
        }

    override fun onBind(intention: Intent?): IBinder = binder

    override fun onDestroy() {
        liaisons.values.forEach(Liaison::couper)
        liaisons.clear()
        super.onDestroy()
    }

    private companion object {
        /** Étiquette des journaux du service (bornes ADR 0009). */
        const val TAG = "pont-journaux"

        /** Version du protocole de trames (miroir de Pont.VERSION_PROTOCOLE). */
        const val VERSION_PROTOCOLE = 1
    }
}

/**
 * Vérificateur d'UID du pont — extrait du service pour être testé en JVM
 * pur : la décision « cet appelant est-il le paquet qu'il prétend ? » ne
 * dépend que de deux entiers.
 */
@Singleton
public class VerificateurUidPont
    @Inject
    public constructor() {
        /**
         * @param uidAppeleur UID du processus appelant (lu au noyau, non falsifiable)
         * @param uidDuPaquet UID du paquet DÉCLARÉ (résolu par PackageManager)
         * @return `true` si l'appelant possède réellement le paquet déclaré
         */
        public fun accepte(
            uidAppeleur: Int,
            uidDuPaquet: Int,
        ): Boolean = uidAppeleur == uidDuPaquet
    }
