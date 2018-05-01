package net.i2p.launchers

import java.net.URL

/**
  * A abstract class is kind of like an java interface.
  *
  * @author Meeh
  * @since 0.9.35
  */
abstract class RouterLauncher {

  def getClassLoader: ClassLoader

  def addJarToClassPath(url: URL): Boolean

  def runRouter(args: Array[String]): Unit
}
