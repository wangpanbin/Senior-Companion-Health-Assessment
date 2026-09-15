package org.company.nianglin.service;

import org.company.nianglin.dto.CheckinCreateDTO;
import org.company.nianglin.vo.CheckinResultVO;
import org.company.nianglin.vo.CheckinVO;
import org.company.nianglin.vo.FileUploadVO;
import org.company.nianglin.vo.ProgressVO;
import org.company.nianglin.vo.TrackPointVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 陪诊执行与打卡服务。
 *
 * <p>对应 {@code docs/api/04-companion-execution.md} §1 ~ §5。</p>
 *
 * <h3>两条主线</h3>
 *
 * <ol>
 *   <li><b>打卡必须同时满足「人对了」与「状态对了」</b>。
 *       角色注解只能保证调用者是陪诊员，保证不了他是<b>这一单</b>的陪诊员（{@code 4003}），
 *       也保证不了订单已经到了可以打卡的状态（{@code 3002}）。</li>
 *   <li><b>「可跳过、不可回退」是本模块的核心约束</b>。
 *       跳过取药是常态（本次不需要开药），但先打「取药」再打「到院」
 *       会让整条时间线失去可读性 —— 那意味着记录本身不可信了。</li>
 * </ol>
 *
 * <h3>读写归属都走同一套判定</h3>
 *
 * <p>查询类接口（打卡列表 / 轨迹 / 进度）一律复用
 * {@link OrderService#requireInvolved(Long)}，不另写一套。
 * 三套归属判断里最松的那一套就是漏洞。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
public interface ExecutionService {

    /**
     * 打卡。
     *
     * <p>须为本单陪诊员，订单状态须为 {@code ACCEPTED} 或 {@code IN_SERVICE}。
     * 成功后同时写入 {@code order_checkin} 与 {@code companion_track}（同一事务），
     * 并向该订单的订阅者推送进度事件。</p>
     */
    CheckinResultVO checkin(Long orderId, CheckinCreateDTO dto);

    /** 打卡记录列表（按 {@code checkin_time} 升序） */
    List<CheckinVO> checkins(Long orderId);

    /** 轨迹点列表（按 {@code record_time} 升序） */
    List<TrackPointVO> track(Long orderId);

    /** 进度快照（家属端进页面先拉一次，之后靠 WebSocket 增量更新） */
    ProgressVO progress(Long orderId);

    /** 上传现场照片 / 取药凭证，须为本单陪诊员 */
    FileUploadVO uploadPhoto(Long orderId, MultipartFile file);
}
