package org.company.nianglin.service.support;

import org.company.nianglin.constant.FulfillmentRisk;

/**
 * 履约风险判定（V3.1 · T1）。
 *
 * <h3>为什么单独抽成一个纯函数</h3>
 *
 * <p>风险判定是 V3.1 唯一有「分支组合爆炸」风险的新逻辑：三个阈值 × 各种边界值 × 脏数据。
 * 把它做成<b>纯函数</b>（无状态、无 Spring、无 Mapper、<b>无时钟依赖</b>）带来三个好处：</p>
 *
 * <ol>
 *   <li>时间由<b>参数</b>传入而不是读 {@code LocalDateTime.now()}，
 *       测试不必 mock 时钟，也不必 sleep；</li>
 *   <li>不依赖数据库，单测在毫秒级跑完，能覆盖全部阈值边界；</li>
 *   <li>管理端风险中心、订单详情、扫描任务三方<b>共用同一个判定</b>，
 *       从根上杜绝「家属端显示紧急、管理员端显示正常」的口径分裂。</li>
 * </ol>
 *
 * <h3>判定规则</h3>
 *
 * <p>按严重度从高到低短路，<b>恰好等于阈值即升档</b>（{@code >=} 而非 {@code >}）。
 * 这条边界必须钉住 —— 写成 {@code >} 会让「等了正好 30 分钟」的订单比
 * 「等了 31 分钟」的订单晚一档才升级，看起来像随机行为。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
public final class FulfillmentRiskEvaluator {

    private FulfillmentRiskEvaluator() {
        // 工具类，禁止实例化
    }

    /**
     * 按等待分钟数判定履约风险等级。
     *
     * @param waitMinutes        已等待分钟数（允许为负，表示下单时间异常，按最低等级处理）
     * @param warningMinutes     预警阈值
     * @param urgentMinutes      高风险阈值
     * @param manualRequiredMinutes 人工介入阈值
     * @return 风险等级；永不返回 {@code null}
     */
    public static FulfillmentRisk evaluate(int waitMinutes,
                                           int warningMinutes,
                                           int urgentMinutes,
                                           int manualRequiredMinutes) {
        // 脏数据防御：下单时间比当前时间还晚（时钟漂移 / 人工改库）时不要抛异常，
        // 更不要把它误判成高风险 —— 那会凭空产生一条「需要人工介入」的告警。
        if (waitMinutes < 0) {
            return FulfillmentRisk.NORMAL;
        }
        if (waitMinutes >= manualRequiredMinutes) {
            return FulfillmentRisk.MANUAL_REQUIRED;
        }
        if (waitMinutes >= urgentMinutes) {
            return FulfillmentRisk.URGENT;
        }
        if (waitMinutes >= warningMinutes) {
            return FulfillmentRisk.WARNING;
        }
        return FulfillmentRisk.NORMAL;
    }

    /**
     * 校验阈值配置是否自洽（V3.1 · T1 启动检查）。
     *
     * <p>「紧急阈值 10 分钟、预警阈值 30 分钟」这种反向配置如果静默生效，
     * 表现出来是订单先变紧急、很久之后才变预警 —— 一个没人能解释的行为。
     * 与其让配置者几个月后才发现不对，不如在启动时就炸。</p>
     *
     * @throws IllegalStateException 阈值顺序不合法
     */
    public static void validateThresholds(int warningMinutes,
                                          int urgentMinutes,
                                          int manualRequiredMinutes) {
        if (warningMinutes <= 0) {
            throw new IllegalStateException(
                    "nianglin.fulfillment.warning-minutes 必须大于 0，当前为 " + warningMinutes);
        }
        if (urgentMinutes <= warningMinutes) {
            throw new IllegalStateException(
                    "nianglin.fulfillment.urgent-minutes（" + urgentMinutes
                            + "）必须大于 warning-minutes（" + warningMinutes + "）");
        }
        if (manualRequiredMinutes <= urgentMinutes) {
            throw new IllegalStateException(
                    "nianglin.fulfillment.manual-required-minutes（" + manualRequiredMinutes
                            + "）必须大于 urgent-minutes（" + urgentMinutes + "）");
        }
    }
}
