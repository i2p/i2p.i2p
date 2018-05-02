package net.i2p.launchers

import java.io.{File, IOException, InputStream}
import java.nio.file.FileAlreadyExistsException


/**
  *
  * This is a singleton ish style. It's an object - it was never a class.
  * However it has a class-"brother/sister"
  *
  * objects like this should be considered like containers for java's
  * public static methods.
  *
  * Many classes/objects benefits from pathJoin, so therefore it's defined
  * as an "public static method".
  *
  * @since 0.9.35
  */
object DeployProfile {


  /**
    * This function will find the executing jar. "myself"
    * @return
    */
  def executingJarFile = getClass().getProtectionDomain().getCodeSource().getLocation()

  /**
    * This joins two paths in a cross platform way. Meaning it takes care of either we use
    * \\ or / as directory separator. It returns the resulting path in a string.
    *
    * @since 0.9.35
    * @param parent The parent path
    * @param child The child path to append
    * @return String
    */
  def pathJoin(parent:String,child:String): String = new File(new File(parent), child).getPath
}

/**
  *
  * The purpose of this class is to copy files from the i2p "default config" directory
  * and to a "current config" directory relative to the browser bundle - but this class is
  * also used by the OSX launcher since it shares common properties like that the bundle has
  * to be read only.
  *
  * @author Meeh
  * @version 0.0.1
  * @since 0.9.35
  */
class DeployProfile(val confDir: String, val baseDir: String) {
  import java.nio.file.{Files, Paths}

  /**
    * This function copies resources from the fatjar to the config directory of i2p.
    *
    * @since 0.9.35
    * @param fStr
    * @return Unit
    */
  def copyFileResToDisk(fStr: String) = try { Files.copy(
    getClass.getResource("/".concat(fStr)).getContent.asInstanceOf[InputStream],
    Paths.get(DeployProfile.pathJoin(confDir, fStr)).normalize()
  )} catch {
    case _:FileAlreadyExistsException => {} // Ignored
    case ex:IOException => println(s"Error! Exception ${ex}")
  }


  /**
    * This function copies resources from the fatjar to the config directory of i2p.
    *
    * @since 0.9.35
    * @param path
    * @param is
    * @return Unit
    */
  def copyBaseFileResToDisk(path: String, is: InputStream) = try { Files.copy(
    is,
    Paths.get(DeployProfile.pathJoin(baseDir, path)).normalize()
  )} catch {
    case _:FileAlreadyExistsException => {} // Ignored
    case ex:IOException => println(s"Error! Exception ${ex}")
  }

  /**
    * Filter function for finding missing required files.
    *
    * @since 0.9.35
    * @param l1
    * @param l2
    * @return
    */
  def missingFiles(l1: List[String], l2: List[String]) = l1.filter { x => !l2.contains(x) }


  val warFiles = List("routerconsole.war")

  val staticFiles = List(
    "blocklist.txt",
    "clients.config",
    "continents.txt",
    "countries.txt",
    "hosts.txt",
    "geoip.txt",
    "i2ptunnel.config",
    "logger.config",
    "router.config",
    "webapps.config"
  )

  /**
    *
    * This function will check the existence of static files,
    * and if any of them are lacking, it will be copied from the
    * fat jar's resources.
    *
    * @since 0.9.35
    * @return Unit (Null)
    */
  def verifyExistenceOfConfig() = {
    val fDir = new File(confDir)
    if (fDir.exists()) {
      // We still check if files are in place
      val currentDirContentList = fDir.list.toList
      val missing = missingFiles(staticFiles, currentDirContentList)
      if (!missing.isEmpty) {
        missing.map(copyFileResToDisk)
      }
    } else {
      // New deployment!
      deployDefaultConfig()
    }
  }

  /**
    *
    * This function does the default deployment of files,
    * map is the same as a loop. we're looping over the file list.
    *
    * @since 0.9.35
    * @return Unit
    */
  def deployDefaultConfig(): Unit = staticFiles.map(copyFileResToDisk)

}
