To install this base instance:

	mkdir lib
	cp ../lib/i2p.jar lib/
	cp ../lib/commons-el.jar lib/
	cp ../lib/commons-logging.jar lib/
	cp ../lib/jasper-compiler.jar lib/
	cp ../lib/jasper-runtime.jar lib/
	cp ../lib/javax.servlet.jar lib/
	cp ../lib/jbigi.jar lib/
	cp ../lib/org.mortbay.jetty.jar lib/
	cp ../lib/xercesImpl.jar lib/

To run it:
	sh run.sh
	firefox http://localhost:7653/syndie/

You can share your archive at http://localhost:7653/ so
that people can syndicate off you via
 cd archive ; wget -m -nH http://yourmachine:7653/

You may want to add a password on the registration form 
so that you have control over who can create blogs via /syndie/.
To do so, set the password in the run.sh script.

Windows users:
write your own instructions.  We're alpha, here ;)
