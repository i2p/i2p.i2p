The routerconsole application is an embedable web server / servlet container.
In it there is a bundled routerconsole.war containing JSPs (per jsp/*) that
implement a web based control panel for the router.  This console gives the user
a quick view into how their router is operating and exposes some pages to 
configure it.

The web server itself is Jetty [1] and is contained within the various jar files
under lib/.  To embed this web server and the included router console, the 
startRouter script needs to be updated to include those jar files in the 
class path, plus the router.config needs appropriate entries to start up the
server:

  clientApp.3.main=net.i2p.router.web.RouterConsoleRunner
  clientApp.3.name=webConsole
  clientApp.3.args=7657 0.0.0.0 ./webapps/

That instructs the router to fire up the webserver listening on port 7657 on
all of its interfaces (0.0.0.0), loading up any .war files under the ./webapps/
directory.  The RouterConsoleRunner itself configures the Jetty server to give
the ./webapps/routerconsole.war control over the root context, directing a
request to http://localhost:7657/index.jsp to the routerconsole.war's index.jsp.
Any other .war file will be mounted under their filename's context (e.g. 
myi2p.war would be reachable at http://localhost:7657/myi2p/index.jsp).

[1] http://jetty.mortbay.com/jetty/index.html