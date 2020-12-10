# Readme for hacking on the source

## Build systems

### Release/Core Packages build system: Ant

To build the I2P java packages we use the Apache `ant` build system.
If you need to build a complete I2P installer for all platforms, run
the:

        ant pkg

target, which will produce `jar` and `exe` files. If you are a developer
who already has I2P installed, it is convenient to build updater packages
instead of the whole installer to speed up builds and to avoid needing to
repeatedly re-run the installer. To simply build the updater `zip` files,
run:

        ant updater

then copy the updater to the I2P configuration directory and re-start I2P. 
On Linux, this is `$HOME/.i2p` if you installed with the `install.jar` file, and
on Windows it is `%LOCALAPPDATA%\I2P\`. When using Debian packages, this type 
of update is intentionally disabled so that the application can be updated from 
the repositories securely. If you are a Debian user who wants to temporarily
try out a dev build, the easiest way is to run:

        sudo systemctl stop i2p

or

        sudo service i2p stop

to stop the I2P Service started by the package, then use an `install.jar` to
set up an I2P Package as your user. Then, run:

        ~/i2p/i2prouter start

to start the user-installed I2P service. This version of I2P will also be able to
use an updater `zip` file to update itself. When you're done using your dev build,
you can run:

        ~/i2p/i2prouter stop

then run:

        sudo systemctl start i2p

or

        sudo service i2p start

### Peripheral/External Packages: SBT

The (Discontinued)Browser Bundle launcher, as well as the Mac OSX 
launcher the `sbt` tool from the Scala project is actually used
underneath, but the `sbt` can still be executed by an `ant` target.
To build the OSX launcher, run the:

        ant osxLauncher

target, and to build the Browser Bundle launcher, run the:

        ant bbLauncher

target.

## Browsing around the source code

If you're new at this, which we all was at one point, I'll have some tips.

* The [DIRECTORIES.md](DIRECTORIES.md) contains a listing of the directories
in the I2P source code and what the code inside them is used for.

* Some parts of the I2P router are started by files that end with the suffix
`'Runner.java'` and can be listed by running the command
`find . -type f -name '*Runner.java'` in the root of your `i2p.i2p` source
directory.

## The ... ~~Monotone~~ part

Monotone served I2P well for many many years, but it's deprecated now. Instead,
we're using Git like almost everybody else.

Please join us at: [https://i2pgit.org](https://i2pgit.org)
and [http://git.idk.i2p](http://git.idk.i2p).

If you're used to monotone and need to see how common monotone commands relate
to git equivalents, the [MONOTONECHEATSHEET.md](MONOTONECHEATSHEET.md) may be
a relevant reference.

## Git clone depth and Git Bundles

Since `git` is not resumable until a repository is cloned, it is helpful to obtain
the copies of of i2p.i2p anonymously in one of a couple ways to make the initial clone
process more reliable. There are two ways to obtain a git repository reliably over I2P
first, configure your git client according to the instructions on the [I2P Project Site](https://geti2p.net/en/docs/applications/git)
and clone

        git clone --depth 1 git@127.0.0.1:i2p-hackers/i2p.i2p

This will clone the minimum amount of i2p.i2p to have a working checkout. You can then
resumably populate the repository with the remaining commits by running:

        git fetch --unshallow

in the newly-created `i2p.i2p` directory.

You can also obtain a historical copy of the i2p.i2p source code from a git bundle which
is distributed as a torrent. Instructions for doing this are also on the [I2P Project Site](https://geti2p.net/en/docs/applications/git-bundle).
Once obtained, you can "clone" using the bundle, then `git remote set-url origin git@127.0.0.1:i2p-hackers/i2p.i2p`
to configure it to use the upstream git remote. Finally, run

        git fetch --all

to finish updating your source code.

## SBT Behind proxy

`sbt` does not work well with SOCKS5 proxies. In order to use it with Tor,
you will need to use an HTTP Proxy in-between. You can configure an HTTP Proxy
for `sbt` with the SBT_OPTS environment variable.

        export SBT_OPTS="$SBT_OPTS -Dhttp.proxyHost=myproxy-Dhttp.proxyPort=myport"

by setting it before you start `sbt`.
