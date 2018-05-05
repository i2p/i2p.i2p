package net.i2p.launchers.osx

import java.awt.SystemTray
import java.io.File

import net.i2p.launchers.{CompleteDeployment, OSXDefaults}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.sys.process.Process
import scala.util.{Failure, Success}

/**
  *
  * For java developers:
  * A scala object is like an instance of a class.
  * If you define a method inside an object, it's equals to
  * java's static methods.
  *
  * Also, in scala, the body of a class/object is executed as it's
  * constructor.
  *
  * Also noteworthy;
  * val is immutable
  * var is mutable
  *
  *
  * The i2p base directory in the build should be in a relative path from
  * the launcher, which would be ../Resources/i2pbase - this directory would
  * need to be copied out to a "writable" area, since we're in a signed "immutable"
  * bundle. First this launcher will check if i2pbase is already deployed to a
  * writable area, if it's not, it deploys, if the i2pbase directory has an older
  * version than the one in the bundle, it upgrades. It does nothing if versions
  * matches.
  *
  *
  * @author Meeh
  * @version 0.0.1
  * @since 0.9.35
  */
object LauncherAppMain extends App {

  val i2pBaseDir = OSXDefaults.getOSXBaseDirectory

  val selfDirPath = new File(getClass().getProtectionDomain().getCodeSource().getLocation().getPath).getParentFile

  // Tricky to get around, but feels hard to use a "var" which means mutable..
  // It's like cursing in the church... Worse.
  var sysTray: Option[SystemTrayManager] = None

  val deployment = new CompleteDeployment(new File(selfDirPath.getPath, "../Resources/i2pbase.zip"), i2pBaseDir)

  val depProc = deployment.makeDeployment
  // Change directory to base dir
  System.setProperty("user.dir", i2pBaseDir.getAbsolutePath)

  // System shutdown hook
  sys.ShutdownHookThread {
    println("exiting launcher process")
  }

  Await.ready(depProc, 60000 millis)

  println("I2P Base Directory Extracted.")

  try {
    val routerProcess: Future[Process] = MacOSXRouterLauncher.runRouter(i2pBaseDir, args)

    if (SystemTray.isSupported) {
      sysTray = Some(new SystemTrayManager)
    }

    routerProcess onComplete {
      case Success(forkResult) => {
        println(s"Router started successfully!")
        try {
          val routerPID = MacOSXRouterLauncher.pid(forkResult)
          println(s"PID is ${routerPID}")
        } catch {
          case ex:java.lang.RuntimeException => println(s"Minor error: ${ex.getMessage}")
        }
        if (!sysTray.isEmpty) sysTray.get.setRunning(true)
      }
      case Failure(fail) => {
        println(s"Router failed to start, error is: ${fail.toString}")
      }
    }

    //Await.result(routerProcess, 5000 millis)

  } finally {
    System.out.println("Exit?")
  }
}
