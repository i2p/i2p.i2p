STASHER README

-----------------------
INSTALLING STASHER

Prerequisite:

Before you can install/run Stasher, you will first need to have installed
the I2P Python modules - available in cvs at i2p/apps/sam/python.

To install stasher, just make sure you've got the latest cvs, then type
  python setup.py install
as root.

This installs the stasher engine, plus a wrapper client script called
'stasher', which setup.py will install into your execution path.

If you don't like the thought of becoming root, you could just put stasher.py
on your execution path, and/or create a symlink called 'stasher'.

Test your installation by typing 'stasher -h' - this should display
a help message.

------------------------
DOZE USERS PLEASE NOTE

You'll need to watch and see where the stasher.py
wrapper script gets installed. On my box, it ends up on d:\python23\scripts,
but on your box it'll likely go into c:\python23\scripts.

You may either update your system PATH environment variable to include your
python scripts directory, OR, you can copy stasher.py to anywhere that's
on your path.

In the explanations below, note that wherever I say to type 'stasher', you'll
need to type 'stasher.py' instead.

------------------------
WARNING

This is a very early pre-alpha test version of stasher.
It is only capable of storing or retrieving files of
less than 29k in size.

Also, files are totally insecure - anyone can overwrite any keys you
insert, and vice versa.

I'll be adding support for CHK-like and SSK-like keys in due course.

------------------------
USING STASHER

To see stasher's options, type:

  stasher -h

This should dump out a verbose help message.

To start a stasher node, type:

  stasher start

To shut down a stasher node, type:

  stasher stop

To insert a file into stasher, type:

  stasher put mykey myfile

Note, if you don't supply a filename, stasher can read
the file from standard input.

To retrieve a file from stasher, type:

  stasher get mykey

