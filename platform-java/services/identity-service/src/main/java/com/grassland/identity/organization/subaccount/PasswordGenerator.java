package com.grassland.identity.organization.subaccount;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * 子账号/商家账号初始密码生成（任务书 #48 D3；#71 治理台初始化商家账号复用）。CSPRNG、无易混字符、16 位。
 *
 * <p>
 * 明文只在创建/重置响应里出现一次（管理员线下转交），服务端只存 Argon2 哈希； 首登强制改密由
 * {@code account_flag.must_change_password} 兜住暴露窗口。
 */
public final class PasswordGenerator {

	private static final char[] ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
	private static final int LENGTH = 16;

	private static final RandomGenerator RANDOM = new SecureRandom();

	private PasswordGenerator() {
	}

	public static String generate() {
		StringBuilder sb = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
		}
		return sb.toString();
	}
}
