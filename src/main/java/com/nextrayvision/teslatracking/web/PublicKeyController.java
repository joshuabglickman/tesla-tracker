package com.nextrayvision.teslatracking.web;

import com.nextrayvision.teslatracking.tesla.KeyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the public key at the path Tesla checks, for setups where this app itself is the public domain. */
@RestController
public class PublicKeyController {

    private final KeyService keys;

    public PublicKeyController(KeyService keys) {
        this.keys = keys;
    }

    @GetMapping(value = "/.well-known/appspecific/com.tesla.3p.public-key.pem", produces = "application/x-pem-file")
    public String publicKey() {
        return keys.publicKeyPem();
    }
}
