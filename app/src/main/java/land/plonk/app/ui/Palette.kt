package land.plonk.app.ui

/**
 * Colours for the app's own screens. They sit around the game, not inside it, so they follow the
 * splash: the near-black #06070c page and the gold and lava of the PLONK logo letters.
 */
internal object Palette {
    const val BG = 0xFF06070C.toInt()

    /** BG with zero alpha. Fading to plain transparent black would leave a grey band. */
    const val BG_CLEAR = 0x0006070C

    const val TITLE = 0xFFF3ECDA.toInt()
    const val BODY = 0xFFA39E8C.toInt()
    const val HINT = 0xFF6F6B5D.toInt()

    const val GOLD_LIGHT = 0xFFF4D06C.toInt()
    const val GOLD = 0xFFDDA93C.toInt()
    const val LAVA = 0xFFFF7A2C.toInt()
    const val ON_GOLD = 0xFF1C1606.toInt()

    const val TRACK = 0xFF1C1D17.toInt()
}
