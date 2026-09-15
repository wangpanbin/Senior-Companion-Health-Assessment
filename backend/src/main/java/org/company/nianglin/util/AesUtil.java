package org.company.nianglin.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感信息加解密工具（身份证号等 PII 字段专用）。
 *
 * <p>算法：AES-256-GCM（带完整性校验，防篡改）。</p>
 *
 * <p>密文格式：{@code Base64( IV(12 字节) || 密文+认证标签 )}。每次加密使用随机 IV，
 * 因此同一条身份证号两次加密的结果不同 —— 这是预期行为，也意味着<b>不能用等值查询匹配密文</b>，
 * 需要按手机号等明文列定位记录后再解密。</p>
 *
 * <p>密钥派生：配置文件里给任意长度的密钥字符串，这里用 SHA-256 派生出 32 字节（AES-256）密钥，
 * 避免因为密钥长度不是 16/24/32 字节而启动报错。</p>
 *
 * <p><b>合规要求</b>：身份证号落库必须是密文，接口返回必须是脱敏串，
 * 日志中禁止打印明文（见 plan.md 合规红线第 2 条）。</p>
 *
 * @author D（PM / 数据库）
 * @since M1
 */
public final class AesUtil {

    /** 变换算法：AES + GCM 模式 + 无填充（GCM 自带认证） */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 算法名 */
    private static final String ALGORITHM = "AES";

    /** GCM 推荐的 IV 长度：12 字节 */
    private static final int IV_LENGTH = 12;

    /** 认证标签长度：128 位 */
    private static final int TAG_LENGTH_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AesUtil() {
        throw new UnsupportedOperationException("工具类禁止实例化");
    }

    /**
     * 加密。
     *
     * @param plainText 明文，为空时原样返回 null
     * @param key       密钥字符串（任意长度，内部 SHA-256 派生）
     * @return Base64 密文；入参为空则返回 null
     */
    public static String encrypt(String plainText, String key) {
        if (plainText == null || plainText.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(key), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 拼接 IV + 密文，一起做 Base64
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 不把明文写进异常信息，避免日志泄漏
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /**
     * 解密。
     *
     * @param cipherText Base64 密文
     * @param key        与加密时相同的密钥字符串
     * @return 明文；入参为空则返回 null
     * @throws IllegalStateException 密文被篡改、密钥不匹配或格式非法
     */
    public static String decrypt(String cipherText, String key) {
        if (cipherText == null || cipherText.isEmpty()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度非法");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] cipherBytes = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(key), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段解密失败（密钥不匹配或密文被篡改）", e);
        }
    }

    /**
     * 用 SHA-256 把任意长度密钥字符串派生为 32 字节 AES-256 密钥。
     */
    private static SecretKeySpec deriveKey(String key) throws Exception {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("密钥不能为空");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, ALGORITHM);
    }
}
