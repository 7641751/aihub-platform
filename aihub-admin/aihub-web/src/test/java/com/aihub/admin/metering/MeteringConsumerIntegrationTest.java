package com.aihub.admin.metering;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aihub.admin.support.AbstractIntegrationTest;
import com.aihub.common.meter.MeteringEvent;
import com.aihub.common.meter.MeteringEventCodec;
import com.aihub.common.meter.MeteringTopology;
import com.aihub.mq.meter.MeteringConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MQ → 落库的端到端链路，用的是真 RabbitMQ 与真 MySQL（Testcontainers）。
 * 幂等与死信这两条只能这样测：内存假实现测不到 broker 的 reject/DLX 行为与 MySQL 的唯一键。
 */
class MeteringConsumerIntegrationTest extends AbstractIntegrationTest {

    /** 幂等用例里**重复投递**的那个 id。判据用例与集成用例共用这一处定义，不许各写一份字面量。 */
    private static final String DUPLICATE_REQUEST_ID = "req-mq-idempotent";

    /**
     * 时序栅栏事件的 id —— 它**以重复 id 为前缀**（{@code req-mq-idempotent-fence}）。
     * 这个形状正是旧 {@code \b} 判据会误判的输入，因此这里显式命名而不是就地拼接。
     */
    private static final String FENCE_REQUEST_ID = DUPLICATE_REQUEST_ID + "-fence";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static MeteringEvent event(String requestId, long createdAtMillis) {
        return new MeteringEvent(requestId, 7L, null, null, "deepseek-chat",
                12, 34, 46, 1200, 250, MeteringEvent.STATUS_SUCCESS, null, createdAtMillis);
    }

    /**
     * 按**发布端真实的内容类型**投递：{@code RabbitTemplate} 的默认转换器打的是
     * {@code contentType=text/plain} 加一个**单独**的 {@code contentEncoding=UTF-8}，
     * 而网关发的是 {@link MeteringTopology#MESSAGE_CONTENT_TYPE}（{@code text/plain;charset=UTF-8}，
     * 见 {@code RabbitMeteringTransport.java:58}）。不在这里盖一刀，本用例验的就不是真实线格式。
     */
    private void send(String payload) {
        rabbitTemplate.convertAndSend(MeteringTopology.EXCHANGE, MeteringTopology.ROUTING_KEY, payload,
                message -> {
                    message.getMessageProperties().setContentType(MeteringTopology.MESSAGE_CONTENT_TYPE);
                    return message;
                });
    }

    private Integer rows(String requestId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from request_log where request_id = ?", Integer.class, requestId);
    }

    /** 轮询等结果：MQ 是异步的，固定 sleep 会既慢又不稳。 */
    private void awaitRows(String requestId, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (rows(requestId) >= expected) {
                return;
            }
            Thread.sleep(100);
        }
    }

    @Test
    void meteringEventOverTheQueueLandsInRequestLog() throws Exception {
        String requestId = "req-mq-1";
        long createdAtMillis = 1_800_000_000_123L;   // 2027-01-15T08:00:00.123Z
        send(MeteringEventCodec.encode(event(requestId, createdAtMillis)));

        awaitRows(requestId, 1, Duration.ofSeconds(10));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select tenant_id, model, prompt_tokens, completion_tokens, total_tokens,"
                        + " latency_ms, ttft_ms, status, created_at from request_log where request_id = ?",
                requestId);
        assertThat(row.get("tenant_id")).isEqualTo(7L);
        assertThat(row.get("model")).isEqualTo("deepseek-chat");
        assertThat(row.get("prompt_tokens")).isEqualTo(12);
        assertThat(row.get("completion_tokens")).isEqualTo(34);
        assertThat(row.get("total_tokens")).isEqualTo(46);
        assertThat(row.get("latency_ms")).isEqualTo(1200);
        assertThat(row.get("ttft_ms")).isEqualTo(250);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        // UTC 墙上时间必须等于事件里的 epoch 毫秒按 UTC 换算出的值（断言里不出现 JVM 默认时区），
        // 且必须取**事件**里的值 —— 消费端用 now() 会让这一行变成「消费时刻」，本断言立刻红。
        //
        // 期望值用 LocalDateTime 而不是 brief 里的 Timestamp：本项目的 mysql-connector-j 9.7.0
        // 在 queryForMap 里对 DATETIME(3) 返回 LocalDateTime（实测，见 task-8-report.md），
        // 用 Timestamp 断言是**类型**失配（值其实是对的），会让用例永远红。Task 8 已踩过同一个坑。
        LocalDateTime expectedCreatedAt =
                LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAtMillis), ZoneOffset.UTC);
        assertThat(expectedCreatedAt).isEqualTo(LocalDateTime.parse("2027-01-15T08:00:00.123"));
        assertThat(row.get("created_at")).isEqualTo(expectedCreatedAt);
    }

    /**
     * 至少一次投递 + 幂等消费：同一份载荷投两次，库里只能有一行。
     * 反证：消费端若用 {@code now()} 生成 created_at，本用例会看到两行（Task 8 已用服务级用例证明）。
     *
     * <p><b>为什么不能靠"栅栏行出现 ⇒ 重复消息已经处理完"这条推理</b>：它隐含"队列是单消费者 FIFO"这条
     * 假设。Testcontainers 的 broker 与队列是整个 JVM 共享的单例，而 Spring 的测试上下文缓存不关，
     * 于是只要本类之前存在**另一个**装着 {@code @RabbitListener} 的活上下文，队列上就有**两个**消费者：
     * 重复消息与栅栏消息可能被不同的消费者取走，"栅栏落库"就不再蕴含"重复消息的 INFO 已经打出来"。
     * 实测（只用既有类、surefire 反序）该假设会以同一句断言变红 —— 那是**顺序**问题，不是幂等坏了。
     *
     * <p>所以这里对"重复消息的 INFO"改成**有界轮询**：{@code MeteringConsumer} 的 logger 是全局的，
     * 挂在它上面的 appender 会捕获**任何**消费者实例打出的记录，所以"这条 INFO 真的出现了"仍然
     * 只能是「重复消息到达了消费者并在消费端被幂等丢弃」。轮询等的是异步观测，不是放宽断言：
     * 若重复消息**从未**被重新投递/消费，轮询到超时后断言照样红。
     */
    @Test
    void theSameEventTwiceInsertsExactlyOneRow() throws Exception {
        String requestId = DUPLICATE_REQUEST_ID;
        String payload = MeteringEventCodec.encode(event(requestId, 1_800_000_000_999L));

        send(payload);
        awaitRows(requestId, 1, Duration.ofSeconds(10));

        // 挂 appender 到消费者**自己的** logger 上（机制与摘除理由见
        // undecodablePayloadEndsUpInTheDeadLetterQueue / attachConsumerAppender）。
        // 窗口必须把**轮询与断言**一起包住：摘了 appender 再读，读到的是空列表而不是"记录没出现"。
        ListAppender<ILoggingEvent> appender = attachConsumerAppender();
        String fenceRequestId = FENCE_REQUEST_ID;
        List<ILoggingEvent> duplicateConsumed;
        try {
            send(payload);   // 重复投递

            // 时序栅栏：重复投递之后再投一条**不同**的事件，并等它真的落库。
            // 只断言 count==1 是不够的 ——「第二条根本没投出去 / 消费链路已经停了」同样给出 count==1 的绿色，
            // 那样的绿色什么也没证明。栅栏消息能落库，证明重复投递之后链路仍在投递且仍在消费。
            send(MeteringEventCodec.encode(event(fenceRequestId, 1_800_000_001_777L)));
            awaitRows(fenceRequestId, 1, Duration.ofSeconds(10));

            // 栅栏只证明**链路还活着**，证明不了**重复的那条消息本身到达过消费者**：「重复被消费并
            // 幂等丢弃」与「重复压根没投出去（路由错 / 断链）」在 count==1 上是同一个观测值。
            // 消费者对**首次**投递走 inserted=true 的 DEBUG 分支，只有 sink 报告「已落库」时才打这条 INFO，
            // 因此该 INFO 记录的存在就是「重复消息到达了消费者、并在消费端被幂等丢弃」的直接证据
            // —— 但**仅当** request_id 过滤真的把栅栏 id 排除在外时才成立：栅栏消息自己也会打
            // 一模一样的 INFO 行（它的 request_id 只比重复 id 多一个 `-fence` 后缀）。
            // 那个前提由 mentionsRequestId 的 `(?![\w-])` 收尾边界提供，并由
            // anIdThatExtendsTheDuplicateIdIsNotItsRecord 钉住；边界一坏，本用例对
            // 「重复消息从未被投递」就是**假绿**（见 m4-task-6-fix-report.md 的变异实验）。
            // 有界轮询（而不是假设它此刻已经打完）：见方法注释。
            duplicateConsumed = awaitInfoRecordsMentioning(appender, requestId, Duration.ofSeconds(10));
        }
        finally {
            detachConsumerAppender(appender);
        }

        assertThat(rows(fenceRequestId))
                .as("时序栅栏：栅栏事件必须真的落库，否则说明这次绿色是「消息根本没被消费」")
                .isEqualTo(1);

        assertThat(duplicateConsumed)
                .as("重复投递的消息必须真的到达消费者并在消费端被幂等丢弃。这条 INFO 只可能来自重复消费"
                        + "—— 前提是 request_id 过滤把「以重复 id 为前缀的另一个 id」（栅栏 id）排除在外，"
                        + "该前提由 mentionsRequestId 的 `(?![\\w-])` 边界提供并被 "
                        + "anIdThatExtendsTheDuplicateIdIsNotItsRecord 钉住；收尾边界一旦退化成 `\\b`，"
                        + "栅栏自己的 INFO 就会满足这里，本断言对「重复消息从未被投递」变成假绿。"
                        + "10 秒内没等到就说明重复消息没有被重新投递/消费，绿色无从谈起")
                .isNotEmpty();

        assertThat(rows(requestId)).as("同一载荷投两次，只能有一行").isEqualTo(1);
    }

    /**
     * 垃圾载荷**不许被静默 ACK**：它必须重试 3 次然后进死信队列，等人排查。
     * 反证：把消费者改成「解码失败就 log + return」→ 本用例拿不到死信消息。
     */
    @Test
    void undecodablePayloadEndsUpInTheDeadLetterQueue() throws Exception {
        drainDeadLetterQueue();
        String garbage = "this-is-not-a-metering-event";

        // 挂一个 appender 到消费者**自己的** logger 上：重试循环每跑一次 onMessage 就打一条 ERROR，
        // 于是「恰好 3 次尝试」成了可断言的事实。日志里那句 WARN「Retries exhausted」来自
        // RejectAndDontRequeueRecoverer，是**另一个** logger，不会混进来。
        ListAppender<ILoggingEvent> appender = attachConsumerAppender();

        Message dead = null;
        try {
            send(garbage);
            dead = receiveFromDeadLetterQueue(garbage, Duration.ofSeconds(15));
        }
        finally {
            // 必须摘掉：appender 挂在全局 logger 上，留下会污染其它用例并一直攒事件。
            detachConsumerAppender(appender);
        }

        assertThat(dead).as("解不开的载荷必须进死信队列，而不是被静默 ACK").isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(garbage);

        // 仅「DLQ 里有一条消息」还不够：要证明它是被 **broker 死信**过来的。x-death 由 DLX 转发时写入——
        // reason=rejected 钉住「拒绝且不 requeue」，queue 钉住它先经过了业务队列（而不是被谁直接投进 DLQ）。
        // reject 的来源是**重试耗尽**：RejectAndDontRequeueRecoverer 抛 AmqpRejectAndDontRequeueException，
        // 与 default-requeue-rejected 无关（见 MeteringTopologyConfig.java:19-25；实测把那个属性翻成
        // true，本用例依然全绿，见 task-9-report.md §4.2）。
        // 反证：摘掉业务队列的 x-dead-letter-*，消息 reject 后直接被丢掉，本用例立刻红。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> xDeath =
                (List<Map<String, Object>>) dead.getMessageProperties().getHeaders().get("x-death");
        assertThat(xDeath).as("死信消息必须带 broker 写入的 x-death 头").isNotNull().isNotEmpty();
        assertThat(xDeath.get(0)).containsEntry("reason", "rejected");
        assertThat(xDeath.get(0)).containsEntry("queue", MeteringTopology.QUEUE);

        // 重试次数必须被钉住：3 次尝试 = 消费者打 3 条 ERROR。max-attempts 改成 1 或 5 都会让这里红
        // （1 条 / 5 条）—— 而原先「15 秒内拿到死信消息」的写法对 1 和 5 都照样绿。
        List<ILoggingEvent> attempts = errorRecords(appender);
        assertThat(attempts).as("重试循环每条 ERROR 对应一次尝试，必须恰好 3 条").hasSize(3);
        assertThat(attempts.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .allSatisfy(message -> assertThat(message).contains(garbage));
    }

    /**
     * 把 appender 挂到消费者**自己的** logger 上并返回它；用完必须 {@link #detachConsumerAppender}。
     *
     * <p>{@code appender.list} 默认是普通 {@code ArrayList}（写发生在消费者线程、读发生在测试线程），
     * 换成同步包装以便读侧在同一个对象上加锁（见 {@link #records}）。
     */
    private static ListAppender<ILoggingEvent> attachConsumerAppender() {
        Logger consumerLogger = (Logger) LoggerFactory.getLogger(MeteringConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.list = Collections.synchronizedList(new ArrayList<>());
        appender.start();
        consumerLogger.addAppender(appender);
        return appender;
    }

    /** 摘掉 appender：它挂在全局 logger 上，留下会污染其它用例并一直攒事件。 */
    private static void detachConsumerAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(MeteringConsumer.class)).detachAppender(appender);
        appender.stop();
    }

    /**
     * 读 appender 里**指定级别**的记录。名字叫 {@code errorRecords} 就必须真的只数 ERROR：
     * 早先的实现不过滤级别，{@code hasSize(3)} 数的是窗口内该 logger 上的**全部**事件 ——
     * 今天恰好安全只是因为那里没人打别的级别，而本类的幂等用例恰恰要断言一条 **INFO** 幂等记录；
     * 一旦将来（并发、重投递、测试顺序变化）它落进重试用例的窗口，重试次数断言就会因**无关原因**变红。
     *
     * <p>加锁的理由：logback 的 {@code ListAppender.list} 本身不是线程安全的，
     * 而 {@code appender.list} 已换成 {@code synchronizedList} 包装，读写锁在同一个对象上。
     */
    private static List<ILoggingEvent> records(ListAppender<ILoggingEvent> appender, Level level) {
        synchronized (appender.list) {
            List<ILoggingEvent> matching = new ArrayList<>();
            for (ILoggingEvent event : appender.list) {
                if (event.getLevel() == level) {
                    matching.add(event);
                }
            }
            return matching;
        }
    }

    /** 消费者重试循环每跑一次 {@code onMessage} 打一条 ERROR，因此 ERROR 条数 = 尝试次数。 */
    private static List<ILoggingEvent> errorRecords(ListAppender<ILoggingEvent> appender) {
        return records(appender, Level.ERROR);
    }

    /**
     * 形如 {@code request_id=<id>} 的日志片段必须按**整词**匹配，不能用 {@code contains}：
     * 本用例的栅栏事件 id（{@code req-mq-idempotent-fence}）是重复事件 id 的**前缀超串**，
     * {@code contains} 会把栅栏的日志也算成重复消息的日志。
     *
     * <p><b>为什么"整词"不能用 {@code \b} 表达</b>：{@code \b} 只要求"词字符与非词字符的交界"。
     * 栅栏 id 里 {@code idempotent} 的 {@code t} 与随后的 {@code -} 之间**恰好**就有一个这样的交界，
     * 于是 {@code request_id=req-mq-idempotent-fence} 会被旧判据
     * （{@code request_id=} + {@code \Qreq-mq-idempotent\E} + {@code \b}）判成**重复 id 的记录**。
     * 收尾边界必须改成 {@code (?![\w-])}：重复 id 之后**不允许**再跟词字符或连字符，
     * "另一个以重复 id 开头的 id"因此一律不算数。
     *
     * <p>这条负向先行断言是承重的：{@code theSameEventTwiceInsertsExactlyOneRow} 里
     * "那条 INFO 只可能来自重复消费"的陈述，正是**依赖**它把栅栏 id 排除在外才成立
     * （见 {@link #anIdThatExtendsTheDuplicateIdIsNotItsRecord}）。
     *
     * <p>判据只吃一个纯字符串，所以它可以脱离容器与 broker 被直接钉住 —— 下面两组
     * {@code @ParameterizedTest} 就是干这个的；本类虽然继承集成基类（会起容器），
     * 那两组用例本身既不发消息也不碰数据库。
     */
    static boolean mentionsRequestId(String message, String requestId) {
        return Pattern.compile("request_id=" + Pattern.quote(requestId) + "(?![\\w-])")
                .matcher(message)
                .find();
    }

    /** 日志记录的判据：只把 {@link ILoggingEvent} 的格式化消息取出来，交给纯字符串版本。 */
    private static boolean mentionsRequestId(ILoggingEvent record, String requestId) {
        return mentionsRequestId(record.getFormattedMessage(), requestId);
    }

    /**
     * 反向：**以重复 id 为前缀的另一个 id** 的记录，不能算成重复 id 的记录。
     * 首条就是真实世界里唯一会出现的形状（栅栏消息自己那条 INFO 行），其余是同一类边界的变体。
     */
    @ParameterizedTest(name = "以 " + DUPLICATE_REQUEST_ID + " 为前缀的 id 不算它自己的记录：[{0}]")
    @ValueSource(strings = {
            // 真实形状：栅栏消息被当成"重复"时，MeteringConsumer 打出来的就是这一行
            "计量事件重复消费，已幂等丢弃 request_id=req-mq-idempotent-fence created_at=2027-01-15T08:00:01.777Z",
            "request_id=req-mq-idempotent-fence",
            // 尾随数字：`\b` 也拦得住它，但换成 startsWith / 删掉收尾边界就会漏
            "request_id=req-mq-idempotent2",
    })
    void anIdThatExtendsTheDuplicateIdIsNotItsRecord(String message) {
        assertThat(mentionsRequestId(message, DUPLICATE_REQUEST_ID))
                .as("以重复 id 为前缀的另一个 id 不是重复 id：[%s]", message)
                .isFalse();
    }

    /**
     * 正向：重复 id 自己的记录必须被认出来，包括**恰好以 id 收尾**（后面什么都没有）这一种 ——
     * 收尾边界若写坏成"什么都不匹配"，这条会红。
     */
    @ParameterizedTest(name = DUPLICATE_REQUEST_ID + " 自己的记录必须被认出：[{0}]")
    @ValueSource(strings = {
            // 真实形状：重复消息被幂等丢弃时打的那一行（MeteringConsumer.java:79）
            "计量事件重复消费，已幂等丢弃 request_id=req-mq-idempotent created_at=2027-01-15T08:00:00.999Z",
            // 恰好以 id 收尾：后面就是字符串终点
            "request_id=req-mq-idempotent",
            // 以 id 收尾、后面跟一个空格再接别的内容
            "request_id=req-mq-idempotent created_at=2027-01-15T08:00:00.999Z",
    })
    void theDuplicateIdsOwnRecordIsRecognised(String message) {
        assertThat(mentionsRequestId(message, DUPLICATE_REQUEST_ID))
                .as("重复 id 自己的记录必须被认出：[%s]", message)
                .isTrue();
    }

    /**
     * 消费者自己打的 INFO 记录只有「重复消费、已幂等丢弃」这一条，故按 request_id 过滤即可定位它。
     *
     * <p>要点在 {@code request_id=} 之后的**收尾边界**：栅栏事件也会打同一条 INFO，只有它的
     * request_id 不同（{@code req-mq-idempotent-fence}）。见
     * {@link #mentionsRequestId(String, String)}。
     */
    private static List<ILoggingEvent> infoRecordsMentioning(ListAppender<ILoggingEvent> appender,
                                                             String requestId) {
        return records(appender, Level.INFO).stream()
                .filter(record -> mentionsRequestId(record, requestId))
                .toList();
    }

    /**
     * 有界轮询等那条 INFO 出现（MQ 是异步的，而且队列上可能不止一个消费者 —— 见
     * {@code theSameEventTwiceInsertsExactlyOneRow} 的方法注释）。
     *
     * <p>轮询的依据不是"睡够时间"，而是"出现即可返回"：{@code MeteringConsumer} 用的是全局 logger，
     * appender 会捕获**任何**消费者实例打出的记录，所以"等到了"这条事实仍然只可能来自
     * 「重复消息真的被消费过」—— <b>前提是 {@link #mentionsRequestId(String, String)} 的
     * {@code (?![\w-])} 收尾边界真的把「以重复 id 为前缀的另一个 id」排除掉了</b>。
     * 少了这个前提，「等到了」也可能只是**栅栏自己的** INFO 行：栅栏消息同样会被消费、同样可能
     * （在消费端分支坏掉时）打出这条 INFO，而它的 request_id 以重复 id 为前缀。
     * 收尾边界的两个方向由 {@link #anIdThatExtendsTheDuplicateIdIsNotItsRecord} 与
     * {@link #theDuplicateIdsOwnRecordIsRecognised} 钉住。
     * 找不到就返回空列表，由调用方断言红 —— 不允许把超时当成功。
     */
    private static List<ILoggingEvent> awaitInfoRecordsMentioning(ListAppender<ILoggingEvent> appender,
                                                                  String requestId, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<ILoggingEvent> found = List.of();
        while (System.nanoTime() < deadline) {
            found = infoRecordsMentioning(appender, requestId);
            if (!found.isEmpty()) {
                return found;
            }
            Thread.sleep(100);
        }
        return found;
    }

    private void drainDeadLetterQueue() {
        while (rabbitTemplate.receive(MeteringTopology.DEAD_LETTER_QUEUE, 200) != null) {
            // 清空历史遗留，避免误判
        }
    }

    /**
     * 轮询 DLQ，直到**载荷等于期望值**的消息出现再返回它（不是取收到的第一条）。
     * DLQ 在基类的单例容器里，被整个 JVM 的测试类共享：别的测试类若在中间死信一条消息，
     * 「取第一条」就会变成一次假红。不是我们的消息一律丢弃（与 {@link #drainDeadLetterQueue()} 一致）。
     */
    private Message receiveFromDeadLetterQueue(String expectedBody, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Message message = rabbitTemplate.receive(MeteringTopology.DEAD_LETTER_QUEUE, 500);
            if (message != null
                    && expectedBody.equals(new String(message.getBody(), StandardCharsets.UTF_8))) {
                return message;
            }
        }
        return null;
    }
}
