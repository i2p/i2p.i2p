This is Apache Tomcat 6.x, supporting Servlet 2.5 and JSP 2.1.
The Glassfish JSP 2.1 bundled in Jetty 6 is way too old.

Retrieved from the file
	apache-tomcat-6.0.43-deployer.tar.gz

minus the following files and directores:

	build.xml
	deployer-howto.html
	images/*
	lib/catalina*
	lib/jsp-api.jar (see below)
	lib/servlet-api.jar (see below)
	LICENSE (see ../../../licenses/LICENSE-Apache2.0.txt, it's also inside every jar)
	RELEASE-NOTES


We could use the following API jars from Apache Tomcat 7.x, supporting Servlet 3.0 and JSP 2.2,
that are required for Jetty 8, but we just bundle the ones from Jetty 8 instead:

	lib/jsp-api.jar
	lib/servlet-api.jar

For more info:
http://tomcat.apache.org/whichversion.html
