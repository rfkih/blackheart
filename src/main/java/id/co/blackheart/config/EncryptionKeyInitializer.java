package id.co.blackheart.config;

import id.co.blackheart.converter.EncryptedStringConverter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * Installs the data-encryption key into {@link EncryptedStringConverter} at startup.
 *
 * <p>The key is supplied via the {@code DB_ENCRYPTION_KEY} environment variable
 * (base64-encoded 32 random bytes — generate with {@code openssl rand -base64 32}).
 *
 * <p><b>Production-like profiles refuse to boot on the dev sentinel.</b> A forgotten
 * env override would otherwise silently ship a publicly-known key that anyone reading
 * the repo can use to decrypt stolen DB backups.
 */
@Component
@Slf4j
public class EncryptionKeyInitializer {

    /**
     * Matches the dev-only default in application.properties byte-for-byte.
     * This value is INTENTIONALLY public — it only exists so local dev can boot
     * without ceremony. Refusing to boot on production-like profiles is what keeps
     * it honest.
     */
    public static final String DEV_ONLY_KEY_SENTINEL =
            "ZGV2b25seTMyYnl0ZWRhdGFjcnlwdG9rZXlwbGVhc2U=";

    /**
     * Matching sentinel decoded to bytes (exactly 32 bytes). Used for the dev path.
     */
    private static final byte[] DEV_ONLY_KEY_BYTES =
            Base64.getDecoder().decode(DEV_ONLY_KEY_SENTINEL);

    private static final Set<String> PRODUCTION_LIKE_PROFILES =
            Set.of("prod", "production", "staging", "stg", "preprod", "uat", "canary");

    @Value("${app.encryption.key:}")
    private String encryptionKeyBase64;

    private final Environment environment;

    public EncryptionKeyInitializer(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void init() {
        if (!StringUtils.hasText(encryptionKeyBase64)) {
            throw new IllegalStateException(
                    "app.encryption.key is not configured — set DB_ENCRYPTION_KEY to a base64-encoded 32-byte key "
                            + "(generate with: openssl rand -base64 32)");
        }

        boolean productionLike = Arrays.stream(environment.getActiveProfiles())
                .map(String::toLowerCase)
                .anyMatch(PRODUCTION_LIKE_PROFILES::contains);

        if (DEV_ONLY_KEY_SENTINEL.equals(encryptionKeyBase64)) {
            if (productionLike) {
                throw new IllegalStateException(
                        "Refusing to start: app.encryption.key is the dev-only sentinel. "
                                + "Set DB_ENCRYPTION_KEY to a real base64-encoded 32-byte key before running in a production-like profile "
                                + "(" + PRODUCTION_LIKE_PROFILES + "). Generate one with: openssl rand -base64 32");
            }
            log.warn("""
                    ===== SECURITY WARNING =====
                    Starting with the INSECURE dev-only DB encryption key. Any value encrypted
                    with this key can be decrypted by anyone with access to this source tree.
                    Acceptable for local development only — set DB_ENCRYPTION_KEY before exposing
                    this process on any network other than localhost.""");
            EncryptedStringConverter.installKey(DEV_ONLY_KEY_BYTES);
            return;
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.encryption.key is not valid base64. Generate with: openssl rand -base64 32");
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "app.encryption.key must decode to exactly 32 bytes (AES-256). Got " + keyBytes.length + " bytes.");
        }

        EncryptedStringConverter.installKey(keyBytes);
        log.info("Data-encryption key installed (length=32 bytes, algorithm=AES-256-GCM)");
    }
}
