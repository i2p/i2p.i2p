#!/usr/bin/perl

## Copyright 2004 Brian Ristuccia. This program is Free Software;
## You can redistribute it and/or modify it under the same terms as
## Perl itself. 

package Net::SAM;

@ISA = ( "IO::Socket::INET" ); 

use strict;

use POSIX;

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


sub readprocesswrite {
    my $self = shift;
    $self->readprocess();
    $self->dowrite();
}

sub doread {
    my $self = shift;
    my $rv;
    my $data;
    
    $rv = $self->recv($data, $POSIX::BUFSIZE, 0);

    if ( defined($rv) && ( length($data) >= 1 ) ) {
	# We received some data. Put it in our buffer.
	${*$self}->{inbuffer} += $data;
    } else {
	# No data. Either we're on a non-blocking socket, or there 
	# was an error or EOF
	if ( $!{EAGAIN} ) {
	    return 1;
	} else {
	    # I suppose caller can look at $! for details
	    return undef; 
	}
    }
}


sub dowrite {
    my $self = shift;
    my $rv;
    my $data; 

    $rv = $self->send(${*$self}->{outbuffer}, 0);
    
    if ( ! defined($rv) ) {
	warn "SAM::dowrite - Couldn't write for no apparent reason.\n";
	return undef; 
    }

    if ( $rv == length(${*$self}->{outbuffer}) || $!{EWOULDBLOCK} ) {
	substr(${*$self}->{outbuffer},0, $rv) = ''; # Remove from buffer

	# Nuke buffer if empty
	delete ${*$self}->{outbuffer} unless length(${*$self}->{outbuffer});
    } else {
	# Socket closed on us or something?
	return undef;
    }
}

sub messages {
    my $self = shift;
    
    return @{ ${*$self}->{messages} };
}

sub queuemessage {

    my $self = shift;
    my $message = shift; 
    
    push @{ ${*$self}->{messages} } , $message;
}

sub unqueuemessage {
    my $self = shift;
    
    return unshift(@{ ${*$self}->{messages} } );
    
}

sub readprocess {
    my $self = shift;

    $self->doread();
    $self->process();
}

sub process {    
    my $self = shift;
    my %tvhash; 
    my $payload; 
    

    # Before we can read any new messages, if an existing message has payload
    # we must read it in. Otherwise we'll create garbage messages containing
    # the payload of previous messages. 

    if ( ${*$self}->{payloadrequired} >= 1 ) {

	if ( length( ${*$self}->{inbuffer} ) >= ${*$self}->{payloadrequired} ) {
	    # Scarf payload from inbuffer into $payload
	    $payload = substr(${*$self}->{inbuffer}, 0, 
			      ${*$self}->{payloadrequired});
	    
	    # Nuke payload from inbuffer
	    substr(${*$self}->{inbuffer}, 0,
		   ${*$self}->{payloadrequired} ) = '';
	    
	    # Put message with payload into spool
	    push @{ ${*$self}->{messages} } , 
	    ${*$self}->{messagerequiringpayload}.$payload;

	    # Delete the saved message requiring payload
	    delete ${*$self}->{messagerequiringpayload};
	} else {
	    # Insufficient payload in inbuffer. Try again later. 
	    return 1;
	}

    }


    if ( ${*$self}->{inbuffer} =~ s/(.*\n)// ) {
	%tvhash = $self->_hashtv($1); # Returns a tag/value hash
	if ( $tvhash{SIZE} ) {
	    # We've got a message with payload on our hands. :(
	    ${*$self}->{payloadrequired} = $tvhash{SIZE}; 
	    ${*$self}->{messagerequiringpayload} = $1; 
	    return 1; # Could call ourself here, but we'll get called again. 
	} else {
	    push @{ ${*$self}->{messages} } , $1;
	}
    }
    return 1; 
}

# sub junk {


#     print "readprocess: " . $self->connected() . "\n";

#     # May block if the SAM bridge gets hosed
#     my $response = <$self>;

#     print "readprocess: $!" . $self->connected() . "\n";

#     chomp $response;
#     my ($primative, $more, $extra) = split (' ', $response, 3);

#     $primative = uc($primative);

#     print "readprocess: " . $self->connected() . " -- $primative -- $more -- $extra\n";

#     switch ($primative) {

# 	case "HELLO" {
# 	    if ($more !~ m/REPLY/ ) { die ("Bogus HELLO response") }
# 	    if ($extra =~ m/NOVERSION/ ) { 
# 		die("SAM Bridge Doesn't support my version") ;
# 	    }
# 	    $self->_hashtv($extra);
# 	    ${*$self}->{greeted} = 1;
# 	};
# 	case "SESSION" {
# 	    if ( $more !~ m/STATUS/ ) {
# 		die("Bogus SESSION response");
# 	    }
# 	    $self->_hashtv($extra);
# 	}
# 	case "STREAM" {};
# 	case "DATAGRAM" {
# 	    if ( $more !~ m/RECEIVE/ ) {
# 		die("Bogus DATAGRAM response.");
# 	    }
# 	    $self->_hashtv($extra);
# 	    push @{ ${*$self}->{incomingdatagram } }, 
# 		    [ ${*$self}->{DESTINATION},
# 		      $self->_readblock(${*$self}->{SIZE}) ];
		      
# 	};
# 	case "RAW" {
# 	    if ( $more !~ m/RECEIVE/ ) {
# 		die("Bogus RAW response.");
# 	    }
# 	    $self->_hashtv($extra);

# 	    push @{ $self->{incomingraw} }, $self->_readblock($self->{SIZE});
# 	};
# 	case "NAMING" {
# 	    if ( $more !~ m/REPLY/ ) {
# 		die("Bogus NAMING response");
# 	    }
# 	    $self->_hashtv($extra);
# 	};
# 	case "DEST" {};
#     }
#     return 1; 
# }

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
    my $tvstring = shift;
    my $tvhash;

    while ( $tvstring =~ m/(\S+)=(\S+)/sg ) {
	$tvhash->{$1}=$2;
	print "hashtv: $1=$2\n"
    }
    return $tvhash;
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
