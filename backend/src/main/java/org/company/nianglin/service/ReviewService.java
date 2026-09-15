package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.ReviewCreateDTO;
import org.company.nianglin.dto.ReviewQuery;
import org.company.nianglin.vo.CompanionScoreVO;
import org.company.nianglin.vo.ReviewCreateResultVO;
import org.company.nianglin.vo.ReviewVO;

/**
 * 评价服务。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §1 ~ §4。</p>
 *
 * <h3>一单一评靠数据库，不靠前端按钮</h3>
 *
 * <p>{@code order_review} 上有唯一索引 {@code uk_order_review (order_id)}。
 * 前端把「评价」按钮置灰只是体验优化 —— 双开页面、网络重放、
 * 或者直接调接口都能绕过它。因此重复评价必须在<b>插入时</b>被数据库拦住，
 * 再由服务层翻译成 {@code 6002}。</p>
 *
 * <h3>评分聚合缓存的失效时机</h3>
 *
 * <p>缓存 key 为 {@code companion:score:{companionId}}，只在
 * <b>评价写入成功后</b>删除，不做定时刷新、不做批量预热。
 * 理由：评分是「低频写、高频读」的典型数据，写路径删除缓存即可；
 * 定时刷新会在没有任何新评价的日子里反复做无用功，
 * 而预热的时机一旦选错（比如在事务提交前重建），缓存里会长期留着旧值。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
public interface ReviewService {

    /**
     * 提交评价。
     *
     * <p>成功后会自动把订单从 {@code COMPLETED} 推进到 {@code REVIEWED}。</p>
     *
     * @throws org.company.nianglin.exception.BusinessException
     *         3004 非本单下单人 / 3001 订单不存在 / 6001 订单未完成 / 6002 已评价 / 6003 敏感词
     */
    ReviewCreateResultVO create(ReviewCreateDTO dto);

    /** 查询订单评价；未评价返回 {@code null} */
    ReviewVO byOrder(Long orderId);

    /** 陪诊员评价列表（排除被管理员判定无效的评价） */
    PageResult<ReviewVO> byCompanion(Long companionId, ReviewQuery query);

    /** 陪诊员评分聚合（优先走缓存；无评价返回 0.00 而不是 null） */
    CompanionScoreVO score(Long companionId);

    /**
     * 重算并同步陪诊员的评分快照与缓存。
     *
     * <p>对外暴露是为了让 M9（管理员判定某条评价无效）能触发重算。
     * 若把它藏在 {@code create} 内部，M9 就只能自己写一遍
     * 「更新 companion_profile.score + 删 Redis」——
     * 而「判定无效后忘记重算」正好是这个功能最典型的 bug，
     * 一次漏算会让陪诊员的分数永远停在被判定无效之前的水平。</p>
     */
    void refreshCompanionScore(Long companionId);
}
