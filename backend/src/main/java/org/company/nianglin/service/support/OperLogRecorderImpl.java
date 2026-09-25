package org.company.nianglin.service.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.util.RequestInfoUtil;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class OperLogRecorderImpl implements OperLogRecorder {

    private static final int MAX_TARGET_DESC_LENGTH = 100;

    private final AdminOperLogMapper operLogMapper;
    private final UserNameResolver userNameResolver;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(OperType operType, OperTargetType targetType, Long targetId,
                       String targetDesc, String beforeStatus, String afterStatus, String remark) {
        LoginUser currentUser = SecurityUtils.currentUser();
        AdminOperLog log = new AdminOperLog();
        log.setOperatorId(currentUser.userId());
        log.setOperatorName(userNameResolver.resolve(currentUser.userId()));
        log.setOperType(operType.name());
        log.setTargetType(targetType.name());
        log.setTargetId(targetId);
        log.setTargetDesc(truncate(targetDesc, MAX_TARGET_DESC_LENGTH));
        log.setBeforeStatus(beforeStatus);
        log.setAfterStatus(afterStatus);
        log.setRemark(remark);
        log.setRequestUrl(RequestInfoUtil.requestUrl());
        log.setRequestMethod(RequestInfoUtil.requestMethod());
        log.setIp(RequestInfoUtil.clientIp());
        log.setOperTime(LocalDateTime.now());
        operLogMapper.insert(log);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
