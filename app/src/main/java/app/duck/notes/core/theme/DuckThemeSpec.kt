package app.duck.notes.core.theme

/**
 * The four named themes. One [DuckColors] per mode each, so eight palettes share one contract.
 *
 * `Sepia` and `Midnight` are authored, not borrowed. A Solarized tuned to pass WCAG AA is not
 * Solarized — its published values are the whole point of it — and Dieci's would have been invented.
 * Naming them for what they are keeps the palette honest and keeps `ContrastTest` free of per-theme
 * exemptions, which would make the criterion mean less than it appears to.
 */
enum class DuckThemeSpec(
    val label: String,
    val light: DuckColors,
    val dark: DuckColors,
) {
    DuckRed("Duck Red", DuckRedLight, DuckRedDark),
    Graphite("Graphite", GraphiteLight, GraphiteDark),
    Sepia("Sepia", SepiaLight, SepiaDark),
    Midnight("Midnight", MidnightLight, MidnightDark),
    ;

    fun colors(isDark: Boolean): DuckColors = if (isDark) dark else light
}
