package com.tian.textbook.auth;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.bean.WxMaSubscribeMessage;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import com.tian.textbook.common.config.TextbookProperties;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.util.http.apache.DefaultApacheHttpClientBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 微信小程序客户端封装（weixin-java-miniapp 4.6.x，SPEC §1）。
 *
 * <p>未配置 appid/secret 时安全降级（返回 null），不影响本地与试运行前的联调；
 * 订阅消息授权率机制上限见 W5/R10。</p>
 *
 * <p>HTTP 超时必须显式设置：SDK 默认不设读超时，对端挂起会让调用线程无限阻塞
 * （通知重发此前在事务内串行调用微信接口，一旦挂起即事务挂起、连接池耗尽）。</p>
 */
@Slf4j
@Component
public class WxMaClient {

    /** 建连超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    /** 读取超时（毫秒）：微信接口正常在百毫秒级，5 秒足够 */
    private static final int SOCKET_TIMEOUT_MS = 5_000;
    /** 从连接池取连接的等待上限（毫秒） */
    private static final int CONNECTION_REQUEST_TIMEOUT_MS = 3_000;
    /** 连接池上限（通知重发为串行，小池即可） */
    private static final int MAX_TOTAL_CONN = 20;
    private static final int MAX_CONN_PER_HOST = 10;

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
        DefaultApacheHttpClientBuilder httpBuilder = DefaultApacheHttpClientBuilder.get();
        httpBuilder.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        httpBuilder.setSoTimeout(SOCKET_TIMEOUT_MS);
        httpBuilder.setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MS);
        httpBuilder.setMaxTotalConn(MAX_TOTAL_CONN);
        httpBuilder.setMaxConnPerHost(MAX_CONN_PER_HOST);
        config.setApacheHttpClientBuilder(httpBuilder);
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

    /**
     * 订阅消息发送（W5/R10：一次性订阅「一次授权一条」；未配置 appid/secret 或模板 id 时
     * 返回 false，调用方按 unauthorized/failed 如实落库）。
     *
     * @param openid     接收者 openid（仅 student；未绑定即未授权）
     * @param templateId 订阅消息模板 id（环境变量 WX_SUBSCRIBE_TEMPLATE_ID）
     * @param page       跳转页面
     * @param data       模板数据（key 不含 thing/data 前缀，SDK 要求带前缀的自行处理）
     */
    public boolean sendSubscribeMessage(String openid, String templateId, String page,
                                        Map<String, String> data) {
        if (wxMaService == null || openid == null || openid.isBlank()
                || templateId == null || templateId.isBlank()) {
            return false;
        }
        try {
            List<WxMaSubscribeMessage.MsgData> msgData = new ArrayList<>();
            data.forEach((k, v) -> msgData.add(new WxMaSubscribeMessage.MsgData().setName(k).setValue(v)));
            WxMaSubscribeMessage message = WxMaSubscribeMessage.builder()
                    .toUser(openid)
                    .templateId(templateId)
                    .page(page)
                    .data(msgData)
                    .build();
            wxMaService.getMsgService().sendSubscribeMsg(message);
            return true;
        } catch (Exception e) {
            log.warn("订阅消息发送失败: openid={}, err={}", openid, e.getMessage());
            return false;
        }
    }
}
