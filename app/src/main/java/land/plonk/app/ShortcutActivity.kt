package land.plonk.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.pm.ShortcutManagerCompat

/**
 * Entry point for the launcher shortcuts (Bag, Market, World map; see res/xml/shortcuts.xml).
 *
 * Android launches static shortcuts with FLAG_ACTIVITY_CLEAR_TASK. Pointed straight at
 * MainActivity, a shortcut would destroy a running game and drop the player's session. This
 * invisible activity runs in a task of its own, forwards the plonk:// link to MainActivity (which
 * is singleTask, so a running game gets it in onNewIntent and opens the window in place), and
 * finishes before it ever draws.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.data
        if (link?.scheme == "plonk") {
            // Lets the launcher rank the shortcuts the player actually uses.
            link.host?.let { ShortcutManagerCompat.reportShortcutUsed(this, it) }
            startActivity(
                Intent(Intent.ACTION_VIEW, link, this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
