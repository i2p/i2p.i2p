import java.io.{File, FileNotFoundException, FileOutputStream}
import java.util.zip._

lazy val i2pVersion = "0.9.34"

lazy val cleanAllTask = taskKey[Unit]("Clean up and remove the OSX bundle")
lazy val buildAppBundleTask = taskKey[Unit](s"Build an Mac OS X bundle for I2P ${i2pVersion}.")
lazy val buildDeployZipTask = taskKey[String](s"Build an zipfile with base directory for I2P ${i2pVersion}.")
lazy val bundleBuildPath = new File("./output")

lazy val staticFiles = List(
  "blocklist.txt",
  "clients.config",
  "continents.txt",
  "countries.txt",
  "hosts.txt",
  "geoip.txt",
  "router.config",
  "webapps.config"
)

lazy val resDir = new File("./../installer/resources")
lazy val i2pBuildDir = new File("./../pkg-temp")
lazy val warsForCopy = new File(i2pBuildDir, "webapps").list.filter { f => f.endsWith(".war") }
lazy val jarsForCopy = new File(i2pBuildDir, "lib").list.filter { f => f.endsWith(".jar") }


def defaultOSXLauncherShellScript(javaOpts: Seq[String] = Seq.empty): Seq[String] = {
  val javaOptsString = javaOpts.map(_ + " ").mkString
  Seq(
    "#!/usr/bin/env sh",
    s"""
       |echo "I2P - Mac OS X Launcher starting up"
       |export I2P=$$HOME/Library/I2P
       |for jar in `ls $${I2P}/lib/*.jar`; do
       |  if [ ! -z $$CP ]; then
       |      CP=$${CP}:$${jar};
       |  else
       |      CP=$${jar}
       |  fi
       |done
       |export CLASSPATH=$$CP
       |exec java -jar $javaOptsString$$JAVA_OPTS "$$0" "$$@"""".stripMargin,
    "")
}

// Pointing the resources directory to the "installer" directory
resourceDirectory in Compile := baseDirectory.value / ".." / ".." / "installer" / "resources"

// Unmanaged base will be included in a fat jar
unmanagedBase in Compile := baseDirectory.value / ".." / ".." / "pkg-temp" / "lib"

// Unmanaged classpath will be available at compile time
unmanagedClasspath in Compile ++= Seq(
  baseDirectory.value / ".." / ".." / "pkg-temp" / "lib" / "*.jar"
)

assemblyOption in assembly := (assemblyOption in assembly).value.copy(
  prependShellScript = Some(defaultOSXLauncherShellScript(
    Seq(
      "-Xmx512M",
      "-Xms128m",
      "-Dwrapper.logfile=/tmp/router.log",
      "-Dwrapper.logfile.loglevel=DEBUG",
      "-Dwrapper.java.pidfile=/tmp/routerjvm.pid",
      "-Dwrapper.console.loglevel=DEBUG",
      "-Di2p.dir.base=$I2P",
      "-Djava.library.path=$I2P"
    )))
)


assemblyJarName in assembly := s"OSXLauncher"

assemblyExcludedJars in assembly := {
  val cp = (fullClasspath in assembly).value
  cp filter { c => jarsForCopy.toList.contains(c.data.getName) }
}

// TODO: MEEH: Add assemblyExcludedJars and load the router from own jar files, to handle upgrades better.
// In fact, most likely the bundle never would need an update except for the router jars/wars.

convertToICNSTask := {
  println("TODO")
}

cleanAllTask := {
  clean.value
  IO.delete(bundleBuildPath)
}

buildDeployZipTask := {
  println(s"Starting the zip file build process. This might take a while..")
  if (!bundleBuildPath.exists()) bundleBuildPath.mkdir()
  val sourceDir = i2pBuildDir
  def recursiveListFiles(f: File): Array[File] = {
    val these = f.listFiles
    these ++ these.filter { f => f.isDirectory }.flatMap(recursiveListFiles).filter(!_.isDirectory)
  }
  def zip(out: String, files: Iterable[String]) = {
    import java.io.{ BufferedInputStream, FileInputStream, FileOutputStream }
    import java.util.zip.{ ZipEntry, ZipOutputStream }

    val zip = new ZipOutputStream(new FileOutputStream(out))

    files.foreach { name =>
      val fname = sourceDir.toURI.relativize(new File(name).toURI).toString
      //println(s"Zipping ${fname}")
      if (!new File(name).isDirectory) {
        zip.putNextEntry(new ZipEntry(fname))
        val in = new BufferedInputStream(new FileInputStream(name))
        var b = in.read()
        while (b > -1) {
          zip.write(b)
          b = in.read()
        }
        in.close()
        zip.closeEntry()
      }
    }
    zip.close()
  }
  val fileList = recursiveListFiles(sourceDir.getCanonicalFile).toList
  val zipFileName = new File(bundleBuildPath, "i2pbase.zip").getCanonicalPath
  zip(zipFileName, fileList.map { f => f.toString }.toIterable)
  zipFileName.toString
}

buildAppBundleTask := {
  println(s"Building Mac OS X bundle for I2P version ${i2pVersion}.")
  if (!bundleBuildPath.exists()) bundleBuildPath.mkdir()
  val paths = Map[String,File](
    "execBundlePath" -> new File(bundleBuildPath, "I2P.app/Contents/MacOS"),
    "resBundlePath" -> new File(bundleBuildPath, "I2P.app/Contents/Resources")
  )
  paths.map { case (s,p) => p.mkdirs() }

  val launcherBinary = Some(assembly.value)
  launcherBinary.map { l => IO.copyFile( new File(l.toString), new File(paths.get("execBundlePath").get, "I2P") ) }


  val plistFile = new File("./macosx/Info.plist")
  if (plistFile.exists()) {
    println(s"Adding Info.plist...")
    IO.copyFile(plistFile, new File(bundleBuildPath, "I2P.app/Contents/Info.plist"))
  }

  val zipFilePath = Some(buildDeployZipTask.value)

  val zipFileOrigin = new File(zipFilePath.get)
  IO.copyFile(zipFileOrigin, new File(paths.get("resBundlePath").get, "i2pbase.zip"))
  println(s"Zip placed into bundle :)")

}
