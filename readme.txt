$Id: readme.txt,v 1.5 2004/03/21 20:03:54 jrandom Exp $
I2P Router 0.3

You have installed a development release of I2P - a variable latency secure 
and anonymous communication network.  I2P is NOT a filesharing network, or an
email network, or a publishing network - it is simply an anonymous communication
layer - kind of an anonymous IP.  This installation includes an application 
called "I2PTunnel", which allows normal TCP/IP applications to run over I2P,
offering functionality such as access to anonymous irc servers, anonymous 
websites ("eepsites"), etc.

Since this is a *development* release, I2P should not be depended upon for 
anonymity or security.  The network is very small, and there are certainly bugs
and suboptimal features.  Participating in the network at this time should be
for exploration and to evaluate I2P's functionality and suitability for your 
later needs.  Once I2P 1.0 is out, wider adoption may be appropriate, but until
that time, we do want to keep the I2P community small, since you are all part of
the development team.

=== How to get started

Like a TCP/IP stack, installing the I2P "router" itself doesn't really do much.
You can fire it up with the script startRouter.sh (or startRouter.bat on 
windows), and its management console can be seen via http://localhost:7655/

Once your router has started up, it may take a few minutes to get integrated 
with the network (you'll see a few TCP connections listed on the management 
console).  At that point, you can start up any of the various proxies:
* startEepProxy: starts an HTTP proxy to access eepsites.  Set your browser's 
  HTTP proxy to localhost:4444 and you can browse various anonymously hosted 
  sites, ala http://duck.i2p/.  In addition, the default proxy is set up to
  tunnel any HTTP requests that don't point at an eepsite (e.g. http://i2p.net/)
  through I2P to an outbound squid proxy - with this, you can browse the web
  anonymously.
* startIrcProxy: starts an anonymous tunnel to an anonymously hosted IRC server
  (irc.duck.i2p) - use your favorite irc client and connect to localhost:6668 
  and join us on #i2p

=== Problems accessing eepsites or servers

I2P is not a distributed data store (ala freenet / mnet / etc) - sites are only 
reachable when the host serving that data is up (and their router is running).  
If you persistently can't reach the irc server, the squid proxy, or some common
eepsites, check your management console (http://localhost:7655/) and make sure 
you have TCP connections.  If you don't have any, make sure your firewall / NAT
allows inbound access to your I2P port (which you specified during 
installation).  If thats fine, but you only see one routerInfo-*.dat file 
underneath your netDb/ directory, run the reseed script to pull some new peer 
references (it contacts http://i2p.net/i2pdb/ and downloads those files.  
alternately, you can get routerInfo files from any of your friends, etc)

If you still have problems, get in touch with the I2P team (contact info below)

=== Resources / contact info

I2P is currently revamping our website, so the two main resources are 
http://i2p.net/ and http://wiki.invisiblenet.net/iip-wiki?I2P (our website's
work-in-progress location is http://drupal.i2p.net/ for those who want to see 
whats coming down the pipe).  The development and user community hangs out on
a few different linked irc chats - IIP's #i2p, freenode.net's #i2p, and the 
in-I2P irc network's #i2p (both irc.baffled.i2p and irc.duck.i2p).  All of those
channels are hooked together, so join whichever one meets your needs.

There is also a relatively low traffic mailing list:
http://i2p.net/mailman/listinfo/i2p with archives at
http://i2p.net/pipermail/i2p/

The source can be retrieved from http://i2p.net/i2p/ as well as the latest 
binary distributions.

You can pull the latest code via cvs:
cvs -d :pserver:anoncvs@i2p.net:/cvsroot login
cvs -d :pserver:anoncvs@i2p.net:/cvsroot co i2p
The password is "anoncvs".  

=== Acknowledgements

We are a small group of volunteers spread around several continents, working to 
advance different aspects of the project and discussing the design of the 
network.  For a current list of team members, please see 
http://drupal.i2p.net/team

=== Licenses

All code included here is released under an open source license.  To review
the I2P license policy, please see http://drupal.i2p.net/node/view/66.
If there is any confusion, please see the source code or contact the 
developers on the i2p list.
