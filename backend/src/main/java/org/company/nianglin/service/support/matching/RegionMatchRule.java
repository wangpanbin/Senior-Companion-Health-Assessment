package org.company.nianglin.service.support.matching;

/**
 * 区域匹配规则（V3.4 · FR-Rule）。
 *
 * <h3>口径</h3>
 *
 * <pre>
 * 区域码相等                    → 1.0
 * 同市不同区（HAIKOU_X vs HAIKOU_Y）→ 0.5
 * 跨市（HAIKOU_x vs SANYA_y）    → 0
 * </pre>
 *
 * <p>「市」这一层由区域码的<b>城市前缀</b>决定（{@code _} 之前）——
 * 区域码格式见 {@code docs/sql/V7__matching_structured.sql} 的头部说明。</p>
 *
 * <h3>结构化列缺失时回退自由文本</h3>
 *
 * <p>区域码是 V3.4 才加的列，老数据全是自由文本。所以任何一方没有区域码时，
 * 回退到<b>包含式</b>匹配（陪诊员的 {@code service_area} 出现在订单地址里）。
 * 两者都没有时**记 0 并写明降级原因**，绝不推断。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
public class RegionMatchRule implements MatchingRule {

    @Override
    public String dimension() {
        return MatchingWeights.DIMENSION_REGION;
    }

    @Override
    public String displayName() {
        return "区域";
    }

    @Override
    public DimensionResult evaluate(MatchingCandidate candidate, MatchTarget target) {
        if (candidate.hasRegionCode() && target.hasRegionCode()) {
            String candidateCode = candidate.regionCode().trim().toUpperCase();
            String targetCode = target.regionCode().trim().toUpperCase();
            if (candidateCode.equals(targetCode)) {
                return DimensionResult.of(1.0D, "同服务区域");
            }
            if (cityOf(candidateCode).equals(cityOf(targetCode))) {
                return DimensionResult.of(0.5D, "同一城市不同区域");
            }
            return DimensionResult.of(0.0D, "跨城市");
        }

        // 回退：自由文本包含式（结构化列缺失的老数据）
        String serviceArea = candidate.serviceArea();
        String address = target.address();
        if (serviceArea != null && !serviceArea.isBlank()
                && address != null && address.contains(serviceArea.trim())) {
            return DimensionResult.degraded(1.0D, "服务区域与订单地址一致",
                    "未登记区域码，按自由文本匹配");
        }
        return DimensionResult.degraded(0.0D, "区域不匹配",
                "缺少区域码且自由文本未命中，区域维度记 0");
    }

    /** 区域码的城市前缀 —— 「区」这一层的判定依据 */
    public static String cityOf(String regionCode) {
        int idx = regionCode.indexOf('_');
        return idx <= 0 ? regionCode : regionCode.substring(0, idx);
    }
}
