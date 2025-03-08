package id.co.blackheart.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.hibernate.dialect.DB2Dialect;
import org.springframework.aot.hint.*;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RuntimeHintsConfig implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(@NotNull RuntimeHints hints, ClassLoader classLoader) {
        try {
            /*register redis*/
            if (ClassUtils.isPresent("io.lettuce.core.RedisClient", classLoader)) {
                hints.reflection().registerType(TypeReference.of("io.lettuce.core.RedisClient"),
                        it -> it
                                .onReachableType(
                                        TypeReference.of("org.springframework.data.redis.connection.lettuce.StandaloneConnectionProvider"))
                                .withMembers(MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.DECLARED_FIELDS));
            }

            /*register db2 dialect*/
            hints.reflection()
                    .registerType(DB2Dialect.class, MemberCategory.values());

            /*register serialization internal class*/
            getInternalClass().forEach(serializable -> hints.serialization().registerType(serializable));

            /*register serialization java.time.Ser*/
            if (ClassUtils.isPresent("java.time.Ser", classLoader)) {
                hints.serialization().registerType(TypeReference.of("java.time.Ser"));
            }

            /*register serialization dto request/response*/
            registerClassInPackage("id.co.pegadaian.mulia.dto.request", classLoader, hints);
            registerClassInPackage("id.co.pegadaian.mulia.dto.response", classLoader, hints);

            /*register entity*/
            registerClassInPackage("id.co.pegadaian.mulia.datasource.db2.entity", classLoader, hints);

            ProxyHints proxies = hints.proxies();
            proxies.registerJdkProxy(HttpServletRequest.class);
        } catch (Exception e) {
            throw new RuntimeException("Could not register RuntimeHint: " + e.getMessage());
        }
    }

    /*
     * Get internal class
     * that is only used in this application
     * */
    private Collection<Class<? extends Serializable>> getInternalClass() {
        Set<Class<? extends Serializable>> classes = new HashSet<>();

        classes.add(byte[].class);
        classes.add(Double.class);
        classes.add(Long.class);
        classes.add(Number.class);
        classes.add(String.class);
        classes.add(BigDecimal.class);
        classes.add(BigInteger.class);
        classes.add(LocalDate.class);
        classes.add(LocalDateTime.class);
        classes.add(HashMap.class);
        classes.add(LinkedHashMap.class);

        return classes;
    }

    private void registerClassInPackage(String valPackage, ClassLoader classLoader, RuntimeHints hints) throws IOException {
        Path packageDirectory = Paths.get(classLoader.getResource(valPackage.replace('.','/')).getPath());
        try (Stream<Path> paths = Files.walk(packageDirectory)) {
            List<Class<? extends Serializable>> serializableClasses = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .map(path -> {
                        String className = valPackage + "." + path.getFileName().toString().replace(".class", "");
                        try {
                            Class<?> clazz = Class.forName(className);
                            if (Serializable.class.isAssignableFrom(clazz)) {
                                //noinspection unchecked
                                return (Class<? extends Serializable>) clazz;
                            }
                        } catch (ClassNotFoundException e) {
                            throw new RuntimeException("Failed to load class: " + className, e);
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            serializableClasses.forEach(clazz -> hints.serialization().registerType(clazz));
        }
    }
}
