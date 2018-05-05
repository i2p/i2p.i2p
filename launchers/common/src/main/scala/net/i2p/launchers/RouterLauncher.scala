package net.i2p.launchers

import java.io.File

import scala.concurrent.Future
import scala.sys.process.Process


/**
  * A abstract class is kind of like an java interface.
  *
  * @author Meeh
  * @since 0.9.35
  */
abstract class RouterLauncher {
  def runRouter(basePath: File, args: Array[String]): Future[Process]

  def runRouter(args: Array[String]): Future[Process]
}
