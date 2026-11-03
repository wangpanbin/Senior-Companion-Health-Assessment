package org.company.nianglin.service.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.SmsProperties;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.util.MaskUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云短信通道（Dysmsapi RPC，V4 · P1-1）。
 *
 * <p><b>为什么不加 Maven 依赖</b>：项目纪律禁止擅自新增依赖（AGENTS.md §0.2）。
 * 阿里云 Dysmsapi 的 RPC 签名只用到 JDK 自带的 {@link HttpClient} 与
 * {@code javax.crypto.Mac}，因此这里手写签名，不引入官方 SDK。</p>
 *
 * <p><b>签名算法</b>（阿里云 RPC 风格）：</p>
 * <ol>
 *   <li>公共参数 + 业务参数按 key 字典序排列；</li>
 *   <li>对 key / value 逐个做 RFC 3986 percent-encode 后拼成
 *       {@code k=v&k=v}（空格 {@code %20}、{@code *} → {@code %2A}、{@code ~} 不编码）；</li>
 *   <li>{@code StringToSign = "POST&%2F&" + percentEncode(canonicalQuery)}；</li>
 *   <li>{@code Signature = Base64(HMAC-SHA1(accessKeySecret + "&", StringToSign))}；</li>
 *   <li>POST {@code application/x-www-form-urlencoded} 到
 *       {@code https://dysmsapi.aliyuncs.com/}。</li>
 * </ol>
 *
 * <p>失败（HTTP 非 200 或响应 {@code Code != "OK"}）抛业务异常；
 * 日志只打脱敏手机号、阿里云返回的 Code / Message / RequestId，
 * <b>不打密钥、不打完整手机号、不打验证码</b>。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-1
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nianglin.sms.provider", havingValue = "aliyun", matchIfMissing = false)
public class AliyunSmsSender implements SmsSender {

    private static final String ENDPOINT = "https://dysmsapi.aliyuncs.com/";
    private static final String ALIYUN_API_VERSION = "2017-05-25";
    private static final String ALIYUN_REGION_ID = "cn-hangzhou";
    private static final DateTimeFormatter UTC_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final SmsProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AliyunSmsSender(SmsProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    @Override
    public void send(String phone, String templateCode, Map<String, String> params) {
        if (templateCode == null || templateCode.isBlank()) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "短信模板未配置，请联系管理员");
        }

        try {
            Map<String, String> allParams = buildParams(phone, templateCode, params);
            String canonicalQuery = buildCanonicalQuery(allParams);
            String signature = sign(canonicalQuery);
            String body = canonicalQuery + "&Signature=" + percentEncode(signature);

            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = objectMapper.readTree(response.body());
            String code = json.path("Code").asText("");
            if (response.statusCode() != 200 || !"OK".equals(code)) {
                log.error("阿里云短信发送失败 | phone={} | http={} | code={} | message={} | requestId={}",
                        MaskUtil.phone(phone), response.statusCode(), code,
                        json.path("Message").asText(""), json.path("RequestId").asText(""));
                throw new BusinessException(ResultCode.SYSTEM_ERROR, "短信发送失败，请稍后再试");
            }
            log.info("阿里云短信发送成功 | phone={} | requestId={}",
                    MaskUtil.phone(phone), json.path("RequestId").asText(""));
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("阿里云短信发送被中断 | phone={} | err={}", MaskUtil.phone(phone), e.toString());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "短信发送失败，请稍后再试");
        } catch (Exception e) {
            log.error("阿里云短信发送异常 | phone={} | err={}", MaskUtil.phone(phone), e.toString());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "短信发送失败，请稍后再试");
        }
    }

    private Map<String, String> buildParams(String phone, String templateCode, Map<String, String> params)
            throws Exception {
        Map<String, String> all = new TreeMap<>();
        all.put("AccessKeyId", properties.getAccessKeyId());
        all.put("Action", "SendSms");
        all.put("Format", "JSON");
        all.put("PhoneNumbers", phone);
        all.put("RegionId", ALIYUN_REGION_ID);
        all.put("SignName", properties.getSignName());
        all.put("SignatureMethod", "HMAC-SHA1");
        all.put("SignatureNonce", UUID.randomUUID().toString());
        all.put("SignatureVersion", "1.0");
        all.put("TemplateCode", templateCode);
        all.put("TemplateParam", objectMapper.writeValueAsString(params == null ? Map.of() : params));
        all.put("Timestamp", UTC_SECONDS.format(Instant.now()));
        all.put("Version", ALIYUN_API_VERSION);
        return all;
    }

    /**
     * 按 key 字典序拼 canonicalized query string。
     *
     * <p>使用 {@link TreeMap} 保证顺序；value 逐个 percent-encode，
     * 因此中文签名 / JSON 模板参数不会破坏签名。</p>
     */
    private static String buildCanonicalQuery(Map<String, String> sortedParams) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            joiner.add(percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()));
        }
        return joiner.toString();
    }

    private String sign(String canonicalQuery) throws Exception {
        String stringToSign = "POST&" + percentEncode("/") + "&" + percentEncode(canonicalQuery);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(
                (properties.getAccessKeySecret() + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 阿里云要求的 RFC 3986 编码：在 {@link URLEncoder} 的基础上把
     * {@code +} 还原成 {@code %20}、{@code *} 转义为 {@code %2A}、{@code ~} 不编码。
     */
    static String percentEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
