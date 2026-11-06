package com.bikeguard.lsposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 可选增强层：LSPosed。**只读监听，不修改任何业务逻辑或上报数据。**
 *
 * 定位：这是第三优先级，不是首选。原因：
 *  1. 美团主 App 有加固，类名/方法名混淆，真实 ClassLoader 要等壳解密后才拿到；
 *  2. 美团对 root / Xposed 环境有风控，主账号试用有账号风险；
 *  3. 版本更新即失效，维护成本远高于通知监听。
 *
 * 能带来什么：在"开锁成功"到"关锁成功"之间拿到精确的订单状态字段，
 * 从而把判定从"推测"升级为"确认"，尤其能解决"关锁失败但用户以为关了"的情况。
 *
 * ---------------------------------------------------------------
 * 注意：美团《共享单车租赁服务协议》第 4.1(3) 条明确禁止
 * "利用技术手段侵入、访问、记录、盗取、交易、传播平台系统的数据"。
 * hook 第三方 App 属于协议风险区，请自行评估；本文件仅作技术研究参考。
 * ---------------------------------------------------------------
 */
class BikeHook : IXposedHookLoadPackage {

    companion object {
        private const val PKG = "com.sankuai.meituan"
        private const val TAG = "BikeGuard/LSP"
        private val KEYWORDS = listOf(
            "lockStatus", "rideStatus", "orderStatus", "bikeNo", "endTime",
            "关锁", "行程结束", "骑行中"
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PKG) return

        // 加固 App：Application 是壳的入口，此时真实 ClassLoader 已就绪
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { installNetworkTap(lpparam) }
                        .onFailure { XposedBridge.log("$TAG install fail: $it") }
                }
            }
        )
    }

    /**
     * 通用网络层探针：拦截 OkHttp 的 BridgeInterceptor，peek 响应体做关键词匹配。
     *
     * 现实情况：美团大量接口走 protobuf / 自研网络栈，这里大概率捞不到明文。
     * 正确姿势是先 dump 出真实链路：
     *   - 脱壳后 dump 类列表，grep "okhttp|retrofit|network|shark|mnetwork"
     *   - 或在 hook 里打印所有 Response 的 content-type 与 URL path
     * 找到单车订单相关 path 后，再收窄到具体接口。
     */
    private fun installNetworkTap(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader

        val bridge = runCatching {
            XposedHelpers.findClass("okhttp3.internal.http.BridgeInterceptor", cl)
        }.getOrNull()
        if (bridge == null) {
            XposedBridge.log("$TAG okhttp not found (可能被混淆或未使用)")
            return
        }

        XposedHelpers.findAndHookMethod(
            bridge, "intercept",
            XposedHelpers.findClass("okhttp3.Interceptor\$Chain", cl),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val resp = param.result ?: return
                        @Suppress("UNCHECKED_CAST")
                        val peek = XposedHelpers.callMethod(resp, "peekBody", 64L * 1024) ?: return
                        val body = XposedHelpers.callMethod(peek, "string") as? String ?: return
                        if (KEYWORDS.none { body.contains(it, ignoreCase = true) }) return

                        val req = XposedHelpers.callMethod(resp, "request")
                        val url = XposedHelpers.callMethod(req, "url").toString()
                        XposedBridge.log("$TAG HIT $url")
                        XposedBridge.log("$TAG BODY ${body.take(800)}")
                        // 命中后：解析出订单状态 → 通过广播 / 本地文件投递给 BikeGuard
                    }.onFailure { XposedBridge.log("$TAG tap err: $it") }
                }
            }
        )
    }
}

/**
 * 模块入口（assets/xposed_init 内容）：
 *   com.bikeguard.lsposed.BikeHook
 *
 * LSPosed 作用域只需勾选「美团」。不要勾选系统框架，没必要且放大风险。
 */
