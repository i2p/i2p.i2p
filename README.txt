Prerequisites to build from source:
	Java SDK (preferably Oracle/Sun or OpenJDK) 1.7.0 or higher
          Non-linux operating systems and JVMs: See https://trac.i2p2.de/wiki/java
          Certain subsystems for embedded (core, router, mstreaming, streaming, i2ptunnel) require only Java 1.6
	Apache Ant 1.7.0 or higher
	The xgettext, msgfmt, and msgmerge tools installed
	  from the GNU gettext package http://www.gnu.org/software/gettext/
	Build environment must use a UTF-8 locale.

To build:
	On x86 systems do:
		ant pkg

	On non-x86, use one of the following instead:
		ant installer-linux
		ant installer-freebsd
		ant installer-osx

	Run 'ant' with no arguments to see other build options.
	See INSTALL.txt or https://geti2p.net/download for installation instructions.

Documentation:
	https://geti2p.net/how
	API: http://docs.i2p-projekt.de/javadoc/
	     or run 'ant javadoc' then start at build/javadoc/index.html

Latest release:
	https://geti2p.net/download

To get development branch from source control:
	https://geti2p.net/newdevelopers

FAQ:
	https://geti2p.net/faq

Need help?
	IRC irc.freenode.net #i2p
	http://forum.i2p/

Bug reports:
	https://trac.i2p2.de/report/1

Contact information, security issues, press inquiries:
	https://geti2p.net/en/contact

Twitter:
	@i2p, @geti2p

Licenses:
	See LICENSE.txt

