package app.duck.notes.domain.model

enum class ThemePreference(val storedValue: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
}

enum class LanguagePreference(val languageTag: String) { System(""), English("en"), Arabic("ar") }
enum class EditorFont(val storedValue: String) { Serif("serif"), Sans("sans"), Mono("mono") }

data class AppSettings(
    val theme: ThemePreference = ThemePreference.System,
    val lockEnabled: Boolean = false,
    val lockTimeoutMs: Long = 60_000L,
    val hideRecents: Boolean = true,
    val language: LanguagePreference = LanguagePreference.System,
    val editorFont: EditorFont = EditorFont.Serif,
    val editorSizeSp: Int = 17,
)

enum class SettingsReadStatus { Loading, Ready, TemporaryDefaults }

enum class SettingsNotice { ResetAfterCorruption, TemporaryReadFailure }

data class SettingsSnapshot(
    val settings: AppSettings = AppSettings(),
    val readStatus: SettingsReadStatus = SettingsReadStatus.Loading,
    val notice: SettingsNotice? = null,
)

internal fun effectiveDark(theme: ThemePreference, systemDark: Boolean): Boolean =
    when (theme) {
        ThemePreference.System -> systemDark
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
