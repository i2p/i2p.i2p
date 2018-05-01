


assemblyExcludedJars in assembly := {
  val donts = List(
    "BOB.jar",
    "sam.jar",
    "desktopgui.jar",
    "i2ptunnel-ui.jar",
    "i2psnark.jar",
    "jetty-sslengine.jar"
  )
  val cp = (fullClasspath in assembly).value
  cp filter { s => donts.contains(s.data.getName)}
}

// Unmanaged base will be included in a fat jar
unmanagedBase := baseDirectory.value / ".." / ".." / "build"

// Unmanaged classpath will be available at compile time
unmanagedClasspath in Compile ++= Seq(
  baseDirectory.value / ".." / ".." / "build" / "*.jar"
)
