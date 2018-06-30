CXX=clang++

CXXFLAGS= -std=c++14 -g -Wall -mmacosx-version-min=10.10
INCLUDEDIRS=-I./include -I/usr/local/include -I/usr/include
SOURCES=main.mm StatusItemButton.mm CRunner.cpp
LDFLAGS=-framework CoreFoundation -framework Foundation -framework Cocoa

i2plauncher:
	$(CXX) $(CXXFLAGS) $(INCLUDEDIRS) $(LDFLAGS) -o i2plauncher $(SOURCES)

clean:
	rm -f i2plauncher
