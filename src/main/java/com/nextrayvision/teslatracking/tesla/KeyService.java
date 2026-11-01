package com.nextrayvision.teslatracking.tesla;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Tesla requires every Fleet API app to publish an EC (prime256v1) public key at
 * https://your-domain/.well-known/appspecific/com.tesla.3p.public-key.pem.
 * The key pair is generated on first start and kept in the data directory.
 */
@Service
public class KeyService {

    private final Path publicKeyFile;
    private final Path privateKeyFile;

    public KeyService(TeslaProperties props) {
        Path dir = Path.of(props.dataDir());
        this.publicKeyFile = dir.resolve("com.tesla.3p.public-key.pem");
        this.privateKeyFile = dir.resolve("private-key.pem");
    }

    public synchronized String publicKeyPem() {
        try {
            if (!Files.exists(publicKeyFile)) {
                generate();
            }
            return Files.readString(publicKeyFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void generate() throws IOException {
        KeyPair pair;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate EC key pair", e);
        }
        Files.createDirectories(publicKeyFile.getParent());
        Files.writeString(privateKeyFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(publicKeyFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
