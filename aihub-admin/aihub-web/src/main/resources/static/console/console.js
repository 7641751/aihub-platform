/*
 * aihub 极简管理台（零构建、零依赖、单文件）。
 *
 * 设计约束（Task 16）：
 *  - 令牌只进 window.sessionStorage（D9），绝不进 URL 或任何其它持久化存储；
 *  - 所有服务端文本一律用 textContent 渲染，绝不拼 HTML（这是本页面唯一的 XSS 面，D9 已登记代价）；
 *  - 所有网络调用走统一的 api(path, options) 包装：非 OK 显示 data.code，401 清令牌回登录视图；
 *  - 登录后必须再调一次 /api/ping 取 tenantId：LoginResponse 只回 {token, role, expiresAtEpochSecond}，
 *    而 /api/logs 在 CONVENTIONS §10 R3.1 下必须显式带 tenantId，否则日志视图必然 400。
 */
(function () {
  "use strict";

  var TOKEN_KEY = "aihub.console.token";

  // 页面状态：token 落 sessionStorage，其余只在内存里（刷新后靠 /api/ping 重建）。
  var state = { token: null, tenantId: null, role: null };
  var channelListRequest = null;
  var channelProbes = new Map();

  function byId(id) {
    return document.getElementById(id);
  }

  // 统一错误对象：把 admin 信封的 {code, message} 带出来给视图显示。
  function ApiError(code, message) {
    var error = new Error(message || code || "未知错误");
    error.code = code;
    return error;
  }

  function describe(error) {
    if (!error) {
      return "未知错误";
    }
    if (error.code) {
      return error.message && error.message !== error.code
        ? error.code + "：" + error.message
        : error.code;
    }
    return String(error.message || error);
  }

  /*
   * 统一的网络包装。
   * 非 OK：抛出带 code 的 ApiError（data.code 由调用方显示）。
   * 401：清令牌 + 回登录视图（除非显式 authRedirect:false，例如登录接口自己）。
   */
  function api(path, options) {
    var opts = options || {};
    var headers = { Accept: "application/json" };
    var init = { method: opts.method || "GET", headers: headers };
    if (opts.body !== undefined && opts.body !== null) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(opts.body);
    }
    var token = state.token;
    if (token) {
      headers.Authorization = "Bearer " + token;
    }

    return fetch(path, init).then(function (response) {
      return response
        .json()
        .catch(function () {
          return { code: "CLIENT_ERROR", message: "响应不是合法 JSON" };
        })
        .then(function (envelope) {
          if (response.status === 401 && opts.authRedirect !== false) {
            if (state.token === token) {
              clearSession();
              showLoginView("登录已失效，请重新登录");
            }
            throw ApiError("UNAUTHORIZED", envelope.message);
          }
          if (response.ok && envelope.code === "OK") {
            return envelope.data;
          }
          throw ApiError(envelope.code || "HTTP_" + response.status, envelope.message);
        });
    });
  }

  // --- 视图切换 -------------------------------------------------------------

  function showLoginView(message) {
    byId("login-view").hidden = false;
    byId("app-view").hidden = true;
    byId("logout-btn").hidden = true;
    setMessage(byId("login-error"), message || "", Boolean(message));
  }

  function showAppView() {
    byId("login-view").hidden = true;
    byId("app-view").hidden = false;
    byId("logout-btn").hidden = false;
    var canWrite = state.role === "ADMIN";
    byId("channel-form").hidden = !canWrite;
    byId("channel-actions-heading").hidden = !canWrite;
    byId("key-form").hidden = !canWrite;
    var role = state.role || "";
    var tenant = state.tenantId === null ? "" : state.tenantId;
    setText(byId("session-info"), "角色 " + role + "，租户 " + tenant);
  }

  function selectTab(name) {
    ["channels", "keys", "logs", "usage"].forEach(function (tab) {
      byId("tab-" + tab).classList.toggle("active", tab === name);
      byId(tab + "-panel").hidden = tab !== name;
    });
    if (name === "channels") {
      loadChannels();
    } else if (name === "keys") {
      loadKeys();
    } else if (name === "usage") {
      loadUsage();
    }
  }

  // --- 通用渲染助手（只用 textContent，绝不拼 HTML）--------------------------

  function setText(element, value) {
    element.textContent = value === undefined || value === null ? "" : String(value);
  }

  function setMessage(element, text, isError) {
    setText(element, text);
    element.classList.toggle("error", Boolean(isError));
  }

  function cell(row, value) {
    var td = document.createElement("td");
    setText(td, value);
    row.appendChild(td);
    return td;
  }

  function renderRows(tbody, records, columns, appendAction) {
    setText(tbody, "");
    records.forEach(function (record) {
      var row = document.createElement("tr");
      columns.forEach(function (column) {
        cell(row, record[column]);
      });
      if (appendAction) {
        appendAction(row, record);
      }
      tbody.appendChild(row);
    });
  }

  // --- 会话 -----------------------------------------------------------------

  function saveToken(token) {
    state.token = token;
    window.sessionStorage.setItem(TOKEN_KEY, token);
  }

  function clearSession() {
    state.token = null;
    state.tenantId = null;
    state.role = null;
    channelListRequest = null;
    channelProbes.clear();
    byId("channel-form").reset();
    byId("key-form").reset();
    byId("plaintext-box").hidden = true;
    setText(byId("plaintext-key"), "");
    window.sessionStorage.removeItem(TOKEN_KEY);
  }

  function enterApp(ping) {
    state.tenantId = ping.tenantId;
    state.role = ping.role;
    byId("logs-tenant-id").value = ping.tenantId;
    showAppView();
    selectTab("channels");
  }

  // --- 登录 / 退出 ----------------------------------------------------------

  function submitLogin(event) {
    event.preventDefault();
    setMessage(byId("login-error"), "", false);
    api("/api/auth/login", {
      method: "POST",
      authRedirect: false,
      body: { username: byId("username").value, password: byId("password").value }
    })
      .then(function (login) {
        saveToken(login.token);
        state.role = login.role;
        // LoginResponse 不含 tenantId ⇒ 必须再探一次，否则日志视图必然 400。
        return api("/api/ping");
      })
      .then(function (ping) {
        byId("login-form").reset();
        enterApp(ping);
      })
      .catch(function (error) {
        setMessage(byId("login-error"), describe(error), true);
      });
  }

  function submitLogout() {
    clearSession();
    showLoginView("已退出登录");
  }

  // --- 渠道视图 -------------------------------------------------------------

  function renderChannels(list) {
    var appendAction = state.role === "ADMIN" ? addChannelProbe : null;
    renderRows(byId("channels-body"), list, ["id", "name", "provider", "baseUrl", "status"], appendAction);
  }

  function addChannelProbe(row, channel) {
    var action = cell(row, "");
    var button = document.createElement("button");
    button.type = "button";
    button.setAttribute("aria-label", "探活 " + channel.name);
    setText(button, "探活");
    var result = document.createElement("div");
    result.className = "message probe-result";
    result.setAttribute("role", "status");
    var probe = channelProbes.get(channel.id);
    if (!probe) {
      probe = { pending: false, message: "", isError: false };
      channelProbes.set(channel.id, probe);
    }
    probe.button = button;
    probe.result = result;
    renderProbe(probe);
    button.addEventListener("click", function () {
      probeChannel(channel.id, probe);
    });
    action.appendChild(button);
    action.appendChild(result);
  }

  function renderProbe(probe) {
    probe.button.disabled = probe.pending;
    setMessage(probe.result, probe.message, probe.isError);
  }

  function setProbeState(probe, pending, message, isError) {
    probe.pending = pending;
    probe.message = message;
    probe.isError = isError;
    renderProbe(probe);
  }

  function probeChannel(id, probe) {
    if (state.role !== "ADMIN" || channelProbes.get(id) !== probe || probe.pending) {
      return;
    }
    setProbeState(probe, true, "探活中…", false);
    api("/api/channels/" + encodeURIComponent(id) + "/probe", { method: "POST" })
      .then(function (result) {
        var details = [result.reachable ? "可达" : "不可达"];
        if (result.httpStatus != null) {
          details.push("HTTP " + result.httpStatus);
        }
        details.push(result.latencyMs + " ms");
        if (result.message) {
          details.push(result.message);
        }
        setProbeState(probe, false, details.join(" · "), !result.reachable);
      })
      .catch(function (error) {
        setProbeState(probe, false, describe(error), true);
      });
  }

  function loadChannels() {
    var message = byId("channels-message");
    setText(byId("channels-body"), "");
    setMessage(message, "正在加载渠道…", false);
    var request = api("/api/channels");
    channelListRequest = request;
    request
      .then(function (list) {
        if (channelListRequest === request) {
          renderChannels(list);
          setMessage(message, list.length ? "" : "暂无渠道", false);
        }
      })
      .catch(function (error) {
        if (channelListRequest === request) {
          setMessage(message, describe(error), true);
        }
      });
  }

  function submitChannel(event) {
    event.preventDefault();
    if (state.role !== "ADMIN") {
      return;
    }
    var token = state.token;
    setMessage(byId("channels-message"), "", false);
    var models = byId("channel-models").value;
    api("/api/channels", {
      method: "POST",
      body: {
        name: byId("channel-name").value,
        provider: byId("channel-provider").value,
        baseUrl: byId("channel-base-url").value,
        apiKey: byId("channel-api-key").value,
        modelsJson: models ? models : null,
        status: "ACTIVE"
      }
    })
      .then(function () {
        if (state.token === token) {
          byId("channel-form").reset();
          loadChannels();
        }
      })
      .catch(function (error) {
        if (state.token === token) {
          setMessage(byId("channels-message"), describe(error), true);
        }
      });
  }

  // --- Key 视图 -------------------------------------------------------------

  function renderKeys(list) {
    renderRows(byId("keys-body"), list, ["id", "name", "status", "expireAt"]);
  }

  function loadKeys() {
    setMessage(byId("keys-message"), "", false);
    api("/api/api-keys")
      .then(renderKeys)
      .catch(function (error) {
        setMessage(byId("keys-message"), describe(error), true);
      });
  }

  function submitKey(event) {
    event.preventDefault();
    if (state.role !== "ADMIN") {
      return;
    }
    var token = state.token;
    setMessage(byId("keys-message"), "", false);
    var rawValidDays = byId("key-valid-days").value;
    var validDays = rawValidDays ? parseInt(rawValidDays, 10) : null;
    api("/api/api-keys", {
      method: "POST",
      body: { tenantId: state.tenantId, name: byId("key-name").value, validDays: validDays }
    })
      .then(function (created) {
        if (state.token !== token) {
          return;
        }
        byId("key-form").reset();
        // 明文只展示这一次：写入 DOM 用 textContent，绝不进 sessionStorage / URL。
        byId("plaintext-box").hidden = false;
        setText(byId("plaintext-key"), created.plaintextKey);
        loadKeys();
      })
      .catch(function (error) {
        if (state.token === token) {
          setMessage(byId("keys-message"), describe(error), true);
        }
      });
  }

  // --- 日志视图 -------------------------------------------------------------

  function renderLogs(page) {
    renderRows(byId("logs-body"), page.records || [], ["requestId", "status", "model", "createdAt"]);
  }

  // 服务端的 from/to 是**必填**且必须是可被 Instant.parse 解析的**带 Z 的 UTC** 字面量
  // （缺省或解析失败一律 400，见 docs/CONVENTIONS.md §10 R3.1 与 Task 11 的裁定 3）。
  // 而 <input type="datetime-local"> 的值形如 "2026-10-02T15:30"（**无秒、无时区**）：
  // 直接透传必然 400；输入为空时原样省略也必然 400。所以在这里统一补齐秒与 Z，并在
  // 为空时给一个「最近 24 小时」的窗口 —— 否则点一次「查询」永远查不出东西。
  function utcInstant(value, fallbackMillis) {
    if (!value) {
      return new Date(fallbackMillis).toISOString().replace(/\.[0-9]{3}Z$/, "Z");
    }
    return (value.length === 16 ? value + ":00" : value) + "Z";
  }

  function submitLogs(event) {
    event.preventDefault();
    setMessage(byId("logs-message"), "", false);
    var tenantId = byId("logs-tenant-id").value || state.tenantId;
    var now = Date.now();
    var query = "tenantId=" + encodeURIComponent(tenantId)
        + "&from=" + encodeURIComponent(utcInstant(byId("logs-from").value, now - 24 * 60 * 60 * 1000))
        + "&to=" + encodeURIComponent(utcInstant(byId("logs-to").value, now));
    api("/api/logs?" + query)
      .then(renderLogs)
      .catch(function (error) {
        setMessage(byId("logs-message"), describe(error), true);
      });
  }

  // --- 用量视图（只读）-------------------------------------------------------

  /*
   * GET /api/quotas/usage（无参 = 当前 UTC 周期；租户由服务端从令牌取，R3.2 不接受显式覆盖）。
   * 后端把三个语义写进了响应，UI **不许抹平**：
   *  - unlimited: true      ⇒ 上限/剩余显示「不限」；`-1` 是协议值，永远不许出现在页面上（D15）；
   *  - bucketMissing: true  ⇒ 提示「本周期尚无预扣记录」—— 那两个 0 是"没有记录"，不是"没花钱"；
   *  - source / estimate    ⇒ 口径行常显（redis-bucket 的**预扣估算**，不是账单数字）。
   * 读失败（例如 Redis 不可用 ⇒ 500 INTERNAL_ERROR）时**清空数字与口径行**：
   * 把失败渲染成 0 会比不显示更糟 —— 那是"看起来成功"。
   */
  function limitText(limit) {
    return limit > 0 ? String(limit) : "不限";
  }

  function remainingText(remaining) {
    return remaining < 0 ? "不限" : String(remaining);
  }

  function renderUsage(view) {
    setText(byId("usage-meta"), "周期 " + view.period + " · 数据源 " + view.source
      + (view.estimate ? "（预扣估算，不是账单口径）" : ""));
    var body = byId("usage-body");
    setText(body, "");
    [
      ["token", view.tokenLimit, view.tokenUsed, view.tokenRemaining],
      ["请求数", view.requestLimit, view.requestUsed, view.requestRemaining]
    ].forEach(function (dimension) {
      var row = document.createElement("tr");
      cell(row, dimension[0]);
      cell(row, limitText(dimension[1]));
      cell(row, dimension[2]);
      cell(row, remainingText(dimension[3]));
      body.appendChild(row);
    });
    setMessage(byId("usage-note"),
      view.bucketMissing ? "本周期尚无预扣记录 —— 这里的 0 不代表用了 0。" : "", false);
  }

  function loadUsage() {
    setMessage(byId("usage-message"), "", false);
    setMessage(byId("usage-note"), "", false);
    byId("usage-refresh").disabled = true;
    api("/api/quotas/usage")
      .then(function (view) {
        renderUsage(view);
        byId("usage-refresh").disabled = false;
      }, function (error) {
        setText(byId("usage-body"), "");
        setText(byId("usage-meta"), "");
        setMessage(byId("usage-message"), describe(error), true);
        byId("usage-refresh").disabled = false;
      });
  }

  // --- 启动 -----------------------------------------------------------------

  function init() {
    byId("login-form").addEventListener("submit", submitLogin);
    byId("logout-btn").addEventListener("click", submitLogout);
    byId("tab-channels").addEventListener("click", function () { selectTab("channels"); });
    byId("tab-keys").addEventListener("click", function () { selectTab("keys"); });
    byId("tab-logs").addEventListener("click", function () { selectTab("logs"); });
    byId("tab-usage").addEventListener("click", function () { selectTab("usage"); });
    byId("channel-form").addEventListener("submit", submitChannel);
    byId("key-form").addEventListener("submit", submitKey);
    byId("logs-form").addEventListener("submit", submitLogs);
    byId("usage-refresh").addEventListener("click", loadUsage);

    var existing = window.sessionStorage.getItem(TOKEN_KEY);
    if (existing) {
      state.token = existing;
      api("/api/ping")
        .then(enterApp)
        .catch(function () { /* 401 已在 api 里回登录视图，这里不再重复提示 */ });
    } else {
      showLoginView("");
    }
  }

  document.addEventListener("DOMContentLoaded", init);
})();
