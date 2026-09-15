import org.company.nianglin.util.AesUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 种子数据密钥生成器（一次性工具，不入库不进 Git）。
 *  1. 生成种子账号统一密码的 BCrypt 哈希
 *  2. 用项目内 AesUtil 为演示身份证号生成 AES-256-GCM 密文
 * 输出：.workbuddy/tmp/secrets.txt
 */
public class GenSeedSecrets {

    /** 与 application.yml 中 nianglin.security.id-card-key 的 dev 默认值保持一致 */
    private static final String AES_KEY = "nianglin-dev-id-card-key-please-change-in-production";

    /** 所有种子账号统一密码 */
    private static final String PLAIN_PASSWORD = "Nl@123456";

    private static final String[] ID_CARDS = {
            "460106194801154321",
            "460105195203226712",
            "460107194512098834",
            "460108195607134455",
            "460106196102257896",
            "460105194909183327",
            "460107195804061168",
            "460108196205129943",
            "460106194403064475",
            "460105195711235580",
            "460107196309081122",
            "460108194706172234"
    };

    public static void main(String[] args) throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode(PLAIN_PASSWORD);

        StringBuilder sb = new StringBuilder();
        sb.append("# 由 GenSeedSecrets 生成，勿手工修改\n");
        sb.append("PLAIN_PASSWORD=").append(PLAIN_PASSWORD).append('\n');
        sb.append("BCRYPT=").append(hash).append('\n');
        sb.append("BCRYPT_MATCHES=").append(encoder.matches(PLAIN_PASSWORD, hash)).append('\n');
        sb.append("# --- id card ciphertext ---\n");
        for (int i = 0; i < ID_CARDS.length; i++) {
            String enc = AesUtil.encrypt(ID_CARDS[i], AES_KEY);
            String back = AesUtil.decrypt(enc, AES_KEY);
            sb.append("IDCARD_").append(i + 1).append('|')
              .append(ID_CARDS[i]).append('|')
              .append(enc).append('|')
              .append("roundtrip=").append(ID_CARDS[i].equals(back)).append('\n');
        }

        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0]), StandardCharsets.UTF_8))) {
            w.write(sb.toString());
        }
        System.out.println("WROTE " + args[0]);
    }
}
