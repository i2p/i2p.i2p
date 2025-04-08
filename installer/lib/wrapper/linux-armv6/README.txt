Wrapper build instructions (Ubuntu or Raspbian):

       apt-get install openjdk-8-jdk-headless ant
       export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-armhf
       ant -Dbits=32 compile-c-unix
       mv bin/wrapper bin/i2psvc
       strip --strip-unneeded bin/i2psvc lib/libwrapper.so
       cp bin/i2psvc $I2P
       cp lib/libwrapper.so $I2P/lib
       cp /path/to/wrapper-delta-pack-3.5.xx/lib/wrapper.jar $I2P/lib
       (test it)
       cp bin/i2psvc lib/libwrapper.so /path/to/installer/lib/wrapper/linux-armv6
       chmod -x i2psvc librapper.so
