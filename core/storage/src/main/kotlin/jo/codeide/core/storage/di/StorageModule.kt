package jo.codeide.core.storage.di

import android.content.ContentResolver
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.ArborescencesSaf
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.FileSystemPrive
import jo.codeide.core.domain.HistoriqueFileSystem
import jo.codeide.core.domain.HistoriqueLocal
import jo.codeide.core.domain.MoteurHistoriqueLocal
import jo.codeide.core.domain.PolitiqueHistorique
import jo.codeide.core.domain.SourceProjetHistorique
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.storage.ContentResolverPersistableUriPermissions
import jo.codeide.core.storage.PersistableUriPermissions
import jo.codeide.core.storage.SafArborescences
import jo.codeide.core.storage.SafFileSystem
import java.io.File
import javax.inject.Singleton
import jo.codeide.core.storage.FileSystemPrive as AdaptateurPrive

/**
 * Assemblage Hilt du module `core:storage` (section 5.1) : le module
 * fournit lui-même la liaison du port [FileSystem] — **décoré par
 * l'historique local** depuis la mission H1 (ADR 0106) : toutes les
 * écritures du projet ouvert sont capturées, silencieusement ; le reste
 * de l'application n'injecte que l'interface du domaine.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface StorageBindsModule {
    /** Port de décomposition des URI d'arborescence (étape 6). */
    @Binds
    fun bindArborescences(impl: SafArborescences): ArborescencesSaf
}

/** Fournitures des collaborateurs système du module. */
@Module
@InstallIn(SingletonComponent::class)
internal object StorageProvidesModule {
    /** Résolveur de contenu de l'application. */
    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context,
    ): ContentResolver = context.contentResolver

    /** Port des permissions persistantes sur le résolveur réel. */
    @Provides
    @Singleton
    fun providePersistableUriPermissions(resolver: ContentResolver): PersistableUriPermissions =
        ContentResolverPersistableUriPermissions(resolver)

    /**
     * Moteur de l'historique local (mission H1, ADR 0104) : blobs +
     * index atomiques dans le stockage PRIVÉ (`files/historique`) —
     * jamais dans le dossier d'un projet. La clé du projet COURANT suit
     * la source de capture : chaque projet ouvert possède son dossier
     * d'historique (l'empreinte de l'URI de sa racine).
     */
    @Provides
    @Singleton
    fun provideHistoriqueLocal(
        @ApplicationContext context: Context,
        source: SourceProjetHistorique,
        horloge: TimeProvider,
        repartiteurs: DispatcherProvider,
    ): HistoriqueLocal =
        MoteurHistoriqueLocal(
            racine = File(context.filesDir, "historique"),
            cleProjetCourante = { source.racineDocument },
            horloge = horloge,
            repartiteurs = repartiteurs,
        )

    /**
     * Le port FileSystem des projets : implémentation SAF **enveloppée
     * par le décorateur d'historique** (ADR 0106) — chaque écriture,
     * suppression, renommage du projet ouvert laisse une trace.
     */
    @Provides
    @Singleton
    fun provideFileSystem(
        impl: SafFileSystem,
        historique: HistoriqueLocal,
        source: SourceProjetHistorique,
        arborescences: ArborescencesSaf,
    ): FileSystem =
        HistoriqueFileSystem(
            delegue = impl,
            historique = historique,
            source = source,
            arborescences = arborescences,
            politique = PolitiqueHistorique(),
        )

    /**
     * Deuxième implémentation du port `FileSystem` (étape 31, ADR 0052) :
     * le stockage **privé** de l'application sous le qualifier dédié —
     * l'arbre « Privé » de l'explorateur. Sans qualifier, l'injection
     * reste l'implémentation SAF des projets (liaison ci-dessus) —
     * l'arbre privé n'est PAS décoré (jamais capturé).
     */
    @Provides
    @FileSystemPrive
    @Singleton
    fun provideFileSystemPrive(impl: AdaptateurPrive): FileSystem = impl
}
