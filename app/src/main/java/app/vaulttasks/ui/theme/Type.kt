package app.vaulttasks.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.vaulttasks.R

/**
 * Google Sans Flex (SIL OFL 1.1, docs/licenses/OFL-google-sans-flex.txt), one variable file. The ROND axis at 100
 * gives the rounded cut Material 3 Expressive uses; each weight is a separate instance of the same file.
 */
private const val ROUNDNESS = 100f

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: FontWeight) = Font(
    resId = R.font.google_sans_flex,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("ROND", ROUNDNESS),
    ),
)

val AppFont = FontFamily(
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map(::flex),
)

private val base = Typography()

private fun TextStyle.app(weight: FontWeight? = null) =
    copy(fontFamily = AppFont, fontWeight = weight ?: fontWeight)

/** Default M3 scale and sizes, in the rounded face; headlines/titles one step heavier (Expressive "emphasized"). */
val AppTypography = Typography(
    displayLarge = base.displayLarge.app(FontWeight.SemiBold),
    displayMedium = base.displayMedium.app(FontWeight.SemiBold),
    displaySmall = base.displaySmall.app(FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.app(FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.app(FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.app(FontWeight.SemiBold),
    titleLarge = base.titleLarge.app(FontWeight.SemiBold),
    titleMedium = base.titleMedium.app(FontWeight.SemiBold),
    titleSmall = base.titleSmall.app(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.app(),
    bodyMedium = base.bodyMedium.app(),
    bodySmall = base.bodySmall.app(),
    labelLarge = base.labelLarge.app(FontWeight.SemiBold),
    labelMedium = base.labelMedium.app(FontWeight.Medium),
    labelSmall = base.labelSmall.app(FontWeight.Medium),
)
