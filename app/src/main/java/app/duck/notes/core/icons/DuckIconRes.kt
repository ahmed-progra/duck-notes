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
 * [tint] is required, not defaulted: an icon is a WCAG 1.4.11 graphic owing 3:1 against whatever it
 * sits on, and that pair depends on the container. Making the caller name the token keeps the
 * decision where `ContrastTest` can be told about it, instead of hiding a default here.
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
