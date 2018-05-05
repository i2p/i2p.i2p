package net.i2p.launchers.osx

import java.io.File
import java.lang.reflect.Field

import scala.sys.process.Process
import net.i2p.launchers.RouterLauncher

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  *
  *
  * @author Meeh
  * @since 0.9.35
  */
object MacOSXRouterLauncher extends RouterLauncher {

  def pid(p: Process): Long = {
    val procField = p.getClass.getDeclaredField("p")
    procField.synchronized {
      procField.setAccessible(true)
      val proc = procField.get(p)
      try {
        proc match {
          case unixProc
            if unixProc.getClass.getName == "java.lang.UNIXProcess" => {
            val pidField = unixProc.getClass.getDeclaredField("pid")
            pidField.synchronized {
              pidField.setAccessible(true)
              try {
                pidField.getLong(unixProc)
              } finally {
                pidField.setAccessible(false)
              }
            }
          }
          case procImpl:java.lang.Process => {
            val f: Field = p.getClass().getDeclaredField("p")
            val f2: Field = f.get(p).getClass.getDeclaredField("pid")
            try {
              f.setAccessible(true)
              f2.setAccessible(true)
              val pid = f2.getLong(p)
              pid
            } finally {
              f2.setAccessible(false)
              f.setAccessible(false)
            }
          }
          // If someone wants to add support for Windows processes,
          // this would be the right place to do it:
          case _ => throw new RuntimeException(
            "Cannot get PID of a " + proc.getClass.getName)
        }
      } finally {
        procField.setAccessible(false)
      }
    }
  }


  // ??? equals "throw not implemented" IIRC - it compiles at least :)
  override def runRouter(args: Array[String]): Future[Process] = ???

  def runRouter(basePath: File, args: Array[String]): Future[Process] = {
    Future {
      lazy val javaOpts = Seq(
        "-Xmx512M",
        "-Xms128m",
        "-Djava.awt.headless=true",
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
      pb.run
    }
  }
}

