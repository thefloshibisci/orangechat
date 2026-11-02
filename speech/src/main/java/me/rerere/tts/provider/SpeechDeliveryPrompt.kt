package me.rerere.tts.provider

/** A request-only call instruction: no model or secret is written into the conversation. */
fun speechDeliveryPrompt(provider: TTSProviderSetting?): String {
    val model = (provider as? TTSProviderSetting.MiniMax)?.model
    val known = model in setOf(
        "speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo",
        "speech-02-hd", "speech-02-turbo", "speech-01-hd", "speech-01-turbo",
    )
    if (!known) return "当前正在语音通话，请直接用自然口语简短回复，不要调用tts工具重复朗读，不要输出语音控制标签。"
    return buildString {
        append("当前正在语音通话，回复会自动逐句朗读，不要调用tts工具重复播放。直接说话，避免Markdown。")
        append("你可以为每句话选择表达：[[voice emotion=happy speed=0.9 pitch=-2 volume=1]]这句话。[[/voice]]")
        append("每个voice块只写一句，必须闭合；不同句子可用不同设置，也可直接输出普通文字。")
        append("所有属性可省略；emotion可选auto(自动)、happy、sad、angry、fearful、disgusted、surprised、calm；")
        if (model in setOf("speech-2.6-hd", "speech-2.6-turbo")) append("还可用fluent、whisper；")
        append("speed=0.5~2，pitch为-12~12整数(音高偏移，不是逐字声调)，volume大于0且不超过10。")
        append("可在一句的可发音文字之间用<#0.3#>停顿0.3秒(0.01~99.99，最多两位小数，不可连续或首尾使用)。")
        if (model in setOf("speech-2.8-hd", "speech-2.8-turbo")) {
            append("支持行内语气词(laughs)、(chuckle)、(sighs)、(breath)、(pant)、(inhale)、(exhale)、(gasps)、")
            append("(sniffs)、(coughs)、(clear-throat)、(groans)、(snorts)、(burps)、(lip-smacking)、(humming)、(hissing)、(emm)、(sneezes)。")
            append("当前模型不支持whisper/fluent。")
        } else append("当前模型不支持笑声/呼吸等英文语气标签，不要输出它们。")
        append("自然的嗯、啊、呀等口语文字也可以直接写进正文。按语境适量使用，不必每句都加标签。")
    }
}
