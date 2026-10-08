package com.ludomadagascar

import android.content.Context

/**
 * Local cosmetic catalog used by the realtime 3D renderer.
 *
 * Variants change material/FX parameters on authored meshes,
 * so every catalogue item remains a live 3D asset rather than a 2D preview.
 * Economy and game rules remain untouched.
 */
object CosmeticCatalog {
    const val PREFS = "ludomada_cosmetics"
    const val BOARD_KEY = "board"
    const val DIE_KEY = "die"
    const val PAWN_KEY = "pawn"
    const val VFX_KEY = "vfx"

    data class Item(
        val id: String,
        val name: String,
        val detail: String,
        val category: String,
        val premium: Boolean = false
    )

    val boards = listOf(
        Item("royal", "Royal Madagascar", "Bois laqué + feutre cacao + cadre or", "Plateau"),
        Item("obsidian", "Obsidian Night", "Laque noire profonde + cadre argent", "Plateau", premium = true),
        Item("sapphire", "Sapphire Royale", "Laque bleu nuit + cadre or", "Plateau", premium = true)
    )

    val dice = listOf(
        Item("chrome", "White Mercury", "Chrome miroir, pips noirs", "Dé"),
        Item("obsidian", "Obsidian Core", "Chrome noir brillant, pips ivoire", "Dé", premium = true),
        Item("ruby", "Ruby Pulse", "Chrome rubis, pips blancs", "Dé", premium = true),
        Item("emerald", "Emerald Prime", "Chrome émeraude, pips blancs", "Dé", premium = true)
    )

    val pawns = listOf(
        Item("classic", "Classic Lacquer", "Laque premium réaliste", "Pions"),
        Item("chrome", "Chrome Royale", "Métal poli + highlights", "Pions", premium = true),
        Item("onyx", "Onyx Shadow", "Finition sombre métallique", "Pions", premium = true)
    )

    val vfx = listOf(
        Item("heroic", "Flamme Héroïque", "Halo or + braises + énergie blanche", "Effets", premium = true),
        Item("ember", "Ember Inferno", "Anneau rouge/orange + braises", "Effets", premium = true),
        Item("frost", "Frost Nova", "Halo cyan + particules glacées", "Effets", premium = true)
    )

    fun all(): List<Item> = boards + dice + pawns + vfx

    fun selected(context: Context, key: String, fallback: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, fallback) ?: fallback

    fun equip(context: Context, key: String, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, id).apply()
    }

    fun selectedLabel(context: Context): String =
        listOf(
            "Plateau=" + selected(context, BOARD_KEY, "royal"),
            "Dé=" + selected(context, DIE_KEY, "chrome"),
            "Pions=" + selected(context, PAWN_KEY, "classic"),
            "FX=" + selected(context, VFX_KEY, "heroic")
        ).joinToString(" • ")
}
