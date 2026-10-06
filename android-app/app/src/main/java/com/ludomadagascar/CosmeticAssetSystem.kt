package com.ludomadagascar

import android.content.Context
import org.json.JSONObject

/**
 * Runtime cosmetic catalog for the 3D board.
 *
 * Game rules/state are deliberately absent from this layer: it only selects visual
 * representations and effect parameters. Procedural fallbacks are always available,
 * while assetPath fields reserve a stable slot for licensed GLB/glTF imports later.
 */
object CosmeticAssetSystem {
    private const val PREFS = "ludomada_cosmetics"
    private const val BOARD_KEY = "board"
    private const val DICE_KEY = "dice"
    private const val PAWN_KEY = "pawn"
    private const val VFX_KEY = "vfx"

    data class BoardStyle(
        val id: String,
        val title: String,
        val subtitle: String,
        val tableColor: Int,
        val frameColor: Int,
        val surfaceColor: Int,
        val pathColor: Int,
        val trimColor: Int,
        val zoneColors: IntArray,
        val assetPath: String? = null
    )

    data class DiceStyle(
        val id: String,
        val title: String,
        val subtitle: String,
        val bodyColor: Int,
        val pipColor: Int,
        val trayColor: Int,
        val roughness: Float,
        val metallic: Float,
        val chrome: Float,
        val emission: Float,
        val rollDegrees: Float,
        val assetPath: String? = null
    )

    data class PawnStyle(
        val id: String,
        val title: String,
        val subtitle: String,
        val colors: IntArray,
        val roughness: Float,
        val metallic: Float,
        val emission: Float,
        val scale: Float = 1f,
        val assetPath: String? = null
    )

    data class VfxStyle(
        val id: String,
        val title: String,
        val subtitle: String,
        val dustColors: IntArray,
        val burstColors: IntArray,
        val dustLife: Float,
        val dustSize: Float,
        val dustGravity: Float,
        val burstCount: Int,
        val burstLife: Float
    )

    data class Snapshot(
        val board: BoardStyle,
        val dice: DiceStyle,
        val pawn: PawnStyle,
        val vfx: VfxStyle
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("board", board.id)
            .put("dice", dice.id)
            .put("pawn", pawn.id)
            .put("vfx", vfx.id)
    }

    private val classicZones = intArrayOf(
        rgb(219, 59, 66), rgb(48, 165, 92), rgb(239, 186, 46), rgb(59, 112, 219)
    )

    val boards = listOf(
        BoardStyle(
            "madagascar", "Madagascar", "Bois chaud • or • ivoire",
            rgb(71, 36, 14), rgb(122, 61, 20), rgb(224, 202, 156),
            rgb(241, 225, 185), rgb(194, 146, 56), classicZones
        ),
        BoardStyle(
            "royal_gold", "Royal Gold", "Noir profond • or premium",
            rgb(24, 20, 18), rgb(78, 48, 16), rgb(218, 183, 93),
            rgb(244, 230, 192), rgb(239, 183, 63),
            intArrayOf(rgb(207, 65, 72), rgb(44, 166, 126), rgb(237, 174, 57), rgb(72, 114, 222))
        ),
        BoardStyle(
            "ocean_night", "Ocean Night", "Bleu nuit • chrome • cyan",
            rgb(8, 24, 48), rgb(17, 55, 87), rgb(123, 166, 194),
            rgb(220, 236, 245), rgb(69, 184, 219),
            intArrayOf(rgb(228, 74, 96), rgb(52, 193, 133), rgb(246, 194, 67), rgb(76, 132, 238))
        ),
        BoardStyle(
            "baobab_sunset", "Baobab Sunset", "Terre rouge • sable • coucher de soleil",
            rgb(78, 35, 20), rgb(128, 58, 24), rgb(218, 174, 120),
            rgb(248, 222, 178), rgb(229, 139, 55),
            intArrayOf(rgb(214, 73, 67), rgb(49, 169, 119), rgb(241, 191, 62), rgb(76, 113, 216))
        )
    )

    val dice = listOf(
        DiceStyle("chrome", "Chrome", "Hydro-chrome argent", rgb(232, 236, 240), rgb(14, 18, 23), rgb(58, 63, 70), .055f, 1f, .98f, .045f, 720f),
        DiceStyle("heroic", "Heroic", "Chrome + énergie dorée", rgb(241, 229, 197), rgb(30, 21, 10), rgb(92, 54, 18), .075f, .94f, .92f, .085f, 1120f),
        DiceStyle("crystal", "Crystal", "Cristal glacé", rgb(206, 237, 248), rgb(13, 42, 55), rgb(38, 91, 112), .10f, .68f, .72f, .12f, 860f),
        DiceStyle("obsidian", "Obsidian", "Noir miroir", rgb(42, 48, 57), rgb(239, 242, 245), rgb(14, 17, 22), .07f, .93f, .90f, .055f, 980f)
    )

    val pawns = listOf(
        PawnStyle("lacquer", "Lacquer", "Classique premium", classicZones, .22f, .04f, .015f),
        PawnStyle("gold", "Gold", "Métal doré", intArrayOf(rgb(222, 71, 61), rgb(64, 181, 124), rgb(246, 196, 65), rgb(68, 119, 232)), .10f, .78f, .055f),
        PawnStyle("gem", "Gem", "Gemme brillante", intArrayOf(rgb(246, 67, 90), rgb(47, 218, 157), rgb(255, 210, 68), rgb(77, 143, 255)), .075f, .56f, .085f),
        PawnStyle("obsidian", "Obsidian", "Laque sombre", intArrayOf(rgb(202, 61, 70), rgb(45, 157, 115), rgb(218, 174, 51), rgb(66, 101, 184)), .14f, .62f, .025f)
    )

    val vfx = listOf(
        VfxStyle("classic", "Classic", "Poussière discrète", classicZones, intArrayOf(rgb(255, 203, 58), rgb(255, 239, 176)) + classicZones, .45f, 1f, 1.25f, 56, .80f),
        VfxStyle("spark", "Spark", "Étincelles premium", intArrayOf(rgb(255, 216, 87), rgb(145, 225, 255), rgb(255, 130, 72), rgb(255, 239, 170)), intArrayOf(rgb(255, 234, 109), rgb(255, 255, 255), rgb(92, 205, 255)), .55f, 1.35f, 1.05f, 72, .92f),
        VfxStyle("flame", "Flame", "Burst chaud", intArrayOf(rgb(255, 94, 48), rgb(255, 180, 46), rgb(255, 224, 105), rgb(230, 56, 40)), intArrayOf(rgb(255, 65, 35), rgb(255, 151, 45), rgb(255, 231, 121)), .42f, 1.45f, 1.65f, 80, .86f),
        VfxStyle("ice", "Ice", "Éclats froids", intArrayOf(rgb(91, 221, 255), rgb(154, 239, 255), rgb(186, 216, 255), rgb(78, 145, 255)), intArrayOf(rgb(164, 245, 255), rgb(240, 252, 255), rgb(110, 190, 255)), .62f, 1.20f, .80f, 64, 1.02f)
    )

    fun snapshot(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            board = boards.byId(p.getString(BOARD_KEY, boards.first().id)),
            dice = dice.byId(p.getString(DICE_KEY, dice.first().id)),
            pawn = pawns.byId(p.getString(PAWN_KEY, pawns.first().id)),
            vfx = vfx.byId(p.getString(VFX_KEY, vfx.first().id))
        )
    }

    fun setBoard(context: Context, id: String) = set(context, BOARD_KEY, boards.byId(id).id)
    fun setDice(context: Context, id: String) = set(context, DICE_KEY, dice.byId(id).id)
    fun setPawn(context: Context, id: String) = set(context, PAWN_KEY, pawns.byId(id).id)
    fun setVfx(context: Context, id: String) = set(context, VFX_KEY, vfx.byId(id).id)

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun set(context: Context, key: String, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, id).apply()
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = android.graphics.Color.rgb(r, g, b)
}

private fun List<CosmeticAssetSystem.BoardStyle>.byId(id: String?): CosmeticAssetSystem.BoardStyle =
    firstOrNull { it.id == id } ?: first()

private fun List<CosmeticAssetSystem.DiceStyle>.byId(id: String?): CosmeticAssetSystem.DiceStyle =
    firstOrNull { it.id == id } ?: first()

private fun List<CosmeticAssetSystem.PawnStyle>.byId(id: String?): CosmeticAssetSystem.PawnStyle =
    firstOrNull { it.id == id } ?: first()

private fun List<CosmeticAssetSystem.VfxStyle>.byId(id: String?): CosmeticAssetSystem.VfxStyle =
    firstOrNull { it.id == id } ?: first()
