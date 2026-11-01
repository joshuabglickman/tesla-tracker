package com.nextrayvision.teslatracking.web;

import com.nextrayvision.teslatracking.tesla.TeslaApiClient;
import com.nextrayvision.teslatracking.tesla.TeslaAuthService;
import com.nextrayvision.teslatracking.tesla.TeslaProperties;
import com.nextrayvision.teslatracking.tracking.PollingService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String STATE_ATTRIBUTE = "teslaOauthState";

    private final TeslaProperties props;
    private final TeslaAuthService auth;
    private final TeslaApiClient api;
    private final PollingService polling;

    public AuthController(TeslaProperties props, TeslaAuthService auth, TeslaApiClient api, PollingService polling) {
        this.props = props;
        this.auth = auth;
        this.api = api;
        this.polling = polling;
    }

    @GetMapping("/auth/login")
    public String login(HttpSession session) {
        if (!props.isConfigured()) {
            return home("Set tesla.client-id and tesla.client-secret in config/application.properties first.");
        }
        String state = UUID.randomUUID().toString();
        session.setAttribute(STATE_ATTRIBUTE, state);
        return "redirect:" + auth.authorizationUrl(state);
    }

    @GetMapping("/auth/callback")
    public String callback(@RequestParam(required = false) String code,
                           @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error,
                           @RequestParam(name = "error_description", required = false) String errorDescription,
                           HttpSession session) {
        Object expected = session.getAttribute(STATE_ATTRIBUTE);
        session.removeAttribute(STATE_ATTRIBUTE);
        if (error != null) {
            return home("Tesla sign-in failed: " + (errorDescription != null ? errorDescription : error));
        }
        if (code == null || expected == null || !expected.equals(state)) {
            return home("Sign-in link expired. Try connecting again.");
        }
        try {
            auth.exchangeCode(code);
        } catch (RuntimeException e) {
            log.warn("Token exchange failed", e);
            return home("Tesla rejected the sign-in: " + e.getMessage());
        }
        try {
            String regional = api.regionBaseUrl();
            if (regional != null && !regional.isBlank()) {
                auth.setFleetApiBaseUrl(regional);
            }
            polling.poll(true);
        } catch (RuntimeException e) {
            log.warn("First poll after linking failed", e);
        }
        return "redirect:/";
    }

    @PostMapping("/auth/unlink")
    public String unlink() {
        auth.unlink();
        return "redirect:/";
    }

    private static String home(String message) {
        return "redirect:" + UriComponentsBuilder.fromPath("/").queryParam("message", message).encode().toUriString();
    }
}
