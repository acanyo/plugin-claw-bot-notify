package com.xhhao.clawbotnotify.channel;

import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WechatDestinationPolicy {
    public static final String DEFAULT_BASE = "https://ilinkai.weixin.qq.com";
    // Add an IDC hostname only after verifying it against the pinned upstream and a real login.
    private static final Set<String> HOSTS = Set.of("ilinkai.weixin.qq.com");

    public URI validate(String value) {
        try {
            var uri = URI.create(value);
            if (!"https".equals(uri.getScheme())
                    || !HOSTS.contains(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return URI.create("https://" + uri.getHost());
        } catch (RuntimeException e) {
            throw new ChannelException(ChannelException.Code.UNSAFE_DESTINATION);
        }
    }
}
