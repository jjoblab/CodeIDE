package jo.codeide.feature.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.domain.EtatSessionJournal
import jo.codeide.core.domain.LigneJournal
import jo.codeide.core.domain.NiveauJournal
import jo.codeide.core.domain.SessionJournal
import jo.codeide.core.ui.collectWithLifecycle
import jo.codeide.feature.editor.databinding.FragmentPanneauLogcatBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Onglet Logcat du panneau inférieur (mission « Exécuter » R3, spec
 * EXECUTER.md § 4, ADR 0103) : le vrai Logcat des applications
 * exécutées, SANS adb — barre d'outils (sélecteur de processus, filtre
 * texte, regex, niveau, pause, effacer), bandeaux d'état (pertes,
 * arrêt, session précédente, erreur de motif), table monospace
 * virtualisée, état vide honnête.
 *
 * Le fragment NE FAIT QUE RENDRE : tout l'état vit dans
 * [LogcatViewModel] (sélection, tampon, filtre, pause) — le chrome
 * (en-tête, onglets) reste à l'hôte (activity_editor.xml), même
 * pattern que les autres onglets (ADR 0055).
 *
 * Honnêteté (spec § 5) : le pied de l'état vide dit le périmètre sans
 * adb ; les pertes ne sont jamais silencieuses ; l'option regex
 * signale son motif invalide en ligne, jamais en crash.
 *
 * Exemption detekt ciblée (règle 16, même forme que
 * ExplorateurFragment) : `TooManyFunctions` — UNE fonction par ZONE de
 * l'onglet (§ 4.1 : barre, table, bandeaux) et une par ACTION de la
 * barre (menu processus, menu niveau, copie, clavier) ; les regrouper
 * masquerait la structure de la spécification.
 */
@Suppress("TooManyFunctions")
@AndroidEntryPoint
class PanneauLogcatFragment : Fragment() {
    private val viewModel: LogcatViewModel by viewModels()

    private var liaisonAmorce: FragmentPanneauLogcatBinding? = null

    /** Liaison de la vue courante (accès après destruction = diagnostic
     *  lisible, jamais de `!!`). */
    private val liaison: FragmentPanneauLogcatBinding
        get() =
            checkNotNull(liaisonAmorce) {
                "liaison du panneau Logcat indisponible — vue détruite ?"
            }

    /** Adaptateur de la table (identité = numéro de ligne). */
    private lateinit var adaptateur: LignesLogcatAdapter

    /** Le défilement suit-il la fin ? Honnête : un doigt qui touche la
     *  liste ARRÊTE le suivi, revenir au fond le reprend (spec § 4.3). */
    private var suitFin = true

    /** Option regex active (l'état du CHAMP vit dans la vue — le
     *  ViewModel reçoit le couple complet à chaque frappe). */
    private var regexActive = false

    /** Format de l'heure des bandeaux (HH:mm, invariant locale). */
    private val formatHeure = SimpleDateFormat("HH:mm", Locale.ROOT)

    /** Format de la copie brute d'une ligne (HH:mm:ss.SSS). */
    private val formatLigneBrute = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    override fun onCreateView(
        inflateur: LayoutInflater,
        conteneur: ViewGroup?,
        etat: Bundle?,
    ): View {
        liaisonAmorce = FragmentPanneauLogcatBinding.inflate(inflateur, conteneur, false)
        return liaison.root
    }

    override fun onViewCreated(
        vue: View,
        etat: Bundle?,
    ) {
        super.onViewCreated(vue, etat)
        brancherBarreOutils()
        brancherListe()
        viewModel.etat.collectWithLifecycle(viewLifecycleOwner) { rendre(it) }
    }

    override fun onDestroyView() {
        liaisonAmorce = null
        suitFin = true
        super.onDestroyView()
    }

    /** Barre d'outils (§ 4.1) : puce de processus, filtre texte + regex,
     *  niveau minimal, pause, effacer. */
    private fun brancherBarreOutils() {
        liaison.puceProcessus.setOnClickListener { ouvrirMenuProcessus() }
        liaison.boutonNiveau.setOnClickListener { ouvrirMenuNiveau() }
        liaison.boutonPause.setOnClickListener { viewModel.basculerPause() }
        liaison.boutonEffacer.setOnClickListener { viewModel.effacer() }

        // Filtre texte : chaque frappe renvoie le couple (texte, regex) —
        // l'état du champ vit dans la vue, jamais d'écho vers le champ.
        liaison.champFiltre.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    texte: CharSequence?,
                    debut: Int,
                    nombre: Int,
                    apres: Int,
                ) = Unit

                override fun onTextChanged(
                    texte: CharSequence?,
                    debut: Int,
                    avant: Int,
                    nombre: Int,
                ) = Unit

                override fun afterTextChanged(texte: Editable?) {
                    viewModel.definirFiltreTexte(texte?.toString().orEmpty(), regexActive)
                }
            },
        )
        // Recherche validée : clavier rangé (le filtre vit déjà à la frappe).
        liaison.champFiltre.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                cacherClavier()
                true
            } else {
                false
            }
        }

        // Option regex : bascule visuelle (accent) + renvoi du couple.
        liaison.boutonRegex.setOnClickListener {
            regexActive = !regexActive
            liaison.boutonRegex.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (regexActive) R.color.logcat_niveau_debogage else R.color.explorateur_texte_2,
                ),
            )
            viewModel.definirFiltreTexte(liaison.champFiltre.text.toString(), regexActive)
        }
    }

    /** Table (§ 4.2) : liste virtualisée, suivi direct honnête, copie au
     *  clic long (export reste une action explicite, jamais automatique). */
    private fun brancherListe() {
        adaptateur =
            LignesLogcatAdapter(requireContext()) { ligne ->
                copierLigne(ligne)
            }
        liaison.listeLogcat.layoutManager = LinearLayoutManager(requireContext())
        liaison.listeLogcat.adapter = adaptateur
        liaison.listeLogcat.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    liste: RecyclerView,
                    etat: Int,
                ) {
                    // Un doigt touche la liste : le suivi s'arrête (honnête,
                    // spec § 4.3) — même si le doigt ne fait que suivre.
                    if (etat == RecyclerView.SCROLL_STATE_DRAGGING) {
                        suitFin = false
                    }
                }

                override fun onScrolled(
                    liste: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    // Fond atteint (même en glissement) : le suivi reprend.
                    if (!liste.canScrollVertically(1)) {
                        suitFin = true
                    }
                }
            },
        )
    }

    /** Rend TOUT l'état : puce, bandeaux, table, état vide, pause. */
    private fun rendre(etat: EtatLogcat) {
        adaptateur.submitList(etat.lignes)
        if (suitFin && etat.lignes.isNotEmpty()) {
            liaison.listeLogcat.scrollToPosition(etat.lignes.lastIndex)
        }
        liaison.etatVideLogcat.isVisible = etat.lignes.isEmpty()

        // Puce de processus : nom affiché + ampoule (vivante = vert).
        val nom =
            etat.sessionArchivee
                ?.session
                ?.identite
                ?.nomProcessus
                ?: etat.sessions
                    .firstOrNull { it.id == etat.idSelection }
                    ?.identite
                    ?.nomProcessus
        liaison.nomProcessus.text = nom ?: getString(R.string.logcat_aucun_processus)
        liaison.ampouleProcessus.backgroundTintList =
            ContextCompat.getColorStateList(
                requireContext(),
                if (etat.sessionArchivee == null && etat.sessions
                        .firstOrNull { it.id == etat.idSelection }
                        ?.etat == EtatSessionJournal.VIVANTE
                ) {
                    R.color.explorateur_vert
                } else {
                    R.color.explorateur_texte_3
                },
            )

        rendreBandeaux(etat)

        // Pause : icône de reprise (lecture) quand figée — teinte d'alerte
        // pour que l'état se voie d'un coup d'œil.
        if (etat.enPause) {
            liaison.boutonPause.setIconResource(R.drawable.ic_executer)
            liaison.boutonPause.setIconTintResource(R.color.logcat_niveau_avertissement)
        } else {
            liaison.boutonPause.setIconResource(R.drawable.ic_pause_logcat)
            liaison.boutonPause.setIconTintResource(R.color.explorateur_texte_2)
        }
    }

    /** Bandeaux d'état (§ 4.1) : pertes, arrêt, session précédente,
     *  erreur de motif — jamais silencieux (§ 5). */
    private fun rendreBandeaux(etat: EtatLogcat) {
        liaison.bandeauPertes.isVisible = etat.lignesPerdues > 0L
        if (etat.lignesPerdues > 0L) {
            liaison.bandeauPertes.text =
                resources.getQuantityString(
                    R.plurals.logcat_lignes_perdues,
                    etat.lignesPerdues.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    etat.lignesPerdues,
                )
        }
        liaison.bandeauArret.isVisible = etat.fin != null
        if (etat.fin != null) {
            liaison.bandeauArret.text = getString(R.string.logcat_arret, etat.fin.raison)
        }
        liaison.bandeauPrecedente.isVisible = etat.sessionArchivee != null
        if (etat.sessionArchivee != null) {
            val session = etat.sessionArchivee.session
            val heure =
                session.fin?.let { fin -> formatHeure.format(Date(fin.horodatageMs)) }
                    ?: getString(R.string.logcat_precedente_heure_inconnue)
            liaison.bandeauPrecedente.text =
                getString(
                    R.string.logcat_precedente,
                    session.identite.nomProcessus,
                    session.identite.pid,
                    heure,
                )
        }
        liaison.ligneErreurMotif.isVisible = etat.erreurMotif
        if (etat.erreurMotif) {
            liaison.ligneErreurMotif.text = getString(R.string.logcat_motif_invalide, etat.filtre.texte)
        }
    }

    /** Sélecteur de processus (§ 4.3) : vivantes d'abord, puis terminées
     *  du registre, puis sessions précédentes (archive bornée). */
    private fun ouvrirMenuProcessus() {
        val etat = viewModel.etat.value
        val vivantes = etat.sessions.filter { it.etat == EtatSessionJournal.VIVANTE }
        val terminees = etat.sessions.filter { it.etat != EtatSessionJournal.VIVANTE }
        val menu = PopupMenu(requireContext(), liaison.puceProcessus)
        vivantes.forEachIndexed { index, session ->
            menu.menu.add(GROUPE_VIVANTES, index + 1, 0, libelleSession(session, R.string.logcat_session_vivante))
        }
        terminees.forEachIndexed { index, session ->
            menu.menu.add(GROUPE_TERMINEES, index + 1, 0, libelleSession(session, R.string.logcat_session_terminee))
        }
        etat.archives.forEachIndexed { index, archivee ->
            menu.menu.add(
                GROUPE_PRECEDENTES,
                index + 1,
                0,
                libelleSession(archivee.session, R.string.logcat_session_precedente),
            )
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.groupId) {
                GROUPE_VIVANTES -> {
                    vivantes.getOrNull(item.itemId - 1)?.let { viewModel.selectionnerSession(it.id) }
                }

                GROUPE_TERMINEES -> {
                    terminees.getOrNull(item.itemId - 1)?.let { viewModel.selectionnerSession(it.id) }
                }

                GROUPE_PRECEDENTES -> {
                    etat.archives.getOrNull(item.itemId - 1)?.let { viewModel.selectionnerArchivee(it.session.id) }
                }

                else -> {
                    Unit
                }
            }
            true
        }
        menu.show()
    }

    /** Filtre de niveau (§ 4.3) : masque les niveaux inférieurs, façon
     *  Android Studio (Verbose → Assert). */
    private fun ouvrirMenuNiveau() {
        val menu = PopupMenu(requireContext(), liaison.boutonNiveau)
        NiveauJournal.entries.forEachIndexed { index, niveau ->
            menu.menu.add(GROUPE_NIVEAUX, index + 1, 0, libelleNiveau(niveau))
        }
        menu.setOnMenuItemClickListener { item ->
            NiveauJournal.entries.getOrNull(item.itemId - 1)?.let { viewModel.definirNiveauMinimal(it) }
            true
        }
        menu.show()
    }

    /** Copie de la ligne brute (clic long, spec § 4.3) — au format
     *  logcat (« heure pid-tid N/etiquette: message »). Confirmation :
     *  le système l'affiche déjà sur Android 13+, un Toast court sinon
     *  (même règle que le terminal, correctif C3). */
    private fun copierLigne(ligne: LigneJournal) {
        val contexte = requireContext()
        val brut =
            formatLigneBrute.format(Date(ligne.horodatageMs)) +
                " " + ligne.pid + "-" + ligne.tid + " " +
                lettreNiveauLogcat(ligne.niveau) +
                "/" + ligne.etiquette + ": " + ligne.message
        val presse = contexte.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        presse.setPrimaryClip(ClipData.newPlainText(getString(R.string.logcat_libelle_clip), brut))
        if (Build.VERSION.SDK_INT < SEUIL_CONFIRMATION_SYSTEME) {
            Toast.makeText(contexte, R.string.logcat_copiee, Toast.LENGTH_SHORT).show()
        }
    }

    /** Range le clavier (validation du filtre). */
    private fun cacherClavier() {
        val gestionnaire =
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        gestionnaire.hideSoftInputFromWindow(liaison.champFiltre.windowToken, 0)
    }

    /** Libellé d'une session du menu : « nom (pid) · état ». */
    private fun libelleSession(
        session: SessionJournal,
        suffixe: Int,
    ): String =
        getString(R.string.logcat_session_menu, session.identite.nomProcessus, session.identite.pid) +
            getString(suffixe)

    /** Libellé localisé d'un niveau du menu. */
    private fun libelleNiveau(niveau: NiveauJournal): Int =
        when (niveau) {
            NiveauJournal.VERBEUX -> R.string.logcat_niveau_verbeux
            NiveauJournal.DEBOGAGE -> R.string.logcat_niveau_debogage
            NiveauJournal.INFO -> R.string.logcat_niveau_info
            NiveauJournal.AVERTISSEMENT -> R.string.logcat_niveau_avertissement
            NiveauJournal.ERREUR -> R.string.logcat_niveau_erreur
            NiveauJournal.ASSERT -> R.string.logcat_niveau_assert
        }

    private companion object {
        /** Groupes du menu de processus (vivantes, terminées, précédentes). */
        const val GROUPE_VIVANTES = 1
        const val GROUPE_TERMINEES = 2
        const val GROUPE_PRECEDENTES = 3

        /** Groupe du menu de niveaux. */
        const val GROUPE_NIVEAUX = 4

        /** Android 13 (API 33) : le système confirme lui-même la copie. */
        const val SEUIL_CONFIRMATION_SYSTEME = 33
    }
}
