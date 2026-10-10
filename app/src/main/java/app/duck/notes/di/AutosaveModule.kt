package app.duck.notes.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AutosaveDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AutosaveModule {
    @Provides
    @AutosaveDispatcher
    fun provideAutosaveDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}
