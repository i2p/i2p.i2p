#!/usr/bin/perl
# Use the "--gij" parameter to run the tests against libgcj.
# Use the "--sourcedir" parameter if running this from the source tree.
# Yeah yeah, a lot of repetitive code here, but it works for now.

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

$failed = 0;
$passed = 0;
$failedtotal = 0;
$passedtotal = 0;

print "\nTest Suite #1: i2p/core/java/test/net/i2p/data/*Test\n\n";

@testclasses = ( "AbuseReason", "AbuseSeverity", "Boolean", "Certificate",
	"CreateLeaseSetMessage", "CreateSessionMessage", "Date", "Destination",
	"DestroySessionMessage", "DisconnectMessage", "Hash", "LeaseSet", "Lease",
	"Mapping", "MessageId", "MessagePayloadMessage", "MessageStatusMessage",
	"Payload", "PrivateKey", "PublicKey", "ReceiveMessageBeginMessage",
	"ReceiveMessageEndMessage", "ReportAbuseMessage", "RequestLeaseSetMessage",
	"RouterAddress", "RouterIdentity", "RouterInfo", "SendMessageMessage",
	"SessionConfig", "SessionId", "SessionKey", "SessionStatusMessage",
	"Signature", "SigningPrivateKey", "SigningPublicKey", "String", "TunnelId",
    "UnsignedInteger" );

foreach $testclass (@testclasses) {
	print "[TEST] $testclass: ";
	if(! system("$javacommand net.i2p.data.TestData test $testclass $testclass.dat > /dev/null 2>test.tmp")) {
		print "OK\n";
		$passed++;
	} else {
		print "FAILED\n";
		print "Error Messages:\n\n";
		open TMP, "test.tmp";
		while (<TMP>) {
			print "$_";
		}
		print "\n";
		$failed++;
	}
	system("rm -f $testclass.dat test.tmp > /dev/null 2>&1");
}

print "\nTEST SUITE #1 RESULTS\nPassed: $passed\nFailed: $failed\n\n";

$failedtotal += $failed;
$passedtotal += $passed;
$failed = 0;
$passed = 0;

print "\nTest Suite #2: i2p/core/java/test/net/i2p/crypto/*\n\n";

@testclasses = ( "ElGamalAESEngine", "ElGamalVerify", "SessionEncryptionTest" );

foreach $testclass (@testclasses) {
	if ($testclass eq "SessionEncryptionTest") {
		print "[TEST] $testclass: ";
		if(! system("$javacommand net.i2p.crypto.SessionEncryptionTest > /dev/null 2>test.tmp")) {
			print "OK\n";
			$passed++;
		} else {
			print "FAILED\n";
			print "Error Messages:\n\n";
			open TMP, "test.tmp";
			while (<TMP>) {
				print "$_";
			}
			$failed++;
		}
	} else {
		print "[TEST] $testclass:\n\n";
		if(! system("$javacommand net.i2p.crypto.$testclass")) {
			$passed++;
		} else {
			$failed++;
		}
	}
	print "\n";
	system("rm -f test.tmp > /dev/null 2>&1");
}

print "\nTEST SUITE #2 RESULTS\nPassed: $passed\nFailed: $failed\n\n";

$failedtotal += $failed;
$passedtotal += $passed;
$failed = 0;
$passed = 0;

print "\nTest Suite #3: Miscellaneous\n\n";

@testclasses = ( "net.i2p.util.LogManager", "net.i2p.util.OrderedProperties", 
	"net.i2p.stat.RateStat", "net.i2p.data.UnsignedInteger" );

foreach $testclass (@testclasses) {
	if ($testclass eq "net.i2p.data.UnsignedInteger") {
		print "[TEST] $testclass:\n\n";
		if(! system("$javacommand net.i2p.data.UnsignedInteger")) {
			$passed++;
		} else {
			$failed++;
		}
		print "\n";
	} else {
		print "[TEST] $testclass: ";
		if(! system("$javacommand $testclass > /dev/null 2>test.tmp")) {
			print "OK\n";
			$passed++;
		} else {
			print "FAILED\n";
			print "Error Messages:\n\n";
			open TMP, "test.tmp";
			while (<TMP>) {
				print "$_";
			}
			$failed++;
		}
	}
	system("rm -f test.tmp > /dev/null 2>&1");
}

print "\nTEST SUITE #3 RESULTS\nPassed: $passed\nFailed: $failed\n\n";

$failedtotal += $failed;
$passedtotal += $passed;

print "\n*** ALL TESTS COMPLETE ***\n\nTotal Passed: $passedtotal\nTotal Failed: $failedtotal\n\n";
