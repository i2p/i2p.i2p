# Perl Basic SAM module
# created 2005 by postman (postman@i2pmail.org)
# This code is under GPL.
# This code is proof of concept


package Net::I2P::SAM;
require 5.001;
use strict;
use POSIX;


# we do not extend the IO::Socket:INET Package
# we just use it, so we keep our stuff sorted  
# This is a testsetup - the next release but
# be an extension of IO::Socket::INET;

use IO::Socket::INET;


use vars qw($VERSION @ISA @EXPORT);


sub new {
  my ($this,$host,$port,$debug) = @_;
  my $classname=ref($this) || $this;
  my $self = {};
  bless($self,$classname);
  

  #%{$self->{conf}}     = The hash where we store tunnel / SAM Settings
  #$self->{debug}       = Whether we should output debugging lines ( this is very helpful when having problems)
  #$self->{debugfile}   = Where to log
  #%{$self->{samreply}} = This hash stores the key=VALUE pairs of sam reply 
  #$self->{sock}        = The INET Socket over which we talk to the SAM bridge
  #$self->{inbuffer}    = a simple layouted inbuffer 
  #$self->{outbuffer}   = a simple layouted outbuffer 
  #                      ( the buffers are for dealing with differences between user wanted in/outputsizes
  #                        and what we're able to deliver on a machine side)
  #$self->{sessiontype} = unused ( will be used when we support different sessiontypes )
  #$self->{lookupresult}= contains the result of a SAM host/userhost naming lookup;
  #$self->{samerror}    = The human readable SAM error message ( if wanted )
  #$self->{streamid}    = The virtual streamid. It will be created upon connect;
  #$self->{bytestoread} = contains the reported size of a packet from sam
  #$self->{bytestosend} = contains the wanted size of a packet to sam
  #$self->{hasbanner}   = is set to 1 when we receive a greeting string upon connect
  
  #

  %{$self->{conf}}=();
  if($debug==1) {
  	$self->{debug}=1;
  }
  $self->{debugfile}="/tmp/sam-debug";
  $self->{debughandle}=undef;
  %{$self->{samreply}}=undef; 
  $self->{sock}=undef;
  $self->{inbuffer}=undef;
  $self->{outbuffer}=undef;
  $self->{sessiontype}=undef;
  $self->{lookupresult}=undef;
  $self->{samerror}=undef;
  $self->{streamid}=undef;
  $self->{bytestoread}=undef;
  $self->{bytestosend}=undef;
  $self->{hasbanner}=1;

  # state == -1 (no socket exists)
  # state == 0  (socket exists, but we're not helloed)
  # state == 1  (socket exists, and we're helloed)
  # state == 200 ( we bound a session)
  # state == 250 ( we have a virtual stream)
  
  $self->{state}=-1;

  
  # if the user has specified a host/port for contacting the SAM
  # Bridge, we'll override the defaults. Otherwise we just use 
  # defaults.
  # 
  if($host) {
    ${$self->{conf}}{host}=$host;
  } else { 
    ${$self->{conf}}{host}="127.0.0.1";
  }

  if($port) {
    ${$self->{conf}}{port}=$port;
  } else {
    ${$self->{conf}}{port}=7656;
  }
  # defaults for the tunnelparameters
  # see www.i2p.net/sam
  ${$self->{conf}}{iblength}=2;
  ${$self->{conf}}{oblength}=2;
  ${$self->{conf}}{ibquant}=2;
  ${$self->{conf}}{obquant}=2;
  ${$self->{conf}}{ibbackup}=0;
  ${$self->{conf}}{obbackup}=0;
  ${$self->{conf}}{ibvariance}=0;
  ${$self->{conf}}{obvariance}=0;
  ${$self->{conf}}{iballowzero}="true";
  ${$self->{conf}}{oballowzero}="true";
  ${$self->{conf}}{ibduration}=600000;
  ${$self->{conf}}{obduration}=600000;
  ${$self->{conf}}{ibnickname}="SAM-Perl-Destination";
  ${$self->{conf}}{obnickname}="SAM-Perl-Destination";
  
  

  # ok, let's open a simple debug file
  # if the user wants us
  if($self->{debug} == 1) {
    if ( ! open (LOGFILE,">" .$self->{debugfile})) {
      print "constructor: Cannot open debugging file, switching debugging off.";
      $self->{debug}=0;
    }
    
    # switch off the nerveracking buffer for the debugfile
    select((select(LOGFILE), $| = 1)[0]);
  }
  
  # ok now, lets move to the manager
  # he manages connecting and hello
  # if the proc_mgr returns 1 the user will get our
  # object reference. if not, he'll just get a 0.
  if($self->proc_mgr()) {
    return $self;
  } else {
    return 0;
  }
  
}

sub proc_mgr {
  my ($self) = @_;
  my $return=undef;
  
  if ($self->{state} == -1) {
    $self->log("Debug: SAM::proc_mgr(): Opening Socket Connection to ".${$self->{conf}}{host}.":".${$self->{conf}}{port});   
    $return=$self->sam_connect();
    if($return==0) {
      return 0;
    }
    if($return == 1) {
      $self->log("Debug: SAM::proc_mgr(): State Transition -1 => 0");
      $self->{state}=0;
    }
  }
  
  
  if($self->{state}==0) {
     if(!$self->hello()) {
       $self->log("Debug: SAM::proc_mgr(): Closing Socket");
       $self->{sock}->close();
       $self->log("State SAM::proc_mgr(): Transition 0 => -1");
       return 0;
     }
  }

  return 1; 

}


sub change_settings {
  my ($self,$list)=@_;
  my (@tochange,$id,$v,$k);
  
  # we cannot change the settings if we have a session already
  # so our state must be 1

  if($self->{state} >1) {
    $self->log("Debug: SAM::change_settings(): Cannot change tunnel settings after establishing a session.");
    return 0;
  }
  
  @tochange=split(",",$list);
  foreach $id (@tochange) {
    ($k,$v)=split("=",$id);
    lc($v);
    lc($k);
    

    $self->log("Debug: SAM::change_settings(): Parsed Setting: Key: $k - Value: $v");
    if($k eq "inbound.length" || $k eq "outbound.length") {
      # make v an int
      $v*1;
      if ($v >3 || $v < 0) {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (out of range)");
	return 0;
      }
      
      if(${$self->{conf}}{iballowzero} eq "false" && $k eq "inbound.length" && $v==0) {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (length forbidden by allowzero=false)");
	return 0;
      }
      
      if(${$self->{conf}}{oballowzero} eq "false" && $k eq "outbound.length" && $v==0) {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (length forbidden by allowzero=false)");
	return 0;
      }
      
      if($k eq "inbound.length") {
        ${$self->{conf}}{iblength}=$v;
      }
      if($k eq "outbound.length") {
        ${$self->{conf}}{oblength}=$v;
      }


      
    }
    
    if($k eq "inbound.quantity" || $k eq "outbound.quantity") {
      $v*1;
      if($v < 0 || $v >3 ) {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (out of range)");
	return 0;
      }

      if ($k eq "inbound.quantity") {
        ${$self->{conf}}{ibquant}=$v;
      }
      if($k eq "outbound.quantity") {
        ${$self->{conf}}{obquant}=$v;
      }
    }

    if($k eq "inbound.backupquantity" || $k eq "outbound.backupquantity") {
      $v*1;
      if($v < 0 || $v >2 ) {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (out of range)");
	return 0;
      }

      if ($k eq "inbound.backupquantity") {
        ${$self->{conf}}{ibbackup}=$v;
      }
      if($k eq "outbound.backupquantity") {
        ${$self->{conf}}{obbackup}=$v;
      }
    }

    if($k eq "inbound.lengthvariance" || $k eq "outbound.lengthvariance") {
      $v*1;
      if($v < -2  || $v >2 ) {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (out of range)");
	return 0;
      }

      if ($k eq "inbound.lengthvariance") {
        ${$self->{conf}}{ibvariance}=$v;
      }
      if($k eq "outbound.lengthvariance") {
        ${$self->{conf}}{ibvariance}=$v;
      }
    }
    
    if($k eq "inbound.duration" || $k eq "outbound.duration") {
      $v*1;
      if($v < 300000  || $v >1200000 ) {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (out of range)");
	return 0;
      }

      if ($k eq "inbound.duration") {
        ${$self->{conf}}{ibduration}=$v;
      }
      if($k eq "outbound.duration") {
        ${$self->{conf}}{obduration}=$v;
      }
    }
    
    if($k eq "inbound.nickname" || $k eq "outbound.nickname") {
      $v=substr($v,0,20);

      if ($k eq "inbound.nickname") {
        ${$self->{conf}}{ibnickname}=$v;
      }
      if($k eq "outbound.nickname") {
        ${$self->{conf}}{obnickname}=$v;
      }
    }
     
    if($k eq "inbound.allowzerohop" || $k eq "outbound.allowzerohop") {
      if($v ne "true" && $v ne "false") {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (must be boolean)");
	return 0;
      }
      
      if(${$self->{conf}}{iblength} ==0  && $k eq "inbound.allowzerohop" && $v eq "false") {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (length forbidden by allowzero=false)");
	return 0;
      }
      
      if(${$self->{conf}}{oblength} == 0  && $k eq "outbound.allowzerohop" && $v eq "false") {
        $self->log("Error: SAM::change_settings(): Wrong value $v is not valid for $k (length forbidden by allowzero=false)");
	return 0;
      }

      
      if ($k eq "inbound.allowzerohop") {
        ${$self->{conf}}{iballowzero}=$v;
      }
      if($k eq "outbound.allowzerohop") {
        ${$self->{conf}}{oballowzero}=$v;
      }
    }
     
    $self->log("Debug: SAM::change_settings(): Setting $k to $v");

    
  }
  
  return 1;

}


sub hello {
  my ($self) = @_;
  my $greeting="HELLO VERSION MIN=1.0 MAX=1.0\n";
  my $return=undef;
  my $return2=undef;
  
  $self->{outbuffer} .= $greeting;
  $return=$self->raw_send();
  if($return == 1) {
    if($self->raw_read()) {
      if($self->parse_sam("HELLO")) {
	$self->{state}=1;
	$self->log("Debug: SAM::hello(): State Transition 0 => 1");
	delete $self->{inbuffer};
	delete $self->{outbuffer};
	return 1;
      }
    } else {
      $self->log("Error: SAM::hello():  HELLO Failed. Cannot read HELLO response");
      return 0;
    }
  }

  if($return == 0) {
    $self->log("Error: SAM::hello():  HELLO Failed. Cannot send HELLO String");
    return 0;
  }

}


sub create_session {
  my ($self,$type,$destination,$direction) = @_;  
  my $line="SESSION CREATE ";
  my $return;
  
  uc($type);
  # WE ARE ONLY DOING STREAMS ATM
  if ($type ne "STREAM") {
    $self->log("Error: SAM::create_session(): SESSION failed. Only session of STREAM type are allowed");
    return 0;
  }
  if(length($destination)==0) {
    $self->log("Warn: SAM::create_session(): SESSION: fallback setting on destination to TRANSIENT.");
    $destination="TRANSIENT";
  }
  
  $line.="STYLE=$type DESTINATION=$destination";
  
  uc($direction);
  if($direction ne "BOTH" && $direction ne "CREATE" && $direction ne "RECEIVE") {
    $self->log("Warn: SAM::create_session(): SESSION: fallback setting on direction to BOTH.");
    $direction="BOTH";
  }
  
  $line .= " DIRECTION=$direction";
  $line .= " inbound.length=".${$self->{conf}}{iblength}." outbound.length=".${$self->{conf}}{oblength};
  $line .= " inbound.quantity=".${$self->{conf}}{ibquant}." outbound.quantity=".${$self->{conf}}{obquant};
  $line .= " inbound.backupQuantity=".${$self->{conf}}{ibbackup}." outbound.backupQuantity=".${$self->{conf}}{obbackup};
  $line .= " inbound.lengthVariance=".${$self->{conf}}{ibvariance}." outbound.lengthVariance=".${$self->{conf}}{obvariance};
  $line .= " inbound.duration=".${$self->{conf}}{ibduration}." outbound.duration=".${$self->{conf}}{obduration};
  $line .= " inbound.nickname=".${$self->{conf}}{ibnickname}." outbound.nickname=".${$self->{conf}}{obnickname};
  $line .= " inbound.allowZeroHop=".${$self->{conf}}{iballowzero}." outbound.allowZeroHop=".${$self->{conf}}{oballowzero};
  $line .="\n";

  $self->{outbuffer}.=$line;
  $return=$self->raw_send();
 
  if($return == 1) {
    if($self->raw_read()) {
      if($self->parse_sam("SESSION ")) {
	$self->{state}=200;
	$self->log("Debug: SAM::create_session(): State Transition 1 => 200");
	# flush the whole inbuffer;
	delete $self->{inbuffer};
	delete $self->{outbuffer};
	return 1;
      }
    } else {
      $self->log("Error: SAM::create_session(): SESSION Failed. Cannot read SAM Response");
      return 0;
    }
  }

  if($return == 0) {
    $self->log("Error: SAM::create_session(): SESSION Failed. Cannot send SESSION String");
    return 0;
  }

 

}




sub parse_sam {

  # this is the main function that parses all SAM replies
  # depending on wanted action and state we'll set different
  # properties like $self->{bytestoread} etc.
  # parse_sam does not CUT OUT SAM messages from the payloads 
  # (look at $self->recv for that)
  #
  my ($self,$state) = @_;
  my (@data,$id,$k,$v);
  %{$self->{samreply}}=();
  
  
  uc($state);
  
  if( $self->{inbuffer} =~ /^(.[^ ]*) (.[^ ]*) (.*)$/m ) {
    ${$self->{samreply}}{COMMAND}=$1;
    ${$self->{samreply}}{REPLY}=$2;
    
    @data=split(" ",$3);
    
    foreach $id (@data) {
      ($k,$v)=split("=",$id);

      #print "k: $k - v: $v\n";
      ${$self->{samreply}}{$k}=$v;
    }
  } else {
    $self->log("Error: SAM::parse_sam(): Could not parse the SAM Reply. Has the specs changed?");
    return 0;
  }
 
  if($state eq "HELLO") {
    if (${$self->{samreply}}{COMMAND} ne "HELLO") {
      $self->log("Error: SAM::parse_sam(): We're in state HELLO but got no proper response from SAM");
      return 0;
    }
    
    if(${$self->{samreply}}{REPLY} eq "REPLY") {
      if(${$self->{samreply}}{RESULT}  eq "OK") {
        $self->log("Debug: SAM::parse_sam(): Got a OK result for HELLO");
	return 1;
      } else {
        $self->log("Error :SAM::parse_sam(): Got no OK Result for HELLO");
	return 0;
      }
    } else {
      $self->log("Error: SAM::parse_sam(): Unknown Reply type for HELLO dialogue");
      return 0;
    }
  }

  if($state eq "SESSION") {
    if (${$self->{samreply}}{COMMAND} ne "SESSION") {
      $self->log("Error: SAM::parse_sam(): We're in state SESSION but got no proper response from SAM");
      return 0;
    }
    
    if(${$self->{samreply}}{REPLY} eq "STATUS") {
      if(${$self->{samreply}}{RESULT} eq "OK") {
        $self->log("Debug: SAM::parse_sam(): Got a OK result for SESSION");
	return 1;
      } else {
        $self->log("Error: SAM::parse_sam(): Got no OK Result for SESSION: ".${$self->{samreply}}{RESULT});
	return 0;
      }
    } else {
      $self->log("Error: SAM::parse_sam(): Unknown Reply type for SESSION dialogue");
      return 0;
    }
  }

  if($state eq "NAMING") {
    if (${$self->{samreply}}{COMMAND} ne "NAMING") {
      $self->log("Error: SAM::parse_sam(): We're in state NAMING but got no proper response from SAM");
      return 0;
    }
    
    if(${$self->{samreply}}{REPLY} eq  "REPLY") {
      if(${$self->{samreply}}{RESULT} eq "OK") {
        $self->log("Debug: SAM::parse_sam(): Got a OK result for NAMING");
	$self->{lookupresult}=${$self->{samreply}}{VALUE};
	return 1;
      } else {
        $self->log("Error: SAM::parse_sam(): Got no OK Result for NAMING: ".${$self->{samreply}}{RESULT});
	return 0;
      }
    } else {
      $self->log("Error: SAM::parse_sam(): Unknown Reply type for NAMING dialogue");
      return 0;
    }
  }

  if($state eq "STREAM") {
    if (${$self->{samreply}}{COMMAND} ne "STREAM") {
      $self->log("Error: SAM::parse_sam(): We're in state STREAM but got no proper response from SAM");
      return 0;
    }
    
    # CREATING STREAMS
    if(${$self->{samreply}}{REPLY} eq "STATUS") {
      if(${$self->{samreply}}{RESULT} eq "OK") {
        $self->log("Debug: SAM::parse_sam(): STREAM STATUS OK - Next action is awaited");
	return 1;
      } else {
        $self->log("Error: SAM::parse_sam(): STREAM STATUS NOT OK.: ".${$self->{samreply}}{RESULT});
	
	if(length(${$self->{samreply}}{MESSAGE}) == 0) { 
	  $self->{samerror}=${$self->{samreply}}{RESULT};
	} else {
	  $self->{samerror}=${$self->{samreply}}{MESSAGE};
	}
	return 0;
      }
    } 
    
    # SEND/RECEIVING STREAMS
    # this can happen directly after a connect 
    if(${$self->{samreply}}{REPLY} eq "RECEIVED") {
      if(${$self->{samreply}}{SIZE} > 0) {
        $self->log("Debug: SAM::parse_sam(): SAM notify: RECEIVED Data. SIZE=".${$self->{samreply}}{SIZE});
	$self->{bytestoread}=${$self->{samreply}}{SIZE};
	return 1;
      } else {
        $self->log("Error: SAM::parse_sam(): Received empty payload");
	return 0;
      }
    }
    
    # STREAMS are closed - bad thing
    # this can happen directly after a connect 
    if(${$self->{samreply}}{REPLY} eq "CLOSED") {
      $self->log("Error: SAM::parse_sam(): Stream is closed: We need to interupt the session");
      return 0;
    }


    
  }


}


sub raw_send {

  # this function sends a crafted SAM request + payload to the
  # SAM socket
  my ($self) = @_;
  my $return;
  
  $self->log("Debug: SAM::raw_send(): >>> ".$self->{outbuffer});
  $return = $self->{sock}->send($self->{outbuffer},0);
  if(! defined($return)) {
    $self->log("Error: SAM::raw_send(): Cannot send to Socket");
    $self->close();
    return 0;
  }
  
  if ( $return == length($self->{outbuffer}) || $!{EWOULDBLOCK} ) {
    substr($self->{outbuffer},0, $return) = '';
    delete $self->{outbuffer} unless length($self->{outbuffer});
    return 1;
  } else {
    $self->log("Error :SAM::raw_send(): Could send, but length does not match. Closing");
    $self->close();
    return 0;
  }
}

sub raw_read {
  my ($self,$size) = @_;
  my $input;
  my $data;

  # this reads SAM replies from the SAM socket and fills the
  # inbuffer

  if(!$size || $size > POSIX::BUFSIZ) {
    $size=POSIX::BUFSIZ;
  }

  
  $input = $self->{sock}->recv($data, $size, 0);
  
  if(defined($input) && length($data) >= 1) {

  
    $self->log("Debug: SAM::raw_read(): <<< $data");
    if(length($self->{inbuffer}) == 0 ) {
      $self->{inbuffer} = $data;
    } else {
      $self->{inbuffer} .= $data;
    }
    
    return 1;    
  
  } else {
    if ( $!{EAGAIN} ) {
      $self->{bytestoread}=0;
      return 1;
    } else {
      return 0;
    }
  }
  

  
}

sub sam_connect {
  # bsic connect to the sam bridge socket itself
  my ($self)=@_;
  my $return=undef;
 

 
  $return=$self->{sock}=IO::Socket::INET->new(${$self->{conf}}{host}.":".${$self->{conf}}{port}); 
  
  if($return==0) {
    $self->log("Debug: SAM::sam_connect(): Connection failed to ".${$self->{conf}}{host}.":".${$self->{conf}}{port}); 
    return 0;
  } else {
    $self->log("Debug: SAM::sam_connect(): Connection established to ".${$self->{conf}}{host}.":".${$self->{conf}}{port}); 
    return 1;
  }
}

sub send {

  # the public fumction to send data to sam
  # the user gives his payload and we create
  # valid SAM requests + payload from it ( as much as needed)
  my ($self,$content,$flags)=@_;
  my $return;
  my $maxsize=(POSIX::BUFSIZ-100);
  
  if($self->{state}!=250) {
    $self->log("Error: SAM::send():  wrong state for send command: Needed:250 Recent:".$self->{state});
    return 0;
  }
  # ok, what can happen?
  # it could be that $content is far bigger than
  # POSIX::BUFSIZ; so we need to do a little loop
  # apart from that sending is in our hand
  
  if(length($content) > $maxsize) {
    $self->{outbuffer}.="STREAM SEND ID=".$self->{streamid}." SIZE=$maxsize\n".substr($content,0,$maxsize);
    $content=substr($content,$maxsize,length($content));
  } else {
    $self->{outbuffer}.="STREAM SEND ID=".$self->{streamid}." SIZE=".length($content)."\n".$content;
  }
  
  if( $self->raw_send()) {
    return 1;
  } else {
    $self->log("Error: SAM::send(): Could not send. Closing Link");
  }
}

sub recv {
  my($self,$varname,$size,$flags)=@_;
  my $return;
  my $tunebuffer;
  my $counter;
  my $chunk;


  # main recv wrapper. We need to invest a few thoughts
  # for this. To the user it will be like a $socket->recv
  # (hopefully)
  # at first some sanity checks
  if(!$flags) {
    $flags=0;
  }
  
  $self->{bytestoread}=0;
  # size must not exceed The posix limit;
  
  if(!$size || $size > POSIX::BUFSIZ) {
    $self->log("Warn: SAM::recv(): Setting buffersize to POSIX::BUFSIZ");
    $size=POSIX::BUFSIZ;
  }
  

  # nobody should call is prior to state 250
  if($self->{state}!=250) {
    $self->log("Error: SAM::recv():  wrong state for rcv command: Needed:250 Recent:".$self->{state});
    return 0;
  }


  # we have a greeting banner left from connect
  # flush it to the user. This can happen on several services
  # like smtp/pop3/nntp but not on HTTP and other stuff
  
  if($self->{hasbanner}) {
    
    #print "D: ".$self->{inbuffer};
    
    if(length($self->{inbuffer}) >0 ) {
      $chunk=substr($self->{inbuffer},0, $size);
      $self->{hasbanner}=0;
      substr($self->{inbuffer},0, $size)='';
      return $chunk;
    } else { 
      $self->log("Error: SAM::recv():  Should have a banner but i have empty inbuffer?");
      return 0;
    }
    # should never reach here    
    return 1;
  }
  # when there's something in the inbuffer left
  # flush it to the user. If the amount of data is bigger than
  # the userspecified limit, only transfer $size Bytes to the 
  # client
  if(length($self->{inbuffer}) > 0) {
    $chunk=substr($self->{inbuffer},0, $size);
    substr($self->{inbuffer},0, $size)='';
    return $chunk;
  }

  # OK, we got noting in the inbuffer
  # we'll fetch a new chunk of data and then add the data to the inbuffer
  # if bytestoread is bigger than POSIX::BUFSIZ we'll internally use 
  # a loop of reads and fill the buffer til bytestoread is 0

  if(length($self->{inbuffer}) == 0) {
    # read the first packet
    if($self->raw_read()) {
      if($self->parse_sam("STREAM") && ${$self->{samreply}}{REPLY} eq "RECEIVED") {
        # it's possible that the packet does not contain any payload at all!! 
	# if this is the case we need 
	if($self->{inbuffer}=~/^.*$/) {
          $self->log("Warn: SAM::recv(): Got only only one line from SAM - but i expected some payload too. Calling raw_read again ");
	  $self->raw_read();
	}
	# ok, cut the SAM HEADER from the payload
        $self->{inbuffer}=substr($self->{inbuffer},(length($self->{inbuffer})-$self->{bytestoread}), length($self->{inbuffer}));
	$self->log("Debug: SAM::recv(): Recived a Stream Packet and cut the SAM header out");
	# ok, check if bytestoread is still bigger than our buffersize
	# this means we can load more. Dangerous loop but well...
	
	while(length($self->{inbuffer}) < $self->{bytestoread}) {
	  # this should definately end some day
	  $counter++;
	  if($counter > 10000) {
            $self->log("Error: SAM::recv(): WTF, could not fill inbuffer as predicted by SAM header");
            last;
	  }
	  # read as long til we have read all of the payload provided by SAM
	  
          $self->log("Debug: SAM::recv(): Load another chunk. Buffersize:".length($self->{inbuffer})." / Bytestoread:".$self->{bytestoread} );
	  $self->raw_read();
	}
	
      } else {
        $self->log("Error: SAM::recv(): parse_sam() did not succeed. Interupting");
	delete $self->{inbuffer};
	return 0;
      }

    } else {
      $self->log("Error: SAM::recv(): Could not read from the socket");
      delete $self->{inbuffer};
      return 0;
    }

   
    $chunk=substr($self->{inbuffer},0, $size);
    substr($self->{inbuffer},0, $size)='';
    return $chunk;
  }
  
  return 1;
  
}

sub lookup {
  my($self,$name)=@_;
  my $return;
  
  $self->{outbuffer}="NAMING LOOKUP NAME=$name\n";
  $return=$self->raw_send();
 
  if($return == 1) {
    if($self->raw_read()) {
      if($self->parse_sam("NAMING")) {
	$self->log("Debug: SAM::lookup(): Naming Lookup successful");
	delete $self->{inbuffer};
	delete $self->{outbuffer};
        return $self->{lookupresult};	
      }
    } else {
      $self->log("Error :SAM::lookup(): NAMING Failed. Cannot read SAM Response");
      return 0;
    }
  }

  if($return == 0) {
    $self->log("Error :SAM::lookup(): NAMING Failed. Cannot send NAMING String");
    return 0;
  }

}


sub sam_close {
  my ($self) =@_;
  $self->log("Debug: SAM::sam_close(): Closing Socket to SAM");
  $self->{sock}->close();
  return 1;
}


sub close {
  my ($self)=@_;
  my $return;
  $self->{outbuffer}.="STREAM CLOSE ID=".$self->{streamid}."\n";
  $self->log("Debug: SAM::close(): CLosing Stream with id: ".$self->{streamid});
  $return=$self->raw_send();
  # well, we do not care wether this worked or not
  $self->sam_close();
  return 1;
}


sub connect {
  my ($self,$destination)=@_;
  my $return;

  $self->{streamid}= int(rand(200000000));
  if(length($destination) == 0) {
    $self->log("Error: SAM::connect(): need I2P destination to connect to with the SAM Bridge");
    return 0;
  }
  
  $self->{outbuffer}.="STREAM CONNECT ID=".$self->{streamid}." DESTINATION=$destination\n";
  $return=$self->raw_send();
 
  if($return == 1) {
    if($self->raw_read()) {
      if($self->parse_sam("STREAM")) {
	if(${$self->{samreply}}{REPLY} eq "STATUS") {
	  $self->{state}=250;
	  $self->log("Debug: SAM::connect(): State Transition 200 => 250");
	  # flush the whole inbuffer;
	  delete $self->{inbuffer};
	  delete $self->{outbuffer};
	  return 1;
	}
	
	if(${$self->{samreply}}{REPLY} eq "RECEIVED") {
	  $self->{state}=250;
	  $self->log("Debug: SAM::connect(): State Transition 200 => 250. Got a banner");
          delete $self->{inbuffer};

	  #print "D: toread:".$self->{bytestoread};
	  #print "D: buffer:".$self->{inbuffer}."\n";
	  #print "D: buffersize:".length($self->{inbuffer})."\n";
	  
	  $self->raw_read();
	  #print "D: toread:".$self->{bytestoread};
	  #print "D: buffer:".$self->{inbuffer}."\n";
	  #print "D: buffersize:".length($self->{inbuffer})."\n";

          $self->{inbuffer}=substr($self->{inbuffer}, 0, $self->{bytestoread});
	  $self->{hasbanner}=1;

	  #print "D: toread:".$self->{bytestoread};
	  #print "D: buffer:".$self->{inbuffer}."\n";
	  #print "D: buffersize:".length($self->{inbuffer})."\n";
	  
	  return 1;	
	}
	
      }
    } else {
      $self->log("Error: SAM::connect(): STREAM Failed. Cannot read SAM Response");
      return 0;
    }
  }

  if($return == 0) {
    $self->log("Error: SAM::connect(): STREAM Failed. Cannot send SESSION String");
    return 0;
  }
  

}



sub log {
  my($self,$line)=@_;
  if($line=~/.*\n$/) {
    chomp $line;
  }
  if ( $self->{debug} ) {
    print LOGFILE "$line\n";
  }
  return 1;

}


