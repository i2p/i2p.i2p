#!/usr/bin/perl

package Net::SAM::RawSession;

use Net::SAM;

@ISA = ("Net::SAM");

sub new {
    my ($class) = shift;
    my ($dest , $direction, $options) = shift; 

    my $self = $class->SUPER::new(@_);

    $self->send("SESSION CREATE STYLE=RAW DESTINATION=$dest DIRECTION=$direction $options\n");

    undef $self->{result};
    while ( ! $self->{RESULT} ) {
	$self->readprocess();
    }

    if ( $self->{RESULT} == "OK" ) {
	return $self;
    } else {
	return 0; # sorry.
    }
}

sub send {
    my $self = shift;
    my $destination = shift;
    my $message = shift;
    my $samsock = $self->{samsock};
    my $size = length($message);
    print $samsock "RAW SEND DESTINATION=$destination SIZE=$size\n$message";
}

sub receive {
    my $self = shift;

    # Shift one off the fifo array. Returns undef if none wait. 
    return shift @{ $self->{incomingraw} };
}

1;
