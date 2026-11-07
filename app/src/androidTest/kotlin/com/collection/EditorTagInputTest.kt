package com.collection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.collection.domain.Entry
import com.collection.domain.Tag
import com.collection.ui.CollTheme
import com.collection.ui.EditorScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Optional instrumented coverage for inline tag input; not run on the user phone. */
@RunWith(AndroidJUnit4::class)
class EditorTagInputTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun editorCreatesTrimmedTagSelectsItAndPreventsDuplicateName() {
        var draft by mutableStateOf(Entry(body = "需要整理的文字资料"))
        var tags by mutableStateOf(emptyList<Tag>())
        var createdName: String? = null
        var creationCalls = 0
        compose.setContent {
            CollTheme(dark = false) {
                EditorScreen(draft, tags, false, "", false,
                    onChange = { value, _ -> draft = value }, onBack = {}, onAddFiles = {},
                    onPreview = {}, onRemove = {}, onGenerate = {}, onCancelGeneration = {},
                    onAddTag = { name ->
                        creationCalls++
                        createdName = name
                        val newTag = Tag(name = name)
                        tags = tags + newTag
                        draft = draft.copy(tagIds = draft.tagIds + newTag.id)
                    }, onSave = {})
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("添加标签"))
        compose.onNodeWithContentDescription("添加标签").performClick()
        compose.onNode(hasSetTextAction() and hasText("标签名称")).performTextInput("  实验计划  ")
        compose.onNodeWithContentDescription("创建标签").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("实验计划", createdName)
            assertEquals(1, creationCalls)
            assertEquals(setOf(tags.single().id), draft.tagIds)
        }
        compose.onNodeWithText("实验计划").assertIsSelected()
        compose.onNodeWithContentDescription("添加标签").performClick()
        compose.onNode(hasSetTextAction() and hasText("标签名称")).performTextInput("实验计划")
        compose.onNodeWithText("标签已存在，请直接选择").assertExists()
        compose.onNodeWithContentDescription("创建标签").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, creationCalls) }
        compose.onNodeWithContentDescription("取消添加标签").performClick()
    }
}
