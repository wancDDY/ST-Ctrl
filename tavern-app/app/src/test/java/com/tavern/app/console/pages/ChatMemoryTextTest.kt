package com.tavern.app.console.pages

import com.tavern.app.console.AgentClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryTextTest {

    @Test
    fun splitsTheCharacterOffTheChatId() {
        assertEquals("某张卡", memoryChatTitle("某张卡 - 2026-07-13@22h52m07s227ms"))
        assertEquals(
            "某张卡 V1.3",
            memoryChatTitle("某张卡 V1.3 - 2026-08-01@23h17m06s484ms"),
        )
    }

    @Test
    fun keepsAnIdThatCarriesNoStampWhole() {
        assertEquals("没有时间戳的聊天", memoryChatTitle("没有时间戳的聊天"))
        assertEquals("", memoryChatStamp("没有时间戳的聊天"))
    }

    @Test
    fun readsTheStampTheCoreWrites() {
        assertEquals("2026-08-01 23:17", memoryChatStamp("某张卡 - 2026-08-01@23h17m06s484ms"))
    }

    @Test
    fun aNameThatLooksLikeAStampIsNotTheChatTime() {
        // The stamp is only ever the second field; a date inside the character name must not win.
        assertEquals(
            "2026-01-01@00h00m开场",
            memoryChatTitle("2026-01-01@00h00m开场 - 2026-08-01@09h05m01s001ms"),
        )
        assertEquals("2026-08-01 09:05", memoryChatStamp("2026-01-01@00h00m开场 - 2026-08-01@09h05m01s001ms"))
    }

    @Test
    fun readsABranchAsItsOwnField() {
        val branched = "某张卡 - 2026-08-02@06h15m01s442ms - Branch #1"
        assertEquals("某张卡", memoryChatTitle(branched))
        assertEquals("2026-08-02 06:15", memoryChatStamp(branched))
        assertEquals("Branch #1", memoryChatBranch(branched))
        assertEquals("", memoryChatBranch("某张卡 - 2026-07-13@22h52m07s227ms"))
    }

    @Test
    fun translatesTheFailuresTheReaderCanActOn() {
        assertEquals("模型返回空内容", memoryErrorText("empty completion"))
        assertEquals("模型返回内容无法按格式解析", memoryErrorText("unparsable reply: Expected ','"))
        // Two different events, two different sentences: one is worth another try, the other is
        // the same batch answering the same way.
        assertEquals(
            "模型返回说明性文字，未按整理格式输出",
            memoryErrorText("declined: the model answered without an archive"),
        )
        assertEquals("模型响应超时", memoryErrorText("request timed out"))
    }

    @Test
    fun statesTheWaitInTheUnitsTheWaitDeserves() {
        assertEquals("", retryInText(0))
        assertEquals("不到 1 分钟", retryInText(30_000))
        // Rounded up: a wait of ten minutes minus a second must not read as nine, and a
        // countdown that rounded down would show zero right before the retry.
        assertEquals("10 分钟", retryInText(9 * 60_000L + 1))
        // A second short of the hour still reads in minutes, which is how someone would say it.
        assertEquals("60 分钟", retryInText(3_600_000L - 1))
        assertEquals("1 小时", retryInText(3_600_000L))
        assertEquals("6 小时", retryInText(6 * 3_600_000L))
    }

    @Test
    fun leavesAnUnknownFailureAsItIs() {
        assertEquals("socket hang up", memoryErrorText("socket hang up"))
        assertEquals("", memoryErrorText("   "))
    }
}
class GhostHintTest {

    @Test
    fun namesTheModelSwitchOnlyWhileItIsOff() {
        // The line explains what turning this on would do; telling a reader who has already
        // turned the model switch on to turn it on is noise in that sentence.
        assertTrue(ghostHint(enabled = true, takeover = false).endsWith("需先开启「用模型整理」。"))
        assertTrue(ghostHint(enabled = false, takeover = true).endsWith("需先开启「用模型整理」。"))
        val on = ghostHint(enabled = true, takeover = true)
        assertFalse(on.contains("需先开启"))
        assertTrue(on.endsWith("未记录的内容不会发送。"))
    }
}

class UndoTextTest {

    @Test
    fun aStaleUndoIsNotTheSameAsNothingToUndo() {
        // The nothing-to-undo answer would be right about the stack and wrong about the reason:
        // the record exists, the archive it described does not.
        assertTrue(undoText(AgentClient.MemoryUndo(stale = true)).contains("无法撤销"))
        assertEquals("无可撤销的改动", undoText(AgentClient.MemoryUndo()))
        assertEquals("记忆服务未返回结果", undoText(null))
        assertEquals(
            "已撤销：固定了 2 条记忆",
            undoText(AgentClient.MemoryUndo(summary = "固定了 2 条记忆")),
        )
    }
}

class TokenSizeTest {

    @Test
    fun smallCountsStayAsTheyAre() {
        assertEquals("0", tokenSize(0))
        assertEquals("9999", tokenSize(9999))
    }

    @Test
    fun largeCountsReadAsWanWithOneTenth() {
        // The refused request and the window it was measured against sit on one line.
        assertEquals("1 万", tokenSize(10000))
        assertEquals("16.4 万", tokenSize(164263))
        assertEquals("16.4 万", tokenSize(163840))
        assertEquals("10 万", tokenSize(99500))
    }
}

class ProviderErrorTextTest {

    @Test
    fun theWindowIsNamedWithItsNumber() {
        // The provider's sentence is English and the chat page drops it; the number in it is the
        // only thing that tells the reader which setting to change.
        val detail = "This model's maximum context length is 163840 tokens. However, you requested 170023 tokens."
        assertEquals("超过提供方的上下文上限 16.4 万 token（400）", providerErrorText(400, detail))
    }

    @Test
    fun aRefusalWithoutAReasonStillSaysWhatToTry() {
        assertEquals("提供方拒绝了密钥（401），检查 API 设置里的密钥", providerErrorText(401, ""))
        assertEquals("提供方限流（429）", providerErrorText(429, "too many requests"))
        assertEquals("提供方出错（503）", providerErrorText(503, ""))
        assertEquals("提供方拒绝了请求（400）", providerErrorText(400, ""))
        assertEquals("没连上提供方", providerErrorText(0, ""))
    }

    @Test
    fun aNumberThatIsNotALimitIsNotReadAsOne() {
        // A 400 about a missing field carries digits that are not a window size.
        assertEquals("提供方拒绝了请求（400）", providerErrorText(400, "invalid value for seed"))
    }
}

class MemoryErrorShortTest {

    @Test
    fun aBusyProviderSaysSoAndNothingElse() {
        // The row has room for a handful of characters; the full sentence lives on the diagnosis
        // page. "Failed" alone sends the reader there to learn it was a one-minute 503.
        val busy = """HTTP 503 {"error":{"message":"Service is too busy. (request_id: 4683ef8d)"}}"""
        assertEquals("服务过载", memoryErrorShort(busy))
    }

    @Test
    fun eachFailureKeepsItsOwnWord() {
        assertEquals("返回内容被截断", memoryErrorShort("reply truncated (finish_reason=length)"))
        assertEquals("返回格式不符", memoryErrorShort("declined: the model answered without cue words"))
        assertEquals("返回内容无法解析", memoryErrorShort("unparsable reply: unexpected end of input"))
        assertEquals("返回内容为空", memoryErrorShort("empty completion"))
        assertEquals("响应超时", memoryErrorShort("request timed out after 120000ms"))
        assertEquals("密钥无效", memoryErrorShort("HTTP 401 unauthorized"))
        assertEquals("服务限流", memoryErrorShort("HTTP 429 rate limited"))
        assertEquals("服务拒绝请求", memoryErrorShort("HTTP 400 bad request"))
    }

    @Test
    fun aFailureWithNoStatusIsNotInvented() {
        assertEquals("原因见「诊断」页", memoryErrorShort("something odd happened"))
        assertEquals("", memoryErrorShort("   "))
    }
}
