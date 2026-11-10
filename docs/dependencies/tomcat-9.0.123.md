# Tomcat / Jasper 9.0.123

Updates both Gradle and Ant from 9.0.107 to 9.0.123, replacing the same checked-in
Tomcat API, Jasper, EL, and utility JAR subset used by the existing build.

The update includes the upstream Jasper JSTL redirect fix
[CVE-2026-74049](https://tomcat.apache.org/security-9.html#Fixed_in_Apache_Tomcat_9.0.123).
This identifies an included upstream fix; it does not assert that an I2P web
application currently exposes the affected Jasper tag.

Provenance: https://downloads.apache.org/tomcat/tomcat-9/v9.0.123/bin/apache-tomcat-9.0.123.tar.gz

Publisher SHA-512 verified before extracting the selected files:

```text
64b1130e719399c0a409e6996081433b16af8d9c89266c7aaa9849af70a1815feca91e13896f5ccc41451dc5759716d1c84882682de955822c30509de102f290
```

The upstream NOTICE is carried with the new distribution subset. Both Ant
Jetty packaging and Gradle tests/JSP compilation were checked with the updates.
