package net.i2p.router.web;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;

/**
 * Simple helper to query the appropriate router for data necessary to render
 * any emergency notices 
 */
public class NoticeHelper extends HelperBase {
    public String getSystemNotice() {
        if (true) return ""; // moved to the left hand nav
        if (_context.router().gracefulShutdownInProgress()) {
            long remaining = _context.router().getShutdownTimeRemaining();
            if (remaining > 0)
                return "Graceful shutdown in " + DataHelper.formatDuration(remaining);
            else
                return "Graceful shutdown imminent, please be patient as state is written to disk";
        } else {
            return "";
        }
    }
}
