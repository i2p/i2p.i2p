package net.i2p.launchers

import java.io.File

/**
  * Defaults object for Mac OS X
  *
  *
  * @author Meeh
  * @since 0.9.35
  */
object OSXDefaults {

  def getOSXConfigDirectory = new File(DeployProfile.pathJoin(System.getProperty("user.home"), "Library/Application Support/i2p"))

  def getOSXBaseDirectory = new File(DeployProfile.pathJoin(System.getProperty("user.home"),"Library/I2P"))

}
