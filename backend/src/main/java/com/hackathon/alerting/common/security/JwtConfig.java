package com.hackathon.alerting.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Token and password primitives: HS256 JWTs signed with {@code alerting.security.jwt.secret} (Nimbus, via Spring
 * Security), BCrypt passwords, and the role hierarchy ADMIN &gt; OPERATOR &gt; VIEWER.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    static final String ISSUER = "alerting-service";

    @Bean
    JwtEncoder jwtEncoder(AuthSettings settings) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(settings)));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthSettings settings) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(settings)).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_ADMIN > ROLE_OPERATOR
                ROLE_OPERATOR > ROLE_VIEWER
                """);
    }

    private static SecretKey key(AuthSettings settings) {
        return new SecretKeySpec(settings.secret(), "HmacSHA256");
    }
}
