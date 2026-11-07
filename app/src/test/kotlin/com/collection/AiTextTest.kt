package com.collection

import com.collection.ai.chunkText
import com.collection.ai.validateOutput
import org.junit.Assert.*
import org.junit.Test

class AiTextTest {
    @Test fun chunkingKeepsEveryCharacterAndUnicodePairs() {
        val input = ("段落资料🙂\n".repeat(30)) + "末尾事实"
        val chunks = chunkText(input, 23)
        assertEquals(input, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 23 })
        assertTrue(chunks.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
    }
    @Test fun blankAndWrongTypesAreRejected() {
        for (input in listOf("   ", "{}", "{\"title\":3,\"summary\":\"内容\"}", "{\"title\":\" \",\"summary\":\"内容\"}")) {
            assertThrows(IllegalArgumentException::class.java) { validateOutput(input, "stop") }
        }
    }
    @Test fun incompleteOutputIsNeverAccepted() {
        assertThrows(IllegalArgumentException::class.java) { validateOutput("{\"title\":\"资料\",\"summary\":\"事实\"}", "length") }
    }
    @Test fun validChineseFieldsArePreserved() {
        val value = validateOutput("{\"title\":\" 实验数据 \"}", "stop")
        assertEquals("实验数据", value.title)

    }
}
