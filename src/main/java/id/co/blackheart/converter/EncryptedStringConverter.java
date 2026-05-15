package id.co.blackheart.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JPA {@link AttributeConverter} that transparently encrypts String columns at rest using
 * AES-256-GCM. Used to wrap sensitive credential columns — e.g. Binance {@code apiKey} and
 * {@code apiSecret} on the {@code accounts} table.
 *
 * <p>Envelope format written to the database:
 * <pre>{@code
 *   enc:v1:<base64( 12-byte IV | ciphertext | 16-byte GCM tag )>
 * }</pre>
 *
 * <p><b>Legacy compatibility:</b> if a row predates encryption and the value does NOT start
 * with the {@code enc:v1:} prefix, the converter returns it as plaintext rather than failing.
 * The row will be written back in encrypted form on the next update. Use
 * {@code DataEncryptionMigrationRunner} (or a one-shot re-save script) to force-encrypt
 * all existing rows.
 *
 * <p><b>Initialization:</b> {@link EncryptionKeyInitializer} installs the key at Spring
 * bean-ready time. JPA converters are instantiated by Hibernate (not Spring) so they cannot
 * be {@code @Autowired} — a static {@code AtomicReference<SecretKey>} is the mechanism.
 */
@Converter
@Slf4j
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String CIPHER_ALG = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    public static final String ENVELOPE_PREFIX = "enc:v1:";

    private static final SecureRandom RNG = new SecureRandom();
    private static final AtomicReference<SecretKey> KEY = new AtomicReference<>();

    /**
     * Installs the symmetric key. Called exactly once at application startup by
     * {@link EncryptionKeyInitializer}. The {@code base64Key} must decode to 32 bytes.
     */
    public static void installKey(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalStateException(
                    "EncryptedStringConverter key must be exactly 32 bytes (AES-256). Got "
                            + (keyBytes == null ? "null" : keyBytes.length) + " bytes.");
        }
        KEY.set(new SecretKeySpec(keyBytes, "AES"));
    }

    public static boolean isKeyInstalled() {
        return KEY.get() != null;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        SecretKey key = requireKey();
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            RNG.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALG);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            ByteBuffer bb = ByteBuffer.allocate(iv.length + ct.length);
            bb.put(iv).put(ct);
            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(bb.array());
        } catch (GeneralSecurityException e) {
            // Do not leak crypto detail or the plaintext. Reject the save.
            log.error("Encryption failed for an encrypted column: {}", e.getMessage());
            throw new IllegalStateException("Column encryption failed");
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (!dbData.startsWith(ENVELOPE_PREFIX)) {
            // Legacy plaintext row written before encryption was enabled.
            // Return as-is; next write will encrypt it.
            return dbData;
        }
        SecretKey key = requireKey();
        try {
            byte[] blob = Base64.getDecoder().decode(dbData.substring(ENVELOPE_PREFIX.length()));
            if (blob.length < GCM_IV_LEN + (GCM_TAG_BITS / 8)) {
                throw new IllegalStateException("Ciphertext too short");
            }
            ByteBuffer bb = ByteBuffer.wrap(blob);
            byte[] iv = new byte[GCM_IV_LEN];
            bb.get(iv);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);

            Cipher cipher = Cipher.getInstance(CIPHER_ALG);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.error("Decryption failed for an encrypted column — data may be corrupted or the key rotated without migration: {}",
                    e.getMessage());
            throw new IllegalStateException("Column decryption failed");
        }
    }

    private static SecretKey requireKey() {
        SecretKey k = KEY.get();
        if (k == null) {
            throw new IllegalStateException(
                    "EncryptedStringConverter has no key installed. "
                            + "Set DB_ENCRYPTION_KEY (base64-encoded 32-byte key) and ensure EncryptionKeyInitializer runs at startup.");
        }
        return k;
    }
}
