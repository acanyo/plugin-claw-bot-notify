package com.xhhao.clawbotnotify.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WechatDestinationPolicyTest {
    private final WechatDestinationPolicy policy = new WechatDestinationPolicy();

    @Test
    void acceptsOnlyTheVerifiedHttpsOrigin() {
        assertThat(policy.validate("https://ilinkai.weixin.qq.com/").toString())
                .isEqualTo(WechatDestinationPolicy.DEFAULT_BASE);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://ilinkai.weixin.qq.com",
                "https://ilinkai.weixin.qq.com.evil.test",
                "https://127.0.0.1",
                "https://user:token@ilinkai.weixin.qq.com",
                "https://ilinkai.weixin.qq.com:8443",
                "https://ilinkai.weixin.qq.com/other",
                "https://ilinkai.weixin.qq.com?url=evil",
                "https://ilinkai.weixin.qq.com#fragment",
                "https://unknown.weixin.qq.com",
                "not a url"
            })
    void rejectsUnsafeOrUnverifiedDestinations(String value) {
        assertThatThrownBy(() -> policy.validate(value)).hasMessage("UNSAFE_DESTINATION");
    }
}
