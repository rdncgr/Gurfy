package com.rdncgr.gurfy.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private val LINK = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)|\*\*([^*]+)\*\*""")

/** "[metin](https://…)" bağlantıya, "**metin**" kalın yazıya dönüşür; bağlantıya dokununca tarayıcı açılır. */
fun linkText(s: String, color: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in LINK.findAll(s)) {
        append(s.substring(at, m.range.first))
        val (label, url, bold) = m.destructured
        if (url.isNotEmpty()) {
            withLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)),
                ),
            ) { append(label) }
        } else {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
        }
        at = m.range.last + 1
    }
    append(s.substring(at))
}

/** Açılıp kapanan, numaralı adım adım anlatım ("Anahtar nasıl alınır?"). */
@Composable
fun Guide(title: String, steps: List<String>, footer: String? = null) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val link = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(title, modifier = Modifier.padding(start = 6.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                steps.forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "${i + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                            color = link, modifier = Modifier.width(24.dp),
                        )
                        Text(linkText(step, link), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (footer != null) {
                    Text(
                        linkText(footer, link), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
