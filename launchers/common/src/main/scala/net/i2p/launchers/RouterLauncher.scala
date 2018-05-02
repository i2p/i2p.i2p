package net.i2p.launchers

import java.io.File


/**
  * A abstract class is kind of like an java interface.
  *
  * @author Meeh
  * @since 0.9.35
  */
abstract class RouterLauncher {
  def runRouter(basePath: File, args: Array[String]): Unit

  def runRouter(args: Array[String]): Unit
}
