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
 * Onglets Chat (réel), Agent / Historique (« bientôt » — IA-7/IA-6) et
 * Recherche (réservé) ; le contrôleur hôte est résolu par cast en
 * `onAttach` (aucune dépendance feature ↔ feature).
 */
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

    /** Affiche l'onglet demandé — Chat en fragment enfant, le reste en « bientôt ». */
    private fun afficherOnglet(position: Int) {
        val liaison = liaisonTiroir ?: return
        if (position == POSITION_CHAT) {
            afficherChat(liaison)
        } else {
            afficherBientot(liaison, position)
        }
    }

    /** Le chat vit dans un fragment enfant (ViewBinding isolé, VM
     * propre) : ajouté UNE fois, puis attaché/détaché au fil des
     * onglets — un simple findFragmentByTag ne suffit pas, le fragment
     * détaché doit être RÉ-attaché au retour sur Chat. */
    private fun afficherChat(liaison: FragmentIaTiroirBinding) {
        masquerBientot(liaison)
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

    /** État « bientôt » générique — aucun contrôle sans effet (ADR 0059).
     * La vue est gonflée UNE fois puis masquée/affichée : elle ne se
     * cumule jamais par-dessus le chat (vue ajoutée au conteneur). */
    private fun afficherBientot(
        liaison: FragmentIaTiroirBinding,
        position: Int,
    ) {
        val chat = childFragmentManager.findFragmentByTag(TAG_CHAT)
        if (chat != null) {
            childFragmentManager.beginTransaction().detach(chat).commitAllowingStateLoss()
        }
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
                POSITION_HISTORIQUE -> R.string.ia_bientot_historique
                else -> R.string.ia_bientot_recherche
            },
        )
        vue.root.visibility = View.VISIBLE
    }

    /** Masque la vue « bientôt » (retour sur Chat). */
    private fun masquerBientot(liaison: FragmentIaTiroirBinding) {
        val vue = liaisonBientot
        if (vue != null && vue.root.parent === liaison.conteneurIa) {
            vue.root.visibility = View.GONE
        }
    }

    private companion object {
        const val TAG_CHAT = "tiroir_ia_chat"
        const val POSITION_CHAT = 0
        const val POSITION_AGENT = 1
        const val POSITION_HISTORIQUE = 2
    }
}
