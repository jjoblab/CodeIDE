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
import jo.codeide.feature.ai.databinding.VueIaBientotBinding
import javax.inject.Inject

/**
 * Tiroir IA (mission IA, lot IA-3, ADR 0122) : le fragment ajouté à la
 * 6ᵉ destination du rail de l'éditeur via [FabriqueFragmentIaTiroir]
 * (liée par Hilt dans `di/TiroirIaBindsModule` — miroir du terminal).
 * Onglets Chat (réel), Historique (réel — lot IA-6), Agent (« bientôt »
 * — IA-7) et Recherche (réservé) ; le contrôleur hôte est résolu par
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

    /** Liens des vues « bientôt » — recréés par onglet (recyclage simple). */
    private var liaisonBientot: VueIaBientotBinding? = null

    override fun onAttach(contexte: Context) {
        super.onAttach(contexte)
        controleur = contexte as? ControleurIaTiroir
    }

    override fun onDetach() {
        controleur = null
        super.onDetach()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val liaison = FragmentIaTiroirBinding.inflate(inflater, container, false)
        liaisonTiroir = liaison
        return liaison.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
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
        liaisonBientot = null
        super.onDestroyView()
    }

    /**
     * Rouvre une conversation de l'historique DANS le chat (lot IA-6,
     * spec E6) : bascule sur l'onglet Chat puis transmet l'identifiant
     * au fragment chat — conversation poursuivie, jamais dupliquée.
     */
    public fun ouvrirConversationDansChat(conversationId: String) {
        afficherOnglet(POSITION_CHAT)
        // L'onglet Chat est attaché ASYNCHRONEMENT (commit) : la
        // transmission passe par la file du fragment enfant dès qu'il
        // reprend son cycle de vie.
        val chat = childFragmentManager.findFragmentByTag(TAG_CHAT) as? ChatFragment
        chat?.ouvrirConversation(conversationId)
    }

    /** Affiche l'onglet demandé — Chat, Agent et Historique en
     * fragments enfants réels, Recherche en « bientôt ». */
    private fun afficherOnglet(position: Int) {
        val liaison = liaisonTiroir ?: return
        when (position) {
            POSITION_CHAT -> afficherChat(liaison)
            POSITION_AGENT -> afficherAgent(liaison)
            POSITION_HISTORIQUE -> afficherHistorique(liaison)
            else -> afficherBientot(liaison, position)
        }
    }

    /** Le chat vit dans un fragment enfant (ViewBinding isolé, VM
     * propre) : ajouté UNE fois, puis attaché/détaché au fil des
     * onglets — un simple findFragmentByTag ne suffit pas, le fragment
     * détaché doit être RÉ-attaché au retour sur Chat. */
    private fun afficherChat(liaison: FragmentIaTiroirBinding) {
        masquerAutres(liaison, TAG_CHAT)
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
    private fun afficherAgent(liaison: FragmentIaTiroirBinding) {
        masquerAutres(liaison, TAG_AGENT)
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
    private fun afficherHistorique(liaison: FragmentIaTiroirBinding) {
        masquerAutres(liaison, TAG_HISTORIQUE)
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

    /** Ouvre la revue des changements (IA-7b, plein tiroir) —
     * le fragment Agent bascule ici après « Examiner ». */
    public fun ouvrirRevueChangements() {
        val liaison = liaisonTiroir ?: return
        masquerAutres(liaison, TAG_REVUE)
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
        val liaison = liaisonTiroir ?: return
        val revue = childFragmentManager.findFragmentByTag(TAG_REVUE)
        if (revue != null) {
            childFragmentManager.beginTransaction().detach(revue).commitAllowingStateLoss()
        }
        afficherAgent(liaison)
    }

    /** Détache les fragments enfants autres que la destination + masque
     * la vue « bientôt » (un seul écran visible à la fois). */
    private fun masquerAutres(
        liaison: FragmentIaTiroirBinding,
        tagCible: String,
    ) {
        val masquerVue = tagCible != TAG_BIENTOT
        val vue = liaisonBientot
        if (vue != null && vue.root.parent === liaison.conteneurIa) {
            vue.root.visibility = if (masquerVue) View.GONE else vue.root.visibility
        }
        for (tag in listOf(TAG_CHAT, TAG_AGENT, TAG_HISTORIQUE, TAG_REVUE)) {
            if (tag == tagCible) continue
            childFragmentManager.findFragmentByTag(tag)?.let { fragment ->
                childFragmentManager.beginTransaction().detach(fragment).commitAllowingStateLoss()
            }
        }
    }

    /** État « bientôt » générique — aucun contrôle sans effet (ADR 0059).
     * La vue est gonflée UNE fois puis masquée/affichée : elle ne se
     * cumule jamais par-dessus le chat (vue ajoutée au conteneur). */
    private fun afficherBientot(
        liaison: FragmentIaTiroirBinding,
        position: Int,
    ) {
        masquerAutres(liaison, TAG_BIENTOT)
        val vue =
            liaisonBientot ?: VueIaBientotBinding
                .inflate(layoutInflater, liaison.conteneurIa, false)
                .also { gonflee ->
                    liaison.conteneurIa.addView(gonflee.root)
                    liaisonBientot = gonflee
                }
        vue.texteBientot.setText(
            when (position) {
                POSITION_AGENT -> R.string.ia_bientot_agent
                else -> R.string.ia_bientot_recherche
            },
        )
        vue.root.visibility = View.VISIBLE
    }

    private companion object {
        const val TAG_CHAT = "tiroir_ia_chat"
        const val TAG_AGENT = "tiroir_ia_agent"
        const val TAG_HISTORIQUE = "tiroir_ia_historique"
        const val TAG_REVUE = "tiroir_ia_revue"

        /** Pseudo-tag de la vue « bientôt » (pas un fragment). */
        const val TAG_BIENTOT = "tiroir_ia_bientot"
        const val POSITION_CHAT = 0
        const val POSITION_AGENT = 1
        const val POSITION_HISTORIQUE = 2
    }
}
