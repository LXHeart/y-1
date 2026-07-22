package com.grassland.identity.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

/**
 * 兼容 bcryptjs 生成的 $2a$ cost-12 哈希。供后续 login 切片使用。
 */
@Component
public class PasswordVerifier {
    public boolean verify(String password, String hash) {
        if (password == null || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
        } catch (Exception error) {
            return false;
        }
    }
}
