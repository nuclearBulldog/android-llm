package com.slopware.androidllm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.slopware.androidllm.ui.MarkdownBlock
import com.slopware.androidllm.ui.parseInlineMarkdown
import com.slopware.androidllm.ui.splitStreamingBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTest {
    @Test
    fun emptyAndPlainTextReturnExpectedBlocks() {
        assertEquals(emptyList<MarkdownBlock>(), splitStreamingBlocks(""))
        assertEquals(emptyList<MarkdownBlock>(), splitStreamingBlocks("  \n  "))
        assertEquals(
            listOf(MarkdownBlock.Text("Hello **world**")),
            splitStreamingBlocks("Hello **world**"),
        )
    }

    @Test
    fun unclosedTrailingFenceAutoClosesDuringTokenByTokenStreaming() {
        assertEquals(
            listOf(
                MarkdownBlock.Text("Intro"),
                MarkdownBlock.Code(code = "", language = ""),
            ),
            splitStreamingBlocks("Intro\n```"),
        )
        assertEquals(
            listOf(
                MarkdownBlock.Text("Intro"),
                MarkdownBlock.Code(code = "", language = "kotlin"),
            ),
            splitStreamingBlocks("Intro\n```kotlin"),
        )
        assertEquals(
            listOf(
                MarkdownBlock.Text("Intro"),
                MarkdownBlock.Code(code = "val x = 1", language = "kotlin"),
            ),
            splitStreamingBlocks("Intro\n```kotlin\nval x = 1"),
        )
        assertEquals(
            listOf(
                MarkdownBlock.Text("Intro"),
                MarkdownBlock.Code(code = "val x = 1", language = "kotlin"),
            ),
            splitStreamingBlocks("Intro\n```kotlin\nval x = 1\n```"),
        )
    }

    @Test
    fun multipleCodeBlocksClosedAndTrailingOpen() {
        val complete = """
            First
            ```kt
            println(1)
            ```
            Middle
            ```py
            print(2)
            ```
            End
        """.trimIndent()
        assertEquals(
            listOf(
                MarkdownBlock.Text("First"),
                MarkdownBlock.Code("println(1)", "kt"),
                MarkdownBlock.Text("Middle"),
                MarkdownBlock.Code("print(2)", "py"),
                MarkdownBlock.Text("End"),
            ),
            splitStreamingBlocks(complete),
        )

        val secondOpen = "First\n```kt\nprintln(1)\n```\nMiddle\n```py\nprint(2)"
        assertEquals(
            listOf(
                MarkdownBlock.Text("First"),
                MarkdownBlock.Code("println(1)", "kt"),
                MarkdownBlock.Text("Middle"),
                MarkdownBlock.Code("print(2)", "py"),
            ),
            splitStreamingBlocks(secondOpen),
        )
    }

    @Test
    fun parseInlineMarkdownStylesCodeAndBold() {
        val codeBg = Color(0x11223344)
        val styled = parseInlineMarkdown("Use `val x = 1` and **bold** text", codeBg)

        assertEquals("Use val x = 1 and bold text", styled.text)
        assertEquals(2, styled.spanStyles.size)

        val codeSpan = styled.spanStyles[0]
        assertEquals(4, codeSpan.start)
        assertEquals(13, codeSpan.end)
        assertEquals(FontFamily.Monospace, codeSpan.item.fontFamily)
        assertEquals(codeBg, codeSpan.item.background)

        val boldSpan = styled.spanStyles[1]
        assertEquals(18, boldSpan.start)
        assertEquals(22, boldSpan.end)
        assertEquals(FontWeight.Bold, boldSpan.item.fontWeight)
    }

    @Test
    fun parseInlineMarkdownHandlesUnclosedDelimitersWithoutFlicker() {
        val unclosedCode = parseInlineMarkdown("Typing `partial")
        assertEquals("Typing `partial", unclosedCode.text)
        assertTrue(unclosedCode.spanStyles.isEmpty())

        val unclosedBold = parseInlineMarkdown("Typing **partial")
        assertEquals("Typing **partial", unclosedBold.text)
        assertTrue(unclosedBold.spanStyles.isEmpty())
    }
}
