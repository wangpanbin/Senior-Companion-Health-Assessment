package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.ElderBindDTO;
import org.company.nianglin.dto.ElderCreateDTO;
import org.company.nianglin.dto.ElderQuery;
import org.company.nianglin.dto.ElderUpdateDTO;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.vo.ElderVO;

/**
 * 老人档案服务。
 *
 * <p>对应 {@code docs/api/02-elder-family.md} §6 ~ §12。</p>
 *
 * <h3>这个接口是「资源归属校验」的样板</h3>
 *
 * <p>角色鉴权只回答「你是什么角色」，回答不了「这条数据是不是你的」。
 * 一个 FAMILY 角色的家属去读别人绑定老人的档案，角色校验会放行，
 * 只有归属校验能拦住 —— 返回 {@code 2006}。因此本接口的每个方法
 * <b>第一件事都是把归属查清楚</b>，而不是先查数据再补一句 if。</p>
 *
 * <p>M4 订单模块需要判断「这个老人能不能由我下单」时，
 * 直接复用 {@link #requireAccessible(Long)}，不要另写一套判断。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
public interface ElderService {

    /** 我的老人档案分页列表（只含当前家属有效绑定的老人） */
    PageResult<ElderVO> page(ElderQuery query);

    /** 新增老人档案，返回新档案 ID；建档人与档案自动建立绑定关系 */
    Long create(ElderCreateDTO dto);

    /** 老人档案详情 */
    ElderVO detail(Long elderId);

    /** 修改老人档案（局部更新） */
    void update(Long elderId, ElderUpdateDTO dto);

    /** 删除老人档案（逻辑删除，有进行中订单时拒绝） */
    void delete(Long elderId);

    /** 按手机号认领已有老人账号，返回老人档案 ID */
    Long bind(ElderBindDTO dto);

    /** 解绑（有进行中订单时拒绝） */
    void unbind(Long elderId);

    /**
     * 校验当前登录用户是否有权访问指定老人档案，有权则返回档案实体。
     *
     * <p>这是跨模块复用的入口：{@code ADMIN} 直通；
     * {@code FAMILY} 需存在有效绑定关系（否则 2006）；
     * {@code ELDER} 仅限本人档案（否则 2006）；
     * 其他角色一律 2006。</p>
     *
     * <p>⚠️ 返回的是<b>实体</b>，内含 AES 密文身份证号。调用方不得把它当 VO 直接返回，
     * 也不得把整个实体打进日志。</p>
     *
     * @param elderId 老人档案 ID
     * @return 档案实体（非 null）
     * @throws org.company.nianglin.exception.BusinessException 2001 档案不存在 / 2006 无权访问
     */
    ElderProfile requireAccessible(Long elderId);
}
