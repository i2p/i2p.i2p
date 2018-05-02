package net.i2p.launchers.osx

import java.io.File

import scala.sys.process.Process
import net.i2p.launchers.RouterLauncher

/**
  *
  *
  * @author Meeh
  * @since 0.9.35
  */
object MacOSXRouterLauncher extends RouterLauncher {

  override def runRouter(args: Array[String]): Unit = {}

  def runRouter(basePath: File, args: Array[String]): Unit = {
    lazy val javaOpts = Seq(
      "-Xmx512M",
      "-Xms128m",
      "-Dwrapper.logfile=/tmp/router.log",
      "-Dwrapper.logfile.loglevel=DEBUG",
      "-Dwrapper.java.pidfile=/tmp/routerjvm.pid",
      "-Dwrapper.console.loglevel=DEBUG",
      s"-Di2p.dir.base=${basePath}",
      s"-Djava.library.path=${basePath}"
    )
    val javaOptsString = javaOpts.map(_ + " ").mkString
    val cli = s"""java -cp "${new File(basePath, "lib").listFiles().map{f => f.toPath.toString.concat(":")}.mkString}." ${javaOptsString} net.i2p.router.Router"""
    println(s"CLI => ${cli}")
    val pb = Process(cli)
    // Use "run" to let it fork in behind
    val exitCode = pb.!
  }
}

