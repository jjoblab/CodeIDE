package jo.codeide.feature.home

import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import jo.codeide.core.domain.EtatSessionProjetVue
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.TemplateId
import jo.codeide.feature.home.databinding.ItemProjetBinding

/**
 * Projet prêt au rendu : le projet du registre, son état d'accès
 * calculé (éphémère, section 5.6) et son état de SESSION (mission
 * « Cycle de vie » étape E) — `null` quand ce projet n'est pas celui de
 * la session du processus. Inclure les deux dans l'égalité du
 * [DiffUtil] fait rafraîchir la ligne quand l'accès OU la session
 * change — le badge apparaît sans attendre un autre mouvement dans la
 * liste.
 */
internal data class ProjetAffiche(
    val projet: Project,
    val acces: ProjectAccessState?,
    val session: EtatSessionProjetVue? = null,
)

/**
 * Adaptateur de la liste des projets (étape 7) : [ListAdapter] +
 * [DiffUtil] — les mises à jour du registre n'animent que les lignes
 * réellement changées (épinglage, renommage, badge d'accès).
 *
 * La date d'ouverture est **relative** (« il y a 3 h », calcul système
 * localisé) : jamais ouverts retombent sur la date de création.
 */
internal class ProjetsAccueilAdapter(private val ecouteur: EcouteurProjets) :
    ListAdapter<ProjetAffiche, ProjetsAccueilAdapter.VueProjet>(EcartProjet) {
    /** Projet mis en évidence (création réussie, étape 11) : contour marqué. */
    internal var projetEnEvidence: ProjectId? = null

    /** Interaction de l'utilisateur avec une ligne de projet. */
    internal interface EcouteurProjets {
        /** Toucher la carte : ouvrir le projet, ou ses actions si l'accès est rompu. */
        fun surClicProjet(projet: Project, acces: ProjectAccessState?)

        /** Toucher le bouton d'actions (menu contextuel de la ligne). */
        fun surMenuProjet(projet: Project, acces: ProjectAccessState?)
    }

    internal class VueProjet(val liaison: ItemProjetBinding) : RecyclerView.ViewHolder(liaison.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VueProjet =
        VueProjet(ItemProjetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VueProjet, position: Int) {
        val (projet, acces, session) = getItem(position)
        val liaison = holder.liaison

        liaison.nomProjet.text = projet.name
        liaison.iconeEpingle.isVisible = projet.isPinned
        liaison.iconeEpingle.contentDescription = liaison.root.context.getString(R.string.accueil_desc_epingle)

        liaison.descriptionProjet.text = projet.description
        liaison.descriptionProjet.isVisible = projet.description.isNotBlank()

        liaison.metaProjet.text =
            liaison.root.context.getString(
                R.string.accueil_meta_projet,
                projet.location.displayPath,
                dateRelative(liaison, projet),
            )

        liaison.pastilleType.setImageResource(iconeDe(projet.templateId))
        liaison.pastilleType.contentDescription = null // décorative

        // Mise en évidence du projet fraîchement créé (étape 11) : contour
        // de la couleur primaire du thème (suit le mode sombre) — sobre,
        // jamais une couleur sémantique (pas d'état).
        liaison.carteProjet.strokeWidth =
            liaison.root.resources.getDimensionPixelSize(R.dimen.accueil_surlignage_contour)
        liaison.carteProjet.strokeColor =
            if (projet.id == projetEnEvidence) {
                MaterialColors.getColor(liaison.root, androidx.appcompat.R.attr.colorPrimary)
            } else {
                Color.TRANSPARENT
            }

        // Badge d'accès rompu : signalé visuellement, résolu par le menu
        // (Relocaliser / Retirer), jamais un crash (section 5.6).
        when (acces) {
            ProjectAccessState.Missing, ProjectAccessState.PermissionLost -> {
                liaison.rangeeEtat.isVisible = true
                liaison.texteEtat.setText(TraductionsAccueil.etat(acces))
            }

            else -> {
                liaison.rangeeEtat.isVisible = false
            }
        }

        // Badge de session (mission « Cycle de vie » étape E, matrice
        // §6.3) : la carte du projet OUVERT (ou en cours d'ouverture /
        // fermeture — la transition se montre, l'accueil reste honnête)
        // porte l'état réel du processus — pas seulement la liste.
        when (session) {
            null, EtatSessionProjetVue.Aucune -> {
                liaison.rangeeSession.isVisible = false
            }

            is EtatSessionProjetVue.Ouverture,
            is EtatSessionProjetVue.Ouverte,
            -> {
                liaison.rangeeSession.isVisible = true
                liaison.texteSession.setText(R.string.accueil_session_ouverte)
            }

            is EtatSessionProjetVue.Fermeture -> {
                liaison.rangeeSession.isVisible = true
                liaison.texteSession.setText(R.string.accueil_session_fermeture)
            }
        }

        liaison.boutonMenu.contentDescription =
            liaison.root.context.getString(R.string.accueil_desc_menu, projet.name)
        liaison.boutonMenu.setOnClickListener { ecouteur.surMenuProjet(projet, acces) }
        liaison.carteProjet.setOnClickListener { ecouteur.surClicProjet(projet, acces) }
    }

    /** Date relative du dernier usage : dernier ouvert, sinon création. */
    private fun dateRelative(liaison: ItemProjetBinding, projet: Project): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            liaison.root.context,
            projet.lastOpenedAtMillis ?: projet.createdAtMillis,
            false,
        )

    /**
     * Icône du type de projet : la pastille des modèles embarqués
     * arrive avec eux (étape 9) ; tout modèle inconnu — dont la
     * sentinelle [TemplateId.IMPORTED] — porte le dossier générique,
     * sans qu'aucun `when` ne referme la liste des modèles futurs.
     */
    private fun iconeDe(@Suppress("UNUSED_PARAMETER") templateId: TemplateId): Int = R.drawable.ic_dossier_code
}

/** Écart entre deux lignes : même projet si même identifiant. */
private object EcartProjet : DiffUtil.ItemCallback<ProjetAffiche>() {
    override fun areItemsTheSame(ancien: ProjetAffiche, nouveau: ProjetAffiche): Boolean =
        ancien.projet.id == nouveau.projet.id

    override fun areContentsTheSame(ancien: ProjetAffiche, nouveau: ProjetAffiche): Boolean = ancien == nouveau
}
