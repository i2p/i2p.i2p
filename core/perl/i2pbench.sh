#!/usr/bin/perl
# Use the "--gij" parameter to run the tests against libgcj.
# Use the "--sourcedir" parameter if running this from the source tree.

$runtime = "java";
$classpath = "../lib/i2p.jar:../lib/jbigi.jar";

foreach $argv (@ARGV) {
	if ($argv eq "--gij") {
		$runtime = "gij";
		print "\n*** Using gij + libgcj ***\n\n";
	}
	if ($argv eq "--sourcedir") {
		$classpath = "../../build/i2p.jar:../../build/jbigi.jar";
	}
}

$javacommand = "$runtime -cp $classpath -Dlogger.shutdownDelay=0";

print "\nBenchmark Suite #1: i2p/core/java/test/net/i2p/crypto/*\n\n";

@testclasses = ( "AES256Bench", "DSABench", "ElGamalBench", "SHA256Bench" );

foreach $testclass (@testclasses) {
	print "[BENCHMARK] $testclass:\n\n";
	system("$javacommand net.i2p.crypto.$testclass");
	print "\n";
}

print "\nBenchmark Suite #2: i2p/core/java/src/net/i2p/util/NativeBigInteger\n\n";

$javacommand = "$runtime -cp $classpath -Djava.library.path=.";

system("$javacommand net.i2p.util.NativeBigInteger");

print "\n\n*** ALL BENCHMARKS COMPLETE ***\n\n";
