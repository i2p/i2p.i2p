#!/usr/bin/perl

print "\nBenchmark Suite #1: i2p/core/java/test/net/i2p/crypto/*\n\n";

@testclasses = ( "AES256Bench", "DSABench", "ElGamalBench", "SHA256Bench" );

foreach $testclass (@testclasses) {
	print "[BENCHMARK] $testclass:\n\n";
	system("java -cp lib/i2p.jar:lib/jbigi.jar net.i2p.crypto.$testclass");
	print "\n";
}

print "\n*** ALL BENCHMARKS COMPLETE ***\n\n";
