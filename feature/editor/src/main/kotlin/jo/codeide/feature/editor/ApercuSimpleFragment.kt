package jo.codeide.feature.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import jo.codeide.feature.editor.databinding.FragmentApercuSimpleBinding

/**
 * Fragment d'aperçu statique du tiroir à fragments (étape 31, § 1 :
 * **Recherche** et **Git** — lot ultérieur) : entête propre (emblème
 * coloré, titre, sous-titre mono) et corps d'accueil décrivant la
 * fonctionnalité à venir. La destination reste visible dans le rail —
 * l'aperçu honore la structure d'accueil de la maquette.
 *
 * @param titre libellé du fragment (« Recherche », « Git »).
 * @param sousTitre sous-titre mono de l'entête.
 * @param description corps d'accueil.
 * @param iconeEmbleme icône de l'emblème (17 dp).
 * @param fondEmbleme fond de l'emblème (accent doux de la couleur du
 * fragment : orange Recherche, vert Git).
 * @param teinteEmbleme teinte de l'icône de l'emblème.
 * @param libelleBouton libellé de l'action d'entête (Git : « Commit »),
 * `null` pour aucun bouton.
 */
@Suppress("LongParameterList") // Trois fragments d'aperçu partagent ce constructeur (§ 1).
abstract class ApercuSimpleFragment(
    /** Libellé du fragment (ressource de chaîne). */
    private val titre: Int,
    /** Sous-titre mono de l'entête (ressource de chaîne). */
    private val sousTitre: Int,
    /** Corps d'accueil (ressource de chaîne). */
    private val description: Int,
    /** Icône de l'emblème (ressource de drawable). */
    private val iconeEmbleme: Int,
    /** Fond de l'emblème (ressource de drawable). */
    private val fondEmbleme: Int,
    /** Teinte de l'icône de l'emblème (ressource de couleur). */
    private val teinteEmbleme: Int,
    /** Libellé de l'action d'entête, `null` pour aucun bouton. */
    private val libelleBouton: Int? = null,
) : Fragment() {
    private var liaisonAmorce: FragmentApercuSimpleBinding? = null
    private val liaison get() = liaisonAmorce!!

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentApercuSimpleBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        val contexte = requireContext()
        liaison.titreApercu.setText(titre)
        liaison.sousTitreApercu.setText(sousTitre)
        liaison.descriptionApercu.setText(description)
        liaison.emblemeApercu.setBackgroundResource(fondEmbleme)
        liaison.iconeEmblemeApercu.setImageResource(iconeEmbleme)
        liaison.iconeEmblemeApercu.setColorFilter(ContextCompat.getColor(contexte, teinteEmbleme))

        // Action d'entête (§ 4 : Git propose « Commit ») : visible mais
        // inerte — l'aperçu annonce le lot dédié.
        if (libelleBouton != null) {
            liaison.boutonApercu.visibility = View.VISIBLE
            liaison.boutonApercu.setText(libelleBouton)
            liaison.boutonApercu.isEnabled = false
        }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        super.onDestroyView()
    }
}

/** Destination « Recherche » du tiroir (étape 31) : aperçu orange. */
class RechercheFragment :
    ApercuSimpleFragment(
        titre = R.string.apercu_recherche_titre,
        sousTitre = R.string.apercu_recherche_sous_titre,
        description = R.string.apercu_recherche_description,
        iconeEmbleme = jo.codeide.core.ui.R.drawable.ic_recherche,
        fondEmbleme = R.drawable.fond_embleme_entete_orange,
        teinteEmbleme = R.color.explorateur_rouge,
    )

/** Destination « Git » du tiroir (étape 31) : aperçu vert + « Commit ». */
class GitFragment :
    ApercuSimpleFragment(
        titre = R.string.apercu_git_titre,
        sousTitre = R.string.apercu_git_sous_titre,
        description = R.string.apercu_git_description,
        iconeEmbleme = jo.codeide.core.ui.R.drawable.ic_git,
        fondEmbleme = R.drawable.fond_embleme_entete_vert,
        teinteEmbleme = R.color.explorateur_vert,
        libelleBouton = R.string.apercu_git_commit,
    )
