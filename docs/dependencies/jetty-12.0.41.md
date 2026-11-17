# Jetty 12.0.41

Updates Gradle's 12.0.32 pin and the default bundled 12.0.28 distribution to
12.0.41, retaining the Jetty 12.0 line and Servlet 4 / EE8 integration.

[Jetty's security table](https://jetty.org/security.html) lists affected 12.0.x
ranges extending beyond both old versions. The update includes those upstream
fixes. [Release notes](https://github.com/jetty/jetty.project/releases/tag/jetty-12.0.41).

Only the same subset of distribution files used by the existing Ant build is
replaced. The servlet API carried by the distribution is now 4.0.9; SLF4J remains
2.0.17. Distribution package builds retain their existing system-Jetty version
selection through `jetty.ver.debian`.

Provenance: https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-home/12.0.41/jetty-home-12.0.41.zip

Publisher SHA-512 verified before extracting the selected files:

```text
df62782acde2b2c992dcf3be066ae7a4920e86ba1c00f03932be161c3fcccf41d122cde60f7bcb246471ac8de25875a07ba4759c2ea6d21e36e231cf4197bea2
```

The Ant archive checksum is updated to match the new distribution. Both Ant
Jetty packaging and Gradle tests/JSP compilation were checked with the updates.
