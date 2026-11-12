package com.ludomadagascar.supreme.model

import androidx.compose.ui.graphics.Color

enum class PlayerColor(
    val displayName: String,
    val primaryColor: Color,
    val darkColor: Color,
    val lightColor: Color,
    val startSquareIndex: Int, // 0..51 global board track index
    val homeEntryIndex: Int    // last track square before entering colored stretch
) {
    RED(
        displayName = "Red",
        primaryColor = Color(0xFFE22026),
        darkColor = Color(0xFFA11419),
        lightColor = Color(0xFFFF8B8F),
        startSquareIndex = 1,
        homeEntryIndex = 51
    ),
    GREEN(
        displayName = "Green",
        primaryColor = Color(0xFF00A348),
        darkColor = Color(0xFF006B2E),
        lightColor = Color(0xFF64E297),
        startSquareIndex = 14,
        homeEntryIndex = 12
    ),
    YELLOW(
        displayName = "Yellow",
        primaryColor = Color(0xFFFFDE18),
        darkColor = Color(0xFFB89E00),
        lightColor = Color(0xFFFFF58A),
        startSquareIndex = 27,
        homeEntryIndex = 25
    ),
    BLUE(
        displayName = "Blue",
        primaryColor = Color(0xFF00A0F0),
        darkColor = Color(0xFF0067A3),
        lightColor = Color(0xFF8ADCFF),
        startSquareIndex = 40,
        homeEntryIndex = 38
    )
}
