package jo.codeide.core.model

import kotlinx.serialization.Serializable

/**
 * Exception aplanie, sérialisable et **dépourvue de tout objet lourd** : c'est
 * une photographie en chaînes de caractères d'une [Throwable], bornée pour
 * pouvoir vivre dans une entrée de journal ([LogEntry]) ou un rapport de
 * plantage sans en exploser la taille.
 *
 * Les limites de la section 5.7 du prompt maître s'appliquent à la
 * journalisation (50 *frames*, 10 causes) ; le gestionnaire de plantages
 * (section 5.8, étape 3) utilise des bornes plus larges via le même
 * mécanisme paramétrable.
 *
 * La chaîne des causes est récursive mais **bornée à la construction** :
 * une exception cyclique (`a` causée par `b`, elle-même causée par `a`)
 * s'aplatit sans boucle infinie.
 *
 * @property className nom qualifié de la classe de l'exception d'origine.
 * @property message message de l'exception, tel que capturé à l'aplatissement
 * (l'expurgation est appliquée en aval par le pipeline de journalisation).
 * @property frames tranches de pile sous forme textuelle, dans l'ordre, déjà
 * tronquées au maximum demandé.
 * @property cause cause aplanie suivante dans la chaîne, ou `null` en fin de
 * chaîne (ou une fois la profondeur maximale atteinte).
 * @property suppressed exceptions supprimées (`Throwable.suppressed`),
 * aplaties à leur tour — section 5.8 : un rapport de plantage les conserve.
 */
@Serializable
public data class FlattenedException(
    public val className: String,
    public val message: String?,
    public val frames: List<String>,
    public val cause: FlattenedException?,
    public val suppressed: List<FlattenedException> = emptyList(),
) {
    public companion object {
        /** Nombre maximal de tranches de pile conservées (section 5.7). */
        public const val DEFAULT_MAX_FRAMES: Int = 50

        /** Nombre maximal d'exceptions enchaînées conservées (section 5.7). */
        public const val DEFAULT_MAX_CAUSES: Int = 10

        /**
         * Nombre maximal d'exceptions supprimées conservées par niveau
         * (section 5.8 : les rapports de plantage les incluent, bornées
         * pour ne pas exploser la taille d'un rapport).
         */
        public const val DEFAULT_MAX_SUPPRESSED: Int = 5

        /**
         * Aplatit une [Throwable] en [FlattenedException] bornée.
         *
         * La profondeur de la chaîne des causes est limitée à [maxCauses]
         * sauts : au-delà, la chaîne est coupée (et non bouclée), ce qui rend
         * l'opération sûre même sur des exceptions mutuellement causes. Les
         * exceptions supprimées de chaque niveau sont aplaties à leur tour
         * ([DEFAULT_MAX_SUPPRESSED] maximum par niveau).
         *
         * @param throwable exception à photographier (jamais stockée).
         * @param maxFrames nombre maximal de tranches de pile par niveau.
         * @param maxCauses nombre maximal de sauts de cause.
         * @return l'exception aplanie, prête à être expurgée puis journalisée.
         */
        public fun from(
            throwable: Throwable,
            maxFrames: Int = DEFAULT_MAX_FRAMES,
            maxCauses: Int = DEFAULT_MAX_CAUSES,
        ): FlattenedException = aplatir(throwable, maxFrames, maxCauses)
    }

    /**
     * Recopie l'exception aplatie en transformant chaque message de la
     * chaîne — y compris ceux des exceptions supprimées (les tranches et
     * noms de classe sont inchangés).
     *
     * Utilisé par le pipeline de journalisation pour appliquer
     * l'expurgation ([jo.codeide.core.domain.LogRedactor]) aux messages
     * d'exception **après** aplatissement — jamais avant, afin qu'un
     * message qui deviendrait identique d'un niveau à l'autre reste
     * distinct.
     *
     * @param transform transformation à appliquer ; reçoit `null` pour un
     * message absent et doit rendre `null` pour le laisser absent.
     * @return une copie dont tous les messages sont transformés.
     */
    public fun transformMessages(transform: (String?) -> String?): FlattenedException =
        FlattenedException(
            className = className,
            message = transform(message),
            frames = frames,
            cause = cause?.transformMessages(transform),
            suppressed = suppressed.map { it.transformMessages(transform) },
        )
}

/**
 * Travail récursif de [FlattenedException.from] : un niveau, puis sa cause
 * tant que la profondeur le permet — les exceptions supprimées de chaque
 * niveau sont aplaties de la même façon (une seule passe, bornée).
 */
private fun aplatir(
    throwable: Throwable,
    maxFrames: Int,
    causesRestantes: Int,
): FlattenedException =
    FlattenedException(
        className = throwable.javaClass.name,
        message = throwable.message,
        frames = throwable.stackTrace.map { it.toString() }.take(maxFrames),
        cause =
            if (causesRestantes <= 0) {
                null
            } else {
                throwable.cause?.let { aplatir(it, maxFrames, causesRestantes - 1) }
            },
        suppressed =
            if (causesRestantes <= 0) {
                emptyList()
            } else {
                throwable.suppressed
                    .take(FlattenedException.DEFAULT_MAX_SUPPRESSED)
                    .map { aplatir(it, maxFrames, causesRestantes - 1) }
            },
    )
