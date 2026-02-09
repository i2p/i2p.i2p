This is launch4j 3.50 2022-11-13.
The license is BSD for launch4j and MIT for the wrapper code in head/

Changelog: https://launch4j.sourceforge.net/changelog.html

Upgraded from 3.0.1 (2008) to 3.50 (2022) to support Java 9+ version formats.
Launch4j 3.12+ fully supports Java 9 and newer version numbers (e.g., "17" instead of "1.8.0").

The previous version was 3.0.1 2008-07-20.

NOTE: Building with launch4j on Java 9+ may require ANT_OPTS:
  ANT_OPTS="--add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED"

Files removed from upstream distribution (not needed for runtime):
  src/, demo/, web/, maven/, assembly/, head_src/, head_jni_BETA/, build.xml,
  .classpath, .project, .settings/, .vscode/
