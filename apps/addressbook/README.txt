addressbook v2.0.2 - A simple name resolution mechanism for I2P

addressbook is a simple implementation of subscribable address books for I2P.  
Addresses are stored in userhosts.txt and a second copy of the address book is 
placed on your eepsite as hosts.txt. 

subscriptions.txt contains a list of urls to check for new addresses.
Since the urls are checked in order, and conflicting addresses are not added,
addressbook.subscriptions can be considered to be ranked in order of trust.

The system created by addressbook is similar to the early days of DNS,
when everyone ran a local name server.  The major difference is the lack of
authority.  Name cannot be guaranteed to be globally unique, but in practise 
they probably will be, for a variety of social reasons.

Requirements
************

i2p with a running http proxy

Installation and Usage
**********************

1. Unzip addressbook-%ver.zip into your i2p directory. 
2. Restart your router.

The addressbook daemon will automatically run while the router is up.

Aside from the daemon itself, the other elements of the addressbook interface
are the config.txt, myhosts.txt, and subscriptions.txt files found in the addressbook 
directory. 

config.txt is the configuration file for addressbook.

myhosts.txt is the addressbook master address book.  Addresses placed in this file 
take precidence over those in the router address book and in remote address books.  
If changes are made to this file, they will be reflected in the router address book 
and published address book after the next update. Do not make changes directly to the 
router address book, as they could be lost during an update.

subscriptions.txt is the subscription list for addressbook.  Each entry is an absolute 
url to a file in hosts.txt format. Since the list is checked in order, url's should be 
listed in order of trust.
