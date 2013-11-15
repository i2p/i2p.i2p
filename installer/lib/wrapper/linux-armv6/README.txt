Wrapper build instructions (Ubuntu or Raspbian):

       apt-get install default-jdk ant
       ant -Dbits=32 compile-c-unix
       mv bin/wrapper bin/i2psvc
       strip --strip-unneeded bin/i2psvc lib/libwrapper.so

