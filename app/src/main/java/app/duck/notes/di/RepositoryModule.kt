package app.duck.notes.di

import app.duck.notes.data.RealClock
import app.duck.notes.data.repository.AttachmentRepositoryImpl
import app.duck.notes.data.repository.AttachmentStorageImpl
import app.duck.notes.data.repository.LinkRepositoryImpl
import app.duck.notes.data.repository.NoteRepositoryImpl
import app.duck.notes.data.repository.SearchRepositoryImpl
import app.duck.notes.data.repository.TagRepositoryImpl
import app.duck.notes.domain.Clock
import app.duck.notes.domain.repository.AttachmentRepository
import app.duck.notes.domain.repository.AttachmentStorage
import app.duck.notes.domain.repository.LinkRepository
import app.duck.notes.domain.repository.NoteRepository
import app.duck.notes.domain.repository.SearchRepository
import app.duck.notes.domain.repository.TagRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the domain's repository ports to their Room-backed implementations. This module is the only
 * place `domain` and `data` meet — use cases are `@Inject`-constructed and never name an impl.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds
    @Singleton
    abstract fun bindLinkRepository(impl: LinkRepositoryImpl): LinkRepository

    @Binds
    @Singleton
    abstract fun bindAttachmentRepository(impl: AttachmentRepositoryImpl): AttachmentRepository

    @Binds
    @Singleton
    abstract fun bindAttachmentStorage(impl: AttachmentStorageImpl): AttachmentStorage

    @Binds
    abstract fun bindClock(impl: RealClock): Clock
}
