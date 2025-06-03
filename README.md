# I2P

This is the source code for the reference Java implementation of I2P.

Latest release: [https://geti2p.net/download](https://geti2p.net/download) 

## Installing

See [INSTALL.txt](INSTALL.txt) or [https://geti2p.net/download](https://geti2p.net/download) for installation instructions.

## Documentation

[https://geti2p.net/how](https://geti2p.net/how)

FAQ: [https://geti2p.net/faq](https://geti2p.net/faq) 

API: [http://docs.i2p-projekt.de/javadoc/](http://docs.i2p-projekt.de/javadoc/) 
or run 'ant javadoc' then start at build/javadoc/index.html

## How to contribute / Hack on I2P

Please check out [HACKING.md](docs/HACKING.md) and other documents in the docs directory.

## Building packages from source

To get development branch from source control: [https://geti2p.net/newdevelopers](https://geti2p.net/newdevelopers) 

### Prerequisites

- Java SDK (preferably Oracle or OpenJDK) 17 or higher
  - Non-linux operating systems and JVMs: See [https://trac.i2p2.de/wiki/java](https://trac.i2p2.de/wiki/java) 
  - Certain subsystems for embedded (core, router, mstreaming, streaming, i2ptunnel)
    require only Java 6
- Apache Ant 1.9.8 or higher
- The xgettext, msgfmt, and msgmerge tools installed from the GNU gettext package
 [http://www.gnu.org/software/gettext/](http://www.gnu.org/software/gettext/) 
- Build environment must use a UTF-8 locale.

### Ant build process

On x86 systems do:

    ant pkg

On non-x86, use one of the following instead:

    ant installer-linux
    ant installer-freebsd
    ant installer-osx

Run 'ant' with no arguments to see other build options.

### Gradle build process

Full builds of installers or updates are not yet possible, but the code can be
compiled with:

    ./gradlew assemble

This will download dependencies over the clearnet by default, including Gradle
itself. To download through a SOCKS proxy (e.g. Tor), add the following lines to
your `~/.gradle/gradle.properties`:

    systemProp.socksProxyHost=localhost
    systemProp.socksProxyPort=9150

### Development builds
Automatic CI builds are available at the [continuous integration](https://github.com/i2p/i2p.i2p/actions/workflows/ant.yml) page.

### Docker
For more information how to run I2P in Docker, see [Docker.md](Docker.md)
## Contact info

Need help? See the IRC channel #i2p on irc.freenode.net

Bug reports: [https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues](https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues) [http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues](http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues)

Contact information, security issues, press inquiries: [https://geti2p.net/en/contact](https://geti2p.net/en/contact) 

Twitter: [@i2p](https://twitter.com/i2p), [@geti2p](https://twitter.com/GetI2P)

## Licenses

See [LICENSE.txt](LICENSE.txt) 

