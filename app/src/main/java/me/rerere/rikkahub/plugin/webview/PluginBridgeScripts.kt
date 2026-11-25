/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.content.Context
import android.net.Uri
import android.util.Base64

internal const val bridgeJavascript = """
(function() {
    if (window.__orangeNativeBridgeReady) return;
    window.__orangeNativeBridgeReady = true;
    var pendingUrls = [];
    var navigationBusy = false;
    function sendNext() {
        if (navigationBusy || !pendingUrls.length) return;
        navigationBusy = true;
        window.location.href = pendingUrls.shift();
    }
    window.__bridgeAccepted = function() {
        navigationBusy = false;
        setTimeout(sendNext, 0);
    };

    window.__bridgeCallbacks = {};
    window.__bridgeResultId = 0;

    window.__bridgeResult = function(callbackId, result) {
        if (callbackId && window.__bridgeCallbacks[callbackId]) {
            try {
                window.__bridgeCallbacks[callbackId](result);
            } catch(e) {
                console.error('Bridge callback error:', e);
            }
            delete window.__bridgeCallbacks[callbackId];
        }
    };

    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + (++window.__bridgeResultId);
            window.__bridgeCallbacks[callbackId] = resolve;

            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(callbackId) + '&pageToken=__ORANGE_PAGE_TOKEN__';
            for (var key in params) {
                if (params.hasOwnProperty(key)) {
                    url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                }
            }

            pendingUrls.push(url);
            sendNext();
        });
    }

    window.Bridge = {
        getPluginConfig: function() {
            return bridgeCall('getPluginConfig', {});
        },
        getData: function(key) {
            return bridgeCall('getData', {key: key});
        },
        setData: function(key, value) {
            return bridgeCall('setData', {key: key, value: value});
        },
        deleteData: function(key) {
            return bridgeCall('deleteData', {key: key});
        },
        listData: function() {
            return bridgeCall('listData', {});
        },
        pickImage: function() {
            return bridgeCall('pickImage', {});
        },
        pickImages: function() {
            return bridgeCall('pickImages', {});
        },
        pickFile: function() {
            return bridgeCall('pickFile', {});
        },
        pickBinaryFile: function() {
            return bridgeCall('pickBinaryFile', {});
        },
        importAudioFile: function() {
            return bridgeCall('importAudioFile', {});
        },
        callTool: function(toolName, params) {
            return bridgeCall('callTool', {toolName: toolName, params: params || '{}'});
        },
        writeFile: function(fileName, base64Data) {
            return bridgeCall('writeFile', {fileName: fileName, data: base64Data});
        },
        saveImageAsset: function(assetKey) {
            return bridgeCall('saveImageAsset', {assetKey: assetKey});
        },
        saveFileAs: function(fileName, base64Data) {
            return bridgeCall('saveFileAs', {fileName: fileName, data: base64Data});
        },
        readFile: function(fileName) {
            return bridgeCall('readFile', {fileName: fileName});
        },
        listFiles: function(dirPath) {
            return bridgeCall('listFiles', {dir: dirPath || ''});
        },
        deleteFile: function(fileName) {
            return bridgeCall('deleteFile', {fileName: fileName});
        },
        close: function() {
            bridgeCall('close', {});
        },
        callAI: function(prompt, context) {
            return bridgeCall('callAI', {prompt: prompt, context: context || '{}'});
        },
        listConversations: function() {
            return bridgeCall('listConversations', {});
        },
        uploadChatImage: async function(image) {
            if (!image) return '';
            var transferId = 'ci_' + Date.now() + '_' + Math.random().toString(36).slice(2);
            var chunkSize = 24000;
            for (var offset = 0; offset < image.length; offset += chunkSize) {
                var result = await bridgeCall('chatImageChunk', {
                    transferId: transferId,
                    chunk: image.slice(offset, offset + chunkSize),
                    reset: offset === 0
                });
                if (!result || result.success === false) {
                    throw new Error(result && result.error || 'Image transfer failed');
                }
            }
            return transferId;
        },
        sendChatMessage: function(conversationId, text, hiddenContext, image) {
            if (!image) return bridgeCall('sendChatMessage', {
                conversationId: conversationId, text: text, hiddenContext: hiddenContext || '', image: ''
            });
            return this.uploadChatImage(image).then(function(transferId) {
                return bridgeCall('sendChatMessage', {
                    conversationId: conversationId,
                    text: text,
                    hiddenContext: hiddenContext || '',
                    imageTransferId: transferId
                });
            });
        },
        // Compatibility for installed drawing plugins: only generic chat/image delivery remains.
        listWatchConversations: function() {
            return this.listConversations();
        },
        sendWatchMessage: function(conversationId, text, hiddenContext, image) {
            return this.sendChatMessage(conversationId, text, hiddenContext, image);
        },
        notifyHook: function(hookName, context) {
            return bridgeCall('notifyHook', {hookName: hookName, context: context || '{}'});
        },
        startTimer: function(seconds) {
            return bridgeCall('startTimer', {seconds: seconds});
        },
        stopTimer: function() {
            return bridgeCall('stopTimer', {});
        },
        getTimerState: function() {
            return bridgeCall('getTimerState', {});
        },
        showOverlay: function(html) {
            return bridgeCall('showOverlay', {html: html});
        },
        hideOverlay: function() {
            return bridgeCall('hideOverlay', {});
        },
        musicPlay: function(filePath, title, artist) {
            return bridgeCall('musicPlay', {filePath: filePath, title: title || '', artist: artist || ''});
        },
        musicPause: function() {
            return bridgeCall('musicPause', {});
        },
        musicResume: function() {
            return bridgeCall('musicResume', {});
        },
        musicStop: function() {
            return bridgeCall('musicStop', {});
        },
        musicSeek: function(position) {
            return bridgeCall('musicSeek', {position: position});
        },
        musicGetProgress: function() {
            return bridgeCall('musicGetProgress', {});
        }
    };

    window.OrangeBridge = {
        localData: function(requestJson) {
            return bridgeCall('localData', {requestJson: typeof requestJson === 'string' ? requestJson : JSON.stringify(requestJson)});
        },
        localDataTask: function(requestJson) {
            return bridgeCall('localDataTask', {requestJson: typeof requestJson === 'string' ? requestJson : JSON.stringify(requestJson)});
        },
        localDataPickImages: function() { return bridgeCall('localDataPickImages', {}); },
        localDataHostInfo: function() { return bridgeCall('localDataHostInfo', {}); }
    };
    window.dispatchEvent(new Event('OrangeBridgeReady'));
    window.onTimerEnd = window.onTimerEnd || function() {};
    window.onMusicCompleted = window.onMusicCompleted || function() {};

    console.log('Bridge API initialized');
})();
"""

internal const val overlayBridgeJavascript = """
(function() {
    if (window.__overlayBridgeReady) return;
    window.__overlayBridgeReady = true;
    var pendingUrls = [];
    var navigationBusy = false;
    function sendNext() {
        if (navigationBusy || !pendingUrls.length) return;
        navigationBusy = true;
        window.location.href = pendingUrls.shift();
    }
    window.__overlayBridgeAccepted = function() {
        navigationBusy = false;
        setTimeout(sendNext, 0);
    };

    window.__bridgeCallbacks = {};
    window.__bridgeResultId = 0;

    window.__bridgeResult = function(callbackId, result) {
        if (callbackId && window.__bridgeCallbacks[callbackId]) {
            try {
                window.__bridgeCallbacks[callbackId](result);
            } catch(e) {
                console.error('Bridge callback error:', e);
            }
            delete window.__bridgeCallbacks[callbackId];
        }
    };

    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + (++window.__bridgeResultId);
            window.__bridgeCallbacks[callbackId] = resolve;
            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(callbackId) + '&pageToken=__OVERLAY_PAGE_TOKEN__';
            for (var key in params) {
                if (params.hasOwnProperty(key)) {
                    url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                }
            }
            pendingUrls.push(url);
            sendNext();
        });
    }

    window.Bridge = {
        hideOverlay: function() {
            return bridgeCall('hideOverlay', {});
        },
        getTimerState: function() {
            return bridgeCall('getTimerState', {});
        },
        getData: function(key) {
            return bridgeCall('getData', {key: key});
        },
        setData: function(key, value) {
            return bridgeCall('setData', {key: key, value: value});
        },
        callAI: function(prompt, context) {
            return bridgeCall('callAI', {prompt: prompt, context: context || '{}'});
        }
    };

    window.onTimerEnd = function() {};

    console.log('Overlay Bridge API initialized');
})();
"""

internal fun uriToBase64(context: Context, uri: Uri): String {
    val inputStream = context.contentResolver.openInputStream(uri) ?: return "null"
    val bytes = inputStream.readBytes()
    inputStream.close()
    return "\"" + Base64.encodeToString(bytes, Base64.NO_WRAP) + "\""
}
