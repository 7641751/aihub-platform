package com.aihub.common.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道密钥的 AES-GCM 加解密是**跨服务**能力：admin 加密（写入 {@code channel.api_key_cipher}），
 * gateway 解密（本地注入上游密钥）。两侧共用本文件里的实现，因此「密文格式」「主密钥解析规则」
 * 「版本自描述」这三件事都压在这里。
 *
 * <p>测试用的明文一律是**一眼可辨的合成值**（{@code sk-channel-plaintext-synthetic}）：
 * 真实渠道密钥不允许出现在任何 tracked 文件里，包括测试夹具。
 *
 * <p>本类不需要 Spring 上下文，也不需要 Docker / Redis。
 */
class ChannelKeyCipherTest {

    private static final String PLAINTEXT = "sk-channel-plaintext-synthetic";

    /**
     * 固定向量 1（评审 Fix 1；二轮 Fix 1 把它**做不到**的事写清楚）：由提交 {@code f64cf27} 的实现加密
     * 一次生成后**粘贴为字面量**，不是被测代码现算的。主密钥是 {@link #b64Key(int) b64Key(2)}，
     * 明文即 {@link #PLAINTEXT}，body 58 字节（12 nonce + 30 明文 + 16 tag）→ 80 字符、以 {@code ==} 结尾。
     *
     * <p>为什么必须有它：本类其余用例的期望值全部由被测实现推导，把 nonce 挪到密文后面、或把 tag 从
     * 128 位改成别的位数，那些用例会**全绿**，而数据库里已经躺着的 {@code channel.api_key_cipher} 行
     * 会永久解不开（admin 写入的密文 gateway 读不出来）。这条字面量把「今天的格式」冻住。
     *
     * <p><b>它单独挡不住字母表漂移（二轮评审实测）</b>：本向量的 body **既不含 {@code +} 也不含
     * {@code /}**，而这两套字母表在只含 {A–Z a–z 0–9} 的 body 上完全一致；把生产代码的
     * {@code getEncoder()} / {@code getDecoder()} 换成 {@code getUrlEncoder()} / {@code getUrlDecoder()}，
     * **只靠这一条向量会全绿**，但 body 含 {@code +} 或 {@code /} 的存量行会永久失解。
     * 字母表那一半由 {@link #PLUS_AND_SLASH_PAYLOAD} 负责 —— 两条向量合起来才是完整的格式冻结点。
     */
    private static final String FIXED_PAYLOAD =
            "v2:DFHTeYKoS0LymUfJRLwmRhPFfua62zGa4USHz54SP6XCgxrD8zO50YS69atCxaYxEo5w79CyGZb1GA==";

    /**
     * 固定向量 2（二轮评审 Fix 1）：与 {@link #FIXED_PAYLOAD} 同源（{@code b64Key(2)} + {@link #PLAINTEXT}），
     * 但它的 body **同时含 {@code +} 与 {@code /}** —— 标准 base64 字母表里那两个非 URL-safe 字符。
     *
     * <p>为什么必须有它：这是**唯一**能发现「标准 base64 换成 URL-safe base64」的用例。实测
     * {@code getUrlDecoder()} 对含 {@code +} 或 {@code /} 的 body 抛 {@code IllegalArgumentException}
     * （{@code decrypt} 把它折算成空），于是**存量行中 body 含这两个字符的那些会永久不可解**；而
     * {@link #FIXED_PAYLOAD} 的 body 落在两套字母表的交集里，同一种漂移下它照样绿。
     *
     * <p>来源：仓库树之外的**离线 scratch 程序**（与生产同布局的 AES/GCM/NoPadding、12 字节 nonce、
     * 128 位 tag）搜索得到后粘贴为字面量 —— nonce 十六进制 {@code e187c6498d5051b6831cbe0b}，
     * 第 4 个候选命中，未落进仓库、未进提交。
     */
    private static final String PLUS_AND_SLASH_PAYLOAD =
            "v2:4YfGSY1QUbaDHL4LPRBYpugHhjy/IjNVWO635z2/scNLl0n5XE06Y0JI5Ww1GU6Sv9YoS5+0HlvIsw==";

    /** 32 字节（AES-256）的合成主密钥。 */
    private static String b64Key(int seed) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 拼出环境变量形态的主密钥表。 */
    private static String envOf(int... versions) {
        StringBuilder out = new StringBuilder();
        for (int version : versions) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append('v').append(version).append(':').append(b64Key(version));
        }
        return out.toString();
    }

    private static ChannelKeyRegistry registry(int... versions) {
        return ChannelKeyRegistry.parse(envOf(versions));
    }

    @Test
    void roundTripsAPlaintextChannelKey() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        for (String plaintext : List.of(PLAINTEXT, "", "中文渠道密钥", "x".repeat(4096))) {
            String payload = cipher.encrypt(plaintext);
            assertThat(cipher.decrypt(payload)).contains(plaintext);
        }
    }

    /**
     * AES-GCM 下**复用 nonce 是致命缺陷**（同一密钥 + 同一 nonce 会泄露明文异或并摧毁认证性）。
     * 这条用例是它的防线：同一明文加密两次必须得到两个不同的密文，且都能解回来。
     */
    @Test
    void everyEncryptionUsesAFreshNonce() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        String first = cipher.encrypt(PLAINTEXT);
        String second = cipher.encrypt(PLAINTEXT);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).contains(PLAINTEXT);
        assertThat(cipher.decrypt(second)).contains(PLAINTEXT);
    }

    @Test
    void payloadIsSelfDescribingWithTheVersionLabel() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1, 2));

        String payload = cipher.encrypt(PLAINTEXT);

        // 环境变量里最大的版本号就是 currentVersion。
        assertThat(payload).startsWith("v2:");
        assertThat(AesGcmChannelCipher.labelOf(payload)).isEqualTo("v2");
        assertThat(cipher.canDecrypt(payload)).isTrue();
    }

    /**
     * 评审 Fix 1 + 二轮 Fix 1：用**字面量**钉住载荷格式，而不是用实现现算期望值。三件事各钉一处：
     * ① 硬编码密文能解回硬编码明文（nonce 在前、tag 在后、填充形态）；
     * ② 版本标签是载荷的一部分；
     * ③ 解码后长度 = 12 (nonce) + 明文长度 + 16 (GCM tag)，把 nonce 与 tag 的字节数**独立于实现**钉死。
     *
     * <p>两条向量的分工：本条（body 不含 {@code +}/{@code /}）钉布局、长度与版本标签；
     * {@link #pinnedVectorWithPlusAndSlashPinsTheStandardBase64Alphabet}（body 同时含这两个字符）
     * 钉**标准** base64 字母表。缺任意一条，就有一种漂移能全绿通过。
     *
     * <p><b>本类不覆盖的方向（不要当成已冻结）</b>：把 {@code +}/{@code /} 映射到同样字符的自定义
     * 字母表漂移（例如只重排 A–Z 的表）；填充字符的省略与否 —— 两条向量只走**解码**方向，而 JDK 解码器
     * 容忍缺失 padding，所以向量自身对「编码器是否补 {@code =}」不设防。
     */
    @Test
    void pinnedPayloadVectorFreezesTheCiphertextFormat() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1, 2));

        assertThat(cipher.decrypt(FIXED_PAYLOAD))
                .as("固定向量必须仍能解回硬编码明文（格式漂移会让存量密文不可解）")
                .contains(PLAINTEXT);
        assertThat(FIXED_PAYLOAD).as("版本标签必须仍在载荷里").startsWith("v2:");
        assertThat(AesGcmChannelCipher.labelOf(FIXED_PAYLOAD)).isEqualTo("v2");
        assertThat(Base64.getDecoder().decode(FIXED_PAYLOAD.substring(FIXED_PAYLOAD.indexOf(':') + 1)))
                .as("body = 12 字节 nonce + 明文 + 16 字节 GCM tag")
                .hasSize(12 + PLAINTEXT.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    /**
     * 二轮评审 Fix 1：把**标准** base64 字母表也钉死，而不只是钉住「某一套 base64」。
     *
     * <p>反证（实测，见报告 §9）：把 {@code AesGcmChannelCipher} 的 {@code getEncoder()} /
     * {@code getDecoder()} 临时换成 {@code getUrlEncoder()} / {@code getUrlDecoder()}，本用例**变红**
     * （{@code decrypt} 返回空 {@link Optional}），而 {@link #pinnedPayloadVectorFreezesTheCiphertextFormat}
     * 仍**是绿的** —— 那正是上一轮冻结留下的缺口。最后一条断言是「防止向量被换成无害 body」的哨兵：
     * 不含 {@code +}/{@code /} 的 body 在这两套字母表下取值相同，会让本用例失去鉴别力。
     */
    @Test
    void pinnedVectorWithPlusAndSlashPinsTheStandardBase64Alphabet() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1, 2));

        assertThat(PLUS_AND_SLASH_PAYLOAD)
                .as("本向量必须同时含 + 与 /，否则覆盖不到标准字母表")
                .contains("+").contains("/");
        assertThat(cipher.decrypt(PLUS_AND_SLASH_PAYLOAD))
                .as("固定向量必须仍能解回硬编码明文（字母表漂移会让存量密文不可解）")
                .contains(PLAINTEXT);
        assertThat(PLUS_AND_SLASH_PAYLOAD).as("版本标签必须仍在载荷里").startsWith("v2:");
        assertThat(AesGcmChannelCipher.labelOf(PLUS_AND_SLASH_PAYLOAD)).isEqualTo("v2");
        assertThat(Base64.getDecoder()
                .decode(PLUS_AND_SLASH_PAYLOAD.substring(PLUS_AND_SLASH_PAYLOAD.indexOf(':') + 1)))
                .as("body = 12 字节 nonce + 明文 + 16 字节 GCM tag")
                .hasSize(12 + PLAINTEXT.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    /**
     * 评审 Fix 2：**真的翻转密文区的字节**。旧写法
     * {@code payload.substring(0, len - 2) + "A"} 只是砍掉两个 base64 填充字符再补一个 'A'：
     * 实测原 body 58 字节 → 篡改后 59 字节，且**原 58 字节一个都没变**（byte diff = 0）。
     * 那不是「篡改任一字节」，只能证明「多一个字节的载荷解不开」；不认证的实现只要对长度敏感
     * （例如 CBC 系）就能照样绿。这里改成：解码 body、翻转 nonce 之后的第一个密文字节、按**等长**
     * 重新编码 —— 长度不变，唯一的变化就是那一个字节，只有 GCM tag 校验能拦下它。
     */
    @Test
    void tamperedCiphertextFailsToDecrypt() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));
        String payload = cipher.encrypt(PLAINTEXT);
        int colon = payload.indexOf(':');

        byte[] body = Base64.getDecoder().decode(payload.substring(colon + 1));
        body[12] ^= 0x01; // 索引 12 = nonce 之后的第一个密文字节
        String tampered = payload.substring(0, colon + 1) + Base64.getEncoder().encodeToString(body);

        assertThat(tampered).as("必须与原件等长：不能靠改长度让解密失败").hasSameSizeAs(payload);
        assertThat(Base64.getDecoder().decode(tampered.substring(colon + 1)))
                .as("篡改后 body 与原件等长，翻转的是字节而不是长度")
                .hasSameSizeAs(body);
        assertThat(cipher.decrypt(tampered)).as("密文区任一字节被翻转必须被 tag 拒绝").isEmpty();
    }

    @Test
    void tamperedNonceFailsToDecrypt() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));
        String payload = cipher.encrypt(PLAINTEXT);
        int colon = payload.indexOf(':');
        byte[] nonceAndBody = Base64.getDecoder().decode(payload.substring(colon + 1));
        nonceAndBody[0] ^= 0x01;

        String tampered = payload.substring(0, colon + 1) + Base64.getEncoder().encodeToString(nonceAndBody);

        assertThat(cipher.decrypt(tampered)).isEmpty();
    }

    /**
     * 评审 Fix 7：body 比 nonce（12）长、比 tag（16）短时，局部长度检查（{@code <= 12}）会放行，
     * 真正兜底的是 GCM 的 {@code doFinal} 抛 {@code AEADBadTagException} → 包装异常被
     * {@code decrypt} 折算成空。评审用 fuzz 验证过安全，但此前没有任何用例钉住它。
     */
    @Test
    void bodyLongerThanTheNonceButShorterThanTheTagReturnsEmpty() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        for (int length = 13; length <= 27; length++) {
            byte[] body = new byte[length];
            for (int i = 0; i < length; i++) {
                body[i] = (byte) (i + 1);
            }
            String payload = "v1:" + Base64.getEncoder().encodeToString(body);

            assertThat(cipher.decrypt(payload))
                    .as("body %s 字节（>nonce 且 <tag）必须解成空而不是抛异常", length)
                    .isEmpty();
            assertThat(cipher.canDecrypt(payload)).isFalse();
        }
    }

    /**
     * 轮换的完整含义：**旧密文仍然解得开**（旧版本还在表里），而**新加密一定用新版本**。
     * 只有「解密时先查 DB 的 key_version 再选密钥」的实现才会在这条上红。
     */
    @Test
    void rotationDecryptsTheOldVersionAndReencryptsToTheNewOne() {
        String oldPayload = new AesGcmChannelCipher(registry(1)).encrypt(PLAINTEXT);

        AesGcmChannelCipher rotated = new AesGcmChannelCipher(registry(1, 2));

        assertThat(rotated.decrypt(oldPayload)).contains(PLAINTEXT);
        String newPayload = rotated.encrypt(PLAINTEXT);
        assertThat(newPayload).startsWith("v2:");
        assertThat(rotated.decrypt(newPayload)).contains(PLAINTEXT);
    }

    @Test
    void retiringTheOldKeyMakesOldCiphertextUndecryptable() {
        String oldPayload = new AesGcmChannelCipher(registry(1)).encrypt(PLAINTEXT);
        AesGcmChannelCipher retired = new AesGcmChannelCipher(registry(2));

        assertThat(retired.decrypt(oldPayload)).isEmpty();
        assertThat(retired.canDecrypt(oldPayload)).isFalse();
    }

    @Test
    void unknownVersionLabelReturnsEmptyInsteadOfThrowing() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        assertThat(cipher.decrypt("v9:" + Base64.getEncoder().encodeToString(new byte[40]))).isEmpty();
    }

    @Test
    void malformedPayloadsReturnEmpty() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));

        for (String payload : new String[]{null, "", "v1", "v1:", "nonsense", "v1:!!!not-base64!!!", "v1:AAAA"}) {
            assertThat(cipher.decrypt(payload)).as("payload %s 必须解成空而不是抛异常", payload).isEmpty();
        }
        assertThat(AesGcmChannelCipher.labelOf("nonsense")).isNull();
        assertThat(AesGcmChannelCipher.labelOf(null)).isNull();
    }

    /**
     * Optional nit 的结论：载荷标签对 {@code [vV]} 的宽容**保留**（运维手写或旧数据不该被大小写坑到），
     * 而 {@code labelOf} 是**规范化**成小写而不是原样回显。于是「V1:」载荷能解开、且 labelOf 报
     * {@code "v1"} —— 两者一致：能解的载荷一定有非空 label，label 一定是小写规范形。
     */
    @Test
    void uppercaseVersionLabelIsAcceptedAndReportedInCanonicalLowerCase() {
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(registry(1));
        String uppercased = "V" + cipher.encrypt(PLAINTEXT).substring(1);

        assertThat(AesGcmChannelCipher.labelOf(uppercased)).isEqualTo("v1");
        assertThat(cipher.decrypt(uppercased)).contains(PLAINTEXT);
    }

    @Test
    void registryParsingAcceptsWhitespaceAndRejectsGarbage() {
        ChannelKeyRegistry parsed = ChannelKeyRegistry.parse(
                " v1:" + b64Key(1) + " , 不是版本 , v2:" + b64Key(2) + ",v3:short ");

        assertThat(parsed.has(1)).isTrue();
        assertThat(parsed.has(2)).isTrue();
        assertThat(parsed.has(3)).as("不足 32 字节的版本必须被跳过").isFalse();
        assertThat(parsed.currentVersion()).as("当前版本 = 解析成功的最大版本号").isEqualTo(2);
        assertThat(parsed.describe()).as("describe 只含版本号，绝不含密钥内容").contains("1").contains("2");
    }

    @Test
    void registryRequiresExactly32ByteKeys() {
        ChannelKeyRegistry parsed = ChannelKeyRegistry.parse(
                "v1:" + Base64.getEncoder().encodeToString(new byte[16])
                        + ",v2:" + Base64.getEncoder().encodeToString(new byte[31])
                        + ",v3:" + Base64.getEncoder().encodeToString(new byte[33]));

        assertThat(parsed.isEmpty()).isTrue();
    }

    /**
     * 评审 Fix 3：不变量必须由**紧凑构造器**自己守住，而不是只靠 {@code parse} 过滤。旧实现里
     * {@code new ChannelKeyRegistry(Map.of(1, new byte[16]))} 构造成功，而且 16 字节会被 JDK 当成
     * AES-128 **静默接受**（实测 encrypt 返回了一个合法的 16 字节密钥密文）；31 字节则在 encrypt 时
     * 抛出与篡改无法区分的 "AES-GCM 运算失败"，decrypt 静默返回空。两者都是坏数据/坏诊断。
     *
     * <p>二轮 Fix 2：{@link ChannelKeyRegistry#MAX_VERSIONS} 的上限同样由构造器守住，而此前只有
     * {@link #registryRefusesMoreVersionsThanTheCap} 走的是 {@code parse}。这里补一条「9 个**合法**
     * 32 字节版本直接构造必须抛」，否则删掉构造器里的上限判断，整个套件仍然全绿。
     */
    @Test
    void constructorRejectsAnInvalidMasterKeyTable() {
        assertThatThrownBy(() -> new ChannelKeyRegistry(Map.of(1, new byte[16])))
                .as("16 字节会被静默降级成 AES-128").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChannelKeyRegistry(Map.of(1, new byte[31])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChannelKeyRegistry(Map.of(1, new byte[33])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChannelKeyRegistry(Map.of(0, new byte[32])))
                .as("版本号必须为正").isInstanceOf(IllegalArgumentException.class);

        Map<Integer, byte[]> withNullKey = new LinkedHashMap<>();
        withNullKey.put(1, null);
        assertThatThrownBy(() -> new ChannelKeyRegistry(withNullKey))
                .as("null 密钥不得构造出表").isInstanceOf(IllegalArgumentException.class);

        Map<Integer, byte[]> tooManyVersions = new LinkedHashMap<>();
        for (int version = 1; version <= 9; version++) {
            tooManyVersions.put(version, Base64.getDecoder().decode(b64Key(version)));
        }
        assertThatThrownBy(() -> new ChannelKeyRegistry(tooManyVersions))
                .as("版本数上限（8）必须由构造器自己守住，而不是只靠 parse")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new ChannelKeyRegistry(Map.of(1, new byte[32])).has(1))
                .as("合法表仍可构造").isTrue();
    }

    /**
     * 评审 Fix 5：主密钥数组**不得按引用外泄**。旧实现里 {@code key(1)} / {@code keys().get(1)}
     * 返回的就是内部数组，调用方（或任何下游工具）就地清零/改写等于破坏表里的密钥。
     * 同时钉住入参侧：传给构造器的数组之后被改写，也不得影响表内内容。
     */
    @Test
    void keyMaterialIsHandedOutAsDefensiveCopies() {
        byte[] expected = Base64.getDecoder().decode(b64Key(1));
        ChannelKeyRegistry registry = registry(1);

        byte[] fromKey = registry.key(1).orElseThrow();
        byte[] fromKeys = registry.keys().get(1);
        assertThat(fromKey).containsExactly(expected);
        assertThat(fromKeys).containsExactly(expected);
        assertThat(fromKeys).as("两次取到的数组不得是同一个引用").isNotSameAs(fromKey);

        Arrays.fill(fromKey, (byte) 0);
        Arrays.fill(fromKeys, (byte) 0);

        assertThat(registry.key(1).orElseThrow()).as("清零交出的数组不得影响表内密钥").containsExactly(expected);
        assertThat(registry.keys().get(1)).containsExactly(expected);

        byte[] source = Base64.getDecoder().decode(b64Key(1));
        ChannelKeyRegistry built = new ChannelKeyRegistry(Map.of(1, source));
        Arrays.fill(source, (byte) 0);
        assertThat(built.key(1).orElseThrow()).as("改写入参数组不得影响表内密钥").containsExactly(expected);
    }

    /**
     * 评审 Fix 4：{@code Map.copyOf} 丢弃迭代顺序，而且 {@code ImmutableCollections} 的遍历顺序带
     * **每次 JVM 启动随机化的 SALT** —— 同一份输入 {@code v7,v1,v4} 实测出现过 {@code [4, 1, 7]}、
     * {@code [4, 7, 1]}、{@code [1, 7, 4]} 三种顺序（本类的红/绿证据里就是 {@code [1, 7, 4]}）。
     * {@code describe()} 现在显式排序：同一份环境变量必须打出同一行。
     */
    @Test
    void describeListsVersionsInSortedOrderRegardlessOfParseOrder() {
        assertThat(registry(7, 1, 4).describe()).isEqualTo("已加载主密钥版本 [1, 4, 7]");
        assertThat(registry(4, 1, 7).describe()).isEqualTo(registry(1, 4, 7).describe());
        assertThat(ChannelKeyRegistry.parse("").describe()).isEqualTo("无主密钥");
    }

    /**
     * 评审 Fix 6：版本数上限**不得静默丢弃**。旧实现遇到第 9 段直接 {@code break}，实测
     * {@code size=8, currentVersion()=8, has(9)=false} —— 运维多写一段，{@code encrypt} 就悄悄改用
     * 较小的版本号加密，唯一的信号是 {@code describe()}。现在超过上限**整表拒绝**：{@code parse}
     * 仍然永不抛异常，返回空表 =「未配置主密钥」，admin 侧的 {@code encrypt} 立刻抛
     * {@code IllegalStateException}（响亮失败），而不是选错密钥。
     */
    @Test
    void registryRefusesMoreVersionsThanTheCap() {
        assertThat(ChannelKeyRegistry.parse(envOf(1, 2, 3, 4, 5, 6, 7, 8)).currentVersion())
                .as("恰好 8 个版本是上限内，必须照常解析")
                .isEqualTo(8);

        ChannelKeyRegistry refused = ChannelKeyRegistry.parse(envOf(1, 2, 3, 4, 5, 6, 7, 8, 9));

        assertThat(refused.isEmpty()).as("超过上限必须整表拒绝，不得截断成前 8 个").isTrue();
        assertThat(refused.currentVersion()).isZero();
        assertThat(refused.has(9)).isFalse();
        assertThatThrownBy(() -> new AesGcmChannelCipher(refused).encrypt(PLAINTEXT))
                .as("拒绝的表等于「未配置主密钥」：加密要响亮失败").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyRegistryCannotEncryptAndCannotDecrypt() {
        ChannelKeyRegistry empty = ChannelKeyRegistry.parse("");
        AesGcmChannelCipher cipher = new AesGcmChannelCipher(empty);

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.currentVersion()).isZero();
        assertThatThrownBy(() -> cipher.encrypt(PLAINTEXT)).isInstanceOf(IllegalStateException.class);
        assertThat(cipher.decrypt("v1:AAAA")).isEmpty();
        assertThat(empty.key(1)).isEqualTo(Optional.empty());
        assertThat(empty.keys()).isEqualTo(Map.of());
    }
}
