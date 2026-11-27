package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.example.model.ThemePreference

private val AssetDeskDarkColors = darkColorScheme(
  primary = MutedMint,
  onPrimary = DeepGraphite,
  primaryContainer = MutedMintDim,
  onPrimaryContainer = MutedMint,
  secondary = CoolSteelBlue,
  onSecondary = DeepGraphite,
  secondaryContainer = CoolSteelDim,
  onSecondaryContainer = OffWhiteText,
  tertiary = WarningAmber,
  onTertiary = DeepGraphite,
  tertiaryContainer = WarningAmberDim,
  onTertiaryContainer = WarningAmber,
  background = DeepGraphite,
  onBackground = OffWhiteText,
  surface = CharcoalBlue,
  onSurface = OffWhiteText,
  surfaceVariant = CharcoalElevated,
  onSurfaceVariant = MutedSlateText,
  surfaceContainerLowest = DeepGraphite,
  surfaceContainerLow = CharcoalBlue,
  surfaceContainer = CharcoalElevated,
  surfaceContainerHigh = CharcoalHover,
  outline = SubtleSlateBorder,
  outlineVariant = SubtleSlateBorder,
  error = ErrorCoral,
  onError = DeepGraphite,
  errorContainer = ErrorCoralDim,
  onErrorContainer = ErrorCoral,
)

private val AssetDeskLightColors = lightColorScheme(
  primary = LightMintAccent,
  onPrimary = LightSurface,
  primaryContainer = MutedMintDim,
  onPrimaryContainer = MutedMint,
  secondary = LightSteelAccent,
  onSecondary = LightSurface,
  secondaryContainer = LightSurfaceElevated,
  onSecondaryContainer = LightInkText,
  tertiary = WarningAmber,
  onTertiary = LightInkText,
  background = LightGraphiteBg,
  onBackground = LightInkText,
  surface = LightSurface,
  onSurface = LightInkText,
  surfaceVariant = LightSurfaceElevated,
  onSurfaceVariant = LightMutedText,
  surfaceContainerLowest = LightSurface,
  surfaceContainerLow = LightGraphiteBg,
  surfaceContainer = LightSurfaceElevated,
  outline = LightSlateBorder,
  outlineVariant = LightSlateBorder,
  error = ErrorCoral,
  onError = LightSurface,
)

val AssetDeskShapes = Shapes(
  extraSmall = RoundedCornerShape(4.dp),
  small = RoundedCornerShape(6.dp),
  medium = RoundedCornerShape(8.dp),
  large = RoundedCornerShape(10.dp),
  extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun AssetDeskTheme(
  themePreference: ThemePreference = ThemePreference.DARK,
  content: @Composable () -> Unit,
) {
  val useDark =
    when (themePreference) {
      ThemePreference.DARK -> true
      ThemePreference.LIGHT -> false
      ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }

  val colorScheme = if (useDark) AssetDeskDarkColors else AssetDeskLightColors

  MaterialTheme(
    colorScheme = colorScheme,
    typography = AssetDeskTypography,
    shapes = AssetDeskShapes,
    content = content,
  )
}
