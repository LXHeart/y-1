package com.grassland.identity.security;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Component;

/**
 * Argon2id 哈希，供 login 切片成功登录后 rehash 使用。本切片仅单测覆盖。
 * 输出格式：$argon2id$v=19$m=...,t=...,p=...$<salt_b64>$<hash_b64>。
 */
@Component
public class Argon2PasswordHasher {
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 64 * 1024;
    private static final int PARALLELISM = 2;

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return hash(password, salt);
    }

    public boolean matches(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 6 || !"argon2id".equals(parts[1])) {
                return false;
            }
            int version = Integer.parseInt(parts[2].replace("v=", ""));
            String[] params = parts[3].split(",");
            int memory = Integer.parseInt(params[0].replace("m=", ""));
            int iterations = Integer.parseInt(params[1].replace("t=", ""));
            int parallelism = Integer.parseInt(params[2].replace("p=", ""));
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);
            Argon2Parameters args = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(version).withMemoryAsKB(memory).withIterations(iterations)
                .withParallelism(parallelism).withSalt(salt).build();
            Argon2BytesGenerator gen = new Argon2BytesGenerator();
            gen.init(args);
            byte[] actual = new byte[HASH_BYTES];
            gen.generateBytes(password.toCharArray(), actual);
            return Arrays.equals(expected, actual);
        } catch (Exception error) {
            return false;
        }
    }

    private String hash(String password, byte[] salt) {
        Argon2Parameters args = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(MEMORY_KB).withIterations(ITERATIONS)
            .withParallelism(PARALLELISM).withSalt(salt).build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(args);
        byte[] hash = new byte[HASH_BYTES];
        gen.generateBytes(password.toCharArray(), hash);
        return "$argon2id$v=" + Argon2Parameters.ARGON2_VERSION_13
            + "$m=" + MEMORY_KB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
            + "$" + Base64.getEncoder().withoutPadding().encodeToString(salt)
            + "$" + Base64.getEncoder().withoutPadding().encodeToString(hash);
    }
}
