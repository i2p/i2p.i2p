#!/usr/bin/perl

package Net::SAM::DatagramSession;

use Net::SAM;

@ISA = ("Net::SAM");

sub new {
    my ($class) = shift;
    my ($dest , $direction, $options) = shift; 

    my $self = $class->SUPER::new(@_);

    $self->SUPER::send("SESSION CREATE STYLE=DATAGRAM DESTINATION=$dest DIRECTION=$direction $options\n");

    undef ${*$self}->{RESULT};

    while ( ! ${*$self}->{RESULT} ) {
	$self->readprocess() || return undef;
	   
    }

    if ( ${*$self}->{RESULT} == "OK" ) {
	return $self;
    } else {
	return undef; # sorry.
    }
}

sub send {
    my $self = shift;
    my $destination = shift;
    my $message = shift;
    my $size = length($message);
    $self->SUPER::send("DATAGRAM SEND DESTINATION=$destination SIZE=$size\n$message");
}

sub receive {
    my $self = shift;

    # Shift one off the fifo array. Returns undef if none wait. 
    return shift @{ $self->{incomingdatagram} };
}



1;
