package com.photonx.camera.di

import android.content.Context
import com.photonx.camera.camera.CameraDiagnosticsProvider
import com.photonx.camera.data.AppLogger
import com.photonx.camera.data.MediaStoreRepository
import com.photonx.camera.data.SettingsRepository
import com.photonx.camera.engine.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppLogger(): AppLogger = AppLogger()

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository {
        return SettingsRepository(context)
    }

    @Provides
    @Singleton
    fun provideMediaStoreRepository(
        @ApplicationContext context: Context,
        logger: AppLogger
    ): MediaStoreRepository = MediaStoreRepository(context, logger)

    @Provides
    @Singleton
    fun provideCameraDiagnosticsProvider(
        @ApplicationContext context: Context,
        logger: AppLogger
    ): CameraDiagnosticsProvider = CameraDiagnosticsProvider(context, logger)

    @Provides
    @Singleton
    fun provideOpenCvInitializer(logger: AppLogger): OpenCvInitializer = OpenCvInitializer(logger)

    @Provides
    @Singleton
    fun provideFrameAligner(logger: AppLogger): FrameAligner = FrameAligner(logger)

    @Provides
    @Singleton
    fun provideColorScienceProcessor(logger: AppLogger): ColorScienceProcessor = ColorScienceProcessor(logger)

    @Provides
    @Singleton
    fun provideHdrEngine(
        frameAligner: FrameAligner,
        logger: AppLogger
    ): HdrEngine = HdrEngine(frameAligner, logger)

    @Provides
    @Singleton
    fun provideNightEngine(
        frameAligner: FrameAligner,
        logger: AppLogger
    ): NightEngine = NightEngine(frameAligner, logger)

    @Provides
    @Singleton
    fun providePortraitEngine(logger: AppLogger): PortraitEngine = PortraitEngine(logger)

    @Provides
    @Singleton
    fun provideComputationalPipeline(
        openCvInitializer: OpenCvInitializer,
        hdrEngine: HdrEngine,
        nightEngine: NightEngine,
        portraitEngine: PortraitEngine,
        colorScienceProcessor: ColorScienceProcessor,
        logger: AppLogger
    ): ComputationalPipeline = ComputationalPipeline(
        openCvInitializer,
        hdrEngine,
        nightEngine,
        portraitEngine,
        colorScienceProcessor,
        logger
    )
}
