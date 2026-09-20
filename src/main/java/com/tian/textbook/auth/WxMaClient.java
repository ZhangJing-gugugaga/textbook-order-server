package com.tian.textbook.auth;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import com.tian.textbook.common.config.TextbookProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 微信小程序客户端封装（weixin-java-miniapp 4.6.x，SPEC §1）。
 *
 * <p>未配置 appid/secret 时安全降级（返回 null），不影响本地与试运行前的联调；
 * 订阅消息授权率机制上限见 W5/R10。</p>
 */
@Slf4j
@Component
public class WxMaClient {

    private final WxMaService wxMaService; // null = 未配置

    public WxMaClient(TextbookProperties properties) {
        TextbookProperties.Miniapp miniapp = properties.getWeixin().getMiniapp();
        if (miniapp.getAppid() == null || miniapp.getAppid().isBlank()
                || miniapp.getSecret() == null || miniapp.getSecret().isBlank()) {
            this.wxMaService = null;
            return;
        }
        WxMaDefaultConfigImpl config = new WxMaDefaultConfigImpl();
        config.setAppid(miniapp.getAppid());
        config.setSecret(miniapp.getSecret());
        WxMaService service = new WxMaServiceImpl();
        service.setWxMaConfig(config);
        this.wxMaService = service;
    }

    public boolean configured() {
        return wxMaService != null;
    }

    /** wx.login code → openid；未配置或失败返回 null。 */
    public String code2Openid(String code) {
        if (wxMaService == null || code == null || code.isBlank()) {
            return null;
        }
        try {
            return wxMaService.getUserService().getSessionInfo(code).getOpenid();
        } catch (Exception e) {
            log.warn("code2session 失败: {}", e.getMessage());
            return null;
        }
    }
}
