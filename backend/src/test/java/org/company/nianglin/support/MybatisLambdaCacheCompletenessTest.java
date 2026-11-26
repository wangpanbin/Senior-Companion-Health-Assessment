package org.company.nianglin.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link MybatisLambdaCache} 的登记完整性检查。
 *
 * <h3>为什么需要这条测试</h3>
 *
 * <p>{@code MybatisLambdaCache} 的 javadoc 写着「新增实体后请在上面的 import 与
 * {@code ENTITY_CLASSES} 里补一行 —— 漏补只会让新实体的单测报上面那个异常」。
 * 那是一句**口头约定**，而口头约定的失效方式恰好是最难查的那种：</p>
 *
 * <ul>
 *   <li>单独跑某一个纯 Mockito 单测 → {@code can not find lambda cache for this entity}，红；</li>
 *   <li>跑全量 {@code mvn test} → 前面任何一个 {@code @SpringBootTest} 已经建好缓存，
 *       <b>同一个测试变成绿的</b>。</li>
 * </ul>
 *
 * <p>也就是说漏登记的后果是「靠执行顺序决定成败」。2026-11-26 实测：29 个实体里
 * 有 5 个（{@code ChatMessage} / {@code ChatReadCursor} / {@code CompanionAvailableSlot} /
 * {@code OrderFeeItem} / {@code OrderFulfillmentEvent}）不在名单里，一直没被发现。</p>
 *
 * <h3>为什么扫源码而不是反射扫包</h3>
 *
 * <p>测试里做 classpath 扫描需要引入扫描库或自己写 jar 遍历，两者都比「读
 * {@code entity} 目录下的 .java 文件、找 {@code @TableName}」更重且更易碎。
 * 工作目录固定是 {@code backend/}（Maven 的 basedir），路径是确定的。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@DisplayName("MybatisLambdaCache 登记完整性")
class MybatisLambdaCacheCompletenessTest {

    /** Maven 的 basedir 是 backend/，surefire 默认工作目录与之一致 */
    private static final Path ENTITY_DIR = Paths.get("src/main/java/org/company/nianglin/entity");

    @Test
    @DisplayName("entity 包下所有带 @TableName 的类都必须在 ENTITY_CLASSES 里登记")
    void everyEntityShouldBeRegistered() throws IOException {
        Set<String> entities = scanEntityClassNames();
        // 目录读不到时直接失败而不是静默跳过：静默跳过会让这条测试永远假绿
        assertTrue(!entities.isEmpty(),
                "没扫到任何实体类，工作目录可能不是 backend/：" + ENTITY_DIR.toAbsolutePath());

        Set<String> registered = registeredEntityNames();

        Set<String> missing = new TreeSet<>(entities);
        missing.removeAll(registered);

        if (!missing.isEmpty()) {
            fail("以下实体带 @TableName 但未登记到 MybatisLambdaCache.ENTITY_CLASSES："
                    + missing
                    + " —— 漏登记会让它们的纯 Mockito 单测在「单独跑」时红、在「全量跑」时假绿。"
                    + "请在 ENTITY_CLASSES 里补一行。");
        }
    }

    @Test
    @DisplayName("ENTITY_CLASSES 里不该留已删除 / 改名实体的残留条目")
    void everyRegisteredClassShouldStillExist() throws IOException {
        Set<String> entities = scanEntityClassNames();
        Set<String> registered = registeredEntityNames();

        Set<String> stale = new TreeSet<>(registered);
        stale.removeAll(entities);

        if (!stale.isEmpty()) {
            fail("MybatisLambdaCache.ENTITY_CLASSES 里有实体包中已不存在的类：" + stale
                    + " —— 类被删除 / 改名后忘了同步这份名单，"
                    + "它指着一个不存在的类，下一次真跑会编译失败而不是在这里被拦住。");
        }
    }

    /* ================================================================== */

    /** 扫 entity 目录下所有含 {@code @TableName} 的 .java，返回类名（不含 .java） */
    private static Set<String> scanEntityClassNames() throws IOException {
        Set<String> names = new TreeSet<>();
        if (!Files.isDirectory(ENTITY_DIR)) {
            return names;
        }
        try (Stream<Path> files = Files.list(ENTITY_DIR)) {
            List<Path> javaFiles = new ArrayList<>();
            files.filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
            for (Path file : javaFiles) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains("@TableName")) {
                    String name = file.getFileName().toString();
                    names.add(name.substring(0, name.length() - ".java".length()));
                }
            }
        }
        return names;
    }

    /** 反射读 ENTITY_CLASSES（私有静态字段），取每个 Class 的简单名 */
    private static Set<String> registeredEntityNames() {
        Set<String> names = new TreeSet<>();
        try {
            Field field = MybatisLambdaCache.class.getDeclaredField("ENTITY_CLASSES");
            field.setAccessible(true);
            for (Class<?> clazz : (Class<?>[]) field.get(null)) {
                names.add(clazz.getSimpleName());
            }
        } catch (ReflectiveOperationException e) {
            fail("读不到 MybatisLambdaCache.ENTITY_CLASSES —— 字段可能被改名了：" + e);
        }
        return names;
    }
}
