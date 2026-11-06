package me.rerere.ai.provider

/** Mirrors the request builders, including the subscription and Google legacy transports. */
enum class FunctionToolUnavailableReason { CAPABILITY_DISABLED, GOOGLE_BUILT_INS }

fun Model.functionToolUnavailableReason(provider: ProviderSetting?): FunctionToolUnavailableReason? = when {
    provider is ProviderSetting.Codex -> null
    ModelAbility.TOOL !in abilities -> FunctionToolUnavailableReason.CAPABILITY_DISABLED
    provider is ProviderSetting.Google && !provider.useInteractionsApi && tools.isNotEmpty() ->
        FunctionToolUnavailableReason.GOOGLE_BUILT_INS
    else -> null
}
