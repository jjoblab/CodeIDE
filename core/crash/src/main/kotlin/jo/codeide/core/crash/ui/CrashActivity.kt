package jo.codeide.core.crash.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import jo.codeide.core.crash.CrashLimits
import jo.codeide.core.crash.CrashReportFileStore
import jo.codeide.core.crash.CrashReportFormatter
import jo.codeide.core.crash.CrashReportZip
import jo.codeide.core.crash.R
import jo.codeide.core.crash.databinding.ActivityCrashBinding
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashType
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Écran dédié aux plantages (section 5.8) — **processus séparé `:crash`**,
 * sans Hilt, sans Room, sans DataStore.
 *
 * Il ne construit que ce dont il lit : un [CrashReportFileStore] et le
 * rapport désigné par l'`Intent`. Deux modes :
 * - [MODE_LIVE] : juste après un plantage (lancé par le gestionnaire) ;
 * - [MODE_VIEW] : consultation depuis l'historique (lancé par l'app).
 *
 * Actions : Redémarrer (LIVE seulement, masqué en cas de boucle), Copier,
 * Partager (texte ou archive zip via FileProvider dédié), Enregistrer
 * (SAF), Fermer — et Vider le cache (jamais les données utilisateur) en
 * cas de boucle, avec conseil explicite.
 */
class CrashActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCrashBinding
    private lateinit var store: CrashReportFileStore
    private var rapport: CrashReport? = null
    private var mode: String = MODE_VIEW
    private var detailsVisibles = false

    /** Enregistrement SAF de l'archive du rapport. */
    private val enregistrerArchive =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) ecrireArchiveVers(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = CrashReportFileStore(File(filesDir, CrashLimits.DIRECTORY_NAME))
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VIEW
        rapport = intent.getStringExtra(EXTRA_REPORT_ID)?.let(store::get)

        val rapportCourant = rapport
        if (rapportCourant == null) {
            afficherIntrouvable()
            return
        }

        remplirEcran(rapportCourant)
        brancherActions(rapportCourant)
    }

    /** Remplît titre, message, résumé, conseil de boucle. */
    private fun remplirEcran(rapport: CrashReport) {
        val enDirect = mode == MODE_LIVE
        binding.crashTitre.setText(if (enDirect) R.string.crash_titre_live else R.string.crash_titre_consultation)
        binding.crashMessage.setText(if (enDirect) R.string.crash_message_live else R.string.crash_message_consultation)

        binding.crashDetailType.text = getString(R.string.crash_resume_type, libelleType(rapport.type))
        binding.crashDetailEcran.text =
            getString(R.string.crash_resume_ecran, rapport.lastScreen ?: getString(R.string.crash_ecran_inconnu))
        binding.crashDetailVersion.text =
            getString(
                R.string.crash_resume_version,
                rapport.application.versionName,
                rapport.application.versionCode,
                rapport.application.buildType,
            )
        binding.crashDetailDate.text =
            getString(
                R.string.crash_resume_date,
                FORMAT_DATE.format(Instant.ofEpochMilli(rapport.timestampMillis)),
            )

        // Boucle : conseil explicite et action « Vider le cache » — jamais
        // de redémarrage proposé (section 5.8).
        if (rapport.isCrashLoop) {
            binding.crashConseilBoucle.visibility = View.VISIBLE
            binding.crashBoutonViderCache.visibility = View.VISIBLE
        }

        binding.crashBoutonRedemarrer.visibility =
            if (enDirect && !rapport.isCrashLoop) View.VISIBLE else View.GONE

        binding.crashTrace.text = CrashReportFormatter.texte(rapport)
        binding.crashAppareil.text = getString(R.string.crash_resume_appareil, resumeAppareil(rapport))
    }

    /** Branche les actions de l'écran. */
    private fun brancherActions(rapport: CrashReport) {
        binding.crashBoutonRedemarrer.setOnClickListener { redemarrerApplication() }
        binding.crashBoutonCopier.setOnClickListener { copierRapport(rapport) }
        binding.crashBoutonPartager.setOnClickListener { partagerTexte(rapport) }
        binding.crashBoutonZip.setOnClickListener { partagerArchive(rapport) }
        binding.crashBoutonEnregistrer.setOnClickListener {
            enregistrerArchive.launch(CrashReportZip.nomFichier(rapport.id))
        }
        binding.crashBoutonFermer.setOnClickListener { finish() }
        binding.crashBoutonViderCache.setOnClickListener { confirmerVidageCache() }
        binding.crashBoutonDetails.setOnClickListener { basculerDetails() }
    }

    /** Affiche ou masque la trace technique et les détails d'appareil. */
    private fun basculerDetails() {
        detailsVisibles = !detailsVisibles
        binding.crashTrace.visibility = if (detailsVisibles) View.VISIBLE else View.GONE
        binding.crashAppareil.visibility = if (detailsVisibles) View.VISIBLE else View.GONE
        binding.crashBoutonDetails.setText(
            if (detailsVisibles) R.string.crash_details_masquer else R.string.crash_details_ouvrir,
        )
    }

    /** Redémarre l'application (tâche propre) puis referme l'écran. */
    private fun redemarrerApplication() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, R.string.crash_redemarrage_impossible, Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finishAffinity()
    }

    /** Copie le rapport texte dans le presse-papiers. */
    private fun copierRapport(rapport: CrashReport) {
        val pressePapiers = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        pressePapiers.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.crash_libelle_presse_papiers),
                CrashReportFormatter.texte(rapport),
            ),
        )
        Toast.makeText(this, R.string.crash_copie_confirmee, Toast.LENGTH_SHORT).show()
    }

    /** Partage le rapport comme texte brut (sélecteur système). */
    private fun partagerTexte(rapport: CrashReport) {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_partage_sujet))
                .putExtra(Intent.EXTRA_TEXT, CrashReportFormatter.texte(rapport))
        startActivity(Intent.createChooser(intent, getString(R.string.crash_partager_via)))
    }

    /**
     * Partage l'archive zip via le FileProvider **du processus `:crash`**
     * (ADR 0010) : l'archive est écrite dans `cache/crash_exports/`.
     */
    private fun partagerArchive(rapport: CrashReport) {
        try {
            val repertoire = File(cacheDir, REPERTOIRE_EXPORTS).apply { mkdirs() }
            val fichier = File(repertoire, CrashReportZip.nomFichier(rapport.id))
            fichier.writeBytes(CrashReportZip.octets(rapport))
            val uri = FileProvider.getUriForFile(this, packageName + AUTHORITE_FOURNISSEUR, fichier)
            val intent =
                Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(
                Intent
                    .createChooser(intent, getString(R.string.crash_partager_via))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (erreur: IOException) {
            Toast.makeText(this, R.string.crash_archive_impossible, Toast.LENGTH_SHORT).show()
        } catch (erreur: IllegalArgumentException) {
            // Fournisseur introuvable : repli sur le partage texte.
            partagerTexte(rapport)
        }
    }

    /** Écrit l'archive du rapport vers l'URI SAF choisi. */
    private fun ecrireArchiveVers(uri: Uri) {
        val rapportCourant = rapport ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { sortie ->
                sortie.write(CrashReportZip.octets(rapportCourant))
            }
            Toast.makeText(this, R.string.crash_enregistrement_confirme, Toast.LENGTH_SHORT).show()
        } catch (erreur: IOException) {
            Toast.makeText(this, R.string.crash_archive_impossible, Toast.LENGTH_SHORT).show()
        }
    }

    /** Demande confirmation avant de vider le cache (jamais les données). */
    private fun confirmerVidageCache() {
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            .setTitle(R.string.crash_vider_cache_titre)
            .setMessage(R.string.crash_vider_cache_message)
            .setPositiveButton(R.string.crash_vider_cache_confirmer) { dialogue, _ ->
                dialogue.dismiss()
                viderCache()
            }.setNegativeButton(R.string.crash_annuler, null)
            .show()
    }

    /** Vide le contenu du répertoire de cache — et rien d'autre. */
    private fun viderCache() {
        cacheDir.listFiles()?.forEach { entree -> entree.deleteRecursively() }
        Toast.makeText(this, R.string.crash_cache_vide, Toast.LENGTH_SHORT).show()
    }

    /** Écran de repli quand le rapport n'existe plus ou est corrompu. */
    private fun afficherIntrouvable() {
        binding.crashTitre.setText(R.string.crash_introuvable_titre)
        binding.crashMessage.setText(R.string.crash_introuvable_message)
        binding.crashCarteResume.visibility = View.GONE
        binding.crashBoutonDetails.visibility = View.GONE
        binding.crashBoutonRedemarrer.visibility = View.GONE
        binding.crashBoutonCopier.visibility = View.GONE
        binding.crashBoutonPartager.visibility = View.GONE
        binding.crashBoutonZip.visibility = View.GONE
        binding.crashBoutonEnregistrer.visibility = View.GONE
        binding.crashBoutonViderCache.visibility = View.GONE
        binding.crashBoutonFermer.setOnClickListener { finish() }
    }

    private fun libelleType(type: CrashType): String =
        when (type) {
            CrashType.EXCEPTION -> getString(R.string.crash_type_exception)
            CrashType.ANR -> getString(R.string.crash_type_anr)
            CrashType.NATIVE -> getString(R.string.crash_type_natif)
        }

    /** Résumé d'appareil non identifiant, une ligne. */
    private fun resumeAppareil(rapport: CrashReport): String =
        "${rapport.device.manufacturer} ${rapport.device.model} — " +
            "${rapport.device.androidVersion} (API ${rapport.device.apiLevel}), " +
            "${rapport.device.abi}, ${rapport.device.locale}"

    public companion object {
        /** Clé de l'identifiant du rapport dans l'intent. */
        public const val EXTRA_REPORT_ID: String = "jo.codeide.core.crash.EXTRA_REPORT_ID"

        /** Clé du mode d'ouverture dans l'intent. */
        public const val EXTRA_MODE: String = "jo.codeide.core.crash.EXTRA_MODE"

        /** Ouverture après un plantage (gestionnaire). */
        public const val MODE_LIVE: String = "LIVE"

        /** Consultation depuis l'historique (application). */
        public const val MODE_VIEW: String = "VIEW"

        private val FORMAT_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        private const val REPERTOIRE_EXPORTS = "crash_exports"

        /** Autorité du FileProvider du processus `:crash` (ADR 0010). */
        internal const val AUTHORITE_FOURNISSEUR = ".crashprovider"
    }
}
