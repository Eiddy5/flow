package org.flow.core.http.client;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientIpLiteralTest {
    /**
     * 验证同一地址的整数、部分十进制、前导零、IPv6 映射与 scope 写法维持 JDK 字面量语义。
     *
     * @param literal 输入的 IP 字面量
     * @param expected 解析后应得到的地址文本
     * @throws ReflectiveOperationException 当测试无法调用字面量解析入口时抛出
     */
    @ParameterizedTest
    @CsvSource(value = {
        "0 | 0.0.0.0",
        "4294967295 | 255.255.255.255",
        "169.254.43518 | 169.254.169.254",
        "2852039166 | 169.254.169.254",
        "127.1 | 127.0.0.1",
        "1.16777215 | 1.255.255.255",
        "1.2.65535 | 1.2.255.255",
        "0177.0.0.1 | 177.0.0.1",
        "000000000000001 | 0.0.0.1",
        "[::1]. | 0:0:0:0:0:0:0:1",
        "[[::1]] | 0:0:0:0:0:0:0:1",
        "::0000000000000000000000001 | 0:0:0:0:0:0:0:1",
        "::ffff:169.254.169.254 | 169.254.169.254",
        "::00000ffff:a9fe:a9fe | 169.254.169.254",
        "::ffff:010.001.2.3 | 10.1.2.3",
        "fe80::1%0 | fe80:0:0:0:0:0:0:1%0",
        "fe80::1%0002 | fe80:0:0:0:0:0:0:1%2"
    }, delimiter = '|')
    void shouldParseCompatibleLiteralForms(String literal, String expected) throws ReflectiveOperationException {
        assertThat(parse(literal)).map(InetAddress::getHostAddress).contains(expected);
    }

    /**
     * 验证可解析主机名、畸形 IPv6 与越界 IPv4 不被当作地址，主机名不会转换成 DNS 结果。
     *
     * @param literal 非 IP 主机名或非法地址文本
     * @throws ReflectiveOperationException 当测试无法调用字面量解析入口时抛出
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "", "localhost", "localhost.", "[localhost]", "localhost:80", "not-an-ip::1",
        "0xA9FEA9FE", "+1", "1..2", "1.2.3.4.5", "256.1", "1.16777216",
        "1.2.65536", "1.2.3.256", "4294967296", "0000000000000001",
        "::ffff:127.1", "::ffff:169.254.169.254%0", "fe80::1%", "1::2::3"
    })
    void shouldRejectHostnamesAndInvalidLiterals(String literal) throws ReflectiveOperationException {
        assertThat(parse(literal)).isEmpty();
    }

    /**
     * 验证允许及拒绝名单共用的主机与 CIDR 比较覆盖同地址变体，且不把主机名解析成 IP 匹配。
     *
     * @param entry 用于比较的主机或 CIDR 名单项
     * @param uri 请求 URI 文本
     * @param expected 是否应匹配该名单项
     * @throws ReflectiveOperationException 当测试无法调用名单比较入口时抛出
     */
    @ParameterizedTest
    @CsvSource(value = {
        "169.254.169.254 | http://169.254.43518/ | true",
        "169.254.169.254 | http://2852039166/ | true",
        "169.254.169.254 | http://[::ffff:a9fe:a9fe]/ | true",
        "169.254.0.0/16 | http://169.254.43518/ | true",
        "169.254.0.0/16 | http://2852039166/ | true",
        "169.254.0.0/16 | http://[::ffff:a9fe:a9fe]/ | true",
        "10.0.0.0/8 | http://010.1.2.3/ | true",
        "127.0.0.0/8 | http://localhost/ | false",
        "169.254.0.0/16 | http://169.255.0.1/ | false",
        "https://169.254.0.0/16 | http://2852039166/ | false"
    }, delimiter = '|')
    void shouldMatchLiteralFormsWithoutResolvingHostnames(String entry, String uri, boolean expected) throws ReflectiveOperationException {
        Method matcher = HttpClient.class.getDeclaredMethod("isListEntryMatch", String.class, URI.class);
        matcher.setAccessible(true);
        assertThat(matcher.invoke(null, entry, URI.create(uri))).isEqualTo(expected);
    }

    /**
     * 验证 JVM 启动时允许 Unicode IP 数字后，完整、部分及整数形式仍使用同一主机和 CIDR 规则。
     * 在未设置或关闭该 JVM 属性时，这些输入维持非字面量语义。
     *
     * @param literal 包含 Unicode 十进制数字的主机文本
     * @param expected 启用属性后应解析得到的 IPv4 地址
     * @throws ReflectiveOperationException 当测试无法调用解析或名单比较入口时抛出
     */
    @ParameterizedTest
    @CsvSource(value = {
        "１６９.２５４.１６９.２５４ | 169.254.169.254",
        "١٦٩.٢٥٤.٤٣٥١٨ | 169.254.169.254",
        "२८५२०३९१६६ | 169.254.169.254"
    }, delimiter = '|')
    void shouldFollowJvmUnicodeDigitPolicy(String literal, String expected) throws ReflectiveOperationException {
        boolean enabled = Boolean.getBoolean("jdk.net.allowAmbiguousIPAddressLiterals");
        assertThat(parse(literal).map(InetAddress::getHostAddress))
            .isEqualTo(enabled ? Optional.of(expected) : Optional.empty());
        Method matcher = HttpClient.class.getDeclaredMethod("isListEntryMatch", String.class, URI.class);
        matcher.setAccessible(true);
        URI uri = URI.create("http://" + literal + "/");
        assertThat(matcher.invoke(null, expected, uri)).isEqualTo(enabled);
        assertThat(matcher.invoke(null, "169.254.0.0/16", uri)).isEqualTo(enabled);
        assertThat(matcher.invoke(null, "１６９.２５４.０.０/16", URI.create("http://169.254.169.254/"))).isEqualTo(enabled);
    }

    /**
     * 调用 HTTP 名单使用的字面量解析方法，测试不建立客户端或网络连接。
     *
     * @param literal 待解析的非空引用主机文本
     * @return 解析后的 IP 地址；非法字面量或主机名对应空值
     * @throws ReflectiveOperationException 当解析入口不存在或调用失败时抛出
     */
    @SuppressWarnings("unchecked")
    private static Optional<InetAddress> parse(String literal) throws ReflectiveOperationException {
        Method parser = HttpClient.class.getDeclaredMethod("parseIpLiteral", String.class);
        parser.setAccessible(true);
        return (Optional<InetAddress>) parser.invoke(null, literal);
    }
}
