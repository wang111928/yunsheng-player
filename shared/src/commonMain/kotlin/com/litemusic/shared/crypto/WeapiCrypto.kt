package com.litemusic.shared.crypto

import kotlin.random.Random

/**
 * weapi 通道：AES-128-CBC 双层加密 + RSA 公钥加密（secKey 反转后加密，1024 位）。
 * 用于读操作与降级兜底。
 */
object WeapiCrypto {

    // 官方公开固定密钥（与官方客户端一致）
    private const val AES_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val RSA_EXPONENT = "010001"
    // 1024 位 RSA 公钥模数（129 字节 = 258 hex，含前导 00）
    private const val RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725" +
        "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312" +
        "ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d" +
        "813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    private const val CHARSET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    data class Result(val params: String, val encSecKey: String)

    /** 随机 16 位 secKey */
    fun randomSecKey(): String = buildString(16) {
        repeat(16) { append(CHARSET[Random.nextInt(CHARSET.length)]) }
    }

    fun encrypt(params: String, secKey: String = randomSecKey()): Result {
        // 第一层用固定 key，第二层用随机 key
        val first = CryptoProvider.aesCbcEncrypt(params, AES_KEY, IV)
        val second = CryptoProvider.aesCbcEncrypt(first, secKey, IV)
        // 官方实现：secKey 反转后再 RSA 加密
        val encSecKey = CryptoProvider.rsaEncrypt(
            secKey.reversed().toByteArray(Charsets.UTF_8),
            RSA_EXPONENT,
            RSA_MODULUS,
        )
        return Result(params = second, encSecKey = encSecKey)
    }
}

