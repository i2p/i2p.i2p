#!/usr/bin/perl

use Net::SAM::RawSession;
use Net::SAM::DatagramSession;

$sam=Net::SAM::DatagramSession->new($ARGV[0], "BOTH", "tunnels.depthInbound=0");
print "Connected? " . $sam->connected() . "\n";

$me = $sam->lookup("ME");
print "Sending to $me.\n";
$sam->send($me,"fooquux");

$sam->readprocess();
($source, $message) = @{ $sam->receive() };
print "$source -- $message";



