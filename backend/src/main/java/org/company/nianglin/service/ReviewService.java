package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.ReviewCreateDTO;
import org.company.nianglin.dto.ReviewQuery;
import org.company.nianglin.dto.ReviewRulingDTO;
import org.company.nianglin.dto.ReviewReplyDTO;
import org.company.nianglin.vo.CompanionScoreVO;
import org.company.nianglin.vo.ReviewCreateResultVO;
import org.company.nianglin.vo.ReviewReplyResultVO;
import org.company.nianglin.vo.ReviewRulingResultVO;
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

    ReviewRulingResultVO reviewValidity(Long reviewId, ReviewRulingDTO dto);

    /**
     * 陪诊员回复评价（E4 评价公信力闭环）。
     *
     * <p>「一评一回复，落库即定稿」：成功后 {@code companion_reply} 与
     * {@code reply_time} 写入；本方法不触发评分重算（回复不参与聚合），
     * 仅向评价家属发送一条站内信（{@code MessageType.REVIEW_REPLIED}）。</p>
     *
     * @throws org.company.nianglin.exception.BusinessException
     *         6005 评价不存在 / 6006 已回复（含并发穿透）/ 6003 敏感词 / 3004 非该评价的陪诊员
     */
    ReviewReplyResultVO reply(Long reviewId, ReviewReplyDTO dto);
}
