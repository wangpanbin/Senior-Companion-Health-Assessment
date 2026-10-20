package org.company.nianglin.constant;

/**
 * 服药任务的三桶划分 —— 用药指标口径的<b>唯一真源</b>（V3.2 · T-0）。
 *
 * <h3>为什么需要这一层</h3>
 *
 * <p>仓库里「漏服」曾经同时存在两套判定：</p>
 * <ul>
 *   <li>M6 的 {@code MedicationCalendarVO.of}：{@code was_missed = 1} <b>或</b> {@code status = 'MISSED'}；</li>
 *   <li>M10 的 {@code StatisticsMapper.selectDailyMedicationCounts}：只有 {@code was_missed = 1}。</li>
 * </ul>
 *
 * <p>第二套会让「扫描判定的漏服」（{@code status = 'MISSED'} 且 {@code was_missed = 0}）
 * <b>既不计入已服也不计入漏服</b>，{@code taken + missed ≠ total}；
 * 而补记过的任务（{@code status = 'TAKEN'} 且 {@code was_missed = 1}）会被重复计入。
 * 根因是漏服扫描只写 {@code status = 'MISSED'}、<b>从不写</b> {@code was_missed}。
 * 更糟的是两处注释都宣称「与服药日历口径一致」—— 那句话是错的。
 *
 * <p>现在判定只写在这一处；SQL 侧复用 {@link MedicationBucketSql#CASE_BUCKET}，
 * 两侧由 {@code MedicationBucketParityTest} 锁死，任何一边单独改动都会让测试变红。
 *
 * <h3>三桶必须构成「划分」</h3>
 *
 * <p>每条任务<b>恰好</b>落进一个桶，因此恒有：</p>
 * <pre>{@code total == taken + missed + pending}</pre>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
public enum MedicationBucket {

    /** 已服：确认过，且从未漏过 */
    TAKEN(MedicationBucketSql.CODE_TAKEN, "已服"),

    /** 漏服：扫描判定漏服，或漏服之后补记过 */
    MISSED(MedicationBucketSql.CODE_MISSED, "漏服"),

    /** 待服：还没到确认时点，尚未结算 */
    PENDING(MedicationBucketSql.CODE_PENDING, "待服");

    /** 与 SQL 侧共用的字面量，保证 Java 与 SQL 说同一件事 */
    private final String code;

    /** 中文展示名 */
    private final String label;

    MedicationBucket(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 桶的字面量（与 SQL 中的字符串一致） */
    public String getCode() {
        return code;
    }

    /** 中文展示名 */
    public String getLabel() {
        return label;
    }

    /**
     * 判定一条服药任务落在哪个桶。
     *
     * <p><b>判定顺序不能调换</b>：</p>
     * <ol>
     *   <li>{@code was_missed = 1} 或 {@code status = 'MISSED'} → <b>漏服</b>。
     *       前者是「漏了之后被补记」（补记后 {@code status} 已变成 {@code TAKEN}，
     *       只有 {@code was_missed} 还留着痕迹），后者是「一直没吃被扫描判成漏服」。
     *       两者都是事实上的漏服，必须一起统计。</li>
     *   <li>{@code status = 'PENDING'} → <b>待服</b>。</li>
     *   <li>其余 → <b>已服</b>。</li>
     * </ol>
     *
     * <p><b>「曾经漏服」优先于「当前状态」</b>：否则补记过的任务会被算进「已服」，
     * 漏服率立刻被稀释。兜底返回 {@link #TAKEN} 而不是抛异常 ——
     * 库里有一条脏状态值不该让整页统计 500。</p>
     *
     * @param status    任务状态字面量，可为 {@code null}
     * @param wasMissed 是否曾判定漏服，可为 {@code null}（按未漏服处理）
     * @return 落点桶，永远非 {@code null}
     */
    public static MedicationBucket of(String status, Boolean wasMissed) {
        if (Boolean.TRUE.equals(wasMissed) || MedicationBucketSql.CODE_MISSED.equals(status)) {
            return MISSED;
        }
        if (MedicationBucketSql.CODE_PENDING.equals(status)) {
            return PENDING;
        }
        return TAKEN;
    }
}
