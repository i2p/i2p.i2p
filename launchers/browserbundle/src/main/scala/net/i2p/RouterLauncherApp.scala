package net.i2p

import java.io.{File, InputStream}

//import net.i2p.Router
import net.i2p.launchers.DeployProfile

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
  * @author Meeh
  * @version 0.0.1
  * @since 0.9.35
  */
object RouterLauncherApp extends App {

  def toBytes(xs: Int*) = xs.map(_.toByte).toArray

  def getInt(bytes: Array[Byte]): Int = (bytes(3) << 24) & 0xff000000 | (bytes(2) << 16) & 0x00ff0000 | (bytes(1) << 8) & 0x0000ff00 | (bytes(0) << 0) & 0x000000ff

  /**
    * Encodes bytes in "binary form" as mentioned in Native Messaging in the WebExtension API.
    * @param length
    * @return
    */
  def getBytes(length: Int): Array[Byte] = {
    val bytes = new Array[Byte](4)
    bytes(0) = (length & 0xFF).toByte
    bytes(1) = ((length >> 8) & 0xFF).toByte
    bytes(2) = ((length >> 16) & 0xFF).toByte
    bytes(3) = ((length >> 24) & 0xFF).toByte
    bytes
  }

  def readMessage(in: InputStream): String = {
    val arr = new Array[Byte](4)
    in.read(arr)
    val bytes = new Array[Byte](getInt(arr))
    in.read(bytes)
    new String(bytes, "UTF-8")
  }

  def sendMessage(message: String): Unit = {
    System.out.write(getBytes(message.length))
    System.out.write(message.getBytes("UTF-8"))
  }

  // Executed at launch
  val basePath = Option(System.getProperty("i2p.dir.base")).getOrElse(System.getenv("I2PBASE"))
  val configPath = Option(System.getProperty("i2p.dir.config")).getOrElse(System.getenv("I2PCONFIG"))

  println(s"basePath => ${basePath}\nconfigPath => ${configPath}")

  object ErrorUtils {
    def errorMessageInJson(message: String, solution: String) : JObject = JObject(
      List(
        ("error",
          JObject(
            ("message", JString(message)),
            ("solution", JString(solution))
          )
        )
      )
    )

    def printError(message: String, solution: String): Unit = {
      println(compact(render( errorMessageInJson(message,solution) )))
    }

    def printErrorAndExit(message: String, solution: String, exitCode: Int = 1): Unit = {
      printError(message, solution)
      System.exit(exitCode)
    }
  }

  // Path related error checking
  if (basePath == null || basePath.isEmpty) ErrorUtils.printErrorAndExit("I2P Base path is missing", "set property i2p.dir.base or environment variable I2PBASE")
  if (configPath  == null || configPath.isEmpty) ErrorUtils.printErrorAndExit("I2P Config path is missing", "set property i2p.dir.config or environment variable I2PCONFIG")
  if (!new File(basePath).exists()) ErrorUtils.printErrorAndExit("I2P Base path don't exist", "Reinstall the Browser Bundle")
  if (!new File(configPath).exists()) ErrorUtils.printErrorAndExit("I2P Config path don't exist", "Delete your config directory for the Browser Bundle")


  val deployer = new DeployProfile(configPath,basePath)
  deployer.verifyExistenceOfConfig()

  // Required/mocked properties
  System.setProperty("wrapper.version", "portable-1")
  System.setProperty("i2p.dir.portableMode", "true")
  System.setProperty("loggerFilenameOverride", "router-@.log")

  //ErrorUtils.printError(s"Starting up with arguments ${(args mkString ", ")}",":)")

  //Router.main(args)
}


