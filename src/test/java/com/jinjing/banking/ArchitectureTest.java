package com.jinjing.banking;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.Optional;
import java.util.function.Predicate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private final JavaClasses importedClasses = new ClassFileImporter().importPackages("com.jinjing.banking");

    @Test
    void controllersShouldNotDependOnRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..");
        rule.check(importedClasses);
    }

    @Test
    void servicesShouldOnlyBeAccessedByControllersOrOtherServices() {
        ArchRule rule = classes()
                .that().resideInAPackage("..service..")
                .should().onlyBeAccessed().byClassesThat().resideInAnyPackage(
                    "..controller..", "..service..", "..config..", "..dlq..", "..analytics.."
                );
        rule.check(importedClasses);
    }

    // ------------------------------------------------------------ Kafka 监听器约束

    private static final String KAFKA_LISTENER = KafkaListener.class.getName();

    /**
     * 每个 {@code @KafkaListener} 都必须显式写出 containerFactory。
     *
     * <p>为什么值得用测试焊死：错误处理器（它决定了死信主题与 {@code x-origin-consumer} 来源标识）
     * 是挂在<b>容器工厂</b>上的，而容器工厂由监听器注解选择。漏写 containerFactory 时，Spring 会
     * <b>静默</b>回退到默认工厂 {@code kafkaListenerContainerFactory} —— 于是审计消费者的失败会被
     * 盖上 {@code banking-group} 的章、并投进账本死信主题：主题隔离与来源守卫<b>同时失效</b>。
     * 历史上正是这个机制把 6 条已 COMPLETED 的转账改写成了 FAILED。
     *
     * <p>漏写既不会编译报错也不会启动报错，所以只能靠架构测试兜住。
     */
    @Test
    void kafkaListenersShouldDeclareContainerFactoryExplicitly() {
        ArchRule rule = methods()
                .that().areAnnotatedWith(KafkaListener.class)
                .should(kafkaListenerCondition(
                        "显式声明 containerFactory（不允许依赖默认容器工厂）",
                        method -> stringProperty(method, "containerFactory") == null,
                        "漏写 containerFactory：死信主题与来源 header 会落到默认工厂上，"
                                + "审计失败可能被当成账本失败处理"));
        rule.check(importedClasses);
    }

    /**
     * 不允许用 {@code topicPattern} 通配订阅。
     *
     * <p>死信链路的隔离建立在「监听器只订阅一个确定的主题」之上：{@code DlqListener} 只订阅
     * {@code banking-transfers.DLT}，所以审计死信在 broker 层面就送不到它面前。一旦有人改成
     * {@code topicPattern = "banking-transfers.*"}，隔离会立刻且静默地失效。
     */
    @Test
    void kafkaListenersShouldNotUseTopicPattern() {
        ArchRule rule = methods()
                .that().areAnnotatedWith(KafkaListener.class)
                .should(kafkaListenerCondition(
                        "不使用 topicPattern 通配订阅（会破坏死信主题隔离）",
                        method -> stringProperty(method, "topicPattern") != null,
                        "使用了 topicPattern 通配订阅：死信主题隔离会静默失效"));
        rule.check(importedClasses);
    }

    private static ArchCondition<JavaMethod> kafkaListenerCondition(String description,
                                                                   Predicate<JavaMethod> violates,
                                                                   String violationMessage) {
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (violates.test(method)) {
                    events.add(SimpleConditionEvent.violated(method,
                            violationMessage + " → " + method.getFullName()));
                }
            }
        };
    }

    /** 取方法上的 @KafkaListener：ArchUnit 直接读字节码，不启动 Spring 上下文。 */
    private static Optional<JavaAnnotation<JavaMethod>> kafkaListenerOn(JavaMethod method) {
        return method.getAnnotations().stream()
                .filter(annotation -> annotation.getRawType().getName().equals(KAFKA_LISTENER))
                .findFirst();
    }

    /** 取注解上的字符串属性；未声明或声明为空串都返回 null。 */
    private static String stringProperty(JavaMethod method, String name) {
        return kafkaListenerOn(method)
                .flatMap(annotation -> annotation.get(name))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }
}
