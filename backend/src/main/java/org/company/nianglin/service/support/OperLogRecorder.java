package org.company.nianglin.service.support;

import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;

public interface OperLogRecorder {

    void record(OperType operType, OperTargetType targetType, Long targetId,
                String targetDesc, String beforeStatus, String afterStatus, String remark);
}
