#pragma once

#include <stdio.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <errno.h>

typedef struct sockaddr *sad; /* A necesary dummy typedef */

int port_check(int portNum)
{
  int sock; /* Socket that will be bind */
  struct sockaddr_in sin; /* Address Structure */
  
  /* Create the socket */
  /* PF_INET is the option for make a TCP socket.
   You can try "man socket" for more info */
  sock = socket( PF_INET, SOCK_STREAM, 0 );
  
  /* The socket creation failed */
  if ( 0 > sock ) {
    perror( "socket" );
    return ( -1 );
  }
  
  /* Address */
  sin.sin_family = AF_INET;
  sin.sin_port = htons( portNum ); /* htons() convert the number
                                    to big endian */
  sin.sin_addr.s_addr = INADDR_ANY;
  
  /* We bind the socket to the port PORT to check if
   the port is in use */
  if ( 0 > bind( sock, (sad)&sin, sizeof( sin ) ) ) {
    /* Bind failed, now we can check if the address is
     in use */
    
    if ( EADDRINUSE == errno ) {
      /* We put the code necesary to manage this case */
      printf( "The TCP port %d is in use.\n", portNum );
    }
    
    else
    /* If the error were other than EADDRINUSE, we print it */
      perror( "bind" );
    
    return 1;
  }
  
  /* If we arrive to this point, the port weren't in use and
   we have it attached to our program. We can close
   the socket or use it */
  close( sock ); /* Close the socket */
  
  return 0;
}


