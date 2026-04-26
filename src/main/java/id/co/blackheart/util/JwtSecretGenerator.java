package id.co.blackheart.util;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.Base64;

/**
 * Tiny CLI helper that prints a fresh Base64-encoded HS256 (256-bit) secret
 * suitable for {@code JWT_SECRET} in production.
 *
 * <p>Run with:
 * <pre>{@code
 *   ./gradlew -q -Pmain=id.co.blackheart.util.JwtSecretGenerator runTool
 * }</pre>
 *
 * <p>Or directly:
 * <pre>{@code
 *   javac -d build src/main/java/id/co/blackheart/util/JwtSecretGenerator.java
 *   java  -cp build id.co.blackheart.util.JwtSecretGenerator
 * }</pre>
 *
 * <p>Then put the output in your prod env:
 * <pre>{@code
 *   export JWT_SECRET=<paste-here>
 * }</pre>
 *
 * <p>Never commit the output to source control. The dev-only sentinel in
 * {@code application.properties} is intentionally public so local development
 * works without secret-management infra; rotating to a unique secret per
 * deployment environment is a hard requirement once you're past localhost.
 */
public final class JwtSecretGenerator {

    private JwtSecretGenerator() {
        // CLI entry point only.
    }

    public static void main(String[] args) throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("HmacSHA256");
        gen.init(256);
        SecretKey key = gen.generateKey();
        String encoded = Base64.getEncoder().encodeToString(key.getEncoded());

        System.out.println();
        System.out.println("Fresh JWT_SECRET (HS256, 256-bit, Base64):");
        System.out.println();
        System.out.println("    " + encoded);
        System.out.println();
        System.out.println("Set in prod env:");
        System.out.println("    export JWT_SECRET=\"" + encoded + "\"");
        System.out.println();
        System.out.println("Then either restart with SPRING_PROFILES_ACTIVE=prod (or any non-dev/test/local)");
        System.out.println("or unset SPRING_PROFILES_ACTIVE — JwtService refuses to boot with the dev sentinel");
        System.out.println("unless an explicit dev/test/local profile is active.");
        System.out.println();
    }
}
