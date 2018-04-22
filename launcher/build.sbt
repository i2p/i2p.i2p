import sbtassembly.AssemblyPlugin.defaultShellScript
import sbt.Keys._


lazy val root = (project in file("."))
  .settings(
    name         := "RouterLaunchApp",
    organization := "net.i2p",
    scalaVersion := "2.11.11", // We have to use Scala 11 as long as we're going to support JRE 1.7
    version      := "0.1.0-SNAPSHOT",
    assemblyJarName in assembly := s"${name.value}-${version.value}.jar",
    mainClass in assembly := Some("net.i2p.RouterLauncherApp")

    // This will prepend shebang and become executable, however, it will only work on unix systems and not windows.
    //assemblyOption in assembly := (assemblyOption in assembly).value.copy(prependShellScript = Some(defaultShellScript))
  )

resolvers ++= Seq(
  DefaultMavenRepository,
  Resolver.mavenLocal,
  Resolver.sonatypeRepo("releases"),
  Resolver.typesafeRepo("releases"),
  Resolver.sbtPluginRepo("releases")
)

libraryDependencies ++= Seq(
  "org.json4s" %% "json4s-native" % "3.5.3"
)

assemblyExcludedJars in assembly := {
  val donts = List("BOB.jar", "sam.jar", "desktopgui.jar", "i2ptunnel-ui.jar", "i2psnark.jar", "jetty-sslengine.jar")
  val cp = (fullClasspath in assembly).value
  cp filter { s => donts.contains(s.data.getName)}
}

fork := true

run / javaOptions += "-Xmx512M"
run / connectInput := true

unmanagedBase := baseDirectory.value / ".." / "build"
unmanagedClasspath in Compile ++= Seq(
  baseDirectory.value / ".." / "build" / "*.jar"
)


