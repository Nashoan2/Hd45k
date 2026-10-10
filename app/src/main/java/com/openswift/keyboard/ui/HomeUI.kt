package com.openswift.keyboard.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openswift.keyboard.R
import com.openswift.keyboard.theme.KbTheme

@Composable
fun HomeUI(
    theme: KbTheme,
    bgColor: Color,
    textColor: Color,
    accentColor: Color,
    onBrowseThemes: () -> Unit,
) {
    val context = LocalContext.current
    val buttonTextColor = if (accentColor.luminance() > 0.54f) Color.Black else Color.White
    val lifecycleOwner = LocalLifecycleOwner.current

    var isEnabled by remember { mutableStateOf(false) }
    var isSelected by remember { mutableStateOf(false) }
    var testInputText by remember { mutableStateOf("") }

    fun refreshImeState() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        isEnabled = imm?.enabledInputMethodList.orEmpty().any { it.packageName == context.packageName }
        val defaultIme = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).orEmpty()
        isSelected = defaultIme.contains(context.packageName)
    }

    DisposableEffect(lifecycleOwner) {
        refreshImeState()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshImeState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header with gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.12f),
                            bgColor,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Image(
                    painter = painterResource(R.drawable.openswift_brand),
                    contentDescription = "OpenSwift app icon",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(78.dp),
                )
                Text(
                    "OpenSwift",
                    style = AppTypography.displayMedium,
                    color = textColor,
                )
                Text(
                    "اكتب بسرعة وسلاسة مع خصوصية تامة لكل ضغطة مفتاح.",
                    style = AppTypography.bodyMedium,
                    color = textColor.copy(alpha = 0.7f),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            // Setup & Activation Section
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = accentColor.copy(alpha = 0.08f),
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "خطوات تفعيل لوحة المفاتيح",
                        style = AppTypography.headlineSmall,
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                    )

                    // Step 1: Enable
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isEnabled) Color(0xFF4CAF50).copy(alpha = 0.2f) else textColor.copy(alpha = 0.1f),
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isEnabled) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "مفعلة",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Text(
                                        "1",
                                        style = AppTypography.labelMedium,
                                        color = textColor.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "1. تفعيل OpenSwift في النظام",
                                style = AppTypography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor,
                            )
                            Text(
                                if (isEnabled) "تم التفعيل بنجاح" else "مطلوب لتشغيل اللوحة على الجهاز",
                                style = AppTypography.bodySmall,
                                color = if (isEnabled) Color(0xFF4CAF50) else textColor.copy(alpha = 0.65f),
                            )
                        }
                        if (!isEnabled) {
                            Button(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("تفعيل", fontSize = 12.sp, color = buttonTextColor)
                            }
                        }
                    }

                    HorizontalDivider(color = textColor.copy(alpha = 0.1f))

                    // Step 2: Select
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) Color(0xFF4CAF50).copy(alpha = 0.2f) else textColor.copy(alpha = 0.1f),
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "محددة",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Text(
                                        "2",
                                        style = AppTypography.labelMedium,
                                        color = textColor.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "2. اختيار OpenSwift كاللوحة الحالية",
                                style = AppTypography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor,
                            )
                            Text(
                                if (isSelected) "OpenSwift هي اللوحة النشطة حالياً" else "قم بالتبديل إليها للبدء في استخدامها",
                                style = AppTypography.bodySmall,
                                color = if (isSelected) Color(0xFF4CAF50) else textColor.copy(alpha = 0.65f),
                            )
                        }
                        Button(
                            onClick = {
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.showInputMethodPicker()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) textColor.copy(alpha = 0.15f) else accentColor,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                if (isSelected) "تبديل" else "اختيار",
                                fontSize = 12.sp,
                                color = if (isSelected) textColor else buttonTextColor,
                            )
                        }
                    }
                }
            }

            // Step 3: Interactive Live Typing Sandbox
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = textColor.copy(alpha = 0.04f),
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, textColor.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_keyboard_return),
                            contentDescription = "تجربة الكتابة",
                            tint = accentColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "تجربة الكتابة المباشرة",
                            style = AppTypography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                        )
                    }
                    Text(
                        "انقر أدناه لتجربة لوحة المفاتيح، السحب السريع، والتنبؤ الذكي مباشرة داخل التطبيق:",
                        style = AppTypography.bodySmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                    OutlinedTextField(
                        value = testInputText,
                        onValueChange = { testInputText = it },
                        placeholder = {
                            Text(
                                "اكتب هنا لتجربة OpenSwift...",
                                style = AppTypography.bodyMedium,
                                color = textColor.copy(alpha = 0.45f),
                            )
                        },
                        trailingIcon = {
                            if (testInputText.isNotEmpty()) {
                                IconButton(onClick = { testInputText = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "مسح",
                                        tint = textColor.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = textColor.copy(alpha = 0.2f),
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                        ),
                        shape = Shapes.md,
                    )
                }
            }

            // Keyboard Preview
            var previewLayout by remember { mutableStateOf("arabic") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "معاينة لوحة المفاتيح",
                    style = AppTypography.headlineSmall,
                    color = textColor,
                    modifier = Modifier.padding(start = Spacing.sm),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = previewLayout == "arabic",
                        onClick = { previewLayout = "arabic" },
                        label = { Text("العربية", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor.copy(alpha = 0.2f),
                            selectedLabelColor = accentColor,
                        ),
                    )
                    FilterChip(
                        selected = previewLayout == "123",
                        onClick = { previewLayout = "123" },
                        label = { Text("123", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor.copy(alpha = 0.2f),
                            selectedLabelColor = accentColor,
                        ),
                    )
                    FilterChip(
                        selected = previewLayout == "qwerty",
                        onClick = { previewLayout = "qwerty" },
                        label = { Text("English", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor.copy(alpha = 0.2f),
                            selectedLabelColor = accentColor,
                        ),
                    )
                }
            }
            KeyboardPreview(theme, modifier = Modifier.fillMaxWidth(), layoutId = previewLayout)

            Text(
                "مصممة للخصوصية التامة والسرعة",
                style = AppTypography.headlineSmall,
                color = textColor,
                modifier = Modifier.padding(start = Spacing.sm),
            )

            FeatureCard(
                icon = Icons.AutoMirrored.Filled.Send,
                title = "الكتابة بالسحب السريع",
                description = "مرر إصبعك فوق الحروف لكتابة الكلمات بسرعة دون اتصال بالإنترنت.",
                accentColor = accentColor,
                textColor = textColor,
            )

            FeatureCard(
                icon = Icons.Filled.Lock,
                title = "خصوصية تامة بدون إنترنت",
                description = "بدون إذن إنترنت، بدون إعلانات، وبدون تتبع أو تسجيل حساب.",
                accentColor = accentColor,
                textColor = textColor,
            )

            FeatureCard(
                icon = Icons.Filled.Star,
                title = "تخصيص متكامل",
                description = "مظاهر متعددة، دعم العربية والإنجليزية، وتخصيص لكل تطبيق.",
                accentColor = accentColor,
                textColor = textColor,
            )

            FeatureCard(
                icon = Icons.AutoMirrored.Filled.List,
                title = "العربية والإنجليزية",
                description = "لوحة مفاتيح سريعة وخفيفة موفرة للبطارية تدعم اللغتين.",
                accentColor = accentColor,
                textColor = textColor,
            )

            OutlinedButton(
                onClick = onBrowseThemes,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = Shapes.md,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
            ) {
                Text(
                    "استكشاف المظاهر والإعدادات",
                    style = AppTypography.labelLarge,
                    color = accentColor,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
