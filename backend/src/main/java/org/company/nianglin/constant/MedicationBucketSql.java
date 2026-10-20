package org.company.nianglin.constant;

/**
 * 用药三桶的 SQL 判定表达式 —— 与 {@link MedicationBucket} 配套的<b>唯一真源</b>（V3.2 · T-0）。
 *
 * <h3>为什么要把 SQL 文本也抽成常量</h3>
 *
 * <p>如果每处聚合 SQL 各写各的 {@code CASE WHEN}，判定规则就会随 SQL 的数量增长而漂移 ——
 * 这正是 M10 与 M6 曾经各写一套、最后对不上的原因。</p>
 *
 * <p>现在<b>整条 SQL 里只出现一次</b>判定：先在内层子查询算出 {@code bucket}，
 * 外层再用 {@code bucket} 派生 taken / missed / pending 三个计数。
 * 于是 SQL 侧也只有<b>一个</b>分类点，taken / missed / pending 不可能彼此打架。</p>
 *
 * <h3>为什么是编译期常量</h3>
 *
 * <p>MyBatis 的 {@code @Select} 注解值必须是编译期常量表达式，
 * 而 {@code Enum.name()} 不是常量 —— 所以这里的字面量必须写成
 * {@code static final String}，由 {@link MedicationBucket} 引用同一份。
 * {@code MedicationBucketParityTest} 断言「枚举名 == 这里的字面量」，
 * 一旦有人改了枚举名而忘了改这里，测试立刻变红。</p>
 *
 * <h3>引用方式</h3>
 *
 * <pre>{@code
 * @Select("SELECT d, COUNT(*) AS total, "
 *       + "SUM(CASE WHEN bucket = 'TAKEN' THEN 1 ELSE 0 END) AS taken, ... "
 *       + "FROM (SELECT ..., " + MedicationBucketSql.CASE_BUCKET + " AS bucket FROM medication_task ...) t "
 *       + "GROUP BY d")
 * }</pre>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
public final class MedicationBucketSql {

    /** 已服桶的字面量（与 {@code medication_task.status} 的取值一致） */
    public static final String CODE_TAKEN = "TAKEN";

    /** 漏服桶的字面量 */
    public static final String CODE_MISSED = "MISSED";

    /** 待服桶的字面量 */
    public static final String CODE_PENDING = "PENDING";

    /**
     * 三桶判定的 SQL 表达式。
     *
     * <p>引用的列名固定为 {@code was_missed} 与 {@code status}，
     * 因此只能用在 {@code medication_task} 的查询里（或别名等价的子查询）。</p>
     *
     * <p>判定顺序与 {@link MedicationBucket#of(String, Boolean)} 一致：
     * 漏服优先，其次待服，其余已服。</p>
     */
    public static final String CASE_BUCKET =
            "CASE WHEN (was_missed = 1 OR status = '" + CODE_MISSED + "') THEN '" + CODE_MISSED + "' "
                    + "WHEN status = '" + CODE_PENDING + "' THEN '" + CODE_PENDING + "' "
                    + "ELSE '" + CODE_TAKEN + "' END";

    private MedicationBucketSql() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }
}
