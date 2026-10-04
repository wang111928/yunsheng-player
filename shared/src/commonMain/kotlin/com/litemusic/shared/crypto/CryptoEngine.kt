package com.litemusic.shared.crypto

/**
 * 三通道加密路由：eapi → xeapi → weapi 自动降级。
 * 写操作默认 eapi（规避 -460），读操作默认 weapi。
 */
class CryptoEngine {
    enum class Channel { EAPI, XEAPI, WEAPI }

    /** 生成 eapi 表单体（写操作首选，规避 -460 风控）；params 为大写 hex，无需编码 */
    fun eapi(url: String, params: Map<String, Any?>): Map<String, String> =
        mapOf("params" to EapiCrypto.encrypt(url, JsonText.build(params)))

    /** 生成 weapi 表单体（读操作默认） */
    fun weapi(params: Map<String, Any?>): Map<String, String> {
        val r = WeapiCrypto.encrypt(JsonText.build(params))
        return mapOf("params" to r.params, "encSecKey" to r.encSecKey)
    }

    /**
     * 生成 xeapi 表单体（B/S/R 三字段）。
     * publicKeyState 需从官方接口引导获取（TODO），获取前 xeapi 不可用。
     */
    fun xeapi(
        url: String,
        params: Map<String, Any?>,
        publicKeyState: XeapiCrypto.PublicKeyState?,
    ): Map<String, String> {
        val form = XeapiCrypto().encrypt(
            uri = url,
            body = params.mapValues { (_, v) -> v?.toString() ?: "" },
            publicKeyState = publicKeyState,
        )
        return mapOf("B" to form.b, "S" to form.s, "R" to form.r)
    }
}
