package net.i2p.launchers

import java.io.{File, IOException}
import java.util.zip.ZipFile
import collection.JavaConverters._

/**
  *
  * OSXDeployment
  *
  * This class can be a bit new for java developers. In Scala, when inherit other classes,
  * you would need to define their arguments if the super class only has constructors taking arguments.
  *
  * This child class don't take arguments, but rather get them from the signleton OSXDefaults.
  *
  * This class should be able to copy recursive resources into correct position for a normal deployment.
  *
  *
  * This class might look like black magic for some. But what it does is to deploy a structure like for example:
  * tree ~/Library/I2P
  * /Users/mikalv/Library/I2P
  * ├── blocklist.txt
  * ├── certificates
  * │   ├── family
  * │   │   ├── gostcoin.crt
  * │   │   ├── i2p-dev.crt
  * │   │   ├── i2pd-dev.crt
  * │   │   └── volatile.crt
  * │   ├── i2ptunnel
  * │   ├── news
  * │   │   ├── ampernand_at_gmail.com.crt
  * │   │   ├── echelon_at_mail.i2p.crt
  * │   │   ├── str4d_at_mail.i2p.crt
  * │   │   └── zzz_at_mail.i2p.crt
  * │   ├── plugin
  * │   │   ├── cacapo_at_mail.i2p.crt
  * │   │   ├── str4d_at_mail.i2p.crt
  * │   │   └── zzz-plugin_at_mail.i2p.crt
  * │   ├── reseed
  * │   │   ├── atomike_at_mail.i2p.crt
  * │   │   ├── backup_at_mail.i2p.crt
  * │   │   ├── bugme_at_mail.i2p.crt
  * │   │   ├── creativecowpat_at_mail.i2p.crt
  * │   │   ├── echelon_at_mail.i2p.crt
  * │   │   ├── hottuna_at_mail.i2p.crt
  * │   │   ├── igor_at_novg.net.crt
  * │   │   ├── lazygravy_at_mail.i2p.crt
  * │   │   ├── meeh_at_mail.i2p.crt
  * │   │   └── zmx_at_mail.i2p.crt
  * │   ├── revocations
  * │   ├── router
  * │   │   ├── echelon_at_mail.i2p.crt
  * │   │   ├── str4d_at_mail.i2p.crt
  * │   │   └── zzz_at_mail.i2p.crt
  * │   └── ssl
  * │       ├── echelon.reseed2017.crt
  * │       ├── i2p.mooo.com.crt
  * │       ├── i2pseed.creativecowpat.net.crt
  * │       ├── isrgrootx1.crt
  * │       └── reseed.onion.im.crt
  * ├── clients.config
  * ├── geoip
  * │   ├── continents.txt
  * │   ├── countries.txt
  * │   ├── geoip.txt
  * │   └── geoipv6.dat.gz
  * ├── hosts.txt
  * └── i2ptunnel.config
  *
  * @author Meeh
  * @since 0.9.35
  */
class OSXDeployment extends
  DeployProfile(
    OSXDefaults.getOSXConfigDirectory.getAbsolutePath,
    OSXDefaults.getOSXBaseDirectory.getAbsolutePath
  ) {

  /**
    * This function will find the executing jar. "myself"
    * @return
    */
  def executingJarFile = getClass().getProtectionDomain().getCodeSource().getLocation()


  /**
    * This list is a micro DSL for how files should
    * be deployed to the filesystem in the base
    * directory.
    */
  val staticFilesFromResources = List(
    new FDObjFile("blocklist.txt"),
    new FDObjFile("clients.config"),
    new FDObjFile("hosts.txt"),
    new FDObjDir("geoip", files = List(
      "continents.txt",
      "countries.txt",
      "geoip.txt",
      "geoipv6.dat.gz")),
    new FDObjFile("i2ptunnel.config"),
    new FDObjDir("certificates", List(
      "family",
      "i2ptunnel",
      "news",
      "plugin",
      "reseed",
      "revocations",
      "router",
      "ssl"
    ),subDirectories=true)
  )

  /**
    * This function copies an directory of files from the jar
    * to the base directory defined in the launcher.
    * @param dir
    * @return
    */
  def copyDirFromRes(dir: File) = {
    // A small hack
    val zipFile = new ZipFile(executingJarFile.getFile)
    zipFile.entries().asScala.toList.filter(_.toString.startsWith(dir.getPath)).filter(!_.isDirectory).map { entry =>
      copyBaseFileResToDisk(entry.getName, getClass.getResourceAsStream("/".concat(entry.getName)))
    }
  }

  /**
    * This function will depending on directory or not copy either the file
    * or create the directory and copy directory content if any.
    *
    * @param file
    * @param isDir
    * @return
    */
  def createFileOrDirectory(file: File, isDir: Boolean = false) = {
    if (file != null) {
      println(s"createFileOrDirectory(${file},${isDir})")
      try {
        if (!new File(DeployProfile.pathJoin(baseDir,file.getPath)).exists()) {
          if (isDir) {
            // Handle dir
            new File(DeployProfile.pathJoin(baseDir,file.getPath)).mkdirs()
            copyDirFromRes(file)
          } else {
            // Handle file
            copyBaseFileResToDisk(file.getPath, getClass.getResourceAsStream("/".concat(file.getName)))
          }
        }
      } catch {
        case ex:IOException => println(s"Error! Exception ${ex}")
      }
    }
  }

  if (!new File(baseDir).exists()) {
    new File(baseDir).mkdirs()
  }

  /**
    * Please note that in Scala, the constructor body is same as class body.
    * What's defined outside of methods is considered constructor code and
    * executed as it.
    *
    *
    *
    * a map function work as a loop with some built in security
    * for "null" objects.
    * What happens here is "for each staticFilesFromResources" do =>
    *
    * Then, based upon if it's a file or a directory, different actions take place.
    *
    * the match case is controlling the flow based upon which type of object it is.
    */
  staticFilesFromResources.map {
    fd => fd.getContent match {
        // Case subject is a file/resource
      case Left(is) => {
        // Write file
        if (!new File(DeployProfile.pathJoin(baseDir, fd.getPath)).exists()) {
          println(s"copyBaseFileResToDisk(${fd.getPath})")
          try {
            copyBaseFileResToDisk(fd.getPath, is)
          } catch {
            case ex:IOException => println(s"Error! Exception ${ex}")
          }
        }
      }
        // Case subject is a directory
      case Right(dir) => {
        // Ensure directory
        println(s"Directory(${fd.getPath})")
        if (!new File(DeployProfile.pathJoin(baseDir,fd.getPath)).exists()) {
          new File(DeployProfile.pathJoin(baseDir,fd.getPath)).mkdirs()
        }
        dir.map { f => createFileOrDirectory(f,fd.filesIsDirectories) }
      }
    }
  }

}
