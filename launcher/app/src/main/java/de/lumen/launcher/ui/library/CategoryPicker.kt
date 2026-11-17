package de.lumen.launcher.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import de.lumen.launcher.data.apps.AppCategory
import de.lumen.launcher.data.apps.AppKey
import de.lumen.launcher.data.apps.CustomCategory
import de.lumen.launcher.data.apps.LibraryModel
import de.lumen.launcher.ui.LauncherController
import de.lumen.launcher.ui.glass.Backdrop
import de.lumen.launcher.ui.glass.GlassCapsuleButton
import de.lumen.launcher.ui.glass.GlassGroup
import de.lumen.launcher.ui.glass.GlassRow
import de.lumen.launcher.ui.glass.GlassSheet
import de.lumen.launcher.ui.glass.SectionHeader
import de.lumen.launcher.ui.home.libraryCategoryRequest
import de.lumen.launcher.ui.theme.Lumen
import de.lumen.launcher.ui.theme.LumenType

/** Sheet to move an app to another App Library category or into an own category. */
@Composable
fun CategoryPickerSheet(c: LauncherController, backdrop: Backdrop) {
    val key: AppKey? = libraryCategoryRequest
    var lastKey by remember { mutableStateOf<AppKey?>(null) }
    if (key != null) lastKey = key
    val k = lastKey
    val lib by c.graph.library.state.collectAsState()
    val app = k?.let { c.graph.apps.find(it) }
    GlassSheet(visible = key != null, onDismiss = { libraryCategoryRequest = null }, backdrop = backdrop, title = app?.let { "Kategorie für „${it.label}“" } ?: "Kategorie") {
        if (k == null || app == null) return@GlassSheet
        val current = LibraryModel.categoryOf(app, lib)
        val inCustom = lib.customCategories.firstOrNull { k in it.apps }
        var newName by remember { mutableStateOf("") }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            SectionHeader("Eigene Kategorien")
            GlassGroup {
                lib.customCategories.forEach { cc ->
                    GlassRow(cc.name, trailing = { if (inCustom?.id == cc.id) Icon(Icons.Rounded.Check, null, tint = Lumen.colors.accent) }, onClick = {
                        c.graph.library.update { st ->
                            st.copy(customCategories = st.customCategories.map {
                                if (it.id == cc.id) it.copy(apps = (it.apps - k) + k) else it.copy(apps = it.apps - k)
                            })
                        }
                        libraryCategoryRequest = null
                    })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CreateNewFolder, null, tint = Lumen.colors.accent)
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        if (newName.isEmpty()) androidx.compose.foundation.text.BasicText("Neue Kategorie", style = LumenType.body.copy(color = Lumen.colors.textSecondary))
                        BasicTextField(newName, { newName = it.take(30) }, singleLine = true, textStyle = LumenType.body.copy(color = Lumen.colors.textPrimary), cursorBrush = SolidColor(Lumen.colors.accent), modifier = Modifier.fillMaxWidth())
                    }
                    if (newName.isNotBlank()) {
                        GlassCapsuleButton("Anlegen", prominent = true) {
                            c.graph.library.update { st ->
                                val id = (st.customCategories.maxOfOrNull { it.id } ?: 0) + 1
                                st.copy(customCategories = st.customCategories.map { it.copy(apps = it.apps - k) } + CustomCategory(id, newName.trim(), listOf(k)))
                            }
                            libraryCategoryRequest = null
                        }
                    }
                }
            }
            SectionHeader("Automatische Kategorien")
            GlassGroup {
                AppCategory.entries.forEachIndexed { i, cat ->
                    GlassRow(
                        cat.title,
                        showDivider = i < AppCategory.entries.lastIndex,
                        trailing = { if (inCustom == null && cat == current) Icon(Icons.Rounded.Check, null, tint = Lumen.colors.accent) },
                        onClick = {
                            c.graph.library.update { st ->
                                val overrides = if (cat == app.systemCategory) st.categoryOverrides - k.value else st.categoryOverrides + (k.value to cat)
                                st.copy(categoryOverrides = overrides, customCategories = st.customCategories.map { it.copy(apps = it.apps - k) })
                            }
                            libraryCategoryRequest = null
                        },
                    )
                }
            }
        }
    }
}
