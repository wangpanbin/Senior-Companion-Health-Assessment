package org.company.nianglin.service.sms;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.company.nianglin.dto.NotificationPreferenceUpdateDTO;
import org.company.nianglin.entity.NotificationPreference;
import org.company.nianglin.mapper.NotificationPreferenceMapper;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.vo.NotificationPreferenceVO;
import org.springframework.stereotype.Service;

/**
 * 通知偏好读写（V4 · P1-3）。
 *
 * <p><b>无记录即默认开启</b>：迁移不回填数据，家属第一次打开页面看到的是两个默认打开的开关；
 * 只有真正改过设置的人才会有 {@code notification_preference} 行。</p>
 *
 * <p>所有读写都以「当前登录用户」为准，<b>入参不接收 userId</b> ——
 * 否则家属 A 可以改家属 B 的提醒设置。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-3
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceMapper preferenceMapper;

    /** 读取当前用户偏好；从未设置过返回两个 true */
    public NotificationPreferenceVO get() {
        NotificationPreference preference = findCurrent();
        return preference == null ? defaults() : toVo(preference);
    }

    /** 更新当前用户偏好；首次更新时插入一行 */
    public NotificationPreferenceVO update(NotificationPreferenceUpdateDTO dto) {
        NotificationPreference preference = findCurrent();
        if (preference == null) {
            preference = new NotificationPreference();
            preference.setUserId(SecurityUtils.currentUserId());
            preference.setSmsMissedEnabled(dto.getSmsMissedEnabled() ? 1 : 0);
            preference.setQuietHoursEnabled(dto.getQuietHoursEnabled() ? 1 : 0);
            preferenceMapper.insert(preference);
        } else {
            preference.setSmsMissedEnabled(dto.getSmsMissedEnabled() ? 1 : 0);
            preference.setQuietHoursEnabled(dto.getQuietHoursEnabled() ? 1 : 0);
            preferenceMapper.updateById(preference);
        }
        return toVo(preference);
    }

    private NotificationPreference findCurrent() {
        return preferenceMapper.selectOne(Wrappers.<NotificationPreference>lambdaQuery()
                .eq(NotificationPreference::getUserId, SecurityUtils.currentUserId()));
    }

    private static NotificationPreferenceVO defaults() {
        NotificationPreferenceVO vo = new NotificationPreferenceVO();
        vo.setSmsMissedEnabled(Boolean.TRUE);
        vo.setQuietHoursEnabled(Boolean.TRUE);
        return vo;
    }

    private static NotificationPreferenceVO toVo(NotificationPreference preference) {
        NotificationPreferenceVO vo = new NotificationPreferenceVO();
        vo.setSmsMissedEnabled(preference.getSmsMissedEnabled() == null
                || preference.getSmsMissedEnabled() == 1);
        vo.setQuietHoursEnabled(preference.getQuietHoursEnabled() == null
                || preference.getQuietHoursEnabled() == 1);
        return vo;
    }
}
