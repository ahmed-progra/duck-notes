package app.duck.notes.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.duck.notes.data.settings.createSettingsDataStore
import app.duck.notes.domain.model.SettingsNotice
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsScope

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    @SettingsScope
    fun provideSettingsScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSettingsNotices(): MutableStateFlow<SettingsNotice?> = MutableStateFlow(null)

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
        @SettingsScope scope: CoroutineScope,
        notices: MutableStateFlow<SettingsNotice?>,
    ): DataStore<Preferences> = createSettingsDataStore(
        file = context.preferencesDataStoreFile("settings"),
        scope = scope,
        notices = notices,
    )
}
