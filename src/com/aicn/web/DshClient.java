package com.aicn.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * DeepSeek Harness 本机服务的极简存活客户端。
 * 只负责两件事：
 *  1) probe() —— HTTP 存活检查（服务是否在 DEFAULT_BASE 上监听）；
 *  2) setAuthToken() —— 记录本插件自己启动 dsh 时捕获的进程 token（供内嵌浏览器拼 /?token=）。
 * 工作区注册/会话等操作一律不再走 Java 侧 RPC：dsh 0.1.2 已移除点号式 HTTP unary
 * （/api/workspace.list 等 404/405），真实通道是页面会话内的斜杠式 typert 网关
 * （/api/workspace/create），由注入 JS（WS_REGISTER_JS）用页面自身的 fetch 完成。
 */
public class DshClient {

  private final String baseUrl;
  private final HttpClient http;
  private volatile String authToken;   // dsh web 启动时打印的进程 token（用于 /?token= 交换）
  private volatile String authCookie;  // token 交换得到的签名 Cookie（预留，页面内部 fetch 自带上）
  private final Object authLock = new Object();

  public DshClient(String baseUrl) {
    String b = baseUrl;
    if (b == null || b.trim().isEmpty()) {
      b = "http://127.0.0.1:3080";
    }
    while (b.endsWith("/")) {
      b = b.substring(0, b.length() - 1);
    }
    this.baseUrl = b;
    // 必须 HTTP/1.1：默认 HTTP/2 会先发 h2c upgrade 请求头，DSH 的 Node 服务器
    // 把 upgrade 当 WebSocket 处理并直接销毁 socket（连接被 reset）
    this.http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  /**
   * 探测服务是否在线：HTTP 存活检查（GET 根路径）。任何 HTTP 应答
   * （含 401/403/3xx——都说明服务在线）都算 true；只有连接失败/超时才算离线。
   * 短超时，供自动启动判断。注意不能用点号式 RPC（如 session.list）探测：
   * dsh 0.1.2 已移除该 HTTP unary 通道，服务在线也会 404/405 → 误判离线 → 误启第二个实例。
   */
  public boolean probe() {
    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(baseUrl + "/"))
          .GET()
          .timeout(java.time.Duration.ofSeconds(2))
          .build();
      http.send(req, HttpResponse.BodyHandlers.discarding());
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** 设置进程 token（本插件启动 dsh 时从 "dsh web: <url>" 行捕获的 /?token= 值）。 */
  public void setAuthToken(String token) {
    synchronized (authLock) {
      if (java.util.Objects.equals(this.authToken, token)) {
        return;
      }
      this.authToken = token;
      this.authCookie = null;
    }
  }

  /** 当前进程 token（供 browserUrl() 拼 /?token=... 让内嵌页面完成鉴权）。 */
  public String authToken() {
    return authToken;
  }
}
