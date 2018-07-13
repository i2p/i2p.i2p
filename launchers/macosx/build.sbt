import java.io.{File, FileNotFoundException, FileOutputStream}
import java.util.zip._

lazy val i2pVersion = "0.9.35"

lazy val cleanAllTask = taskKey[Unit]("Clean up and remove the OSX bundle")
lazy val buildAppBundleTask = taskKey[Unit](s"Build an Mac OS X bundle for I2P ${i2pVersion}.")
lazy val buildDeployZipTask = taskKey[String](s"Build an zipfile with base directory for I2P ${i2pVersion}.")
lazy val bundleBuildPath = new File("./output")


lazy val resDir = new File("./../installer/resources")
lazy val i2pBuildDir = new File("./../pkg-temp")
lazy val warsForCopy = new File(i2pBuildDir, "webapps").list.filter { f => f.endsWith(".war") }
lazy val jarsForCopy = new File(i2pBuildDir, "lib").list.filter { f => f.endsWith(".jar") }


// Unmanaged classpath will be available at compile time
unmanagedClasspath in Compile ++= Seq(
    baseDirectory.value / ".." / ".." / "pkg-temp" / "lib" / "router.jar",
    baseDirectory.value / ".." / ".." / "pkg-temp" / "lib" / "i2p.jar"
)

unmanagedBase in Compile := baseDirectory.value / ".." / ".." / "pkg-temp" / "lib"

assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false, includeDependency = false)

assemblyExcludedJars in assembly := {
    val cp = (fullClasspath in assembly).value
    cp filter { c => jarsForCopy.toList.contains(c.data.getName) }
}

javacOptions ++= Seq("-source", "1.7", "-target", "1.7")
scalacOptions in Compile := Seq("-deprecated","-target:jvm-1.7")

