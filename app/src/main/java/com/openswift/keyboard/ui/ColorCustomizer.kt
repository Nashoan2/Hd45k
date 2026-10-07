package com.openswift.keyboard.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openswift.keyboard.theme.KbTheme
import com.openswift.keyboard.theme.Themes

@Composable
fun ColorCustomizer(
    currentThemeId: String,
    onThemeChange: (String) -> Unit,
    bgColor: Color,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    themes: List<KbTheme> = Themes.all,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            "أنظمة الألوان",
            style = AppTypography.headlineSmall,
            color = textColor,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        themes.forEach { theme ->
            ColorOption(
                theme = theme,
                isSelected = currentThemeId == theme.id,
                onSelect = {
                    onThemeChange(theme.id)
                    Toast.makeText(context, "تم تطبيق سمة: ${theme.displayName} ✓", Toast.LENGTH_SHORT).show()
                },
                textColor = textColor,
                accentColor = accentColor
            )
        }
    }
}

@Composable
fun ColorOption(
    theme: KbTheme,
    isSelected: Boolean,
    onSelect: () -> Unit,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val themeAccent = Color(theme.keyAccent)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .clickable(onClick = onSelect),
        shape = Shapes.md,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                themeAccent.copy(alpha = 0.16f)
            } else {
                Color(0xFF181A20)
            }
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, themeAccent)
        } else {
            BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        },
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) Elevations.md else Elevations.none
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Theme name and radio indicator
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = themeAccent,
                        unselectedColor = textColor.copy(alpha = 0.4f)
                    )
                )

                Text(
                    theme.displayName,
                    style = AppTypography.labelLarge,
                    color = if (isSelected) themeAccent else textColor,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }

            // 4 Color preview swatches on the trailing side
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(theme.background))
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(theme.keyBackground))
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(theme.keyText))
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(theme.keyAccent))
                )
            }
        }
    }
}
