package net.i2p.crypto;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.util.RandomSource;

public class ElGamalTest extends TestCase{
    private I2PAppContext _context;
    
    // Following 4 String arrays for use with the testVerify* methods
    
    private static final String UNENCRYPTED[] = new String[] { 
        "", 
        "hello world",
        "1234567890123456789012345678901234567890123456789012345678901234567890" +
        "1234567890123456789012345678901234567890123456789012345678901234567890" +
        "1234567890123456789012345678901234567890123456789012345678901234567890" +
        "123456789012",
        "\0x00",
        "\0x00\0x00\0x00",
        "\0x00\0x01\0x02\0x00",
    };
    private static final String PUBLIC_KEY = new String(
        "pOvBUMrSUUeN5awynzbPbCAwe3MqWprhSpp3OR7pvdfm9PhWaNbPoKRLeEmDoUwyNDoHE0" +
        "E6mcZSG8qPQ8XUZFlczpilOl0MJBvsI9u9SMyi~bEqzSgzh9FNfS-NcGji3q2wI~Ux~q5B" +
        "KOjGlyMLgd1nxl5R5wIYL4uHKZNaYuArsRYmtV~MgMQPGvDtIbdGTV6aL6UbOYryzQSUMY" +
        "OuO3S~YoBjA6Nmi0SeJM3tyTxlI6U1EYjR6oQcI4SOFUW4L~8pfYWijcncCODAqpXVN6ZI" +
        "AJ3a6vjxGu56IDp4xCcKlOEHgdXvqmEC67dR5qf2btH6dtWoB3-Z6QPsS6tPTQ=="
        );
    private static final String PRIVATE_KEY = new String(
        "gMlIhURVXU8uPube20Xr8E1K11g-3qZxOj1riThHqt-rBx72MPq5ivT1rr28cE9mzOmsXi" +
        "bbsuBuQKYDvF7hGICRB3ROSPePYhcupV3j7XiXUIYjWNw9hvylHXK~nTT7jkpIBazBJZfr" +
        "LJPcDZTDB0YnCOHOL-KFn4N1R5B22g0iYRABN~O10AUjQmf1epklAXPqYlzmOYeJSfTPBI" +
        "E44nEccWJp0M0KynhKVbDI0v9VYm6sPFK7WrzRyWwHL~r735wiRkwywuMmKJtA7-PuJjcW" +
        "NLkJwx6WScH2msMzhzYPi8JSZJBl~PosX934l-L0T-KNV4jg1Ih6yoCnm1748A=="
        );
    private static final String ENCRYPTED[] = new String[] {
        "AMfISa8KvTpaC7KXZzSvC2axyiSk0xPexBAf29yU~IKq21DzaU19wQcGJg-ktpG4hjGSg7" +
        "u-mJ07b61yo-EGmVGZsv3nYuQYW-GjvsZQa9nm98VljlMtWrxu7TsRXw~SQlWQxMvthqJB" +
        "1A7Y7Qa~C7-UlRytkD-cpVdgUfM-esuMWmjGs6Vc33N5U-tce5Fywa-9y7PSn3ukBO8KGR" +
        "wm7T12~H2gvhgxrVeK2roOzsV7f5dGkvBQRZJ309Vg3j0kjaxWutgI3vli0pzDbSK9d5NR" +
        "-GUDtdOb6IIfLiOckBegcv6I-wlSXjYJe8mIoaK45Ok3rEpHwWKVKS2MeuI7AmsAWgkQmW" +
        "f8irmZaKc9X910VWSO5GYu6006hSc~r2TL3O7vwtW-Z9Oq~sAam9av1PPVJzAx8A4g~m~1" +
        "avtNnncwlChsGo6mZHXqz-QMdMJXXP57f4bx36ZomkvpM-ZLlFAn-a~42KQJAApo4LfEyk" +
        "7DPY2aTXL9ArOCNQIQB4f8QLyjvAvu6M3jzCoGo0wVX6oePfdiokGflriYOcD8rL4NbnCP" +
        "~MSnVzC8LKyRzQVN1tDYj8~njuFqekls6En8KFJ-qgtL4PiYxbnBQDUPoW6y61m-S9r9e9" +
        "y8qWd6~YtdAHAxVlw287~HEp9r7kqI-cjdo1337b7~5dm83KK45g5Nfw==",

        "AIrd65mG1FJ~9J-DDSyhryVejJBSIjYOqV3GYmHDWgwLchTwq-bJS7dub3ENk9MZ-C6FIN" +
        "gjUFRaLBtfwJnySmNf8pIf1srmgdfqGV2h77ufG5Gs0jggKPmPV~7Z1kTcgsqpL8MyrfXr" +
        "Gi86X5ey-T0SZSFc0X1EhaE-47WlyWaGf-~xth6VOR~KG7clOxaOBpks-7WKZNQf7mpQRE" +
        "4IsPJyj5p1Rf-MeDbVKbK~52IfXSuUZQ8uZr34KMoy4chjn6e-jBhM4XuaQWhsM~a3Q-zE" +
        "pV-ea6t0bQTYfsbG9ch7pJuDPHM64o5mF9FS5-JGr7MOtfP7KDNHiYM2~-uC6BIAbiqBN8" +
        "WSLX1mrHVuhiM-hiJ7U4oq~HYB6N~U980sCIW0dgFBbhalzzQhJQSrC1DFDqGfL5-L25mj" +
        "ArP8dtvN0JY3LSnbcsm-pT9ttFHCPGomLfaAuP7ohknBoXK0j9e6~splg5sUA9TfLeBfqc" +
        "Lr0Sf8b3l~PvmrVkbVcaE8yUqSS6JFdt3pavjyyAQSmSlb2jVNKGPlrov5QLzlbH7G~AUv" +
        "IehsbGQX5ptRROtSojN~iYx3WQTOa-JLEC-AL7RbRu6B62p9I0pD0JgbUfCc4C4l9E9W~s" +
        "MuaJLAXxh0b2miF7C5bzZHxbt~MtZ7Ho5qpZMitXyoE3icb43B6Y1sbA==", 

        "ACjb0FkTIQbnEzCZlYXGxekznfJad5uW~F5Mbu~0wtsI1O2veqdr7Mb0N754xdIz7929Ti" +
        "1Kz-CxVEAkb3RBbVNcYHLfjy23oQ4BCioDKQaJcdkJqXa~Orm7Ta2tbkhM1Mx05MDrQaVF" +
        "gCVXtwTsPSLVK8VwScjPIFLXgQqqZ5osq~WhaMcYe2I2RCQLOx2VzaKbT21MMbtF70a-nK" +
        "WovkRUNfJEPeJosFwF2duAD0BHHrPiryK9BPDhyOiyN82ahOi2uim1Nt5yhlP3xo7cLV2p" +
        "6kTlR1BNC5pYjtsvetZf6wk-solNUrJWIzcuc18uRDNH5K90GTL6FXPMSulM~E4ATRQfhZ" +
        "fkW9xCrBIaIQM49ms2wONsp7fvI07b1r0rt7ZwCFOFit1HSAKl8UpsAYu-EsIO1qAK7vvO" +
        "UV~0OuBXkMZEyJT-uIVfbE~xrwPE0zPYE~parSVQgi~yNQBxukUM1smAM5xXVvJu8GjmE-" +
        "kJZw1cxaYLGsJjDHDk4HfEsyQVVPZ0V3bQvhB1tg5cCsTH~VNjts4taDTPWfDZmjtVaxxr" +
        "PRII4NEDKqEzg3JBevM~yft-RDfMc8RVlm-gCGANrRQORFii7uD3o9~y~4P2tLnO7Fy3m5" +
        "rdjRsOsWnCQZzw37mcBoT9rEZPrVpD8pjebJ1~HNc764xIpXDWVt8CbA==", 

        "AHDZBKiWeaIYQS9R1l70IlRnoplwKTkLP2dLlXmVh1gB33kx65uX8OMb3hdZEO0Bbzxkkx" +
        "quqlNn5w166nJO4nPbpEzVfgtY4ClUuv~W4H4CXBr0FcZM1COAkd6rtp6~lUp7cZ8FAkpH" +
        "spl95IxlFM-F1HwiPcbmTjRO1AwCal4sH8S5WmJCvBU6jH6pBPo~9B9vAtP7vX1EwsG2Jf" +
        "CQXkVkfvbWpSicbsWn77aECedS3HkIMrXrxojp7gAiPgQhX4NR387rcUPFsMHGeUraTUPZ" +
        "D7ctk5tpUuYYwRQc5cRKHa4zOq~AQyljx5w5~FByLda--6yCe7qDcILyTygudJ4AHRs1pJ" +
        "RU3uuRTHZx0XJQo~cPsoQ2piAOohITX9~yMCimCgv2EIhY3Z-mAgo8qQ4iMbItoE1cl93I" +
        "u2YV2n4wMq9laBx0shuKOJqO3rjRnszzCbqMuFAXfc3KgGDEaCpI7049s3i2yIcv4vT9uU" +
        "AlrM-dsrdw0JgJiFYl0JXh~TO0IyrcVcLpgZYgRhEvTAdkDNwTs-2GK4tzdPEd34os4a2c" +
        "DPL8joh3jhp~eGoRzrpcdRekxENdzheL4w3wD1fJ9W2-leil1FH6EPc3FSL6e~nqbw69gN" +
        "bsuXAMQ6CobukJdJEy37uKmEw4v6WPyfYMUUacchv1JoNfkHLpnAWifQ==", 

        "AGwvKAMJcPAliP-n7F0Rrj0JMRaFGjww~zvBjyzc~SPJrBF831cMqZFRmMHotgA7S5BrH2" +
        "6CL8okI2N-7as0F2l7OPx50dFEwSVSjqBjVV6SGRFC8oS-ii1FURMz2SCHSaj6kazAYq4s" +
        "DwyqR7vnUrOtPnZujHSU~a02jinyn-QOaHkxRiUp-Oo0jlZiU5xomXgLdkhtuz6725WUDj" +
        "3uVlMtIYfeKQsTdasujHe1oQhUmp58jfg5vgZ8g87cY8rn4p9DRwDBBuo6vi5on7T13sGx" +
        "tY9wz6HTpwzDhEqpNrj~h4JibElfi0Jo8ZllmNTO1ZCNpUQgASoTtyFLD5rk6cIAMK0R7A" +
        "7hjB0aelKM-V7AHkj-Fhrcm8xIgWhKaLn2wKbVNpAkllkiLALyfWJ9dhJ804RWQTMPE-GD" +
        "kBMIFOOJ9MhpEN533OBQDwUKcoxMjl0zOMNCLx8IdCE6cLtUDKJXLB0atnDpLkBer6FwXP" +
        "81EvKDYhtp1GsbiKvZDt8LSPJQnm2EdA3Pr9fpAisJ5Ocaxlfa6~uQCuqGA9nJ9n6w03u-" +
        "ZpSMhSh4zm2s1MqijmaJRc-QNKmN~u1hh3R2hwWNi7FoStMA87sutEBXMdFI8un7StHNSE" +
        "iCYwmmW2Nu3djkM-X8gGjSsdrphTU7uOXbwazmguobFGxI0JujYruM5Q==", 

        "ALFYtPSwEEW3eTO4hLw6PZNlBKoSIseQNBi034gq6FwYEZsJOAo-1VXcvMviKw2MCP9ZkH" +
        "lTNBfzc79ms2TU8kXxc7zwUc-l2HJLWh6dj2tIQLR8bbWM7U0iUx4XB1B-FEvdhbjz7dsu" +
        "6SBXVhxo2ulrk7Q7vX3kPrePhZZldcNZcS0t65DHYYwL~E~ROjQwOO4Cb~8FgiIUjb8CCN" +
        "w5zxJpBaEt7UvZffkVwj-EWTzFy3DIjWIRizxnsI~mUI-VspPE~xlmFX~TwPS9UbwJDpm8" +
        "-WzINFcehSzF3y9rzSMX-KbU8m4YZj07itZOiIbWgLeulTUB-UgwEkfJBG0xiSUAspZf2~" +
        "t~NthBlpcdrBLADXTJ7Jmkk4MIfysV~JpDB7IVg0v4WcUUwF3sYMmBCdPCwyYf0hTrl2Yb" +
        "L6kmm4u97WgQqf0TyzXtVZYwjct4LzZlyH591y6O6AQ4Fydqos9ABInzu-SbXq6S1Hi6vr" +
        "aNWU3mcy2myie32EEXtkX7P8eXWY35GCv9ThPEYHG5g1qKOk95ZCTYYwlpgeyaMKsnN3C~" +
        "x9TJA8K8T44v7vE6--Nw4Z4zjepwkIOht9iQsA6D6wRUQpeYX8bjIyYDPC7GUHq0WhXR6E" +
        "6Ojc9k8V5uh0SZ-rCQX6sccdk3JbyRhjGP4rSKr6MmvxVVsqBjcbpxsg=="
    };
    
    protected void setUp() {
        _context = I2PAppContext.getGlobalContext();
        Object o = YKGenerator.class;
    }
    
    public void testBasicAES(){
        SessionKey sessionKey = KeyGenerator.getInstance().generateSessionKey();
        Hash h = SHA256Generator.getInstance().calculateHash(sessionKey.getData());
        byte iv[] = new byte[16];
        System.arraycopy(h.getData(), 0, iv, 0, 16);
        
        String msg = "Hello world01234012345678901234501234567890123450123456789012345";
        h = SHA256Generator.getInstance().calculateHash(DataHelper.getASCII(msg));
        
        byte aesEncr[] = new byte[DataHelper.getASCII(msg).length];
        byte aesDecr[] = new byte[aesEncr.length];
        _context.aes().encrypt(DataHelper.getASCII(msg), 0, aesEncr, 0, sessionKey, iv, aesEncr.length);
        _context.aes().decrypt(aesEncr, 0, aesDecr, 0, sessionKey, iv, aesEncr.length);
        h = SHA256Generator.getInstance().calculateHash(aesDecr);
        
        assertEquals(msg, new String(aesDecr));
    }
    
    public void testAES(){
        SessionKey sessionKey = KeyGenerator.getInstance().generateSessionKey();
        Hash h = SHA256Generator.getInstance().calculateHash(sessionKey.getData());
        byte iv[] = new byte[16];
        System.arraycopy(h.getData(), 0, iv, 0, 16);
        
        String msg = "Hello world";
        
        byte encrypted[] = _context.elGamalAESEngine().encryptAESBlock(DataHelper.getASCII(msg), sessionKey, iv, null, null, 64);
        Set<SessionTag> foundTags = new HashSet<SessionTag>();
        SessionKey foundKey = new SessionKey();
        byte decrypted[] = null;
        try{
            decrypted = _context.elGamalAESEngine().decryptAESBlock(encrypted, 0, encrypted.length, sessionKey, iv, null, foundTags, foundKey);
        }catch(DataFormatException dfe){
            dfe.printStackTrace();
            fail();
        }
        assertNotNull(decrypted);
        String read = new String(decrypted);
        assertEquals(msg, read);
    }
    
    public void testRoundTrip(){
        Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)keys[0];
        PrivateKey privKey = (PrivateKey)keys[1];
        
        String msg = "Hello world";
        Set toBeDelivered = new HashSet();
        SessionKey key = _context.sessionKeyManager().getCurrentKey(pubKey);
        if (key == null)
            key = _context.sessionKeyManager().createSession(pubKey);
        byte[] encrypted = _context.elGamalAESEngine().encrypt(DataHelper.getASCII(msg), pubKey, key, null, null, 64);
        byte[] decrypted = null;
        try{
            decrypted = _context.elGamalAESEngine().decrypt(encrypted, privKey, _context.sessionKeyManager());
        }catch(DataFormatException dfe){
            dfe.printStackTrace();
            fail();
        }
        assertNotNull(decrypted);
        String read = new String(decrypted);
        assertEquals(msg, read);
    }
    
    public void testElGamal(){
        for (int i = 0; i < 2; i++) {
            Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
            PublicKey pubKey = (PublicKey)keys[0];
            PrivateKey privKey = (PrivateKey)keys[1];
            SessionKey key = KeyGenerator.getInstance().generateSessionKey();
            
            ByteArrayOutputStream elgSrc = new ByteArrayOutputStream(256);
            try{
                key.writeBytes(elgSrc);
            }catch(DataFormatException dfe){
                dfe.printStackTrace();
                fail();
            }catch(IOException ioe){
                ioe.printStackTrace();
                fail();
            }
            
            byte preIV[] = new byte[32];
            RandomSource.getInstance().nextBytes(preIV);
            try{
                elgSrc.write(preIV);
                elgSrc.flush();
            }catch(IOException ioe){
                ioe.printStackTrace();
                fail();
            }
            
            
            byte elgEncr[] = _context.elGamalEngine().encrypt(elgSrc.toByteArray(), pubKey);
            byte elgDecr[] = _context.elGamalEngine().decrypt(elgEncr, privKey);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(elgDecr);
            SessionKey nk = new SessionKey();
            
            try{
                nk.readBytes(bais);
            }catch(DataFormatException dfe){
                dfe.printStackTrace();
                fail();
            }catch(IOException ioe){
                ioe.printStackTrace();
                fail();
            }
            byte postpreIV[] = new byte[32];
            
            int read = 0;
            try{
                read = bais.read(postpreIV);
            }catch(IOException ioe){
                ioe.printStackTrace();
                fail();
            }
            
            assertEquals(read, postpreIV.length);
            
            
            assertTrue(DataHelper.eq(preIV, postpreIV));
            assertEquals(key, nk);
        }
    }
    
    public void testLoop(){
        for(int i = 0; i < 5; i++){
            Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
            PublicKey pubKey = (PublicKey)keys[0];
            PrivateKey privKey = (PrivateKey)keys[1];
            
            byte[] msg = new byte[400];
            RandomSource.getInstance().nextBytes(msg);
            SessionKey key = _context.sessionKeyManager().getCurrentKey(pubKey);
            if (key == null)
                key = _context.sessionKeyManager().createSession(pubKey);
            
            byte[] encrypted = _context.elGamalAESEngine().encrypt(msg, pubKey, key, null, null, 1024);
            byte[] decrypted = null;
            try{
                decrypted = _context.elGamalAESEngine().decrypt(encrypted, privKey, _context.sessionKeyManager());
            }catch(DataFormatException dfe){
                dfe.printStackTrace();
                fail();
            }
            
            assertTrue(DataHelper.eq(msg, decrypted));
        }
    }
    
    public void testVerifySelf(){
        Object keypair[] = _context.keyGenerator().generatePKIKeypair();
        PublicKey pub = (PublicKey)keypair[0];
        PrivateKey priv = (PrivateKey)keypair[1];

        for (int i = 0; i < UNENCRYPTED.length; i++) { 
            byte orig[] = DataHelper.getASCII(UNENCRYPTED[i]);

            byte encrypted[] = _context.elGamalEngine().encrypt(orig, pub);
            byte decrypted[] = _context.elGamalEngine().decrypt(encrypted, priv);

            assertTrue(DataHelper.eq(decrypted, orig));
        }
    }
    
    public void testVerifyCompatability(){
        PublicKey pub = new PublicKey();
        PrivateKey priv = new PrivateKey();
        try{
            pub.fromBase64(PUBLIC_KEY);
            priv.fromBase64(PRIVATE_KEY);
        }catch(DataFormatException dfe){
            dfe.printStackTrace();
            fail();
        }

        for (int i = 0; i < ENCRYPTED.length; i++) {
            byte enc[] = Base64.decode(ENCRYPTED[i]);
            byte decrypted[] = _context.elGamalEngine().decrypt(enc, priv);

            assertTrue(DataHelper.eq(decrypted, DataHelper.getASCII(UNENCRYPTED[i])));
        }
    }
    
    public void testMultiple(){
        Object[] keys = KeyGenerator.getInstance().generatePKIKeypair();
        byte[] message = new byte[222];
        for (int x = 0; x < 25; x++) {
            _context.random().nextBytes(message);
            keys = KeyGenerator.getInstance().generatePKIKeypair();
            PublicKey pubkey = (PublicKey)keys[0];
            PrivateKey privkey = (PrivateKey)keys[1];
            
            byte[] e = _context.elGamalEngine().encrypt(message, pubkey);
            byte[] d = _context.elGamalEngine().decrypt(e, privkey);
            
            assertTrue(DataHelper.eq(d, message));
        }
    }
    
    public void testElGamalAESEngine() throws Exception{
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        ElGamalAESEngine e = new ElGamalAESEngine(ctx);
        Object kp[] = ctx.keyGenerator().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)kp[0];
        PrivateKey privKey = (PrivateKey)kp[1];
        SessionKey sessionKey = ctx.keyGenerator().generateSessionKey();
        for (int i = 0; i < 10; i++) {
            Set<SessionTag> tags = new HashSet<SessionTag>(5);
            if (i == 0) {
                for (int j = 0; j < 5; j++)
                    tags.add(new SessionTag(true));
            }
            byte encrypted[] = e.encrypt(DataHelper.getASCII("blah"), pubKey, sessionKey, tags, null, 1024);
            byte decrypted[] = e.decrypt(encrypted, privKey, _context.sessionKeyManager());
            assertEquals("blah", new String(decrypted));
                
            ctx.sessionKeyManager().tagsDelivered(pubKey, sessionKey, tags);
        }
    }
    
    public void testElGamalEngine(){
        int numRuns = 10;
        RandomSource.getInstance().nextBoolean();
        I2PAppContext context = I2PAppContext.getGlobalContext();

        for (int i = 0; i < numRuns; i++) {
            Object pair[] = KeyGenerator.getInstance().generatePKIKeypair();

            PublicKey pubkey = (PublicKey) pair[0];
            PrivateKey privkey = (PrivateKey) pair[1];
            byte buf[] = new byte[128];
            RandomSource.getInstance().nextBytes(buf);
            byte encr[] = context.elGamalEngine().encrypt(buf, pubkey);
            byte decr[] = context.elGamalEngine().decrypt(encr, privkey);

            assertTrue(DataHelper.eq(decr, buf));
        }
    }
    
    public void testYKGen(){
        RandomSource.getInstance().nextBoolean();
        I2PAppContext context = I2PAppContext.getGlobalContext();
        YKGenerator ykgen = new YKGenerator(context);
        for (int i = 0; i < 5; i++) {
            ykgen.getNextYK();
        }
    }
}
