package net.i2p.router.update

import org.scalatest.FunSpec

import java.util.Collections
import java.util.Set

import net.i2p.update.UpdateMethod
import net.i2p.update.UpdateType
import net.i2p.update.Updater

/**
 * @author str4d
 */
trait UpdaterBehaviors { this: FunSpec =>
    def updater(newUpdater: => Updater, validTypes: => Set<UpdateType>,
                validMethods: => Set<UpdateMethod>) {
        it("should return null if no updateSources are provided") {
            val updateSources = Collections.emptyList
            val updateTask = newUpdater.update(validTypes[0], validMethods[0],
                                               updateSources, "", "", 1000)
            updateTask should be (null)
        }
    }
}
