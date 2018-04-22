import sbtassembly.AssemblyPlugin.defaultShellScript

lazy val i2pVersion = "0.9.34"

// Unmanaged classpath will be available at compile time
unmanagedClasspath in Compile ++= Seq(
  baseDirectory.value / ".." / ".." / "build" / "*.jar",
  baseDirectory.value / ".." / ".." / "router" / "java" / "src"
)

// Please note the difference between browserbundle, this has
// the "in Compile" which limit it's scope to that.
//unmanagedBase in Compile := baseDirectory.value / ".." / ".." / "build"

libraryDependencies ++= Seq(
  "net.i2p" % "router" % i2pVersion % Compile
)


assemblyOption in assembly := (assemblyOption in assembly).value.copy(prependShellScript = Some(defaultShellScript))

assemblyJarName in assembly := s"${name.value}-${version.value}"
