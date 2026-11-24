package me.rerere.rikkahub.data.video

/** Immutable capture authorization, carried by the queue through every tool continuation. */
data class VideoFrameRequestScope(
    val conversationId: String,
    val requestToken: String,
    val callEpoch: Long,
    val cameraEpoch: Long,
    val systemPromptWithoutCamera: String? = null,
)

internal fun videoFrameAuthorization(
    urls: List<String>,
    anchorMessageId: String?,
    scope: VideoFrameRequestScope?,
    conversationId: String?,
    requestToken: String?,
    revokedContext: String? = scope?.systemPromptWithoutCamera,
) = VideoRequestImageAuthorization(urls, anchorMessageId, revokedContext) {
    scope != null && scope.conversationId == conversationId && scope.requestToken == requestToken &&
        VideoCallRuntime.isFrameRequestScopeCurrent(scope)
}
