package com.slopware.androidllm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

sealed interface MarkdownBlock {
    data class Text(val text: String) : MarkdownBlock
    data class Code(val code: String, val language: String = "") : MarkdownBlock
}

private val INLINE_TOKEN_REGEX = Regex("""`([^`\n]+)`|\*\*(.+?)\*\*""")

/**
 * Splits streaming Markdown on triple-backtick fences instead of importing a full AST parser.
 *
 * Why: Full Markdown AST libraries add heavy dependency bloat and flicker when an opening ```
 * fence has streamed in before its closing ``` arrives. Odd fence indices are treated as code
 * blocks immediately (auto-closing an open trailing fence mid-stream).
 */
fun splitStreamingBlocks(raw: String): List<MarkdownBlock> {
    if (raw.isEmpty()) return emptyList()
    val parts = raw.split("```")
    val hasOpenTrailingFence = parts.size % 2 == 0
    return buildList {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 0) {
                val text = part.trim('\n', '\r')
                if (text.isNotBlank()) add(MarkdownBlock.Text(text))
            } else {
                val nl = part.indexOf('\n')
                val block = when {
                    nl >= 0 -> MarkdownBlock.Code(
                        code = part.substring(nl + 1).trimEnd('\n', '\r'),
                        language = part.substring(0, nl).trim(),
                    )
                    hasOpenTrailingFence && index == parts.lastIndex && !part.contains(' ') ->
                        MarkdownBlock.Code(code = "", language = part.trim())
                    else -> MarkdownBlock.Code(code = part.trimEnd('\n', '\r'))
                }
                add(block)
            }
        }
    }
}

/**
 * Styles inline `code` spans and **bold** text into a Compose [AnnotatedString].
 * Unclosed delimiters mid-stream simply fall through as plain text without flickering.
 */
fun parseInlineMarkdown(
    text: String,
    codeBackground: Color = Color(0x24888888),
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE_TOKEN_REGEX.findAll(text)) {
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val inlineCode = match.groups[1]?.value
        val bold = match.groups[2]?.value
        when {
            inlineCode != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
            ) { append(inlineCode) }
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

@Composable
fun StreamingMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onCopyCode: (String) -> Unit = {},
) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val blocks = remember(markdown) { splitStreamingBlocks(markdown) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Text -> {
                    val styled = remember(block.text, codeBg) {
                        parseInlineMarkdown(block.text, codeBg)
                    }
                    Text(
                        text = styled,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                is MarkdownBlock.Code -> CodeBlockCard(
                    code = block.code,
                    language = block.language,
                    onCopy = { onCopyCode(block.code) },
                )
            }
        }
    }
}

@Composable
private fun CodeBlockCard(
    code: String,
    language: String,
    onCopy: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                text = code,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
