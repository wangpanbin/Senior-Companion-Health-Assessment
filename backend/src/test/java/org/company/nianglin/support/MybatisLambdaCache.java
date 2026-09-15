package org.company.nianglin.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.entity.CompanionAuditRecord;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.CompanionTrack;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.FamilyElderRelation;
import org.company.nianglin.entity.InternalMessage;
import org.company.nianglin.entity.MedicationPlan;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.entity.MedicineDict;
import org.company.nianglin.entity.OrderCheckin;
import org.company.nianglin.entity.OrderRejectLog;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.entity.OrderStatusLog;
import org.company.nianglin.entity.SysDict;
import org.company.nianglin.entity.SysFile;
import org.company.nianglin.entity.SysLoginLog;
import org.company.nianglin.entity.SysUser;

/**
 * 为纯 Mockito 单测预热 MyBatis-Plus 的 lambda 列名缓存。
 *
 * <h3>不预热会怎样</h3>
 *
 * <p>{@code LambdaQueryWrapper} / {@code LambdaUpdateWrapper} 要把方法引用
 * （{@code CompanionOrder::getStatus}）翻译成数据库列名。翻译依赖 MyBatis-Plus 内部的
 * {@code TableInfo} 缓存，而这个缓存平时是 {@code MybatisPlusAutoConfiguration}
 * 在应用启动时建好的。纯单测不起 Spring 容器，缓存是空的，于是：</p>
 *
 * <ul>
 *   <li>{@code wrapper.set(Entity::getX, value)} 会直接抛
 *       {@code MybatisPlusException: can not find lambda cache for this entity}
 *       —— 注意 {@code set} 是<b>立即</b>求值的，不像 {@code eq} 那样惰性；</li>
 *   <li>任何断言 {@code getSqlSegment()} / {@code getSqlSet()} 的测试同样会炸。</li>
 * </ul>
 *
 * <h3>为什么这是"假绿"，必须单独修</h3>
 *
 * <p>单独跑 {@code mvn test -Dtest=ElderServiceTest} 会失败；跑全量测试却会通过 ——
 * 因为只要前面有一个 {@code @SpringBootTest} 类执行过，缓存就已经建好了。
 * 于是这些单测的成败取决于<b>测试执行顺序</b>：改一下 surefire 的 runOrder、
 * 或者在 IDEA 里只点一个类的运行按钮，就会莫名其妙地红一片。
 * 这种"靠运气通过"的测试比没有测试更危险，因为它会在排查别的问题时把人带偏。</p>
 *
 * <h3>为什么预热是安全的</h3>
 *
 * <p>本项目的实体全部由 {@code gen_entity.py} 从 DDL 生成，
 * 表名、主键、逻辑删除、乐观锁字段全部由注解显式声明
 * （{@code @TableName} / {@code @TableId} / {@code @TableLogic} / {@code @Version}），
 * 不依赖全局配置兜底，因此用默认配置预热得到的 {@code TableInfo}
 * 与 Spring 启动时构建的一致。</p>
 *
 * <p>⚠️ 新增实体后请在上面的 import 与 {@link #ENTITY_CLASSES} 里补一行 ——
 * 漏补只会让新实体的单测报上面那个异常，不会污染已有测试。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
public final class MybatisLambdaCache {

    /** 全部实体（与 {@code backend/sql/V1__init_schema.sql} 的 20 张表一一对应） */
    private static final Class<?>[] ENTITY_CLASSES = {
            SysUser.class, SysLoginLog.class, SysDict.class, SysFile.class, AdminOperLog.class,
            ElderProfile.class, FamilyElderRelation.class, CompanionProfile.class,
            CompanionAuditRecord.class,
            CompanionOrder.class, OrderStatusLog.class, OrderRejectLog.class,
            OrderCheckin.class, CompanionTrack.class,
            MedicineDict.class, MedicationPlan.class, MedicationTask.class,
            OrderReview.class, Complaint.class, InternalMessage.class,
    };

    /** 进程级只做一次，TableInfoHelper 本身就是全局缓存 */
    private static boolean warmed = false;

    private MybatisLambdaCache() {
    }

    /** 幂等：重复调用直接返回 */
    public static synchronized void warmUp() {
        if (warmed) {
            return;
        }
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entityClass : ENTITY_CLASSES) {
            TableInfoHelper.initTableInfo(assistant, entityClass);
        }
        warmed = true;
    }
}
