package i2p.bote.network;

import i2p.bote.EmailDestination;

public class EmailAddressResolver {

    /**
     * Looks up a key pair for an email address. This method blocks.
     * The local address book is searched first, then the distributed
     * email directory.
     * @param emailAddress
     * @return An <code>EmailDestination</code>, or <code>null</code> if none is found
     */
    public EmailDestination getDestination(String emailAddress) {
        // TODO
        return null;
    }
}