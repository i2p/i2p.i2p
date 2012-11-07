package net.i2p.router.update

import org.scalatest.FunSpec

import java.net.URI
import java.util.Collections
import java.util.List

import net.i2p.update.UpdateMethod
import net.i2p.update.UpdateType
import net.i2p.update.Updater

/**
 * @author str4d
 */
trait UpdaterBehaviors { this: FunSpec =>
    def updater(newUpdater: => Updater, validTypes: => List[UpdateType],
                validMethods: => List[UpdateMethod]) {
        it("should return null if no updateSources are provided") {
            val updateSources = Collections.emptyList[URI]
            val updateTask = newUpdater.update(validTypes.iterator().next(),
                                               validMethods.iterator().next(),
                                               updateSources, "", "", 1000)
            assert(updateTask == null)
        }
    }
}
