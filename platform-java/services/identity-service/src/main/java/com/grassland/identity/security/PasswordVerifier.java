package com.grassland.identity.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

/**
 * 兼容 bcryptjs 生成的 $2a$ cost-12 哈希与 Argon2id 哈希。
 * 登录成功后自动将 bcrypt 升级为 Argon2id（GL-P3-IDENTITY-001）。
 */
@Component
public class PasswordVerifier {
    private final Argon2PasswordHasher argon2Hasher;

    public PasswordVerifier(Argon2PasswordHasher argon2Hasher) {
        this.argon2Hasher = argon2Hasher;
    }

    public boolean verify(String password, String hash) {
        if (password == null || hash == null || hash.isBlank()) {
            return false;
        }
        PasswordType type = detectType(hash);
        return switch (type) {
            case BCRYPT -> verifyBcrypt(password, hash);
            case ARGON2ID -> argon2Hasher.matches(password, hash);
            case UNKNOWN -> false;
        };
    }

    /** 检测密码哈希类型。 */
    public PasswordType detectType(String hash) {
        if (hash == null || hash.isBlank()) return PasswordType.UNKNOWN;
        if (hash.startsWith("$2a$") || hash.startsWith("$2b$")) {
            return PasswordType.BCRYPT;
        }
        if (hash.startsWith("$argon2id$")) {
            return PasswordType.ARGON2ID;
        }
        return PasswordType.UNKNOWN;
    }

    /** 判断是否需要升级为 Argon2id。 */
    public boolean needsRehash(String hash) {
        return detectType(hash) != PasswordType.ARGON2ID;
    }

    private boolean verifyBcrypt(String password, String hash) {
        try {
            return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
        } catch (Exception error) {
            return false;
        }
    }

    public enum PasswordType {
        BCRYPT,
        ARGON2ID,
        UNKNOWN
    }
}
