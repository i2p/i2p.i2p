#! /bin/sh

if [ "$TRAVIS_JDK_VERSION" == "oraclejdk11" ]; then
  ./gradlew sonarqube codeCoverageReport -Dsonar.verbose=true
else
  ./gradlew check codeCoverageReport
fi
