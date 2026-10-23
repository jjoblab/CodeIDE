package jo.codeide.feature.preview

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.feature.preview.databinding.FragmentVoletSourceBinding
import jo.codeide.feature.preview.databinding.ItemLigneSourceBinding
import jo.codeide.preview.xml.ColorationSourceXml
import jo.codeide.preview.xml.FeuilleApercu
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Le volet SOURCE de la feuille (PE9a, miroir `renderSrc` — refonte
 * PE11k à l'identique de `fragment_volet_source`/`item_ligne_source` de
 * la référence, ADR 0134) : le TEXTE VIVANT de la session en UN
 * RecyclerView réel ([AdaptateurSource]) — lignes 20 dp numérotées (col
 * 24 dp), COLORATION balise/attribut/valeur ([ColorationSourceXml],
 * rôles → `ForegroundColorSpan` lus dans `?attr/apercu*`), TROIS
 * surlignages (§ 9 : racine sélectionnée = `isSelected` → accent doux ;
 * plage de la vue = `isActivated` → `apercuSourceVue` ; plage insérée =
 * fond temporaire `apercuSourceInsere`, posée par le flux
 * [PreviewViewModel.plageInseree] puis retirée), plage amenée à 70 dp du
 * haut, tap sur une ligne → la vue propriétaire (miroir `ownerOf`), et
 * « Ouvrir dans l'éditeur ↗ » (BoutonLien de la référence).
 */
@AndroidEntryPoint
internal class VoletSourceFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_SOURCE

    /** Navigation : « Ouvrir dans l'éditeur ↗ » (l'éditeur est en dessous dans la pile). */
    @Inject
    lateinit var navigateur: AppNavigator

    private lateinit var liaison: FragmentVoletSourceBinding

    /** La liste des lignes (recyclée — ~1 500 entrées sans reconstruction). */
    private val adaptateur =
        AdaptateurSource { numero ->
            val indice =
                FeuilleApercu.vueDeLigne(
                    renduCourant?.vues ?: emptyList(),
                    viewModel.texteCourant(),
                    numero,
                )
            if (indice >= 0) viewModel.selectionner(indice)
        }

    override fun onCreateView(inflater: LayoutInflater, conteneur: ViewGroup?, savedInstanceState: Bundle?): View {
        liaison = FragmentVoletSourceBinding.inflate(inflater, conteneur, false)
        liaison.listeSource.adapter = adaptateur
        liaison.listeSource.layoutManager = LinearLayoutManager(requireContext())
        liaison.boutonOuvrirEditeur.setOnClickListener { navigateur.goBack() }
        return liaison.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // PE11k : la plage INSÉRÉE (miroir `S.flash`) re-marche le volet —
        // pose et RETRAIT du surlignage « ins », au rythme du flux.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.plageInseree.collect { reMarcher() }
            }
        }
    }

    override fun marcher() {
        val texte = viewModel.texteCourant()
        val vues = renduCourant?.vues
        val vue = vues?.getOrNull(selectionCourante)
        val plage = FeuilleApercu.plageSource(texte, vue)
        // Miroir renderSrc : la racine sélectionnée affiche « Document
        // entier » (sa plage couvre tout le document — surlignage `hl`).
        val racineSelectionnee = selectionCourante == 0 && vues?.isNotEmpty() == true
        liaison.plageSource.text =
            if (plage != null && !racineSelectionnee) {
                getString(R.string.preview_feuille_source_lignes, plage.first, plage.last)
            } else {
                getString(R.string.preview_feuille_source_entier)
            }
        adaptateur.submitList(lignesSource(texte, plage, racineSelectionnee, viewModel.plageInseree.value))
        // Défilement vers la plage (miroir `pane.scrollTop = …offsetTop - 70`) ;
        // la plage INSÉRÉE prime (miroir `const target = flashSp || …`).
        val cible = viewModel.plageInseree.value ?: plage?.takeIf { !racineSelectionnee }
        if (cible != null) {
            liaison.listeSource.post {
                (liaison.listeSource.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(cible.first - 1, StyleVolet.dp(requireContext(), DP_PLAGE))
            }
        }
    }

    /**
     * Les lignes du document, PORTIONS ET SURLIGNAGES POSÉS (miroir le
     * `SRC.L.forEach` de `renderSrc`) : la coloration du document se
     * calcule UNE fois (rôles purs — les couleurs se résolvent au liage,
     * par le thème), chaque ligne porte son surlignage (racine/plage) et
     * le drapeau « insérée » de la dernière écriture.
     */
    private fun lignesSource(
        texte: String,
        plage: IntRange?,
        racineSelectionnee: Boolean,
        inseree: IntRange?,
    ): List<LigneSourceUi> {
        val coloration = ColorationSourceXml.colorerParLigne(texte)
        return texte.split('\n').mapIndexed { indiceLigne, contenuLigne ->
            val numero = indiceLigne + 1
            val dansPlage = plage != null && numero >= plage.first && numero <= plage.last
            LigneSourceUi(
                numero = numero,
                texte = if (contenuLigne.isEmpty()) " " else contenuLigne,
                surlignage =
                when {
                    racineSelectionnee && dansPlage -> Surlignage.RACINE
                    !racineSelectionnee && dansPlage -> Surlignage.VUE
                    else -> Surlignage.AUCUN
                },
                inseree = inseree != null && numero >= inseree.first && numero <= inseree.last,
                portions = coloration.getOrElse(indiceLigne) { emptyList() },
            )
        }
    }

    /**
     * Amène la liste sur une LIGNE précise (PE11i — bandeau « Ligne n ») :
     * même défilement que la plage de la sélection (70 dp au-dessus).
     */
    internal fun allerALigne(numero: Int) {
        liaison.listeSource.post {
            (liaison.listeSource.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(numero - 1, StyleVolet.dp(requireContext(), DP_PLAGE))
        }
    }

    private companion object {
        /** Le retrait de la plage ramenée en tête de liste, en dp (miroir `- 70`). */
        private const val DP_PLAGE: Int = 70
    }
}

/** Les trois niveaux de surlignage d'une ligne (§ 9 — le sélecteur porte les couleurs). */
internal enum class Surlignage {
    /** Aucun surlignage. */
    AUCUN,

    /** Racine sélectionnée : `isSelected` → `apercuAccentDoux` (miroir `hl`). */
    RACINE,

    /** Plage de la vue sélectionnée : `isActivated` → `apercuSourceVue` (miroir `hl2`). */
    VUE,
}

/** UNE ligne du volet Source (miroir `renderSrc` : numéro, texte, surlignage, portions colorées). */
internal data class LigneSourceUi(
    val numero: Int,
    val texte: String,
    val surlignage: Surlignage,
    val inseree: Boolean,
    val portions: List<ColorationSourceXml.Portion>,
)

/**
 * L'adaptateur du volet Source (miroir `renderSrc`) : ligne = item
 * layout RÉEL (`item_ligne_source.xml` — numéro 24 dp + texte mono
 * 12). Au liage : les ÉTATS (racine/plage) sur la rangée (le sélecteur
 * `apercu_source_ligne_fond` peint `hl`/`hl2`), le fond TEMPORAIRE
 * `apercu_source_insere_fond` sur la plage insérée, les SPANS de
 * coloration (rôles purs → couleurs du thème), et le tap remonte au
 * propriétaire via [auLigne] (lecture FRAÎCHE de l'état du volet).
 */
internal class AdaptateurSource(private val auLigne: (Int) -> Unit) :
    ListAdapter<LigneSourceUi, AdaptateurSource.SourceVue>(ComparateurSource) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceVue =
        SourceVue(ItemLigneSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false), auLigne)

    override fun getItemCount(): Int = currentList.size

    override fun onBindViewHolder(holder: SourceVue, position: Int) {
        holder.poser(currentList[position])
    }

    /** Le conteneur d'une ligne (item_ligne_source.xml). */
    internal class SourceVue(private val liaison: ItemLigneSourceBinding, auLigne: (Int) -> Unit) :
        RecyclerView.ViewHolder(liaison.root) {
        private var numero: Int = -1

        /** Les trois couleurs de coloration, résolues du THÈME au liage (jamais en dur). */
        private val couleurBalise: Int
        private val couleurAttribut: Int
        private val couleurValeur: Int

        init {
            liaison.root.setOnClickListener { auLigne(numero) }
            val contexte = liaison.root.context
            couleurBalise = StyleVolet.couleur(contexte, R.attr.apercuPrimaire)
            couleurAttribut = StyleVolet.couleur(contexte, R.attr.apercuRemplissage)
            couleurValeur = StyleVolet.couleur(contexte, R.attr.apercuMarge)
        }

        fun poser(ligne: LigneSourceUi) {
            numero = ligne.numero
            val contexte = liaison.root.context
            liaison.numeroLigne.text =
                contexte.getString(R.string.preview_feuille_ligne_numero, ligne.numero)
            liaison.texteLigne.text = colorer(ligne.texte, ligne.portions)
            // Les DEUX surlignages d'état : racine (isSelected → hl) et
            // plage de la vue (isActivated → hl2) — le sélecteur peint.
            liaison.root.isSelected = ligne.surlignage == Surlignage.RACINE
            liaison.root.isActivated = ligne.surlignage == Surlignage.VUE
            // Le surlignage « inséré » : fond TEMPORAIRE posé par le code
            // (miroir le commentaire de apercu_source_ligne_fond) — le
            // retrait suit le flux plageInseree (re-marche du volet).
            liaison.root.setBackgroundResource(
                if (ligne.inseree) R.drawable.apercu_source_insere_fond else R.drawable.apercu_source_ligne_fond,
            )
        }

        /** Le texte de la ligne, ses portions colorées en spans du thème. */
        private fun colorer(texte: String, portions: List<ColorationSourceXml.Portion>): CharSequence {
            if (portions.isEmpty()) return texte
            val spans = SpannableString(texte)
            portions.forEach { portion ->
                val couleur =
                    when (portion.role) {
                        ColorationSourceXml.Role.BALISE -> couleurBalise
                        ColorationSourceXml.Role.ATTRIBUT -> couleurAttribut
                        ColorationSourceXml.Role.VALEUR -> couleurValeur
                    }
                val fin = portion.fin.coerceAtMost(texte.length)
                if (fin > portion.debut) {
                    spans.setSpan(
                        ForegroundColorSpan(couleur),
                        portion.debut,
                        fin,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            return spans
        }
    }

    /** Le comparateur (lignes stables par NUMÉRO — le texte seul bouge). */
    private object ComparateurSource : DiffUtil.ItemCallback<LigneSourceUi>() {
        override fun areItemsTheSame(ancienne: LigneSourceUi, nouvelle: LigneSourceUi): Boolean =
            ancienne.numero == nouvelle.numero

        override fun areContentsTheSame(ancienne: LigneSourceUi, nouvelle: LigneSourceUi): Boolean =
            ancienne == nouvelle
    }
}
