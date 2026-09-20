package com.tian.textbook.auth.dto;

/**
 * 首登校验请求（W19：手机号后 4 位匹配 或 绑定 openid）。
 *
 * @param phoneTail 手机号后 4 位
 * @param wxCode    小程序 wx.login code（配置了微信密钥时换取 openid 绑定）
 */
public record FirstLoginVerifyRequest(String phoneTail, String wxCode) {
}
