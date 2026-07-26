package app.duck.notes.core.icons

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import app.duck.notes.R

/**
 * The glyph half of [DuckIcon], kept in its own file so the resolver next door stays Compose-free and
 * therefore unit-testable on the JVM.
 *
 * The `when` is exhaustive over the enum, so adding an icon without a drawable will not compile — the
 * cheapest possible totality check, and the reason `DuckIconTest` only has to assert the ids are real.
 */
@DrawableRes
fun DuckIcon.drawable(): Int = when (this) {
    DuckIcon.Todo -> R.drawable.duckicon_todo
    DuckIcon.Work -> R.drawable.duckicon_work
    DuckIcon.Home -> R.drawable.duckicon_home
    DuckIcon.Idea -> R.drawable.duckicon_idea
    DuckIcon.Book -> R.drawable.duckicon_book
    DuckIcon.Code -> R.drawable.duckicon_code
    DuckIcon.People -> R.drawable.duckicon_people
    DuckIcon.Tag -> R.drawable.duckicon_tag
}

/**
 * Paints a [DuckIcon] in [tint].
 *
 * [tint] is required, not defaulted, and **that is load-bearing — do not give it a default.** Two
 * things depend on it:
 *
 * 1. An icon is a WCAG 1.4.11 graphic owing 3:1 against whatever it sits on, and that pair depends on
 *    the container. Making the caller name the token keeps the decision where `ContrastTest` can be
 *    told about it, instead of hiding a default here.
 * 2. It is the only reason Material's disabled path cannot reach a Duck glyph. `IconButton` provides
 *    `LocalContentColor` composited at **0.38** to its content, and this function never reads it: it
 *    paints through foundation's `Image` with an unconditional `ColorFilter.tint`, not material3's
 *    `Icon`. `DuckIconButton` supplies its own opaque disabled tint instead. A default here — and
 *    `Color.Unspecified` is the natural one to reach for — would tint with a sentinel and remove the
 *    forcing function that keeps every glyph colour a measured token.
 *
 * Compiler-enforced is not the same as understood, which is why this is written down.
 */
@Composable
fun DuckIconImage(
    icon: DuckIcon,
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(icon.drawable()),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint),
    )
}
