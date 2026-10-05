package com.ludomadagascar

/**
 * Runtime-neutral PBR tuning contract shared by the visual lanes.
 * Values are inputs for the renderer/material adapter only.
 */
data class LudoPbrProfile(
    val metalDieMetallic: Float = 0.95f,
    val metalDieRoughness: Float = 0.08f,
    val pawnMetallic: Float = 0.14f,
    val pawnRoughness: Float = 0.20f,
    val pawnClearCoat: Float = 0.35f,
    val tableMetallic: Float = 0.18f,
    val tableRoughness: Float = 0.28f,
    val boardRoughness: Float = 0.82f,
    val exposure: Float = 0.0f,
    val bloomEnabled: Boolean = true,
    val ssaoEnabled: Boolean = true,
    val contactShadowsEnabled: Boolean = true
)
