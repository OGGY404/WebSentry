package com.websentry.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.websentry.model.RuleChecks;

/** The rule engine. */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 4000;

    /** Stop reading the page after this many characters. */
    private static final int MAX_BODY_CHARS = 300_000;

    /** A raw IP address used as the host, like http://192.168.1.1/login. */
    private static final Pattern IP_HOST =
            Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

    /** An http file loaded inside a page. */
    private static final Pattern MIXED_CONTENT = Pattern.compile(
            "<\\s*(?:img|script|iframe|link|audio|video|source|embed|object)\\b[^>]*?"
                    + "\\b(?:src|href|data)\\s*=\\s*[\"']http://",
            Pattern.CASE_INSENSITIVE);

    /** Cheap or often abused top level domains. */
    private static final Set<String> ABUSED_TLDS = Set.of(
            "tk", "ml", "ga", "cf", "gq", "xyz", "top", "buzz", "click", "link",
            "work", "country", "kim", "loan", "download", "stream", "review", "zip", "mov");

    /** Words phishing pages use to make you act quickly. */
    private static final Set<String> SUSPICIOUS_WORDS = Set.of(
            "login", "signin", "verify", "verification", "secure", "account",
            "update", "banking", "confirm", "password", "wallet", "recover", "unlock");

    /** Well known brands. */
    private static final Set<String> BRANDS = Set.of(
            "paypal", "apple", "google", "microsoft", "amazon", "facebook",
            "netflix", "instagram", "whatsapp", "outlook", "office365", "dhl", "fedex");

    private static final Set<String> SHORTENERS = Set.of(
            "bit.ly", "tinyurl.com", "goo.gl", "t.co", "ow.ly", "is.gd",
            "buff.ly", "cutt.ly", "rebrand.ly", "shorturl.at");

    /** Run every check for one URL. */
    public RuleChecks check(String url, boolean safeBrowsingHit, boolean performLiveChecks) {
        List<String> reasons = new ArrayList<>();

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            reasons.add("The address could not be read as a valid web address.");
            return new RuleChecks(false, false, null, null, false, safeBrowsingHit, true, reasons);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

        if (!https) {
            reasons.add("The connection is not encrypted, so anything you type could be read by others.");
        }

        // Address checks first.
        boolean suspiciousPattern = checkAddressPatterns(uri, host, reasons);

        // Now the live checks.
        CertificateResult cert = new CertificateResult(false, null, null);
        boolean mixedContent = false;

        if (performLiveChecks && https && !host.isBlank()) {
            LiveResult live = inspectLiveSite(url, reasons);
            cert = live.certificate();
            mixedContent = live.mixedContent();
        } else if (!performLiveChecks && https) {
            // We did not open the site, so we must not claim the certificate is broken.
            cert = new CertificateResult(true, null, null);
        }

        if (safeBrowsingHit) {
            reasons.add("Google Safe Browsing lists this address as dangerous.");
        }

        return new RuleChecks(
                https,
                cert.valid(),
                cert.issuer(),
                cert.daysToExpiry(),
                mixedContent,
                safeBrowsingHit,
                suspiciousPattern,
                reasons);
    }

    /** Checks that only look at the text of the address. */
    private boolean checkAddressPatterns(URI uri, String host, List<String> reasons) {
        boolean suspicious = false;
        String full = uri.toString().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);

        if (IP_HOST.matcher(host).matches()) {
            reasons.add("The address uses a raw IP number instead of a normal website name.");
            suspicious = true;
        }
        if (full.contains("@")) {
            reasons.add("The address contains an @ sign, which can hide the real destination.");
            suspicious = true;
        }
        if (host.contains("xn--")) {
            reasons.add("The address uses look-alike foreign characters that can imitate a real name.");
            suspicious = true;
        }

        String[] labels = host.isBlank() ? new String[0] : host.split("\\.");
        if (labels.length >= 5) {
            reasons.add("The address has an unusual number of sub-parts before the real domain name.");
            suspicious = true;
        }
        if (countChar(host, '-') >= 4) {
            reasons.add("The website name contains many hyphens, which is common in fake addresses.");
            suspicious = true;
        }
        if (host.length() > 45) {
            reasons.add("The website name is unusually long.");
            suspicious = true;
        }

        if (labels.length >= 2) {
            String tld = labels[labels.length - 1];
            if (ABUSED_TLDS.contains(tld)) {
                reasons.add("The address ends in ." + tld + ", an ending often used for throwaway scam sites.");
                suspicious = true;
            }
        }

        if (SHORTENERS.contains(host)) {
            reasons.add("This is a shortened link, so the real destination is hidden.");
            suspicious = true;
        }

        // A brand name outside the real domain, such as paypal.secure-login.xyz.
        String registrable = registrableDomain(labels);
        for (String brand : BRANDS) {
            if (host.contains(brand) && !registrable.startsWith(brand + ".")) {
                reasons.add("The address mentions " + brand + " but the real website name is "
                        + registrable + ".");
                suspicious = true;
                break;
            }
        }

        for (String word : SUSPICIOUS_WORDS) {
            if (host.contains(word) || path.contains(word)) {
                reasons.add("The address contains the word \"" + word
                        + "\", often used to make you enter your details in a hurry.");
                suspicious = true;
                break;
            }
        }

        return suspicious;
    }

    /** Opens the site once to read the certificate and to look for mixed content. */
    private LiveResult inspectLiveSite(String url, List<String> reasons) {
        HttpsURLConnection connection = null;
        long started = System.nanoTime();
        try {
            URL target = URI.create(url).toURL();
            connection = (HttpsURLConnection) target.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "WebSentry/1.0 (final year project scanner)");
            connection.connect();

            CertificateResult cert = readCertificate(connection, reasons);
            boolean mixed = readBodyForMixedContent(connection);
            if (mixed) {
                reasons.add("This secure page loads some files over an unencrypted connection.");
            }
            return new LiveResult(cert, mixed);

        } catch (javax.net.ssl.SSLException e) {
            // The certificate was rejected.
            reasons.add("The security certificate could not be trusted.");
            log.info("SSL problem for {}: {}", url, e.getMessage());
            return new LiveResult(new CertificateResult(false, null, null), false);

        } catch (Exception e) {
            // The site did not answer.
            reasons.add("The website did not respond, so live checks could not be completed.");
            log.info("Could not reach {}: {}", url, e.toString());
            return new LiveResult(new CertificateResult(false, null, null), false);

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            log.debug("live check took {} ms", (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** Reads the certificate and works out if it is valid and when it expires. */
    private CertificateResult readCertificate(HttpsURLConnection connection, List<String> reasons) {
        try {
            java.security.cert.Certificate[] certificates = connection.getServerCertificates();
            if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate certificate)) {
                return new CertificateResult(false, null, null);
            }

            String issuer = commonName(certificate.getIssuerX500Principal().getName());
            Instant expiresAt = certificate.getNotAfter().toInstant();
            int daysToExpiry = (int) Duration.between(Instant.now(), expiresAt).toDays();

            boolean valid = true;
            try {
                certificate.checkValidity();
            } catch (Exception expiredOrNotYetValid) {
                valid = false;
                reasons.add("The security certificate is expired or not yet valid.");
            }

            if (valid && daysToExpiry <= 7) {
                reasons.add("The security certificate expires in " + daysToExpiry + " days.");
            }

            return new CertificateResult(valid, issuer, daysToExpiry);

        } catch (Exception e) {
            log.debug("could not read certificate: {}", e.toString());
            return new CertificateResult(false, null, null);
        }
    }

    /** Reads the start of the page and looks for http files inside it. */
    private boolean readBodyForMixedContent(HttpsURLConnection connection) {
        try (InputStream in = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            StringBuilder body = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1 && body.length() < MAX_BODY_CHARS) {
                body.append(buffer, 0, read);
            }
            Matcher matcher = MIXED_CONTENT.matcher(body);
            return matcher.find();

        } catch (Exception e) {
            log.debug("could not read page body: {}", e.toString());
            return false;
        }
    }

    /** Pulls the readable name out of a certificate subject string. */
    private String commonName(String distinguishedName) {
        for (String part : distinguishedName.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                return trimmed.substring(3);
            }
        }
        return distinguishedName;
    }

    /** The last two parts of the host, for example "secure-login.xyz". */
    private String registrableDomain(String[] labels) {
        if (labels.length < 2) {
            return String.join(".", labels);
        }
        return labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private int countChar(String text, char target) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                total++;
            }
        }
        return total;
    }

    private record CertificateResult(boolean valid, String issuer, Integer daysToExpiry) {
    }

    private record LiveResult(CertificateResult certificate, boolean mixedContent) {
    }
}
