/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.serein

import okhttp3.OkHttpClient

/**
 * Dedicated bare OkHttpClient shared across Serein recall/upload/observation clients.
 * Deliberately carries no chat logging interceptors or redirects, while avoiding per-client
 * and per-worker thread/connection pool leaks.
 */
internal object SereinHttp {
    val client: OkHttpClient by lazy { OkHttpClient.Builder().build() }
}
