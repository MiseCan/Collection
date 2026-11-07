@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Official AppBarSamples pattern, adapted to the app's navigation callbacks. */
@Composable
fun CollectionTopBar(title: String, onBack: () -> Unit, scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    MediumFlexibleTopAppBar(
        modifier = modifier,
        title = { if (titleContent != null) titleContent() else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        scrollBehavior = scrollBehavior,
    )
}

/** RadioButtonSamples: the whole row is selectable and owns the single accessibility action. */
@Composable
fun ImportChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
