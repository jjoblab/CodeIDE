package jo.codeide.feature.ai

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.ControleurIaTiroir
import jo.codeide.feature.ai.databinding.FragmentIaTiroirBinding
import javax.inject.Inject

/**
 * Tiroir IA (mission IA, lot IA-3, ADR 0122) : le fragment ajouté à la
 * 6ᵉ destination du rail de l'éditeur via [FabriqueFragmentIaTiroir]
 * (liée par Hilt dans `di/TiroirIaBindsModule` — miroir du terminal).
 * Onglets Chat (réel), Agent (réel — IA-7b, rapport IA lot 2),
 * Historique (réel — lot IA-6) et Recherche (réel — rapport IA, lot 3 :
 * l'état « bientôt » est MORT, remplacé par la recherche de fichiers
 * avec « Demander à l'IA ») ; le contrôleur hôte est résolu par
 * cast en `onAttach` (aucune dépendance feature ↔ feature).
 *
 * Le seuil de fonctions est structurellement dépassé : un fragment est
 * un CONTRAT de câblage des onglets (précédent `PanneauConsoleFragment`).
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
public class IaTiroirFragment : Fragment() {
    /** Commandes de l'hôte (chemin du fichier actif, dossier projet…) — IA-4/IA-7. */
    private var controleur: ControleurIaTiroir? = null

    private var liaisonTiroir: FragmentIaTiroirBinding? = null

    override fun onAttach(contexte: Context) {
        super.onAttach(contexte)
        controleur = contexte as? ControleurIaTiroir
    }

    override fun onDetach() {
        controleur = null
        super.onDetach()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val liaison = FragmentIaTiroirBinding.inflate(inflater, container, false)
        liaisonTiroir = liaison
        return liaison.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val liaison = liaisonTiroir ?: return
        val onglets = liaison.ongletsIa
        onglets.addTab(onglets.newTab().setText(R.string.ia_onglet_chat))
        onglets.addTab(onglets.newTab().setText(R.string.ia_onglet_agent))
        onglets.addTab(onglets.newTab().setText(R.string.ia_onglet_historique))
        onglets.addTab(onglets.newTab().setText(R.string.ia_onglet_recherche))
        onglets.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(onglet: TabLayout.Tab) {
                    afficherOnglet(onglet.position)
                }

                override fun onTabUnselected(onglet: TabLayout.Tab) = Unit

                override fun onTabReselected(onglet: TabLayout.Tab) = Unit
            },
        )
        afficherOnglet(POSITION_CHAT)
    }

    override fun onDestroyView() {
        liaisonTiroir = null
        super.onDestroyView()
    }

    /**
     * Rouvre une conversation de l'historique DANS le chat (lot IA-6,
     * spec E6) : bascule sur l'onglet Chat puis transmet l'identifiant
     * au fragment chat — conversation poursuivie, jamais dupliquée.
     *
     * B2 (rapport IA, lot 1) : l'onglet Chat est désormais le tab
     * SÉLECTIONNÉ du TabLayout (l'utilisateur voit OÙ la discussion
     * s'ouvre — plus d'onglet Historique resté actif pendant que le
     * chat s'affiche), et la transmission passe par un FRAGMENT
     * RESULT : le chat — possiblement détaché, l'attachement est
     * asynchrone (`commitAllowingStateLoss`) — consomme le résultat à
     * son passage à STARTED, plus JAMAIS d'appel direct sur un
     * fragment sans vue (l'appel était PERDU quand le chat n'était
     * pas encore attaché).
     */
    public fun ouvrirConversationDansChat(conversationId: String) {
        val liaison = liaisonTiroir
        val ongletChat = liaison?.ongletsIa?.getTabAt(POSITION_CHAT)
        if (ongletChat?.isSelected == true) {
            // Déjà sélectionné : le listener ne redéclenchera pas,
            // l'affichage est fait À LA MAIN (idempotent).
            afficherOnglet(POSITION_CHAT)
        } else {
            ongletChat?.select()
        }
        childFragmentManager.setFragmentResult(
            CLE_RESULTAT_OUVERTURE_CONVERSATION,
            Bundle().apply { putString(EXTRA_CONVERSATION_ID, conversationId) },
        )
    }

    /**
     * Ouvre une discussion NEUVE dans le chat (B3, rapport IA, lot 1 —
     * bouton « ＋ Nouvelle » de l'en-tête de l'historique) : même
     * mécanisme que [ouvrirConversationDansChat] — le tab Chat est
     * SÉLECTIONNÉ et le résultat (clé dédiée, SANS identifiant) attend
     * le passage à STARTED du chat : plus jamais d'appel direct sur un
     * fragment sans vue.
     */
    public fun ouvrirNouvelleDiscussionDansChat() {
        val liaison = liaisonTiroir
        val ongletChat = liaison?.ongletsIa?.getTabAt(POSITION_CHAT)
        if (ongletChat?.isSelected == true) {
            afficherOnglet(POSITION_CHAT)
        } else {
            ongletChat?.select()
        }
        childFragmentManager.setFragmentResult(CLE_RESULTAT_NOUVELLE_DISCUSSION, Bundle.EMPTY)
    }

    /**
     * « Demander à l'IA » sur un fichier de la RECHERCHE (rapport IA,
     * lot 3 — d'après la maquette) : bascule sur l'onglet Chat (tab
     * SÉLECTIONNÉ, même mécanisme que l'ouverture de conversation) et
     * pose « /expliquer <chemin> » dans la saisie par FRAGMENT RESULT —
     * le chat consomme le texte à son passage à STARTED, l'utilisateur
     * garde la main sur l'envoi.
     */
    public fun ouvrirDiscussionIaSur(cheminRelatif: String) {
        val liaison = liaisonTiroir
        val ongletChat = liaison?.ongletsIa?.getTabAt(POSITION_CHAT)
        if (ongletChat?.isSelected == true) {
            afficherOnglet(POSITION_CHAT)
        } else {
            ongletChat?.select()
        }
        childFragmentManager.setFragmentResult(
            CLE_RESULTAT_PREPARER_SAISIE,
            Bundle().apply { putString(EXTRA_SAISIE_PREREMPLIE, "/expliquer $cheminRelatif") },
        )
    }

    /** Affiche l'onglet demandé — les QUATRE onglets sont des fragments
     * enfants réels (rapport IA, lot 3 : la recherche remplace
     * « bientôt »). */
    private fun afficherOnglet(position: Int) {
        val liaison = liaisonTiroir ?: return
        when (position) {
            POSITION_CHAT -> afficherChat()
            POSITION_AGENT -> afficherAgent()
            POSITION_HISTORIQUE -> afficherHistorique()
            POSITION_RECHERCHE -> afficherRecherche()
        }
    }

    /** Le chat vit dans un fragment enfant (ViewBinding isolé, VM
     * propre) : ajouté UNE fois, puis attaché/détaché au fil des
     * onglets — un simple findFragmentByTag ne suffit pas, le fragment
     * détaché doit être RÉ-attaché au retour sur Chat. */
    private fun afficherChat() {
        masquerAutres(TAG_CHAT)
        val gestionnaire = childFragmentManager
        val existant = gestionnaire.findFragmentByTag(TAG_CHAT)
        val transaction = gestionnaire.beginTransaction()
        if (existant == null) {
            transaction.add(R.id.conteneur_ia, ChatFragment(), TAG_CHAT)
        } else {
            transaction.attach(existant)
        }
        transaction.commitAllowingStateLoss()
    }

    /** L'agent vit lui aussi en fragment enfant (lot IA-7b) —
     * ajouté UNE fois puis ré-attaché (même patron que le chat). */
    private fun afficherAgent() {
        masquerAutres(TAG_AGENT)
        val gestionnaire = childFragmentManager
        val existant = gestionnaire.findFragmentByTag(TAG_AGENT)
        val transaction = gestionnaire.beginTransaction()
        if (existant == null) {
            transaction.add(
                R.id.conteneur_ia,
                jo.codeide.feature.ai.agent
                    .AgentFragment(),
                TAG_AGENT,
            )
        } else {
            transaction.attach(existant)
        }
        transaction.commitAllowingStateLoss()
    }

    /** L'historique vit lui aussi en fragment enfant (lot IA-6) —
     * ajouté UNE fois puis ré-attaché (même patron que le chat). */
    private fun afficherHistorique() {
        masquerAutres(TAG_HISTORIQUE)
        val gestionnaire = childFragmentManager
        val existant = gestionnaire.findFragmentByTag(TAG_HISTORIQUE)
        val transaction = gestionnaire.beginTransaction()
        if (existant == null) {
            transaction.add(R.id.conteneur_ia, HistoriqueFragment(), TAG_HISTORIQUE)
        } else {
            transaction.attach(existant)
        }
        transaction.commitAllowingStateLoss()
    }

    /** La recherche vit elle aussi en fragment enfant (rapport IA, lot 3
     * — l'état « bientôt » est MORT) — même patron : ajoutée UNE fois
     * puis ré-attachée. */
    private fun afficherRecherche() {
        masquerAutres(TAG_RECHERCHE)
        val gestionnaire = childFragmentManager
        val existant = gestionnaire.findFragmentByTag(TAG_RECHERCHE)
        val transaction = gestionnaire.beginTransaction()
        if (existant == null) {
            transaction.add(R.id.conteneur_ia, RechercheIaFragment(), TAG_RECHERCHE)
        } else {
            transaction.attach(existant)
        }
        transaction.commitAllowingStateLoss()
    }

    /** Ouvre la revue des changements (IA-7b, plein tiroir) —
     * le fragment Agent bascule ici après « Examiner ». */
    public fun ouvrirRevueChangements() {
        masquerAutres(TAG_REVUE)
        val gestionnaire = childFragmentManager
        val existant = gestionnaire.findFragmentByTag(TAG_REVUE)
        val transaction = gestionnaire.beginTransaction()
        if (existant == null) {
            transaction.add(
                R.id.conteneur_ia,
                jo.codeide.feature.ai.agent
                    .RevueChangementsFragment(),
                TAG_REVUE,
            )
        } else {
            transaction.attach(existant)
        }
        transaction.commitAllowingStateLoss()
    }

    /** Retour de la revue vers l'écran Agent (bouton Retour). */
    public fun fermerRevueChangements() {
        val revue = childFragmentManager.findFragmentByTag(TAG_REVUE)
        if (revue != null) {
            childFragmentManager.beginTransaction().detach(revue).commitAllowingStateLoss()
        }
        afficherAgent()
    }

    /** Détache les fragments enfants autres que la destination (un seul
     * écran visible à la fois — les QUATRE onglets sont réels, rapport
     * IA lot 3 : plus de vue « bientôt » à masquer). */
    private fun masquerAutres(tagCible: String) {
        for (tag in listOf(TAG_CHAT, TAG_AGENT, TAG_HISTORIQUE, TAG_RECHERCHE, TAG_REVUE)) {
            if (tag == tagCible) continue
            childFragmentManager.findFragmentByTag(tag)?.let { fragment ->
                childFragmentManager.beginTransaction().detach(fragment).commitAllowingStateLoss()
            }
        }
    }

    private companion object {
        const val TAG_CHAT = "tiroir_ia_chat"
        const val TAG_AGENT = "tiroir_ia_agent"
        const val TAG_HISTORIQUE = "tiroir_ia_historique"
        const val TAG_RECHERCHE = "tiroir_ia_recherche"
        const val TAG_REVUE = "tiroir_ia_revue"

        const val POSITION_CHAT = 0
        const val POSITION_AGENT = 1
        const val POSITION_HISTORIQUE = 2
        const val POSITION_RECHERCHE = 3
    }
}

/**
 * Clé du FragmentResult d'ouverture d'une discussion (B2, rapport IA
 * lot 1) : le tiroir POSE le résultat (identifiant de conversation),
 * le chat le CONSOMME à son passage à STARTED — le résultat attend
 * dans le `childFragmentManager` du tiroir tant que le chat n'est pas
 * prêt (attachement asynchrone), aucune perte.
 */
internal const val CLE_RESULTAT_OUVERTURE_CONVERSATION: String = "tiroir_ia_ouvrir_conversation"

/** Extra du résultat : identifiant de la conversation à rouvrir. */
internal const val EXTRA_CONVERSATION_ID: String = "conversation_id"

/**
 * Clé du FragmentResult de discussion NEUVE (B3, rapport IA lot 1) :
 * le tiroir POSE le résultat, le chat le consomme à STARTED — sans
 * identifiant : c'est une action, pas une donnée.
 */
internal const val CLE_RESULTAT_NOUVELLE_DISCUSSION: String = "tiroir_ia_nouvelle_discussion"

/**
 * Clé du FragmentResult de PRÉREMPLISSAGE de saisie (rapport IA, lot 3
 * — « Demander à l'IA » de la recherche) : le tiroir POSE le texte
 * (commande « /expliquer <chemin> »), le chat le consomme à STARTED —
 * l'envoi reste à l'utilisateur.
 */
internal const val CLE_RESULTAT_PREPARER_SAISIE: String = "tiroir_ia_preparer_saisie"

/** Extra du résultat : texte à poser dans la saisie du chat. */
internal const val EXTRA_SAISIE_PREREMPLIE: String = "saisie_pre_remplie"
