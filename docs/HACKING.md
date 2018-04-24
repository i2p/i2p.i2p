# Readme for hacking on the source

## Build systems
The build system of I2P today is a bit mixed. 
In the old days, and at least for relasing we actually use
the old `ant` tool. For the new Browser Bundle launcher, as 
well as the Mac OSX launcher the `sbt` tool from the Scala 
world was used cause it gives some increadibly effictive plugins
and logic to build them while keeping a relation to the old build 
system.

We also have Gradle partly implemented, much work, but not all. Meeh 
which is writing this readme is guessing we'll end up with a combination
of Gradle and SBT in the end when we know what kind of structure we want.


## Browsing around the source code

If you're new at this, which we all was at one point, I'll have some tips.

* Check out our [DIRECTORIES.md](DIRECTORIES.md) to learn ore 
about where you'll find I2P's different parts in the codebase.

* For me (Meeh), it worked well to run `find . -type f -name '*Runner.java'` 
from the source tree root, and take a look at the files that get listed. A lot 
of hints of how this is peaced together lies there.


## The .. Monotone part

Check out [MONOTONECHEATSHEET.md](MONOTONECHEATSHEET.md) file.

## SBT Behind proxy

Seems it's a hassle behind SOCKSv5. But for use of HTTP proxy to fetch 
dependencies and such, please edit `export SBT_OPTS="$SBT_OPTS -Dhttp.proxyHost=myproxy-Dhttp.proxyPort=myport"` 
to have correct values for your system, then execute it before you start SBT.
