# Bundled Jetty 12.0.41

This directory contains the Maven artifacts used by the standalone I2P build,
including the matched EE8 modules and the existing SLF4J 2.0.17 API dependency.
`start.jar` is the Maven `jetty-start` artifact under the name expected by Ant.

`artifacts.json` records the release, upstream URLs and SHA-256 digests. Every
jar also has a SHA-256 sidecar checked by the normal Ant build. Artifacts were
copied from the local Gradle dependency cache after checking its original content
hashes. See the [upstream release](https://github.com/jetty/jetty.project/releases/tag/jetty-12.0.41).

The companion `jetty-home-12.0.41-debian` directory contains the two EE8 jars
needed alongside the system Jetty package. Debian source packaging retains that
directory; source builds using bundled Jetty retain this full directory.
