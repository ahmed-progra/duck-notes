package app.duck.notes.data.local

import androidx.room.TypeConverter

/** Room type converters. Kept tiny — only [SyncState] needs one this phase. */
class Converters {
    @TypeConverter
    fun syncStateToString(state: SyncState): String = state.name

    @TypeConverter
    fun stringToSyncState(value: String): SyncState = SyncState.valueOf(value)
}
