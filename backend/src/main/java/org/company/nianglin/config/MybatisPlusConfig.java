package org.company.nianglin.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * MyBatis-Plus 配置。
 *
 * <p>三个插件的用途（对应 plan.md 技术亮点）：</p>
 * <ol>
 *   <li>{@link PaginationInnerInterceptor} —— 分页插件，所有列表接口统一用它</li>
 *   <li>{@link OptimisticLockerInnerInterceptor} —— <b>乐观锁，防陪诊员超卖接单</b>（M4 核心验收项）</li>
 *   <li>{@link BlockAttackInnerInterceptor} —— 拦截全表更新 / 删除，防止误操作打穿数据</li>
 * </ol>
 *
 * <p>⚠️ 分页插件的 {@code maxLimit} 必须设置，避免 {@code size=999999} 把数据库拉爆。</p>
 *
 * @author 银龄伴诊团队
 */
@Configuration
@EnableTransactionManagement
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 乐观锁：实体上标注 @Version 的字段自动参与并发控制（order 表的 version 字段）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 分页：单页上限 100 条，与 PageQuery.normalizedSize() 保持一致
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 防全表更新 / 删除
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
