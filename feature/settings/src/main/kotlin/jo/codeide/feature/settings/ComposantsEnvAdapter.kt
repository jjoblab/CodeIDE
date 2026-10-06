package jo.codeide.feature.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.feature.settings.databinding.RangeeComposantEnvBinding

/**
 * Ligne d'un composant de l'écran Environnement (E5, § 7 — ADR 0090).
 *
 * @property id identifiant (ex. `build-tools`) ou `jdk` pour la rangée JDK.
 * @property version version installée (celle vérifiée par exécution si connue).
 * @property revision révision de reconditionnement (`r1`), ou `null` (JDK).
 * @property tailleOctets taille réelle sur disque, ou `null` si absent.
 * @property verifie le composant est présent et vérifié.
 * @property desinstallable désinstallable depuis cet écran (JDK : non).
 * @property paquetApt rangée JDK — installé par `pkg` en phase 3, hors manifeste.
 */
data class ComposantEnv(
    val id: String,
    val version: String,
    val revision: String?,
    val tailleOctets: Long?,
    val verifie: Boolean,
    val desinstallable: Boolean,
    val paquetApt: Boolean,
)

/**
 * Adaptateur des composants de l'environnement : versions, tailles
 * réelles et état vérifié par composant, action de désinstallation par
 * ligne (confirmation portée par l'écran).
 */
class ComposantsEnvAdapter(
    private val surDesinstallation: (ComposantEnv) -> Unit,
) : ListAdapter<ComposantEnv, ComposantsEnvAdapter.Vue>(DIFF) {
    /** Vue d'une ligne : liaison par ViewBinding. */
    inner class Vue(
        val liaison: RangeeComposantEnvBinding,
    ) : RecyclerView.ViewHolder(liaison.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Vue = Vue(RangeeComposantEnvBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: Vue,
        position: Int,
    ) {
        val composant = getItem(position)
        val contexte = holder.liaison.root.context
        val taille =
            composant.tailleOctets?.let { octets ->
                contexte.getString(
                    R.string.environnement_taille_mio,
                    octets / MIO,
                )
            } ?: contexte.getString(R.string.environnement_taille_inconnue)
        val etat =
            when {
                composant.verifie && composant.paquetApt -> {
                    contexte.getString(R.string.environnement_etat_verifie_apt)
                }

                composant.verifie -> {
                    contexte.getString(R.string.environnement_etat_verifie)
                }

                else -> {
                    contexte.getString(R.string.environnement_etat_absent)
                }
            }
        val revision =
            composant.revision?.let { contexte.getString(R.string.environnement_revision, it) }
                ?: contexte.getString(R.string.environnement_revision_aucune)
        holder.liaison.nomComposant.text = composant.id
        holder.liaison.detailsComposant.text =
            contexte.getString(
                R.string.environnement_details,
                composant.version,
                revision,
                taille,
                etat,
            )
        holder.liaison.boutonDesinstaller.isVisible = composant.desinstallable
        holder.liaison.boutonDesinstaller.setOnClickListener { surDesinstallation(composant) }
    }

    private companion object {
        private const val MIO: Long = 1024L * 1024

        private val DIFF =
            object : DiffUtil.ItemCallback<ComposantEnv>() {
                override fun areItemsTheSame(
                    ancien: ComposantEnv,
                    nouveau: ComposantEnv,
                ): Boolean = ancien.id == nouveau.id

                override fun areContentsTheSame(
                    ancien: ComposantEnv,
                    nouveau: ComposantEnv,
                ): Boolean = ancien == nouveau
            }
    }
}
