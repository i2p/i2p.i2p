#!/usr/bin/perl

## Copyright 2004 Brian Ristuccia. This program is Free Software;
## You can redistribute it and/or modify it under the same terms as
## Perl itself. 

package Net::SAM;

@ISA = ( "IO::Socket::INET" ); 

use strict;

use Switch;

use IO::Socket;
use IO::Select;

#use Net::SAM::StreamSession;
#use Net::SAM::DatagramSession;
#use Net::SAM::RawSession;

sub new {
    my ($class) = shift;
    my $type = ref($class) || $class;
    my $self = $type->SUPER::new("127.0.0.1:7656");


    ${*$self}->{incomingraw} = [];

    # Connect us to the local SAM proxy. 
    # my $samsock = IO::Socket::INET->new('127.0.0.1:7657');
    #$self->{samsock}=$samsock;

    # Say hello, read response. 
    $self->SUPER::send("HELLO VERSION MIN=1.0 MAX=1.0\n");
    
    while (! ${*$self}->{greeted}) { 
	$self->readprocess();
    }
    print "Created SAM object\n";
    return $self;
}

sub lookup {
    my $self = shift;
    my $name= shift;

    $self->SUPER::send("NAMING LOOKUP NAME=$name\n");
    undef ${*$self}->{RESULT};
    while (! ${*$self}->{RESULT}) {
	$self->readprocess();
    }
    if ( ${*$self}->{RESULT} == "OK" ) {
	return ${*$self}->{VALUE};
    } else {
	return undef;
    }
}

#sub createsession {
#    my ($self) = shift;
#    my ($sesstype) = shift;
#    print $self->{samsock} "SESSION CREATE STYLE=$SESSTYPE DESTINATION=$DEST, DIRECTION=
#}

#sub waitfor {
#    my ($self) = shift;
#    my ($prefix) = shift;
#    my ($response) = <$samsock>;#

 #   if $response =~ 
    

#}

sub readprocess {    
    my $self = shift;
    my $chunk;
    my $payload; 

    print "readprocess: " . $self->connected() . "\n";

    # May block if the SAM bridge gets hosed
    my $response = <$self>;

    print "readprocess: $!" . $self->connected() . "\n";

    chomp $response;
    my ($primative, $more, $extra) = split (' ', $response, 3);

    $primative = uc($primative);

    print "readprocess: " . $self->connected() . " -- $primative -- $more -- $extra\n";

    switch ($primative) {

	case "HELLO" {
	    if ($more !~ m/REPLY/ ) { die ("Bogus HELLO response") }
	    if ($extra =~ m/NOVERSION/ ) { 
		die("SAM Bridge Doesn't support my version") ;
	    }
	    $self->_hashtv($extra);
	    ${*$self}->{greeted} = 1;
	};
	case "SESSION" {
	    if ( $more !~ m/STATUS/ ) {
		die("Bogus SESSION response");
	    }
	    $self->_hashtv($extra);
	}
	case "STREAM" {};
	case "DATAGRAM" {
	    if ( $more !~ m/RECEIVE/ ) {
		die("Bogus DATAGRAM response.");
	    }
	    $self->_hashtv($extra);
	    push @{ ${*$self}->{incomingdatagram } }, 
		    [ ${*$self}->{DESTINATION},
		      $self->_readblock(${*$self}->{SIZE}) ];
		      
	};
	case "RAW" {
	    if ( $more !~ m/RECEIVE/ ) {
		die("Bogus RAW response.");
	    }
	    $self->_hashtv($extra);

	    push @{ $self->{incomingraw} }, $self->_readblock($self->{SIZE});
	};
	case "NAMING" {
	    if ( $more !~ m/REPLY/ ) {
		die("Bogus NAMING response");
	    }
	    $self->_hashtv($extra);
	};
	case "DEST" {};
    }
    return 1; 
}

sub getfh {
    # Return the FH of the SAM socket so apps can select() or poll() on it
    my $self = shift;
    return $self->{samsock};
}

sub _readblock {
    my $self = shift;
    my $size = shift; 
    my $chunk;
    my $payload;

    while ( $size > 1 )  {
	# XXX: May block. No error checking. 
	print "readblock: $size\n";
	$size -= $self->SUPER::recv($chunk, $size);
	$payload .= $chunk;
    }
    return $payload; 
}

sub _hashtv {
    my $self = shift;
    my $extra = shift;

    while ( $extra=~ m/(\S+)=(\S+)/sg ) {
	${*$self}->{$1}=$2;
	print "$1=$2\n"
    }
}

sub DESTROY {
    # Do nothing yet. 
}

#sub StreamSession {
#    my $self = shift;
#    return Net::SAM::StreamSession->new($self);
#}

#sub DatagramSession {
#    return Net::SAM::DatagramSession->new($self);
#}

#sub RawSession {
#    return Net::SAM::RawSession->new($self);
#}

1;
